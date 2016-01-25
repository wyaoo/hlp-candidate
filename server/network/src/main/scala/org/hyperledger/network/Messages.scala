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

import org.hyperledger.common._
import org.hyperledger.network.codecs.HyperledgerCodecs._
import org.hyperledger.network.codecs._
import scodec._
import scodec.bits.BitVector

import scala.collection.immutable.List

/**
 * Common structures for blockchain based P2P protocols.
 */
object Messages {

  sealed trait BlockchainMessage
  sealed trait DataMessage extends BlockchainMessage

  case class VersionMessage(payload: Version) extends BlockchainMessage
  case class VerackMessage() extends BlockchainMessage
  case class AddrMessage(addresses: List[NetworkAddress]) extends BlockchainMessage

  object InvMessage {
    def txs(txs: scala.collection.Iterable[TID]) = InvMessage(txs.map(InventoryVector.tx).toList)
    def blocks(hashes: scala.collection.Iterable[BID]) = InvMessage(hashes.map(InventoryVector.block).toList)
  }
  case class InvMessage(inventory: List[InventoryVector]) extends DataMessage

  object GetDataMessage {
    def blocks(h: scala.collection.Iterable[BID]): GetDataMessage = GetDataMessage(h.map(InventoryVector.block).toList)
    def blocks(h: BID*): GetDataMessage = blocks(h.toIterable)
    def txs(ids: scala.collection.Iterable[TID]): GetDataMessage = GetDataMessage(ids.map(InventoryVector.tx).toList)
  }
  case class GetDataMessage(inventory: List[InventoryVector]) extends BlockchainMessage

  case class NotFoundMessage(inventory: List[InventoryVector]) extends DataMessage
  case class GetBlocksMessage(payload: BlockDataRequest) extends BlockchainMessage
  case class GetHeadersMessage(payload: BlockDataRequest) extends BlockchainMessage
  case class TxMessage(tx: Transaction) extends DataMessage
  case class BlockMessage(block: Block) extends DataMessage
  case class SignedBlockMessage(block: Block) extends DataMessage
  case class HeadersMessage(headers: List[Header]) extends BlockchainMessage
  case class SignedHeadersMessage(headers: List[HeaderWithSignatures]) extends BlockchainMessage
  case class GetAddrMessage() extends BlockchainMessage
  case class MempoolMessage() extends BlockchainMessage
  case class PingMessage(payload: Ping) extends BlockchainMessage {
    def pong = PongMessage(payload)
  }
  case class PongMessage(payload: Ping) extends BlockchainMessage
  case class RejectMessage(payload: Rejection) extends BlockchainMessage

  case class AlertMessage(
    content: Alert,
    signature: BitVector) extends BlockchainMessage

  // todo add https://github.com/bitcoin/bips/blob/master/bip-0037.mediawiki messages

  import scodec.codecs._

  private def discriminator[M](d: String) = Discriminator[BlockchainMessage, M, String](d)

  implicit val versionD = discriminator[VersionMessage]("version")
  implicit val versionCodec = Version.codec.hlist.as[VersionMessage]

  // verack
  implicit val verackD = discriminator[VerackMessage]("verack")
  implicit val verackCodec = provide(VerackMessage())

  implicit val addrD = discriminator[AddrMessage]("addr")
  implicit val addrCodec = ("addresses" | varIntSizeSeq(NetworkAddress.codec)).as[AddrMessage]

  implicit val invD = discriminator[InvMessage]("inv")
  implicit val invCodec = ("inventory" | varIntSizeSeq(InventoryVector.codec)).as[InvMessage]

  implicit val getdataD = discriminator[GetDataMessage]("getdata")
  implicit val getdataCodec = ("inventory" | varIntSizeSeq(InventoryVector.codec)).as[GetDataMessage]

  implicit val notfoundD = discriminator[NotFoundMessage]("notfound")
  implicit val notfoundCodec = ("inventory" | varIntSizeSeq(InventoryVector.codec)).as[NotFoundMessage]

  implicit val getblocksD = discriminator[GetBlocksMessage]("getblocks")
  implicit val getblocksCodec = BlockDataRequest.codec.hlist.as[GetBlocksMessage]

  implicit val getheadersD = discriminator[GetHeadersMessage]("getheaders")
  implicit val getheadersCodec = BlockDataRequest.codec.hlist.as[GetHeadersMessage]

  implicit val txMessageD = discriminator[TxMessage]("tx")
  implicit val txMessageCodec = txCodec.hlist.as[TxMessage]

  implicit val blockMessageD = discriminator[BlockMessage]("block")
  implicit val blockMessageCodec = blockCodec.hlist.as[BlockMessage]

  implicit val signedBlockMessageD = discriminator[SignedBlockMessage]("sigblock")
  implicit val signedBlockMessageCodec = signedBlockCodec.hlist.as[SignedBlockMessage]

  implicit val headersMessageD = discriminator[HeadersMessage]("headers")
  implicit val headersMessageCodec: Codec[HeadersMessage] = varIntSizeSeq(headerCodec ~ ignore(8)).xmap[HeadersMessage](
    x => HeadersMessage(x.map(_._1)),
    hm => hm.headers.map((_, ())))

  implicit val signedHeadersMessageD = discriminator[SignedHeadersMessage]("sighdrs")
  implicit val signedHeadersMessageCodec = varIntSizeSeq(signedHeaderCodec).hlist.as[SignedHeadersMessage]

  implicit val getaddrD = discriminator[GetAddrMessage]("getaddr")
  implicit val getaddrCodec = provide(GetAddrMessage())

  implicit val mempoolD = discriminator[MempoolMessage]("mempool")
  implicit val mempoolCodec = provide(MempoolMessage())

  implicit val pingD = discriminator[PingMessage]("ping")
  implicit val pingCodec = Ping.codec.hlist.as[PingMessage]

  implicit val pongD = discriminator[PongMessage]("pong")
  implicit val pongCodec = Ping.codec.hlist.as[PongMessage]

  implicit val rejectD = discriminator[RejectMessage]("reject")
  implicit val rejectCodec = Rejection.codec.hlist.as[RejectMessage]

  implicit val alertD = discriminator[AlertMessage]("alert")
  implicit val alertMessageCodec = {
    variableSizeBytes(varInt, Alert.codec) ::
      scodec.codecs.bits
  }.as[AlertMessage]

  val blockChainMessageCodec: Codec[BlockchainMessage] = Codec.coproduct[BlockchainMessage]
    .framing(PayloadFrameCodec.framing)
    .discriminatedBy(cmdCodec).auto

  def messageCodec(magic: Int): Codec[BlockchainMessage] = {
    magicCodec(magic) ~> blockChainMessageCodec
  }.as[BlockchainMessage]
}
