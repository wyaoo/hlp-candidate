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

import com.google.common.base.Stopwatch;
import org.hyperledger.common.*;
import org.hyperledger.core.bitcoin.BitcoinBlockStore;
import org.hyperledger.core.bitcoin.BitcoinValidatorFactory;
import org.hyperledger.core.bitcoin.GenesisBlocks;
import org.hyperledger.core.signed.BlockSignatureConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class DefaultBlockStore implements BlockStore, BitcoinBlockStore {
    private static final Logger log = LoggerFactory.getLogger(DefaultBlockStore.class);

    // This class uses two locking mechanisms in conjunction:
    // a read-write lock and a single threaded executor with priority queue
    //
    // Multiple threads may hold the read lock allowing simultaneous
    // reads of internal state. A thread that has the write lock
    // has exclusive write access to internal state.
    // In addition methods that will write run with the single threaded execitor
    // pre-computing changes they will apply within the write lock.
    // This allows read-only methods to execute
    // during the pre-computation of a write, but ensures that only a
    // single pre-computation takes place at any time point.
    // Once pre-computation is ready the write lock is aquired and
    // pre-computed changes applied.
    //
    // This is a work-around that lock upgrade is not possible from read to write.
    //
    // To avoid deadlock no thread holding the read lock may call a method that writes the
    // internal state. Methods that execute in the single threaded executor should
    // not call another method that uses the executor.
    private final ReentrantReadWriteLock readWriteLock = new ReentrantReadWriteLock();
    private final List<BlockListener> blockListeners = new ArrayList<>();
    private int heightAtPrevPruning = 0;

    private abstract static class PrioritizedOrderedCallable<T> implements Callable<T> {
        public final int order;
        public final int priority;

        public PrioritizedOrderedCallable(int priority, int order) {
            this.priority = priority;
            this.order = order;
        }
    }

    private static class PrioritizedOrderedFutureTask<T> extends FutureTask<T> {
        public final int order;
        public final int priority;

        public PrioritizedOrderedFutureTask(Callable<T> callable, int priority, int order) {
            super(callable);
            this.priority = priority;
            this.order = order;
        }
    }

    private static class Tops {
        final BID spvTop;
        final BID fullTop;

        public Tops(BID spvTop, BID fullTop) {
            this.spvTop = spvTop;
            this.fullTop = fullTop;
        }
    }

    private final PriorityBlockingQueue<Runnable> singleThreadedTaskQueue = new PriorityBlockingQueue<>(10, (o1, o2) -> {
        PrioritizedOrderedFutureTask task1 = (PrioritizedOrderedFutureTask) o1;
        PrioritizedOrderedFutureTask task2 = (PrioritizedOrderedFutureTask) o2;
        return task1.priority == task2.priority ? task1.order - task2.order : task1.priority - task2.priority;
    });

    private final ExecutorService singleThreadedExecutor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, singleThreadedTaskQueue) {
        @Override
        protected <T> RunnableFuture<T> newTaskFor(Callable<T> callable) {
            if (callable instanceof PrioritizedOrderedCallable)
                return new PrioritizedOrderedFutureTask<T>(callable, ((PrioritizedOrderedCallable) callable).priority, ((PrioritizedOrderedCallable<T>) callable).order);
            else
                return new PrioritizedOrderedFutureTask<T>(callable, 0, 0);
        }
    };

    // new transactions initiated by application clients of this server
    // are places into this outbox after validation
    // the outbox will be read by the network layer and sent to peers
    private final CoreOutbox outbox;

    // this is the outbound queue of events that will notify client
    // applications of state changes of the block chain
    private final ClientEventQueue clientEventQueue;

    // This class deals with structural integrity of the block chain
    // content validation is delegated to validators delivered by this factory
    private final ValidatorChain validatorChain;

    // This is the persistence layer of the block chain offering
    // transactional append store of blocks
    private final PersistentBlocks persistentBlocks;

    // This is the pool of transactions heard on the network
    // validated but not yet included into the block chain
    private final Mempool mempool = new Mempool();

    // This is the map of block header kept in memory all time for
    // the entire chain
    private final Map<BID, StoredHeader> headers = new HashMap<>();

    // The set of block headers (a subset of above) that are along
    // the trunk (the longes chain in the tree of blocks)
    private final Set<BID> trunk = new HashSet<>();

    // This is an ordered representation of the above trunk set
    // useful for quick traversal first is highest block, last is genesis
    final LinkedList<StoredHeader> trunkList = new LinkedList<>();

    // id of the highest known header and block
    // this has to be volatile as read-write lock does not
    // guarantee consistent read
    private volatile Tops tops = new Tops(null, null);

    private final PrunerSettings prunerSettings;
    private final BlockSignatureConfig blockSignatureConfig;

    // UTXO cache of unspent coins that were already stored in blocks.
    // The coins are guaranteed unspent, but here is no guarantee that they are along a trunk,
    // further check is needed after retrieval with isOnTrunk.
    // This is to reduce number of reads needed while looking for inputs in block validation.
    static class CoinCache {
        private static final int CACHESIZE = 10000;

        private final Map<Outpoint, StoredTransaction> cache = new LinkedHashMap<Outpoint, StoredTransaction>() {
            @Override
            protected boolean removeEldestEntry(Map.Entry eldest) {
                return size() > CACHESIZE;
            }
        };

        public StoredTransaction getTransaction(Outpoint outpoint) {
            return cache.get(outpoint);
        }

        public void remove(Outpoint outpoint) {
            cache.remove(outpoint);
        }

        public void add(StoredTransaction transaction) {
            for (Coin c : transaction.getCoins()) {
                cache.put(c.getOutpoint(), transaction);
            }
        }
    }

    private final CoinCache coinCache = new CoinCache();

    // A thread pool used to parallel read of spent coins if not found in cache or mempool
    private final ExecutorService readerPool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());


    // helper class for a potentially different view of the trunk set
    // while validating on a branch of the block tree
    private static class DeltaHashSet implements TrunkFilter {
        private final Set<BID> original;
        private final Set<BID> added;
        private final Set<BID> removed;

        public DeltaHashSet(Set<BID> original) {
            this.original = original;
            added = new HashSet<>();
            removed = new HashSet<>();
        }

        public void add(BID h) {
            if (!removed.remove(h))
                added.add(h);
        }

        public void remove(BID h) {
            if (!added.remove(h))
                removed.add(h);
        }

        public boolean contains(BID h) {
            return (original.contains(h) || added.contains(h)) && !removed.contains(h);
        }
    }


    private static boolean isOnTrunk(StoredTransaction t, TrunkFilter trunk) {
        if (t != null && t.getBlocks() != null) {
            for (BID bh : t.getBlocks())
                if (trunk.contains(bh))
                    return true;
        }
        return false;
    }

    private static boolean isOnTrunk(BID h, TrunkFilter trunk) {
        return trunk.contains(h);
    }

    private boolean isAnyOnTrunk(Set<StoredTransaction> ts, TrunkFilter trunk) {
        return ts.stream().anyMatch((t) -> isOnTrunk(t, trunk::contains));
    }

    private boolean isAnyOnTrunk(Set<StoredTransaction> ts) {
        return ts.stream().anyMatch((t) -> isOnTrunk(t, trunk::contains));
    }

    private StoredTransaction onTrunk(StoredTransaction t) {
        return isOnTrunk(t, trunk::contains) ? t : null;
    }

    private StoredTransaction onTrunk(StoredTransaction t, TrunkFilter trunk) {
        return isOnTrunk(t, trunk) ? t : null;
    }

    private StoredTransaction oneOnTrunk(Set<StoredTransaction> ts) {
        return ts.stream().filter((t) -> isOnTrunk(t, trunk::contains)).findAny().orElse(null);
    }

    private Set<StoredTransaction> allOnTrunk(Set<StoredTransaction> ts) {
        return ts.stream().filter((t) -> isOnTrunk(t, trunk::contains)).collect(Collectors.toSet());
    }

    // helper methods for lock use

    @FunctionalInterface
    public interface Op<R> {
        R execute() throws HyperLedgerException;
    }

    private <T> T readOpE(Op<T> f) throws HyperLedgerException {
        try {
            readWriteLock.readLock().lock();
            return f.execute();
        } finally {
            readWriteLock.readLock().unlock();
        }
    }

    private <T> T readOp(Supplier<T> f) {
        try {
            readWriteLock.readLock().lock();
            return f.get();
        } finally {
            readWriteLock.readLock().unlock();
        }
    }

    private static class DBReadResult {
        public final TID validating;
        public final Outpoint source;
        public final StoredTransaction transaction;
        public final Set<StoredTransaction> spends;

        public DBReadResult(TID validating, Outpoint source, StoredTransaction transaction, Set<StoredTransaction> spends) {
            this.validating = validating;
            this.source = source;
            this.transaction = transaction;
            this.spends = spends;
        }
    }


    public DefaultBlockStore(ValidatorChain validatorChain,
                             PersistentBlocks persistentBlocks,
                             CoreOutbox outbox,
                             ClientEventQueue clientEventQueue,
                             PrunerSettings prunerSettings,
                             BlockSignatureConfig blockSignatureConfig) {
        this.persistentBlocks = persistentBlocks;
        this.validatorChain = validatorChain;
        this.outbox = outbox;
        this.clientEventQueue = clientEventQueue;
        this.prunerSettings = prunerSettings;
        this.blockSignatureConfig = blockSignatureConfig;
    }

    @Override
    public void rejectedByPeer(String command, Hash hash, int code) {
        clientEventQueue.sendStoreEvent(new ClientEventQueue.Rejected(command, hash, code));
    }

    @Override
    public void start() throws HyperLedgerException {

        initializePersistentBlocks();

        if (prunerSettings.enabled) {
            setupPruning();
        }
    }

    private void initializePersistentBlocks() throws HyperLedgerException {
        try {
            singleThreadedExecutor.submit(() -> {
                persistentBlocks.start();
                try {
                    readWriteLock.writeLock().lock();

                    if (persistentBlocks.isEmpty()) {
                        Block genesisBlock;
                        if (blockSignatureConfig.enabled()) {
                            byte[] inScriptBytes = Script.create().blockSignature(blockSignatureConfig.getRequiredSignatureCount(), blockSignatureConfig.getPublicKeys()).build().toByteArray();
                            byte[] scriptHash = Hash.keyHash(inScriptBytes);
                            genesisBlock = GenesisBlocks.regtestWithHeaderSignature(new byte[0], scriptHash);
                        } else {
                            genesisBlock = validatorChain.getValidatorFactory(BitcoinValidatorFactory.class).getConfig().getGenesisBlock();
                        }
                        addGenesisSingleThreaded(genesisBlock);
                    }

                    log.info("Reading all block header...");
                    // read in all headers
                    persistentBlocks.readHeaders(headers);

                    // find highest work
                    StoredHeader last = null;
                    for (StoredHeader b : headers.values()) {
                        if (last == null || StoredHeader.compareHeaders(b, last) > 0)
                            last = b;
                    }
                    // find trunk
                    if (last != null) {
                        log.info("Highest block is " + last.getID());
                        tops = new Tops(last.getID(), last.getID());
                        do {
                            if (trunk.add(last.getID()))
                                trunkList.addLast(last);
                            last = headers.get(last.getPreviousID());
                        } while (last != null);
                    }
                } finally {
                    readWriteLock.writeLock().unlock();
                }
                return null;
            }).get();

        } catch (ExecutionException e) {
            throw new HyperLedgerException(e.getCause());
        } catch (InterruptedException e) {
            throw new HyperLedgerException(e);
        }
    }

    private void setupPruning() {
        Thread pruningThread = new Thread(() -> {
            while (true) {
                CountDownLatch latch = null;
                final int blockHeight;
                try {
                    readWriteLock.readLock().lock();
                    blockHeight = headers.get(tops.fullTop).getHeight();
                } finally {
                    readWriteLock.readLock().unlock();
                }

                if (heightAtPrevPruning < blockHeight) {
                    try {
                        readWriteLock.readLock().lock();

                        StandalonePruner.StopWatch.start(0);

                        heightAtPrevPruning = blockHeight;

                        int fromHeight = prunerSettings.pruneFrom;
                        int toHeight;
                        if (prunerSettings.pruneOnlyLowerThanHeight >= 0) {
                            toHeight = Math.min(prunerSettings.pruneOnlyLowerThanHeight, blockHeight);
                        } else {
                            toHeight = Math.max(0, blockHeight - prunerSettings.doNotPruneTopBlocks + 1);
                        }

                        ListIterator<StoredHeader> it = trunkList.listIterator(trunkList.size() - fromHeight);
                        int blockProcessed = fromHeight;
                        int priority = isDownloading() ? 0 : 1;


                        if (toHeight - fromHeight - 1 > 0) {
                            latch = new CountDownLatch(toHeight - fromHeight);
                        }
                        final CountDownLatch finalLatch = latch;

                        while (it.hasPrevious()) {
                            if (blockProcessed >= toHeight) break;
                            StoredHeader h = it.previous();

                            singleThreadedExecutor.submit(new PrioritizedOrderedCallable<Void>(priority, h.getHeight()) {
                                @Override
                                public Void call() throws Exception {
                                    try {
                                        pruneBlock(h.getID(), toHeight);
                                    } catch (Throwable e) {
                                        log.warn("Error while pruning at height {}, {}", h.getHeight(), e);
                                    } finally {
                                        if (finalLatch != null) {
                                            finalLatch.countDown();
                                        }
                                    }
                                    return null;
                                }
                            });
                            blockProcessed++;
                        }
                        StandalonePruner.StopWatch.stop(0);
                        log.info("Scheduling pruning from {} to {} took {} ms", fromHeight, toHeight, StandalonePruner.StopWatch.getEllapsedTime(0));
                    } finally {
                        readWriteLock.readLock().unlock();
                    }
                } else {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
                try {
                    if (latch != null) latch.await();
                } catch (InterruptedException e) {
                    break;
                }
            } // while(true)
        });
        pruningThread.setDaemon(true);
        pruningThread.setName("pruning");
        pruningThread.start();
    }

    @Override
    public void stop() {
        singleThreadedExecutor.shutdown();
    }

    @Override
    public boolean isEmpty() throws HyperLedgerException {
        return readOpE(persistentBlocks::isEmpty);
    }

    @Override
    public StoredBlock getHighestBlock() throws HyperLedgerException {
        return readOpE(() -> persistentBlocks.readBlock(tops.fullTop));
    }

    @Override
    public StoredHeader getHighestHeader() throws HyperLedgerException {
        return readOpE(() -> headers.get(tops.spvTop));
    }

    @Override
    public StoredBlock getBlock(BID hash) throws HyperLedgerException {
        return readOpE(() -> persistentBlocks.readBlock(hash));
    }

    @Override
    public ValidatedTransaction getTransaction(TID hash) throws HyperLedgerException {
        return readOpE(() -> {
            ValidatedTransaction t = mempool.get(hash);
            return t != null ? t : onTrunk(persistentBlocks.readTransaction(hash));
        });
    }

    @Override
    public BID getTrunkBlockID(Transaction t) {
        if (t instanceof StoredTransaction) {
            return readOp(() -> ((StoredTransaction) t).getBlocks().stream().filter(b -> isOnTrunk(b, trunk::contains)).findAny().orElse(null));
        }
        return null;
    }

    @Override
    public List<BID> getMissingBlocks(int max) {
        return readOp(() -> {
            LinkedList<BID> missing = new LinkedList<>();

            if (trunk.size() > getFullHeight() + 1) {
                ListIterator<StoredHeader> iterator = trunkList.listIterator(trunkList.size() - getFullHeight() - 1);

                for (int n = 0; iterator.hasPrevious() && n < max; ++n)
                    missing.add(iterator.previous().getID());
            }
            return missing;
        });
    }

    @Override
    public StoredHeader getHeader(BID hash) {
        return readOp(() -> headers.get(hash));
    }

    @Override
    public BID getSpvTop() {
        return readOp(() -> tops.spvTop);
    }

    @Override
    public BID getFullTop() {
        return readOp(() -> tops.fullTop);
    }


    @Override
    public int getSpvHeight() {
        return readOp(() -> {
            BID spvTop = tops.spvTop;
            return spvTop != null ? headers.get(spvTop).getHeight() : 0;
        });
    }

    @Override
    public int getFullHeight() {
        return readOp(() -> {
            BID fullTop = tops.fullTop;
            return fullTop != null ? headers.get(fullTop).getHeight() : 0;
        });
    }

    @Override
    public byte[] getMiscData(BID id) {
        return readOp(() -> {
            return persistentBlocks.readMisc(id);
        });
    }

    @Override
    public boolean hasTransaction(TID hash) throws HyperLedgerException {
        return readOpE(() -> mempool.get(hash) != null || persistentBlocks.hasTransaction(hash));
    }

    @Override
    public boolean hasBlock(BID hash) throws HyperLedgerException {
        return readOpE(() -> persistentBlocks.hasBlock(hash));
    }

    @Override
    public List<BID> getHeaderLocator() {
        return readOp(() -> {
            List<BID> th = new ArrayList<>();
            int step = 1, n = 1;
            for (StoredHeader h : trunkList) {
                if (n % step == 0) {
                    th.add(h.getID());
                    if (n > 10)
                        step *= 2;
                }
                ++n;
            }
            if (th.isEmpty())
                th.add(BID.INVALID);
            return th;
        });
    }

    @Override
    public List<BID> catchUpHeaders(List<BID> inventory, int limit) throws HyperLedgerException {
        return readOp(() -> {
            List<BID> added = new ArrayList<>();
            BID catchUpFrom = null;
            for (BID sample : inventory) {
                if (trunk.contains(sample)) {
                    catchUpFrom = sample;
                    break;
                }
            }

            BID curr = tops.spvTop;
            if (headers.containsKey(curr)) {
                BID prev;
                do {
                    if (added.size() >= limit) {
                        break;
                    }
                    if (catchUpFrom != null && curr.equals(catchUpFrom)) {
                        break;
                    }
                    added.add(curr);
                    prev = headers.get(curr).getPreviousID();
                    curr = prev;
                } while (!BID.INVALID.equals(prev));
            }
            Collections.reverse(added);
            return added;
        });
    }

    @Override
    public List<BID> catchUpBlocks(List<BID> inventory, int limit) throws HyperLedgerException {
        return readOp(() -> {
            List<BID> added = new ArrayList<>();
            BID catchUpFrom = null;
            for (BID sample : inventory) {
                if (trunk.contains(sample)) {
                    catchUpFrom = sample;
                    break;
                }
            }

            BID curr = tops.fullTop;
            if (headers.containsKey(curr)) {
                BID prev;
                do {
                    if (added.size() >= limit) {
                        break;
                    }
                    if (catchUpFrom != null && curr.equals(catchUpFrom)) {
                        break;
                    }
                    added.add(curr);
                    prev = headers.get(curr).getPreviousID();
                    curr = prev;
                } while (!BID.INVALID.equals(prev));
            }
            Collections.reverse(added);
            return added;
        });
    }

    @Override
    public List<StoredTransaction> filterTransactions(Set<ByteVector> matchSet, MasterKey ek, int lookAheadP)
            throws HyperLedgerException {
        int lookAhead = Math.min(Math.max(10, lookAheadP), 1000);

        Map<ByteVector, Integer> addressMap = new HashMap<>();
        LinkedList<ByteVector> newAddresses = new LinkedList<>();
        for (int i = 0; i < lookAhead; ++i) {
            Script addressBytes = ek.getKey(i).getAddress().getAddressScript();
            ByteVector address = new ByteVector(addressBytes.toByteArray());
            matchSet.add(address);
            addressMap.put(address, i);
            newAddresses.add(address);
        }
        int nextIndex = lookAhead;

        return readOpE(() -> {
            Stopwatch timer = Stopwatch.createStarted();

            Set<Transaction> txs = new HashSet<>();
            List<StoredTransaction> tlist = new ArrayList<>();
            int nextI = nextIndex;
            int firstI = 0;

            while (!newAddresses.isEmpty()) {
                ByteVector addressScript = newAddresses.remove();

                Script script = new Script(addressScript.toByteArray());
                Set<StoredTransaction> receives = allOnTrunk(persistentBlocks.getTransactionsWithOutput(script));
                if (!receives.isEmpty()) {
                    for (StoredTransaction r : receives) {
                        firstI = Math.max(firstI, addressMap.get(addressScript));
                        for (int i = nextI; i < firstI + lookAhead; ++i) {
                            Script addressBytes = ek.getKey(i).getAddress().getAddressScript();
                            ByteVector address = new ByteVector(addressBytes.toByteArray());
                            matchSet.add(address);
                            addressMap.put(address, i);
                            newAddresses.add(address);
                        }
                        nextI = firstI + lookAhead;

                        if (txs.add(r)) {
                            tlist.add(r);
                        }
                        for (Coin o : r.getCoins()) {
                            Address a = o.getOutput().getOutputAddress();
                            if (a != null && addressMap.containsKey(new ByteVector(a.getAddressScript().toByteArray()))) {
                                StoredTransaction s = oneOnTrunk(persistentBlocks.getSpendingTransactions(o.getOutpoint()));
                                if (s != null) {
                                    if (txs.add(s)) {
                                        tlist.add(s);
                                    }
                                } else {
                                    matchSet.add(new ByteVector(o.getOutpoint().toWire()));
                                }
                            }
                        }
                    }
                } else if (persistentBlocks.probablyHadTransactionsWithOutput(script)) {
                    firstI = Math.max(firstI, addressMap.get(addressScript));
                    for (int i = nextI; i < firstI + lookAhead; ++i) {
                        Script addressBytes = ek.getKey(i).getAddress().getAddressScript();
                        ByteVector address = new ByteVector(addressBytes.toByteArray());
                        matchSet.add(address);
                        addressMap.put(address, i);
                        newAddresses.add(address);
                    }
                    nextI = firstI + lookAhead;
                }
            }
            sortTransactions(tlist);
            log.info("Filtered {} transactions for master public key using {} addresses in {} ms.", tlist.size(),
                    addressMap.size(), timer.stop().elapsed(TimeUnit.MILLISECONDS));
            return tlist;
        });
    }

    @Override
    public List<ValidatedTransaction> getMempoolContent() {
        return readOp(mempool::getInventory);
    }

    @Override
    public List<ValidatedTransaction> scanUnconfirmedPool(Set<ByteVector> matchSet) {
        return readOp(() -> mempool.scanUnconfirmedPool(matchSet));
    }

    @Override
    public List<StoredTransaction> filterTransactions(Set<ByteVector> matchSet) throws HyperLedgerException {
        return readOpE(() -> {
            Stopwatch timer = Stopwatch.createStarted();
            List<StoredTransaction> txs = new ArrayList<>();

            Set<StoredTransaction> withOutput = new HashSet<>();
            Set<StoredTransaction> spend = new HashSet<>();

            for (ByteVector s : matchSet) {
                withOutput.addAll(allOnTrunk(persistentBlocks.getTransactionsWithOutput(new Script(s.toByteArray()))));
            }
            for (StoredTransaction coin : withOutput) {
                for (Coin o : coin.getCoins()) {
                    if (matchSet.contains(new ByteVector(o.getOutput().getScript().toByteArray()))) {
                        StoredTransaction s = oneOnTrunk(persistentBlocks.getSpendingTransactions(o.getOutpoint()));
                        if (s != null) {
                            spend.add(s);
                        } else {
                            matchSet.add(new ByteVector(o.getOutpoint().toWire()));
                        }
                    }
                }
            }
            txs.addAll(spend);
            txs.addAll(withOutput);
            sortTransactions(txs);
            log.info("Filtered {} transactions for {} output scripts in {} ms.", txs.size(), matchSet.size(),
                    timer.stop().elapsed(TimeUnit.MILLISECONDS));
            return txs;
        });
    }

    private class StoredTransactionWithHeight {
        StoredTransaction transaction;
        int height;

        public StoredTransactionWithHeight(StoredTransaction transaction) {
            this.transaction = transaction;
            height = getTransactionHeight(transaction);
        }
    }

    private int compare(StoredTransactionWithHeight o1, StoredTransactionWithHeight o2) {
        if (o1.height == o2.height) {
            for (TransactionInput in : o1.transaction.getInputs()) {
                if (in.getSourceTransactionID().equals(o2.transaction.getID())) {
                    return 1;
                }
            }
            for (TransactionInput in : o2.transaction.getInputs()) {
                if (in.getSourceTransactionID().equals(o1.transaction.getID())) {
                    return -1;
                }
            }
        }
        return o1.height - o2.height;
    }

    private void sortTransactions(List<StoredTransaction> tl) {
        ArrayList<StoredTransactionWithHeight> transactions = new ArrayList<>();
        for (StoredTransaction t : tl) {
            transactions.add(new StoredTransactionWithHeight(t));
        }
        // sub-optimal to sort transitively
        boolean swapped;
        do {
            swapped = false;
            for (int i = 0; i < transactions.size(); ++i) {
                for (int j = i + 1; j < transactions.size(); ++j) {
                    if (compare(transactions.get(i), transactions.get(j)) > 0) {
                        StoredTransactionWithHeight t = transactions.get(i);
                        transactions.set(i, transactions.get(j));
                        transactions.set(j, t);
                        swapped = true;
                    }
                }
            }
        } while (swapped);
        tl.clear();
        for (StoredTransactionWithHeight th : transactions) {
            tl.add(th.transaction);
        }
    }

    @Override
    public List<ValidatedTransaction> getDescendants(List<TID> tids) throws HyperLedgerException {
        return readOpE(() -> {
            LinkedList<ValidatedTransaction> transactions = new LinkedList<>();
            for (TID tid : tids) {
                ValidatedTransaction t = mempool.get(tid);
                if (t != null) {
                    transactions.add(t);
                } else {
                    t = persistentBlocks.readTransaction(tid);
                    if (t != null) {
                        transactions.add(t);
                    }
                }
            }
            List<ValidatedTransaction> remove = new ArrayList<>();
            ListIterator<ValidatedTransaction> ti = transactions.listIterator();
            while (ti.hasNext()) {
                ValidatedTransaction t = ti.next();
                int spent = 0;
                for (Coin c : t.getCoins()) {
                    Outpoint o = c.getOutpoint();
                    if (c.getOutput().getScript().isPrunable()) {
                        ++spent;
                    } else {
                        ValidatedTransaction memSpend = mempool.getSpend(c);
                        if (memSpend != null) {
                            ti.add(memSpend);
                            ++spent;
                        } else {
                            Set<StoredTransaction> spends = persistentBlocks.getSpendingTransactions(o);
                            for (StoredTransaction s : spends) {
                                if (isOnTrunk(s, trunk::contains)) {
                                    ti.add(s);
                                    ++spent;
                                }
                            }
                        }
                    }
                }
                if (spent == t.getCoins().size()) {
                    remove.add(t);
                }
            }
            transactions.removeAll(remove);
            return transactions;
        });
    }

    @Override
    public List<ValidatedTransaction> getSpendingTransactions(List<TID> tids) throws HyperLedgerException {
        return readOpE(() -> {
            LinkedList<ValidatedTransaction> transactions = new LinkedList<>();
            for (TID tid : tids) {
                ValidatedTransaction t = mempool.get(tid);
                if (t != null) {
                    transactions.add(t);
                } else {
                    t = persistentBlocks.readTransaction(tid);
                    if (t != null) {
                        transactions.add(t);
                    }
                }
            }

            List<ValidatedTransaction> spendingTransactions = new ArrayList<>();

            for (ValidatedTransaction tx : transactions) {
                for (Coin c : tx.getCoins()) {
                    ValidatedTransaction memSpend = mempool.getSpend(c);
                    if (memSpend != null) spendingTransactions.add(memSpend);
                    Outpoint o = c.getOutpoint();
                    Set<StoredTransaction> spends = persistentBlocks.getSpendingTransactions(o);
                    for (StoredTransaction s : spends) {
                        if (isOnTrunk(s, trunk::contains)) {
                            spendingTransactions.add(s);
                        }
                    }
                }
            }
            return spendingTransactions;
        });
    }

    /**
     * check if a transaction could be uniquelly referenced along a trunk with that hash
     *
     * @param th    transaction hash
     * @param trunk the filter to define the trunk
     * @return true if the transaction was uniquelly identified with hash
     * @throws HyperLedgerException from storage layer
     */
    private boolean isUnique(TID th, TrunkFilter trunk) throws HyperLedgerException {
        // if no such transaction is in store we are fine
        if (!hasTransaction(th))
            return true;

        StoredTransaction t = onTrunk(persistentBlocks.readTransaction(th), trunk);
        if (t != null) {
            // if yes, all outputs must have been already spent
            for (Coin o : t.getCoins()) {
                if (!isAnyOnTrunk(persistentBlocks.getSpendingTransactions(o.getOutpoint()), trunk)) {
                    return false;
                }
            }
        }
        return true;
    }

    @Override
    public void addGenesis(Block b) throws HyperLedgerException {
        try {
            singleThreadedExecutor.submit(() -> addGenesisSingleThreaded(b)).get();
        } catch (ExecutionException e) {
            throw LoggedHyperLedgerException.loggedError(log, e.getCause());
        } catch (InterruptedException e) {
            throw LoggedHyperLedgerException.loggedError(log, e);
        }

    }

    private Object addGenesisSingleThreaded(final Block b) throws HyperLedgerException {
        if (!b.getPreviousID().equals(BID.INVALID))
            throw LoggedHyperLedgerException.loggedError(log, "Genesis block expected");

        if (!persistentBlocks.isEmpty())
            throw LoggedHyperLedgerException.loggedError(log, "Genesis block can only be the first in db");

        log.info("Storing genesis block ...");
        StoredHeader storedHeader =
                new StoredHeader(b.getHeader(), 1.0, 0);
        try {
            readWriteLock.writeLock().lock();

            headers.put(b.getID(), storedHeader);
            trunkList.add(storedHeader);
            trunk.add(b.getID());
            tops = new Tops(b.getID(), b.getID());
            List<StoredTransaction> st = new ArrayList<>();
            Transaction gt = b.getTransactions().get(0);
            st.add(new StoredTransaction(gt, 0));
            persistentBlocks.writeBlock(new StoredBlock(storedHeader, st));
            log.info("Stored genesis " + b.getID());

        } finally {
            readWriteLock.writeLock().unlock();
        }
        return null;
    }

    @Override
    @SuppressWarnings("deprecation")
    public int medianBlockTime(int window) {
        List<Integer> times = new ArrayList<>(window);
        for (Header h : trunkList) {
            times.add(h.getCreateTime());
            if (--window < 0)
                break;
        }
        Collections.sort(times, Integer::compareUnsigned);
        return times.size() > window / 2 ? times.get(window / 2) : 0;
    }

    @Override
    public boolean isBlockVersionUsed(int version, BID until, int required, int window) {
        int have = 0;
        for (StoredHeader h : trunkList) {
            if (Integer.compareUnsigned(h.getVersion(), version) >= 0)
                ++have;

            if (--window < 0)
                break;

            if (have >= required)
                return true;

            if (required - have < window)
                return false;
        }
        return have >= required;
    }

    @Override
    public int getTransactionHeight(TID hash) throws HyperLedgerException {
        return getTransactionHeight(persistentBlocks.readTransaction(hash));
    }

    private int getTransactionHeight(StoredTransaction t) {
        if (t != null) {
            for (BID h : t.getBlocks()) {
                if (trunk.contains(h)) {
                    return headers.get(h).getHeight();
                }
            }
        }
        return 0;
    }

    @Override
    public ValidatedTransaction addClientTransaction(Transaction t) throws HyperLedgerException {
        try {
            ValidatedTransaction r = addTransaction(t);
            outbox.sendP2PEvent(new CoreOutbox.TransactionAdded(r));
            return r;
        } finally {
            log.info("Processed client transaction " + t.getID());
        }
    }

    @Override
    public ValidatedTransaction addTransaction(final Transaction t) throws HyperLedgerException {
        try {
            readWriteLock.readLock().lock();

            Stopwatch stopwatch = Stopwatch.createStarted();

            ValidatedTransaction validated = mempool.get(t.getID());
            if (validated != null) {
                log.debug("Ignoring known transaction " + t.getID());
                return validated;
            }

            CompletionService<DBReadResult> resolver = new ExecutorCompletionService<>(readerPool);

            int dbread = 0;
            Map<Outpoint, Transaction> referred = new HashMap<>();
            for (TransactionInput in : t.getInputs()) {
                Outpoint op = new Outpoint(in.getSourceTransactionID(), in.getOutputIndex());
                if (op.isNull()) // Native asset marker - will be validated in the validator
                    continue;
                Transaction referredTransaction = null;
                // if available in mempool
                if (mempool.isAvailable(op)) {
                    referredTransaction = mempool.get(in.getSourceTransactionID());
                } else {
                    StoredTransaction cached = coinCache.getTransaction(in.getSource());
                    if (cached != null && isOnTrunk(cached, trunk::contains)) {
                        // cache hit also means it is unspent
                        referredTransaction = cached;
                    } else {
                        // if in db
                        ++dbread;
                        resolver.submit(() -> {
                            StoredTransaction storedSource = persistentBlocks.readTransaction(in.getSource());
                            Set<StoredTransaction> spends = null;
                            if (storedSource != null) {
                                spends = persistentBlocks.getSpendingTransactions(in.getSource());
                            }
                            return new DBReadResult(t.getID(), in.getSource(), storedSource, spends);
                        });
                    }
                }
                if (referred.put(op, referredTransaction) != null) {
                    throw LoggedHyperLedgerException.loggedInfo(log, "Mempool rejects " + t.getID() + " : attempts to reuse inputs.");
                }
            }

            for (int i = 0; i < dbread; ++i) {
                try {
                    DBReadResult result = resolver.take().get();

                    // checks must be in this thread since this holds the lock
                    if (result.transaction == null || !isOnTrunk(result.transaction, trunk::contains)) {
                        throw LoggedHyperLedgerException.loggedInfo(log, "Mempool rejects " + result.validating + " : refers to unknown input. "
                                + result.source);
                    }
                    if (isAnyOnTrunk(result.spends)) {
                        StoredTransaction firstSpend = oneOnTrunk(result.spends);
                        throw LoggedHyperLedgerException.loggedInfo(log, "Mempool rejects " + result.validating + " : refers to spent input. "
                                + result.source + " first spend " + firstSpend.getID());
                    }
                    if (referred.put(result.source, result.transaction) != null) {
                        throw LoggedHyperLedgerException.loggedInfo(log, "Mempool rejects " + result.transaction.getID() + " : attempts to reuse inputs.");
                    }
                } catch (ExecutionException e) {
                    throw LoggedHyperLedgerException.loggedError(log, e.getCause());
                } catch (InterruptedException e) {
                    throw LoggedHyperLedgerException.loggedError(log, e);
                }
            }

            try {
                validated = validatorChain.validateTransaction(t, getFullHeight(), referred);
            } catch (HyperLedgerException e) {
                throw LoggedHyperLedgerException.loggedInfo(log, "Mempool rejects " + t.getID() + " : " + e.getMessage());
            }

            // block validation does not depend on mempool
            // this write does not need write lock
            // simultaneous read of mempool is also not a concern as mempool delete
            // is only within write lock and double spend can not be added as add method checks sources
            mempool.add(validated);

            log.info("Mempool accepts {} size {} reads {} vt {} ms", t.getID(), mempool.size(), dbread,
                    stopwatch.stop().elapsed(TimeUnit.MILLISECONDS));

            if (!isDownloading())
                clientEventQueue.sendStoreEvent(new ClientEventQueue.TransactionAdded(validated));
            return validated;
        } finally {
            readWriteLock.readLock().unlock();
        }
    }

    @Override
    public HeaderStoredInfo addHeader(final Header h) throws HyperLedgerException {
        try {
            return singleThreadedExecutor.submit(() -> addHeaderSingleThreaded(h)).get();
        } catch (ExecutionException e) {
            throw LoggedHyperLedgerException.loggedError(log, e.getCause());
        } catch (InterruptedException e) {
            throw LoggedHyperLedgerException.loggedError(log, e);
        }
    }

    private HeaderStoredInfo addHeaderSingleThreaded(final Header h) throws HyperLedgerException {
        if (headers.containsKey(h.getID())) {
            throw LoggedHyperLedgerException.loggedInfo(log, "Rejected header " + h.getID() + " : duplicate.");
        }

        StoredHeader b = new StoredHeader(h, 0.0, 0);

        // do not care unconnected
        StoredHeader prev = headers.get(b.getPreviousID());
        if (prev == null) {
            throw LoggedHyperLedgerException.loggedWarn(log, "Rejected header " + b.getID() + " : unconnected.");
        }


        try {
            validatorChain.validateHeader(this, b);
        } catch (HyperLedgerException e) {
            throw LoggedHyperLedgerException.loggedWarn(log, "Rejected header " + b.getID() + " : " + e.getMessage());
        }

        List<StoredHeader> path = new ArrayList<>();
        path.add(b);
        // find path from join with trunk
        BID join = prev.getID();
        while (!trunk.contains(join)) {
            StoredHeader c = headers.get(join);
            path.add(c);
            join = c.getPreviousID();
        }
        Collections.reverse(path);

        // evaluate the path
        try {
            validatorChain.validatePath(this, headers.get(join), path);
        } catch (HyperLedgerException e) {
            throw LoggedHyperLedgerException.loggedWarn(log, "Rejected header " + b.getID() + " : " + e.getMessage());
        }

        Set<TID> resurrect = new HashSet<>();
        Set<TID> confirm = new HashSet<>();
        Set<TID> coinbases = new HashSet<>();
        List<BID> removed = new ArrayList<>();
        List<BID> added = new ArrayList<>();


        // reorg?
        BID spvTop = tops.spvTop;
        StoredHeader best = headers.get(spvTop);
        if (b.getPreviousID().equals(spvTop)) {
            added.add(b.getID());
            log.info("Accepted header " + b.getID() + " h: " + b.getHeight());
        } else if (StoredHeader.compareHeaders(best, b) < 0) {
            // remove old trunk until join
            while (!best.getID().equals(join)) {
                removed.add(best.getID());
                List<TID> blockContent = persistentBlocks.readBlockTIDList(best.getID());
                if (!blockContent.isEmpty()) {
                    // resurrect all including(!) coinbase (so remove dependency works thereafter)
                    resurrect.addAll(blockContent);
                    // transitive remove mempool dependency on destroyed coinbase
                    coinbases.add(blockContent.get(0));
                }
                best = headers.get(best.getPreviousID());
            }
            Collections.reverse(removed);

            // addTransaction new path
            for (StoredHeader blk : path) {
                added.add(blk.getID());
                // no need to resurrect confirmed on new trunk
                List<TID> blockContent = persistentBlocks.readBlockTIDList(blk.getID());
                resurrect.removeAll(blockContent);
                confirm.addAll(blockContent);
            }
            log.info("Accepted header " + b.getID() + " height: " + getSpvHeight());
        } else {
            log.info("Accepted orphan header " + b.getID() + " height: " + getSpvHeight());
        }
        HeaderStoredInfo info;
        try {
            readWriteLock.writeLock().lock();

            headers.put(b.getID(), b);

            // ensuring reading the spvTop and fullTop reflects the same state
            Tops oldTops = tops;
            BID newSpvTop = oldTops.spvTop;
            BID newFullTop = oldTops.fullTop;
            for (BID rh : removed) {
                if (trunk.remove(rh)) {
                    trunkList.removeFirst();
                    newSpvTop = trunkList.getFirst().getID();
                    if (persistentBlocks.hasBlock(newSpvTop)) {
                        newFullTop = newSpvTop;
                    }
                }
            }
            for (BID ah : added) {
                if (trunk.add(ah)) {
                    trunkList.addFirst(headers.get(ah));
                    newSpvTop = ah;
                    if (persistentBlocks.hasBlock(newSpvTop)) {
                        newFullTop = newSpvTop;
                    }
                }
            }
            tops = new Tops(newSpvTop, newFullTop);

            // resurrect
            for (TID hash : resurrect) {
                StoredTransaction t = persistentBlocks.readTransaction(hash);
                mempool.add(t);
            }
            // transitive remove mempool dependency on destroyed coinbase
            for (TID hash : coinbases) {
                mempool.remove(hash, true);
            }
            // remove confirmed on trunk (non-transitive) from mempool
            for (TID hash : confirm) {
                mempool.remove(hash, false);
            }
            info = new HeaderStoredInfo(getSpvHeight(), added, removed);
        } finally {
            readWriteLock.writeLock().unlock();
        }

        if (log.isDebugEnabled()) {
            if (removed.size() > 0) {
                StringBuilder builder = new StringBuilder();
                for (BID i : removed) {
                    builder.append(i.toString());
                    builder.append(" ");
                }
                log.info("Reorg removes header(s) " + builder.toString());
                if (added.size() > 1) {
                    builder = new StringBuilder();
                    for (BID i : added) {
                        builder.append(i.toString());
                        builder.append(" ");
                    }
                    log.info("Reorg adds headers(s) " + builder.toString());
                }
            }
        }

        if (!isDownloading())
            clientEventQueue.sendStoreEvent(new ClientEventQueue.HeaderAdded(info));
        return info;
    }

    private boolean isDownloading() {
        if (tops.fullTop == null)
            return true;
        return getFullHeight() < (getSpvHeight() - 100);
    }

    @Override
    public BlockStoredInfo addClientBlock(Block b) throws HyperLedgerException {
        try {
            BlockStoredInfo r = addBlock(b);
            outbox.sendP2PEvent(new CoreOutbox.BlockAdded(r));
            return r;
        } finally {
            log.info("Processed client block " + b.getID());
        }
    }

    private class ValidateBlockResult {
        List<BID> added;
        List<BID> removed;
        StoredBlock storedBlock;
        long validationTime;
    }

    @Override
    public BlockStoredInfo addBlock(final Block block) throws HyperLedgerException {
        try {
            return singleThreadedExecutor.submit(() -> {
                ValidateBlockResult validateResult = validate(block);
                return storeBlock(validateResult.added,
                        validateResult.removed,
                        validateResult.storedBlock,
                        validateResult.validationTime);
            }).get();
        } catch (ExecutionException e) {
            throw LoggedHyperLedgerException.loggedError(log, e.getCause());
        } catch (InterruptedException e) {
            throw LoggedHyperLedgerException.loggedError(log, e);
        }
    }

    public boolean validateBlock(final Block block) throws HyperLedgerException {
        try {
            return singleThreadedExecutor.submit(() -> {
                try {
                    validate(block);
                    return true;
                } catch (HyperLedgerException e) {
                    return false;
                }
            }).get();
        } catch (InterruptedException e) {
            throw LoggedHyperLedgerException.loggedError(log, e.getCause());
        } catch (ExecutionException e) {
            throw LoggedHyperLedgerException.loggedError(log, e);
        }

    }

    private ValidateBlockResult validate(final Block block) throws HyperLedgerException {
        ValidateBlockResult result = new ValidateBlockResult();

        if (persistentBlocks.hasBlock(block.getID())) {
            throw LoggedHyperLedgerException.loggedWarn(log, "Rejected block " + block.getID() + " : duplicate.");
        }

        if (!persistentBlocks.hasBlock(block.getPreviousID())) {
            throw LoggedHyperLedgerException.loggedWarn(log, "Rejected block " + block.getID() + " : unconnected.");
        }

        result.added = new ArrayList<>();
        result.removed = new ArrayList<>();

        addHeaderForBlock(block, result.added, result.removed);

        TrunkFilter validationTrunk = computeValidationTrunk(result.removed, block);

        Map<Outpoint, Transaction> referred = collectReferredOutputs(block, validationTrunk);

        result.storedBlock = createStoredBlock(block);
        Stopwatch stopwatch = Stopwatch.createStarted();
        try {
            result.storedBlock = validatorChain.validateBody(this, result.storedBlock, referred);
        } catch (HyperLedgerException e) {
            throw LoggedHyperLedgerException.loggedWarn(log, "Rejected block " + block.getID() + " : " + e.getMessage());
        }
        result.validationTime = stopwatch.stop().elapsed(TimeUnit.MILLISECONDS);

        return result;
    }

    private StoredBlock createStoredBlock(Block b) {
        List<StoredTransaction> txlist = new ArrayList<>(b.getTransactions().size());
        for (Transaction t : b.getTransactions()) {
            txlist.add(new StoredTransaction(t, 0));
        }
        return new StoredBlock(headers.get(b.getID()), txlist);
    }

    private BlockStoredInfo storeBlock(List<BID> added, List<BID> removed, StoredBlock b, long validationTime) throws HyperLedgerException {
        BlockStoredInfo info;
        PersistenceStatistics stats = null;
        try {
            readWriteLock.writeLock().lock();

            stats = persistentBlocks.writeBlock(b);

            for (StoredTransaction t : b.getTransactions()) {

                // cache must reflect db if hit. Filter on trunk view is at use.
                t.addBlock(b.getID());
                coinCache.add(t);

                // or be rather a miss if in doubt ...
                for (TransactionInput in : t.getInputs()) {
                    coinCache.remove(in.getSource());
                }
            }

            if (trunk.contains(b.getID())) {
                tops = new Tops(tops.spvTop, b.getID());
                // erase confirmed from mempool (not transitive)
                // erase double spends from mempool
                for (StoredTransaction t : b.getTransactions()) {
                    mempool.remove(t.getID(), false);
                    for (ValidatedTransaction c : mempool.getConflicts(t)) {
                        log.info("Confirmed double-spend of {}  by {}", c.getID(), t.getID());
                        for (ValidatedTransaction d : mempool.remove(c.getID(), true)) {
                            log.info("Dropping from mempool {}", d.getID());
                        }
                    }
                }


                added.add(b.getID());
            }
            info = new BlockStoredInfo(getFullHeight(), added, removed);
        } finally {
            readWriteLock.writeLock().unlock();
        }
        log.info("Stored  {} block  {} h: {} tx: {} mr: {} vt: {} prev: {} ms mempool: {} reads: {} rt: {} ms wt: {} ms", (!trunk.contains(b.getID()) ? "orphan " : ""), b.getID(), getFullHeight(),
                b.getTransactions().size(), b.getMerkleRoot(), validationTime, b.getPreviousID(), mempool.size(), stats.getnReads(), stats.getReadTime(), stats.getWriteTime());

        if (!isDownloading())
            clientEventQueue.sendStoreEvent(new ClientEventQueue.BlockAdded(info));

        callBlockListeners(info);
        return info;
    }

    @Override
    public boolean addMiscData(BID id, byte[] data) throws HyperLedgerException {
        try {
            return singleThreadedExecutor.submit(() -> {
                if (!headers.containsKey(id)) {
                    throw LoggedHyperLedgerException.loggedError(log, "Misc data for nonexistant blockheader: " + id);
                }
                readWriteLock.writeLock().lock();
                try {
                    return persistentBlocks.writeMisc(id, data);
                } finally {
                    readWriteLock.writeLock().unlock();
                }
            }).get();
        } catch (InterruptedException e) {
            throw LoggedHyperLedgerException.loggedError(log, e);
        } catch (ExecutionException e) {
            throw LoggedHyperLedgerException.loggedError(log, e.getCause());
        }
    }

    private boolean isPrunable(Transaction t, int blockHeight, int checkHeightLimit) throws HyperLedgerException {
        if (blockHeight > checkHeightLimit) return false;
        return isPrunableThroughHops(t, checkHeightLimit, 2);
    }

    // only those transactions can be pruned which spending transaction's spending transaction's containing
    // block's hight is below the  checkHeightLimit.
    private boolean isPrunableThroughHops(Transaction t, int checkHeightLimit, int hopCount) throws HyperLedgerException {
        for (Coin coin : t.getCoins()) {
            Outpoint outpoint = coin.getOutpoint();
            StoredTransaction spendingTransaction = oneOnTrunk(persistentBlocks.getSpendingTransactions(outpoint));
            if (spendingTransaction == null || getMaxHeightOfContainingBlocks(spendingTransaction) > checkHeightLimit) {
                return false;
            }
            if (hopCount > 1) {
                if (!isPrunableThroughHops(spendingTransaction, checkHeightLimit, hopCount - 1)) {
                    return false;
                }
            }
        }
        return true;
    }

    private int getMaxHeightOfContainingBlocks(StoredTransaction t) throws HyperLedgerException {
        int maxHeight = -1;
        for (BID blockId : t.getBlocks()) {
            maxHeight = Math.max(maxHeight, persistentBlocks.readHeader(blockId).getHeight());
        }
        return maxHeight;
    }

    @Override
    public void standalonePruneBlock(BID id, int toHeight) throws HyperLedgerException {
        try {
            singleThreadedExecutor.submit(new PrioritizedOrderedCallable<Void>(0, 0) {
                @Override
                public Void call() throws Exception {
                    pruneBlock(id, toHeight);
                    return null;
                }
            }).get();
        } catch (InterruptedException e) {
            throw new HyperLedgerException(e);
        } catch (ExecutionException e) {
            throw new HyperLedgerException(e.getCause());
        }
    }

    private void pruneBlock(BID h, int checkHeightLimit) throws HyperLedgerException {
        StoredBlock block = null;
        int prunedCount = 0;
        try {
            block = persistentBlocks.readBlock(h);
            List<MerkleTreeNode> pruned = new ArrayList<>();
            for (MerkleTreeNode n : block.getMerkleTreeNodes()) {
                if (n instanceof PrunedNode) {
                    pruned.add(n);
                } else {
                    Transaction t = (Transaction) n;
                    if (isPrunable(t, block.getHeight(), checkHeightLimit)) {
                        log.info("Pruning transaction {} in block {} at height {} (with limit {})", t.getID(), block.getID(), block.getHeight(), checkHeightLimit);
                        pruned.add(new PrunedNode(t.getID(), 0));
                        prunedCount++;
                    } else {
                        pruned.add(t);
                    }
                }
            }

            Hash oldMerkleRoot = block.getMerkleRoot();

            if (prunedCount > 0) {
                StoredBlock prunedBlock = new StoredBlock(block.getHeader(), MerkleTree.compress(pruned));
                if (!prunedBlock.getMerkleRoot().equals(oldMerkleRoot)) {
                    log.error("Merkle root mismatch between pruned and original block {} at height {}. Keeping the original block", block.getID(), block.getHeight());
                    return;
                }

                try {
                    readWriteLock.writeLock().lock();

                    persistentBlocks.updateBlock(prunedBlock);
                } finally {
                    readWriteLock.writeLock().unlock();
                }
            }
            log.info("Pruned {} transactions out of {} in block {} at height {} (with limit {})", prunedCount, block.getMerkleTreeNodes().size(), block.getID(), block.getHeight(), checkHeightLimit);
        } catch (HyperLedgerException e) {
            log.warn("Exception while pruning block {} at height {} (with limit {}). Exception is: ", block.getID(), block.getHeight(), checkHeightLimit, e);
        }
    }


    // BIP30 exception list is Bitcoin specific, and is unlikely to matter in other chain.
    // Reuse of transaction ids have to be disallowed.
    private final List<BID> dirtyBIP30Hacks = Arrays.asList(
            new BID("00000000000a4d0a398161ffc163c503763b1f4360639393e0e4c8e300e0caec"),
            new BID("00000000000743f190a18c5577a3c2d2a1f610ae9601ac046a38084ccb7cd721")
    );

    private Map<Outpoint, Transaction> collectReferredOutputs(Block b, TrunkFilter validationTrunk) throws HyperLedgerException {
        Map<Outpoint, Transaction> inBlock = new HashMap<>();
        Map<Outpoint, Transaction> referred = new HashMap<>();


        CompletionService<DBReadResult> resolver = new ExecutorCompletionService<>(readerPool);

        int dbread = 0;
        for (Transaction t : b.getTransactions()) {
            // Assuming that TID can only ever collide with coinbase transactions.
            if (t.getInput(0).getSourceTransactionID().equals(TID.INVALID) && !dirtyBIP30Hacks.contains(b.getID()) && !isUnique(t.getID(), validationTrunk))
                throw LoggedHyperLedgerException.loggedError(log, "Transaction would not be unique if referred " + t.getID());
            for (TransactionInput in : t.getInputs()) {
                if (!in.getSourceTransactionID().equals(TID.INVALID)) {
                    Outpoint outpoint = new Outpoint(in.getSourceTransactionID(), in.getOutputIndex());
                    Transaction referredTransaction = inBlock.remove(outpoint);
                    if (referredTransaction == null) {
                        referredTransaction = coinCache.getTransaction(outpoint);
                        if (referredTransaction == null || !isOnTrunk((StoredTransaction) referredTransaction, validationTrunk)) {
                            ++dbread;
                            resolver.submit(() -> {
                                StoredTransaction storedSource = persistentBlocks.readTransaction(in.getSource());
                                Set<StoredTransaction> spends = null;
                                if (storedSource != null) {
                                    spends = persistentBlocks.getSpendingTransactions(in.getSource());
                                }
                                return new DBReadResult(t.getID(), in.getSource(), storedSource, spends);
                            });
                        }
                    }
                    if (referred.put(outpoint, referredTransaction) != null) {
                        throw LoggedHyperLedgerException.loggedError(log, "Transaction attempts to reuse inputs " + t.getID());
                    }
                }
            }
            for (Coin o : t.getCoins()) {
                inBlock.put(o.getOutpoint(), t);
            }
        }
        for (int i = 0; i < dbread; ++i) {
            try {
                DBReadResult result = resolver.take().get();

                // checks must be in this thread since this holds the lock
                if (result.transaction == null || !isOnTrunk(result.transaction, validationTrunk)
                        || isAnyOnTrunk(result.spends, validationTrunk)) {
                    throw LoggedHyperLedgerException.loggedError(log, "Block " + b.getID() + " refers to unknown or spent input " + result.source +
                            "] from " + result.validating);
                }
                if (referred.put(result.source, result.transaction) != null) {
                    throw LoggedHyperLedgerException.loggedError(log, "Transaction attempts to reuse inputs " + result.transaction.getID());
                }
            } catch (ExecutionException e) {
                throw LoggedHyperLedgerException.loggedError(log, e.getCause());
            } catch (InterruptedException e) {
                throw LoggedHyperLedgerException.loggedError(log, e);
            }
        }

        return referred;
    }

    private TrunkFilter computeValidationTrunk(List<BID> removed, Block b) {
        DeltaHashSet validationTrunk = new DeltaHashSet(trunk);

        // addTransaction hashes that lead to join point from here
        BID join = b.getPreviousID();
        while (!trunk.contains(join)) {
            validationTrunk.add(join);
            join = headers.get(join).getPreviousID();
        }

        // remove hashes that led to current fullTop
        BID tp = tops.fullTop;
        while (!tp.equals(join)) {
            validationTrunk.remove(tp);
            tp = headers.get(tp).getPreviousID();
        }
        Collections.reverse(removed);
        return validationTrunk;
    }

    private void addHeaderForBlock(Block inb, List<BID> added, List<BID> removed) throws HyperLedgerException {
        if (!headers.containsKey(inb.getID())) {
            HeaderStoredInfo headerStored = addHeaderSingleThreaded(inb.getHeader());
            for (BID s : headerStored.getAddedToTrunk()) {
                if (!persistentBlocks.hasBlock(s))
                    break;
                added.add(s);
            }

            for (BID s : headerStored.getRemovedFromFrunk()) {
                if (persistentBlocks.hasBlock(s))
                    removed.add(s);
            }
        }
    }

    private void callBlockListeners(BlockStoredInfo info) {
        for (BlockListener listener : blockListeners) {
            listener.blockStored(info);
        }
    }

    public void addBlockListener(BlockListener listener) {
        blockListeners.add(listener);
    }

    public void removeBlockListener(BlockListener listener) {
        blockListeners.remove(listener);
    }
}
