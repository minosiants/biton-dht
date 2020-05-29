package kademlia

import java.time.{ Clock, Instant, LocalDateTime, ZoneOffset }

import cats.effect.{ Concurrent, ContextShift, IO, Timer }
import com.comcast.ip4s.{ IpAddress, Port }
import kademlia.KBucket.Cache
import kademlia.types.{ Contact, KSize, Node, NodeId, Prefix }
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import scodec.bits.BitVector
import org.scalacheck.cats.implicits._
import cats.instances.list._
import cats.syntax.traverse._
import kademlia.protocol.Token

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
  val contactGen: Gen[Contact] = for {
    ip   <- ipV4Gen
    port <- portGen
  } yield Contact(ip, port)

  def nodeGen(nodeIdGen: Gen[NodeId] = nodeIdCharGen): Gen[Node] =
    for {
      id      <- nodeIdGen
      contact <- contactGen
    } yield Node(id, contact)

  def listOfNodesGen(
      size: Int,
      nodeIdGen: Gen[NodeId] = nodeIdCharGen
  ): Gen[List[Node]] =
    for {
      list <- Gen
        .infiniteStream(nodeIdGen)
        .map(_.take(size * 30).toSet.take(size).toList)
        .retryUntil(_.size == size)
      res <- list.traverse(v => contactGen.map(Node(v, _)))
    } yield res

  def nodeIdChooseGen(from: Int, to: Int): Gen[NodeId] =
    Gen.choose(from, to).map(NodeId.fromInt)

  def kbucketGen(
      from: Prefix,
      to: Prefix,
      ksize: KSize,
      nsize: Int
  ): Gen[KBucket] =
    for {
      nodes <- listOfNodesGen(
        nsize,
        nodeIdChooseGen(from.value.toInt, to.value.toInt - 1)
      ).map(v => Nodes(v, ksize))
      cache <- listOfNodesGen(nsize).map(v => Cache(Nodes(v, ksize)))

    } yield KBucket
      .create(from, to, nodes, cache)
      .toOption
      .get

  def availableIds(kb: KBucket): Set[Int] = {
    val ids = kb.nodes.value.map(v => BigInt(v.nodeId.value.toByteArray).toInt)
    Set.range(kb.from.value.toInt, kb.to.value.toInt) &~ ids.toSet
  }

  val tokenGen: Gen[Token] = bitVectorGen(2 * 8).map(Token(_))
}
