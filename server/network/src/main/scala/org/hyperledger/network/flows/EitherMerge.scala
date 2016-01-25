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

import akka.stream.FanInShape.Init
import akka.stream.stage.{InHandler, GraphStageLogic, GraphStage}
import akka.stream._

import scala.annotation.unchecked.uncheckedVariance

class EitherFanInShape[-T0, -T1, +O](_init: FanInShape.Init[O]) extends FanInShape[O](_init) {
  def this(name: String) = this(FanInShape.Name[O](name))
  def this(in0: Inlet[T0], in1: Inlet[T1], out: Outlet[O]) = this(FanInShape.Ports(out, in0 :: in1 :: Nil))
  override protected def construct(init: Init[O @uncheckedVariance]): FanInShape[O] = new EitherFanInShape(init)
  override def deepCopy(): EitherFanInShape[T0, T1, O] = super.deepCopy().asInstanceOf[EitherFanInShape[T0, T1, O]]

  val left: Inlet[T0 @uncheckedVariance] = newInlet[T0]("left")
  val right: Inlet[T1 @uncheckedVariance] = newInlet[T1]("right")
}

class EitherMerge[L, R] extends GraphStage[EitherFanInShape[L, R, Either[L, R]]] {
  val left = Inlet[L]("left")
  val right = Inlet[R]("right")
  val out = Outlet[Either[L, R]]("out")
  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {

    override def preStart(): Unit = {
      tryPull(left)
      tryPull(right)
    }

    setHandler(left, new InHandler {
      override def onPush(): Unit = {
        val elem = grab(left)
        emit(shape.out, Left(elem), () => tryPull(left))
      }
    })
    setHandler(right, new InHandler {
      override def onPush(): Unit = {
        val elem = grab(right)
        emit(shape.out, Right(elem), () => tryPull(right))
      }
    })
    setHandler(out, eagerTerminateOutput)
  }

  override def shape = new EitherFanInShape(left, right, out)
}

