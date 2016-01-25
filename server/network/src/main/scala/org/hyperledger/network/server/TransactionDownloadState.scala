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

import org.hyperledger.common.{ TID, Transaction }

import scala.annotation.tailrec
import scala.collection.JavaConverters._

object TransactionDownloadState {

  object Command {
    def apply[T <: Command, X](f: List[X] => T)(l: List[X]): Command = if (l.nonEmpty) f(l) else Noop
  }
  sealed trait Command
  case class DownloadTransactions(ids: List[TID]) extends Command
  case class StoreTransactions(ids: List[Transaction]) extends Command
  case object Noop extends Command
  case class DoubleSpend(txs: List[Transaction]) extends Command

  def empty = TransactionDownloadState()
}

import org.hyperledger.network.server.TransactionDownloadState._

case class TransactionDownloadState(txs: Map[TID, Transaction] = Map.empty,
                                     pendingDLs: Map[TID, TID] = Map.empty) {

  def isKnown(id: TID) = txs.contains(id) || pendingDLs.contains(id)

  lazy val rootIds = pendingDLs.values.toSet

  def transactionReceived(tx: Transaction, missingInputs: List[TID]) = {
    val tid = tx.getID
    if (txs.contains(tid)) {
      (this, Noop)
    } else {
      val rootId = pendingDLs.getOrElse(tid, tid)

      val thingsToDownload = missingInputs.filterNot(isKnown).map(_ -> rootId)
      val newPendingDLs = pendingDLs - tid ++ thingsToDownload
      val sourceIds = tx.getInputs.asScala.map(_.getSourceTransactionID).toSet

      if (((txs.keySet ++ pendingDLs.keySet -- rootIds) intersect sourceIds).nonEmpty) {
        val (newTxs, toReject) = collectToStore(rootId, txs)
        copy(newTxs, pendingDLs.filterNot(_._2 == rootId)) -> Command(DoubleSpend)(toReject)
      } else if (newPendingDLs.exists(_._2 == rootId)) {
        copy(txs + (tid -> tx), newPendingDLs) -> Command(DownloadTransactions)(thingsToDownload.map(_._1))
      } else if ((rootIds intersect sourceIds).nonEmpty) {
        copy(
          txs + (tid -> tx),
          newPendingDLs.collect {
            case (txid, rootid) if sourceIds(rootid) => (txid, rootId)
            case x                                   => x
          }) -> Command(DownloadTransactions)(thingsToDownload.map(_._1))
      } else {
        val (newTxs, toStoreTxs) = collectToStore(rootId, txs + (tid -> tx))
        copy(newTxs, newPendingDLs) -> Command(StoreTransactions)(toStoreTxs)
      }
    }
  }

  def transactionStored(storedId: TID) = {
    import scalaz.std.tuple._
    import scalaz.syntax.bifunctor._

    if (pendingDLs.contains(storedId)) {
      val rootID = pendingDLs(storedId)
      val newPendingDLs = pendingDLs - storedId
      if (newPendingDLs.exists(_._2 == rootID)) {
        copy(pendingDLs = newPendingDLs) -> Noop
      } else {
        collectToStore(rootID, txs).bimap(
          newTxs => copy(pendingDLs = newPendingDLs, txs = newTxs),
          Command(StoreTransactions))
      }
    } else if (txs.contains(storedId)) {
      val (updatedTxs, depsToRemove) = collectToStore(storedId, txs)
      val pendingDeps = depsToRemove.flatMap(_.getInputs.asScala.map(_.getSourceTransactionID)).toSet

      val (obsoleteDLRoots, newPendingDLs) = pendingDLs.partition(x => pendingDeps(x._1)).leftMap(_.values.toSet)
      //assert(obsoleteDLRoots.size == 1)

      if (newPendingDLs.values.exists(obsoleteDLRoots)) {
        copy(txs = updatedTxs, pendingDLs = newPendingDLs) -> Noop
      } else {
        val rootID = obsoleteDLRoots.head
        collectToStore(rootID, updatedTxs).bimap(
          newTxs => copy(txs = newTxs, pendingDLs = newPendingDLs),
          Command(StoreTransactions)
        )
      }
    } else {
      this -> Noop
    }
  }

  def collectToStore(rootId: TID, txs: Map[TID, Transaction]): (Map[TID, Transaction], List[Transaction]) = {
    @tailrec
    def go(rootIds: Set[TID],
           txMap: Map[TID, Transaction],
           accu: List[Transaction]): (Map[TID, Transaction], List[Transaction]) = txMap.filterKeys(rootIds) match {
      case toStore if toStore.isEmpty => (txMap, accu)
      case toStore =>
        val toStoreDependencies = toStore.values.flatMap(_.getInputs.asScala.map(_.getSourceTransactionID)).toSet
        go(toStoreDependencies, txMap -- toStore.keySet, toStore.values.toList ++ accu)
    }

    go(Set(rootId), txs, List.empty[Transaction])
  }
}
