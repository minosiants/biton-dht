package kademlia

import kademlia.TraversalNode.{ Responded, Stale }
import kademlia.types.{ NodeId, NodeInfo }
import org.scalacheck.Prop.forAll
import org.scalacheck.Gen
import cats.implicits._
import kademlia.protocol.InfoHash
import scodec.bits.BitVector

class TraversalTableSpec extends KSuite {

  def isSorted(l: List[TraversalNode]) = l match {
    case Nil => true
    case xs =>
      xs.sliding(2).forall {
        case x :: y :: Nil => x.distance.value <= y.distance.value
        case _             => false
      }
  }

  test("create") {
    forAll(infoHashGen, listOfNodesGen(5)) { (infohash, nodes) =>
      TraversalTable.create(infohash, nodes, 8) match {
        case TraversalTable.Completed(_, nodes, _)  => isSorted(nodes)
        case TraversalTable.InProgress(_, nodes, _) => isSorted(nodes)
      }
    }
  }

  test("markNodesAsStale") {
    forAll(infoHashGen, Gen.nonEmptyListOf(nodeGen())) { (infohash, nodes) =>
      val table         = TraversalTable.create(infohash, nodes, 8)
      val expected      = table.nodes.takeRight(1)
      val nodesToUpdate = expected.map(_.node)
      val t             = table.markNodesAsStale(nodesToUpdate)
      val result        = t.nodes.takeRight(1).collect { case Stale(n, _) => n }
      expected.map(_.node) === result
    }
  }
  test("markNodesAsResponded") {
    forAll(infoHashGen, Gen.nonEmptyListOf(nodeGen()), tokenGen) {
      (infohash, nodes, token) =>
        val table         = TraversalTable.create(infohash, nodes, 8)
        val sizeToTake    = table.nodes.size / 2
        val expected      = table.nodes.takeRight(sizeToTake)
        val nodesToUpdate = expected.map(v => NodeInfo(token, v.node))
        val t             = table.markNodesAsResponded(nodesToUpdate)
        val result =
          t.nodes.takeRight(sizeToTake).collect { case Responded(n, _, _) => n }
        expected.map(_.node) === result
    }
  }
  test("completed") {
    forAll(infoHashGen, listOfNodesGen(5), tokenGen) {
      (infohash, nodes, token) =>
        val table                   = TraversalTable.create(infohash, nodes, 3)
        val a :: b :: c :: d :: Nil = table.topFresh(4)
        val result = table
          .markNodesAsResponded(
            List(NodeInfo(token, a), NodeInfo(token, c), NodeInfo(token, d))
          )
          .markNodeAsStale(b)
        result match {
          case TraversalTable.Completed(_, _, _)  => true
          case TraversalTable.InProgress(_, _, _) => false
        }

    }

  }

  test("order by distance") {
    forAll(infoHashGen, Gen.nonEmptyListOf(nodeGen())) { (infohash, nodes) =>
      val table = TraversalTable.create(infohash, nodes, 8)
      val order = table.nodes.sliding(2).forall {
        case x :: y :: Nil => x.distance < y.distance
        case _ :: Nil      => true
        case _             => false
      }
      order
    }
  }
}
