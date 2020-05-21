package kademlia

import kademlia.types.KSize
import org.scalacheck.Prop.forAll
import cats.syntax.either._
import cats.instances.list._
import cats.syntax.order._

class TableSpec extends KSuite {
  val ksize = KSize(5)
  test("add to empty table") {
    forAll(kbucketGen(0, ksize, ksize.value), nodeIdIntGen) {
      (kbucket, nodeId) =>
        val table  = Table.empty(nodeId, ksize)
        val result = table.map(_.addNodes(kbucket.nodes.value)).unsafeRunSync()
        result.map(_.size) == 1.asRight

        lazy val expected = kbucket.nodes.value.asRight[Error]
        lazy val actual   = result.map(_.kbuckets.head.nodes.value)
        result.map(_.size) == 1.asRight && expected === actual
    }

  }

  test("add to full bucket") {
    forAll(kbucketGen(0, ksize, ksize.value), nodeGen, nodeIdIntGen) {
      (kbucket, node, nodeId) =>
        val table = Table.empty(nodeId, ksize)
        val result =
          table
            .map { t =>
              for {
                t2 <- t.addNodes(kbucket.nodes.value)
                t3 <- t2.addNode(node)
              } yield t3
            }
            .unsafeRunSync()

        val order =
          result.map(t => t.kbuckets.head.prefix > t.kbuckets.tail.head.prefix)
        result.map(_.size) == 2.asRight && order == true.asRight
    }

  }
}
