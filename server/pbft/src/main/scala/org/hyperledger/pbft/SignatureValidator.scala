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

import org.hyperledger.common.PublicKey
import scodec.bits.BitVector
import scodec.{ Attempt, Err }

import scalaz.Scalaz._
import scalaz._

object SignatureValidator {

  type MessageOrErr[M] = \/[(Err, M), M]

  // format: OFF
  def valid[M](getHash: M => Attempt[BitVector],
               getSig: M => Array[Byte])
              (m: M, k: PublicKey): MessageOrErr[M] = getHash(m) match {
    // format: ON
    case Attempt.Failure(err) =>
      \/.left((err, m))
    case Attempt.Successful(hash) =>
      k.verify(hash.toByteArray, getSig(m)) match {
        case true  => \/.right(m)
        case false => \/.left((Err("Incorrect signature"), m))
      }
  }

  def pkViewChange(settings: PbftSettings, m: ViewChange): PublicKey = settings.nodes(m.node).publicKey
  def getViewChangeHash(m: ViewChange): Attempt[BitVector] = m.getHash
  def getViewChangeSig(m: ViewChange): Array[Byte] = m.signature.toArray

  def pkCommit(settings: PbftSettings, m: Commit): PublicKey = settings.nodes(m.node).publicKey
  def getCommitHash(m: Commit): Attempt[BitVector] = m.getHash
  def getCommitSig(m: Commit): Array[Byte] = m.signature.toArray

  // format: OFF
  def validMessages[M](messages: List[M],
                       validator: (M, PublicKey) => MessageOrErr[M],
                       pk: (PbftSettings, M) => PublicKey): Reader[PbftSettings, List[MessageOrErr[M]]] = Reader(settings => {
    // format: ON
    messages.map { m => validator(m, pk(settings, m)) }
  })

  def validateViewChanges(messages: List[ViewChange]): Reader[PbftSettings, List[MessageOrErr[ViewChange]]] =
    validMessages(messages, valid(getViewChangeHash, getViewChangeSig), pkViewChange)

  def validateCommits(messages: List[Commit]): Reader[PbftSettings, List[MessageOrErr[Commit]]] =
    validMessages(messages, valid(getCommitHash, getCommitSig), pkCommit)

  def split[M](messages: List[MessageOrErr[M]]): (List[(Err, M)], List[M]) = messages.separate

}
