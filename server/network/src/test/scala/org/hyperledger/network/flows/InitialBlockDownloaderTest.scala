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

import akka.actor.ActorSystem
import akka.testkit.{ImplicitSender, TestActorRef, TestKit}
import org.hyperledger.common.{BID, Block, Hash}
import org.hyperledger.network.Implicits._
import org.hyperledger.network.flows.InitialBlockDownloader._
import org.hyperledger.network.server.InitialBlockDownloaderState.PendingDownload
import org.hyperledger.network.server.{InitialBlockDownloaderConfig, InitialBlockDownloaderState}
import org.scalatest.mock.MockitoSugar
import org.scalatest.{BeforeAndAfter, FunSpecLike, Matchers}

import scala.collection.immutable.Queue
import scala.concurrent.duration._

class InitialBlockDownloaderTest
  extends TestKit(ActorSystem("InitialBlockDownloaderTest")) with FunSpecLike with BeforeAndAfter
    with Matchers with MockitoSugar with ImplicitSender {

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

  implicit val ec = system.dispatcher

  val config = InitialBlockDownloaderConfig(missingBlocksFn, 4, 2, 2)

  val blockStore = TestActorRef(new BlockStoreWorkerStub)
  def missingBlocksFn(i: Int): List[BID] = blockStore.underlyingActor.missingBlocks.take(i)

  val downloader: TestActorRef[InitialBlockDownloader] = TestActorRef(InitialBlockDownloader.props(blockStore, config))

  before {
    blockStore.underlyingActor.storedBlocks = Nil
    blockStore.underlyingActor.missingBlocks = Nil
    downloader.underlyingActor.state = InitialBlockDownloaderState.empty
    downloader.underlyingActor.peers = Set.empty
  }

  def downloaderState = downloader.underlyingActor.state

  describe("The InitialBlockDownloader") {
    describe("if there is no missing blocks") {
      it("should tell the peers to continue as normal") {
        downloader ! RegisterPeer(testActor)
        expectMsg(ContinueAsNormal)
        downloaderState shouldBe InitialBlockDownloaderState(Set(testActor), Nil, Queue.empty, Queue.empty)
      }
    }
    describe("if there are missing blocks") {
      it ("should send jobs to peers on registration") {
        blockStore.underlyingActor.missingBlocks = List(hash1, hash2, hash3, hash4)
        downloader ! RegisterPeer(testActor)
        expectMsg(RequestBlockDownloadBatch(List(hash1, hash2)))

        downloaderState shouldBe InitialBlockDownloaderState(Set(testActor),
          List(PendingDownload(List(hash1, hash2), testActor, Nil)),
          Queue.empty,
          Queue.empty)
      }
      it("should not put blocks to blockstore out of order") {
        blockStore.underlyingActor.missingBlocks = List(hash1, hash2, hash3, hash4)

        downloader ! RegisterPeer(testActor)
        expectMsg(RequestBlockDownloadBatch(List(hash1, hash2)))

        downloader ! CompleteBlockDownload(block2)
        expectNoMsg(10 millis)

        downloader ! CompleteBlockDownload(block1)
        expectMsg(RequestBlockDownloadBatch(List(hash3, hash4)))
        blockStore.underlyingActor.storedBlocks shouldBe List(block1, block2)

        downloader ! CompleteBlockDownload(block3)
        expectNoMsg(10 millis)

        downloader ! CompleteBlockDownload(block4)
        expectMsg(ContinueAsNormal)
        blockStore.underlyingActor.storedBlocks shouldBe List(block1, block2, block3, block4)
      }
    }
  }
}
