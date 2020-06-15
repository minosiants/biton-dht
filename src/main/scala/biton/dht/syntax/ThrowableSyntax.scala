/*
 * Copyright 2020 Kaspar Minosiants
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package biton.dht.syntax

import biton.dht.Error
import cats.syntax.show._

trait ThrowableSyntax {
  implicit def kadThrowableSyntax(error: Throwable) = new ThrowableOps(error)
}

final class ThrowableOps(val error: Throwable) extends AnyVal {
  def string: String = error match {
    case e: Error => s"Error: ${e.show}"
    case e: Throwable =>
      val msg = if (e.getMessage != null) s"message: ${e.getMessage}" else ""
      s"Error: $e $msg"

  }
}
