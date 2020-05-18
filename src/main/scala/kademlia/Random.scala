package kademlia

import java.time.Instant

import cats.data.Reader
import scodec.bits.BitVector

import scala.util.{ Random => SRandom }

object Random {

  type R[A] = Reader[SRandom, A]

  object R {

    def apply[A](run: SRandom => A): R[A] =
      Reader(run)

    def short: R[Short] = R(_.between(Short.MinValue, Short.MaxValue).toShort)

    def shortBits: R[BitVector] =
      short.map(i => BitVector.fromShort(i))
  }

  def random = new SRandom(Instant.now().toEpochMilli)

  def `2chars`: BitVector = R.shortBits.run(random)

}
