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

import com.google.protobuf.ByteString;
import org.hyperledger.common.Block;
import org.hyperledger.common.HeaderWithSignatures;
import org.hyperledger.common.MerkleTreeNode;
import org.hyperledger.model.LevelDBStore;

import java.util.Arrays;
import java.util.List;

public class StoredBlock extends Block {

    public StoredBlock(StoredHeader header,
                       List<? extends MerkleTreeNode> transactions) {
        super(header, transactions);
    }

    public double getChainWork() {
        return getHeader().getChainWork();
    }

    public int getHeight() {
        return getHeader().getHeight();
    }

    @Override
    @SuppressWarnings("unchecked")
    public StoredHeader getHeader() {
        return (StoredHeader) super.getHeader();
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<? extends StoredTransaction> getTransactions() {
        return (List<? extends StoredTransaction>) super.getTransactions();
    }


    @SuppressWarnings("deprecation")
    public byte[] toLevelDBHeader() {
        LevelDBStore.BLOCKHEADER.Builder builder = LevelDBStore.BLOCKHEADER.newBuilder();
        builder.setVersion(getVersion());
        builder.setPreviousHash(ByteString.copyFrom(getPreviousID().unsafeGetArray()));
        builder.setMerkleRoot(ByteString.copyFrom(getMerkleRoot().unsafeGetArray()));
        builder.setCreateTime(getCreateTime());
        builder.setDifficultyTarget(getDifficultyTarget());
        builder.setNonce(getNonce());
        builder.setHeight(getHeight());
        builder.setChainWork(getChainWork());
        if (getHeader().getHeader() instanceof HeaderWithSignatures) {
            HeaderWithSignatures headerWithSignatures = (HeaderWithSignatures) getHeader().getHeader();
            builder.setInScript(ByteString.copyFrom(headerWithSignatures.getInScript().toByteArray()));
            builder.setNextScriptHash(ByteString.copyFrom(headerWithSignatures.getNextScriptHash()));
        }

        return builder.build().toByteArray();
    }

    public byte[] toLevelDBContent() {
        LevelDBStore.BLOCKCONTENT.Builder builder = LevelDBStore.BLOCKCONTENT.newBuilder();
        for (MerkleTreeNode t : getMerkleTreeNodes()) {
            byte hh[] = Arrays.copyOf(t.getID().unsafeGetArray(), 33);
            hh[32] = (byte) (t.getMerkleHeight() & 0xff);
            builder.addTxHashes(ByteString.copyFrom(hh));
        }
        return builder.build().toByteArray();
    }
}
