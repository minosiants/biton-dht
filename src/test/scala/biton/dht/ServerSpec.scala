package biton.dht

import biton.dht.Conf.{ GoodDuration, SecretExpiration }
import biton.dht.protocol.KMessage
import cats.effect.{ Blocker, IO }
import com.comcast.ip4s.{ IpAddress, Port }
import fs2._
import fs2.io.udp.SocketGroup
import types.{ Contact, KSize, Node, NodeId, Prefix }
import cats.syntax.either._
import cats.syntax.show._

import scala.concurrent.duration._

class ServerSpec extends KSuite {

  val serverNode = (for {
    ip   <- IpAddress("127.0.0.1")
    port <- Port(2222)
  } yield Node(NodeId.fromBigInt(2), Contact(ip, port))).get

  def kbucket(to: Int = 30): KBucket =
    kbucketGen(Prefix(0), Prefix(BigInt(to)), KSize(10), 10).sample.get

  def tableState(pingClient: Client.Ping): IO[TableState] = {
    val nodes = kbucket().nodes.filterNot(serverNode).value.toList.map(_.node)
    println(s"nodes: $nodes")
    for {
      ts <- TableState.empty(
        serverNode.nodeId,
        pingClient,
        GoodDuration(1.minute)
      )
      _ <- ts.addNodes(nodes)
    } yield ts
  }
  val clientNodeId = nodeIdCharGen.sample.get
  def sendToServer[A](f: Client => Stream[IO, A]): IO[A] =
    Blocker[IO]
      .use { blocker =>
        SocketGroup[IO](blocker).use { sg =>
          val client = Client(clientNodeId, sg)
          for {
            secrets <- Secrets.create(SecretExpiration(1.minute))
            _ = println("before state")
            table <- tableState(client)
            s     <- table.get
            _ = println(s.kbuckets.collect(_.nodes.value.size))
            store <- PeerStore.inmemory()
            server = Server(
              serverNode.nodeId,
              table,
              store,
              secrets,
              sg,
              serverNode.contact.port
            )
            rs <- f(client)
              .delayBy(1.second)
              .concurrently(server.start())
              .compile
              .toList
              .map(_.head)
          } yield rs

        }
      }

  test("ping") {
    val result = sendToServer(_.ping(serverNode)).attempt.unsafeRunSync()
    result.leftMap {
      case e: Error => println(e.show)
    }
    assert(result.isRight)
  }

  test("findNode") {
    val result =
      sendToServer(_.findNode(serverNode.contact, serverNode.nodeId)).attempt
        .unsafeRunSync()

    println(result)
    assert(result.isRight)

  }

}

object ServerSpec {
  final case class PingClient() extends Client.Ping {
    override def ping(node: Node): Stream[IO, KMessage.NodeIdResponse] = ???
  }
  object PingClient {}
}
