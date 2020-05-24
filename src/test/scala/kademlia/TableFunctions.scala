package kademlia

trait TableFunctions {
  def formatBucket(kb: KBucket): String = {
    val prefix   = kb.prefix.value.toBin
    val nodeIds  = kb.nodes.value.map(_.nodeId.value.toBin).mkString("\n")
    val cacheIds = kb.cache.value.value.map(_.nodeId.value.toBin).mkString("\n")
    s"""
       |===========================================
       |prefix
       |$prefix
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
    t.kbuckets.map(_.prefix.value.toBin).toVector.mkString("\n")
  }
}
