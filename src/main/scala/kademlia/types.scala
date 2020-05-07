package kademlia

import java.time.LocalDateTime

import scodec.bits.BitVector

import com.comcast.ip4s.IpAddress

import io.estatico.newtype.macros._
import cats.Order
import cats.implicits._
import Function._
object types {

  @newtype final case class Key(value: BitVector)
  @newtype final case class NodeId(value: BitVector)
  @newtype final case class Prefix(value: BitVector)
  @newtype final case class KSize(value: Int)

  case class Node(
      nodeId: NodeId,
      ip: IpAddress,
      port: Int,
      lastSeen: LocalDateTime
  )

  object NodeId extends ByteSyntax {
    def fromInt(n: Int): NodeId = NodeId(BitVector.fromInt(n).padLeft(idLength))

    implicit val nodeIdOrder: Order[NodeId] = Order.from[NodeId] { (a, b) =>
      val result = a.value.bytes.toSeq.toList
        .zip(b.value.bytes.toSeq)
        .foldM[Either[Int, *], List[Byte]](List.empty) {
          case (b, (aa, bb)) if aa.ubyte == bb.ubyte => (aa :: b).asRight
          case (_, (aa, bb)) if aa.ubyte > bb.ubyte  => 1.asLeft
          case (_, (aa, bb)) if aa.ubyte < bb.ubyte  => (-1).asLeft
        }
      result.fold(identity, const(0))
    }

  }

}
