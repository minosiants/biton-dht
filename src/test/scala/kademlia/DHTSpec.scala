package kademlia

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.{ Files, Path }

import cats.effect._
import cats.syntax.either._
import cats.syntax.show._
import fs2.io.udp.SocketGroup
import fs2.Stream
import boopickle.Default._
import kademlia.types.NodeId
import scodec.bits.BitVector
import java.io.{
  FileInputStream,
  FileOutputStream,
  ObjectInputStream,
  ObjectOutputStream
}

class DHTSpec extends KSuite with TableFunctions {
  test("bootstrap") {

    val bs = Blocker[IO]
      .use { blocker =>
        SocketGroup[IO](blocker).use { sg =>
          DHT.bootstrap(sg)
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

    def objectOutputStream(path: Path): Resource[IO, ObjectOutputStream] = {
      Resource.make {
        IO {
          val fileOutputStream = new FileOutputStream(path.toString)
          new ObjectOutputStream(fileOutputStream)
        }
      } { o =>
        IO {
          o.flush()
          o.close()
        }
      }
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
        _.kbuckets.head.nodes.value.map(_.nodeId.value.toBin).mkString("\n")
      )
    )
    true
  }

  test("load") {
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
    val tableRes = objectInputStream(
      Path.of("2bec44347b590a91f42c5bbd337494bda22d191d.kad")
    ).use { o =>
      IO(o.readObject().asInstanceOf[KTable])
    }
    val res = (for {
      table <- tableRes
      _ = println(table)
    } yield table).attempt.unsafeRunSync().toOption.get

    println(res.isFull)
    println(res.nodeId.value.toHex)
    // println(formatBucket(res.kbuckets.head))
    //println(formatBucket(res.kbuckets.tail.head))
    println(res.kbuckets.map(_.prefix.value.toBin).toVector.mkString("\n"))

  }

  test("bla".only) {
    val v1 = BitVector.fromByte(256.toByte)
    val v2 = v1.set(7)

    println(BitVector(Math.pow(2, 8).toByte).toBin)

    println(BitVector(Math.pow(2, 7).toByte).toBin)
    println(v2.toBin)

    println(BitVector(Math.pow(2, 6).toByte).toBin)
    println(BitVector(Math.pow(2, 5).toByte).toBin)
    println(BitVector(Math.pow(2, 4).toByte).toBin)
    println(BitVector(Math.pow(2, 3).toByte).toBin)
    println(BitVector(Math.pow(2, 2).toByte).toBin)
    println(BitVector(Math.pow(2, 1).toByte).toBin)
    println(BitVector(Math.pow(2, 0).toByte).toBin)

  }

}
