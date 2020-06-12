package biton.dht

import java.time.Clock

import biton.dht.Conf.CacheExpiration
import biton.dht.types.{ Distance, NodeInfo }
import cats.instances.list._
import cats.instances.option._
import cats.syntax.eq._
import cats.syntax.flatMap._
import cats.syntax.option._
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

import scala.concurrent.duration._

class NodeInfoCacheSpec extends KSuite {
  val _clock: Clock = Clock.systemDefaultZone()

  test("put/get") {
    forAll(infoHashGen, nodeInfoListGen) { (infohash, nodesInfo) =>
      val result = (for {
        cache <- NodeInfoCache.create(CacheExpiration(1.second))(
          ioTimer,
          _clock
        )
        _      <- cache.put(infohash, nodesInfo)
        result <- cache.get(infohash)
      } yield result).unsafeRunSync()

      result === nodesInfo.some
    }
  }
  test("expired") {

    forAll(infoHashGen, nodeInfoListGen) { (infohash, nodesInfo) =>
      val result = (for {
        cache <- NodeInfoCache.create(CacheExpiration(10.millis))(
          ioTimer,
          _clock
        )
        _      <- cache.put(infohash, nodesInfo)
        result <- ioTimer.sleep(11.millis) >> cache.get(infohash)
      } yield result).unsafeRunSync()

      result === none[List[NodeInfo]]
    }
  }

  val distanceGen: Gen[Distance] =
    Gen.negNum[Long].map(BigInt(_)).map(Distance(_))

}
