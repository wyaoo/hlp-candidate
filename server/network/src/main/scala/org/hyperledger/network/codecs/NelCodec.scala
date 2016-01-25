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

import scodec._
import scodec.bits.BitVector

import scalaz._

object NelCodec {
  def nelOfN[A](countCodec: Codec[Int], valueCodec: Codec[A]): Codec[NonEmptyList[A]] =
    countCodec
      .flatZip { count => new NelCodec(valueCodec, Some(count)) }
      .xmap[NonEmptyList[A]]({ case (cnt, xs) => xs }, xs => (xs.size, xs))
      .withToString(s"nelOfN($countCodec, $valueCodec)")
}

final class NelCodec[A](codec: Codec[A], limit: Option[Int] = None) extends Codec[NonEmptyList[A]] {
  def sizeBound = limit match {
    case None | Some(0) => SizeBound.atLeast(1)
    case Some(lim)      => codec.sizeBound * lim
  }

  def encode(nel: NonEmptyList[A]) = Encoder.encodeSeq(codec)(nel.list)

  def decode(buffer: BitVector) = {
    val res: Attempt[DecodeResult[List[A]]] = Decoder.decodeCollect[List, A](codec, limit)(buffer)
    res.flatMap { //result: DecodeResult[List[A]] =>
      case DecodeResult(Nil, remainder)     => Attempt.failure(Err("At least one element expected"))
      case DecodeResult(a :: as, remainder) => Attempt.successful(DecodeResult(NonEmptyList.nel(a, as), remainder))
    }
  }

  override def toString = s"nonemptylist($codec)"
}

