package kademlia
import java.time.{ Clock, Instant, ZoneId }

import org.scalacheck._
import cats.syntax.either._

class KBucketSpec extends KSpec {

  "KBucket" should {

    val ksize = 5

    "add to full kbucket" in Prop.forAll(kbucketGen(0, ksize, 5), nodeGen) {
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
  }

}
