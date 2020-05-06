package kademlia

import org.specs2.mutable.Specification
import scodec.bits._

class KBucketSpec extends Specification {
  "KBucket" should {
    "bla" in {

      val bv1  = BitVector.low(8)
      val bv2  = BitVector.high(4)
      val b1   = bin"0000 0000"
      val b2   = bin"1000 0000"
      val b254 = bin"11111110"
      val b256 = bin"11111111"
      val b11  = bin"1100 0000"
      val b22  = bin"1110 0000"
      val b    = bin"1110 0100"

      def roundup(i: Long) = i + (8 - i % 8) % 8

      println((BitVector.high(20 * 8).bytes))

      success
    }
  }

}
