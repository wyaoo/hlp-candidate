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

import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import akka.stream.{Attributes, FlowShape, Inlet, Outlet}
import org.hyperledger.common.LoggedHyperLedgerException

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

object AsyncStatefulStage {
  type DataProcessor[M, S] = PartialFunction[M, S => Future[(S, List[M])]]

  def noop[M, S]: DataProcessor[M, S] = { case _ => s => Future.successful((s, Nil)) }
}

abstract class AsyncStatefulStage[M, S](startingState: S)(implicit ec: ExecutionContext)
  extends GraphStage[FlowShape[M, M]] {

  import AsyncStatefulStage._

  val in = Inlet[M]("in")
  val out = Outlet[M]("out")

  def inputProcessor: AsyncStatefulStage.DataProcessor[M, S]
  lazy val liftedProcessor = inputProcessor.lift

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {

    var result = List.empty[M]
    var state = startingState
    var holdingUpstream = false

    override def preStart(): Unit = tryPull(in)

    setHandler(out, eagerTerminateOutput)
    setHandler(in, new InHandler {
      override def onPush(): Unit = {
        val elem = grab(in)
        (inputProcessor orElse noop)(elem)(state).onComplete(actionResultCallback.invoke)
      }
    })

    val actionResultCallback = getAsyncCallback[Try[(S, List[M])]] {
      case Failure(ex: LoggedHyperLedgerException) => tryPull(in)
      case Failure(ex)                             => failStage(ex)
      case Success((newState, messages)) =>
        state = newState
        result ++ messages match {
          case Nil => tryPull(in)
          case msgs :: newResult if !(isClosed(in) || hasBeenPulled(in)) =>
            result = newResult
            pushIt(msgs)
          case newResult => result = newResult
        }
    }

    private def pushIt(message: M) = {
      push(out, message)
      if (isClosed(in)) {
        completeStage()
      } else if (result.isEmpty && !hasBeenPulled(in)) {
        tryPull(in)
        holdingUpstream = false
      }
    }
  }

  override val shape = FlowShape(in, out)
}
