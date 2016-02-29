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
package org.hyperledger.pbft

import akka.util.ByteString
import org.hyperledger.pbft.TestBlockStore._
import org.scalatest.{FunSuite, Matchers}
import scodec._
import scodec.bits._

object DummyData {

  def dummySignature(elements: Byte) = ByteVector(dummyList(elements, 70))

  val dummyViewSeq = 3
  val dummyNode = 4
  val dummySig = dummySignature(2)
  val dummySig2 = dummySignature(5)

  // format: OFF
  /**
  * In some cases, reading a byte with signed value -1 did cause a problem,
  * discovered with this message.
  */
  val messageWithMinusOne = ByteString(0, 0, 0, 0, // magic
    112, 114, 101, 112, 114, 101, 112, 97, 114, 101, 0, 0, // discriminator
    -7, 0, 0, 0, // length = 249
    -59, -32, 48, 46, // checksum
    0, 0, 0, 0, //node = 0
    0, 0, 0, 0, // viewSeq = 0
    3, 0, 0, 0, // version = 3
    6, 34, 110, 70,        17, 26, 11, 89,      -54, -81, 18, 96,      67, -21, 91, -65,    // previous id hash
    40, -61, 79, 58,       94, 51, 42, 31,      -57, -78, -73, 60,    -15, -120, -111, 15,  // previous id hash
    60, -108, 28, -112,    -4, -59, 72, 89,      49, 93, -98, -85,     99, 85, 101, 52,     // merkle root
    -125, 58, -33, -27,    74, 124, -107, -68,   85, 36, 105, -108,   112, 118, 25, 123,    // merkle root
    68, 26, -106, 86, // create time
    -1, -1, 127, 32,  // difficulty target
    2, 0, 0, -128,    // nonce
  // rest of the block
    1, 1, 0, 0,            0, 1, 0, 0,            0, 0, 0, 0,            0, 0, 0, 0,
    0, 0, 0, 0,            0, 0, 0, 0,            0, 0, 0, 0,            0, 0, 0, 0,
    0, 0, 0, 0,            0, 0, -1, -1,         -1, -1, 4, 1,           0, 0, 0, -1,
    -1, -1, -1, 1,         0, -14, 5, 42,         1, 0, 0, 0,           25, 118, -87, 20,
    -55, -47, 84, -69,   112, -67, 88, -65,      83, -110, 50, 108,     39, -19, 69, 44,
    71, 48, -93, 126,   -120, -84, 0, 0,          0, 0, 70, 48,         68, 2, 32, 59,
    -60, 56, 33, -110,  -122, -25, 4, 110,      -71, -42, -60, 117,     64, -91, -15, 18,
    -3, -110, -1, 59,     90, 7, 32, -39,       109, 118, 100, 43,     -23, 72, 99, 2,
    32, 41, 87, 70,        5, -75, -11, -93,     16, 5, 27, 103,       -93, -80, 16, 66,
    17, -117, -39, 23,   -11, -68, 5, -34,      -99, -35, -70, -45,    127, -63, 11, 27,
    -48)
  // format: ON
}

class PbftProtocolCodecSuite extends FunSuite with Matchers {
  import DummyData._

  val messageCodec = PbftMessage.messageCodec(0)

  test("PrePrepare message") {
    val message = PrePrepareMessage(PrePrepare(dummyNode, dummyViewSeq, dummyBlock, dummySig))
    roundtrip(message)
  }

  test("Prepare message") {
    val message = PrepareMessage(Prepare(dummyNode, dummyViewSeq, dummyBlockHeader, dummySig))
    roundtrip(message)
  }

  test("Commit message") {
    val message = CommitMessage(Commit(dummyNode, dummyViewSeq, dummyBlockHeader, dummySig))
    roundtrip(message)
  }

  test("Problematic message with -1 byte") {
    val message = BitVector(messageWithMinusOne.toByteBuffer)
    backwardTrip(message)
  }

  def roundtrip[M <: PbftMessage](message: M) = {
    val Attempt.Successful(encoded) = messageCodec.encode(message)
    val Attempt.Successful(DecodeResult(decoded, rest)) = messageCodec.decode(encoded)
    rest shouldEqual BitVector.empty
    decoded shouldEqual message
  }

  def backwardTrip(message: BitVector) = {
    val Attempt.Successful(DecodeResult(decoded, rest)) = messageCodec.decode(message)
    rest shouldEqual BitVector.empty
    val Attempt.Successful(encoded) = messageCodec.encode(decoded)
    encoded shouldEqual message
  }

}
