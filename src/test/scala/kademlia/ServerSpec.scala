package kademlia

import cats.effect.{ Blocker, IO }
import com.comcast.ip4s.{ IpAddress, Port }
import fs2._
import fs2.io.udp.SocketGroup
import kademlia.types.Node

class ServerSpec extends KSuite {
  val serverNode = (for {
    id   <- nodeIdCharGen.sample
    ip   <- IpAddress("127.0.0.1")
    port <- Port(2222)
  } yield Node(id, ip, port)).get

  val clientNodeId = nodeIdCharGen.sample.get
  def sendToServer[A](f: Client => Stream[IO, A]): IO[A] =
    Blocker[IO]
      .use { blocker =>
        SocketGroup[IO](blocker).use { sg =>
          val s = Server.start(sg, serverNode.nodeId, serverNode.port)
          val c = f(Client(clientNodeId, serverNode, sg))
          Client.extract(c.concurrently(s))

        }
      }
  test("ping") {
    val result = sendToServer(_.ping(serverNode.nodeId)).attempt.unsafeRunSync()
    result.isRight
  }

  test("findNode") {
    val result =
      sendToServer(_.findNode(serverNode.nodeId)).attempt.unsafeRunSync()
    result.isRight
  }
}
