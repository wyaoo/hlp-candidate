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

import com.typesafe.config.ConfigException.BadValue
import com.typesafe.config.ConfigFactory
import org.scalatest.{Matchers, WordSpec}

class PbftSettingsTest extends WordSpec with Matchers {
  /*
  k1: L3i1bb4tPemhifqwqa9s5hriARpP4s1edkLcJ6xrsqLnnr3jGLcL
  K1: 0279f6a3d862c7b367524e7d0875fb85314b4f960ceeb2526a2ea0e8585dc8ff69

  K2: 0385ea1509ab2dfbb375ea5e41819ebaf49127df9fcec70a93b84b0aa57f6b4642
   */

  def asConf(s: String) = ConfigFactory.parseString(
    s"""hyperledger{pbft{
       |bindAddress: "localhost:9999",
       |protocolTimeoutSeconds: 60,
       |blockFrequencySeconds: 10
       |$s
       |}}""".stripMargin)

  "The PbftSettings" when {
    "no or too few nodes set" should {
      "throw BadValue exception" in {
        intercept[BadValue] {
          PbftSettings.fromConfig(asConf("nodes: []"))
        }
        intercept[BadValue] {
          PbftSettings.fromConfig(asConf(
            """
              |nodes: [
              |    {addressHost: "localhost", addressPort: "1234", publicKey: "0279f6a3d862c7b367524e7d0875fb85314b4f960ceeb2526a2ea0e8585dc8ff69"}
              |]
              |privateKey: "L3i1bb4tPemhifqwqa9s5hriARpP4s1edkLcJ6xrsqLnnr3jGLcL"
            """.stripMargin))
        }
      }
    }

    "no private key set" should {
      "enable client mode" in {
        val settings = PbftSettings.fromConfig(asConf(
          """
            |nodes: [
            |    {address: "localhost:1234", publicKey: "0279f6a3d862c7b367524e7d0875fb85314b4f960ceeb2526a2ea0e8585dc8ff69"}
            |    {address: "localhost:2345", publicKey: "0385ea1509ab2dfbb375ea5e41819ebaf49127df9fcec70a93b84b0aa57f6b4642"}
            |]
          """.stripMargin))
        settings.clientMode shouldBe true
      }
    }

    "duplicate address is set" should {
      "throw BadValue exception" in {
        intercept[BadValue] {
          PbftSettings.fromConfig(asConf(
            """
              |nodes: [
              |    {address: "localhost:1234", publicKey: "0279f6a3d862c7b367524e7d0875fb85314b4f960ceeb2526a2ea0e8585dc8ff69"}
              |    {address: "localhost:1234", publicKey: "0385ea1509ab2dfbb375ea5e41819ebaf49127df9fcec70a93b84b0aa57f6b4642"}
              |]
              |privateKey: "L3i1bb4tPemhifqwqa9s5hriARpP4s1edkLcJ6xrsqLnnr3jGLcL"
            """.stripMargin))
        }
      }
    }

    "duplicate key is set" should {
      "throw BadValue exception" in {
        intercept[BadValue] {
          PbftSettings.fromConfig(asConf(
            """
              |nodes: [
              |    {address: "localhost:1234", publicKey: "0279f6a3d862c7b367524e7d0875fb85314b4f960ceeb2526a2ea0e8585dc8ff69"}
              |    {address: "localhost:9876", publicKey: "0279f6a3d862c7b367524e7d0875fb85314b4f960ceeb2526a2ea0e8585dc8ff69"}
              |]
              |privateKey: "L3i1bb4tPemhifqwqa9s5hriARpP4s1edkLcJ6xrsqLnnr3jGLcL"
            """.stripMargin))
        }
      }
    }

    "minimal configuration set" should {
      "create the settings object" in {
        val settings = PbftSettings.fromConfig(asConf(
          """
            |nodes: [
            |    {address: "localhost:1234", publicKey: "0279f6a3d862c7b367524e7d0875fb85314b4f960ceeb2526a2ea0e8585dc8ff69"}
            |    {address: "localhost:2345", publicKey: "0385ea1509ab2dfbb375ea5e41819ebaf49127df9fcec70a93b84b0aa57f6b4642"}
            |]
            |privateKey: "L3i1bb4tPemhifqwqa9s5hriARpP4s1edkLcJ6xrsqLnnr3jGLcL"
          """.stripMargin))
        settings.nodes.size shouldBe 2
        settings.otherNodes.size shouldBe 1
        settings.clientMode shouldBe false
      }
    }
  }

}
