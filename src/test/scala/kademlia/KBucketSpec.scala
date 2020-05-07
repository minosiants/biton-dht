package kademlia
import cats.syntax.either._
import cats.syntax.partialOrder._
import kademlia.KBucket.Cache
import kademlia.types.NodeId
import org.scalacheck.Prop.forAll

class KBucketSpec extends KSuite {

  val ksize = 5

  test("add to full kbucket") {
    forAll(kbucketGen(0, ksize, ksize), nodeGen) { (kbucket, node) =>
      val result = kbucket.add(node)
      result === Error.KBucketError(s"$kbucket is full").asLeft
    }
  }

  test("add to empty kbucket") {
    forAll(kbucketGen(0, ksize, 0), nodeGen) { (kbucket, node) =>
      val result = kbucket.add(node)
      result === KBucket.create(
        kbucket.prefix,
        Nodes(List(node), ksize),
        kbucket.cache
      )
    }
  }

  test("add to kbucket") {
    forAll(kbucketGen(0, ksize, 2), nodeGen) { (kbucket, node) =>
      val result = kbucket.add(node)
      val expected = Nodes(
        kbucket.nodes.value.filterNot(_.nodeId == node.nodeId) :+ node,
        ksize
      )
      result.map(_.nodes) === expected.asRight
    }
  }

  test("add to kbucket cache") {
    forAll(kbucketGen(0, ksize, ksize), nodeGen) { (kbucket, node) =>
      val result = for {
        k  <- kbucket.addToCache(node)
        kk <- k.addToCache(node)
      } yield kk

      val expected =
        Cache(Nodes(node :: kbucket.cache.value.value.dropRight(1), ksize))

      result.map(_.cache) === expected.asRight
    }

    test("split kbucket") {
      forAll(kbucketGen(0, ksize, ksize)) { kbucket =>
        val result = for {
          (first, second) <- kbucket.split()
        } yield checkBuckets(first, second)

        result == true.asRight
      }
    }

  }

  def checkBuckets(first: KBucket, second: KBucket): Boolean = {
    val firstResult = first.nodes.value.foldLeft(true) { (v, n) =>
      val f = n.nodeId.value ^ first.prefix.value
      val s = n.nodeId.value ^ second.prefix.value
      (NodeId(f) < NodeId(s)) && v
    }
    val secondResult = second.nodes.value.foldLeft(true) { (v, n) =>
      val f = n.nodeId.value ^ first.prefix.value
      val s = n.nodeId.value ^ second.prefix.value
      (NodeId(f) > NodeId(s)) && v
    }
    firstResult && secondResult
  }
}
