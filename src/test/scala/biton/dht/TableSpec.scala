package biton.dht

import java.nio.file.Path

import biton.dht.Conf.GoodDuration
import biton.dht.protocol.KMessage
import biton.dht.types.{ KSize, NodeId, Prefix }
import cats.effect.IO
import cats.implicits._
import org.scalacheck.Prop.forAll

import scala.concurrent.duration._

class TableSpec extends KSuite with TableFunctions {

  import TableSpec._

  val ksize        = KSize(3)
  val from         = Prefix(0)
  val to           = Prefix(10)
  val goodDuration = GoodDuration(1.minute)
  def kbGen(from: Int = 0, to: Int = 10, ksize: KSize = ksize) =
    kbucketGen(Prefix(from), Prefix(to), ksize, ksize.value)

  val pingClient = PingClient()

  def emptyTable(nodeId: NodeId, to: Prefix = to) =
    IO.fromEither(
      Table.empty(nodeId, pingClient, goodDuration, ksize, from, to)
    )

  test("add to empty table") {
    forAll(kbGen(), nodeIdChooseGen(0, 9)) { (kbucket, nodeId) =>
      val result = (for {
        table  <- emptyTable(nodeId)
        result <- table.addNodes(kbucket.nodes.value.toList.map(_.node))
      } yield result).unsafeRunSync()

      lazy val actual   = result.kbuckets.head.nodes.value
      lazy val expected = kbucket.nodes.value
      result.bsize == 1 && expected === actual
    }

  }

  test("add to table with full bucket") {
    forAll(kbGen(), nodeIdChooseGen(0, 9)) { (kbucket, nodeId) =>
      val result = (for {
        t1 <- emptyTable(nodeId)
        t2 <- t1.addNodes(kbucket.nodes.value.toList.map(_.node))
        id   = availableIds(kbucket).head
        node = kbucket.nodes.value.head.node.copy(nodeId = NodeId.fromInt(id))
        t3 <- t2.addNode(node)
      } yield t3).unsafeRunSync()

      val order = result.kbuckets.head.from.value > result.kbuckets.tail.head.from.value

      result.bsize == 2 && order
    }

  }
  test("table with three buckets") {
    forAll(kbGen(), kbGen(10, 20), nodeIdChooseGen(11, 19)) {
      (kb1, kb2, nodeId) =>
        val result = (for {
          t1 <- emptyTable(nodeId, Prefix(20))
          t2 <- t1.addNodes(
            (kb2.nodes.value ++ kb1.nodes.value).toList.map(_.node)
          )
          id   = availableIds(t2.kbuckets.head).head
          node = kb1.nodes.value.head.node.copy(nodeId = NodeId.fromInt(id))
          t3 <- t2.addNode(node)
        } yield t3).unsafeRunSync()

        result.bsize == 3
    }
  }

  test("neighbors") {
    forAll(
      kbGen(),
      kbGen(10, 20),
      nodeIdChooseGen(11, 19),
      nodeIdChooseGen(0, 19)
    ) { (kb1, kb2, nodeId, target) =>
      val result = (for {
        t1 <- emptyTable(nodeId, Prefix(20))
        t2 <- t1.addNodes(
          (kb2.nodes.value ++ kb1.nodes.value).toList.map(_.node)
        )
      } yield t2.neighbors(target)).unsafeRunSync()

      result.size == ksize.value && ordered(result, target)
    }
  }

  test("toFile/fromFile") {
    val base = Path.of(s"target/.biton")
    forAll(kbGen(), nodeIdChooseGen(0, 9)) { (kbucket, nodeId) =>
      val result = (for {
        t1    <- emptyTable(nodeId)
        t2    <- t1.addNodes(kbucket.nodes.value.toList.map(_.node))
        p     <- TableSerialization.toFile(base, t2)
        table <- TableSerialization.fromFile(base, nodeId)
        _     <- Files.delete(p)
      } yield (table, t2)).attempt.unsafeRunSync()

      result.leftMap {
        case e: Error => println(e.show)
      }
      result.map {
        case (t1, t2) => t1.kbuckets === t2.kbuckets && t1.nodeId === t2.nodeId
      } == true.asRight
    }
  }

  test("tableState") {
    forAll(kbGen(0, 100, KSize(10)), nodeIdChooseGen(0, 9)) {
      (kbucket, nodeId) =>
        val result = (for {
          table <- TableState.empty(
            nodeId,
            PingClient(),
            GoodDuration(1.minute)
          )
          nodes = kbucket.nodes.value.toList.map(_.node)
          _ <- table.addNodes(nodes)
          _ <- table.addNodes(nodes)
          t <- table.get
        } yield t).unsafeRunSync()

        result.kbuckets
          .collect(_.nodes.value.size)
          .sum == kbucket.nodes.value.size

    }
  }

  //override val scalaCheckInitialSeed = "vAbmilUBs8yWyW6eBwLqST70VKO_yzZYQ678ZwF7LkK="
}

object TableSpec {
  final case class PingClient() extends Client.Ping {
    override def ping(
        node: types.Node
    ): fs2.Stream[IO, KMessage.NodeIdResponse] = ???
  }
}
