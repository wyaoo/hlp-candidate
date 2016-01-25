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

import akka.stream.{ Attributes, Inlet, Outlet, FlowShape }
import akka.stream.stage._
import org.hyperledger.common._
import org.hyperledger.network.{InventoryVector, InventoryVectorType, Messages, HyperLedger}
import InventoryVectorType.MSG_BLOCK
import Messages._
import org.hyperledger.network.server.TransactionDownloadState._
import org.hyperledger.network.server._

import scala.collection.JavaConverters._
import scala.concurrent.{ ExecutionContext, Future }
import scala.util._
import scalaz.std.tuple._
import scalaz.syntax.bifunctor._
import scalaz.syntax.std.option._
import scalaz.syntax.std.tuple._

object DataStoreStage {
  type ActionResult = Try[(BlockStoreState, List[CtrlOrMessage])]

  case class BlockStoreState(blocks: BlockDownloadState = BlockDownloadState.empty,
    txs: TransactionDownloadState = TransactionDownloadState.empty)

}

class DataStoreStage(hyperLedger: HyperLedger)(implicit ec: ExecutionContext) extends GraphStage[FlowShape[CtrlOrDataMessage, CtrlOrMessage]] {
  import DataStoreStage._

  val in = Inlet[CtrlOrDataMessage]("in")
  val out = Outlet[CtrlOrMessage]("out")


  override def shape: FlowShape[CtrlOrDataMessage, CtrlOrMessage] = new FlowShape(in, out)

  override def createLogic(inheritedAttributes: Attributes) = new GraphStageLogic(shape) {

    var result: List[CtrlOrMessage] = Nil
    var state = BlockStoreState()
    var holdingUpstream = false

    setHandler(in, new DataStoreInHandler)
    setHandler(out, new DataStoreOutHandler)

    override def preStart(): Unit = tryPull(in)

    val actionResultCallback = getAsyncCallback[ActionResult] {
      case Failure(ex: LoggedHyperLedgerException) => // TODO we need proper exception classes to decide what to do
      case Failure(ex)                             => failStage(ex)
      case Success((newState, messages)) =>
        state = newState
        result ++ messages match {
          case Nil => pull(in)
          case msg :: newResult if !(isClosed(in) || hasBeenPulled(in)) =>
            result = newResult
            pushIt(msg)
          case newResult => result = newResult
        }
    }

    class DataStoreInHandler extends InHandler {
      override def onPush(): Unit = {
        val thisState = state
        val elem = grab(in)
        val future = elem match {
          case Right(InvMessage(invs))               => receiveInvs(thisState.txs)(invs).map(x => (thisState, x)).some
          case Right(BlockMessage(block))            => receiveBlock(thisState)(block).some
          case Right(SignedBlockMessage(block))      => receiveBlock(thisState)(block).some
          case Right(TxMessage(tx))                  => receiveTx(thisState.txs)(tx).map(_.leftMap(ts => thisState.copy(txs = ts))).some
          case Right(NotFoundMessage(_))             => None // TODO handle NotFoundMessage
          case Left(ServerStateTransition(newState)) => Future.successful(thisState, List(Reply(MempoolMessage()))).some
          case _                                     => None
        }

        future match {
          case Some(f) =>
            f onComplete actionResultCallback.invoke

          case None => pull(in)
        }
      }
      override def onUpstreamFinish(): Unit = {
        if (holdingUpstream) absorbTermination()
        else completeStage()
      }

      private def absorbTermination() =
        if (isAvailable(out)) getHandler(out).onPull()

    }

    class DataStoreOutHandler extends OutHandler {
      override def onPull(): Unit = {
        result match {
          case message :: rest =>
            result = rest
            pushIt(message)
          case Nil if isClosed(in) => completeStage()
          case Nil                 => // hold
        }
      }
    }

    private def pushIt(message: CtrlOrMessage) = {
      push(out, message)
      if (isClosed(in)) {
        completeStage()
      } else if (result.isEmpty && !hasBeenPulled(in)) {
        pull(in)
        holdingUpstream = false
      }
    }

    def receiveInvs(txState: TransactionDownloadState)(invs: List[InventoryVector]): Future[List[CtrlOrMessage]] = {
      hyperLedger.filterUnknown(invs).map { missingInvs =>
        missingInvs.partition(_.typ == MSG_BLOCK)
          .rightMap(_.filterNot(i => txState.isKnown(new TID(i.hash))))
          .fold(_ ++ _) match {
            case Nil => List(NoReply)
            case is  => List(Reply(GetDataMessage(is)))
          }
      }
    }

    def sourceIDs(tx: Transaction) = tx.getInputs.asScala
      .map(_.getSourceTransactionID)
      .map(InventoryVector.tx)
      .toList.distinct

    def receiveTx(txState: TransactionDownloadState)(tx: Transaction): Future[(TransactionDownloadState, List[CtrlOrMessage])] =
      hyperLedger.filterUnknown(sourceIDs(tx)).map(_.map(i => new TID(i.hash))) flatMap { missingIDs =>
        txState.transactionReceived(tx, missingIDs)
          .rightMap {
            case Noop                      => Future.successful(Nil)
            case DoubleSpend(ids)          => Future.successful(Nil)
            case DownloadTransactions(txs) => Future.successful(List(Reply(GetDataMessage.txs(txs))))
            case StoreTransactions(txs) => hyperLedger.addTransactions(txs) map {
              case (_, Nil)          => Nil
              case (Nil, success)    => List(SendBroadcast(InvMessage.txs(success)))
              case (failed, success) => List(SendBroadcast(InvMessage.txs(success)))
              // TODO reject message for faulty txs?
            }
          }
          .fold((newState, f) => f.map(newState -> _))
      }

    def receiveBlock(state: BlockStoreState)(block: Block): Future[(BlockStoreState, List[CtrlOrMessage])] =
      blockStatus(block).flatMap {
        case (known, connected) if known || connected =>
          val (newBlocksState, toStore) =
            if (known) state.blocks.knownBlockReceived(block)
            else state.blocks.connectedBlockReceived(block)

          hyperLedger.addBlocks(toStore).map {
            case (_, Nil)          => Nil
            case (failed, success) => List(SendBroadcast(InvMessage.blocks(success.flatMap(_.getAddedToTrunk.asScala))))
            // TODO handle block store failure with either RejectMessage or ctx.fail()
          }.map(state.copy(blocks = newBlocksState) -> _)

        case _ =>
          val (newBlocksState, downloadPrevious) = state.blocks.blockReceived(block)
          if (downloadPrevious) Future.successful((state.copy(blocks = newBlocksState), List(Reply(GetDataMessage.blocks(block.getPreviousID)))))
          else Future.successful((state.copy(blocks = newBlocksState), Nil))
      }

    def blockStatus(block: Block) = for {
      known <- hyperLedger.hasBlock(block.getID)
      connected <- if (known) Future.successful(true) else hyperLedger.hasBlock(block.getPreviousID)
    } yield (known, connected)

  }

}
