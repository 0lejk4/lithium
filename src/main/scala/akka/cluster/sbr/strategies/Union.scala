package akka.cluster.sbr.strategies

import akka.cluster.sbr.strategy.Strategy
import akka.cluster.sbr.{StrategyDecision, WorldView}
import cats.implicits._

/**
 * Strategy combining `a` and `b` by taking the union
 * of both decisions.
 */
final case class Union(a: Strategy, b: Strategy) extends Strategy {
  override def takeDecision(worldView: WorldView): Either[Throwable, StrategyDecision] =
    (a.takeDecision(worldView), b.takeDecision(worldView)).mapN(_ |+| _)
}