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
package org.hyperledger.pbft.streams

import akka.actor.ActorRef
import akka.pattern.ask
import akka.stream._
import akka.stream.scaladsl._
import akka.util.Timeout
import org.hyperledger.common.{ Block, Transaction }
import org.hyperledger.network.Implicits._
import org.hyperledger.network.InventoryVectorType.{ MSG_BLOCK, MSG_TX }
import org.hyperledger.network.flows.ScodecStage
import org.hyperledger.network.{ BlockDataRequest, InventoryVector, Rejection, Version }
import org.hyperledger.pbft.PbftHandler.UpdateViewSeq
import org.hyperledger.pbft.RecoveryActor.NewHeaders
import org.hyperledger.pbft._
import org.slf4j.LoggerFactory
import scodec.Attempt

import scala.concurrent.{ ExecutionContext, Future }
import scalaz.\/

object PbftStreams {
  import scala.concurrent.duration._
  implicit val timeout: Timeout = 1 seconds

  val LOG = LoggerFactory.getLogger("PbftStreams")

  val NETWORK_MAGIC = 1
  val HEADERS_MAX_SIZE = 1000

  def createFlow(settings: PbftSettings,
    store: PbftBlockstoreInterface,
    versionP: Version => String \/ Unit,
    handler: ActorRef,
    ourVersion: Version,
    outbound: Boolean)(implicit ec: ExecutionContext) = {
    val handshake = new HandshakeStage(ourVersion, versionP, outbound)

    ScodecStage.bidi(PbftMessage.messageCodec(NETWORK_MAGIC))
      .atopMat(BidiFlow.fromGraph(handshake))(Keep.right)
      .joinMat(createProtocolFlow(settings, store, handler))(Keep.both)
  }

  def createProtocolFlow(settings: PbftSettings,
    store: PbftBlockstoreInterface,
    handler: ActorRef)(implicit ec: ExecutionContext): Graph[FlowShape[PbftMessage, PbftMessage], ActorRef] = {
    val broadcastSource = Source.actorRef(100, OverflowStrategy.dropHead)
    GraphDSL.create(broadcastSource) { implicit b =>
      broadcast =>
        import GraphDSL.Implicits._

        val forwardFilter = Flow[PbftMessage].collect {
          case m: PrePrepareMessage => m
          case m: PrepareMessage    => m
          case m: CommitMessage     => m
          case m: ViewChangeMessage => m
          case m: NewViewMessage    => m
        }

        val forwardToHandlerGen = Flow[PbftMessage]
          .mapAsync(1)(m => (handler ? m).mapTo[List[PbftMessage]])
          .mapConcat[PbftMessage](identity)

        val txFlow = Flow[PbftMessage]
          .collect { case tx: TxMessage => tx }
          .mapAsync(1) { tm =>
            store.addTransactions(List(tm.payload))
              .map(_._1.map(Rejection.invalidTx("invalid-tx")).map(RejectMessage))
          }.mapConcat(identity)

        val invFlow = Flow[PbftMessage]
          .collect { case m: InvMessage => m }
          .mapAsync(1)(m => store.filterUnkown(m.payload).map(GetDataMessage))

        val getHeadersFlow = Flow[PbftMessage]
          .collect {
            case GetHeadersMessage(BlockDataRequest(_, locatorHashes, hashStop)) =>
              store.getHeadersAndCommits(locatorHashes, hashStop)
          }.mapConcat(_.fold(
            err => {
              LOG.error(s"Error when reading commits: ${err.message}")
              List.empty
            },
            list => {
              val headersAndCommits = list.take(HEADERS_MAX_SIZE).map((HeaderAndCommit.apply _).tupled)
              List(HeadersMessage(headersAndCommits))
            }))

        val headersFlow = Flow[PbftMessage]
          .mapAsync(1) {
            case m: HeadersMessage =>
              val invVectors = m.payload.map { item =>
                Option(store.fetchHeader(item.header.getID)) match {
                  case None =>
                    store.validateAndAdd(item.header, item.commits, settings)
                      .flatMap { commits =>
                        handler ! UpdateViewSeq(commits.head.viewSeq)
                        val id = commits.head.blockHeader.getID
                        store.hasBlock(id) map { hasIt => (hasIt, id) }
                      }.map { hasIt =>
                        if (hasIt._1) None
                        else Some(InventoryVector(MSG_BLOCK, hasIt._2))
                      }
                  case Some(_) =>
                    Future.successful(None)
                }

              }
              Future.sequence(invVectors).map { ivs =>
                handler ! NewHeaders()
                val invVectors = ivs.flatten
                if (invVectors.nonEmpty) {
                  List(GetDataMessage(invVectors))
                } else {
                  List.empty
                }
              }
            case _ => Future.successful(Nil)
          }.mapConcat(identity)

        val blockFlow = Flow[PbftMessage]
          .mapAsync(1) {
            case BlockMessage(block) =>
              store.hasBlock(block.getID).filter { _ == false }
                .flatMap { _ => store.validateBlock(block) }.filter { _ == true }
                .flatMap { _ => store.storeBlock(block) }
                .map { _ => List.empty[PbftMessage] }
            case _ => Future.successful(Nil)
          }.mapConcat(identity)

        val getDataFlow = Flow[PbftMessage]
          .mapConcat {
            case m: GetDataMessage => m.payload filter { msg => msg.typ == MSG_TX || msg.typ == MSG_BLOCK }
            case _                 => Nil
          }
          .mapAsync(1) {
            case InventoryVector(MSG_TX, id)    => store.fetchTx(id.toTID)
            case InventoryVector(MSG_BLOCK, id) => store.fetchBlock(id.toBID)
            case _                              => Future.successful(None)
          }
          .collect {
            case Some(t: Transaction) => TxMessage(t)
            case Some(b: Block)       => BlockMessage(b)
          }

        val verifySig = b.add(Flow[PbftMessage].transform(() => new VerifySignatureStage(settings)))

        val inputRoute = b.add(Broadcast[PbftMessage](7))
        val outMerge = b.add(Merge[PbftMessage](8, eagerClose = true))

        // format: OFF
        //inputLogger ~>
        verifySig ~> inputRoute ~> forwardFilter ~> forwardToHandlerGen ~> outMerge
                     inputRoute ~> txFlow                               ~> outMerge //~> outputLogger
                     inputRoute ~> invFlow                              ~> outMerge
                     inputRoute ~> getDataFlow                          ~> outMerge
                     inputRoute ~> getHeadersFlow                       ~> outMerge
                     inputRoute ~> headersFlow                          ~> outMerge
                     inputRoute ~> blockFlow                            ~> outMerge
                                   broadcast                            ~> outMerge
        // format: ON

        //FlowShape(inputLogger.in, outputLogger.out)
        FlowShape(verifySig.in, outMerge.out)
    }
  }

  case class ProtocolError(message: String) extends Exception(message)
}
