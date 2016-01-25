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

import org.hyperledger.common.WireFormat
import org.scalatest.{FunSpec, Matchers}
import scodec._
import scodec.bits.{BitVector, ByteVector}

class WireFormatCodecTests extends FunSpec with Matchers {

  object TestData {
    def fromWire(r: WireFormat.Reader): TestData = {
      TestData(r.readUint32)
    }
  }

  case class TestData(id: Int) {
    def toWire(w: WireFormat.Writer): Unit = {
      w.writeUint32(id)
    }
  }

  val testCodec: Codec[TestData] = WireFormatCodec.wireFormatCodec(SizeBound.exact(32), _.toWire, TestData.fromWire)

  describe("WireFormatCodec") {
    describe("when parsing") {
      it("should return remaining bytes") {
        val bytes = ByteVector(12, 0, 0, 0, 32, 32, 32)
        val Attempt.Successful(DecodeResult(test, rest)) = testCodec.decode(bytes.bits)
        test shouldBe TestData(12)
        rest shouldBe ByteVector(32, 32, 32).bits
      }
      it("should return empty bytevector if there is no more bytes in the buffer") {
        val bytes = ByteVector(12, 0, 0, 0)
        val Attempt.Successful(DecodeResult(test, rest)) = testCodec.decode(bytes.bits)
        test shouldBe TestData(12)
        rest shouldBe BitVector.empty
      }
      it("should return error if there is not enough bytes") {
        val bytes = ByteVector(12)
        val Attempt.Failure(Err.InsufficientBits(32, 8, _)) = testCodec.decode(bytes.bits)
      }
    }
    describe("when writing") {
      it ("should write the value") {
        val data = TestData(12)
        val Attempt.Successful(bits) = testCodec.encode(data)
        bits shouldBe ByteVector(12, 0, 0, 0).bits
      }
    }
  }
}
