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

import org.hyperledger.network.Messages._
import org.scalatest.{FunSuite, Matchers}
import scodec._
import scodec.bits._

class ProtocolCodecSuite extends FunSuite with Matchers {
  test("version message") {
    val bits = hex"""
           f9 be b4 d9
           76 65 72 73 69 6f 6e 00 00 00 00 00
           65 00 00 00
           f6 7f f1 1a
           62 ea 00 00
           01 00 00 00 00 00 00 00
           11 b2 d0 50 00 00 00 00
           01 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 ff ff 00 00 00 00 00 00
           01 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 ff ff 00 00 00 00 00 00
           3b 2e b3 5d 8c e6 17 65
           0f 2f 53 61 74 6f 73 68 69 3a 30 2e 37 2e 32 2f
           c0 3e 03 00
           01
         """.bits
    // older version taken from bitcoin wiki. protocol version dependent encoding has to be implemented
    // val bits = hex"""
    //        f9 be b4 d9
    //        76 65 72 73 69 6f 6e 00 00 00 00 00
    //        65 00 00 00
    //        3B 64 8D 5A
    //        62 ea 00 00
    //        01 00 00 00 00 00 00 00
    //        11 b2 d0 50 00 00 00 00
    //        01 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 ff ff 00 00 00 00 00 00
    //        01 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 ff ff 00 00 00 00 00 00
    //        3b 2e b3 5d 8c e6 17 65
    //        0f 2f 53 61 74 6f 73 68 69 3a 30 2e 37 2e 32 2f
    //        c0 3e 03 00
    //        01
    //      """.bits

    roundtrip(bits, classOf[VersionMessage])
  }

  test("verack message") {
    val bits = hex"""F9 BE B4 D9
                     76 65 72 61 63 6B 00 00 00 00 00 00
                     00 00 00 00
                     5D F6 E0 E2
                     """.bits
    roundtrip(bits, classOf[VerackMessage])
  }

  test("addr message") {
    val bits = hex"""
                  F9 BE B4 D9
                  61 64 64 72 00 00 00 00 00 00 00 00
                  1F 00 00 00
                  ED 52 39 9B
                  01
                  E2 15 10 4D
                  01 00 00 00 00 00 00 00
                  00 00 00 00 00 00 00 00 00 00 FF FF 0A 00 00 01
                  20 8D
                  """.bits
    roundtrip(bits, classOf[AddrMessage])
  }

  test("inv message") {
    val bits = hex"""
                  F9 BE B4 D9
                  69 6e 76 00 00 00 00 00 00 00 00 00
                  49 00 00 00
                  30 14 1a 62
                  02
                  01 00 00 00
                  ff 00 00 00 ff 00 00 00 ff 00 00 00 ff 00 00 00 ff 00 00 00 ff 00 00 00 ff 00 00 00 ff 00 00 00
                  02 00 00 00
                  ee 00 00 00 ee 00 00 00 ee 00 00 00 ee 00 00 00 ee 00 00 00 ee 00 00 00 ee 00 00 00 ee 00 00 00
                  """.bits
    roundtrip(bits, classOf[InvMessage])
  }

  test("getdata message") {
    val bits = hex"""
                  F9 BE B4 D9
                  67 65 74 64 61 74 61 00 00 00 00 00
                  49 00 00 00
                  30 14 1a 62
                  02
                  01 00 00 00
                  ff 00 00 00 ff 00 00 00 ff 00 00 00 ff 00 00 00 ff 00 00 00 ff 00 00 00 ff 00 00 00 ff 00 00 00
                  02 00 00 00
                  ee 00 00 00 ee 00 00 00 ee 00 00 00 ee 00 00 00 ee 00 00 00 ee 00 00 00 ee 00 00 00 ee 00 00 00
                  """.bits
    roundtrip(bits, classOf[GetDataMessage])
  }

  test("notfound message") {
    val bits = hex"""
                  F9 BE B4 D9
                  6e 6f 74 66 6f 75 6e 64 00 00 00 00
                  49 00 00 00
                  30 14 1a 62
                  02
                  01 00 00 00
                  ff 00 00 00 ff 00 00 00 ff 00 00 00 ff 00 00 00 ff 00 00 00 ff 00 00 00 ff 00 00 00 ff 00 00 00
                  02 00 00 00
                  ee 00 00 00 ee 00 00 00 ee 00 00 00 ee 00 00 00 ee 00 00 00 ee 00 00 00 ee 00 00 00 ee 00 00 00
                  """.bits
    roundtrip(bits, classOf[NotFoundMessage])
  }

  test("getblocks message") {
    val bits = hex"""
                  F9 BE B4 D9
                  67 65 74 62 6C 6F 63 6B 73 00 00 00
                  65 00 00 00
                  B3 E3 66 6B
                  62 ea 00 00
                  02
                  ff 00 00 00 ff 00 00 00 ff 00 00 00 ff 00 00 00 ff 00 00 00 ff 00 00 00 ff 00 00 00 ff 00 00 00
                  ee 00 00 00 ee 00 00 00 ee 00 00 00 ee 00 00 00 ee 00 00 00 ee 00 00 00 ee 00 00 00 ee 00 00 00
                  11 00 00 00 11 00 00 00 11 00 00 00 11 00 00 00 11 00 00 00 11 00 00 00 11 00 00 00 11 00 00 00
                  """.bits

    roundtrip(bits, classOf[GetBlocksMessage])
  }

  test("getheaders message") {
    val bits = hex"""
                  F9 BE B4 D9
                  67 65 74 68 65 61 64 65 72 73 00 00
                  65 00 00 00
                  B3 E3 66 6B
                  62 ea 00 00
                  02
                  ff 00 00 00 ff 00 00 00 ff 00 00 00 ff 00 00 00 ff 00 00 00 ff 00 00 00 ff 00 00 00 ff 00 00 00
                  ee 00 00 00 ee 00 00 00 ee 00 00 00 ee 00 00 00 ee 00 00 00 ee 00 00 00 ee 00 00 00 ee 00 00 00
                  11 00 00 00 11 00 00 00 11 00 00 00 11 00 00 00 11 00 00 00 11 00 00 00 11 00 00 00 11 00 00 00
                  """.bits

    roundtrip(bits, classOf[GetHeadersMessage])
  }

  test("tx message") {
    val bits = hex"""
                   F9 BE B4 D9
                   74 78 00 00 00 00 00 00 00 00 00 00
                   02 01 00 00
                   E2 93 CD BE
                   01 00 00 00
                   01
                   6D BD DB 08 5B 1D 8A F7  51 84 F0 BC 01 FA D5 8D
                   12 66 E9 B6 3B 50 88 19  90 E4 B4 0D 6A EE 36 29
                   00 00 00 00
                   8B
                   48 30 45 02 21 00 F3 58  1E 19 72 AE 8A C7 C7 36
                   7A 7A 25 3B C1 13 52 23  AD B9 A4 68 BB 3A 59 23
                   3F 45 BC 57 83 80 02 20  59 AF 01 CA 17 D0 0E 41
                   83 7A 1D 58 E9 7A A3 1B  AE 58 4E DE C2 8D 35 BD
                   96 92 36 90 91 3B AE 9A  01 41 04 9C 02 BF C9 7E
                   F2 36 CE 6D 8F E5 D9 40  13 C7 21 E9 15 98 2A CD
                   2B 12 B6 5D 9B 7D 59 E2  0A 84 20 05 F8 FC 4E 02
                   53 2E 87 3D 37 B9 6F 09  D6 D4 51 1A DA 8F 14 04
                   2F 46 61 4A 4C 70 C0 F1  4B EF F5
                   FF FF FF FF
                   02
                   40 4B 4C 00 00 00 00 00
                   19
                   76 A9 14 1A A0 CD 1C BE  A6 E7 45 8A 7A BA D5 12
                   A9 D9 EA 1A FB 22 5E 88  AC
                   80 FA E9 C7 00 00 00 00
                   19
                   76 A9 14 0E AB 5B EA 43  6A 04 84 CF AB 12 48 5E
                   FD A0 B7 8B 4E CC 52 88  AC
                   00 00 00 00
         """.bits

    roundtrip(bits, classOf[TxMessage])
  }

  test("block message") {
    val bits = hex"""
                  F9 BE B4 D9
                  62 6c 6f 63 6b 00 00 00 00 00 00 00
                  1d 01 00 00
                  f7 1a 24 03
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
                  8A 4C 70 2B 6B F1 1D 5F  AC 00 00 00 00
                  """.bits
    roundtrip(bits, classOf[BlockMessage])
  }
  test("headers message") {
    val bits = hex"""
                  F9 BE B4 D9
                  68 65 61 64 65 72 73 00 00 00 00 00
                  52 00 00 00
                  0b 0e 13 eb
                  01
                  01 00 00 00 00 00 00 00  00 00 00 00 00 00 00 00
                  00 00 00 00 00 00 00 00  00 00 00 00 00 00 00 00
                  00 00 00 00 3B A3 ED FD  7A 7B 12 B2 7A C7 2C 3E
                  67 76 8F 61 7F C8 1B C3  88 8A 51 32 3A 9F B8 AA
                  4B 1E 5E 4A 29 AB 5F 49  FF FF 00 1D 1D AC 2B 7C
                  00
                  """.bits
    roundtrip(bits, classOf[HeadersMessage])
  }

  test("sighdrs message") {
    val bits = hex"""
                  f9 be b4 d9
                  73 69 67 68  64 72 73 00 00 00 00 00
                  f7 01 00 00
                  7a 03 c5 6f
                  02
                  03 00 00 00 e0 80 e0
                  70 c6 75 fd a0 f7 b5 f1  56 14 14 d1 7f a7 80 c8
                  10 d3 f5 59 87 06 4e 8f  93 8f 6e ea b8 cc 4a bc
                  1d fa 55 77 b5 21 d0 5c  96 c5 e1 fa 1c 74 d4 0a
                  9e 16 34 dd d8 3a 5b 52  0c c9 df 8b 40 8e b9 49
                  56 ff ff 7f 20 00 00 00  80 95 47 30 45 02 21 00
                  e5 c8 6d 52 ca d0 09 39  eb 2d 95 22 67 36 08 ff
                  91 c3 62 35 ee ce 5f 33  37 6c 32 36 74 89 f1 b7
                  02 20 57 bd 7d 87 cc 9c  bb c6 7b b4 fa 4f 70 00
                  8e c5 e7 d0 60 c6 df bf  5e 37 77 e2 60 a1 06 59
                  06 ec 00 4b 51 74 8c 7a  a9 21 03 ae 9d 05 bb 39
                  11 df f1 67 c3 4a 63 68  67 6e bf 8c 42 7a 18 0e
                  56 3f 60 6a 7e b7 43 91  4c 2e 81 21 03 ba 64 6c
                  26 79 4f a9 9b 0d f6 c6  d1 3f 14 ef 59 44 93 8f
                  2d e2 56 a6 4f c3 76 83  49 b6 7a 89 0e 52 bb 14
                  6c 66 82 48 28 c5 09 3b  67 79 98 24 41 dd 77 22
                  ea 90 38 04 03 00 00 00  b1 4b 9b 3b 64 ca b8 71
                  67 26 de 18 49 94 7c c2  9a b9 b2 32 ba 73 25 f3
                  33 03 70 14 94 d3 f0 7c  30 d3 c8 f3 a5 56 4e 38
                  16 a0 29 a6 c4 82 dc ed  45 d4 77 7b b8 38 0e 4b
                  12 5a f8 61 61 76 1a c5  90 b9 49 56 ff ff 7f 20
                  00 00 00 80 95 47 30 45  02 21 00 c6 f8 a0 e3 e7
                  8f de e2 0e 25 19 c1 9d  6a bb bb d9 dc 8c 9f 3e
                  27 aa fe ef dd a6 d2 e2  cb db 1e 02 20 43 66 02
                  a9 ad 06 5f 2f 07 a3 0c  00 e9 64 38 cf ca 6c e6
                  1a bc 0c e4 3e 71 ec 4e  d8 b0 2c 5e 12 00 4b 51
                  74 8c 7a a9 21 03 ae 9d  05 bb 39 11 df f1 67 c3
                  4a 63 68 67 6e bf 8c 42  7a 18 0e 56 3f 60 6a 7e
                  b7 43 91 4c 2e 81 21 03  ba 64 6c 26 79 4f a9 9b
                  0d f6 c6 d1 3f 14 ef 59  44 93 8f 2d e2 56 a6 4f
                  c3 76 83 49 b6 7a 89 0e  52 bb 14 6c 66 82 48 28
                  c5 09 3b 67 79 98 24 41  dd 77 22 ea 90 38 04
                  """.bits
    roundtrip(bits, classOf[SignedHeadersMessage])
  }

  test("getaddr message") {
    val bits = hex"""
                   F9 BE B4 D9
                   67 65 74 61 64 64 72 00 00 00 00 00
                   00 00 00 00
                   5D F6 E0 E2

                  """.bits
    roundtrip(bits, classOf[GetAddrMessage])
  }

  test("mempool message") {
    val bits = hex"""
                   F9 BE B4 D9
                   6D 65 6D 70 6F 6F 6C 00 00 00 00 00
                   00 00 00 00
                   5D F6 E0 E2
                  """.bits
    roundtrip(bits, classOf[MempoolMessage])
  }

  test("ping message") {
    val bits = hex"""
                   F9 BE B4 D9
                   70 69 6E 67 00 00 00 00 00 00 00 00
                   08 00 00 00
                   C9 CF E5 88
                   11 00 00 00 11 00 00 00
                  """.bits
    roundtrip(bits, classOf[PingMessage])
  }

  test("pong message") {
    val bits = hex"""
                   F9 BE B4 D9
                   70 6F 6E 67 00 00 00 00 00 00 00 00
                   08 00 00 00
                   C9 CF E5 88
                   11 00 00 00 11 00 00 00
                  """.bits
    roundtrip(bits, classOf[PongMessage])
  }

  test("reject message") {
    val bits = hex"""
                   F9 BE B4 D9
                   72 65 6A 65 63 74 00 00 00 00 00 00
                   10 00 00 00
                   81 5C 42 70
                   07
                   6d 65 73 73 61 67 65
                   01 06 72 65
                   61 73 6F 6E
                  """.bits
    roundtrip(bits, classOf[RejectMessage])

    val bits2 = hex"""fa bf b5 da
          72 65 6a 65 63 74 00 00 00 00 00 00
          23 00 00 00
          91 34 b3 bc
          07
          76 65 72 73 69 6f 6e
          12
          19
          44 75 70 6c 69 63 61 74 65 20 76 65 72 73 69 6f 6e 20 6d 65 73 73 61 67 65
          """.bits
    roundtrip(bits2, classOf[RejectMessage])
  }

  // ignore("filterload message") { pending }
  // ignore("filteradd message") { pending }
  // ignore("filterclear message") { pending }
  // ignore("merkleblock message") { pending }

  test("alert message") {
    val bits = hex"""F9 BE B4 D9
                     61 6c 65 72 74 00 00 00 00 00 00 00
                     bc 00 00 00
                     4f e6 8f e9
                     73
                     01 00 00 00
                     37 66 40 4f 00 00 00 00
                     b3 05 43 4f 00 00 00 00
                     f2 03 00 00
                     f1 03 00 00
                     00
                     10 27 00 00
                     48 ee 00 00
                     00
                     64 00 00 00
                     00
                     4653656520626974636f696e2e6f72672f666562323020696620796f7520686176652074726f75626c6520636f6e6e656374696e67206166746572203230204665627275617279
                     00
                     4730450221008389df45f0703f39ec8c1cc42c13810ffcae14995bb648340219e353b63b53eb022009ec65e1c1aaeec1fd334c6b684bde2b3f573060d5b70c3a46723326e4e8a4f1
                  """.bits
    roundtrip(bits, classOf[AlertMessage])
    // todo test signature
  }

  //val codec = com.hyperledger.bitscale.blockchain.codec.messageCodec(ProductionBitcoinNetwork.p2pMagic)
  val codec = Messages.messageCodec(0xd9b4bef9)
  val regtestCodec = Messages.messageCodec(0xdab5bffa)

  val codecs = Map(
    0xd9b4bef9 -> Messages.messageCodec(0xd9b4bef9),
    0xdab5bffa -> Messages.messageCodec(0xdab5bffa)
  )

  def roundtrip[M <: BlockchainMessage](bits: BitVector, c: Class[M]) = {
    val cdc = codecs(bits.bytes.take(4).toInt(signed = false, ByteOrdering.LittleEndian))
    val Attempt.Successful(DecodeResult(decoded, rest)) = cdc decode bits
    rest shouldEqual BitVector.empty

    val Attempt.Successful(encoded) = cdc encode decoded
    encoded shouldEqual bits
  }
}
