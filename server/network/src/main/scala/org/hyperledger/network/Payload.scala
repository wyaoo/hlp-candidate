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

import java.net.InetSocketAddress

import org.hyperledger.common.{BID, Hash, TID}
import org.hyperledger.network.Implicits._
import org.hyperledger.network.codecs._
import scodec.Codec
import scodec.bits._
import scodec.codecs._

object NetworkAddress {

  val noTimestampCodec: Codec[NetworkAddress] = {
    ("time" | provide(0L)) ::
      ("services" | bits(64)) ::
      ("address" | InetAddressCodec.inetSocketAddress)
  }.as[NetworkAddress]

  implicit val codec: Codec[NetworkAddress] = {
    ("time" | longL(32)) ::
      ("services" | bits(64)) ::
      ("address" | InetAddressCodec.inetSocketAddress)
  }.as[NetworkAddress]

  def forVersion(services: BitVector, address: InetSocketAddress) =
    NetworkAddress(0L, services, address)
}
case class NetworkAddress(
  time: Long,
  services: BitVector,
  address: InetSocketAddress) {
  require(services.length == 64)
}

object InventoryVectorType {
  implicit val codec: Codec[InventoryVectorType] = mappedEnum(
    uint32L,
    ERROR -> ERROR.value,
    MSG_TX -> MSG_TX.value,
    MSG_BLOCK -> MSG_BLOCK.value)

  case object ERROR extends InventoryVectorType(0)
  case object MSG_TX extends InventoryVectorType(1)
  case object MSG_BLOCK extends InventoryVectorType(2)
}

sealed class InventoryVectorType(val value: Long)

object InventoryVector {

  // format: OFF
  implicit val codec = {
      ("type"              | Codec[InventoryVectorType]) ::
      ("hash"              | HashCodec.hashCodec)
  }.as[InventoryVector]
  // format: ON

  def block(h: BID) = InventoryVector(InventoryVectorType.MSG_BLOCK, h)
  def tx(h: TID) = InventoryVector(InventoryVectorType.MSG_TX, h)
}
case class InventoryVector(typ: InventoryVectorType, hash: Hash)

object RejectCode {
  implicit val codec: Codec[RejectCode] = mappedEnum(
    uint8L,
    REJECT_MALFORMED -> REJECT_MALFORMED.value,
    REJECT_INVALID -> REJECT_INVALID.value,
    REJECT_OBSOLETE -> REJECT_OBSOLETE.value,
    REJECT_DUPLICATE -> REJECT_DUPLICATE.value,
    REJECT_NONSTANDARD -> REJECT_NONSTANDARD.value,
    REJECT_DUST -> REJECT_DUST.value,
    REJECT_INSUFFICIENTFEE -> REJECT_INSUFFICIENTFEE.value,
    REJECT_CHECKPOINT -> REJECT_CHECKPOINT.value)

  case object REJECT_MALFORMED extends RejectCode(0x01)
  case object REJECT_INVALID extends RejectCode(0x10)
  case object REJECT_OBSOLETE extends RejectCode(0x11)
  case object REJECT_DUPLICATE extends RejectCode(0x12)
  case object REJECT_NONSTANDARD extends RejectCode(0x40)
  case object REJECT_DUST extends RejectCode(0x41)
  case object REJECT_INSUFFICIENTFEE extends RejectCode(0x42)
  case object REJECT_CHECKPOINT extends RejectCode(0x43)
}

sealed class RejectCode(val value: Int)

object Rejection {
  import RejectCode._

  implicit val codec = {
    ("message" | varString) ::
      ("ccode" | RejectCode.codec) ::
      ("reason" | varString) ::
      ("extraData" | choice(optionalVarBytes, provide(Option.empty[ByteVector])))
  }.as[Rejection]

  def invalidTx(reason: String)(tid: TID) =
    Rejection("tx", REJECT_INVALID, reason, Some(ByteVector(tid.toByteArray)))

  def invalidBlock(reason: String)(bid: BID) =
    Rejection("block", REJECT_INVALID, reason, Some(ByteVector(bid.toByteArray)))
}
case class Rejection(
  message: String,
  ccode: RejectCode,
  reason: String,
  extraData: Option[ByteVector] = None)

object Version {
  val NETWORK_NODE = BitVector.fromLong(1, 64, ByteOrdering.LittleEndian)
  val SIMPLE_NODE = BitVector.fromLong(0, 64, ByteOrdering.LittleEndian)

  implicit val codec = {
    ("version"           | uint32L) ::
      ("services"          | fixedSizeBits(64, scodec.codecs.bits)) ::
      ("timestamp"         | int64L) ::
      ("addr_recv"         | NetworkAddress.noTimestampCodec) ::
      ("addr_from"         | NetworkAddress.noTimestampCodec) ::
      ("nonce"             | int64L) ::
      ("user_agent"        | varString) ::
      ("start_height"      | int32L) ::
      ("relay"             | simpleBool)
  }.as[Version]


  val clock = java.time.Clock.systemUTC
  def forNow(
    v: Long,
    services: BitVector,
    addrRecv: NetworkAddress, // note: no timestamp serialize he,
    addrFrom: NetworkAddress, // note: no timestamp serialize he,
    nonce: Long,
    userAgent: String,
    startHeight: Int,
    relay: Boolean) =
    Version(v, services, clock.instant.getEpochSecond,
      addrRecv, addrFrom, nonce, userAgent, startHeight, relay)

}

case class Version(
  version: Long,
  services: BitVector,
  timestamp: Long,
  addrRecv: NetworkAddress, // note: no timestamp serialize here
  addrFrom: NetworkAddress, // note: no timestamp serialize here
  nonce: Long,
  userAgent: String,
  startHeight: Int,
  relay: Boolean) {
  def supportsServices(s: BitVector) = (services & s) == s

  override def toString = s"Version(nonce=${nonce.toHexString},startHeigh=$startHeight,version=$version,relay=$relay," +
    s"services=${services.toHex},timestamp=$timestamp," +
    s"addrRecv=$addrRecv,addrFrom=$addrFrom,userAgent=$userAgent"
}

object BlockDataRequest {
  implicit val codec = {
    ("version"       | uint32L) ::
      ("locatorHashes" | varIntSizeSeq(HashCodec.bidCodec)) ::
      ("hashStop"      | HashCodec.bidCodec)
  }.as[BlockDataRequest]
}

case class BlockDataRequest(
  version:       Long,
  locatorHashes: List[BID],
  hashStop:      BID)

object Ping {
  implicit val codec = long(64).hlist.as[Ping]
}
case class Ping(nonce: Long)

object Alert {
  def setCodec[A](elemCodec: Codec[A]): Codec[Set[A]] = varIntSizeSeq(elemCodec).xmap(_.toSet, _.toList)
  implicit val codec = {
    ("version"    | int32L) ::
      ("relayUntil" | int64L) ::
      ("expiration" | int64L) ::
      ("id"         | uint32L) ::
      ("cancel"     | uint32L) ::
      ("setCancel"  | setCodec(uint32L)) ::
      ("minVer"     | uint32L) ::
      ("maxVer"     | uint32L) ::
      ("setSubVer"  | setCodec(varString)) ::
      ("priority"   | uint32L) ::
      ("comment"    | varString) ::
      ("statusBar"  | varString) ::
      ("reserved"   | varString)
  }.as[Alert]
}
case class Alert(
  version:    Int,
  relayUntil: Long,
  expiration: Long,
  id:         Long,
  cancel:     Long,
  setCancel:  Set[Long],
  minVer:     Long,
  maxVer:     Long,
  setSubVer:  Set[String],
  priority:   Long,
  comment:    String,
  statusBar:  String,
  reserved:   String
)
