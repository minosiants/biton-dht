package kademlia

import cats.effect.{ Blocker, IO }
import fs2.io.udp.SocketGroup
import kademlia.protocol.Peer
import scodec.bits.BitVector

class DHTSpec extends KSuite {
  test("bal") {
    val res = Blocker[IO]
      .use { blocker =>
        SocketGroup[IO](blocker).use { sg =>
          DHT.bootstrap(sg)

        }
      }

    println(res.map(_.nodes.size).attempt.unsafeRunSync())
    true
  }
}
