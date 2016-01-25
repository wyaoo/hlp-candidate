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
package org.hyperledger.core;

import org.hyperledger.common.BID;
import org.hyperledger.common.Header;
import org.hyperledger.common.MerkleRoot;
import org.hyperledger.common.WireFormat;

import java.io.IOException;
import java.time.LocalTime;

public class ValidatedHeader implements Header {
    private final Header header;

    public ValidatedHeader(Header header) {
        this.header = header;
    }

    @Override
    public BID getID() {
        return header.getID();
    }

    @Override
    public int getVersion() {
        return header.getVersion();
    }

    @Override
    public BID getPreviousID() {
        return header.getPreviousID();
    }

    @Override
    public MerkleRoot getMerkleRoot() {
        return header.getMerkleRoot();
    }

    @Deprecated
    @Override
    public int getCreateTime() {
        return header.getCreateTime();
    }

    @Override
    public LocalTime getLocalCreateTime() {
        return header.getLocalCreateTime();
    }

    @Override
    public int getEncodedDifficulty() {
        return header.getEncodedDifficulty();
    }

    @Override
    public int getNonce() {
        return header.getNonce();
    }

    @Override
    public void toWireHeader(WireFormat.Writer writer) throws IOException {
        header.toWireHeader(writer);
    }

    @Override
    public byte[] toWireHeaderBytes() throws IOException {
        return header.toWireHeaderBytes();
    }

    public Header getHeader() {
        return header;
    }
}
