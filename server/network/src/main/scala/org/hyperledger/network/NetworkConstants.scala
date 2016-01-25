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

/**
 *
 */
trait NetworkConstants {
  def defaultPort: Int
  def p2pMagic: Int
  def maxNBit: Int
}

object Testnet3BitcoinNetwork extends NetworkConstants {
  val defaultPort = 18333
  val p2pMagic = 0x0709110b
  val maxNBit = 0x1d00ffff
}

object ProductionBitcoinNetwork extends NetworkConstants {
  val defaultPort = 8333
  val p2pMagic = 0xd9b4bef9
  val maxNBit = 0x1d00ffff

}

object RegtestBitcoinNetwork extends NetworkConstants {
  val defaultPort = 18444
  val p2pMagic = 0xdab5bffa
  val maxNBit = 0x207fffff

}

object SignedRegtestBitcoinNetwork extends NetworkConstants {
  val defaultPort = 19555
  val p2pMagic = 0xf353c1d8
  val maxNBit = 0x207fffff

}
