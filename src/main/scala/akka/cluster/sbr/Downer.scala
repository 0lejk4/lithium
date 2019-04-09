package akka.cluster.sbr

import akka.actor.{Actor, ActorContext, ActorLogging, Address, Cancellable, Props}
import akka.cluster.Cluster
import akka.cluster.ClusterEvent._
import akka.cluster.MemberStatus.{Down, Removed}
import akka.cluster.pubsub.DistributedPubSub
import akka.cluster.pubsub.DistributedPubSubMediator.{Put, SendToAll}
import akka.cluster.sbr.implicits._
import akka.cluster.sbr.strategies.Or
import akka.cluster.sbr.strategies.downall.DownAll
import akka.cluster.sbr.strategies.indirected.Indirected
import akka.cluster.sbr.strategy.Strategy
import akka.cluster.sbr.strategy.ops._
import cats.effect.SyncIO
import cats.implicits._

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

class Downer[A: Strategy](cluster: Cluster,
                          strategy: A,
                          stableAfter: FiniteDuration,
                          downAllWhenUnstable: FiniteDuration)
    extends Actor
    with ActorLogging {

  import Downer._

  // TODO is this one ok?
  implicit private val ec: ExecutionContext = context.system.dispatcher

  // If a node receive a unreachability event in his name it means that it is
  // indirectly connected. It is unreachable via a link but reachable via another as
  // it receive the event.
  // As cluster events are only gossiped to reachable nodes,
  // a node that has been detected as unreachable will never receive an unreachability
  // event in his name.
  private val mediator = DistributedPubSub(cluster.system).mediator
  mediator ! Put(self)

  override def receive: Receive = waitingForSnapshot.andThen(_.unsafeRunSync())

  /**
   * Waits for the state snapshot we should get after having
   * subscribed to the cluster's state with the initial
   * state as snapshot.
   */
  private def waitingForSnapshot: IOReceive = {
    case state: CurrentClusterState =>
      val worldView = WorldView(cluster.selfMember, state)

      for {
        clusterIsStable <- scheduleClusterIsStable
        instability     <- scheduleClusterIsUnstable

        notifier <- worldView.unreachableNodes.foldLeft(SyncIO.pure(Notifier(send))) {
          case (notifier, node) =>
            notifier.flatMap(notifyIfReachable(_, UnreachableMember(node.member)))
        }

        _ <- become(hasSplitBrain(worldView, clusterIsStable, instability, notifier))
      } yield ()

    case _ => become(waitingForSnapshot) // ignore
  }

  // todo need to send reachable?
  private def notifyIfReachable(notifier: ReachabilityNotifier, e: ReachabilityEvent): SyncIO[ReachabilityNotifier] =
    if (!cluster.failureDetector.isAvailable(e.member.address) || e.member.status == Down || e.member.status == Removed)
      SyncIO(log.debug(s"[notify-if-reachable] Not notifying. $e")) >> SyncIO.pure(notifier)
    else {
      // only notify available and non-exiting members.
      SyncIO(log.debug(s"[notify-if-reachable] Notifying. $e")) >> notifier.send(e, e.member.address)
    }

  private def noSplitBrain(worldView: WorldView,
                           clusterIsStable: Cancellable,
                           notifier: ReachabilityNotifier): IOReceive = {
    def resetWhenUnstable(newWorldView: WorldView): SyncIO[Cancellable] =
      when(newWorldView.isStableChange(worldView))(
        SyncIO(log.debug(s"[reset-when-unstable] Stable")) >> SyncIO.pure(clusterIsStable),
        SyncIO(log.debug(s"[reset-when-unstable] Unstable")) >> cancel(clusterIsStable) >> scheduleClusterIsStable
      )

    def stateTransition(newWorldView: WorldView, notifier: ReachabilityNotifier): SyncIO[Unit] =
      for {
        clusterIsStable <- resetWhenUnstable(newWorldView)
        _ <- when(newWorldView.hasSplitBrain)(toHasSplitBrain(newWorldView, clusterIsStable, notifier).flatMap(become),
                                              become(noSplitBrain(newWorldView, clusterIsStable, notifier)))
      } yield ()

    def splitBrainResolver_[A: Strategy](a: A): SyncIO[Unit] =
      splitBrainResolver(
        cancel(clusterIsStable),
        scheduleClusterIsStable.flatMap(c => become(noSplitBrain(worldView, c, notifier)))
      )(worldView, a)

    def memberEvent(event: MemberEvent): SyncIO[WorldView]             = SyncIO.pure(worldView.memberEvent(event))
    def reachabilityEvent(event: ReachabilityEvent): SyncIO[WorldView] = SyncIO.pure(worldView.reachabilityEvent(event))

    {
      case e: MemberEvent =>
        for {
          _         <- SyncIO(log.debug("{}", e))
          worldView <- memberEvent(e)
          _         <- stateTransition(worldView, notifier)
        } yield ()

      case e: ReachabilityEvent =>
        for {
          _         <- SyncIO(log.debug("{}", e))
          worldView <- reachabilityEvent(e)
          notifier  <- notifyIfReachable(notifier, e)
          _         <- stateTransition(worldView, notifier)
        } yield ()

      case r @ ReachabilityNotification(event, _) =>
        if (event.member === cluster.selfMember) {
          for {
            _         <- SyncIO(log.debug("{}", r))
            _         <- SyncIO(mediator ! SendToAll(self.path.toStringWithoutAddress, r.ack, allButSelf = false))
            worldView <- reachabilityEvent(event)
            _         <- stateTransition(worldView, notifier)
          } yield ()
        } else {
          // notification is not for this member
          for {
            _ <- SyncIO(log.warning("Ignore notification for another node. {}", r))
            _ <- become(noSplitBrain(worldView, clusterIsStable, notifier))
          } yield ()
        }

      case r @ ReachabilityNotificationAck(event, ackN) =>
        for {
          _        <- SyncIO(log.debug("{}", r))
          notifier <- notifier.ack(ackN, event.member.address)
          _        <- become(noSplitBrain(worldView, clusterIsStable, notifier))
        } yield ()

      case ClusterIsStable =>
        SyncIO(log.debug("Cluster is stable, resolving...")) >> splitBrainResolver_(Or(strategy, Indirected))

      case ClusterIsUnstable =>
        SyncIO(log.warning("[no-split-brain] Received unstable cluster event."))
    }
  }

  private def hasSplitBrain(worldView: WorldView,
                            clusterIsStable: Cancellable,
                            clusterIsUnstable: Cancellable,
                            notifier: ReachabilityNotifier): IOReceive = {
    def resetWhenUnstable(newWorldView: WorldView): SyncIO[Cancellable] =
      when(newWorldView.isStableChange(worldView))(
        SyncIO(log.debug(s"[reset-when-unstable] Stable")) >> SyncIO.pure(clusterIsStable),
        SyncIO(log.debug(s"[reset-when-unstable] Unstable")) >> cancel(clusterIsStable) >> scheduleClusterIsStable
      )

    def stateTransition(newWorldView: WorldView, notifier: ReachabilityNotifier): SyncIO[Unit] =
      for {
        clusterIsStable <- resetWhenUnstable(newWorldView)
        _ <- when(newWorldView.hasSplitBrain)(
          become(hasSplitBrain(newWorldView, clusterIsStable, clusterIsUnstable, notifier)),
          toNoSplitBrain(newWorldView, clusterIsStable, clusterIsUnstable, notifier).flatMap(become)
        )
      } yield ()

    def splitBrainResolver_[A: Strategy](a: A): SyncIO[Unit] =
      splitBrainResolver(
        cancel(clusterIsStable) >> cancel(clusterIsUnstable), {
          for {
            clusterIsStable   <- scheduleClusterIsStable
            clusterIsUnstable <- scheduleClusterIsUnstable
            _                 <- become(hasSplitBrain(worldView, clusterIsStable, clusterIsUnstable, notifier))
          } yield ()
        }
      )(worldView, a)

    def memberEvent(event: MemberEvent): SyncIO[WorldView]             = SyncIO.pure(worldView.memberEvent(event))
    def reachabilityEvent(event: ReachabilityEvent): SyncIO[WorldView] = SyncIO.pure(worldView.reachabilityEvent(event))

    {
      case e: MemberEvent =>
        for {
          _         <- SyncIO(log.debug("{}", e))
          worldView <- memberEvent(e)
          _         <- stateTransition(worldView, notifier)
        } yield ()

      case e: ReachabilityEvent =>
        for {
          _         <- SyncIO(log.debug("{}", e))
          worldView <- reachabilityEvent(e)
          notifier  <- notifyIfReachable(notifier, e)
          _         <- stateTransition(worldView, notifier)
        } yield ()

      case r @ ReachabilityNotification(event, _) =>
        if (event.member === cluster.selfMember) {
          for {
            _         <- SyncIO(log.debug("{}", r))
            _         <- SyncIO(mediator ! SendToAll(self.path.toStringWithoutAddress, r.ack, allButSelf = true))
            worldView <- reachabilityEvent(event)
            _         <- stateTransition(worldView, notifier)
          } yield ()
        } else {
          // notification is not for this member
          for {
            _ <- SyncIO(log.warning("Ignore notification for another node. {}", r))
            _ <- become(hasSplitBrain(worldView, clusterIsStable, clusterIsUnstable, notifier))
          } yield ()
        }

      case r @ ReachabilityNotificationAck(event, ackN) =>
        for {
          _        <- SyncIO(log.debug("{}", r))
          notifier <- notifier.ack(ackN, event.member.address)
          _        <- become(hasSplitBrain(worldView, clusterIsStable, clusterIsUnstable, notifier))
        } yield ()

      case ClusterIsStable =>
        SyncIO(log.debug("Cluster is stable, resolving...")) >> splitBrainResolver_(Or(strategy, Indirected))

      case ClusterIsUnstable =>
        SyncIO(log.debug("Cluster is unstable, downing all...")) >> splitBrainResolver_(DownAll)
    }
  }

  private def toHasSplitBrain(worldView: WorldView,
                              clusterIsStable: Cancellable,
                              notifier: ReachabilityNotifier): SyncIO[IOReceive] =
    SyncIO(log.debug("to has-split-brain")) >> scheduleClusterIsUnstable.map(
      hasSplitBrain(worldView, clusterIsStable, _, notifier)
    )

  private def toNoSplitBrain(worldView: WorldView,
                             clusterIsStable: Cancellable,
                             clusterIsUnstable: Cancellable,
                             notifier: ReachabilityNotifier): SyncIO[IOReceive] =
    SyncIO(log.debug("to no-split-brain")) >> SyncIO(clusterIsUnstable.cancel())
      .as(noSplitBrain(worldView, clusterIsStable, notifier))

  /**
   * Executes the decision.
   *
   * If the current node is the leader all the nodes referred in the decision
   * will be downed. Otherwise, if it is not the leader or none exists, and refers to itself.
   * It will down the current node. Else, no node will be downed.
   *
   * In short, the leader can down anyone. Other nodes are only allowed to down themselves.
   */
  private def executeDecision(decision: StrategyDecision): SyncIO[Unit] = SyncIO {
    if (cluster.state.leader.contains(cluster.selfAddress)) {
      val nodesToDown = decision.nodesToDown
      log.debug(s"[execute-decision] Downing nodes: $nodesToDown")
      nodesToDown.foreach(node => cluster.down(node.member.address))
    } else {
      if (decision.nodesToDown.map(_.member).contains(cluster.selfMember)) {
        log.debug(s"[execute-decision] Downing self")
        cluster.down(cluster.selfAddress)
      } else {
        log.debug(s"[execute-decision] Not downing anything.")
      }
    }
  }

  private def scheduleClusterIsStable: SyncIO[Cancellable] =
    SyncIO(context.system.scheduler.scheduleOnce(stableAfter, self, ClusterIsStable))

  private def scheduleClusterIsUnstable: SyncIO[Cancellable] =
    SyncIO(context.system.scheduler.scheduleOnce(stableAfter + downAllWhenUnstable, self, ClusterIsUnstable))

  private def send(e: ReachabilityEvent, to: Address, id: Long): SyncIO[Cancellable] =
    SyncIO(
      context.system.scheduler.schedule(0.seconds, 1.second)(
        mediator ! SendToAll(self.path.toStringWithoutAddress, ReachabilityNotification(e, id), allButSelf = false)
      )
    )

  def splitBrainResolver[A: Strategy](before: SyncIO[Unit], after: SyncIO[Unit])(worldView: WorldView,
                                                                                 a: A): SyncIO[Unit] =
    for {
      _        <- SyncIO(log.debug("[execute] worldView = {}", worldView))
      _        <- before
      decision <- SyncIO.fromEither(a.takeDecision(worldView))
      _        <- executeDecision(decision)
      _        <- after
    } yield ()

  override def preStart(): Unit =
    cluster.subscribe(self,
                      InitialStateAsSnapshot,
                      classOf[akka.cluster.ClusterEvent.MemberEvent],
                      classOf[akka.cluster.ClusterEvent.ReachabilityEvent])

  override def postStop(): Unit = cluster.unsubscribe(self)
}

object Downer {

  /**
   * Pure version of [[akka.actor.Actor.Receive]].
   */
  type IOReceive = PartialFunction[Any, SyncIO[Unit]]

  type ReachabilityNotifier = Notifier[ReachabilityEvent]

  def props[A: Strategy](cluster: Cluster,
                         strategy: A,
                         stableAfter: FiniteDuration,
                         downAllWhenUnstable: FiniteDuration): Props =
    Props(new Downer(cluster, strategy, stableAfter, downAllWhenUnstable))

  /**
   * Change the actor's behavior to `receive`.
   */
  def become(receive: IOReceive)(implicit context: ActorContext): SyncIO[Unit] =
    SyncIO(context.become(receive.andThen(_.unsafeRunSync())))

  /**
   * Choose `whenTrue` when `cond` is true else choose `whenFalse`.
   */
  def when[A](cond: Boolean)(whenTrue: => SyncIO[A], whenFalse: => SyncIO[A]): SyncIO[A] =
    if (cond) whenTrue
    else whenFalse

  def cancel(cancellable: Cancellable): SyncIO[Unit] = SyncIO(cancellable.cancel()).void

  final case object ClusterIsStable
  final case object ClusterIsUnstable

  final case class ReachabilityNotification(reachabilityEvent: ReachabilityEvent, id: Long) {
    def ack: ReachabilityNotificationAck = ReachabilityNotificationAck(reachabilityEvent, id)
  }

  final case class ReachabilityNotificationAck(reachabilityEvent: ReachabilityEvent, id: Long)
}
