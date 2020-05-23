package kademlia

import java.time.Clock

import cats.data.NonEmptyVector
import cats.effect.{ Concurrent, IO }
import cats.instances.either._
import cats.instances.vector._
import cats.syntax.either._
import cats.syntax.foldable._
import kademlia.KBucket.{ Cache, FullBucket }
import kademlia.types._
import cats.instances.int._
import cats.syntax.eq._
import scala.annotation.tailrec

trait Table extends Product with Serializable {
  def nodeId: NodeId
  def bsize: Long = kbuckets.size
  def size: Long  = kbuckets.map(_.nodes.value.size).fold
  def kbuckets: NonEmptyVector[KBucket]
  def addNode(node: Node): Result[Table]
  def addNodes(nodes: List[Node]): Result[Table]
  def isFull: Boolean =
    kbuckets.head.prefix === Table.lastPrefix && kbuckets.head.isFull
  def nonFull: Boolean = !isFull
}

object Table {
  val lastPrefix = Prefix(highestNodeId)

  type IndexKBucket = (Index, KBucket)

  def empty(
      nodeId: NodeId,
      ksize: KSize = KSize(8)
  )(implicit c: Concurrent[IO], clock: Clock): IO[Table] = {
    val prefix = Prefix(lowestNodeId)
    val nodes  = Nodes(List.empty, ksize)
    val cache  = Cache(Nodes(List.empty, ksize * 3))
    for {
      b <- IO.fromEither(KBucket.create(prefix, nodes, cache))
    } yield KTable(nodeId, NonEmptyVector.of(b))

  }
}

final case class KTable(
    nodeId: NodeId,
    kbuckets: NonEmptyVector[KBucket]
)(implicit val c: Clock)
    extends Table {

  def findBucketFor(
      node: Node
  ): Table.IndexKBucket = {
    (kbuckets.head, kbuckets.tail) match {
      case (h, IndexedSeq()) =>
        (Index(0), h)
      case (h, tail) =>
        val res = tail.foldM[Either[Table.IndexKBucket, *], Table.IndexKBucket](
          (Index(0), h)
        ) {
          case ((i, fb), sb) =>
            node.nodeId.closest(fb.prefix.toNodeId, sb.prefix.toNodeId)(
              (i, fb).asLeft,
              (i add 1, sb).asRight
            )

        }
        res.fold(identity, identity)
    }
  }
  def addNodeToBucket(
      node: Node,
      bucket: KBucket
  ): Result[NonEmptyVector[KBucket]] = {
    (nonFull, bucket.prefix === kbuckets.head.prefix, bucket) match {
      case (true, true, b @ FullBucket(_, _, _, _)) =>
        //Is one split enough ?
        b.split().flatMap {
          case (first, second) =>
            node.nodeId.closest(first.prefix.toNodeId, second.prefix.toNodeId)(
              first.add(node) orElse first
                .addToCache(node) map (NonEmptyVector.of(_, second)),
              second.add(node) orElse second
                .addToCache(node) map (NonEmptyVector.of(first, _))
            )
        }
      case (false, true, b @ FullBucket(_, _, _, _)) =>
        b.addToCache(node) map (NonEmptyVector.of(_))

      case (_, _, bucket) =>
        (bucket.add(node) orElse bucket.addToCache(node))
          .map(NonEmptyVector.one)
    }

  }

  def insertBuckets(
      index: Index,
      buckets: NonEmptyVector[KBucket]
  ): NonEmptyVector[KBucket] = {
    val vec = kbuckets.toVector
    val newVec = vec.take(index.value) ++ buckets.toVector ++ vec.drop(
      index.value + 1
    )
    NonEmptyVector(newVec.head, newVec.drop(1))
  }

  override def addNode(node: Node): Result[Table] = {
    val (i, kb) = findBucketFor(node)
    for {
      list <- addNodeToBucket(node, kb)
      res = insertBuckets(i, list.reverse)
    } yield KTable(nodeId, res)
  }

  override def addNodes(nodes: List[Node]): Result[Table] = {
    @tailrec
    def go(t: Result[Table], _nodes: List[Node]): Result[Table] = {
      (t, _nodes) match {
        case (e @ Left(_), _) => e
        case (_, Nil)         => t
        case (_, x :: xs) =>
          go(t.flatMap(_.addNode(x)), xs)
      }
    }
    go(this.asRight, nodes)
  }

}
