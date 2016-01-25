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
package org.hyperledger.perftest;

import com.google.common.base.Stopwatch;
import org.hyperledger.account.BaseAccount;
import org.hyperledger.account.BaseTransactionFactory;
import org.hyperledger.account.KeyListChain;
import org.hyperledger.account.TransactionFactory;
import org.hyperledger.api.APIBlock;
import org.hyperledger.api.BCSAPI;
import org.hyperledger.common.BID;
import org.hyperledger.common.PrivateKey;
import org.hyperledger.common.Transaction;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class SendTransactionThroughputMeasure extends BaseMeasure {
    private final BCSAPI api;
    private int threadCount;
    private List<ArrayList<Transaction>> txs;

    public SendTransactionThroughputMeasure(BCSAPI api, int rounds, int count, int threadCount) {
        super(rounds, count);
        this.api = api;
        this.threadCount = threadCount;
    }

    @Override
    public void setup() throws Exception {
        PrivateKey key = PrivateKey.parseWIF("Kxj5wXRXPxVZScsHkK6Dwo2k7enphcW9wWidvZ93wTALHDXjDo2U");
        KeyListChain keyChain = new KeyListChain(key);
        BaseAccount account = new BaseAccount(keyChain);
        api.mine(keyChain.getNextReceiverAddress());
        BID topBlockId = api.getBlockIds(null, 1).idList.get(0);
        APIBlock topBlock = api.getBlock(topBlockId);
        account.sync(api);

        Transaction tx = topBlock.getTransaction(0);

        txs = new ArrayList<>(getRounds());
        for (int j = 0; j < getRounds(); j++) {
            System.out.println("Preparing round " + j);
            ArrayList<Transaction> txsForOneRound = new ArrayList<>(getCount());
            txs.add(txsForOneRound);
            for (int i = 0; i < getCount(); i++) {

                TransactionFactory tf = new BaseTransactionFactory(account);
                tx = tf.propose(key.getAddress(), 5000000000L - BaseTransactionFactory.MINIMUM_FEE).sign(keyChain);
                txsForOneRound.add(tx);
            }
        }
    }

    @Override
    protected List<Long> measure() throws Exception {
        if (threadCount <= 1) {
            return measureSingleThreaded();
        } else {
            return measureMultiThreaded();
        }
    }

    private List<Long> measureSingleThreaded() throws Exception {
        List<Long> results = new ArrayList<>(getRounds());
        for (int i = 0; i < getRounds(); i++) {
            System.out.println("Measuring round " + i);
            List<Transaction> txsForOneRound = txs.get(i);
            System.gc();
            System.gc();
            System.gc();
            System.gc();
            System.gc();
            Stopwatch watch = Stopwatch.createStarted();
            for (Transaction tx : txsForOneRound) {
                api.sendTransaction(tx);
            }
            watch.stop();
            results.add(watch.elapsed(TimeUnit.MILLISECONDS));
        }
        return results;
    }

    // It is not in use yet as the transactions are chained, so they must be sent in order
    private List<Long> measureMultiThreaded() throws Exception {
        List<Long> results = new ArrayList<>(getRounds());
        for (int i = 0; i < getRounds(); i++) {
            ArrayList<Transaction> txsForOneRound = txs.get(i);
            List<Thread> threads = new ArrayList<>(threadCount);
            List<Worker> workers = new ArrayList<>(threadCount);
            CountDownLatch startSignal = new CountDownLatch(1);
            CountDownLatch doneSignal = new CountDownLatch(threadCount);
            for (int t = 0; t < threadCount; t++) {
                Worker worker = new Worker(t, txsForOneRound, startSignal, doneSignal);
                workers.add(worker);
                threads.add(new Thread(worker));
            }
            for (Thread thread : threads) {
                thread.start();
            }
            Thread.sleep(1000);
            startSignal.countDown();
            doneSignal.await();
            results.add(sumResults(workers));
        }
        return results;
    }

    private long sumResults(List<Worker> workers) throws Exception {
        long result = 0;
        for (Worker worker : workers) {
            if (result == Long.MIN_VALUE) {
                throw new Exception("Failure in executing a worker");
            }
            result += worker.result;
        }
        return result;
    }

    class Worker implements Runnable {
        private final ArrayList<Transaction> txs;
        private final CountDownLatch startSignal;
        private final CountDownLatch doneSignal;
        private final int threadNumber;
        long result = 0;

        public Worker(int threadNumber, ArrayList<Transaction> txs, CountDownLatch startSignal, CountDownLatch doneSignal) {
            this.threadNumber = threadNumber;
            this.txs = txs;
            this.startSignal = startSignal;
            this.doneSignal = doneSignal;
        }

        @Override
        public void run() {
            try {
                startSignal.await();
                Stopwatch watch = Stopwatch.createStarted();
                for (int i = threadNumber; i < getCount(); i += threadCount) {
                    api.sendTransaction(txs.get(i));
                }
                watch.stop();
                result = watch.elapsed(TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                e.printStackTrace();
                result = Long.MIN_VALUE;
            } finally {
                doneSignal.countDown();
            }
        }
    }
}
