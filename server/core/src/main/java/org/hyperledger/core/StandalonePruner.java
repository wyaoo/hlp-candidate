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
import org.hyperledger.common.HyperLedgerException;
import org.hyperledger.core.bitcoin.BitcoinPersistentBlocks;
import org.hyperledger.core.bitcoin.BitcoinValidatorFactory;
import org.hyperledger.core.kvstore.LevelDBStore;
import org.hyperledger.core.signed.BlockSignatureConfig;

import java.util.*;


public class StandalonePruner {
    private Map<BID, StoredHeader> headers = new HashMap<>();
    private List<List<StoredHeader>> topHeaders;
    private List<StoredHeader> trunk;
    private final BlockStore blockStore;
    private final BitcoinPersistentBlocks persistentBlocks;
    private PrunerSettings settings;

    public static void main(String[] args) throws HyperLedgerException {
        new StandalonePruner(new PrunerSettings(true, 1, -1, 14000, 0));
    }

    private StandalonePruner(PrunerSettings settings) throws HyperLedgerException {
        this.settings = settings;
        System.out.println("Reading database...");
        persistentBlocks = new BitcoinPersistentBlocks(new LevelDBStore("work/db/beginning/hyperledger-server-main-2.0.0-SNAPSHOT/data", 100 * 1048576));
        blockStore = new DefaultBlockStore(new ValidatorChain(new BitcoinValidatorFactory()),
                persistentBlocks,
                new CoreOutbox(),
                new ClientEventQueue(),
                settings,
                BlockSignatureConfig.DISABLED);

        StopWatch.start(0);
        blockStore.start();
        persistentBlocks.readHeaders(headers);
        StopWatch.stop(0);

        System.out.println("[" + StopWatch.getEllapsedTime(0) + " ms] Read " + blockStore.getHighestHeader().getHeight() + "blocks");

        trunk = getTopHeaders().get(0);
        System.out.println(trunk.size());

        StopWatch.start(0);
        pruneTrunkFromBottom();
        StopWatch.stop(0);
        System.out.println("[" + StopWatch.getEllapsedTime(0) + " ms] Pruning");
        blockStore.stop();
    }

    private void pruneTrunkFromBottom() throws HyperLedgerException {
        int fromHeight = 0;
        int toHeight = trunk.size() - settings.doNotPruneTopBlocks;
        ListIterator<StoredHeader> it = trunk.listIterator(trunk.size() - fromHeight);
        int i = fromHeight;
        int prunedCount = 0;
        int notPrunedCount = 0;
        while (it.hasPrevious()) {
            if (i >= toHeight) break;
            StoredHeader h = it.previous();
            StopWatch.start(1);
            boolean pruned = false;
            blockStore.standalonePruneBlock(h.getID(), toHeight);
            if (pruned) {
                prunedCount++;
            } else {
                notPrunedCount++;
            }
            StopWatch.stop(1);
            System.out.println("[" + StopWatch.getEllapsedTime(1) + " ms] Block " + h.getHeight() + (pruned ? "" : " not") + " pruned");
            i++;
        }
        System.out.println("Prunable: " + prunedCount);
        System.out.println("Non prunable: " + notPrunedCount);
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
            }
            topHeaders.add(0, topHeaders.remove(longestChainIndex));
        }
        return topHeaders;
    }

    static class StopWatch {
        private static long startTimes[] = new long[100];
        private static long stopTimes[] = new long[100];

        public static void start(int id) {
            startTimes[id] = System.currentTimeMillis();
        }

        public static long stop(int id) {
            stopTimes[id] = System.currentTimeMillis();
            return stopTimes[id] - startTimes[id];
        }

        public static long getEllapsedTime(int id) {
            return stopTimes[id] - startTimes[id];
        }
    }

}
