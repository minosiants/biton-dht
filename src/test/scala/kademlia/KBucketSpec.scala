package kademlia
import cats.syntax.either._
import kademlia.KBucket.Cache
import kademlia.types.NodeId
import org.scalacheck._

import cats.syntax.partialOrder._

class KBucketSpec extends KSpec {

  "KBucket" should {

    val ksize = 5

    "add to full kbucket" in Prop.forAll(kbucketGen(0, ksize, ksize), nodeGen) {
      (kbucket, node) =>
        val result = kbucket.add(node)
        result ==== Error.KBucketError(s"$kbucket is full").asLeft
    }

    "add to empty kbucket" in Prop.forAll(kbucketGen(0, ksize, 0), nodeGen) {
      (kbucket, node) =>
        val result = kbucket.add(node)
        result ==== KBucket.create(
          kbucket.prefix,
          Nodes(List(node), ksize),
          kbucket.cache
        )
    }

    "add to kbucket" in Prop.forAll(kbucketGen(0, ksize, 2), nodeGen) {
      (kbucket, node) =>
        val result = kbucket.add(node)
        val expected = Nodes(
          kbucket.nodes.value.filterNot(_.nodeId == node.nodeId) :+ node,
          ksize
        )
        result.map(_.nodes) ==== expected.asRight
    }

    "add to kbucket cache" in Prop.forAll(kbucketGen(0, ksize, ksize), nodeGen) {
      (kbucket, node) =>
        val result = for {
          k  <- kbucket.addToCache(node)
          kk <- k.addToCache(node)
        } yield kk

        val expected =
          Cache(Nodes(node :: kbucket.cache.value.value.dropRight(1), ksize))

        result.map(_.cache) ==== expected.asRight
    }

    "split kbucket" in Prop.forAll(kbucketGen(0, ksize, ksize)) { kbucket =>
      val result = for {
        (first, second) <- kbucket.split()
      } yield checkBuckets(first, second)
      result ==== true.asRight
    }

  }

  def checkBuckets(first: KBucket, second: KBucket) = {

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
