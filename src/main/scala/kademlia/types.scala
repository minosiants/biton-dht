package kademlia

import benc.{BDecoder, BEncoder}
import scodec.bits.BitVector
import com.comcast.ip4s.{IpAddress, Port}
import io.estatico.newtype.macros._
import cats.{Eq, Order}
import cats.implicits._
import kademlia.syntax.ByteSyntax
import scodec.Codec
import scodec.codecs._

import Function._

object types {

  @newtype final case class Key(value: BitVector)
  @newtype final case class Value(value: BitVector)
  @newtype final case class Prefix(value: BitVector)
  @newtype final case class KSize(value: Int)
  @newtype final case class NodeId(value: BitVector)

  final case class Contact(ip: IpAddress, port: Port) extends Product with Serializable

  object Contact {
    val codec:Codec[Contact] = (
        ("ip" | ipAddressScocec) ::
        ("port" | portScodec)
      ).as[Contact]
  }
  case class Node(
      nodeId: NodeId,
      contact: Contact
  )extends Product with Serializable

  object Node {

    implicit val nodeEq: Eq[Node] = Eq.fromUniversalEquals

    val nodeCodec: Codec[Node] =
      (NodeId.codec :: Contact.codec).as[Node]

    val listNodeCodec = list(nodeCodec)
    implicit val bencoder: BEncoder[List[Node]] =
      BEncoder.sc[List[Node]](listNodeCodec)
    implicit val bdecoder: BDecoder[List[Node]] =
      BDecoder.sc[List[Node]](listNodeCodec)

  }
  object NodeId extends ByteSyntax {
    def fromInt(n: Int): NodeId = NodeId(BitVector.fromInt(n).padLeft(idLength))
    def gen(): NodeId           = NodeId(Random.`20bytes`)

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

    val codec: Codec[NodeId] = bits(idLength).xmap(
      NodeId(_),
      _.value
    )

    implicit val nodeIdBencoder: BEncoder[NodeId] =
      BEncoder.bitVectorBEncoder.contramap(_.value)
    implicit val nodeIdBDecoder: BDecoder[NodeId] =
      BDecoder.bitVectorBDecoder.map(NodeId(_))
  }

}
