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
import scodec.codecs.ChecksumMismatch
import scodec.{ codecs => C }

object PayloadFrameCodec {
  def apply[M](wrapped: Codec[M]) = new PayloadFrameCodec[M](wrapped)
  def framing = new CodecTransformation {
    def apply[M](c: Codec[M]) = new PayloadFrameCodec(c)
  }

}

/**
  * Handles the payload length and checksum fields for the payload
  */
class PayloadFrameCodec[M](wrapped: Codec[M]) extends Codec[M] {
  import PayloadFrameCodec._
  val lenC = C.uint32L

  override def sizeBound = lenC.sizeBound.atLeast

  override def encode(value: M): Attempt[BitVector] = for {
    encoded <- wrapped.encode(value)
    length <- lenC.encode(encoded.length / 8).mapErr(e => Err.General(s"failed to encode size of [$value]: ${e.messageWithContext}", List("size")))
    ck <- Attempt.successful(computeChecksum(encoded))
  } yield length ++ ck ++ encoded

  private val decoder: Decoder[M] = for {
    length <- lenC
    checksumBytes <- C.bits(32)
    payloadBits <- Decoder.get
    payload <- C.fixedSizeBytes(length, wrapped)
    _ <- Decoder.liftAttempt(verifyChecksum(payloadBits.take(length * 8), checksumBytes))
  } yield payload
  override def decode(bits: BitVector): Attempt[DecodeResult[M]] = decoder.decode(bits)

  def computeChecksum(bits: BitVector) = bits.digest("SHA-256").digest("SHA-256").take(32)
  def verifyChecksum(payload: BitVector, actual: BitVector) = {
    val expected = computeChecksum(payload)

    booleanAttempt(actual == expected, ChecksumMismatch(payload, expected, actual))
  }

  def booleanAttempt(p: => Boolean, e: => Err) = if (p) Attempt.successful(()) else Attempt.failure(e)

}
