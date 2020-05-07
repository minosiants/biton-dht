package kademlia

import java.time.{ Clock, Instant, LocalDateTime, ZoneOffset }

import cats.effect.specs2.CatsIO
import com.comcast.ip4s.IpAddress
import kademlia.KBucket.Cache
import kademlia.types._
import org.scalacheck.Gen
import org.specs2.ScalaCheck
import org.specs2.mutable.Specification
import scodec.bits.BitVector

class KSpec extends Specification with ScalaCheck with CatsIO {

  implicit val clock: Clock = Clock.fixed(Instant.now(), ZoneOffset.UTC)

  val nodeIdIntGen: Gen[NodeId] =
    Gen.chooseNum(0, Integer.MAX_VALUE).map(NodeId.fromInt)

  val nodeIdCharGen: Gen[NodeId] =
    Gen
      .infiniteStream(Gen.chooseNum(0, 255))
      .map(_.take(20).toList)
      .map(v => NodeId(BitVector(v.map(_.toByte))))

  val byteGen: Gen[Byte] = Gen.chooseNum(0, 255).map(_.byteValue())

  val ipV4Gen: Gen[IpAddress] =
    Gen
      .infiniteStream(byteGen)
      .map(_.take(4).toArray)
      .map(IpAddress.fromBytes(_).get)

  val nodeGen: Gen[Node] = for {
    ip   <- ipV4Gen
    id   <- nodeIdCharGen
    port <- Gen.posNum[Int]
  } yield Node(id, ip, port, LocalDateTime.now(clock))

  def listOfNodesGen(size: Int): Gen[List[Node]] =
    Gen
      .infiniteStream(nodeGen)
      .map(_.take(size * 40).toSet.take(size).toList)
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
