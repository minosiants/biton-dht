package kademlia

import kademlia.types._
import org.specs2.mutable.Specification
import scodec.bits._
import org.scalacheck._
import org.specs2.ScalaCheck

class KBucketSpec extends KSpec {
  "KBucket" should {
    "add to bucket" in {
      //KBucket.create(Prefix())

      val zero = lowestNodeId | BitVector.fromInt(5).padLeft(idLength)
      println("zero.toBin")
      println(zero.toInt(false))
      println(lowestNodeId.toBin)
      success
    }
  }

}
