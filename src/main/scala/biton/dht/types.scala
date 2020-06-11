package biton.dht

import java.time.chrono.ChronoLocalDateTime
import java.time.{ Clock, LocalDateTime }

import benc.{ BDecoder, BEncoder }
import biton.dht.protocol.Token
import cats.implicits._
import cats.{ Eq, Order }
import com.comcast.ip4s.{ IpAddress, Port }
import io.estatico.newtype.macros._
import scodec.bits.BitVector
import scodec.codecs._
import scodec.{ Attempt, Codec, DecodeResult }

import scala.concurrent.duration.FiniteDuration

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
  @newtype final case class LastActive(value: LocalDateTime) {
    def isBefore: ChronoLocalDateTime[_] => Boolean = value.isBefore
  }

  object LastActive {
    def now(implicit clock: Clock): LastActive =
      LastActive(LocalDateTime.now(clock))

    lazy val codec: Codec[LastActive] = Codec[LastActive](
      (_: LastActive) => Attempt.successful(BitVector.empty),
      bits =>
        Attempt.successful(DecodeResult(LastActive(LocalDateTime.now), bits))
    )

    implicit val eqLastActive: Order[LastActive] =
      Order.from((a, b) => a.value.compareTo(b.value))
  }

  @newtype final case class FailCount(value: Int) {
    def inc: FailCount = FailCount(value + 1)
  }
  object FailCount {
    val zero = FailCount(0)

    implicit val orderFailCount: Order[FailCount] =
      Order.reverse(Order.by[FailCount, Int](_.value))
  }
  final case class NodeActivity(
      node: Node,
      lastActive: LastActive,
      count: FailCount
  )

  object NodeActivity {
    def apply(node: Node)(implicit clock: Clock): NodeActivity =
      NodeActivity(node, LastActive.now(clock), FailCount.zero)

    implicit val eqNodeActivity: Eq[NodeActivity] = Eq.instance(
      (a, b) =>
        a.node === b.node && a.lastActive === b.lastActive && a.count === b.count
    )
  }
  final case class Node(
      nodeId: NodeId,
      contact: Contact
  ) extends Product
      with Serializable

  @newtype final case class GoodDuration(finiteDuration: FiniteDuration)

  object Node {

    implicit val eqNode: Eq[Node] = Eq.instance { (a, b) =>
      a.nodeId === b.nodeId && a.contact === b.contact
    }

    lazy val nodeCodec: Codec[Node] =
      (NodeId.codec :: Contact.codec).as[Node]

    lazy val listNodeCodec = list(nodeCodec)
    implicit def bencoder: BEncoder[List[Node]] =
      BEncoder.sc[List[Node]](listNodeCodec)
    implicit def bdecoder: BDecoder[List[Node]] =
      BDecoder.sc[List[Node]](listNodeCodec)

  }

  @newtype final case class Distance(value: BigInt) {
    def >(other: Distance): Boolean  = value > other.value
    def <(other: Distance): Boolean  = value < other.value
    def <=(other: Distance): Boolean = value <= other.value

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

    implicit lazy val nodeIdBencoder: BEncoder[NodeId] =
      BEncoder.bitVectorBEncoder.contramap(_.value)
    implicit lazy val nodeIdBDecoder: BDecoder[NodeId] =
      BDecoder.bitVectorBDecoder.map(NodeId(_))

    implicit val eqNodeId: Eq[NodeId] = Eq.instance(_.value === _.value)
  }

}
