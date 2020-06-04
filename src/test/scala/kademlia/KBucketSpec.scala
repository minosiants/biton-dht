package kademlia
import cats.instances.list._
import cats.syntax.either._
import kademlia.KBucket.Cache
import kademlia.types.{ KSize, NodeId, Prefix }
import org.scalacheck.Prop.forAll

class KBucketSpec extends KSuite {

  val ksize = KSize(3)
  val from  = Prefix(0)
  val to    = Prefix(10)

  def kbGen(ksize: KSize = ksize, nsize: Int = ksize.value) =
    kbucketGen(from, to, ksize, nsize)

  test("add to full kbucket") {
    forAll(kbGen()) { kbucket =>
      val id     = availableIds(kbucket).head
      val node   = kbucket.nodes.value.head.copy(nodeId = NodeId.fromInt(id))
      val result = kbucket.add(node)
      result === Error.KBucketError(s"$kbucket is full").asLeft
    }
  }

  test("add to empty kbucket") {
    forAll(kbGen(ksize, 0), nodeGen(nodeIdChooseGen(0, 9))) { (kbucket, node) =>
      val result = kbucket.add(node)
      result === KBucket.create(
        kbucket.from,
        kbucket.to,
        Nodes(List(node), ksize),
        kbucket.cache
      )
    }
  }

  test("add to kbucket") {
    forAll(kbGen(ksize, 2)) { kbucket =>
      val node = kbucket.nodes.value.head
      val result = for {
        kb  <- kbucket.remove(node)
        res <- kb.add(node)
      } yield res
      result.map(_.nodes.value) === kbucket.nodes.value.reverse.asRight
    }
  }

  test("add to kbucket cache") {
    forAll(kbGen(), nodeGen(nodeIdChooseGen(0, 9))) { (kbucket, node) =>
      val result = for {
        k  <- kbucket.addToCache(node)
        kk <- k.addToCache(node)
      } yield kk

      val expected =
        Cache(Nodes(node :: kbucket.cache.value.value.dropRight(1), ksize))

      result.map(_.cache) === expected.asRight
    }
  }
  test("split kbucket") {
    forAll(kbGen(ksize), nodeGen(nodeIdChooseGen(0, 9))) { (kbucket, node) =>
      println(kbucket)
      val result = for {
        (first, second) <- kbucket.split()
      } yield checkBuckets(first, second)

      result == true.asRight
    }
  }
  test("inRange") {
    forAll(kbGen(ksize), nodeGen(nodeIdChooseGen(5, 9))) { (kbucket, node) =>
      println(kbucket)
      val result = for {
        (_, second) <- kbucket.split()
      } yield second.inRange(node.nodeId)

      result == true.asRight
    }
  }

  def checkBuckets(first: KBucket, second: KBucket): Boolean = {
    val firstResult = first.nodes.value.foldLeft(true) { (v, n) =>
      first.inRange(n.nodeId) && v
    }
    val secondResult = second.nodes.value.foldLeft(true) { (v, n) =>
      second.inRange(n.nodeId) && v
    }
    firstResult && secondResult
  }
}
