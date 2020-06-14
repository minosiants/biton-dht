package biton.dht

import java.time.Instant

import cats.data.Reader
import scodec.bits.BitVector

import scala.util.{ Random => SRandom }
import cats.syntax.traverse._
import cats.instances.list._

trait Random[A] {
  def value: A
}

object Random {

  type R[A] = Reader[SRandom, A]

  object R {

    def apply[A](run: SRandom => A): R[A] =
      Reader(run)

    def rshort: R[Short] = R(_.between(Short.MinValue, Short.MaxValue).toShort)
    def rlong: R[Long]   = R(_.nextLong())
    def rint: R[Int]     = R(_.nextInt())
    def betweenInt(minInclusive: Int, maxExclusive: Int): R[Int] = R {
      _.between(minInclusive, maxExclusive)
    }
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

  def `40bytes`: BitVector          = `20bytes` ++ `20bytes`
  def rint(from: Int, to: Int): Int = R.betweenInt(from, to).run(random)
  def rint(to: Int): Int            = rint(0, to: Int)

  def apply[A](implicit R: Random[A]): Random[A] = R
  def instance[A](v: => A): Random[A] = new Random[A] {
    override def value: A = v
  }
  def random[A: Random]: A = Random[A].value

}
