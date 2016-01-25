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

import java.io.{InputStream, OutputStream}

import org.hyperledger.common.WireFormat.{InsufficientBytesException, Reader, Writer}
import scodec._
import scodec.bits.{BitVector, ByteVector}

object WireFormatCodec {
  object ByteVectorOutputStream {
    def apply = new ByteVectorOutputStream

    def wireWriter[M](f: Writer => Unit): BitVector = {
      val bvos = new ByteVectorOutputStream
      val writer = new Writer(bvos)
      f(writer)
      bvos.bytes.bits
    }
  }

  class ByteVectorOutputStream extends OutputStream {
    var bytes = ByteVector.empty
    override def write(b: Int): Unit = bytes = bytes :+ b.toByte

    override def write(b: Array[Byte], off: Int, len: Int): Unit = {
      if (b == null) {
        throw new NullPointerException()
      } else if ((off < 0) || (off > b.length) || (len < 0) ||
        ((off + len) > b.length) || ((off + len) < 0)) {
        throw new IndexOutOfBoundsException()
      } else if (len != 0) {
        bytes = bytes ++ ByteVector(b).drop(off).take(len)
      }
    }
  }

  object ByteVectorInputStream {
    def apply(bytes: ByteVector) = new ByteVectorInputStream(bytes)

    def wireReader[M](bits: BitVector)(f: Reader => M): (M, BitVector) = {
      val bvis = new ByteVectorInputStream(bits.bytes)
      val reader = new Reader(bvis)
      val value = f(reader)
      (value, bvis.bytes.bits)
    }

  }

  class ByteVectorInputStream(var bytes: ByteVector) extends InputStream {
    override def read(): Int = {
      if (bytes.nonEmpty) {
        val b = bytes.head
        bytes = bytes.tail
        0xFF & b
      } else -1
    }
  }

  def wireFormatCodec[T](size: SizeBound, writer: T => (Writer => Unit), reader: Reader => T): Codec[T] =
    new WireFormatCodec(size, writer, reader)
}

/**
  * Scodec adapter for [[org.hyperledger.common.WireFormat.Reader]] and
  * [[org.hyperledger.common.WireFormat.Writer]].
  */
class WireFormatCodec[T](val sizeBound: SizeBound, encoder: T => (Writer => Unit), decoder: Reader => T) extends Codec[T] {
  import WireFormatCodec._

  override def encode(value: T): Attempt[BitVector] =
    Attempt.successful(ByteVectorOutputStream.wireWriter(encoder(value)))

  override def decode(bits: BitVector): Attempt[DecodeResult[T]] = try {
    val (processed, rest) = ByteVectorInputStream.wireReader(bits)(decoder)
    Attempt.successful(DecodeResult(processed, rest))
  } catch {
    case e: InsufficientBytesException => Attempt.failure(Err.insufficientBits(e.getNeeded * 8, e.getHave * 8))
    case e: Exception => Attempt.failure(Err("Error reading wire format " + e))
  }
}
