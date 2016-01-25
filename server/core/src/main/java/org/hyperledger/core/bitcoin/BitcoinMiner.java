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
package org.hyperledger.core.bitcoin;

import org.hyperledger.HyperLedgerSettings;
import org.hyperledger.common.*;
import org.hyperledger.common.color.Color;
import org.hyperledger.common.color.ColoredTransactionOutput;
import org.hyperledger.core.*;
import org.hyperledger.core.signed.BlockSignatureConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class BitcoinMiner implements Runnable, BlockStore.BlockListener {
    private static final Logger log = LoggerFactory.getLogger(BitcoinMiner.class);

    private final BlockStore store;
    private MiningConfig miningConfig;
    private final BitcoinValidatorConfig config;
    private final BlockSignatureConfig blockSignatureConfig;
    private Address minerAddress;
    private volatile boolean running = true;
    private AtomicBoolean abortMiningNextBlock = new AtomicBoolean(false);


    public BitcoinMiner(BlockStore store, MiningConfig miningConfig, BitcoinValidatorConfig config, BlockSignatureConfig blockSignatureConfig) {
        this.store = store;
        this.miningConfig = miningConfig;
        this.config = config;
        this.blockSignatureConfig = blockSignatureConfig;
        minerAddress = miningConfig.getMinerAddress();
        store.addBlockListener(this);
    }

    @Override
    public void run() {
        mineContinuously();
    }

    public void start() {
        running = true;
        abortMiningNextBlock.set(false);
        Thread minerThread = new Thread(this, "MinerThread");
        // set slightly lower priority to give yield to getting/storing new blocks from hte network
        minerThread.setPriority(Thread.NORM_PRIORITY - 1);
        minerThread.start();
    }

    public void stop() {
        running = false;
    }

    public void mineContinuously() {
        log.info("Mining started, delay between blocks {} sec", miningConfig.getDelayBetweenMiningBlocksSecs());
        try {
            while (running) {
                try {
                    long miningStartTime = System.currentTimeMillis();
                    mineAndStoreOneBlock();
                    if (miningConfig.getDelayBetweenMiningBlocksSecs() > 0) {
                        long miningTimeMillis = System.currentTimeMillis() - miningStartTime;
                        sleep(Math.max(0, 1000 * miningConfig.getDelayBetweenMiningBlocksSecs() - miningTimeMillis));
                    }
                } catch (HyperLedgerException e) {
                    log.error("Mining error: {}", e.getMessage());
                    sleep(1000);
                }
            }
        } finally {
            log.info("Mining stopped");
            running = false;
        }
    }

    public Block mineAndStoreOneBlock() throws HyperLedgerException {
        Block block = mineOneBlock();
        if (block != null) {
            store.addClientBlock(block);
        }
        return block;
    }

    public Block mineOneBlock() throws HyperLedgerException {
        abortMiningNextBlock.set(false);
        Block block;
        StoredBlock previous = store.getHighestBlock();
        int intendedHeight = previous.getHeight() + 1;
        log.info("Attempting mining the next block at h: {}, p: {}", intendedHeight, previous.getID());

        int target = previous.getHeader().getEncodedDifficulty();
        if ((previous.getHeight() + 1) % config.getReviewBlocks() == 0 && previous.getHeight() + 1 > config.getReviewBlocks()) {
            target = config.getDifficulty().computeNextDifficulty(store, previous.getHeader());
            log.info("New difficulty target: {} at height: {}", String.format("%x", target), previous.getHeight() + 1);
        }

        if (blockSignatureConfig.enabled()) {
            block = mineWithBlockSignature(previous, target, minerAddress);
        } else {
            block = mineRegular(previous, target, minerAddress);
        }
        if (block != null) {
            log.info("Mined block, id: {}, intended h: {}, ct: {}, d: {}, p: {}", block.getID(), intendedHeight, block.getLocalCreateTime(), block.getDifficultyTarget(), block.getPreviousID());
        } else {
            log.info("Mining a single block aborted, intended h: {}, p: {}", intendedHeight, previous.getID());
        }
        return block;
    }

    private Block mineRegular(StoredBlock previous, int target, Address minerAddress) throws HyperLedgerException {
        if (abortMiningNextBlock.compareAndSet(true, false) || !running) return null;
        while (true) {
            int createTime = calculateNextCreateTime(previous);
            List<Transaction> include = collectTransactions(previous, minerAddress);
            MerkleRoot root = MerkleTree.computeMerkleRoot(include);
            for (int nonce = Integer.MIN_VALUE; nonce < Integer.MAX_VALUE; ++nonce) {
                if (abortMiningNextBlock.compareAndSet(true, false) || !running) {
                    return null;
                }
                BitcoinHeader header = new BitcoinHeader(config.getNewBlockVersion(), previous.getID(), root, createTime, target, nonce);
                if (header.getID().toBigInteger().compareTo(BitcoinDifficulty.getTarget(target)) <= 0) {
                    return new Block(header, include);
                }
            }
        }
    }

    private Block mineWithBlockSignature(StoredBlock previous, int target, Address minerAddress) throws HyperLedgerException {
        if (abortMiningNextBlock.compareAndSet(true, false) || !running) return null;
        byte[] script = Script.create().blockSignature(blockSignatureConfig.getRequiredSignatureCount(), blockSignatureConfig.getPublicKeys()).build().toByteArray();
        byte[] scriptHash = Hash.keyHash(script);
        while (true) {
            int createTime = calculateNextCreateTime(previous);
            List<Transaction> include = collectTransactions(previous, minerAddress);
            MerkleRoot root = MerkleTree.computeMerkleRoot(include);
            for (int nonce = Integer.MIN_VALUE; nonce < Integer.MAX_VALUE; ++nonce) {
                if (abortMiningNextBlock.compareAndSet(true, false) || !running) {
                    return null;
                }
                BitcoinHeader header = new BitcoinHeader(config.getNewBlockVersion(), previous.getID(), root, createTime, target, nonce);
                Script inScript = calculateInScript(header, script);
                HeaderWithSignatures headerWithSignatures = new HeaderWithSignatures(config.getNewBlockVersion(), previous.getID(), root, createTime, target, nonce, inScript, scriptHash);
                if (headerWithSignatures.getID().toBigInteger().compareTo(BitcoinDifficulty.getTarget(target)) <= 0) {
                    return new Block(headerWithSignatures, include);
                }
            }
        }
    }

    @SuppressWarnings("deprecation")
    private int calculateNextCreateTime(StoredBlock previous) {
        int createTime = (int) (new Date().getTime() / 1000);
        if (createTime <= previous.getCreateTime()) {
            // Note that this can generate a timestamp too far in the future if we mine too fast.  But it should
            // be fine if we average one per second.  This is nicer for local testing, so that we don't take two
            // minutes to initialize a chain.
            createTime = previous.getCreateTime() + 1;
        }
        return createTime;
    }

    private Script calculateInScript(BitcoinHeader header, byte[] script) {
        ScriptBuilder scriptBuilder = Script.create();
        for (int i = 0; i < blockSignatureConfig.getPrivateKeyIndex(); i++) {
            scriptBuilder.data(new byte[0]);
        }
        try {
            byte[] sign = blockSignatureConfig.getMinerPrivateKey().sign(Hash.keyHash(header.toByteArray()));
            scriptBuilder.data(sign);
        } catch (IOException e) {
            // should not happen
        }
        for (int i = blockSignatureConfig.getPrivateKeyIndex() + 1; i < blockSignatureConfig.getPublicKeys().size(); i++) {
            scriptBuilder.data(new byte[0]);
        }
        return scriptBuilder.data(script).build();
    }

    private List<Transaction> collectTransactions(StoredBlock previous, Address minerAddress) throws HyperLedgerException {
        WireFormat.SizeWriter sizeWriter = new WireFormat.SizeWriter();
        WireFormatter wireFormatter = HyperLedgerSettings.getInstance().getTxWireFormatter();
        int height = previous.getHeight() + 1;
        Transaction coinbase = createCoinbase(height, minerAddress, config.getRewardForHeight(height));
        List<Transaction> include = new ArrayList<>();
        try {
            wireFormatter.toWire(coinbase, sizeWriter);
            Iterator<ValidatedTransaction> transactionList = store.getMempoolContent().iterator();

            long fee = 0;
            include.add(coinbase);
            while (transactionList.hasNext()) {
                ValidatedTransaction transaction = transactionList.next();
                wireFormatter.toWire(transaction, sizeWriter);
                if (sizeWriter.size() > config.getMaxBlockSize())
                    break;
                fee += transaction.getFee();
                include.add(transaction);
            }
            if (fee != 0L) {
                include.set(0, createCoinbase(height, minerAddress, config.getRewardForHeight(height) + fee));
            }
        } catch (IOException e) {
            throw new HyperLedgerException(e);
        }
        return include;
    }

    private static Transaction createCoinbase(int height, Address address, long subsidy) throws HyperLedgerException {
        WireFormat.ArrayWriter writer = new WireFormat.ArrayWriter();
        try {
            writer.writeUint32(height);
        } catch (IOException e) {
            // cannot happen
        }
        return Transaction.create().inputs(TransactionInput.create().source(new Outpoint(TID.INVALID, 0))
                .script(new Script(writer.toByteArray())).build())
                .outputs(ColoredTransactionOutput.create().payTo(address).value(subsidy).color(Color.BITCOIN).build()).build();
    }

    public void setMinerAddress(Address minerAddress) {
        this.minerAddress = minerAddress;
    }

    public Address getMinerAddress() {
        return minerAddress;
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            // nothing to do
        }
    }

    @Override
    public void blockStored(BlockStoredInfo content) {
        abortMiningNextBlock.set(true);
    }
}
