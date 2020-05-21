package kademlia

import kademlia.types.KSize
import org.scalacheck.Prop.forAll
import cats.syntax.either._
import cats.instances.list._

class TableSpec extends KSuite {
  val ksize = KSize(5)
  test("add to empty table") {
    forAll(kbucketGen(0, ksize, ksize.value), nodeGen, nodeIdIntGen) {
      (kbucket, node, nodeId) =>
        val table  = Table.empty(nodeId, ksize)
        val result = table.map(_.addNodes(kbucket.nodes.value)).unsafeRunSync()
        result.map(_.size) == 1.asRight
        println(s">> length: ${result.map(_.kbuckets.head.nodes.value.size)}")
        result.map(_.kbuckets.length) == ksize.value.asRight
      /*val expected = kbucket.nodes.value.asRight[Error]
      val actual = result.map(_.kbuckets.head.nodes.value)
      println(s">> expected: $expected")
      println(s">>   actual: $actual")
       expected === actual*/
    }
  }
}
