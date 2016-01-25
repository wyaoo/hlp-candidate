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
package org.hyperledger.stresstest.helper

import java.net.Socket
import java.util.concurrent.{Executors, TimeUnit}
import org.hyperledger.network.Messages
import Messages.BlockchainMessage
import org.hyperledger.network.Messages
import org.hyperledger.stresstest.helper.Channel.handshakeWithServer
import scodec.Codec

class MeasurementRunner {

  private val recorder = new MeasurementValues()

  def connectAndExecute(threadCount: Int, iterationCount: Int, timeoutMillis: Long, f: (Socket, Codec[BlockchainMessage]) => List[BlockchainMessage]): Stats = {
    val threadPool = Executors.newFixedThreadPool(threadCount)
    for (_ <- 1 to threadCount) {
      threadPool.execute(
        new Runnable {
          def run() {
            val (socket: Socket, codec: Codec[BlockchainMessage], _) = handshakeWithServer
            for (_ <- 1 to iterationCount) {
              recorder.recordExecTime(f(socket, codec))
            }
            socket.close()
          }
        }
      )
    }
    threadPool.shutdown()
    threadPool.awaitTermination(timeoutMillis, TimeUnit.MILLISECONDS)
    recorder.generateStats(threadCount * iterationCount)
  }
}

