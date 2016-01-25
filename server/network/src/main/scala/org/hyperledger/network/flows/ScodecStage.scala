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

import akka.stream.scaladsl.{BidiFlow, Flow}
import akka.stream.stage._
import akka.util.ByteString
import org.slf4j.LoggerFactory
import scodec._
import scodec.bits._

object ScodecStage {
  import org.hyperledger.scodec.interop.akka._


  def bidi[M](codec: Codec[M]) = BidiFlow.fromFlows(
    Flow[ByteString].transform(() => decode(codec)),
    Flow[M].transform(() => encode(codec)))

  def encode[M](codec: Codec[M]) = new ScodecEncodeStage(codec)
  def decode[M](codec: Codec[M]) = new ScodecDecodeStage(codec)

  class ScodecEncodeStage[M](codec: Codec[M]) extends PushStage[M, ByteString] {
    val LOG = LoggerFactory.getLogger(classOf[ScodecEncodeStage[M]])

    override def onPush(elem: M, ctx: Context[ByteString]) = {
      LOG.debug(s"Encoding $elem".take(160))
      codec.encode(elem).fold(
        e => ctx.fail(new ScodecParserError(e.messageWithContext)),
        bits => ctx.push(bits.bytes.toByteString))
    }
  }

  class ScodecDecodeStage[M](codec: Codec[M]) extends PushPullStage[ByteString, M] {
    var buffer = ByteVector.empty
    val LOG = LoggerFactory.getLogger(classOf[ScodecDecodeStage[M]])

    override def onPush(elem: ByteString, ctx: Context[M]) = {
      LOG.debug(s"Received ${elem.length} bytes for parsing")
      buffer = buffer ++ elem.toByteVector
      doParse(ctx)
    }

    override def onPull(ctx: Context[M]): SyncDirective = doParse(ctx)

    override def onUpstreamFinish(ctx: Context[M]) = {
      if (buffer.nonEmpty) ctx.absorbTermination()
      else ctx.finish()
    }

    override def postStop(): Unit = buffer = null

    private def tryPull(ctx: Context[M]) = {
      if (ctx.isFinishing) ctx.fail(ScodecParserError("Stream finished but there was a truncated final frame in the buffer"))
      else ctx.pull()
    }

    private def emitMessage(ctx: Context[M], value: M) = {
      if (ctx.isFinishing && buffer.isEmpty) ctx.pushAndFinish(value)
      else ctx.push(value)
    }

    private def doParse(ctx: Context[M]) = {
      codec.decode(buffer.bits) match {
        case Attempt.Failure(ib: Err.InsufficientBits) =>
          LOG.debug(s"Insufficient bits ${ib.have} / ${ib.needed}")
          tryPull(ctx)
        case Attempt.Failure(err)                      => ctx.fail(new ScodecParserError(err.messageWithContext))
        case Attempt.Successful(DecodeResult(value, rest)) =>
          LOG.debug(s"Successfully parsed $value".take(160))
          LOG.debug(s"Remaining bytes: ${rest.length / 8}")
          buffer = rest.bytes
          emitMessage(ctx, value)
      }
    }
  }

  case class ScodecParserError(msg: String) extends RuntimeException(msg)
}
