package kademlia

import kademlia.TraversalNode.{ Responded, Stale }
import kademlia.types.NodeInfo
import org.scalacheck.Prop.forAll
import org.scalacheck.Gen
import cats.implicits._

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
      TraversalTable.create(infohash, nodes) match {
        case TraversalTable.Completed(_, nodes)  => isSorted(nodes)
        case TraversalTable.InProgress(_, nodes) => isSorted(nodes)
      }
    }
  }

  test("markNodesAsStale") {
    forAll(infoHashGen, Gen.nonEmptyListOf(nodeGen())) { (infohash, nodes) =>
      val table         = TraversalTable.create(infohash, nodes)
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
        val table         = TraversalTable.create(infohash, nodes)
        val sizeToTake    = table.nodes.size / 2
        val expected      = table.nodes.takeRight(sizeToTake)
        val nodesToUpdate = expected.map(v => NodeInfo(token, v.node))
        val t             = table.markNodesAsResponded(nodesToUpdate)
        val result =
          t.nodes.takeRight(sizeToTake).collect { case Responded(n, _, _) => n }
        expected.map(_.node) === result
    }
  }
}
