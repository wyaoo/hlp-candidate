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

import java.net.InetSocketAddress

import akka.actor.ActorRef
import org.hyperledger.common.PublicKey
import org.hyperledger.network.Version
import org.hyperledger.pbft.PbftSettings.NodeConfig

import scalaz._
import Scalaz._

object ConnectionManagement {
  def empty = new ConnectionManagement(Map.empty, Map.empty)

  sealed trait Connection {
    def peer: ActorRef
  }
  case class PendingConnection(remoteAddress: InetSocketAddress, peer: ActorRef)
  case class ReplicaConnection(
    remoteAddress: InetSocketAddress,
    version: Version,
    publicKey: PublicKey,
    peer: ActorRef) extends Connection
  case class ClientConnection(
    remoteAddress: InetSocketAddress,
    version: Version,
    peer: ActorRef) extends Connection

  sealed trait ConnectionError
  case object AlreadyConnected extends ConnectionError
  case object UnknownConnection extends ConnectionError
}
case class ConnectionManagement(
  pendingConnections: Map[InetSocketAddress, ConnectionManagement.PendingConnection],
  activeConnections: Map[InetSocketAddress, ConnectionManagement.Connection]) {
  import ConnectionManagement._

  def connected(peer: ActorRef, remoteAddress: InetSocketAddress): ConnectionError \/ ConnectionManagement =
    if (pendingConnections.contains(remoteAddress))
      \/.left(AlreadyConnected)
    else
      \/.right(copy(pendingConnections = pendingConnections + (remoteAddress -> PendingConnection(remoteAddress, peer))))

  def timeout(remoteAddress: InetSocketAddress) = copy(pendingConnections = pendingConnections - remoteAddress)

  def disconnected(version: Version) = copy(activeConnections = activeConnections - version.addrFrom.address)

  def handshakeComplete(settings: PbftSettings,
    peer: ActorRef,
    remoteAddress: InetSocketAddress,
    version: Version): (ConnectionManagement, Connection) = {

    val conn = settings.otherNodes.find(_.address == version.addrFrom)
      .map(n => ReplicaConnection(remoteAddress, version, n.publicKey, peer))
      .getOrElse(ClientConnection(remoteAddress, version, peer))

    val updated = copy(
      pendingConnections = pendingConnections - remoteAddress,
      activeConnections = activeConnections + (version.addrFrom.address -> conn))

    (updated, conn)
  }
}
