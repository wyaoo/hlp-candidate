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

import scala.collection.mutable.ListBuffer

class MeasurementValues {

  private val execTimes: ListBuffer[Long] = ListBuffer()
  private var stats: Option[Stats] = None

  def getExecTimes: List[Long] = {
    execTimes.toList
  }

  def generateStats(expectedValueCount: Int): Stats = {
    stats match {
      case None => stats = Some(new Stats(execTimes.toList, expectedValueCount))
      case Some(s) => s
    }
    stats.get
  }

  def getStats: Stats = stats.get

  def recordExecTime[A](f: => A) = {
    val startTime = System.currentTimeMillis()
    val retVal = f
    val execTime = System.currentTimeMillis() - startTime
    this.synchronized {
      execTimes += execTime
    }
    retVal
  }

  def reset() = {
    execTimes.clear()
  }
}

class Stats(values: List[Long], expectedValueCount: Int) {
  val rawValues = values
  val min = values.min
  val max = values.max
  val count = values.length
  val sum = values.sum
  val mean = sum.toDouble / count
  val stddev = Math.sqrt(values.map(t => (t - mean) * (t - mean)).sum / count)
  val expectedCount = expectedValueCount

  override def toString: String = {
    s"(min: ${min}, max: ${max}, sum: ${sum}, mean: ${mean}, stddev: ${stddev})"
  }
}
