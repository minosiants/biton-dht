package kademlia

import cats.syntax.partialOrder._
import kademlia.types._
import org.scalacheck.Prop._
import org.scalacheck._

class NodeIdSute extends KSuite {

  property("id is comparable") {
    forAll(intPairGen) {
      case (a, b) =>
        val aId = NodeId.fromInt(a)
        val bId = NodeId.fromInt(b)
        (aId > bId) == (a > b)
    }

  }

  val intPairGen: Gen[(Int, Int)] = for {
    a <- Gen.posNum[Int]
    b <- Gen.posNum[Int]
  } yield (a, b)
}
