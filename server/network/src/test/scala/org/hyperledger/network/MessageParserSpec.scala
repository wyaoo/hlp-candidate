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

import java.net.InetSocketAddress

import akka.actor._
import akka.stream._
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import akka.testkit._
import akka.util.ByteString
import org.hyperledger.network.Messages.VersionMessage
import org.hyperledger.network.flows.MessageParser
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpec}
import scodec.bits._

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.Random

class MessageParserSpec extends WordSpec with TestKitBase with ImplicitSender
  with Matchers with BeforeAndAfterAll {
  implicit lazy val system = ActorSystem()

  def rechunk(bytes: ByteString): List[ByteString] = scalaz.DList.unfoldr[ByteString, ByteString](bytes, bs => {
    if (bs.isEmpty) None
    else {
      val point = Random.nextInt(bs.size) + 1
      Some((bs.take(point), bs.drop(point)))
    }
  }).toList

  val settings = ActorMaterializerSettings(system)
  implicit val materializer = ActorMaterializer(settings)

  "MessageParse" must {
    val versionMessageBits = hex"""
           f9 be b4 d9
           76 65 72 73 69 6f 6e 00 00 00 00 00
           65 00 00 00
           f6 7f f1 1a
           62 ea 00 00
           01 00 00 00 00 00 00 00
           11 b2 d0 50 00 00 00 00
           01 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 ff ff 00 00 00 00 00 00
           01 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 ff ff 00 00 00 00 00 00
           3b 2e b3 5d 8c e6 17 65
           0f 2f 53 61 74 6f 73 68 69 3a 30 2e 37 2e 32 2f
           c0 3e 03 00
           01
         """.bits

    "parse messages" in {
      val flow = MessageParser.messagePayloadDecoder(0xd9b4bef9)
      val result = Await.result(
        Source(rechunk(ByteString(versionMessageBits.toByteArray)))
          .via(flow)
          .runWith(Sink.head), 3.seconds)

      result shouldBe VersionMessage(Version(
        60002L,
        hex"0x0100000000000000".bits,
        1355854353L,
        NetworkAddress.forVersion(hex"0x0100000000000000".bits, new InetSocketAddress("0.0.0.0", 0)),
        NetworkAddress.forVersion(hex"0x0100000000000000".bits, new InetSocketAddress("0.0.0.0", 0)),
        7284544412836900411L,
        "/Satoshi:0.7.2/",
        212672,
        relay = true))
    }
  }

}
