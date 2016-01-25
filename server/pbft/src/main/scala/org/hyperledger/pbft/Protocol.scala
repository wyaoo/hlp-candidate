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

import org.hyperledger.common._
import org.hyperledger.network._
import org.hyperledger.network.codecs._
import org.hyperledger.network.{codecs => C, BlockDataRequest, Version, Rejection, InventoryVector}
import scodec._
import scodec.bits._
import scodec.codecs._

import org.hyperledger.network.codecs.HyperledgerCodecs._

sealed trait PbftMessage {
  def payload: Any
}

case class PrePrepareMessage(payload: PrePrepare) extends PbftMessage
case class PrepareMessage(payload: Prepare) extends PbftMessage
case class CommitMessage(payload: Commit) extends PbftMessage
case class ViewChangeMessage(payload: ViewChange) extends PbftMessage
case class NewViewMessage(payload: NewView) extends PbftMessage
case class VersionMessage(payload: Version) extends PbftMessage
case class VerackMessage(payload: Unit = ()) extends PbftMessage
case class PingMessage(payload: Ping) extends PbftMessage
case class PongMessage(payload: Ping) extends PbftMessage
case class RejectMessage(payload: Rejection) extends PbftMessage
case class TxMessage(payload: Transaction) extends PbftMessage
case class BlockMessage(payload: Block) extends PbftMessage
case class InvMessage(payload: List[InventoryVector]) extends PbftMessage
case class GetDataMessage(payload: List[InventoryVector]) extends PbftMessage
case class GetBlocksMessage(payload: BlockDataRequest) extends PbftMessage

object PbftMessage {
  implicit val discriminated: Discriminated[PbftMessage, String] = Discriminated(cmdCodec)

  def discriminator[M](d: String) = Discriminator[PbftMessage, M, String](d)

  implicit val prePrepareD = discriminator[PrePrepareMessage](PrePrepare.command)
  implicit val prePrepareC = PrePrepare.codec.hlist.as[PrePrepareMessage]

  implicit val prepareD = discriminator[PrepareMessage](Prepare.command)
  implicit val prepareC = Prepare.codec.hlist.as[PrepareMessage]

  implicit val commitD = discriminator[CommitMessage](Commit.command)
  implicit val commitC = Commit.codec.hlist.as[CommitMessage]

  implicit val viewChangeD = discriminator[ViewChangeMessage](ViewChange.command)
  implicit val viewChangeC = ViewChange.codec.hlist.as[ViewChangeMessage]

  implicit val newViewD = discriminator[NewViewMessage](NewView.command)
  implicit val newViewC = NewView.codec.hlist.as[NewViewMessage]

  implicit val versionD = discriminator[VersionMessage]("version")
  implicit val versionC = Version.codec.hlist.as[VersionMessage]

  implicit val verackD = discriminator[VerackMessage]("verack")
  implicit val verackC = provide(VerackMessage())

  implicit val pingD = discriminator[PingMessage]("ping")
  implicit val pingC = Ping.codec.hlist.as[PingMessage]

  implicit val pongD = discriminator[PongMessage]("pong")
  implicit val pongC = Ping.codec.hlist.as[PongMessage]

  implicit val rejectD = discriminator[RejectMessage]("reject")
  implicit val rejectC = Rejection.codec.hlist.as[RejectMessage]

  implicit val txD = discriminator[TxMessage]("tx")
  implicit val txC: Codec[TxMessage] = txCodec.hlist.as[TxMessage]

  implicit val blockD = discriminator[BlockMessage]("block")
  implicit val blockC: Codec[BlockMessage] = blockCodec.hlist.as[BlockMessage]

  implicit val invD = discriminator[InvMessage]("inv")
  implicit val invC: Codec[InvMessage] = ("payload" | C.varIntSizeSeq(InventoryVector.codec)).as[InvMessage]

  implicit val getDataD = discriminator[GetDataMessage]("getdata")
  implicit val getDataC: Codec[GetDataMessage] = ("payload" | C.varIntSizeSeq(InventoryVector.codec)).as[GetDataMessage]

  implicit val getBlocksD = discriminator[GetBlocksMessage]("getblocks")
  implicit val getBlocksC: Codec[GetBlocksMessage] = BlockDataRequest.codec.hlist.as[GetBlocksMessage]

  val codec: Codec[PbftMessage] = Codec.coproduct[PbftMessage]
    .framing(PayloadFrameCodec.framing)
    .discriminatedBy(cmdCodec).auto

  def messageCodec(magic: Int) = magicCodec(magic) ~> PbftMessage.codec


}

object Ping {
  implicit val codec: Codec[Ping] = long(64).hlist.as[Ping]
}

case class Ping(nonce: Long)

sealed trait PbftPayload[M <: PbftPayload[M]] {
  def withSignature(sig: ByteVector): M
  def command: String
  def node: Int
  def signature: ByteVector

  def getHash(implicit codec: Codec[M]) = for {
    cmd <- Attempt.fromEither(BitVector.encodeAscii(command).left.map(e => Err(e.getMessage)))
    payload <- codec.encode(withSignature(ByteVector.empty))
  } yield (cmd ++ payload).digest("SHA-256")

  def sign(pk: PrivateKey)(implicit codec: Codec[M]) =
    getHash.map(d => ByteVector.view(pk.sign(d.toByteArray))).map(withSignature)

  def verify(keys: Int => Option[PublicKey])(implicit codec: Codec[M]) = {
    (for {
      h <- getHash.toOption
      pk <- keys(node)
    } yield pk.verify(h.toByteArray, signature.toArray)).getOrElse(false)
  }
}

object PrePrepare {
  val command = "preprepare"

  implicit val codec = {
    ("node" | int32L) ::
      ("viewSeq" | int32L) ::
      ("block" | blockCodec) ::
      ("signature" | C.signatureCodec)
  }.as[PrePrepare]
  //implicit def discriminator[F] = hyperLedgerDiscriminator[F, PrePrepare]("preprepare")
}

case class PrePrepare(
  node: Int,
  viewSeq: Int,
  block: Block,
  signature: ByteVector = ByteVector.empty) extends PbftPayload[PrePrepare] {

  val command = PrePrepare.command

  val blockHeader = block.getHeader
  def withSignature(sig: ByteVector) = copy(signature = sig)
}

object Prepare {
  val command = "prepare"

  implicit val codec = {
    ("node" | int32L) ::
      ("vewSeq" | int32L) ::
      ("blockHeader" | headerCodec) ::
      ("signature" | C.signatureCodec)
  }.as[Prepare]
  //implicit def discriminator[F] = hyperLedgerDiscriminator[F, Prepare]("prepare")
}

case class Prepare(
  node: Int,
  viewSeq: Int,
  blockHeader: Header,
  signature: ByteVector = ByteVector.empty) extends PbftPayload[Prepare] {

  val command = Prepare.command
  def withSignature(sig: ByteVector) = copy(signature = sig)
}

object Commit {
  val command = "commit"

  implicit val codec: Codec[Commit] = {
    ("node" | int32L) ::
      ("viewSeq" | int32L) ::
      ("blockHeader" | headerCodec) ::
      ("signature" | C.signatureCodec)
  }.as[Commit]
  //implicit def discriminator[F] = hyperLedgerDiscriminator[F, Commit]("commit")
}

case class Commit(
  node: Int,
  viewSeq: Int,
  blockHeader: Header,
  signature: ByteVector = ByteVector.empty) extends PbftPayload[Commit] {

  val command = Commit.command
  def withSignature(sig: ByteVector) = copy(signature = sig)
}

object ViewChange {
  val command = "viewchange"

  implicit val codec = {
    ("node" | int32L) ::
      ("viewSeq" | int32L) ::
      ("blockHeader" | headerCodec) ::
      ("commits" | C.varIntSizeSeq(Commit.codec)) ::
      ("signature" | C.signatureCodec)
  }.as[ViewChange]
  //implicit def discriminator[F] = hyperLedgerDiscriminator[F, ViewChange]("viewchange")
}

case class ViewChange(
  node: Int,
  viewSeq: Int,
  blockHeader: Header,
  commits: List[Commit],
  signature: ByteVector = ByteVector.empty) extends PbftPayload[ViewChange] {

  val command = ViewChange.command
  def withSignature(sig: ByteVector) = copy(signature = sig)
}

object NewView {
  val command = "newview"

  implicit val codec = {
    ("node" | int32L) ::
      ("viewSeq" | int32L) ::
      ("viewChanges" | C.varIntSizeSeq(ViewChange.codec)) ::
      ("blockHeader" | headerCodec) ::
      ("commits" | C.varIntSizeSeq(Commit.codec)) ::
      ("signature" | C.signatureCodec)
  }.as[NewView]
  //implicit def newViewDiscriminator[F] = hyperLedgerDiscriminator[F, NewView]("newview")
}

case class NewView(
  node: Int,
  viewSeq: Int,
  viewChanges: List[ViewChange],
  blockHeader: Header,
  commits: List[Commit],
  signature: ByteVector = ByteVector.empty) extends PbftPayload[NewView] {

  val command = NewView.command
  def withSignature(sig: ByteVector) = copy(signature = sig)
}
