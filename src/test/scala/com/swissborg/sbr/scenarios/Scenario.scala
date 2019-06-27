package com.swissborg.sbr
package scenarios

import akka.cluster.Member
import akka.cluster.MemberStatus.{Exiting, Leaving, Removed}
import cats.data.NonEmptySet
import cats.implicits._
import com.swissborg.sbr.implicits._
import com.swissborg.sbr.utils._
import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric.Positive
import eu.timepit.refined.refineV
import org.scalacheck.Arbitrary._
import org.scalacheck.Gen.someOf
import org.scalacheck.{Arbitrary, Gen}

sealed abstract class Scenario {
  def worldViews: List[WorldView]
  def clusterSize: Int Refined Positive
}

final case class OldestRemovedDisseminationScenario(
    worldViews: List[WorldView],
    clusterSize: Int Refined Positive
) extends Scenario

object OldestRemovedDisseminationScenario {
  implicit val arbOldestRemovedScenario: Arbitrary[OldestRemovedDisseminationScenario] = Arbitrary {
    def divergeWorldView(
        worldView: WorldView,
        allNodes: NonEmptySet[Node],
        partition: NonEmptySet[Node]
    ): Arbitrary[Option[WorldView]] =
      Arbitrary {
        val otherNodes = allNodes -- partition

        val oldestNode = allNodes.toList.sortBy(_.member)(Member.ageOrdering).head

        // Change `self`
        val worldViewWithChangedSelf = worldView.changeSelf(partition.head.member)

        val worldView0 = otherNodes.foldLeft(worldViewWithChangedSelf) {
          case (worldView, node) =>
            worldView.addOrUpdate(node.member).withUnreachableNode(node.member.uniqueAddress)
        }

        def oldestRemoved =
          if (worldView0.selfUniqueAddress === oldestNode.member.uniqueAddress) {
            None
          } else {
            Some(worldView0.removeMember(oldestNode.member.copy(Removed)))
          }

        def oldestNotRemoved =
          Some(worldView0.addOrUpdate(oldestNode.member.copy(Leaving).copy(Exiting)))

        Gen.oneOf(oldestRemoved, oldestNotRemoved)
      }

    for {
      initWorldView <- arbAllUpWorldView.arbitrary
      nodes = initWorldView.nodes
      partitions <- splitCluster(nodes)
      divergedWorldViews <- partitions.traverse(divergeWorldView(initWorldView, nodes, _)).arbitrary
      bla = divergedWorldViews.toList.flatten
    } yield
      OldestRemovedDisseminationScenario(
        bla,
        refineV[Positive](nodes.length).right.get // TODO -1?
      )
  }
}

final case class CleanPartitionsScenario(
    worldViews: List[WorldView],
    clusterSize: Int Refined Positive
) extends Scenario

object CleanPartitionsScenario {

  /**
    * Generates clean partition scenarios where the allNodes is split
    * in multiple sub-clusters and where each one sees the rest as
    * unreachable.
    */
  implicit val arbSplitScenario: Arbitrary[CleanPartitionsScenario] = Arbitrary {

    def partitionedWorldView[N <: Node](
        nodes: NonEmptySet[N]
    )(partition: NonEmptySet[N]): WorldView = {
      val otherNodes = nodes -- partition

      val worldView0 =
        WorldView.fromNodes(ReachableNode(partition.head.member), partition.tail.map(identity))

      otherNodes.foldLeft[WorldView](worldView0) {
        case (worldView, node) =>
          worldView.addOrUpdate(node.member).withUnreachableNode(node.member.uniqueAddress)
      }
    }

    for {
      allNodes <- arbNonEmptySet[ReachableNode].arbitrary

      // Split the allNodes in `nSubCluster`.
      partitions <- splitCluster(allNodes)

      // Each sub-allNodes sees the other nodes as unreachable.
      partitionedWorldViews = partitions.map(partitionedWorldView(allNodes))
    } yield
      CleanPartitionsScenario(
        partitionedWorldViews.toList,
        refineV[Positive](allNodes.length).right.get
      )
  }

}

final case class UpDisseminationScenario(
    worldViews: List[WorldView],
    clusterSize: Int Refined Positive
) extends Scenario

object UpDisseminationScenario {
  implicit val arbUpDisseminationScenario: Arbitrary[UpDisseminationScenario] = Arbitrary {

    /**
      * Yields a [[WorldView]] that based on `worldView`
      * that sees all the nodes not in the `partition`
      * as unreachable and sees some members up that others
      * do not see.
      */
    def divergeWorldView(
        worldView: WorldView,
        allMembersUp: NonEmptySet[Member]
    )(
        partition: NonEmptySet[Node]
    ): Arbitrary[WorldView] = Arbitrary {
      val allNodes = worldView.nodes
      val otherNodes = allNodes -- partition

      // Change `self`
      val worldViewWithChangedSelf = worldView.changeSelf(partition.head.member)

      val worldView0 = otherNodes.foldLeft[WorldView](worldViewWithChangedSelf) {
        case (worldView, node) =>
          worldView.addOrUpdate(node.member).withUnreachableNode(node.member.uniqueAddress)
      }

      pickNonEmptySubset(allMembersUp).arbitrary.map(_.foldLeft(worldView0) {
        case (worldView, member) => worldView.addOrUpdate(member)
      })
    }

    for {
      initWorldView <- arbJoiningOrWeaklyUpOnlyWorldView.arbitrary

      allNodes = initWorldView.nodes // all are reachable

      membersToUp <- pickNonEmptySubset(allNodes).arbitrary.map(_.map(_.member))

      // Move some random nodes to up.
      // Fix who the oldest node is else we get a cluster with an
      // inconsistent state where the oldest one might not be up.

      allMembersUp = membersToUp.zipWithIndex.map {
        case (member, upNumber) =>
          println(s"UP ${member} - $upNumber")
          member.copyUp(upNumber)
      }

      //
      oldestMember = allMembersUp.head // upNumber = 0
      _ = println(s"OLDEST ${oldestMember}")
      worldViewWithOldestUp = initWorldView.addOrUpdate(oldestMember)
      _ = println(s"WV ${worldViewWithOldestUp}")
      _ = println(s"ALLUP ${allMembersUp}")

      // Split the allNodes in `nSubCluster`.
      partitions <- splitCluster(worldViewWithOldestUp.nodes)

      divergedWorldViews <- partitions
        .traverse(divergeWorldView(worldViewWithOldestUp, allMembersUp))
        .arbitrary
    } yield
      UpDisseminationScenario(
        divergedWorldViews.toList,
        refineV[Positive](allNodes.length).right.get
      )
  }
}

final case class RemovedDisseminationScenario(
    worldViews: List[WorldView],
    clusterSize: Int Refined Positive
) extends Scenario

object RemovedDisseminationScenario {
  implicit val arbRemovedDisseminationScenario: Arbitrary[RemovedDisseminationScenario] =
    Arbitrary {

      /**
        * Yields a [[WorldView]] that based on `worldView`
        * that sees all the nodes not in the `partition`
        * as unreachable and sees some members removed that others
        * do not see.
        */
      def divergeWorldView(
          worldView: WorldView,
          allNodes: NonEmptySet[Node],
          membersToRemove: NonEmptySet[Member]
      )(
          partition: NonEmptySet[Node]
      ): Arbitrary[WorldView] = Arbitrary {
        val otherNodes = allNodes -- partition

        // Change `self`
        val worldViewWithChangedSelf = worldView.changeSelf(partition.head.member)

        val worldView0 = otherNodes.foldLeft[WorldView](worldViewWithChangedSelf) {
          case (worldView, node) =>
            worldView.addOrUpdate(node.member).withUnreachableNode(node.member.uniqueAddress)
        }

        def nodesRemoved = membersToRemove.foldLeft(worldView0) {
          case (worldView, member) => worldView.removeMember(member)
        }

        def nodesNotRemoved = membersToRemove.foldLeft(worldView0) {
          case (worldView, member) =>
            worldView.addOrUpdate(member.copy(Leaving).copy(Exiting))
        }

        Gen.oneOf(nodesRemoved, nodesNotRemoved)
      }

      for {
        initWorldView <- arbAllUpWorldView.arbitrary

        allNodes = initWorldView.nodes // all are reachable

        // Split the allNodes in `nSubCluster`.
        partitions <- splitCluster(allNodes)

        membersToRemove <- pickNonEmptySubset(allNodes).arbitrary.map(_.map(_.member))

        divergedWorldViews <- partitions
          .traverse(divergeWorldView(initWorldView, allNodes, membersToRemove))
          .arbitrary
      } yield
        RemovedDisseminationScenario(
          divergedWorldViews.toList,
          refineV[Positive](allNodes.length).right.get
        )
    }
}

final case class WithNonCleanPartitions[S <: Scenario](
    worldViews: List[WorldView],
    clusterSize: Int Refined Positive
) extends Scenario

object WithNonCleanPartitions {
  implicit def arbWithNonCleanPartitions[S <: Scenario: Arbitrary]
      : Arbitrary[WithNonCleanPartitions[S]] = Arbitrary {
    for {
      scenario <- arbitrary[S]

      // Add some arbitrary indirectly-connected nodes to each partition.
      worldViews <- scenario.worldViews.traverse { worldView =>
        someOf(worldView.reachableNodes).map(_.foldLeft(worldView) {
          case (worldView, indirectlyConnectedNode) =>
            worldView.withIndirectlyConnectedNode(indirectlyConnectedNode.member.uniqueAddress)
        })
      }
    } yield WithNonCleanPartitions(worldViews, scenario.clusterSize)
  }
}
