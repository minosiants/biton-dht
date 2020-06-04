package biton.dht

import scodec.bits.BitVector

trait TableFunctions {
  def formatBucket(kb: KBucket): String = {
    val from    = BitVector(kb.from.value.toByteArray).toBin
    val to      = BitVector(kb.to.value.toByteArray).toBin
    val nodeIds = kb.nodes.value.map(_.nodeId.value.toBin).mkString("\n")
    val cacheIds =
      kb.cache.value.value.map(_.nodeId.value.toBin).mkString("\n")
    s"""
       |===========================================
       |bucket
       |$from = $to
       |${kb.from} = ${kb.to}
       |------------------------------------------
       |nodeId
       |$nodeIds
       |------------------------------------------
       |cache
       |$cacheIds
       |===========================================
       |""".stripMargin
  }

  def formatPrfexes(t: Table): String = {
    t.kbuckets.map(_.from.value).toVector.mkString("\n")
  }
}
