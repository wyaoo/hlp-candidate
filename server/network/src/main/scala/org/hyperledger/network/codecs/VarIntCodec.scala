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
package org.hyperledger.network.codecs

import scodec.Attempt._
import scodec._
import scodec.bits.BitVector
import scodec.codecs._

private[codecs] class VarIntCodec[T](p: Long => Attempt[T])(implicit val I: Integral[T]) extends Codec[T] {
  val NotMinimal = failure(Err("VarInt is not in minimal representation"))

  override def sizeBound = SizeBound.bounded(8, 72)

  override def encode(value: T) = p(I.toLong(value)).flatMap { i =>
    I.toLong(i) match {
      case n if n < 0xfdL       => ulongL(8).encode(n)
      case n if n < 0xffffL     => (uint8L ~ ulongL(16)).encode(0xfd ~ n)
      case n if n < 0xffffffffL => (uint8L ~ ulongL(32)).encode(0xfe ~ n)
      // only 63 bits can be encoded with ulongL(i). since we checked the sign bit already
      // ulongL(64) would be the same effect as longL(64)
      case n                    => (uint8L ~ longL(64)).encode(0xff ~ n)
    }
  }

  private val decoder: Decoder[Long] = uint(8).flatMap {
    case n if n == 0xfd => ulongL(16).emap(l => if (l > 0xfcL) successful(l) else NotMinimal)
    case n if n == 0xfe => ulongL(32).emap(l => if (l > 0xffffL) successful(l) else NotMinimal)
    case n if n == 0xff => longL(64).emap(l => if (l > 0xffffffffL) successful(l) else NotMinimal)
    case n              => provide(n)
  }

  override def decode(bits: BitVector) = for {
    decoded <- decoder.decode(bits)
    narrowed <- p(decoded.value)
  } yield DecodeResult(narrowed, decoded.remainder)
}
