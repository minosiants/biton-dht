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

import cats.syntax.apply._

import scala.annotation.tailrec

trait Table extends Product with Serializable {
  def nodeId: NodeId
  def bsize: Long = kbuckets.size
  def size: Long  = kbuckets.map(_.nodes.value.size).fold
  def kbuckets: NonEmptyVector[KBucket]
  def addNode(node: Node): Result[Table]
  def addNodes(nodes: List[Node]): Result[Table]
}

object Table {

  type IndexKBucket = (KBucket, Index)

  def empty(
      nodeId: NodeId,
      ksize: KSize = KSize(8),
      lowerPrefix: Prefix = Prefix(0),
      higherPrefix: Prefix = Prefix(BigInt(2).pow(160))
  )(implicit clock: Clock): Result[Table] = {

    val nodes = Nodes(List.empty, ksize)
    val cache = Cache(Nodes(List.empty, ksize * 3))
    for {
      b <- KBucket.create(lowerPrefix, higherPrefix, nodes, cache)
    } yield KTable(nodeId, NonEmptyVector.of(b))

  }
}

final case class KTable(
    nodeId: NodeId,
    kbuckets: NonEmptyVector[KBucket]
)(implicit val c: Clock)
    extends Table {

  def findBucketFor(
      id: NodeId
  ): Result[Table.IndexKBucket] = {
    kbuckets.zipWithIndex
      .find { case (kb, _) => kb.inRange(id) }
      .map { case (kb, i) => (kb, Index(i)) }
      .toRight(Error.KBucketError(s"bucket for $nodeId not found"))
  }
  def addNodeToBucket(
      node: Node,
      bucket: KBucket
  ): Result[NonEmptyVector[KBucket]] = {

    (bucket.canSplit, bucket.inRange(nodeId), bucket) match {
      case (true, true, b @ FullBucket(_, _, _, _, _)) =>
        b.split().flatMap {
          case (first, second) =>
            if (first.inRange(node.nodeId))
              (first.add(node) orElse first
                .addToCache(node)) map (NonEmptyVector.of(_, second))
            else
              (second.add(node) orElse second
                .addToCache(node)) map (NonEmptyVector.of(first, _))
        }
      case (_, false, b @ FullBucket(_, _, _, _, _)) =>
        b.addToCacheIfNew(node) map (NonEmptyVector.of(_))

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

    for {
      (kb, i) <- findBucketFor(node.nodeId)
      list    <- addNodeToBucket(node, kb)
      _   = list.toVector.head.nodes
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
