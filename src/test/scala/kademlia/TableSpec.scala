package kademlia

import kademlia.types.{ KSize, Node, NodeId }
import org.scalacheck.Prop.forAll
import cats.implicits._

class TableSpec extends KSuite with TableFunctions {
  val ksize = KSize(5)
  test("add to empty table") {
    forAll(kbucketGen(0, ksize, ksize.value), nodeIdIntGen) {
      (kbucket, nodeId) =>
        val table  = Table.empty(nodeId, ksize)
        val result = table.map(_.addNodes(kbucket.nodes.value)).unsafeRunSync()
        result.map(_.size) == 1.asRight

        lazy val expected = kbucket.nodes.value.asRight[Error]
        lazy val actual   = result.map(_.kbuckets.head.nodes.value)
        result.map(_.bsize) == 1.asRight && expected === actual
    }

  }

  test("add to table with full bucket") {
    forAll(kbucketGen(0, ksize, ksize.value), nodeIdIntGen) {
      (kbucket, nodeId) =>
        val table = Table.empty(nodeId, ksize)
        val result =
          table
            .map { t =>
              for {
                t2 <- t.addNodes(kbucket.nodes.value)
                h    = kbucket.nodes.value.head
                node = createNodes(h.nodeId, h, 1).head
                t3 <- t2.addNode(node)
              } yield t3
            }
            .unsafeRunSync()

        val order =
          result.map(t => t.kbuckets.head.prefix > t.kbuckets.tail.head.prefix)
        result.map(_.bsize) == 2.asRight && order == true.asRight
    }

  }
  test("table with three buckets") {
    val nodeId = NodeId.fromInt(1)
    val node   = nodeGen.sample.get
    val table  = Table.empty(nodeId, ksize)
    val nodes1 = createNodes(nodeId, node, 5)
    val result =
      table
        .map { t =>
          for {
            withOne <- t.addNodes(nodes1)
            id1    = withOne.kbuckets.head.prefix.toNodeId
            nodes2 = createNodes(id1, node, 5)
            withTwo <- t.addNodes(nodes2)
            id2      = withTwo.kbuckets.head.prefix.toNodeId
            newNodes = createNodes(id2, node, 5)
            withThree <- withTwo.addNodes(newNodes)
          } yield withThree
        }
        .unsafeRunSync()
        .toOption
        .get

    val order = result.kbuckets.map(_.prefix).toVector
    result.bsize == 3 && order === order.sortWith(_ > _)

  }
  def createNodes(nodeId: NodeId, node: Node, n: Int): List[Node] = {
    List
      .unfold(1)(i => {
        val a = node.copy(nodeId = NodeId((nodeId.value.not >>> i).not))

        Option.when(i <= n)(a -> (i + 1))
      })
      .take(n)
  }
}
