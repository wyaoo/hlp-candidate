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

import akka.actor.{ LoggingFSM, Props }
import akka.pattern.pipe
import org.hyperledger.common.BID
import org.hyperledger.network.InventoryVector
import org.hyperledger.network.InventoryVectorType.MSG_BLOCK
import org.hyperledger.pbft.CommitCounter._

object CommitCounter {
  sealed trait CommitCounterState
  case object Counting extends CommitCounterState

  case class CommitCounterData(commits: Map[Int, Commit])

  case class Finished(id: BID)

  def props(id: BID, f: Int) = Props(new CommitCounter(id, f))

  def getBlockMessage(id: BID) = List(GetDataMessage(List(InventoryVector(MSG_BLOCK, id))))
}

class CommitCounter(id: BID, f: Int) extends LoggingFSM[CommitCounterState, CommitCounterData] {
  import context.dispatcher

  val extension = PbftExtension(context.system)
  val settings = extension.settings
  val store = extension.blockStoreConn

  startWith(Counting, CommitCounterData(Map.empty))

  setTimer("accept-timer", Finished(id), settings.protocolTimeout)

  when(Counting) {
    case Event(m: Commit, data) =>
      val commits = data.commits.updated(m.node, m)
      if (commits.size > 2 * f) {
        store.validateAndAdd(m.blockHeader, commits.values.toList, settings)
          .map { _ => getBlockMessage(id) }
          .pipeTo(sender)
        context.parent ! Finished(id)
        log.debug("Enough commits received, block sent for storage.")
        stop
      } else {
        log.debug(s"Number of commits received: ${commits.size}")
        sender ! PbftClient.emptyResponse
        stay using data.copy(commits = commits)
      }

    case Event(m: Finished, data) =>
      log.debug("Consensus not achieved within timeout")
      context.parent ! m
      stop
  }


}
