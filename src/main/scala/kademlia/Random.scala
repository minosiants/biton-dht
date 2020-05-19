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

    def rshort: R[Short] = R(_.between(Short.MinValue, Short.MaxValue).toShort)
    def rlong: R[Long]   = R(_.nextLong())
    def rshortBits: R[BitVector] =
      rshort.map(BitVector.fromShort(_))
    def rlongBits: R[BitVector] = rlong.map(BitVector.fromLong(_))
  }

  def random = new SRandom(Instant.now().toEpochMilli)

  def `2chars`: BitVector    = R.rshortBits.run(random)
  def shortBinStr: BitVector = R.rlongBits.run(random)

}
