package akka.cluster.sbr.strategies.downall

import akka.cluster.sbr._
import akka.cluster.sbr.ArbitraryInstances._
import akka.cluster.sbr.scenarios.{OldestRemovedScenario, SymmetricSplitScenario, UpDisseminationScenario}
import akka.cluster.sbr.utils.RemainingPartitions
import cats.implicits._

class DownAllSpec extends MySpec {
  "DownAll" - {
    "1 - should always down nodes" in {
      forAll { worldView: WorldView =>
        Strategy[DownAll](worldView, ()).map {
          case DownReachable(_) =>
            if (worldView.unreachableNodes.nonEmpty) succeed
            else fail
          case Idle =>
            if (worldView.reachableNodes.isEmpty || worldView.unreachableNodes.isEmpty) succeed
            else fail
          case _ => fail
        }
      }
    }

    "2 - should handle symmetric split scenarios" in {
      forAll { scenario: SymmetricSplitScenario =>
        val remainingSubClusters = scenario.worldViews.foldMap { worldView =>
          Strategy[DownAll](worldView, ()).foldMap(RemainingPartitions.fromDecision(worldView))
        }

        remainingSubClusters.n.value should be <= 1
      }
    }

    "3 - should handle a split during up-dissemination scenarios" in {
      forAll { scenario: UpDisseminationScenario =>
        val remainingSubClusters = scenario.worldViews.foldMap { worldView =>
          Strategy[DownAll](worldView, ()).foldMap(RemainingPartitions.fromDecision(worldView))
        }

        remainingSubClusters.n.value should be <= 1
      }
    }

    "4 - should handle a split during the oldest-removed scenarios" in {
      forAll { scenario: OldestRemovedScenario =>
        val remainingSubClusters = scenario.worldViews.foldMap { worldView =>
          Strategy[DownAll](worldView, ()).foldMap(RemainingPartitions.fromDecision(worldView))
        }

        remainingSubClusters.n.value should be <= 1
      }
    }
  }
}