package kademlia

trait TableFunctions {
  def formatBucket(kb: KBucket): String = {
    val prefix   = kb.prefix.toDecStr
    val nodeIds  = kb.nodes.value.map(_.nodeId.toDecStr).mkString("\n")
    val cacheIds = kb.cache.value.value.map(_.nodeId.toDecStr).mkString("\n")
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
    t.kbuckets.map(_.prefix.toDecStr).toVector.mkString("\n")
  }
}
