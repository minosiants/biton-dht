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
  test("closest") {
    forAll(intTripleGen) {
      case (a, b, c) =>
        val aId = NodeId.fromInt(a)
        val bId = NodeId.fromInt(b)
        val cId = NodeId.fromInt(c)
        val res = cId.closest(aId, bId)(
          aId,
          bId
        )
        val ax = a ^ c
        val bx = b ^ c

        if (ax < bx)
          res === aId
        else
          res === bId

    }
  }

  val intPairGen: Gen[(Int, Int)] = for {
    a <- Gen.posNum[Int]
    b <- Gen.posNum[Int]
  } yield (a, b)

  val intTripleGen: Gen[(Int, Int, Int)] = for {
    a <- Gen.posNum[Int]
    b <- Gen.posNum[Int]
    c <- Gen.posNum[Int]
  } yield (a, b, c)
}
