package kademlia

import kademlia.types._
import org.scalacheck._
import cats.syntax.partialOrder._
class NodeIdSpec extends KSpec {

  "NodeId" should {

    "compare" in Prop.forAll(intPairGen) {
      case (a, b) =>
        val aId = NodeId.fromInt(a)
        val bId = NodeId.fromInt(b)
        (aId > bId) ==== (a > b)
    }

  }

  val intPairGen: Gen[(Int, Int)] = for {
    a <- Gen.posNum[Int]
    b <- Gen.posNum[Int]
  } yield (a, b)
}
