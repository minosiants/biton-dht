package kademlia

import java.time.LocalDateTime

import cats.data.NonEmptyList
import cats.implicits._
import io.estatico.newtype.macros._
import kademlia.KBucket.{ Cache, splitNodes }
import kademlia.types._

sealed abstract class KBucket extends Product with Serializable {
  def prefix: Prefix
  def nodes: Nodes
  def cache: Cache
  def lastUpdated: LocalDateTime

  private def nextPrefix: Result[Prefix] =
    Either.cond(
      prefix.value != highestNodeId,
      Prefix((prefix.value.not >>> 1).not),
      Error.KBucketError(
        s"Not aible to create next prefix. Already the last one - $prefix "
      )
    )

  def split(): Result[(KBucket, KBucket)] = this match {
    case b @ KBucket.EmptyBucket(_, _, _, _) =>
      Error.KBucketError(s"Can not split empty bucket $b").asLeft
    case b @ KBucket.Bucket(_, _, _, _) =>
      Error.KBucketError(s"Can not split not full  bucket $b").asLeft
    case KBucket.FullBucket(prefix, nodes, cache, _) =>
      for {
        newPref  <- nextPrefix
        (f, s)   <- splitNodes(nodes, prefix, newPref)
        (cf, cs) <- splitNodes(cache.value, prefix, newPref)
        first    <- KBucket.create(prefix, f, Cache(cf))
        second   <- KBucket.create(newPref, s, Cache(cs))
      } yield (first, second)
  }

  def addToCache(node: Node): Result[KBucket] = {
    val newCache = Cache(cache.value.filterNot(node).dropAndPrepended(node))
    KBucket.create(prefix, nodes, newCache)
  }

  def add(node: Node): Result[KBucket] = this match {

    case KBucket.EmptyBucket(prefix, nodes, cache, _) =>
      for {
        n <- nodes.append(node)
        b <- KBucket.create(prefix, n, cache)
      } yield b

    case KBucket.Bucket(prefix, nodes, cache, _) =>
      for {
        n <- nodes.filterNot(node).append(node)
        b <- KBucket.create(prefix, n, cache)
      } yield b

    case b @ KBucket.FullBucket(_, _, _, _) =>
      Error.KBucketError(s"$b is full").asLeft[KBucket]
  }
}

object KBucket {

  @newtype final case class Cache(value: Nodes)

  final case class EmptyBucket(
      prefix: Prefix,
      nodes: Nodes,
      cache: Cache,
      lastUpdated: LocalDateTime
  ) extends KBucket

  final case class FullBucket(
      prefix: Prefix,
      nodes: Nodes,
      cache: Cache,
      lastUpdated: LocalDateTime
  ) extends KBucket

  final case class Bucket(
      prefix: Prefix,
      nodes: Nodes,
      cache: Cache,
      lastUpdated: LocalDateTime
  ) extends KBucket

  //add more validation
  def create(
      prefix: Prefix,
      nodes: Nodes,
      cache: Cache
  ): Result[KBucket] = {
    if (nodes.isFull)
      FullBucket(prefix, nodes, cache, LocalDateTime.now()).asRight
    else if (nodes.isEmpty)
      EmptyBucket(prefix, nodes, cache, LocalDateTime.now()).asRight
    else
      Bucket(prefix, nodes, cache, LocalDateTime.now()).asRight
  }

  object splitNodes {

    def apply(
        nodes: Nodes,
        prefix: Prefix,
        newPref: Prefix
    ): Result[(Nodes, Nodes)] = {
      def emptyNodes() = Nodes(List.empty, nodes.size)
      val result = nodes.value.foldLeft(
        (emptyNodes().asRight[Error], emptyNodes().asRight[Error])
      ) { (acc, node) =>
        val (fst, snd) = acc
        val f          = NodeId(node.nodeId.value ^ prefix.value)
        val s          = NodeId(node.nodeId.value ^ newPref.value)
        if (f < s)
          (fst.flatMap(_.prepend(node)), snd)
        else
          (fst, snd.flatMap(_.prepend(node)))
      }
      result match {
        case (Left(e1), Left(e2)) =>
          Error.MultiError(NonEmptyList.of(e1, e2)).asLeft
        case (Left(e), _)         => e.asLeft
        case (_, Left(e))         => e.asLeft
        case (Right(f), Right(s)) => (f, s).asRight
      }
    }
  }
}
