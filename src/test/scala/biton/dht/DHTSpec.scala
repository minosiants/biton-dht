package biton.dht

import java.io.{
  FileInputStream,
  FileOutputStream,
  ObjectInputStream,
  ObjectOutputStream
}
import java.nio.file.Path

import cats.effect._
import cats.syntax.either._
import cats.syntax.show._
import com.comcast.ip4s.Port
import fs2.io.udp.SocketGroup
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import protocol.InfoHash
import types.NodeId
import scodec.bits._
import scala.concurrent.duration._
class DHTSpec extends KSuite with TableFunctions {

  test("bootstrap".ignore) {

    val bs = Blocker[IO]
      .use { blocker =>
        SocketGroup[IO](blocker).use { sg =>
          Secrets.create(1.minute).use { secrets =>
            for {
              store <- PeerStore.inmemory()
              dht   <- DHT.bootstrap(sg, Port(6881).get, store, secrets)
              table <- dht.table
            } yield table
          }
        }
      }

    def saveTable(table: Table): IO[Unit] = IO {
      val fileOutputStream   = new FileOutputStream(fileName(table.nodeId))
      val objectOutputStream = new ObjectOutputStream(fileOutputStream)
      objectOutputStream.writeObject(table)
      objectOutputStream.flush()
      objectOutputStream.close()
    }

    def fileName(nodeId: NodeId): String = {
      s"${nodeId.value.toHex}.kad"
    }

    val res = (for {
      table <- bs
      _     <- saveTable(table)
    } yield table).attempt.unsafeRunSync()

    res.leftMap {
      case e: Error => println(e.show)
    }
    println(res.map(_.nodeId.value.toBin))
    println("--")
    println(
      res.map(
        _.kbuckets.head.nodes.value
          .map(_.node.nodeId.value.toBin)
          .mkString("\n")
      )
    )
    true
  }

  def withDHT[A](tableName: String)(f: DHT => IO[A]): IO[A] = {
    Blocker[IO]
      .use { blocker =>
        SocketGroup[IO](blocker).use { sg =>
          Secrets.create(1.minute).use { secrets =>
            for {
              store <- PeerStore.inmemory()
              table <- loadTable(tableName)
              dht <- DHT
                .fromTable(
                  sg,
                  Port(6881).get,
                  table,
                  15.minutes,
                  15.minutes,
                  store,
                  secrets
                )
              res <- f(dht)
            } yield res
          }
        }
      }
  }
  test("lookup".ignore) {
    val bits      = hex"01c8c9ea65fe48a0bb02127c898bef9644b99fe0"
    val infoHash  = InfoHash(bits.bits)
    val tableName = "ecd4b88252f2699718fb871380cafc8d77b6db5c.kad"
    val peers = withDHT(tableName) { dht =>
      dht.lookup(infoHash).compile.toList
    }

    val result = peers.unsafeRunSync()
    println(result)
    println(result.size)
    true
  }

  def loadTable(name: String): IO[Table] = {
    def objectInputStream(path: Path): Resource[IO, ObjectInputStream] = {
      Resource.make {
        IO {
          val fileInputStream = new FileInputStream(path.toString)
          new ObjectInputStream(fileInputStream)
        }
      } { i =>
        IO {
          i.close()
        }
      }
    }
    objectInputStream(
      Path.of(name)
    ).use { o =>
      IO(o.readObject().asInstanceOf[KTable])
    }

  }

  test("load".ignore) {

    val res = (for {
      table <- loadTable("fffc21a3f289db8057396725b6cd53d4c0759991.kad")
      _ = println(s"nodeId ${table.nodeId.value.toHex}")
      _ = println(table)
    } yield table).attempt.unsafeRunSync().toOption.get
    println(res.nodeId.value.toBin)
    res.kbuckets.toVector.foreach { v =>
      println(formatBucket(v))
    }

    println(res.kbuckets.map(_.from.value).toVector.mkString("\n"))

  }

}

object DHTSpec {
  val logger = Slf4jLogger.getLogger[IO]
}
