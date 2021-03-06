package com.swissborg.lithium

import akka.remote.testconductor.RoleName

object TestUtil {

  def linksToKillForPartitions(partitions: List[List[RoleName]]): List[(RoleName, RoleName)] = {
    @scala.annotation.tailrec
    def go(
      partitions: List[List[RoleName]],
      links: List[(RoleName, RoleName)]
    ): List[(RoleName, RoleName)] =
      partitions match {
        case Nil      => links
        case _ :: Nil => links
        case p :: ps =>
          val others = ps.flatten
          val newLinks = p.foldLeft(List.empty[(RoleName, RoleName)]) {
            case (newLinks, role) => newLinks ++ others.map(_ -> role)
          }

          go(ps, links ++ newLinks)
      }

    go(partitions, List.empty)
  }
}
