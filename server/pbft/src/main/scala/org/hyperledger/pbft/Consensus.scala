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

import akka.actor._
import org.hyperledger.common.{ BID, Block }
import org.hyperledger.pbft.BlockHandler.{ Store, SendCommit, SendPrepare }
import org.hyperledger.pbft.Consensus._

object Consensus {
  sealed trait ConsensusState
  case object WaitForPrePrepare extends ConsensusState
  case object WaitForPrepare extends ConsensusState
  case object WaitForCommit extends ConsensusState
  case object Done extends ConsensusState

  // format: OFF
  case class ConsensusData(block: Option[Block],
                           prepares: Set[Prepare],
                           commits: Set[Commit])
  // format: ON

  sealed trait ConsensusMessage
  case class Timeout() extends ConsensusMessage
  case class StartConsensus(block: Block) extends ConsensusMessage
  case class Stored() extends ConsensusMessage
  case class ConsensusBye(id: BID) extends ConsensusMessage

  def props(f: Int, blockId: BID, parent: ActorRef) = Props(new Consensus(f, blockId, parent))
}

class Consensus(f: Int, blockId: BID, parent: ActorRef) extends LoggingFSM[ConsensusState, ConsensusData] {

  val settings = PbftExtension(context.system).settings

  setTimer("timeout", Timeout(), settings.protocolTimeout)

  startWith(WaitForPrePrepare, ConsensusData(None, Set.empty, Set.empty))

  when(WaitForPrePrepare) {
    case Event(PrePrepare(_, viewSeq, block, _), data) =>
      sender ! SendPrepare(block.getHeader)
      goto(WaitForPrepare) using data.copy(block = Some(block))

    case Event(StartConsensus(block), data) =>
      goto(WaitForPrepare) using data.copy(block = Some(block))

    case Event(m: Prepare, data) =>
      stay() using data.copy(prepares = data.prepares + m)

    case Event(m: Commit, data) =>
      stay() using data.copy(commits = data.commits + m)
  }

  when(WaitForPrepare) {
    case Event(m: Prepare, data) =>
      val prepares = data.prepares + m
      if (prepares.size >= 2 * f) {
        sender ! SendCommit(data.block.get.getHeader)
        goto(WaitForCommit) using data.copy(prepares = prepares)
      } else {
        stay() using data.copy(prepares = prepares)
      }

    case Event(m: Commit, data) =>
      stay() using data.copy(commits = data.commits + m)
  }

  when(WaitForCommit) {
    case Event(m: Commit, data) =>
      val commits = data.commits + m
      if (commits.size > 2 * f) {
        sender ! Store(data.block.get, commits.toList)
        goto(Done)
      } else {
        stay() using data.copy(commits = data.commits + m)
      }

    case Event(m: Prepare, _) =>
      log.debug("Prepare arrived after enough received")
      stay()
  }

  when(Done) {
    case Event(Stored(), data) =>
      parent ! ConsensusBye(blockId)
      stop()

    case Event(x @ (_: Prepare | _: Commit), data) =>
      log.debug(s"Arrived after done: $x")
      stay()
  }

  whenUnhandled {
    case Event(Timeout(), _) =>
      parent ! ConsensusBye(blockId)
      stop()
  }

}
