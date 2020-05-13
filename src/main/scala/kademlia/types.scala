package kademlia

import java.time.LocalDateTime

import benc.{ BCodec, BEncoder, BencIgnore, BencKey }
import scodec.bits.BitVector
import com.comcast.ip4s.{ IpAddress, Port }
import io.estatico.newtype.macros._
import cats.{ Eq, Order }
import cats.implicits._
import scodec.Codec
import scodec.codecs._

import Function._
object types {

  @newtype final case class Key(value: BitVector)
  @newtype final case class Value(value: BitVector)
  @newtype final case class Prefix(value: BitVector)
  @newtype final case class KSize(value: Int)
  @newtype final case class NodeId(value: BitVector)

  case class Node(
      nodeId: NodeId,
      ip: IpAddress,
      port: Port,
      @BencIgnore lastSeen: LocalDateTime
  )

  object Node {
    def apply(nodeId: NodeId, ip: IpAddress, port: Port) =
      Node(nodeId, ip, port, LocalDateTime.now)

    implicit val nodeEq: Eq[Node] = Eq.fromUniversalEquals

    implicit val nodeCodec: Codec[Node] = (
      ("nodeId" | NodeId.codec) ::
        ("ip" | ipAddressScocec) ::
        ("port" | portScodec)
    ).as[Node]

    implicit val bencoder: BEncoder[Node] =
      BEncoder.bitVectorBEncoder.contramap(
        v =>
          for {

            nid <- v.nodeId.value
          } yield ???
      )
  }
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

    val codec: Codec[NodeId] = bits(idLength * 8).xmap(
      NodeId(_),
      _.value
    )
    implicit val nodeIdBCodec: BCodec[NodeId] =
      BCodec.bitVectorBCodec.xmap(NodeId(_), _.value)
  }

}
