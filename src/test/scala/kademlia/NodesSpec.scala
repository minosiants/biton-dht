package kademlia

import org.scalacheck._
import cats.syntax.either._

class NodesSpec extends KSpec {

  "Nodes" should {
    "filterNot" in Prop.forAll(listOfNodesGen(5), nodeGen) { (list, node) =>
      val nodes = Nodes(list.filterNot(_.nodeId == node.nodeId), 6)
      val result = for {
        r <- nodes.append(node)
      } yield r.filterNot(node)

      result ==== nodes.asRight
    }

    "append" in Prop.forAll(listOfNodesGen(5), nodeGen) { (list, node) =>
      val nodes  = Nodes(list.filterNot(_.nodeId == node.nodeId), 6)
      val result = nodes.append(node)
      result.map(_.value.last) ==== node.asRight
    }

    "append when full" in Prop.forAll(listOfNodesGen(5), nodeGen) {
      (list, node) =>
        val nodes  = Nodes(list, 5)
        val result = nodes.append(node)
        result ==== Error.KBucketError(s"Bucket is full for Node $node").asLeft
    }

    "prepend" in Prop.forAll(listOfNodesGen(5), nodeGen) { (list, node) =>
      val nodes  = Nodes(list.filterNot(_.nodeId == node.nodeId), 6)
      val result = nodes.prepend(node)
      result.map(_.value.head) ==== node.asRight
    }

    "prepend when full" in Prop.forAll(listOfNodesGen(5), nodeGen) {
      (list, node) =>
        val nodes  = Nodes(list, 5)
        val result = nodes.prepend(node)
        result ==== Error.KBucketError(s"Bucket is full for Node $node").asLeft
    }

    "drop and prepend" in Prop.forAll(listOfNodesGen(5), nodeGen) {
      (list, node) =>
        val nodes  = Nodes(list, 6)
        val result = nodes.dropAndPrepended(node)
        result.value.head ==== node
    }

    "drop and prepend when full" in Prop.forAll(listOfNodesGen(5), nodeGen) {
      (list, node) =>
        val nodes  = Nodes(list, 5)
        val result = nodes.dropAndPrepended(node)
        result.value.head ==== node
    }

  }
}
