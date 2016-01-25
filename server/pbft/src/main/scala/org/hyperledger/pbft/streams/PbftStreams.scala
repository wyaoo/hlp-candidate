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

import java.net.InetSocketAddress

import akka.actor.ActorRef
import akka.pattern.ask
import akka.stream._
import akka.stream.scaladsl._
import akka.util.Timeout
import org.hyperledger.common.Transaction
import org.hyperledger.network.Implicits._
import org.hyperledger.network.flows.ScodecStage
import org.hyperledger.network.{Version, Rejection, InventoryVector, InventoryVectorType}
import InventoryVectorType.MSG_TX
import org.hyperledger.pbft._

import scala.concurrent.{ ExecutionContext, Future }
import scalaz.\/

object PbftStreams {
  import scala.concurrent.duration._
  implicit val timeout: Timeout = 1 seconds

  val NETWORK_MAGIC = 1

  def createFlow(settings: PbftSettings,
    store: PbftBlockstoreInterface,
    versionP: Version => String \/ Unit,
    handler: ActorRef,
    remoteAddress: InetSocketAddress,
    ourVersion: Version,
    outbound: Boolean)(implicit ec: ExecutionContext) = {
    val handshake = new HandshakeStage(ourVersion, versionP, outbound)

    ScodecStage.bidi(PbftMessage.messageCodec(NETWORK_MAGIC))
      .atopMat(BidiFlow.fromGraph(handshake))(Keep.right)
      .joinMat(createProtocolFlow(settings, store, handler, remoteAddress))(Keep.both)
  }

  def createProtocolFlow(settings: PbftSettings,
    store: PbftBlockstoreInterface,
    handler: ActorRef,
    remoteAddress: InetSocketAddress)(implicit ec: ExecutionContext): Graph[FlowShape[PbftMessage, PbftMessage], ActorRef] = {
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
          case m: BlockMessage      => m
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

        val getDataFlow = Flow[PbftMessage]
          .mapConcat {
            case m: GetDataMessage => m.payload.filter(_.typ == MSG_TX)
            case _                 => Nil
          }
          .mapAsync(1) {
            case InventoryVector(MSG_TX, id) => store.fetchTx(id.toTID)
            case _                           => Future.successful(None)
          }
          .collect { case Some(t: Transaction) => TxMessage(t) }

//        val inputLogger = b.add(Flow[PbftMessage].log(s"Receiving $remoteAddress", _.toString.take(160)))
//        val outputLogger = b.add(Flow[PbftMessage].log(s"Sending $remoteAddress  ", _.toString.take(160)))

        val verifySig = b.add(Flow[PbftMessage].transform(() => new VerifySignatureStage(settings)))

        val inputRoute = b.add(Broadcast[PbftMessage](4))
        val outMerge = b.add(Merge[PbftMessage](5, eagerClose = true))

        // format: OFF
        //inputLogger ~>
        verifySig ~> inputRoute ~> forwardFilter ~> forwardToHandlerGen ~> outMerge
                     inputRoute ~> txFlow                               ~> outMerge //~> outputLogger
                     inputRoute ~> invFlow                              ~> outMerge
                     inputRoute ~> getDataFlow                          ~> outMerge
                                   broadcast                            ~> outMerge
        // format: ON

        //FlowShape(inputLogger.in, outputLogger.out)
        FlowShape(verifySig.in, outMerge.out)
    }
  }

  case class ProtocolError(message: String) extends Exception(message)
}
