package biton.dht

import java.time.{ Clock, LocalDateTime }

import biton.dht.KBucket.FullBucket
import biton.dht.TraversalNode.{ Fresh, Responded, Stale }
import biton.dht.TraversalTable.{ Completed, InProgress }
import biton.dht.types._
import cats.Show
import cats.data.NonEmptyVector
import cats.effect.IO
import cats.instances.int._
import cats.syntax.either._
import cats.syntax.eq._
import cats.syntax.foldable._
import cats.syntax.option._
import cats.syntax.show._
import fs2.{ Pure, Stream }

import scala.annotation.tailrec
import scala.concurrent.duration.FiniteDuration

trait Table extends Product with Serializable {
  def nodeId: NodeId
  def bsize: Long = kbuckets.size
  def size: Long  = kbuckets.map(_.nodes.value.size).fold
  def kbuckets: NonEmptyVector[KBucket]
  def addNode(node: Node): IO[Table]
  def addNodes(nodes: List[Node]): IO[Table]
  def neighbors(nodeId: NodeId): List[Node]
  def markNodeAsBad(node: Node): IO[Table]
  def markNodesAsBad(node: List[Node]): IO[Table]
  def outdated: Vector[KBucket]
}

object Table {

  type IndexKBucket = (KBucket, Index)

  def empty(
      nodeId: NodeId,
      client: Client.Ping,
      outdatedPeriod: FiniteDuration,
      ksize: KSize = KSize(8),
      lowerPrefix: Prefix = Prefix(0),
      higherPrefix: Prefix = Prefix(BigInt(2).pow(160))
  )(implicit clock: Clock): Result[Table] = {

    val nodes = Nodes(Vector.empty, ksize)
    for {
      b <- KBucket.create(lowerPrefix, higherPrefix, nodes)
    } yield KTable(nodeId, NonEmptyVector.of(b), client, outdatedPeriod)

  }
}

final case class KTable(
    nodeId: NodeId,
    kbuckets: NonEmptyVector[KBucket],
    client: Client.Ping,
    refreshPeriod: FiniteDuration
)(implicit val clock: Clock)
    extends Table {

  def findBucketFor(
      id: NodeId
  ): Result[Table.IndexKBucket] = {
    kbuckets.zipWithIndex
      .find { case (kb, _) => kb.inRange(id) }
      .map { case (kb, i) => (kb, Index(i)) }
      .toRight(Error.KBucketError(s"bucket for $nodeId not found"))
  }
  def pingQuestionable(nodes: List[Node]): IO[(Option[Node], List[Node])] = {
    Stream
      .emits(nodes)
      .flatMap { n =>
        client.ping(n).attempt.map(_.leftMap(_ => n).map(_ => n))
      }
      .takeThrough(_.isRight)
      .compile
      .toList
      .map {
        _.foldLeft((none[Node], List.empty[Node])) {
          case ((la, ra), el) =>
            el match {
              case Left(v)  => v.some -> ra
              case Right(v) => la     -> (v :: ra)
            }
        }
      }
  }
  def addNodeToBucket(
      node: Node,
      bucket: KBucket
  ): IO[NonEmptyVector[KBucket]] = {

    def split: Either[Error, NonEmptyVector[KBucket]] = {
      bucket.split().flatMap {
        case (first, second) =>
          if (first.inRange(node.nodeId))
            first.addOne(node) map (NonEmptyVector.of(_, second))
          else
            second.addOne(node) map (NonEmptyVector.of(first, _))
      }
    }

    bucket match {
      case FullBucket(_, _, _, _)
          if bucket.inRange(nodeId) && bucket.canSplit =>
        IO.fromEither(split)
      case b @ FullBucket(_, _, _, _) =>
        b.findBad.fold {
          pingQuestionable(b.questionable)
            .map {
              case (Some(n), xs) => b.swap(n, node).flatMap(_.add(xs: _*))
              case (None, xs)    => b.add(xs: _*)
            }
            .flatMap(v => IO.fromEither(v.map(NonEmptyVector.one)))
        } { v =>
          IO.fromEither(b.swap(v, node).map(v => NonEmptyVector.one(v)))
        }

      case bucket =>
        IO.fromEither(bucket.addOne(node).map(NonEmptyVector.one))
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

  override def addNode(node: Node): IO[Table] = {

    for {
      (kb, i) <- IO.fromEither(findBucketFor(node.nodeId))
      list    <- addNodeToBucket(node, kb)
      _   = list.toVector.head.nodes
      res = insertBuckets(i, list.reverse)
    } yield this.copy(kbuckets = res)
  }

  override def addNodes(nodes: List[Node]): IO[Table] = {
    @tailrec
    def go(t: IO[Table], _nodes: List[Node]): IO[Table] = {
      _nodes match {
        case Nil => t
        case x :: xs =>
          go(t.flatMap(_.addNode(x)), xs)
      }
    }
    go(IO(this), nodes)
  }

  override def neighbors(nodeId: NodeId): List[Node] = {
    assert(kbuckets.nonEmpty)
    def go(
        left: Vector[KBucket],
        right: Vector[KBucket],
        current: KBucket,
        isLeft: Boolean
    ): Stream[Pure, NodeActivity] = {
      (left, right, current) match {
        case (IndexedSeq(), IndexedSeq(), bucket) =>
          Stream.emits(bucket.nodes.value)
        case (x +: tail, r, bucket) if isLeft =>
          Stream.emits(bucket.nodes.value) ++ go(tail, r, x, false)
        case (l, x +: tail, bucket) if !isLeft =>
          Stream.emits(bucket.nodes.value) ++ go(l, tail, x, true)
        case (x +: tail, IndexedSeq(), bucket) =>
          Stream.emits(bucket.nodes.value) ++ go(tail, Vector.empty, x, false)
        case (IndexedSeq(), x +: tail, bucket) =>
          Stream.emits(bucket.nodes.value) ++ go(Vector.empty, tail, x, false)
      }
    }

    val result = for {
      (kb, i) <- findBucketFor(nodeId)
      (left, right) = kbuckets.toVector.splitAt(i.value)
    } yield go(left.reverse, right, kb, true)
      .filter(_.node.nodeId =!= nodeId)
      .take(kb.nodes.ksize.value)
      .compile
      .toList
      .sortBy(v => v.node.nodeId.distance(nodeId))
      .map(_.node)

    result.fold(_ => List.empty, identity)
  }

  override def markNodeAsBad(node: Node): IO[Table] = {
    IO.fromEither(for {
      (kb, i) <- findBucketFor(node.nodeId)
      kb1     <- kb.fail(node)
      updated = insertBuckets(i, NonEmptyVector.of(kb1))
    } yield this.copy(kbuckets = updated))
  }

  override def markNodesAsBad(node: List[Node]): IO[Table] = {
    node match {
      case Nil     => IO(this)
      case x :: xs => markNodeAsBad(x).flatMap(_.markNodesAsBad(xs))
    }
  }

  override def outdated: Vector[KBucket] =
    kbuckets
      .filter(
        _.lastUpdated
          .plusNanos(refreshPeriod.toNanos)
          .isBefore(LocalDateTime.now(clock))
      )

}

sealed abstract class TraversalNode[A] {
  def distance: Distance
  def node: Node
  def fold[B](
      fresh: (Node, Distance) => B,
      stale: (Node, Distance) => B,
      responded: (Node, A, Distance) => B
  ): B = this match {
    case Fresh(node, distance) => fresh(node, distance)
    case Stale(node, distance) => stale(node, distance)
    case r: Responded[A]       => responded(r.node, r.info, distance)
  }
  def isFresh: Boolean =
    fold((_, _) => true, (_, _) => false, (_, _, _) => false)
  def nonFresh: Boolean = !isFresh
  def isStale: Boolean =
    fold((_, _) => false, (_, _) => true, (_, _, _) => false)
  def nonStale: Boolean = !isStale
  def isResponded: Boolean =
    fold((_, _) => false, (_, _) => false, (_, _, _) => true)
  def nonResponded: Boolean = !isResponded

}
object TraversalNode {
  final case class Fresh[A](node: Node, distance: Distance)
      extends TraversalNode[A]
  final case class Stale[A](node: Node, distance: Distance)
      extends TraversalNode[A]
  final case class Responded[A](node: Node, info: A, distance: Distance)
      extends TraversalNode[A]

  implicit def showTraversalNode[A]: Show[TraversalNode[A]] = Show.show {
    case Fresh(node, distance) =>
      s"Fresh:     ${node.nodeId.toBigInt} distance: $distance"
    case Stale(node, distance) =>
      s"Stale:     ${node.nodeId.toBigInt} distance: $distance"
    case Responded(node, _, distance) =>
      s"Responded: ${node.nodeId.toBigInt} distance: $distance"
  }
}

sealed abstract class TraversalTable[A] {
  def target: NodeId
  def nodes: List[TraversalNode[A]]
  def completeSize: Int
  def markNodesAsStale(n: List[Node]): TraversalTable[A] = {
    n match {
      case Nil     => this
      case x :: xs => markNodeAsStale(x).markNodesAsStale(xs)
    }
  }

  def markNodeAsStale(n: Node): TraversalTable[A] = {
    updateNode(n) { x =>
      Stale(n, x.distance)
    }
  }
  def markNodesAsResponded(n: List[A])(f: A => Node): TraversalTable[A] = {
    n match {
      case Nil     => this
      case x :: xs => markNodeAsResponded(x)(f).markNodesAsResponded(xs)(f)
    }
  }
  def markNodeAsResponded(n: A)(f: A => Node): TraversalTable[A] = {
    updateNode(f(n)) { x =>
      Responded(f(n), n, x.distance)
    }
  }

  def topFresh(n: Int): List[Node] =
    nodes.collect { case Fresh(node, _) => node }.take(n)

  def topResponded(n: Int): List[A] =
    nodes
      .collect {
        case r: Responded[A] => r.info
      }
      .take(n)

  def updateNode(
      n: Node
  )(f: TraversalNode[A] => TraversalNode[A]): TraversalTable[A] = {
    def isComplete(l: List[TraversalNode[A]]): Boolean = {
      l.takeWhile(_.nonFresh).count(_.isResponded) >= completeSize
    }

    nodes.zipWithIndex.find { case (el, _) => el.node.nodeId === n.nodeId } match {
      case None => this
      case Some((node, i)) =>
        this match {
          case Completed(target, _, s) =>
            Completed(target, nodes.updated(i, f(node)), s)
          case TraversalTable.InProgress(target, _, s) =>
            val _nodes = nodes.updated(i, f(node))
            if (isComplete(_nodes))
              Completed(target, _nodes, s)
            else
              InProgress(target, _nodes, s)
        }
    }
  }

}

object TraversalTable {
  final case class InProgress[A](
      target: NodeId,
      nodes: List[TraversalNode[A]],
      completeSize: Int
  ) extends TraversalTable[A] {
    def addNodes(n: List[Node]): TraversalTable[A] = {
      val nodesToAdd =
        n.distinct.filterNot(v => nodes.exists(_.node.nodeId === v.nodeId))
      val result: List[TraversalNode[A]] = nodesToAdd.map(
        v => Fresh(v, v.nodeId.distance(target))
      )
      TraversalTable.InProgress(
        target,
        (result ++ nodes).sortBy(_.distance),
        completeSize
      )
    }

  }

  final case class Completed[A](
      target: NodeId,
      nodes: List[TraversalNode[A]],
      completeSize: Int
  ) extends TraversalTable[A]

  def create[A](
      nodeId: NodeId,
      nodes: List[Node],
      completeSize: Int
  ): TraversalTable[A] =
    InProgress(nodeId, Nil, completeSize).addNodes(nodes)

  def log(l: List[TraversalNode[_]]): String =
    s"""\n ${l.map(_.show).mkString("\n")}"""
}
