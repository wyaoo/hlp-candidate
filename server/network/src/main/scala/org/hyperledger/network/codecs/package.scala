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

import java.nio.charset.Charset

import org.hyperledger.common.Block
import scodec.Attempt.{Failure, Successful}
import scodec.bits.{ByteOrdering, BitVector, ByteVector}
import scodec.{ codecs => C, _ }

package object codecs {

  def attemptCatch[A, B](f: A => B)(a: A) = try {
    Attempt.successful(f(a))
  } catch {
    case e: Exception => Attempt.failure(Err(e.getMessage))
  }

  /**
    * 12 byte string, zero padded to the right
    */
  val cmdCodec: Codec[String] = C.paddedFixedSizeBytes(12, C.cstring, C.constant(ByteVector.low(1)))

  def magicCodec(magic: Int): Codec[Unit] = C.constant(BitVector.fromInt(magic, 32, ByteOrdering.LittleEndian))

  implicit def nelOfN[A](countCodec: Codec[Int], valueCodec: Codec[A]) = NelCodec.nelOfN(countCodec, valueCodec)

  val varInt: Codec[Int] = new VarIntCodec[Int](l =>
    if (l >= 0 && l <= Int.MaxValue) Attempt.successful(l.toInt)
    else Attempt.failure(Err(s"Only positive unsigned int range is supported")))

  val varLong: Codec[Long] = new VarIntCodec[Long](l =>
    if (l >= 0) Attempt.successful(l)
    else Attempt.failure(Err(s"Only positive unsigned long range is supported")))

  def varIntSizeSeq[A](codec: Codec[A]) = C.listOfN(varInt, codec)

  def varIntSizeNel[A](codec: Codec[A]) = NelCodec.nelOfN(varInt, codec)

  def varString(implicit charset: Charset) = C.variableSizeBytes(varInt, C.string)
  def varBytes = C.variableSizeBytes(varInt, C.bytes)
  def optionalVarBytes: Codec[Option[ByteVector]] = varBytes.exmap(
    { x => Successful(Some(x)) },
    {
      case Some(b) => Successful(b)
      case None    => Failure(Err(""))
    })

  private val TRUE_BITS = ByteVector(1).toBitVector
  private val FALSE_BITS = BitVector.low(8)
  val simpleBool = C.bits(8).xmap[Boolean](bits => !(bits == FALSE_BITS), b => if (b) TRUE_BITS else FALSE_BITS)

  val signatureCodec: Codec[ByteVector] = C.variableSizeBytes(C.uint8L, C.bytes)


}
