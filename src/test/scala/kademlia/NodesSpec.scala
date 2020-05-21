package kademlia

import kademlia.types.KSize
import org.scalacheck._
//import cats.syntax.either._
import org.scalacheck.Prop.forAll
import cats.implicits._

class NodesSpec extends KSuite {

  test("filterNot") {
    forAll(listOfNodesGen(5), nodeGen) { (list, node) =>
      val nodes = Nodes(list.filterNot(_.nodeId == node.nodeId), KSize(6))
      val result = for {
        r <- nodes.append(node)
      } yield r.filterNot(node)
      result === nodes.asRight
    }
  }

  test("append") {
    forAll(listOfNodesGen(5), nodeGen) { (list, node) =>
      val nodes  = Nodes(list.filterNot(_.nodeId == node.nodeId), KSize(6))
      val result = nodes.append(node)
      result.map(_.value.last) == node.asRight
    }
  }

  test("append when full") {
    forAll(listOfNodesGen(5), nodeGen) { (list, node) =>
      val nodes  = Nodes(list, KSize(5))
      val result = nodes.append(node)
      result === Error.KBucketError(s"Bucket is full for Node $node").asLeft
    }
  }

  test("prepend") {
    forAll(listOfNodesGen(5), nodeGen) { (list, node) =>
      val nodes  = Nodes(list.filterNot(_.nodeId == node.nodeId), KSize(6))
      val result = nodes.prepend(node)
      result.map(_.value.head) === node.asRight
    }

    test("prepend when full") {
      forAll(listOfNodesGen(5), nodeGen) { (list, node) =>
        val nodes  = Nodes(list, KSize(5))
        val result = nodes.prepend(node)
        result === Error.KBucketError(s"Bucket is full for Node $node").asLeft
      }
    }

    test("drop and prepend") {
      forAll(listOfNodesGen(5), nodeGen) { (list, node) =>
        val nodes  = Nodes(list, KSize(6))
        val result = nodes.dropAndPrepended(node)
        result.value.head === node
      }
    }

    test("drop and prepend when full") {
      forAll(listOfNodesGen(5), nodeGen) { (list, node) =>
        val nodes  = Nodes(list, KSize(5))
        val result = nodes.dropAndPrepended(node)
        result.value.head === node
      }
    }

  }
}
