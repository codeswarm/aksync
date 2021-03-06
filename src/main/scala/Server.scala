package org.codeswarm.aksync

import akka.actor.{Actor, ActorLogging, ActorRef, Cancellable}
import scala.collection.mutable.{Queue, HashMap, Stack}
import scala.concurrent.duration.FiniteDuration

import Server._

/** A `Server` mediates access to a pool of "tokens", responding to each client request by
  * issuing a lease that grant temporary exclusive access to one token until the lease is
  * released.
  *
  * A client actor initiates communication with the server by sending the [[Lease.Request]]
  * message. The requestor's `ActorRef` is held in a FIFO queue until a token is available,
  * at which point the server replies with a [[Lease]]. The client is required to
  * [[Lease.Acknowledge acknowledge]] the lease, and to [[Lease.Release release]] it when
  * finished using it. [[Lease.apply]] is a convenient way to ensure that your clients handles
  * its leases properly.
  *
  * @tparam Token Token type. This is typically a resource cannot be used concurrently, such
  * as a database connection.
  *
  * @param lifecycle Strategy for creating and destroying tokens.
  * @param poolSizeRange Minimum and maximum number of tokens in the pool. Defaults to 2-8.
  * @param poolStructure Constructor for the collection data structure which will hold unleased
  * tokens. Defaults to [[Pool.Stack]]. Another good choice is [[Pool.Queue]].
  * @param leaseTimeout Amount of time that a lease is allowed to persist without acknowledgement.
  * Defaults to a short time for the first acknowledgement and a longer duration subsequently.
  * @param tokenRetryInterval Amount of time to wait between retries when token creation fails.
  * Defaults to an exponential backoff.
  * @param leaseTransform A function to apply to outgoing `Lease` messages. Defaults to the
  * identity. This can be a useful way to work around limitations in pattern-matching on
  * generic types that poses a problem when trying to receive the message type `Lease[Token]`.
  */
class Server[Token](lifecycle: Lifecycle[Token], poolSizeRange: PoolSizeRange = 2 to 8,
    poolStructure: => Pool[Token] = new Pool.Stack[Token](),
    leaseTimeout: LeaseTimeout = LeaseTimeout.FirstAndSubsequent(),
    tokenRetryInterval: TokenRetryInterval = TokenRetryInterval.ExponentialBackoff(),
    leaseTransform: (Lease[Token]) => Any = conforms[Lease[Token]])
    (implicit tokenManifest: Manifest[Token]) extends Actor with ActorLogging {

  val system = context.system
  import system.{dispatcher, scheduler}

  private val clients = Queue[ActorRef]()
  private val tokens = poolStructure
  private val leases = HashMap[StandardLease[Token], LeaseState]()
  private var tokenCreationState: TokenCreationState = TokenCreationState.NotDoingAnything
  private val leaseIds = (1 to Int.MaxValue).iterator
  private val lifecycleActor = lifecycle.actor

  override def preStart() {
    self ! Internal.MaybeRequestToken
  }

  def receive = {

    case Lease.Request =>

      log debug "Received Lease.Request"

      if (poolSizeRange.isZero) {
        /* If the pool size is fixed at 0, this server will never do anything,
           so there is no point in enqueuing any requests. */
      } else {

        clients enqueue sender

        self ! Internal.MaybeIssueLeases
        self ! Internal.MaybeRequestToken
      }

    case Lease.Acknowledge(lease: StandardLease[Token]) =>

      log debug "Received Lease.Acknowledge[%d]".format(lease.id)

      leases.get(lease) match {
        case Some(state) => state.ack()
        case None => log warning "Received Acknowledge for unknown lease from %s".format(sender)
      }

    case Lease.Release(lease: StandardLease[Token]) =>

      log debug "Received Lease.Release[%d]".format(lease.id)

      leases.remove(lease) match {
        case Some(state) => tokens add lease.token
        case None => log warning "Received Release for unknown lease from %s".format(sender)
      }

      self ! Internal.MaybeIssueLeases

    case Internal.MaybeIssueLeases =>

      createLease() match {

        case Some(lease) =>

          log debug "Issuing %s".format(lease)

          leases += lease -> new LeaseState(lease)
          lease.client ! leaseTransform(lease)
          self ! Internal.MaybeIssueLeases

        case None =>
      }

      self ! Internal.MaybeRequestToken

    case Internal.MaybeRevoke(lease: StandardLease[Token], nrOfAcks: Int) =>

      leases.get(lease) foreach { state =>
        if (state.nrOfAcks == nrOfAcks) {
          log warning "Revoking %s (acks: %d) that was issued to %s".
            format(lease, nrOfAcks, lease.client)
          leases -= lease
          lifecycleActor ! Lifecycle.Revoked(lease.token)
          self ! Internal.MaybeRequestToken
        }
      }

    case Internal.MaybeRequestToken =>

      if (tokenCreationState == TokenCreationState.NotDoingAnything) {

        val size = tokens.size + leases.size

        val needsAnotherToken = (poolSizeRange requiresMoreThan size) ||
          (clients.nonEmpty && (poolSizeRange allowsMoreThan size))

        if (needsAnotherToken) {
          tokenCreationState = TokenCreationState.AnticipatingNewToken()
          self ! Internal.RequestToken
        }
      }

    case Internal.RequestToken =>

      log debug "Sending Lifecycle.TokenRequest"

      lifecycleActor ! Lifecycle.TokenRequest

    case Lifecycle.NewToken(token) =>

      log debug "Received Lifecycle.NewToken"

      if (!tokenManifest.runtimeClass.isAssignableFrom(token.getClass)) {
        log warning "Received NewToken of incorrect type %s".format(token.getClass)
      } else {
        tokenCreationState match {
          case TokenCreationState.NotDoingAnything =>
            log warning "Received unexpected NewToken from %s".format(sender)
          case _: TokenCreationState.AnticipatingNewToken =>
            tokenCreationState = TokenCreationState.NotDoingAnything
            tokens add token.asInstanceOf[Token]
            self ! Internal.MaybeIssueLeases
            self ! Internal.MaybeRequestToken
        }
      }

    case Lifecycle.TokenUnavailable =>

      log debug("Received Lifecycle.TokenUnavailable")

      tokenCreationState match {
        case TokenCreationState.NotDoingAnything =>
          log warning "Received unexpected TokenUnavailable from %s".format(sender)
        case x: TokenCreationState.AnticipatingNewToken =>
          tokenCreationState = x.fail
          scheduler.scheduleOnce(
            delay = tokenRetryInterval(tokenCreationState.nrOfFails),
            receiver = self,
            message = Internal.RequestToken
          )
      }

    case m =>

      log warning "Received unrecognized message: %s".format(m)

  }

  private def createLease(): Option[StandardLease[Token]] = {

    // Remove terminated requestors to avoid wasting time issuing a lease to a dead actor.
    // This does not guarantee that it will never happen (there is a race condition), but
    // it's unlikely.
    while (clients.headOption.exists(_.isTerminated)) {
      log debug "Removing dead actor"
      clients.dequeue()
    }

    // No one is currently waiting for a lease.
    if (clients.isEmpty) {
      log debug "There are no requestors waiting"
      return None
    }

    // Remove dead tokens as a best effort toward avoiding giving a client a dead token
    // (for example, if the token is a database connection that has timed out).
    while (tokens.peek.exists(lifecycle.isDead(_))) {
      log debug "Removing dead token"
      lifecycleActor ! Lifecycle.Dead(tokens.remove())
    }

    // There are no free connections available.
    if (tokens.isEmpty) {
      log debug "There are no tokens available for lease"
      return None
    }

    // Create a new lease.
    Some(new StandardLease(
      token = tokens.remove(),
      id = leaseIds.next(),
      client = clients.dequeue(),
      server = self
    ))

  }

  /** State about a lease that is used internally by the server to manage lease expiration.
    */
  private class LeaseState(lease: Lease[Token]) {

    // The currently-running expiration timer, if there is one. This is None if the lease
    // will never be revoked due to an indefinite timeout duration.
    private var timer: Option[Cancellable] = None

    // The number of times this lease has been acknowledged.
    private[Server] var nrOfAcks = 0

    setTimer()

    def ack() {
      nrOfAcks += 1
      timer foreach (_.cancel())
      setTimer()
    }

    private def setTimer() {
      timer = leaseTimeout(nrOfAcks) match {
        case delay: FiniteDuration =>
          Some(scheduler.scheduleOnce(
            delay = delay,
            receiver = self,
            message = Internal.MaybeRevoke(lease, nrOfAcks)
          ))
        case _ =>
          None
      }
    }

  }

}
private object Server {

  /** Messages that the server sends to itself.
    */
  private object Internal {

    case object MaybeIssueLeases

    /** @param nrOfAcks The number of acknowledgements the lease had at the time this message
      * was scheduled. When this message is received, if the lease has not been acknowledged
      * since then (its `nrOfAcks` has not changed), then the lease shall be revoked.
      */
    case class MaybeRevoke(lease: Lease[_], nrOfAcks: Int)

    case object MaybeRequestToken

    case object RequestToken

  }

  /** An enumeration of where the server is in its conversation with the lifecycle actor
    * in regards to creating new tokens.
    */
  private trait TokenCreationState {

    def nrOfFails: Int = 0
  }

  private object TokenCreationState {

    /** Nothing is going on. We're not interested in getting new tokens, and the lifecycle
      * actor shouldn't be doing anything.
      */
    case object NotDoingAnything extends TokenCreationState

    /** A token has been requested, and we are waiting for the lifecycle actor to reply.
      */
    case class AnticipatingNewToken(override val nrOfFails: Int = 0) extends TokenCreationState {

      def fail = AnticipatingNewToken(nrOfFails + 1)
    }

  }

}