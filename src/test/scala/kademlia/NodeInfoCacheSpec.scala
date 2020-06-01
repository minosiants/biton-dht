package kademlia
import java.time.{ Clock, Instant, ZoneOffset }

import cats.effect.IO
import kademlia.protocol.InfoHash
import kademlia.types.{ Distance, NodeInfo }
import org.scalacheck.Gen

import scala.concurrent.duration._
import org.scalacheck.Prop.forAll
import scodec.bits.BitVector
import cats.syntax.option._
import cats.syntax.eq._
import cats.instances.list._
import cats.instances.option._
import cats.syntax.flatMap._

class NodeInfoCacheSpec extends KSuite {
  val _clock: Clock = Clock.systemDefaultZone()

  test("put/get") {
    forAll(infoHashGen, nodeInfoListGen) { (infohash, nodesInfo) =>
      val result = (for {
        cache  <- NodeInfoCache.create(1.second)(ioTimer, _clock)
        _      <- cache.put(infohash, nodesInfo)
        result <- cache.get(infohash)
      } yield result).unsafeRunSync()

      result === nodesInfo.some
    }
  }
  test("expired") {

    forAll(infoHashGen, nodeInfoListGen) { (infohash, nodesInfo) =>
      val result = (for {
        cache  <- NodeInfoCache.create(10.millis)(ioTimer, _clock)
        _      <- cache.put(infohash, nodesInfo)
        result <- ioTimer.sleep(11.millis) >> cache.get(infohash)
      } yield result).unsafeRunSync()

      result === none[List[NodeInfo]]
    }
  }

  val distanceGen: Gen[Distance] =
    Gen.negNum[Long].map(BigInt(_)).map(Distance(_))

  val nodeInfoGen: Gen[NodeInfo] = for {
    token <- tokenGen
    node  <- nodeGen()
  } yield NodeInfo(token, node)

  val nodeInfoListGen: Gen[List[NodeInfo]] = Gen.listOf(nodeInfoGen)
}
