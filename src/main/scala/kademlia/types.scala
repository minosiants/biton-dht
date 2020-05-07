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

  object NodeId {

    implicit val nodeIdOrder: Order[NodeId] = Order.from[NodeId] { (a, b) =>
      val result = a.value.bytes.toSeq.toList
        .zip(b.value.bytes.toSeq)
        .foldM[Either[Int, *], List[Byte]](List.empty) {
          case (b, (aa, bb)) if aa == bb => (aa :: b).asRight
          case (b, (aa, bb)) if aa > bb  => 1.asLeft
          case (b, (aa, bb)) if aa < bb  => (-1).asLeft
        }
      result.fold(identity, const(0))
    }
  }

}
