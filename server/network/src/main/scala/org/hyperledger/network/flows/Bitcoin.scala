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

import _root_.akka.actor._
import _root_.akka.stream._
import _root_.akka.stream.scaladsl._
import _root_.akka.stream.stage._
import org.hyperledger.common.{HeaderWithSignatures, BitcoinHeader, BID, TID}
import org.hyperledger.core.conf.CoreAssemblyFactory
import org.hyperledger.network._
import InventoryVectorType.{MSG_BLOCK, MSG_TX}
import org.hyperledger.network.Version
import Messages._
import org.hyperledger.network.server.ServerActor.PeerControlMessage
import org.slf4j.LoggerFactory

import scala.concurrent.{Future, ExecutionContext}

object Bitcoin {
  val LOG = LoggerFactory.getLogger("BitcoinFlow")

  val HEADERS_MAX_SIZE = 2000
  val INV_MAX_SIZE = 50000
  val READ_CONCURRENCY = 2

  def misbehavingPeer[M <: BlockchainMessage] = Flow[M].transform(() => new PushStage[M, CtrlOrMessage] {
    def onPush(elem: M, ctx: Context[CtrlOrMessage]) = ctx.fail(ProtocolError.misbehavingPeer(elem))
  })

  /**
   * Flow, which doesn't produce a Reply
   */
  def noReplyFlow[M <: BlockchainMessage](f: M => Unit) = Flow[M].map(f).map(_ => NoReply)
  def placeholder[M <: BlockchainMessage]: MessageFlow[M] = Flow[M].map(_ => NoReply)

  def createProcessingGraph(version: Version, serverActor: ActorRef, hyperLedger: HyperLedgerExtension, outbound: Boolean)(implicit ec: ExecutionContext) = {
    val controlSource = Source.actorRef[ControlMessage](100, OverflowStrategy.dropHead)
    val controlSink = Sink.actorRef[PeerControlMessage](serverActor, PeerDisconnected)
    GraphDSL.create(controlSource, controlSink)(Keep.left) { implicit b =>
      (controlSource, controlSink) =>
        import GraphDSL.Implicits._

        val protocol = b.add(createProtocolFlow(hyperLedger, version, outbound))
        val decoder = b.add(MessageParser.messagePayloadDecoder(hyperLedger.settings.chain.p2pMagic))
        val encoder = b.add(MessageParser.messagePayloadEncoder(hyperLedger.settings.chain.p2pMagic))

        val enricher = Flow[CtrlOrMessage]
          .collect[ControlMessage] { case c: ControlMessage => c }
          .transform(() => new ControlMessageEnricher)

        val ctrlSplit = b.add(Broadcast[CtrlOrMessage](2).named("cos"))

        val ctrlSourceMat = Flow[ActorRef].map[CtrlOrMessage](ProtocolFlowMaterialized)
        val payloadExtract = Flow[CtrlOrMessage].collect[BlockchainMessage] { case Reply(m) => m }
        val ctrlMergeO = b.add(Merge[CtrlOrMessage](2).named("CtrlOrMessageMerge"))
        val ctrlMergeI = b.add(Merge[CtrlOrMessage](2, eagerClose = true))
        val toIncoming = Flow[BlockchainMessage].map(Incoming(_))

        // format: OFF
        b.materializedValue   ~> ctrlSourceMat          ~> ctrlMergeO
        controlSource         ~> ctrlMergeI
        decoder ~> toIncoming ~> ctrlMergeI ~> protocol ~> ctrlMergeO ~> ctrlSplit ~> payloadExtract   ~> encoder
                                                                         ctrlSplit ~> enricher         ~> controlSink
        // format: ON
        FlowShape(decoder.in, encoder.out)
    }
  }

  def dataFlow(hyperLedger: HyperLedger)(implicit ec: ExecutionContext) = {
    GraphDSL.create() { implicit b =>
      import GraphDSL.Implicits._
      val dataMessageFlow = Flow.fromGraph(new DataStoreStage(hyperLedger))
      val ibdMessageFlow = Flow[CtrlOrDataMessage].collect {
        case Right(BlockMessage(block))   => BlockReceived(block)
        case Right(SignedBlockMessage(block))   => BlockReceived(block)
        case Left(DownloadBlocks(blocks)) => Reply(GetDataMessage.blocks(blocks))
      }
      val ibdSwitch = b.add(IBDSwitch[DataMessage]())
      val dataOutMerge = b.add(Merge[CtrlOrMessage](2))

      ibdSwitch.normal ~> dataMessageFlow ~> dataOutMerge
      ibdSwitch.ibd ~> ibdMessageFlow ~> dataOutMerge

      FlowShape(ibdSwitch.in, dataOutMerge.out)
    }
  }

  def headersFlow2(hyperLedger: HyperLedgerExtension)(implicit ec: ExecutionContext) = {
    GraphDSL.create() { implicit b =>
      import GraphDSL.Implicits._

      val headersFlowIBD = Flow[HeadersMessage]
        .mapAsync(1) { h =>
          hyperLedger.api.addHeaders(h.headers).map { _ =>
            if (h.headers.size < HEADERS_MAX_SIZE)
              HeadersDownloadComplete
            else
              Reply(hyperLedger.createGetHeadersRequest)
          }
        }
      val headersFlowNormal = Flow[HeadersMessage]
        .mapAsync(1) { h =>
          hyperLedger.api.addHeaders(h.headers).flatMap { _ =>
            Future.sequence(h.headers.map(h => hyperLedger.api.hasBlock(h.getID).map(has => (has, h))))
              .map { tuples =>
                val missingHeaders = tuples.filterNot(_._1).map(_._2)
                val reply = Reply(GetDataMessage.blocks(missingHeaders.map(_.getID)))

                if (h.headers.size < HEADERS_MAX_SIZE) List(reply, HeadersDownloadComplete)
                else List(reply)
              }
          }
        }.mapConcat(identity)

      val ibdSwitch = b.add(IBDSwitch[HeadersMessage]())
      val dataOutMerge = b.add(Merge[CtrlOrMessage](2))

      val filter = Flow[Either[ControlMessage, HeadersMessage]].collect {
        case Right(h) => h
      }

      ibdSwitch.normal ~> filter ~> headersFlowNormal ~> dataOutMerge
      ibdSwitch.ibd    ~> filter ~> headersFlowIBD    ~> dataOutMerge

      FlowShape(ibdSwitch.in, dataOutMerge.out)
    }
  }

  def signedHeadersFlow2(hyperLedger: HyperLedgerExtension)(implicit ec: ExecutionContext) = {
    GraphDSL.create() { implicit b =>
      import GraphDSL.Implicits._

      val headersFlowIBD = Flow[SignedHeadersMessage]
        .mapAsync(1) { h =>
          hyperLedger.api.addHeaders(h.headers).map { _ =>
            if (h.headers.size < HEADERS_MAX_SIZE)
              HeadersDownloadComplete
            else
              Reply(hyperLedger.createGetHeadersRequest)
          }
        }
      val headersFlowNormal = Flow[SignedHeadersMessage]
        .mapAsync(1) { h =>
          hyperLedger.api.addHeaders(h.headers).flatMap { _ =>
            Future.sequence(h.headers.map(h => hyperLedger.api.hasBlock(h.getID).map(has => (has, h))))
              .map { tuples =>
                val missingHeaders = tuples.filterNot(_._1).map(_._2)
                val reply = Reply(GetDataMessage.blocks(missingHeaders.map(_.getID)))

                if (h.headers.size < HEADERS_MAX_SIZE) List(reply, HeadersDownloadComplete)
                else List(reply)
              }
          }
        }.mapConcat(identity)

      val ibdSwitch = b.add(IBDSwitch[SignedHeadersMessage]())
      val dataOutMerge = b.add(Merge[CtrlOrMessage](2))

      val filter = Flow[Either[ControlMessage, SignedHeadersMessage]].collect {
        case Right(h) => h
      }

      ibdSwitch.normal ~> filter ~> headersFlowNormal ~> dataOutMerge
      ibdSwitch.ibd    ~> filter ~> headersFlowIBD    ~> dataOutMerge

      FlowShape(ibdSwitch.in, dataOutMerge.out)
    }
  }

  def createProtocolFlow(hyperLedger: HyperLedgerExtension, version: Version, outbound: Boolean)(implicit ec: ExecutionContext) = {
    val versionO = if (outbound) None else Some(version)
    GraphDSL.create() { implicit b =>
      import GraphDSL.Implicits._

      val handshake = b.add(Flow[Incoming[_ <: BlockchainMessage]]
        .transform(() => new HandshakeStage(hyperLedger, versionO)))

      val headersFlow = b.add(headersFlow2(hyperLedger))
      val signedHeadersFlow = b.add(signedHeadersFlow2(hyperLedger))

      val mempoolFlow = Flow[MempoolMessage]
        .mapAsync(1) { _ => hyperLedger.api.mempool() }
        .mapConcat(_
          .grouped(INV_MAX_SIZE)
          .map(hashes => Reply(InvMessage(hashes.map(hash => InventoryVector(MSG_TX, hash)))))
          .toList)
      val pingFlow = Flow[PingMessage].map(p => Reply(p.pong))
      val rejectFlow = noReplyFlow[RejectMessage](hyperLedger.api.reject)
      val alertFlow = noReplyFlow[AlertMessage](hyperLedger.api.alert)

      val getDataFlow = Flow[GetDataMessage]
        .mapConcat(_.inventory)
        .mapAsync(READ_CONCURRENCY) {
          case i @ InventoryVector(MSG_TX, hash) =>
            hyperLedger.api.fetchTx(new TID(hash)).map(_.map(tx => TxMessage(tx)).getOrElse(NotFoundMessage(List(i))))

          case i @ InventoryVector(MSG_BLOCK, hash) =>
            hyperLedger.api.fetchBlock(new BID(hash)).map(_.map(b =>
              if (CoreAssemblyFactory.getAssembly.getBlockSignatureConfig.enabled()) SignedBlockMessage(b)
              else BlockMessage(b)).getOrElse(NotFoundMessage(List(i))))
        }.map(Reply(_))

      val getBlocksFlow = placeholder[GetBlocksMessage]

      val getHeadersFlow = Flow[GetHeadersMessage].mapConcat { m =>
        val req = m.payload
        val headers = hyperLedger.api.catchupHeaders(req.locatorHashes)
        (if (headers.contains(req.hashStop)) headers.dropWhile(_ != req.hashStop) else headers)
          .map(hyperLedger.api.fetchHeader)
          .grouped(HEADERS_MAX_SIZE)
          .map(storedHeaders => Reply(
            if (CoreAssemblyFactory.getAssembly.getBlockSignatureConfig.enabled()) {
              SignedHeadersMessage(storedHeaders.map(sh => sh.getHeader.asInstanceOf[HeaderWithSignatures]))
            } else {
              HeadersMessage(storedHeaders.map(sh => sh.getHeader))
            }
          ))
          .toList
      }
      val getAddrFlow = placeholder[GetAddrMessage]
      val addrFlow = placeholder[AddrMessage]
      val pongFlow = placeholder[PongMessage]

      val msgs = b.add(new ProtocolRoute)

      val payloadFlow: Flow[CtrlOrMessage, BlockchainMessage, Unit] = Flow[CtrlOrMessage].collect[BlockchainMessage] { case Incoming(r) => r }
      val controlFlow: Flow[CtrlOrMessage, ControlMessage, Unit] = Flow[CtrlOrMessage].collect[ControlMessage] { case m: ControlMessage => m }
      val notIncoming = Flow[CtrlOrMessage].filter {
        case Incoming(_) => false
        case _           => true
      }

      // this merges incoming DataMessages and control messages for the DataDownloadStage
      val dataCtrlMerge = b.add(new EitherMerge[ControlMessage, DataMessage])
      val headersCtrlMerge = b.add(new EitherMerge[ControlMessage, HeadersMessage])
      val signedHeadersCtrlMerge = b.add(new EitherMerge[ControlMessage, SignedHeadersMessage])

      // merging all protocol flows + the handshake output
      val protocolMerge = b.add(Merge[CtrlOrMessage](msgs.outlets.size + 2).named("ProtocolMerge"))
      // handshake produces Incoming messages and Outgoing/ControlMessages
      // we let Incoming through the protocol flow while Outgoing/ControlMessages sent directoy to protocolMerge
      val handshakeSplit = b.add(Broadcast[CtrlOrMessage](2).named("HandshakeSplit"))

      //val controlBroadcast = b.add(Broadcast[ControlMessage](2))
      val broadcaster = Flow[CtrlOrMessage] collect {
        case SendBroadcast(m) => Reply(m)
      }

      val dataF = b.add(dataFlow(hyperLedger.api))

      // newstuff
      val incoming = Flow[CtrlOrMessage].collect[Incoming[_ <: BlockchainMessage]] { case m @ Incoming(_) => m }
      val inBroadcast = b.add(Broadcast[CtrlOrMessage](5).named("InBroadcast"))

      // format: OFF
      inBroadcast ~> incoming    ~> handshake
      inBroadcast ~> controlFlow ~> dataCtrlMerge.left
      inBroadcast ~> controlFlow ~> headersCtrlMerge.left
      inBroadcast ~> controlFlow ~> signedHeadersCtrlMerge.left
      inBroadcast ~> controlFlow ~> broadcaster ~> protocolMerge

      handshake ~> handshakeSplit
                   handshakeSplit.out(1) ~> notIncoming                                                                ~> protocolMerge
                   handshakeSplit.out(0) ~> payloadFlow ~> msgs.in
                                                           msgs.dataOut             ~> dataCtrlMerge.right
                                                                                       dataCtrlMerge.out   ~>   dataF  ~> protocolMerge

                                                           msgs.headersOut          ~> headersCtrlMerge.right
                                                                                       headersCtrlMerge.out ~> headersFlow   ~> protocolMerge
                                                           msgs.signedHeadersOut    ~> signedHeadersCtrlMerge.right
                                                                                       signedHeadersCtrlMerge.out ~> signedHeadersFlow   ~> protocolMerge
                                                           //msgs.signedHeadersOut    ~> signedHeadersFlow               ~> protocolMerge
                                                           msgs.mempoolOut          ~> mempoolFlow                     ~> protocolMerge
                                                           msgs.pingOut             ~> pingFlow                        ~> protocolMerge
                                                           msgs.rejectOut           ~> rejectFlow                      ~> protocolMerge
                                                           msgs.getDataOut          ~> getDataFlow                     ~> protocolMerge
                                                           msgs.getBlocksOut        ~> getBlocksFlow                   ~> protocolMerge
                                                           msgs.getHeadersOut       ~> getHeadersFlow                  ~> protocolMerge
                                                           msgs.getAddrOut          ~> getAddrFlow                     ~> protocolMerge
                                                           msgs.addrOut             ~> addrFlow                        ~> protocolMerge
                                                           msgs.pongOut             ~> pongFlow                        ~> protocolMerge
                                                           msgs.alertOut            ~> alertFlow                       ~> protocolMerge
                                                           msgs.verackOut           ~> misbehavingPeer[VerackMessage]  ~> protocolMerge
                                                           msgs.versionOut          ~> misbehavingPeer[VersionMessage] ~> protocolMerge
      //format: ON
      FlowShape(inBroadcast.in, protocolMerge.out)
    }
  }
}
