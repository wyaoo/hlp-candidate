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
package org.hyperledger.network.server

import java.util.Collections

import org.hyperledger.common._
import org.scalatest.{ FunSpec, Matchers }

class BlockDownloadStateSpec extends FunSpec with Matchers {

  def blockstub(nonce: Int, previousID: BID) = new Block(new BitcoinHeader(0, previousID, MerkleRoot.INVALID, 0, 0, nonce), Collections.emptyList())

  val ZERO_BID = new BID(Hash.INVALID)
  // block1 -> block2 -> block3 -> block6 <--- connects to a stored block
  // block4 ----^                    ^
  // block5 -------------------------|
  val block6 = blockstub(6, ZERO_BID)
  val hash6 = block6.getID
  val block5 = blockstub(5, hash6)
  val hash5 = block5.getID

  val block3 = blockstub(3, hash6)
  val hash3 = block3.getID
  val block2 = blockstub(2, hash3)
  val hash2 = block2.getID

  val block4 = blockstub(4, hash2)
  val hash4 = block4.getID

  val block1 = blockstub(1, hash2)
  val hash1 = block1.getID

  describe("BlockDownloadSate") {
    describe("if it is empty") {
      val s = BlockDownloadState.empty
      it("should have no effect on connected blocks") {
        val (newS, toStore) = s.connectedBlockReceived(block1)
        toStore shouldBe List(block1)
        newS.pendingBlocks shouldBe 'empty
      }
      it("should add the first non-connected block") {
        val (newS, downloadPrev) = s.blockReceived(block1)
        newS.pendingBlocks shouldBe Set(hash1)
        newS.dependencies shouldBe Map(hash2 -> List(block1))
        downloadPrev shouldBe true
      }
    }
    describe("it has block1") {
      val s = BlockDownloadState(Map(hash2 -> List(block1)), Set(hash1))
      it("should add its previous block to the list") {
        val (newS, downloadPrev) = s.blockReceived(block2)
        newS.pendingBlocks shouldBe Set(hash1, hash2)
        newS.dependencies shouldBe Map(hash2 -> List(block1), hash3 -> List(block2))
        downloadPrev shouldBe true
      }
      it("should add a new pending list for unrelated blocks") {
        val (newS, downloadPrev) = s.blockReceived(block5)
        newS.pendingBlocks shouldBe Set(hash1, hash5)
        newS.dependencies shouldBe Map(hash2 -> List(block1), hash6 -> List(block5))
        downloadPrev shouldBe true
      }
      it("should not need to download block4's previous since it's block1's previous and is already downloading") {
        val (newS, downloadPrev) = s.blockReceived(block4)
        newS.pendingBlocks shouldBe Set(hash1, hash4)
        newS.dependencies shouldBe Map(hash2 -> List(block1, block4))
        downloadPrev should not be true
      }
    }

    describe("it has block1 and block4") {
      val s = BlockDownloadState(Map(hash2 -> List(block1, block4)), Set(hash1, hash4))
      it("should add block2 and unify the tree") {
        val (newS, downloadPrev) = s.blockReceived(block2)
        newS.pendingBlocks shouldBe Set(hash1, hash2, hash4)
        newS.dependencies shouldBe Map(hash2 -> List(block1, block4), hash3 -> List(block2))
        downloadPrev shouldBe true
      }
    }

    // block1 -> block2 -> block3 -> block6 <--- connects to a stored block
    // block4 ----^                    ^
    // block5 -------------------------|
    describe("it has block1, block2 and block3") {
      val s = BlockDownloadState(
        Map(hash2 -> List(block1), hash3 -> List(block2), hash6 -> List(block3)),
        Set(hash1, hash2, hash3))
      it("should not need to download block4's previous since it's already downloaded") {
        val (newS, downloadPrev) = s.blockReceived(block4)
        newS.pendingBlocks shouldBe Set(hash1, hash2, hash3, hash4)
        newS.dependencies shouldBe Map(hash2 -> List(block1, block4), hash3 -> List(block2), hash6 -> List(block3))
        downloadPrev should not be true
      }

      it("should be empty after receiving block6") {
        val (newS, toStore) = s.connectedBlockReceived(block6)
        newS shouldBe BlockDownloadState.empty
        toStore shouldBe block6 :: block3 :: block2 :: block1 :: Nil
      }
    }
    describe("it hash block 1,2,3 and 4") {
      val s = BlockDownloadState(
        Map(hash2 -> List(block1, block4), hash3 -> List(block2), hash6 -> List(block3)),
        Set(hash1, hash2, hash3, hash4))
      it("should be empty after receiving block6") {
        val (newS, toStore) = s.connectedBlockReceived(block6)
        newS shouldBe BlockDownloadState.empty
        toStore shouldBe List(block6, block3, block2, block1, block4)
      }
    }
  }

}
