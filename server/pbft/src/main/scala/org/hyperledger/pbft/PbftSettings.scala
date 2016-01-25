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

import java.net.{InetAddress, InetSocketAddress}

import org.hyperledger.common.{ PrivateKey, ByteUtils, PublicKey }
import com.typesafe.config.{ ConfigException, Config }
import com.typesafe.config.ConfigException.BadValue
import scala.concurrent.duration._

import scala.collection.JavaConverters._

object PbftSettings {
  case class NodeConfig(address: InetSocketAddress, publicKey: PublicKey)

  def parseAddress(conf: Config, path: String) = {
    val address = conf.getString(path).split(':')
    if (address.length != 2) throw new BadValue(conf.origin(), path, "Invalid address")

    new InetSocketAddress(InetAddress.getByName(address(0)), Integer.valueOf(address(1)))
  }

  def fromConfig(config: Config) = {
    val conf = config.getConfig("hyperledger.pbft")

    val nodesConfig = conf.getConfigList("nodes")
    if (nodesConfig.size < 2) throw new BadValue(conf.origin(), "nodes", "At least 2 nodes must be configured")

    val nodes = nodesConfig.asScala.map { nodeConf =>
      try {
        val inetSocketAddress = parseAddress(nodeConf, "address")
        val keyBytes = ByteUtils.fromHex(nodeConf.getString("publicKey"))
        // TODO verify key bytes

        NodeConfig(inetSocketAddress, new PublicKey(keyBytes, true))
      } catch {
        case e: ConfigException => throw e
        case e: Exception       => throw new BadValue(conf.origin(), "nodes.address", "Invalid address", e)
      }
    }.toList

    val privateKey = try PrivateKey.parseWIF(conf.getString("privateKey")) catch {
      case e: ConfigException => throw e
      case e: Exception       => throw new BadValue(conf.origin(), "privateKey", "Invalid private key", e)
    }

    val bindAddress = parseAddress(conf, "bindAddress")
    val protocolTimeoutSec = conf.getInt("protocolTimeoutSeconds")
    val blockFrequency = conf.getInt("blockFrequencySeconds")


    PbftSettings(nodes, privateKey, bindAddress, protocolTimeoutSec, blockFrequency)
  }

}

import scalaz._
import Scalaz._

case class PbftSettings(nodes: List[PbftSettings.NodeConfig],
                        privateKey: PrivateKey,
                        bindAddress: InetSocketAddress,
                        protocolTimeoutSec: Int,
                        blockFrequencySec: Int) {

  val ourPublicKey = privateKey.getPublic

  lazy val ourNodeId = nodes.indexWhere(_.publicKey == ourPublicKey)

  nodes.find(_.publicKey == ourPublicKey).getOrElse(throw new BadValue(
    "hyperledger.pbft.privateKey",
    "No configured node found for the specified private key"))

  if (!nodes.unzip(c => c.address -> c.publicKey)
    .bimap(_.distinct.size == nodes.size, _.distinct.size == nodes.size)
    .fold(_ && _))
    throw new BadValue("hyperledger.pbft.nodes", "Duplicate public key or node address")


  lazy val otherNodes = nodes.filterNot(_.publicKey == ourPublicKey)

  val protocolTimeout = protocolTimeoutSec seconds
  val blockFrequency = blockFrequencySec seconds
}
