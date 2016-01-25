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

import akka.stream._
import akka.stream.FanOutShape._
import akka.stream.stage.{InHandler, GraphStageLogic, GraphStage}
import org.hyperledger.network.server.InitialBlockDownload

object IBDSwitch {
  type SWITCHER[T] = Either[ControlMessage, T]

  def apply[T]() = new IBDSwitch[T]
}
import IBDSwitch.SWITCHER

class IBDSwitchShape[T](_init: Init[SWITCHER[T]] = Name[SWITCHER[T]]("IBDSwitch"))
  extends FanOutShape[SWITCHER[T]](_init) {

  val ibd = newOutlet[SWITCHER[T]]("ibd")
  val normal = newOutlet[SWITCHER[T]]("normal")

  override protected def construct(init: Init[SWITCHER[T]]) = new IBDSwitchShape[T](init)
}

class IBDSwitch[T] extends GraphStage[IBDSwitchShape[T]] {
  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {

    override def preStart(): Unit = tryPull(shape.in)

    setHandler(shape.in, new NormalHandler)
    setHandler(shape.ibd, eagerTerminateOutput)
    setHandler(shape.normal, eagerTerminateOutput)

    class NormalHandler extends InHandler with (() => Unit) {
      override def apply(): Unit = tryPull(shape.in)
      override def onPush(): Unit = {
        grab(shape.in) match {
          case Left(ServerStateTransition(newState)) =>
            if (newState == InitialBlockDownload) {
              setHandler(shape.in, new IBDHandler)
              tryPull(shape.in)
            } else tryPull(shape.in)
          case m => emit(shape.normal, m, this)
        }
      }
    }

    class IBDHandler extends InHandler with (() => Unit) {
      override def apply(): Unit = tryPull(shape.in)
      override def onPush(): Unit = {
        grab(shape.in) match {
          case m @ Left(ServerStateTransition(newState)) =>
            if (newState != InitialBlockDownload)
              emit(shape.normal, m, () => {
                setHandler(shape.in, new NormalHandler)
                tryPull(shape.in)
              })
            else tryPull(shape.in)
          case m => emit(shape.ibd, m, this)
        }
      }
    }
  }

  override val shape: IBDSwitchShape[T] = new IBDSwitchShape[T]()
}

