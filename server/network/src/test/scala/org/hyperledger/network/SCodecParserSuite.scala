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

import org.hyperledger.network.Implicits._
import org.hyperledger.network.{codecs => C}
import org.scalatest.{FunSuite, Matchers}
import scodec._
import scodec.bits._


class SCodecParserSuite extends FunSuite with Matchers {

  def roundtrip[A](codec: Codec[A], bits: BitVector, expected: A) = {
    val Attempt.Successful(encoded) = codec.encode(expected)
    encoded shouldEqual bits

    val Attempt.Successful(DecodeResult(decoded, rest)) = codec.decode(bits)
    decoded shouldEqual expected
    rest shouldEqual BitVector.empty
  }

  test("varString codec") {
    val strBits = hex"0F 2F 53 61 74 6F 73 68 69 3A 30 2E 37 2E 32 2F".bits

    roundtrip(C.varString, strBits, "/Satoshi:0.7.2/")
  }

  test("varInt decode") {
    val codec = C.varLong
    roundtrip(codec, hex"0x0a".bits, 10L)
    roundtrip(codec, hex"0xfd0002".bits, 512L)
    roundtrip(codec, hex"0xfe00000100".bits, 65536L)
  }

  test("block decode") {
    val genesisBits = hex"""
                    F9 BE B4 D9 1d 01 00 00
                    01 00 00 00 00 00 00 00  00 00 00 00 00 00 00 00
                    00 00 00 00 00 00 00 00  00 00 00 00 00 00 00 00
                    00 00 00 00 3B A3 ED FD  7A 7B 12 B2 7A C7 2C 3E
                    67 76 8F 61 7F C8 1B C3  88 8A 51 32 3A 9F B8 AA
                    4B 1E 5E 4A 29 AB 5F 49  FF FF 00 1D 1D AC 2B 7C
                    01 01 00 00 00 01 00 00  00 00 00 00 00 00 00 00
                    00 00 00 00 00 00 00 00  00 00 00 00 00 00 00 00
                    00 00 00 00 00 00 FF FF  FF FF 4D 04 FF FF 00 1D
                    01 04 45 54 68 65 20 54  69 6D 65 73 20 30 33 2F
                    4A 61 6E 2F 32 30 30 39  20 43 68 61 6E 63 65 6C
                    6C 6F 72 20 6F 6E 20 62  72 69 6E 6B 20 6F 66 20
                    73 65 63 6F 6E 64 20 62  61 69 6C 6F 75 74 20 66
                    6F 72 20 62 61 6E 6B 73  FF FF FF FF 01 00 F2 05
                    2A 01 00 00 00 43 41 04  67 8A FD B0 FE 55 48 27
                    19 67 F1 A6 71 30 B7 10  5C D6 A8 28 E0 39 09 A6
                    79 62 E0 EA 1F 61 DE B6  49 F6 BC 3F 4C EF 38 C4
                    F3 55 04 E5 1E C1 12 DE  5C 38 4D F7 BA 0B 8D 57
                    8A 4C 70 2B 6B F1 1D 5F  AC 00 00 00 00""".bits

    //val genesisBlock: Err \/ Block = blkFileParser(MAIN).decodeValue(genesisBits)

    //assert(genesisBlock.isRight)
  }

}
