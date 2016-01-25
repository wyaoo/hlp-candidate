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
package org.hyperledger.stresstest

import java.net.Socket
import org.hyperledger.common.WireFormat.Reader
import org.hyperledger.common.{BID, BitcoinHeader, Hash}
import org.hyperledger.network.Messages
import org.hyperledger.stresstest.helper.{MeasurementRunner, Stats, Channel}
import Channel._
import org.scalatest.{FeatureSpec, GivenWhenThen}
import scodec.Codec

class Headers extends FeatureSpec with GivenWhenThen {

  def printExecTimes(stats: Stats) = {
    stats.rawValues.foreach(e => println(s"time: ${e}"))
    println(s"count: ${stats.count}, min: ${stats.min}, max: ${stats.max}, mean: ${stats.mean}, stddev: ${stats.stddev}")
  }

  feature("Asking for headers") {
    scenario("Requesting zero headers many times") {
      Given("a measurement runner")
      val runner = new MeasurementRunner()

      val threadCount = 20
      val iterations = 100
      val timeoutMillis = 30000
      When(s"requesting zero hash headers in ${threadCount} threads times ${iterations} iterations with ${timeoutMillis} milliseconds timeout")
      val stats = runner.connectAndExecute(threadCount, iterations, timeoutMillis, sendGetZeroHashHeadersMessage)

      val expectedMaxReplyTimeMillis = 2000
      Then(s"the max reply time is less then ${expectedMaxReplyTimeMillis} milliseconds (${stats})")
      assert(stats.max < expectedMaxReplyTimeMillis, s"Maximum reply time was ${stats.max} milliseconds but expected less than ${expectedMaxReplyTimeMillis} milliseconds")
      Then(s"all the ${threadCount * iterations} requests were executed")
      assert(stats.count == stats.expectedCount, s"Only ${stats.count} out of ${stats.expectedCount} requests were executed within the timeout. Please rerun the test")
    }
  }


  scenario("Requesting headers in wrong order") {
    Given("a request with headers in wrong order")
    val (socket, codec, _) = handshakeWithServer
    val (locatorHashes: List[BID], stopHash: BID) = getSparseHashesInWrongOrder(socket, codec)

    Given("a measurement runner")
    val runner = new MeasurementRunner()

    val threadCount = 20
    val iterations = 100
    val timeoutMillis = 30000
    When(s"requesting headers in ${threadCount} threads times ${iterations} iterations with ${timeoutMillis} milliseconds timeout")
    val stats = runner.connectAndExecute(threadCount, iterations, timeoutMillis, sendGetHeadersMessage(locatorHashes, stopHash))

    val expectedMaxReplyTimeMillis = 2000
    Then(s"the max reply time is less then ${expectedMaxReplyTimeMillis} milliseconds (${stats}))")
    assert(stats.max < expectedMaxReplyTimeMillis, s"Maximum reply time was ${stats.max} milliseconds but expected less than ${expectedMaxReplyTimeMillis} milliseconds")
    Then(s"all the ${threadCount * iterations} requests were executed")
    assert(stats.count == stats.expectedCount, s"Only ${stats.count} out of ${stats.expectedCount} requests were executed within the timeout. Please rerun the test")
  }

  scenario("Requesting headers with huge request") {
    Given("an oversized request with plenty of hashes")
    val (socket, codec, _) = handshakeWithServer
    // getHeaders message with 65535 hashes would exceed the 2MB message size limit
    // with 65534 hashes its size is 2097127 which is 25 bytes lesss than 2MB
    val (locatorHashes, stopHash) = getManyHashes(socket, codec, 65534)
    val replies = sendGetHeadersMessage(locatorHashes, stopHash)(socket, codec)

    Given("a measurement runner")
    val runner = new MeasurementRunner()

    val threadCount = 20
    val iterations = 10
    val timeoutMillis = 50000
    When(s"requesting headers in ${threadCount} threads times ${iterations} iterations with ${timeoutMillis} milliseconds timeout")
    val stats = runner.connectAndExecute(threadCount, iterations, timeoutMillis, sendGetHeadersMessage(locatorHashes, stopHash))

    val expectedMaxReplyTimeMillis = 2000
    Then(s"the max reply time is less then ${expectedMaxReplyTimeMillis} milliseconds (${stats})")
    assert(stats.max < expectedMaxReplyTimeMillis, s"Maximum reply time was ${stats.max} milliseconds but expected less than ${expectedMaxReplyTimeMillis} milliseconds")
    Then(s"all the ${threadCount * iterations} requests were executed")
    assert(stats.count == stats.expectedCount, s"Only ${stats.count} out of ${stats.expectedCount} requests were executed within the timeout. Please rerun the test")
  }
}
