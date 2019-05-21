package com.swissborg.sbr.resolver

import akka.actor.{Actor, ActorLogging, Address, Props}
import akka.cluster.Cluster
import cats.data.OptionT
import cats.data.OptionT.liftF
import cats.effect.SyncIO
import cats.implicits._
import com.swissborg.sbr.WorldView.SimpleWorldView
import com.swissborg.sbr.implicits._
import com.swissborg.sbr.reporter.SBReporter
import com.swissborg.sbr.resolver.SBResolver.HandleSplitBrain.SimpleHandleSplitBrain
import com.swissborg.sbr.strategies.Union
import com.swissborg.sbr.strategies.indirectlyconnected.IndirectlyConnected
import com.swissborg.sbr.strategy.Strategy
import com.swissborg.sbr.{Node, StrategyDecision, WorldView}
import io.circe.Encoder
import io.circe.syntax._
import io.circe.generic.semiauto.deriveEncoder

import scala.concurrent.duration._

/**
 * Actor resolving split-brain scenarios.
 *
 * @param _strategy the strategy with which to resolved the split-brain.
 * @param stableAfter duration during which a cluster has to be stable before attempting to resolve a split-brain.
 */
class SBResolver(_strategy: Strategy, stableAfter: FiniteDuration) extends Actor with ActorLogging {

  import SBResolver._

  private val _ = context.actorOf(SBReporter.props(self, stableAfter))

  private val cluster     = Cluster(context.system)
  private val selfAddress = cluster.selfMember.address
  private val strategy    = Union(_strategy, IndirectlyConnected())

  override def receive: Receive = {
    case e @ HandleSplitBrain(worldView) =>
      log.info(e.simple.asJson.noSpaces)

      runStrategy(strategy, worldView)
        .handleErrorWith(err => SyncIO(log.error(err, "An error occurred during decision making.")))
        .unsafeRunSync()
  }

  private def runStrategy(strategy: Strategy, worldView: WorldView): SyncIO[Unit] = {
    def down(nodes: Set[Node]): OptionT[SyncIO, Unit] =
      liftF(nodes.toList.traverse_(node => SyncIO(cluster.down(node.member.address))))

    // Execute the decision by downing all the nodes to be downed if
    // the current node is the leader. Otherwise, do nothing.
    def execute(decision: StrategyDecision): SyncIO[Unit] = {
      val leader: OptionT[SyncIO, Address] = OptionT(SyncIO(cluster.state.leader))

      leader
        .map(_ === selfAddress)
        .ifM(
          down(decision.nodesToDown) >> liftF(SyncIO(log.info(decision.simple.asJson.noSpaces))),
          liftF(SyncIO(log.info("Cannot take a decision. Not the leader.")))
        )
        .value
        .void
    }

    strategy.takeDecision(worldView).flatMap(execute)
  }
}

object SBResolver {
  def props(strategy: Strategy, stableAfter: FiniteDuration): Props = Props(new SBResolver(strategy, stableAfter))

  final case class HandleSplitBrain(worldView: WorldView) {
    lazy val simple: SimpleHandleSplitBrain = SimpleHandleSplitBrain(worldView.simple)
  }

  object HandleSplitBrain {
    final case class SimpleHandleSplitBrain(worldView: SimpleWorldView)

    object SimpleHandleSplitBrain {
      implicit val simpleHandleSplitBrainEncoder: Encoder[SimpleHandleSplitBrain] = deriveEncoder
    }
  }
}
