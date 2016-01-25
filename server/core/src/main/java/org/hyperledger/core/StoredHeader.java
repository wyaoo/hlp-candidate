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

import com.google.protobuf.InvalidProtocolBufferException;
import org.hyperledger.common.*;
import org.hyperledger.model.LevelDBStore;

public class StoredHeader extends ValidatedHeader {
    private double chainWork;
    private int height;

    public StoredHeader(Header h, double chainWork, int height) {
        super(h);
        this.chainWork = chainWork;
        this.height = height;
    }

    public double getChainWork() {
        return chainWork;
    }

    public void setChainWork(double chainWork) {
        this.chainWork = chainWork;
    }

    public int getHeight() {
        return height;
    }

    public void setHeight(int height) {
        this.height = height;
    }

    public static StoredHeader fromLevelDB(byte[] data) throws HyperLedgerException {
        LevelDBStore.BLOCKHEADER p;
        try {
            p = LevelDBStore.BLOCKHEADER.parseFrom(data);
            BitcoinHeader.Builder builder;
            if (p.hasInScript() && p.hasNextScriptHash()) {
                builder = HeaderWithSignatures.create();
                ((HeaderWithSignatures.Builder) builder).inScript(p.getInScript().toByteArray()).nextScriptHash(p.getNextScriptHash().toByteArray());
            } else {
                builder = BitcoinHeader.create();
            }
            Header b = builder
                    .version(p.getVersion())
                    .previousID(new BID(p.getPreviousHash().toByteArray()))
                    .merkleRoot(MerkleRoot.createFromSafeArray(p.getMerkleRoot().toByteArray()))
                    .createTime(p.getCreateTime())
                    .difficultyTarget(p.getDifficultyTarget())
                    .nonce(p.getNonce()).build();
            return new StoredHeader(b, p.getChainWork(), p.getHeight());
        } catch (InvalidProtocolBufferException e) {
            throw new HyperLedgerException(e);
        }
    }

    public static int compareHeaders(StoredHeader a, StoredHeader b) {
        return Double.compare(a.getChainWork(), b.getChainWork());
    }


}
