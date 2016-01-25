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
import com.google.protobuf.InvalidProtocolBufferException;
import org.hyperledger.common.*;
import org.hyperledger.common.color.Color;
import org.hyperledger.common.color.ColoredTransactionOutput;
import org.hyperledger.model.LevelDBStore;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class StoredTransaction extends ValidatedTransaction {

    public StoredTransaction(ValidatedTransaction transaction, Set<BID> blocks) {
        super(transaction, transaction.getFee());
        this.blocks = blocks;
    }

    public StoredTransaction(ValidatedTransaction transaction) {
        super(transaction, transaction.getFee());
    }

    public StoredTransaction(Transaction transaction, long fee) {
        super(transaction, fee);
    }

    private Set<BID> blocks = new HashSet<>();


    public Set<BID> getBlocks() {
        return blocks;
    }


    public void addBlock(BID hash) {
        blocks.add(hash);
    }


    public byte[] toLevelDB() {
        LevelDBStore.TX.Builder builder = LevelDBStore.TX.newBuilder();

        builder.setVersion(getVersion());
        builder.setLockTime(getLockTime());
        if (getInputs() != null) {
            for (TransactionInput in : getInputs()) {
                LevelDBStore.TX.TXIN.Builder b = LevelDBStore.TX.TXIN.newBuilder();
                b.setIx(in.getSource().getOutputIndex());
                b.setSourceHash(ByteString.copyFrom(in.getSource().getTransactionId().unsafeGetArray()));
                b.setScript(ByteString.copyFrom(in.getScript().toByteArray()));
                b.setSequence(in.getSequence());
                builder.addTxin(b.build());
            }
        }
        if (getOutputs() != null) {
            for (TransactionOutput o : getOutputs()) {
                LevelDBStore.TX.TXOUT.Builder b = LevelDBStore.TX.TXOUT.newBuilder();
                b.setValue(o.getValue());
                b.setScript(ByteString.copyFrom(o.getScript().toByteArray()));
                if (o instanceof ColoredTransactionOutput) {
                    b.setColor(ByteString.copyFrom(((ColoredTransactionOutput) o).getColor().getEncoded()));
                    b.setQuantity(((ColoredTransactionOutput) o).getQuantity());
                }
                builder.addTxout(b.build());
            }
        }
        if (blocks != null) {
            for (BID hash : blocks) {
                builder.addBlocks(ByteString.copyFrom(hash.unsafeGetArray()));
            }
        }
        builder.setFee(getFee());
        return builder.build().toByteArray();
    }

    public static StoredTransaction fromLevelDB(byte[] data) throws HyperLedgerException {
        LevelDBStore.TX p;
        try {
            p = LevelDBStore.TX.parseFrom(data);
            Builder builder = new Builder()
                    .version(p.getVersion())
                    .lockTime(p.getLockTime());
            if (p.getTxinCount() > 0) {
                List<TransactionInput> inputs = new ArrayList<>(p.getTxinCount());
                for (LevelDBStore.TX.TXIN i : p.getTxinList()) {
                    TransactionInput in = new TransactionInput.Builder()
                            .source(new Outpoint(new TID(i.getSourceHash().toByteArray()),
                                    i.getIx()))
                            .script(new Script(i.getScript().toByteArray()))
                            .sequence(i.getSequence()).build();
                    inputs.add(in);
                }
                builder = builder.inputs(inputs);
            }
            if (p.getTxoutCount() > 0) {
                List<TransactionOutput> outputs = new ArrayList<>(p.getTxoutCount());
                for (LevelDBStore.TX.TXOUT o : p.getTxoutList()) {
                    TransactionOutput out = new TransactionOutput.Builder()
                            .value(o.getValue())
                            .script(new Script(o.getScript().toByteArray())).build();
                    if (o.hasColor()) {
                        out = new ColoredTransactionOutput(out.getValue(), out.getScript(),
                                Color.fromEncoded(o.getColor().toByteArray()), o.getQuantity());
                    }
                    outputs.add(out);
                }
                builder = builder.outputs(outputs);
            }
            Transaction t = builder.build();
            StoredTransaction stx = new StoredTransaction(t, p.getFee());

            if (p.getBlocksCount() > 0) {
                for (ByteString bs : p.getBlocksList()) {
                    stx.blocks.add(new BID(bs.toByteArray()));
                }
            }
            return stx;
        } catch (InvalidProtocolBufferException e) {
            throw new HyperLedgerException(e);
        }
    }
}
