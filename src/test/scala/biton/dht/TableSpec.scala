package biton.dht

import biton.dht.types.{ KSize, NodeId, Prefix }
import cats.implicits._
import org.scalacheck.Prop.forAll

class TableSpec extends KSuite with TableFunctions {

  val ksize = KSize(3)
  val from  = Prefix(0)
  val to    = Prefix(10)

  def kbGen(from: Int = 0, to: Int = 10) =
    kbucketGen(Prefix(from), Prefix(to), ksize, ksize.value)

  test("add to empty table") {
    forAll(kbGen(), nodeIdChooseGen(0, 9)) { (kbucket, nodeId) =>
      val result = for {
        table  <- Table.empty(nodeId, ksize, from, to)
        result <- table.addNodes(kbucket.nodes.value)
      } yield result

      lazy val actual   = result.toOption.get.kbuckets.head.nodes.value
      lazy val expected = kbucket.nodes.value
      result.map(_.bsize) == 1.asRight && expected === actual
    }

  }

  test("add to table with full bucket") {
    forAll(kbGen(), nodeIdChooseGen(0, 9)) { (kbucket, nodeId) =>
      val result = for {
        t1 <- Table.empty(nodeId, ksize, from, to)
        t2 <- t1.addNodes(kbucket.nodes.value)
        id   = availableIds(kbucket).head
        node = kbucket.nodes.value.head.copy(nodeId = NodeId.fromInt(id))
        t3 <- t2.addNode(node)
      } yield t3

      val order =
        result.map(
          t => t.kbuckets.head.from.value > t.kbuckets.tail.head.from.value
        )
      result.map(_.bsize) == 2.asRight && order == true.asRight
    }

  }

  test("table cache does not overlap with not cache") {
    forAll(kbGen(), nodeIdChooseGen(0, 9)) { (kbucket, nodeId) =>
      val table = (for {
        t1 <- Table.empty(nodeId, ksize, from, to)
        t2 <- t1.addNodes(kbucket.nodes.value)
        t3 <- t2.addNodes(kbucket.nodes.value)
      } yield t3).toOption.get

      val nodes = table.kbuckets.head.nodes.value.map(_.nodeId.toPrefix).toSet
      val cache =
        table.kbuckets.head.cache.value.value.map(_.nodeId.toPrefix).toSet

      nodes.intersect(cache).isEmpty
    }
  }
  test("table with three buckets") {
    forAll(kbGen(), kbGen(10, 20), nodeIdChooseGen(11, 19)) {
      (kb1, kb2, nodeId) =>
        val result = for {
          t1 <- Table.empty(nodeId, ksize, from, Prefix(20))
          t2 <- t1.addNodes(kb2.nodes.value)
          t3 <- t2.addNodes(kb1.nodes.value)
          id   = availableIds(t3.kbuckets.head).head
          node = kb1.nodes.value.head.copy(nodeId = NodeId.fromInt(id))
          t4 <- t3.addNode(node)
        } yield t4

        result.leftMap {
          case e: Error => println(e.show)
        }

        result.map(_.bsize) == 3.asRight
    }
  }

}
