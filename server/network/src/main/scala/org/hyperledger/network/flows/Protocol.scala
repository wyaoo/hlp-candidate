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
package org.hyperledger.network.flows

import akka.actor.ActorRef
import akka.actor.FSM.Transition
import org.hyperledger.common._
import org.hyperledger.network.{Version, Messages}
import Messages._
import org.hyperledger.network.server.ServerState

object ProtocolError {
  def invalidMessage(m: BlockchainMessage) = ProtocolError(s"Invalid message $m")
  def incompatible(m: Version) = ProtocolError(s"Incompatible peer $m")
  def misbehavingPeer(m: BlockchainMessage) = ProtocolError(s"Misbehabing peer sent $m")
}
case class ProtocolError(m: String) extends RuntimeException(m)

sealed trait CtrlOrMessage

/**
 * The protocol deals with MessagePayloads, but we need to:
 * - Distinguish between incoming and outgoing messages for routing purposes
 * - We need to attach the sender's metadata (Version) for incoming messages
 * - (Possibly for the reply too)
 */
sealed trait ProtocolMessage extends CtrlOrMessage
case class Incoming[M <: BlockchainMessage](message: M) extends ProtocolMessage
sealed trait Outgoing extends ProtocolMessage
case class Reply(m: BlockchainMessage) extends Outgoing
case object NoReply extends Outgoing

/**
 * Control messages are used for communication between peers and between a peer and the server.
 */
sealed trait ControlMessage extends CtrlOrMessage
case class HandshakeComplete(peerVersion: Version) extends ControlMessage
case object HeadersDownloadComplete extends ControlMessage
case class ServerStateTransition(newState: ServerState) extends ControlMessage

case class DownloadBlocks(hashes: List[BID]) extends ControlMessage
case class BlockReceived(block: Block) extends ControlMessage

case object PeerDisconnected extends ControlMessage
case class PeerCommunicationFailure(cause: Throwable) extends ControlMessage

case class SendBroadcast(message: BlockchainMessage) extends ControlMessage

// Internal control message
case class ProtocolFlowMaterialized(controlActor: ActorRef) extends ControlMessage
