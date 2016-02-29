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

import java.io.{ByteArrayOutputStream, InputStream}
import java.net.{InetSocketAddress, Socket, SocketException}
import org.hyperledger.common.{TID, BID}
import org.hyperledger.network._
import Messages._
import org.hyperledger.stresstest.helper.ServerProperties._
import scodec.bits._
import scodec.{Codec, DecodeResult}

import scala.collection.immutable.List
import scala.collection.mutable.ListBuffer
import scala.math._
import scala.util.Random


object Channel {

  private def getTimeoutMillis(payload: BlockchainMessage): Long = {
    payload match {
      case VersionMessage(_) => 1000L
      case PingMessage(_) => 3000L
      case VerackMessage() => 0L
      case GetHeadersMessage(_) => 5000L
      case InvMessage(_) => 1000L
      case PongMessage(_) => 0L
      case GetBlocksMessage(_) => 1000L
    }
  }

  def sendAndReceive(socket: Socket, codec: Codec[BlockchainMessage], message: BlockchainMessage): List[BlockchainMessage] = {
    try {
      val attempt = codec.encode(message)
      if (attempt.isSuccessful) {
        val bits = attempt.getOrElse[BitVector](BitVector.empty)
        //        printMsgs("Sending message", List(message))
        val outputStream = socket.getOutputStream
        val inputStream = socket.getInputStream

        outputStream.write(bits.toByteArray)
        outputStream.flush()
        val timeoutMillis = getTimeoutMillis(message)
        var msgs: List[BlockchainMessage] = List.empty
        do {
          val replyBytes = readAsBytes(inputStream, timeoutMillis)
          val replyBits = BitVector(replyBytes)

          if (replyBits.length != 0) {
            msgs = decodeReplyMessages(socket, codec, replyBits)
          }
        } while (msgs.length == 1 && msgs.count(m => m.isInstanceOf[PingMessage]) != 0)
        return msgs
      }
    } catch {
      case e: SocketException => println(e) // nothing to do
    }
    List.empty
  }


  private def decodeReplyMessages(socket: Socket, codec: Codec[BlockchainMessage], reply: BitVector): List[BlockchainMessage] = {
    var bits = reply
    var messages = new ListBuffer[BlockchainMessage]
    var dr: DecodeResult[BlockchainMessage] = null
    do {
      val attempt = codec.decode(bits)
      if (attempt.isSuccessful) {
        dr = attempt.getOrElse[DecodeResult[BlockchainMessage]](null)
        messages += dr.value
        bits = dr.remainder
      }
    } while (dr != null && dr.remainder.length != 0)
    messages.toList
  }

  def bytes2hex(bytes: Array[Byte], sep: Option[String] = None): String = {
    sep match {
      case None => bytes.map("%02x ".format(_)).mkString
      case _ => bytes.map("%02x ".format(_)).mkString(sep.get)
    }
  }

  private def waitForAvailableBytes(is: InputStream, timeoutMillis: Long): Unit = {
    var sleepTime = 0L
    var cumulatedSleepTime = 0L
    while (is.available <= 0 && cumulatedSleepTime < timeoutMillis) {
      if (sleepTime == 0L) {
        sleepTime = 1L
      } else {
        cumulatedSleepTime += sleepTime
        Thread.sleep(sleepTime)
        sleepTime = min(sleepTime * 2, timeoutMillis - cumulatedSleepTime)
      }
    }
  }

  private def readAsBytes(is: InputStream, timeoutMillis: Long): Array[Byte] = {
    val buffer = new ByteArrayOutputStream()
    val data = new Array[Byte](100)
    waitForAvailableBytes(is, timeoutMillis)
    var available = is.available
    while (available > 0) {
      var nRead = is.read(data, 0, data.length)
      buffer.write(data, 0, nRead)
      available -= nRead
    }
    buffer.flush()
    buffer.toByteArray
  }


  def printMsgs(clue: String, replyMessages: List[BlockchainMessage]): Unit = {
    println(clue)
    replyMessages.foreach(x => println(s"  [$x]"))
    println()
  }

  def sendGetZeroHashHeadersMessage(socket: Socket, codec: Codec[BlockchainMessage]): List[BlockchainMessage] = {
    val getZeroHeadersMessage = GetHeadersMessage(BlockDataRequest(60002L, List(BID.INVALID), BID.INVALID))
    sendAndReceive(socket, codec, getZeroHeadersMessage)
  }

  def sendGetHeadersMessage(locatorHashes: List[BID], stopHash: BID)
                           (socket: Socket, codec: Codec[BlockchainMessage]): List[BlockchainMessage] = {
    val getHeadersMessage = GetHeadersMessage(BlockDataRequest(60002L, locatorHashes, stopHash))
    sendAndReceive(socket, codec, getHeadersMessage)
  }

  def sendGetZeroHashBlocksMessage(socket: Socket, codec: Codec[BlockchainMessage]): List[BlockchainMessage] = {
    val getZeroHeadersMessage = GetBlocksMessage(BlockDataRequest(60002L, List(BID.INVALID), BID.INVALID))
    sendAndReceive(socket, codec, getZeroHeadersMessage)
  }

  def sendGetBlocksMessage(locatorHashes: List[BID], stopHash: BID)
                          (socket: Socket, codec: Codec[BlockchainMessage]): List[BlockchainMessage] = {
    val getHeadersMessage = GetHeadersMessage(BlockDataRequest(60002L, locatorHashes, stopHash))
    sendAndReceive(socket, codec, getHeadersMessage)
  }

  def sendPongMessage(socket: Socket, codec: Codec[BlockchainMessage], nonce: Long): List[BlockchainMessage] = {
    val pongMessage = PongMessage(Ping(nonce))
    sendAndReceive(socket, codec, pongMessage)
  }

  def sendPingMessage(socket: Socket, codec: Codec[BlockchainMessage]): List[BlockchainMessage] = {
    val pingMessage = PingMessage(Ping(12))
    sendAndReceive(socket, codec, pingMessage)
  }

  def sendVerackMessage(socket: Socket, codec: Codec[BlockchainMessage]): List[BlockchainMessage] = {
    val message = VerackMessage()
    sendAndReceive(socket, codec, message)
  }

  def sendInvMessage(socket: Socket, codec: Codec[BlockchainMessage], hashes: List[TID]): List[BlockchainMessage] = {
    val message = InvMessage.txs(hashes)
    sendAndReceive(socket, codec, message)
  }

  def sendVersionMessage(socket: Socket, codec: Codec[BlockchainMessage]): List[BlockchainMessage] = {
    val nonce = Random.nextLong()
    val message = Version.forNow(
      60002L,
      hex"0x0000000000000000".bits,
      NetworkAddress.forVersion(hex"0x0000000000000000".bits, new InetSocketAddress("0.0.0.0", 0)),
      NetworkAddress.forVersion(hex"0x0000000000000000".bits, new InetSocketAddress("0.0.0.0", 0)),
      nonce,
      "/Satoshi:0.7.2/",
      212672,
      relay = true)

    sendAndReceive(socket, codec, VersionMessage(message))
  }

  def connectToServer: (Socket, Codec[BlockchainMessage]) = {
    val socket = new Socket(regtestHost, regtestPort)
    socket.setKeepAlive(false)

    val codec = Messages.messageCodec(regtestMagicNumber)
    (socket, codec)
  }

  def handshakeWithServer: (Socket, Codec[BlockchainMessage], List[BlockchainMessage]) = {
    val (socket, codec) = connectToServer
    val replyMessages = sendVersionMessage(socket, codec)
    (socket, codec, replyMessages)
  }


  def getManyHashes(socket: Socket, codec: Codec[BlockchainMessage], count: Int): (List[BID], BID) = {
    val replies = sendGetZeroHashHeadersMessage(socket, codec)
    val headers = replies.collect {
      case HeadersMessage(h) => h
    }.flatten
    val allHashes = headers.map(_.getID)
    val locatorHashes = Stream.continually(allHashes).flatten.zip(0 until count).map(_._1).toList
    val stopHash = headers.last.getID
    (locatorHashes, stopHash)
  }

  def getSparseHashesInWrongOrder(socket: Socket, codec: Codec[BlockchainMessage]): (List[BID], BID) = {
    val replies = sendGetZeroHashHeadersMessage(socket, codec)
    val headers = replies.collect {
      case HeadersMessage(h) => h
    }.flatten
    val locatorHashes = List(3, 10, 8, 1, 14, 20) map headers map (_.getID)

    val stopHash = headers(40).getID
    (locatorHashes, stopHash)
  }

}
