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
import akka.stream.stage._
import org.hyperledger.network.server.PeerInterface
import org.hyperledger.network.server.PeerInterface.UnexpectedMessage
import org.hyperledger.network.{ Rejection, RejectionException, Version }
import org.slf4j.LoggerFactory

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.{ Failure, Success }
import scalaz.{ -\/, \/, \/- }

object HandshakeStage {
  sealed trait HandshakeResult
  case class VersionReceived(v: Version) extends HandshakeResult
  case object VersionAcked extends HandshakeResult
  case class VersionRejected(r: Rejection) extends HandshakeResult
  case class NonHandshakeMessage[M](m: M) extends HandshakeResult

  type HandshakeHandler[M] = M => HandshakeResult

  val LOG = LoggerFactory.getLogger(classOf[HandshakeStage[_]])

  def apply[M](peer: PeerInterface[M])(handler: HandshakeStage.HandshakeHandler[M])(implicit ec: ExecutionContext) =
    new HandshakeStage[M](peer, handler)(ec)

}

class HandshakeStage[M](peer: PeerInterface[M], handler: HandshakeStage.HandshakeHandler[M])(implicit ec: ExecutionContext)
  extends GraphStage[BidiShape[M, M, M, M]] {
  import HandshakeStage._

  implicit val timeout = 1 seconds

  val protoIn = Inlet[M]("proto.in")
  val protoOut = Outlet[M]("proto.out")
  val appIn = Inlet[M]("app.in")
  val appOut = Outlet[M]("app.out")

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    private var theirVersion = Option.empty[Version]
    private var ack = false
    private var cb: AsyncCallback[(Version, List[M] \/ List[M])] = _

    override def preStart() = {
      cb = getAsyncCallback[(Version, List[M] \/ List[M])] {
        case (version, \/-(msgs)) =>
          theirVersion = Some(version)
          val sendMessages = if (peer.inbound) peer.ourVersion +: msgs else msgs
          emitMultiple(protoOut, sendMessages, checkState)
        case (version, -\/(msgs)) =>
          emitMultiple(protoOut, msgs, () => completeStage())
      }
      pull(protoIn)

      if (!peer.inbound)
        emit(protoOut, peer.ourVersion)
    }

    class HandshakeHandler extends InHandler {
      override def onPush(): Unit = {
        val elem = grab(protoIn)
        handler(elem) match {
          case VersionReceived(v) => peer.versionReceived(v).onComplete {
            case Success(msgs) => cb.invoke((v, msgs))
            case Failure(e)    => failStage(e)
          }
          case VersionAcked =>
            ack = true
            checkState()
          case VersionRejected(rejection) =>
            // TODO use specific exception for flow failures
            failStage(RejectionException(s"Version rejected by peer", rejection))
          case NonHandshakeMessage(m) =>
            peer.misbehavior(UnexpectedMessage(m))
            tryPull(protoIn)
        }
      }
      override def onUpstreamFinish(): Unit = complete(appOut)
    }

    def bidiPassAlong[Out, In <: Out](from: Inlet[In], to: Outlet[Out], msg: String) = {
      class PassAlongHandler extends InHandler with (() => Unit) {
        def apply() = tryPull(from)
        override def onPush(): Unit = emit(to, grab(from), this)
        override def onUpstreamFinish(): Unit = complete(to)
      }

      val ph = new PassAlongHandler
      if (isAvailable(from)) emit(to, grab(from), ph)
      setHandler(from, ph)
      if (!hasBeenPulled(from)) tryPull(from)
    }

    val checkState = () => if (theirVersion.isDefined && ack) {
      bidiPassAlong(appIn, protoOut, "sending to")
      bidiPassAlong(protoIn, appOut, "receiving from")
    } else tryPull(protoIn)

    // initial handlers
    setHandler(protoIn, new HandshakeHandler)
    setHandler(protoOut, ignoreTerminateOutput)

    setHandler(appOut, new OutHandler {
      override def onPull(): Unit = if (!hasBeenPulled(protoIn)) tryPull(protoIn)
      override def onDownstreamFinish(): Unit = ()
    })

    setHandler(appIn, new InHandler {
      override def onPush(): Unit = ()
      override def onUpstreamFinish(): Unit = complete(protoOut)
    })

  }

  val shape = new BidiShape(protoIn, appOut, appIn, protoOut)
}
