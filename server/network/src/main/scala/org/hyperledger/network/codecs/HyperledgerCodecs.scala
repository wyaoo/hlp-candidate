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

import org.hyperledger.HyperLedgerSettings
import org.hyperledger.common._
import scodec.{ SizeBound, Codec }
import scodec.bits.ByteVector
import scodec.codecs._

import WireFormatCodec._

object HyperledgerCodecs {
  val wireFormat = HyperLedgerSettings.getInstance.getTxWireFormatter

  val pubKeyCodec: Codec[PublicKey] = variableSizeBytes(uint8L, bytes).exmap(
    attemptCatch(bytes => PublicKey.decode(bytes.toArray)),
    attemptCatch(pk => ByteVector(pk.toByteArray)))

  implicit val txCodec: Codec[Transaction] = bytes.exmap(
    attemptCatch(bytes => wireFormat.fromWire(bytes.toArray)),
    attemptCatch(tx => ByteVector(wireFormat.toWireBytes(tx))))

  implicit val blockCodec: Codec[Block] = wireFormatCodec(SizeBound.atLeast(640),
    _.toWire,
    { reader => Block.fromWire(reader, wireFormat, classOf[BitcoinHeader]) })

  val signedBlockCodec: Codec[Block] = wireFormatCodec(SizeBound.atLeast(640),
    _.toWire,
    { reader => Block.fromWire(reader, wireFormat, classOf[HeaderWithSignatures]) })

  implicit val headerCodec: Codec[Header] = wireFormatCodec(SizeBound.exact(640),
    _.toWireHeader,
    BitcoinHeader.fromWire)

  implicit val signedHeaderCodec: Codec[HeaderWithSignatures] = wireFormatCodec(SizeBound.atLeast(640),
    _.toWireHeader,
    HeaderWithSignatures.fromWire)

  //  implicit val blockCodec: Codec[Block] = bytes.exmap(
  //    attemptCatch(bytes => Block.fromWire(bytes.toArray, wireFormat, classOf[BitcoinHeader])),
  //    attemptCatch(block => ByteVector(block.toWireBytes))
  //  )
  //  val signedBlockCodec: Codec[Block] = bytes.exmap(
  //    attemptCatch(bytes => Block.fromWire(bytes.toArray, wireFormat, classOf[HeaderWithSignatures])),
  //    attemptCatch(block => ByteVector(block.toWireBytes)))
  //

  //  implicit val headerCodec: Codec[Header] = bytes(80).exmap(
  //    attemptCatch { bytes => BitcoinHeader.fromWire(bytes.toArray) },
  //    attemptCatch { header => ByteVector(header.toWireHeaderBytes) })
  //
  //  implicit val signedHeaderCodec: Codec[HeaderWithSignatures] = bytes.exmap(
  //    attemptCatch { bytes => HeaderWithSignatures.fromWire(bytes.toArray) },
  //    attemptCatch { headerWithSignature => ByteVector(headerWithSignature.toWireHeaderBytes) })

}
