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

import java.util.Collections

import akka.actor.{ActorRef, ActorSystem}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{BidiFlow, Flow, Keep}
import akka.stream.testkit.scaladsl.{TestSink, TestSource}
import akka.testkit.{ImplicitSender, TestActorRef, TestKit}
import org.hyperledger.common._
import org.hyperledger.network.Implicits._
import org.hyperledger.network.Messages._
import org.hyperledger.network._
import org.hyperledger.network.flows.InitialBlockDownloader.RegisterPeer
import org.hyperledger.network.server.{InitialBlockDownloaderConfig, InitialBlockDownloaderState}
import org.scalatest.{BeforeAndAfter, FunSpecLike, Matchers}

import scala.concurrent.Future
import scala.concurrent.duration._

class InitialBlockDownloadStageTest extends TestKit(ActorSystem("InitialBlockDownloadStageTest"))
  with FunSpecLike with ImplicitSender with BeforeAndAfter with Matchers {

  case class BlockStub(hash: BID) extends Block(null, Collections.emptyList()) {
    override def getID: BID = hash
  }

  val hash1 = Hash.of(Array[Byte](1)).toBID
  val hash2 = Hash.of(Array[Byte](2)).toBID
  val hash3 = Hash.of(Array[Byte](3)).toBID
  val hash4 = Hash.of(Array[Byte](4)).toBID

  val block1 = BlockStub(hash1)
  val block2 = BlockStub(hash2)
  val block3 = BlockStub(hash3)
  val block4 = BlockStub(hash4)

  implicit val materializer = ActorMaterializer()

  implicit val ec = system.dispatcher

  val config = InitialBlockDownloaderConfig(missingBlocksFn, 4, 2, 2)

  val blockStore = TestActorRef(new BlockStoreWorkerStub)
  def missingBlocksFn(i: Int): List[BID] = {
    blockStore.underlyingActor.missingBlocks.take(i)
  }
  val downloader: TestActorRef[InitialBlockDownloader] = TestActorRef(InitialBlockDownloader.props(blockStore, config))

  class TestIBDInterface(downloader: ActorRef) extends IBDInterface {
    var addedHeaders = List.empty[Header]
    var knownBlocks = Set.empty[BID]

    override def addHeaders(headers: List[Header]): Future[Unit] = {
      addedHeaders ++= headers
      Future.successful(())
    }
    override def hasBlock(id: BID): Future[Boolean] = Future.successful(knownBlocks(id))

    override def registerForInitialBlockDownload(stageActor: ActorRef): ActorRef = {
      downloader ! RegisterPeer(stageActor)
      downloader
    }

    override def createGetHeadersRequest() =
      GetHeadersMessage(BlockDataRequest(HyperLedgerExtension.PROTOCOL_VERSION,
        missingBlocksFn(Int.MaxValue),
        BID.INVALID))
  }
  val ibdIf = new TestIBDInterface(downloader)

  before {
    blockStore.underlyingActor.storedBlocks = Nil
    blockStore.underlyingActor.missingBlocks = Nil
    downloader.underlyingActor.state = InitialBlockDownloaderState.empty
    downloader.underlyingActor.peers = Set.empty
  }

  def testflow = {
    TestSource.probe[BlockchainMessage]
      .via(BidiFlow.fromGraph(InitialBlockDownloadStage(ibdIf)).join(Flow[BlockchainMessage]))
      .toMat(TestSink.probe[BlockchainMessage])(Keep.both)
      .run
  }

  describe("InitialBlockDownloadStage") {
    describe("when there is no missing block on start") {
      it("should let messages through") {
        val (pub, sub) = testflow

        sub.request(n = 3)
        pub.sendNext(PingMessage(Ping(1))).sendNext(PingMessage(Ping(2)))
        val resp = sub.expectNextN(2)
        resp shouldBe Seq(PingMessage(Ping(1)), PingMessage(Ping(2)))
      }
    }
    describe("when there are missing blocks") {
      it("it should forward block messages to IBD until no more missing blocks") {
        blockStore.underlyingActor.missingBlocks = List(hash1, hash2, hash3, hash4)
        val (pub, sub) = testflow

        sub.request(50)
        sub.expectNext(GetDataMessage.blocks(List(hash1, hash2)))
        pub.sendNext(InvMessage.blocks(List(hash1))).sendNext(BlockMessage(block1))

        sub.expectNoMsg(10 millis)

        pub.sendNext(BlockMessage(block2)).sendNext(InvMessage.blocks(List(hash4)))
        sub.expectNext(GetDataMessage.blocks(List(hash3, hash4)))

        pub.sendNext(BlockMessage(block3)).sendNext(BlockMessage(block4))
        sub.expectNoMsg(10 millis)

        pub.sendNext(PingMessage(Ping(4)))
        sub.expectNext(10 millis, PingMessage(Ping(4)))
      }
    }
  }

}
