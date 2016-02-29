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
package org.hyperledger.network.server

import java.net.InetSocketAddress

import org.hyperledger.network.Version

import scalaz.\/
import scalaz.syntax.either._

object ConnectionMaintenance {
  case class ActiveConnection(connectionAddress: InetSocketAddress, version: Version)

  case class PeerInfo(address: InetSocketAddress,
    lastConnected: Long = 0L,
    lastTry: Long = 0L,
    attempts: Int = 0)

  def apply(ourNonce: Long, preferredAddresses: Set[InetSocketAddress]) =
    new ConnectionMaintenance(ourNonce, preferredAddresses, preferredAddresses.map(a => a -> PeerInfo(a)).toMap)
}

case class ConnectionMaintenance(
  ourNonce: Long,
  preferredAddresses: Set[InetSocketAddress],
  peerRegistry: Map[InetSocketAddress, ConnectionMaintenance.PeerInfo] = Map.empty,
  newPeers: Set[InetSocketAddress] = Set.empty,
  activePeers: Map[Long, ConnectionMaintenance.ActiveConnection] = Map.empty) {
  import ConnectionMaintenance._

  def connectionIDs: Set[Long] = activePeers.keySet
  def numberOfActivePeers = activePeers.size

  def unavailable(address: InetSocketAddress, now: Long) = {
    val info = peerRegistry.getOrElse(address, PeerInfo(address))
    if (!preferredAddresses(address) && info.attempts == 9) {
      copy(peerRegistry = peerRegistry - address)
    } else {
      copy(peerRegistry = peerRegistry + (address -> info.copy(attempts = info.attempts + 1, lastTry = now)))
    }
  }

  def disconnected(address: InetSocketAddress) =
    copy(activePeers = activePeers.filterNot(_._2.connectionAddress == address))

  def protocolError(address: InetSocketAddress, cause: Throwable): String \/ ConnectionMaintenance= // TODO handle bans here
    copy(
      peerRegistry = peerRegistry.filterNot(_ == address),
      activePeers = activePeers.filterNot(_._2.connectionAddress == address)).right

  def discovered(addresses: Set[InetSocketAddress]) = {
    copy(newPeers = newPeers ++ addresses.filterNot(peerRegistry.keySet))
  }

  def handshakeComplete(version: Version, connectionAddress: InetSocketAddress, now: Long): String \/ ConnectionMaintenance = {
    if (ourNonce == version.nonce) {
      "Connecting to self".left
    } else if (activePeers contains version.nonce) {
      s"Node already connected: ${version.nonce.toHexString}".left
    } else {
      copy(activePeers = activePeers + (version.nonce -> ActiveConnection(connectionAddress, version)),
        peerRegistry = peerRegistry.updated(connectionAddress,
          peerRegistry.get(connectionAddress)
            .map(_.copy(lastConnected = now, attempts = 0))
            .getOrElse(PeerInfo(connectionAddress))))
        .right
    }
  }

  def addressesForConnection(nr: Int, now: Long, p: PeerInfo => Boolean) = {
    val missingPreferred = preferredAddresses -- activePeers.map(_._2.connectionAddress).filter(preferredAddresses)
    val prefs = missingPreferred.toStream.flatMap(peerRegistry.get).filter(canTryToConnect(now))
    val s = prefs #::: peerRegistry.toStream.filterNot(e => missingPreferred(e._1)).map(_._2)
    s.filter(p).take(nr).toList
  }

  def canTryToConnect(now: Long)(info: PeerInfo) = {
    val minutes = (1 `max` Math.pow(info.attempts, 1.3).toInt) * 1000
    val ret = info.lastTry == 0 || (now - info.lastTry) > minutes
    ret
  }
}
