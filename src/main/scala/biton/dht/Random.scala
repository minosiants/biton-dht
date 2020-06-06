package biton.dht

import java.time.Instant

import cats.data.Reader
import scodec.bits.BitVector

import scala.util.{ Random => SRandom }
import cats.syntax.traverse._
import cats.instances.list._
object Random {

  type R[A] = Reader[SRandom, A]

  object R {

    def apply[A](run: SRandom => A): R[A] =
      Reader(run)

    def rshort: R[Short] = R(_.between(Short.MinValue, Short.MaxValue).toShort)
    def rlong: R[Long]   = R(_.nextLong())
    def rint: R[Int]     = R(_.nextInt())
    def rshortBits: R[BitVector] =
      rshort.map(BitVector.fromShort(_))
    def rlongBits: R[BitVector]                 = rlong.map(BitVector.fromLong(_))
    def list[A](size: Int, r: R[A]): R[List[A]] = List.fill(size)(r).sequence
  }

  val random = new SRandom(Instant.now().toEpochMilli)

  def `2chars`: BitVector    = R.rshortBits.run(random)
  def shortBinStr: BitVector = R.rlongBits.run(random)
  def `20bytes`: BitVector =
    R.list(5, R.rint)
      .run(random)
      .foldLeft(BitVector.empty)((acc, v) => acc ++ BitVector.fromInt(v))

  def `40bytes`: BitVector = `20bytes` ++ `20bytes`
}
