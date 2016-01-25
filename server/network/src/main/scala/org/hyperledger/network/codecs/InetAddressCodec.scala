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
package org.hyperledger.network.codecs

import java.net.{Inet4Address, InetAddress, InetSocketAddress, UnknownHostException}

import scodec.Attempt._
import scodec.bits._
import scodec.{codecs => C, _}

object InetAddressCodec {
  val ipv4Prefix = hex"00 00 00 00 00 00 00 00 00 00 FF FF".bits
  implicit val inetAddress: Codec[InetAddress] = C.bytes(16).exmap(
    { bytes =>
      val addressBits = if (bytes.startsWith(ipv4Prefix.bytes)) bytes.drop(12) else bytes
      try {
        successful(InetAddress.getByAddress(addressBits.toArray))
      } catch {
        case (_: UnknownHostException) => failure(Err("Invalid networkAddress length"))
      }
    },
    { case v: Inet4Address => successful(ipv4Prefix.bytes ++ ByteVector(v.getAddress))
      case v => successful(ByteVector(v.getAddress))
    }
  )

  implicit val inetSocketAddress: Codec[InetSocketAddress] = (inetAddress ~ C.uint16).exmap(
    addr => try { successful(new InetSocketAddress(addr._1, addr._2)) } catch { case e: Throwable => failure(Err(e.getMessage)) },
    isad => successful((isad.getAddress, isad.getPort))
  )
}
