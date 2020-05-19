package kademlia

import java.time.{ Clock, Instant, LocalDateTime, ZoneOffset }

import cats.effect.{ Concurrent, ContextShift, IO, Timer }
import com.comcast.ip4s.{ IpAddress, Port }
import kademlia.KBucket.Cache
import kademlia.types.{ Node, NodeId, Prefix }
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import scodec.bits.BitVector

import scala.concurrent.ExecutionContext

class KSuite extends ScalaCheckSuite {

  implicit val clock: Clock                      = Clock.fixed(Instant.now(), ZoneOffset.UTC)
  private val executionContext: ExecutionContext = ExecutionContext.global

  implicit val ioContextShift: ContextShift[IO] =
    IO.contextShift(executionContext)

  implicit val ioTimer: Timer[IO] = IO.timer(executionContext)

  val nodeIdIntGen: Gen[NodeId] =
    Gen.chooseNum(0, Integer.MAX_VALUE).map(NodeId.fromInt)

  def bitVectorGen(size: Int = idLength): Gen[BitVector] =
    Gen
      .infiniteStream(Gen.chooseNum(0, 255))
      .map(_.take(20).toList.map(_.toByte))
      .map(BitVector(_))

  val nodeIdCharGen: Gen[NodeId] = bitVectorGen(idLength).map(NodeId((_)))

  val byteGen: Gen[Byte] = Gen.chooseNum(0, 255).map(_.byteValue())

  val ipV4Gen: Gen[IpAddress] =
    Gen
      .infiniteStream(byteGen)
      .map(_.take(4).toArray)
      .map(IpAddress.fromBytes(_).get)

  val portGen: Gen[Port] = Gen.chooseNum(0, 65535).map(Port(_).get)

  val nodeGen: Gen[Node] = for {
    ip   <- ipV4Gen
    id   <- nodeIdCharGen
    port <- portGen
  } yield Node(id, ip, port)

  def listOfNodesGen(size: Int): Gen[List[Node]] =
    Gen
      .infiniteStream(nodeGen)
      .map(_.take(size * 30).toSet.take(size).toList)
      .retryUntil(_.size == size)

  def kbucketGen(prefix: Int, ksize: Int, nsize: Int): Gen[KBucket] =
    for {
      nodes <- listOfNodesGen(nsize).map(v => Nodes(v, ksize))
      cache <- listOfNodesGen(nsize).map(v => Cache(Nodes(v, ksize)))
      pref = Prefix(NodeId.fromInt(prefix).value)
    } yield KBucket
      .create(pref, nodes, cache)
      .toOption
      .get
}
