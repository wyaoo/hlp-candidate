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
package org.hyperledger.accountimport;

import org.hyperledger.account.MasterPrivateChain;
import org.hyperledger.account.MasterPublicChain;
import org.hyperledger.common.HyperLedgerException;
import org.hyperledger.common.MasterPrivateKey;
import org.hyperledger.common.MasterPublicKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.StreamSupport;

/**
 * This class enables the measurement of runtime characteristics of importing
 * private and public master chains
 */
public class MasterChainImporter {

    private static final Logger log = LoggerFactory.getLogger(KeyPairGenerator.class);

    private static final int IMPORT_LOGGING_FREQUENCY = 2000;

    private static final int MAX_ARRAY_SIZE = 100000;

    private final InputStream stream;

    private final ForkJoinPool pool;

    // volatile is needed for these attributes to ensure memory consistency
    // accross multiple threads without using locks
    private volatile MasterPrivateChain[][] privateChains;

    private volatile MasterPublicChain[][] publicChains;

    private final MemoryCompacter memoryCompacter;

    public MasterChainImporter(final InputStream stream, final int maxThreadCount, final MemoryCompacter memoryCompacter) {
        this.stream = stream;
        pool = new ForkJoinPool(maxThreadCount);
        this.memoryCompacter = memoryCompacter;
    }

    private Callable<LongSummaryStatistics> buildUpStream(final long maxCount, final boolean measureMemoryUsage) {
        final KeyPairIterator iterator = new KeyPairIterator(stream);
        return () -> StreamSupport.stream(Spliterators.spliteratorUnknownSize(
                iterator, Spliterator.ORDERED | Spliterator.NONNULL), false)
                .limit(maxCount)
                .parallel().mapToLong(item -> {
                    try {
                        final long startTime = System.nanoTime();
                        final MasterPrivateKey privateKey = MasterPrivateKey.parse(item.privateKey);
                        final MasterPublicKey publicKey = MasterPublicKey.parse(item.publicKey);
                        final MasterPrivateChain privateChain = new MasterPrivateChain(privateKey);
                        final MasterPublicChain publicChain = new MasterPublicChain(publicKey);
                        final long duration = System.nanoTime() - startTime;
                        if (measureMemoryUsage) {
                            storeChainPair(item.index, privateChain, publicChain);
                        }
                        return duration;
                    } catch (final HyperLedgerException e) {
                        throw new RuntimeException(e);
                    }
                }).summaryStatistics();
    }

    /**
     * Runs a chain pair importing up to the maxCount limit potentially also
     * measuring the memory usage
     *
     * @param maxCount the maximum number of key pairs to process
     * @param measureMemoryUsage true if the memory usage should be measured
     * @return the measured statistics
     */
    public MeasurementResults importChain(final long maxCount, final boolean measureMemoryUsage) {
        log.info("Starting to import {} key pairs from the input stream", maxCount);
        long initialUsedMemory = 0;
        if (measureMemoryUsage) {
            initializeMemoryStorage(maxCount);
            memoryCompacter.doCompact();
            initialUsedMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        }

        final Callable<LongSummaryStatistics> stream = buildUpStream(maxCount, measureMemoryUsage);

        final LongSummaryStatistics stats = pool.submit(stream).join();

        log.info("Importing has finished with the following statistics");
        log.info(stats.toString());

        if (measureMemoryUsage) {
            memoryCompacter.doCompact();
            final long actualUsedMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
            final long relevantUsedMemory = actualUsedMemory - initialUsedMemory;
            final long usedMemoryPerChainPair = relevantUsedMemory / maxCount;
            log.info(
                    "The JVM started with {} bytes of used memory, after running the test the" +
                            " JVM uses {} bytes more memory, {} bytes per chain pair",
                    initialUsedMemory, relevantUsedMemory, usedMemoryPerChainPair);
            return new MeasurementResults(stats, usedMemoryPerChainPair);
        } else {
            return new MeasurementResults(stats, 0);
        }

    }

    private void initializeMemoryStorage(final long count) {
        log.info("Allocating arrays to store data for memory usage measurements");
        final int level2ArrayCount = (int) (count / MAX_ARRAY_SIZE + 1);
        log.info("Will have {} level 2 arrays", level2ArrayCount);
        privateChains = new MasterPrivateChain[level2ArrayCount][];
        publicChains = new MasterPublicChain[level2ArrayCount][];
        for (int i = 0; i < level2ArrayCount; i++) {
            final int actualSize = Math.min((int) count, MAX_ARRAY_SIZE * (i + 1)) - MAX_ARRAY_SIZE * i;
            log.info("For the {}th level 2 array will have {} items", i, actualSize);
            privateChains[i] = new MasterPrivateChain[actualSize];
            publicChains[i] = new MasterPublicChain[actualSize];
        }
    }

    private void storeChainPair(final long index, final MasterPrivateChain privateChain,
                                final MasterPublicChain publicChain) {
        final int level1Index = (int) (index / MAX_ARRAY_SIZE);
        final int level2Index = (int) (index % MAX_ARRAY_SIZE);
        privateChains[level1Index][level2Index] = privateChain;
        publicChains[level1Index][level2Index] = publicChain;
    }

    /**
     * Class used to load serialized key pairs into memory
     */
    static class KeyPair {

        final long index;

        final String privateKey;

        final String publicKey;

        public KeyPair(final long index, final String privateKey, final String publicKey) {
            this.index = index;
            this.privateKey = privateKey;
            this.publicKey = publicKey;
        }

        @Override
        public String toString() {
            return "Private key: " + privateKey + ", public key: " + publicKey;
        }
    }

    /**
     * An iterator used to iterate through an input stream containing serialized
     * key pairs. The corresponding key pair is stored as subsequent lines
     * (This implementation is following the structure of the iterator found
     * in {@link java.io.BufferedReader}).lines())
     */
    static class KeyPairIterator implements Iterator<KeyPair> {

        private final BufferedReader reader;

        private long lineNumber = 0;

        KeyPair nextKeyPair = null;

        public KeyPairIterator(final InputStream stream) {
            reader = new BufferedReader(new InputStreamReader(stream));
        }

        @Override
        public boolean hasNext() {
            if (nextKeyPair != null) {
                return true;
            } else {
                try {
                    final String privateStr = getNextLine();
                    final String publicStr = getNextLine();
                    if (privateStr != null) {
                        if (publicStr == null || !privateStr.startsWith("xprv") || !publicStr.startsWith("xpub")) {
                            throw new RuntimeException("Illegal format by lines " + lineNumber);
                        }
                        nextKeyPair = new KeyPair(lineNumber / 2 - 1, privateStr, publicStr);
                    }
                    return (nextKeyPair != null);
                } catch (final IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }

        private String getNextLine() throws IOException {
            final String nextLine = reader.readLine();
            if (nextLine != null) {
                if (lineNumber % IMPORT_LOGGING_FREQUENCY == 0) {
                    log.info("Have read {} lines from the input file", lineNumber);
                }
                lineNumber++;
            }
            return nextLine;
        }

        @Override
        public KeyPair next() {
            if (nextKeyPair != null || hasNext()) {
                final KeyPair keyPair = nextKeyPair;
                nextKeyPair = null;
                return keyPair;
            } else {
                throw new NoSuchElementException();
            }
        }
    }

    public static class MeasurementResults {

        public final LongSummaryStatistics timeStatistics;

        public final long memoryPerChainPair;

        public MeasurementResults(final LongSummaryStatistics timeStatistics, final long memoryPerChainPair) {
            this.timeStatistics = timeStatistics;
            this.memoryPerChainPair = memoryPerChainPair;
        }

        @Override
        public String toString() {
            final StringBuilder builder = new StringBuilder();
            builder.append("Timing statistics: ");
            builder.append(timeStatistics.toString());
            builder.append('\n');
            if (memoryPerChainPair != 0) {
                builder.append("Memory usage per chain pair: ");
                builder.append(memoryPerChainPair);
            }
            return builder.toString();
        }
    }

    /**
     * An interface through which the component wil request cleaning up the
     * memory as much as possible
     */
    interface MemoryCompacter {

        void doCompact();

    }
}
