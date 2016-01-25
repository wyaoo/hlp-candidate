/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.hyperledger.network

import org.scalatest._
import scodec.Attempt
import scodec.bits._

/**
 * Helps dealing with scodec.Attempt values in tests:
 *
 * {{{
 * codec.encode(x).value should hasPrefix(0x12.toByte)
 * }}}
 */
trait AttemptValues {
  import matchers._

  implicit def convertAttemptToValuable[T](attempt: Attempt[T]): Valuable[T] = new Valuable(attempt)

  /**
   * Wraps a scodec.Attempt, adding a method `value`. The method is the same
   * as scoder.Attermp#require, but throws TestFailedException.
   */
  class Valuable[T](attempt: Attempt[T]) {
    def value: T = try {
      attempt.require
    } catch {
      case cause: IllegalArgumentException =>
        throw new exceptions.TestFailedException(sde => Some("Parser attempt failed"), Some(cause), _.failedCodeStackDepth)
    }
  }

  def hasPrefix(right: Byte): Matcher[BitVector] = new Matcher[BitVector] {
    def apply(left: BitVector) = MatchResult(
      left.size > 8 && left.getByte(0) == right,
      "{0} do not start with {1}",
      "{0} starts with {1}",
      Vector(left, BitVector.fromByte(right)),
      Vector(left, BitVector.fromByte(right))
    )
  }
}

object AttemptValues extends AttemptValues

