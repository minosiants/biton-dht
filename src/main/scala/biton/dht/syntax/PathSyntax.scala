package biton.dht
package syntax

import java.nio.file.Path

trait PathSyntax {
  implicit def pathSyntax(path: Path): PathOps = new PathOps(path)
}

final class PathOps(val path: Path) extends AnyVal {

  def /(el: String): Path =
    path.resolve(el)
}
