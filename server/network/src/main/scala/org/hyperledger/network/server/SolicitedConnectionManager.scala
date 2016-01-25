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
import java.time.Clock

import akka.actor.FSM._
import akka.actor._
import akka.pattern._
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Flow, Tcp, Keep}
import akka.stream.scaladsl.Tcp.{IncomingConnection, OutgoingConnection}
import org.hyperledger.network.{Ping, Version, Messages, HyperLedgerExtension}
import Messages.{PingMessage, VersionMessage}
import org.hyperledger.network._
import org.hyperledger.network.flows._
import org.hyperledger.network.server.ServerActor._

import scala.util.Random
import scalaz.\/
import scalaz.syntax.either._

object SolicitedConnectionManager {
  def props(server: ActorRef) = Props(classOf[SolicitedConnectionManager], server)

  case object MaintainConnections

}

class SolicitedConnectionManager(server: ActorRef) extends Actor with ActorLogging {

  import SolicitedConnectionManager._

  val clock = Clock.systemUTC()

  val hyperLedger = HyperLedgerExtension(context.system)

  val numberOfConnections = hyperLedger.settings.outgoingConnections
  var firstHeaders = false

  var pendingConnections = Set.empty[InetSocketAddress]
  var connections = ConnectionMaintenance(hyperLedger.NONCE, hyperLedger.settings.discoverySettings.collect {
    case FixedAddressDiscovery(addresses) => addresses
  }.flatten.toSet)

  override def preStart() = server ! SubscribeTransitionCallBack(context.self)

  def receive = {
    case CurrentState(_, state: ServerState) =>
      context.self ! MaintainConnections
      bindServer()

    case MaintainConnections => maintainConnections()
    case ce: PeerConnection.ConnectionEvent => ce match {
      case PeerConnection.PeerUnavailable(address) =>
        pendingConnections -= address
        //peers -= sender()
        updateConnections(_.unavailable(address, clock.millis()).right)
        maintainConnections()

      case PeerConnection.ConnectionFailure(address, cause, data) =>
        //peers -= sender()
        pendingConnections -= address
        updateConnections(_.protocolError(address, cause))
        maintainConnections()

      case PeerConnection.Disconnected(address, data) =>
        //peers -= sender()
        pendingConnections -= address
        updateConnections(_.disconnected(address).right)
        maintainConnections()

      case PeerConnection.ShookHands(address, version) =>
        updateConnections(_.handshakeComplete(version, address, clock.millis()))

      case PeerConnection.HeadersDownloaded(address) =>
        if (!firstHeaders) {
          firstHeaders = true
          maintainConnections()
        }
    }

    case scm: ServerControlMessage => context.children.filterNot(_ == sender()).foreach(_ ! scm)
  }

  def updateConnections(f: ConnectionMaintenance => String \/ ConnectionMaintenance) = {
    f(connections).fold({
      error =>
        log.info(s"Closing connection : $error")
        context stop sender()
    }, {
      newConnections =>
        connections = newConnections
        context.parent ! connections
    })
  }

  def maintainConnections() = {
    if (pendingConnections.isEmpty || (firstHeaders && pendingConnections.size < numberOfConnections)) {
      import scala.concurrent.duration._
      import context.dispatcher

      log.debug(s"Trying to get connections. ${pendingConnections.size} < $numberOfConnections firstHeaders = $firstHeaders")
      connections.addressesForConnection(1, clock.millis(), pi => !pendingConnections(pi.address)).foreach { peer =>
        connectToPeer(peer.address)
        pendingConnections += peer.address
      }

      context.system.scheduler.scheduleOnce(1 minute, context.self, MaintainConnections)
    }
  }

  def connectToPeer(address: InetSocketAddress) = {
    import context.dispatcher
    implicit val mat = ActorMaterializer()

    val peerActor = context.actorOf(PeerConnection.props(address, server))
    server ! SubscribeTransitionCallBack(peerActor)

    val (connection, controlSink) = Tcp(context.system)
      .outgoingConnection(address)
      .joinMat(Bitcoin.createProcessingGraph(hyperLedger.ourVersion(address), peerActor, hyperLedger, outbound = true))(Keep.both)
      .run

    connection.map(c => PeerConnection.OutboundPeerConnection(c, controlSink)).recover {
      case e: Throwable => PeerConnection.PeerConnectionFailed
    }.pipeTo(peerActor)
  }

  private def bindServer() = {
    hyperLedger.settings.bindAddress.foreach { address =>
      import context.dispatcher
      implicit val mat = ActorMaterializer()

      val connections = Tcp(context.system).bind(address.getHostString, address.getPort)
      connections.runForeach { connection =>
        val peerActor = context.actorOf(PeerConnection.props(connection.remoteAddress, server))
        server ! SubscribeTransitionCallBack(peerActor)

        val graph = Bitcoin.createProcessingGraph(hyperLedger.ourVersion(connection.remoteAddress), peerActor, hyperLedger, outbound = false)
        val controlSink = connection.handleWith(Flow.fromGraph(graph))
        peerActor ! PeerConnection.InboundPeerConnection(connection, controlSink)
      }
    }
  }

}

object PeerConnection {
  def props(address: InetSocketAddress, server: ActorRef) = Props(classOf[PeerConnection], address, server)

  case object PeerConnectionFailed

  sealed trait PeerConnectionX[C] {
    def connection: C
    def actor: ActorRef
  }

  case class OutboundPeerConnection(connection: OutgoingConnection, actor: ActorRef) extends PeerConnectionX[OutgoingConnection]
  case class InboundPeerConnection(connection: IncomingConnection, actor: ActorRef) extends PeerConnectionX[IncomingConnection]

  sealed trait ConnectionEvent {
    def address: InetSocketAddress
  }
  case class PeerUnavailable(address: InetSocketAddress) extends ConnectionEvent
  case class ConnectionFailure(address: InetSocketAddress, cause: Throwable, data: Data) extends ConnectionEvent
  case class Disconnected(address: InetSocketAddress, data: Data) extends ConnectionEvent
  case class ShookHands(address: InetSocketAddress, version: Version) extends ConnectionEvent
  case class HeadersDownloaded(address: InetSocketAddress) extends ConnectionEvent

  sealed trait State
  case object Initializing extends State
  case object Connecting extends State
  case object Handshake extends State
  case object HeaderDownload extends State
  case object Running extends State

  sealed trait Data
  case object Initial extends Data
  case class Waiting(serverState: ServerState) extends Data
  case class Connected(serverState: ServerState, actor: ActorRef, inbound: Boolean) extends Data
  case class PeerData(serverState: ServerState, actor: ActorRef, version: Version) extends Data

}

class PeerConnection(address: InetSocketAddress, server: ActorRef) extends LoggingFSM[PeerConnection.State, PeerConnection.Data] {

  import PeerConnection._

  val hyperLedger = HyperLedgerExtension(context.system)

  override def preStart() = {
    log.debug(s"Initializing connection to $address")
    import scala.concurrent.duration._
    setTimer("ping", ServerControlMessage(SendBroadcast(PingMessage(Ping(Random.nextLong()))), None), 120 seconds, repeat = true)
  }

  startWith(Initializing, Initial)

  when(Initializing) {
    case Event(CurrentState(_, state: ServerState), _) => goto(Connecting) using Waiting(state)
  }

  when(Connecting) {
    case Event(Transition(_, _, state: ServerState), Waiting(_)) =>
      stay() using Waiting(state)

    case Event(OutboundPeerConnection(conn, actor), Waiting(state)) =>
      actor ! ServerStateTransition(state)
      actor ! SendBroadcast(VersionMessage(hyperLedger.ourVersion(address)))
      goto(Handshake) using Connected(state, actor, inbound = false)

    case Event(InboundPeerConnection(conn, actor), Waiting(state)) =>
      actor ! ServerStateTransition(state)
      goto(Handshake) using Connected(state, actor, inbound = true)

    case Event(PeerConnectionFailed, _) =>
      context.parent ! PeerUnavailable(address)
      stop()
  }

  when(Handshake) {
    case Event(PeerControlMessage(HandshakeComplete(v), version, a), Connected(state, actor, inbound)) =>
      context.parent ! ShookHands(address, v)
      if (inbound) goto(Running) using PeerData(state, actor, version)
      else goto(HeaderDownload) using PeerData(state, actor, version)
  }

  when(HeaderDownload) {
    case Event(pcm@PeerControlMessage(HeadersDownloadComplete, version, actor), _) =>
      context.parent ! HeadersDownloaded(address)
      server ! pcm
      goto(Running)
  }

  when(Running) {
    case Event(PeerControlMessage(msg: SendBroadcast, version, actor), _) =>
      context.parent ! ServerControlMessage(msg, Some(version.nonce))
      stay()

    case Event(pc: PeerControlMessage, _) =>
      server ! pc
      stay()
  }

  whenUnhandled {
    case Event(Transition(_, _, state: ServerState), d@PeerData(oldState, actor, version)) =>
      actor ! ServerStateTransition(state)
      stay() using d.copy(serverState = state)

    case Event(Transition(_, _, state: ServerState), d@Connected(oldState, actor, _)) =>
      actor ! ServerStateTransition(state)
      stay() using d.copy(serverState = state)

    case Event(akka.actor.Status.Failure(error), d) =>
      context.parent ! ConnectionFailure(address, error, d)
      log.error(error, "Peer connection closed with error")
      stop()

    case Event(PeerDisconnected, d) =>
      context.parent ! Disconnected(address, d)
      log.info("Peer disconnected")
      stop()

    case Event(ServerControlMessage(msg, peerIdOpt), PeerData(_, actor, v)) =>
      if (peerIdOpt.map(_ == v.nonce).getOrElse(true)) {
        actor ! msg
      }
      stay()
  }

  initialize()
}
