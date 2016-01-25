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
package org.hyperledger.network

import java.net.{InetAddress, InetSocketAddress}

import org.hyperledger.network.Version.{NETWORK_NODE, SIMPLE_NODE}
import org.scalatest.{FunSuite, Matchers}
import scodec.bits.BitVector

/**
 *
 */
class ProtocolTests extends FunSuite with Matchers {

  val LOCAL_ADDR = new InetSocketAddress(InetAddress.getLocalHost, 8333)

  def createVersion(services: BitVector) = Version.forNow(1,
    services,
    NetworkAddress.forVersion(services, LOCAL_ADDR),
    NetworkAddress.forVersion(services, LOCAL_ADDR),
    123, "Test", 0, relay = false)

  test("VersionMessage#supportsServices") {
    createVersion(SIMPLE_NODE).supportsServices(SIMPLE_NODE) shouldBe true
    createVersion(SIMPLE_NODE).supportsServices(NETWORK_NODE) shouldBe false

    createVersion(NETWORK_NODE).supportsServices(SIMPLE_NODE) shouldBe true
    createVersion(NETWORK_NODE).supportsServices(NETWORK_NODE) shouldBe true
  }

}
