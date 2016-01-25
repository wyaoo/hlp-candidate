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
package org.hyperledger.blockchainvalidator;

import org.hyperledger.common.*;
import org.hyperledger.core.StoredBlock;
import org.hyperledger.core.StoredHeader;
import org.hyperledger.core.StoredTransaction;
import org.hyperledger.core.bitcoin.BitcoinDifficulty;
import org.hyperledger.core.bitcoin.BitcoinPersistentBlocks;

import java.math.BigInteger;
import java.util.*;
import java.util.stream.Collectors;

public class Validator {
    private static final int NO_CONTAINING_CHAIN_INDEX = -1;
    private BitcoinPersistentBlocks persistentBlocks;
    private final Map<BID, StoredHeader> headers;
    private static final int MAX_CACHE_SIZE = 1000;
    LinkedHashMap<TID, StoredTransaction> transactionCache = new LinkedHashMap<TID, StoredTransaction>(MAX_CACHE_SIZE, 0.8f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry eldest) {
            return size() > MAX_CACHE_SIZE;
        }
    };
    private List<List<StoredHeader>> topHeaders;
    private final int minHeight;
    private final int maxHeight;
    private final int merkleTreeValidationBlockLimit;
    private final int transactionMatchCheckBlockLimit;
    private final boolean checkPrunedData = false;

    private static final BitcoinDifficulty difficulty = new BitcoinDifficulty(BigInteger.valueOf(0xFFFFL).shiftLeft(8 * (0x1d - 3)), 2016, 1209600);

    public Validator(BitcoinPersistentBlocks persistentBlocks, Map<BID, StoredHeader> headers, int minHeight, int maxHeight, int merkleTreeValidationBlockLimit, int transactionMatchCheckBlockLimit) {
        this.persistentBlocks = persistentBlocks;
        this.headers = headers;
        this.minHeight = minHeight;
        this.maxHeight = maxHeight;
        this.merkleTreeValidationBlockLimit = merkleTreeValidationBlockLimit;
        this.transactionMatchCheckBlockLimit = transactionMatchCheckBlockLimit;
    }

    private List<List<StoredHeader>> getTopHeaders() {
        if (topHeaders == null) {
            topHeaders = new ArrayList<>();
            HashMap<BID, StoredHeader> unlinkedHeaders = new HashMap<>(headers);
            for (StoredHeader header : headers.values()) {
                if (headers.containsKey(header.getPreviousID())) {
                    unlinkedHeaders.remove(header.getPreviousID());
                }
            }
            for (StoredHeader topHeader : unlinkedHeaders.values()) {
                ArrayList<StoredHeader> chain = new ArrayList<>();
                topHeaders.add(chain);
                StoredHeader header = topHeader;

                while (header != null && !BID.INVALID.equals(header.getID())) {
                    chain.add(header);
                    header = headers.get(header.getPreviousID());
                }
            }
            int i = 0;
            int longestChainSize = 0;
            int longestChainIndex = 0;
            for (List<StoredHeader> headerChain : topHeaders) {

                int chainSize = headerChain.size();
                if (chainSize > longestChainSize) {
                    longestChainSize = chainSize;
                    longestChainIndex = i;
                }
                i++;
            }
            topHeaders.add(0, topHeaders.remove(longestChainIndex));
        }
        return topHeaders;
    }

    public Set<Header> checkDifficulty() {
        HashSet<Header> result = new HashSet<>();

        for (StoredHeader header : headers.values()) {
            if (header.getID().toBigInteger().compareTo(BitcoinDifficulty.getTarget(header.getEncodedDifficulty())) > 0) {
                result.add(header);
            }
        }
        return result;
    }

    private boolean hasSeen(BID headerId, HashSet<BID> visited) {
        return visited.contains(headerId);
    }

    private int getContainingChainIndex(BID headerId, HashSet<BID>[] seenLinkedHeaders, int ownIndex) {
        for (int i = 0; i < seenLinkedHeaders.length; i++) {
            if (i != ownIndex && hasSeen(headerId, seenLinkedHeaders[i])) {
                return i;
            }
        }
        return NO_CONTAINING_CHAIN_INDEX;
    }

    public List<HeaderChain> checkIfSingleTree() {
        List<HeaderChain> chains = new ArrayList<>();

        HashSet<BID>[] seenLinkedHeaders = (HashSet<BID>[]) new HashSet[getTopHeaders().size()];

        for (int i = 0; i < seenLinkedHeaders.length; i++) {
            seenLinkedHeaders[i] = new HashSet<>();
        }

        int topHeaderIndex = 0;
        for (List<StoredHeader> topHeader : getTopHeaders()) {
            int height = 0;
            BID currentHeaderId = null;
            for (StoredHeader header : topHeader) {
                currentHeaderId = header.getID();
                int containingChainIndex = getContainingChainIndex(currentHeaderId, seenLinkedHeaders, topHeaderIndex);
                if (containingChainIndex != NO_CONTAINING_CHAIN_INDEX) {
                    chains.add(new HeaderChain(topHeader.get(0).getID(), chains.get(containingChainIndex).genesisHeaderId, height));
                    break;
                } else {
                    seenLinkedHeaders[topHeaderIndex].add(currentHeaderId);
                }
                height++;
            }
            chains.add(new HeaderChain(topHeader.get(0).getID(), currentHeaderId, height));
        }
        return chains;
    }


    public Set<BID> checkDifficultyIncrease() {
        Set<BID> violators = new HashSet<>();
        for (List<StoredHeader> topHeader : getTopHeaders()) {
            Iterator<StoredHeader> iterator = topHeader.iterator();
            StoredHeader currentHeader;
            StoredHeader prevHeader = iterator.next();

            while (iterator.hasNext()) {
                currentHeader = prevHeader;
                prevHeader = iterator.next();
                if (prevHeader.getChainWork() != currentHeader.getChainWork() - difficulty.getDifficulty(currentHeader.getEncodedDifficulty())) {
                    violators.add(currentHeader.getID());
                }
            }
        }

        return violators;
    }

    public Set<BID> checkMerkleRoot() throws HyperLedgerException {
        Set<BID> result = new HashSet<>();
        int blocksProcessed = 0;
        for (StoredHeader header : headers.values()) {
            StoredBlock block = persistentBlocks.readBlock(header.getID());
            if (checkPrunedData) {
                MerkleRoot o = MerkleTree.computeMerkleRoot(block.getMerkleTreeNodes());
                MerkleRoot merkleRoot = header.getMerkleRoot();
                if (!merkleRoot.equals(o)) {
                    result.add(header.getID());
                }
            }
            if (merkleTreeValidationBlockLimit > 0 && ++blocksProcessed >= merkleTreeValidationBlockLimit) break;
        }
        return result;
    }

    private class FeeAndReward {

        private final long[] fees;
        private final long reward;
        private long fee;
        private int coinbaseTransactionIndex;

        FeeAndReward(StoredBlock block) {
            fees = new long[block.getTransactions().size()];
            Arrays.fill(fees, 0L);
            reward = calculateReward(block.getHeight());
            fee = 0L;
            coinbaseTransactionIndex = -1;
        }
    }

    public TransactionCheckResult checkTransactionMatch() throws HyperLedgerException {
        TransactionCheckResult result = new TransactionCheckResult();
        int blocksProcessed = 0;
        long totalSpendingAmount = 0L;
        long totalSourceAmount = 0L;
        int currentHeaderIndex = 0;
        boolean hadPrunedTransaction;
        for (StoredHeader header : getTopHeaders().get(0)) {
            if (minHeight > header.getHeight() || maxHeight < header.getHeight()) continue;
            hadPrunedTransaction = false;
            StoredBlock block = persistentBlocks.readBlock(header.getID());
            int blockTransactionCount = block.getTransactions().size();
            long transactionSpendingAmounts[] = new long[blockTransactionCount];
            long transactionSourceAmounts[] = new long[blockTransactionCount];
            Arrays.fill(transactionSpendingAmounts, 0L);
            Arrays.fill(transactionSourceAmounts, 0L);
            FeeAndReward feeAndReward = new FeeAndReward(block);
            int currentTransactionIndex = 0;
            for (Transaction spendingTransaction : block.getTransactions()) {
                for (TransactionOutput output : spendingTransaction.getOutputs()) {
                    if (TID.INVALID.equals(spendingTransaction.getInput(0).getSourceTransactionID())) {
                        feeAndReward.fee = output.getValue() - feeAndReward.reward;
                        feeAndReward.coinbaseTransactionIndex = currentTransactionIndex;
                    } else {
                        transactionSpendingAmounts[currentTransactionIndex] += output.getValue();
                    }
                } // spendingTransaction.getOutputs()
                totalSpendingAmount += transactionSpendingAmounts[currentTransactionIndex];
                for (TransactionInput input : spendingTransaction.getInputs()) {
                    TID sourceTransactionId = input.getSourceTransactionID();
                    StoredTransaction sourceTransaction = getOrReadTransaction(sourceTransactionId);
                    if (sourceTransaction == null) {
                        hadPrunedTransaction = true;
                    }
                    if (TID.INVALID.equals(sourceTransactionId)) {
                        continue;
                    }
                    processInput(result, currentHeaderIndex, currentTransactionIndex, spendingTransaction, input, sourceTransaction, transactionSourceAmounts);
                } // spendingTransaction.getInputs()
                if (transactionSourceAmounts[currentTransactionIndex] < transactionSpendingAmounts[currentTransactionIndex] - feeAndReward.fee - feeAndReward.reward) {
                    result.overspendingTransactions.add(block.getID());
                }
                feeAndReward.fees[currentTransactionIndex] = feeAndReward.coinbaseTransactionIndex == currentTransactionIndex ? feeAndReward.fee : 0;
                totalSourceAmount += transactionSourceAmounts[currentTransactionIndex];
                currentTransactionIndex++;
            } // block.getTransactions()

            checkBlockOverspending(result, totalSpendingAmount, totalSourceAmount, hadPrunedTransaction, header, feeAndReward.reward);
            currentHeaderIndex++;

            if (transactionMatchCheckBlockLimit > 0 && ++blocksProcessed >= transactionMatchCheckBlockLimit) break;
        } // getTopHeaders().get(0)
        return result;
    }

    private void checkBlockOverspending(TransactionCheckResult result, long totalSpendingAmount, long totalSourceAmount, boolean hadPrunedTransaction, StoredHeader header, long reward) {
        if (checkPrunedData) {
            if (!hadPrunedTransaction && totalSourceAmount < totalSpendingAmount - reward) {
                result.overspendingBlocks.add(header);
            }
        } else {
            if (totalSourceAmount < totalSpendingAmount - reward) {
                result.overspendingBlocks.add(header);
            }
        }
    }

    private void processInput(TransactionCheckResult result,
                              int currentHeaderIndex,
                              int currentTransactionIndex,
                              Transaction spendingTransaction,
                              TransactionInput input,
                              StoredTransaction sourceTransaction,
                              long[] transactionSourceAmounts) {
        if (sourceTransaction == null) {
            if (checkPrunedData) {
                List<Outpoint> outpoints = spendingTransaction.getCoins().stream().map(Coin::getOutpoint).collect(Collectors.toList());
                if (!isAllSpent(outpoints)) {
                    result.transactionsWithNoMatchingInput.add(spendingTransaction.getID());
                }
            } else {
                result.transactionsWithNoMatchingInput.add(spendingTransaction.getID());
            }
        } else {
            if (sourceTransaction.getOutputs().size() < input.getOutputIndex()) {
                result.transactionsWithNoMatchingInput.add(spendingTransaction.getID());
            } else {
                transactionSourceAmounts[currentTransactionIndex] += sourceTransaction.getOutput(input.getOutputIndex()).getValue();
            }
            if (!isOnChainFromThis(sourceTransaction.getBlocks(), getTopHeaders().get(0).listIterator(currentHeaderIndex))) {
                result.transactionsWithNoMatchingInput.add(spendingTransaction.getID());
            }
        }
    }

    private boolean isAllSpent(List<Outpoint> outpoints) {
        try {
            for (Outpoint outpoint : outpoints) {
                if (persistentBlocks.getSpendingTransactions(outpoint) == null) {
                    return false;
                }
            }
        } catch (HyperLedgerException e) {
            return false;
        }
        return true;
    }

    long calculateReward(int height) {
        if (height > 6720000)
            return 0;
        // 50 bitcoin is 50 * 10^8 satoshi
        return 5000000000L / (1L << (height / 210000));
    }

    int missCount = 0;
    int hitCount = 0;

    private StoredTransaction getOrReadTransaction(TID sourceTransactionId) throws HyperLedgerException {
        StoredTransaction storedTransaction = transactionCache.get(sourceTransactionId);
        if (storedTransaction == null) {
            storedTransaction = persistentBlocks.readTransaction(sourceTransactionId);
            if (storedTransaction == null) {
                return null;
            }
            transactionCache.put(storedTransaction.getID(), storedTransaction);
            missCount++;
        } else {
            hitCount++;
        }
        return storedTransaction;
    }

    private boolean isOnChainFromThis(Set<BID> referredBlockIds, ListIterator<StoredHeader> it) {
        while (it.hasNext()) {
            StoredHeader header = it.next();
            if (referredBlockIds.contains(header.getID())) {
                return true;
            }
        }
        return false;
    }

    static class HeaderChain {
        BID topHeaderId;
        BID genesisHeaderId;
        int index = -1;
        int height = -1;

        private static int chainCount = 0;

        public HeaderChain(BID topHeaderId, BID genesisHeaderId, int height) {
            this.topHeaderId = topHeaderId;
            this.genesisHeaderId = genesisHeaderId;
            this.height = height;
            index = chainCount;
            chainCount++;

        }

        @Override
        public String toString() {
            return "height: " + height + ", top: " + topHeaderId + ", genesis: " + genesisHeaderId;
        }
    }

    static class TransactionCheckResult {
        Set<TID> transactionsWithNoMatchingInput = new HashSet<>();
        Set<BID> overspendingTransactions = new HashSet<>();
        Set<StoredHeader> overspendingBlocks = new HashSet<>();
    }
}
