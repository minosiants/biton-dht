package kademlia

import kademlia.types.Prefix
import org.scalacheck.Gen
import scodec.bits.BitVector
import org.scalacheck.Prop._
import cats.syntax.order._

class PrefixSpec extends KSuite {
  test("set") {
    forAll(prefixIntGen(0)) { p =>
      Prefix(p.value.update(0, true)) === p.set(0)
    }
  }

  test(">>>") {
    forAll(prefixIntGen(1)) { p =>
      p >>> 1 === Prefix(BitVector.fromInt(p.value.toInt() / 2))
    }
  }
  test("nonLow") {
    forAll(prefixIntGen(1)) { p =>
      p.nonLow
    }
  }
  test("isLow") {
    Prefix(BitVector.fromInt(0)).isLow
  }

  test("next") {
    forAll(prefixIntGen(0)) { p =>
      val next = p.next
      if (p.isLow)
        next === Prefix(BitVector.fromInt(0)).set(0)
      else
        next === Prefix(p.value >>> 1)
    }
  }

  def prefixIntGen(from: Int): Gen[Prefix] =
    Gen
      .chooseNum(from, Integer.MAX_VALUE)
      .map(v => Prefix(BitVector.fromInt(v)))
}
