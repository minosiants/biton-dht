package biton

import biton.dht.syntax.Syntax
import cats.Order
import cats.instances.either._
import cats.instances.list._
import cats.kernel.Eq
import cats.syntax.either._
import cats.syntax.foldable._
import com.comcast.ip4s.{ IpAddress, Port }
import scodec.bits.{ BitVector, ByteVector }

import scala.Function.const

package object dht extends Codecs with Syntax {

  type Result[A] = Either[Error, A]

  val idLength                 = 20 * 8
  val highestNodeId: BitVector = BitVector.high(idLength)
  val lowestNodeId: BitVector  = BitVector.low(idLength)

  implicit val orderByteVector: Order[ByteVector] = Order.from[ByteVector] {
    (a, b) =>
      val result = a.toSeq.toList
        .zip(b.toSeq)
        .foldM[Either[Int, *], List[Byte]](List.empty) {
          case (b, (aa, bb)) if aa.ubyte == bb.ubyte => (aa :: b).asRight
          case (_, (aa, bb)) if aa.ubyte > bb.ubyte  => 1.asLeft
          case (_, (aa, bb)) if aa.ubyte < bb.ubyte  => (-1).asLeft
        }
      result.fold(identity, const(0))
  }

  implicit val eqBitVector: Eq[BitVector] = Eq.instance { (a, b) =>
    a === b
  }
  implicit val eqPort: Eq[Port]           = Eq.instance(_.equals(_))
  implicit val eqIpAddress: Eq[IpAddress] = Eq.instance(_.equals(_))
}
