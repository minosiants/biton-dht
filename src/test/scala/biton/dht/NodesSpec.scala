package biton.dht

import types.KSize
import org.scalacheck.Prop._
import cats.implicits._

class NodesSpec extends KSuite {

  test("filterNot") {
    forAll(vectorOfNodesAcitvity(5), nodeGen()) { (vec, node) =>
      val nodes = Nodes(vec.filterNot(_.node.nodeId === node.nodeId), KSize(6))
      val result = for {
        r <- nodes.append(node)
      } yield r.filterNot(node)
      result === nodes.asRight
    }
  }

  test("append") {
    forAll(vectorOfNodesAcitvity(5), nodeGen()) { (vec, node) =>
      val nodes  = Nodes(vec.filterNot(_.node.nodeId === node.nodeId), KSize(6))
      val result = nodes.append(node)
      result.map(_.value.last.node) === node.asRight
    }
  }

  test("append when full") {
    forAll(vectorOfNodesAcitvity(5), nodeGen()) { (vec, node) =>
      val nodes  = Nodes(vec, KSize(5))
      val result = nodes.append(node)
      result === Error.KBucketError(s"Bucket is full for Node $node").asLeft
    }
  }

}
