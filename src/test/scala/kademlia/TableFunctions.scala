package kademlia

trait TableFunctions {
  def formatBucket(kb: KBucket): String = {
    val from    = kb.from.value
    val to      = kb.to.value
    val nodeIds = kb.nodes.value.map(_.nodeId.toPrefix.value).mkString("\n")
    val cacheIds =
      kb.cache.value.value.map(_.nodeId.toPrefix.value).mkString("\n")
    s"""
       |===========================================
       |bucket
       |$from = $to
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
