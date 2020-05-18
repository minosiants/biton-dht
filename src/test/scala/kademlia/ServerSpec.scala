package kademlia

import cats.effect.{ Blocker, IO }
import com.comcast.ip4s.Port
import fs2.io.udp.SocketGroup

class ServerSpec extends KSuite {
  test("bla") {
    val server = Blocker[IO]
      .use { blocker =>
        SocketGroup[IO](blocker).use { socketGroup =>
          Server.start(socketGroup, Port(2222).get).compile.drain
        }
      }

    val res = server.attempt.unsafeRunSync()
    println(res)
    true
  }
}
