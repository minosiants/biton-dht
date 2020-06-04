package biton.dht

import benc.{ BDecoder, BEncoder }
import biton.dht.protocol.Token
import cats.implicits._
import cats.{ Eq, Order }
import com.comcast.ip4s.{ IpAddress, Port }
import io.estatico.newtype.macros._
import protocol.Token
import scodec.Codec
import scodec.bits.BitVector
import scodec.codecs._

object types {

  @newtype final case class KSize(value: Int) {
    def *(i: Int): KSize = KSize(value * i)
  }
  @newtype final case class Key(value: BitVector)
  @newtype final case class Value(value: BitVector)
  @newtype final case class Index(value: Int) {
    def +(i: Index): Index = Index(value + i.value)
    def +(i: Int): Index   = Index(value + i)
    def add(i: Int): Index = Index(value + i)
  }

  @newtype final case class Prefix(value: BigInt)

  object Prefix {

    implicit val eqPrefix: Eq[Prefix] = Eq.instance(_.value === _.value)

    /*implicit val orderPrefix: Order[Prefix] =
      orderByteVector.contramap[Prefix](_.value.bytes)*/
  }

  final case class Contact(ip: IpAddress, port: Port)
      extends Product
      with Serializable

  object Contact {
    implicit val eqContact: Eq[Contact] = Eq.instance { (a, b) =>
      a.ip.equals(b.ip) && a.port.equals(b.port)
    }

    val codec: Codec[Contact] = (
      ("ip" | ipAddressScocec) ::
        ("port" | portScodec)
    ).as[Contact]

  }
  final case class Node(
      nodeId: NodeId,
      contact: Contact
  ) extends Product
      with Serializable

  object Node {

    implicit val eqNode: Eq[Node] = Eq.instance { (a, b) =>
      a.nodeId === b.nodeId && a.contact === b.contact
    }

    val nodeCodec: Codec[Node] =
      (NodeId.codec :: Contact.codec).as[Node]

    val listNodeCodec = list(nodeCodec)
    implicit val bencoder: BEncoder[List[Node]] =
      BEncoder.sc[List[Node]](listNodeCodec)
    implicit val bdecoder: BDecoder[List[Node]] =
      BDecoder.sc[List[Node]](listNodeCodec)

  }

  @newtype final case class Distance(value: BigInt) {
    def >(other: Distance): Boolean = value > other.value
    def <(other: Distance): Boolean = value < other.value
  }

  object Distance {
    implicit val orderingDistance: Ordering[Distance] =
      Ordering[BigInt].contramap(_.value)
    implicit val eqDistance: Eq[Distance] = Eq.fromUniversalEquals
  }

  final case class NodeInfo(token: Token, node: Node)

  object NodeInfo {
    implicit val eqNodeInfo: Eq[NodeInfo] = Eq.fromUniversalEquals
  }

  @newtype final case class NodeId(value: BitVector)

  object NodeId {

    def fromInt(n: Int): NodeId = NodeId(BitVector.fromInt(n).padLeft(idLength))
    def fromBigInt(n: BigInt): NodeId =
      NodeId(BitVector(n.toByteArray).padLeft(idLength))
    def fromString(str: String): NodeId =
      NodeId(BitVector(str.getBytes().take(20)).padLeft(idLength))
    def gen(): NodeId = NodeId(Random.`20bytes`)

    implicit val nodeIdOrder: Order[NodeId] =
      orderByteVector.contramap(_.value.bytes)

    val codec: Codec[NodeId] = bits(idLength).xmap(
      NodeId(_),
      _.value
    )

    implicit val nodeIdBencoder: BEncoder[NodeId] =
      BEncoder.bitVectorBEncoder.contramap(_.value)
    implicit val nodeIdBDecoder: BDecoder[NodeId] =
      BDecoder.bitVectorBDecoder.map(NodeId(_))

    implicit val eqNodeId: Eq[NodeId] = Eq.instance(_.value === _.value)
  }

}
