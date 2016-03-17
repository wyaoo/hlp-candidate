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

import org.apache.commons.math3.stat.descriptive.StatisticalSummary;
import org.apache.commons.math3.stat.descriptive.SummaryStatistics;
import org.hyperledger.common.MasterPrivateKey;
import org.hyperledger.common.MasterPublicKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.stream.LongStream.range;
import static java.util.stream.LongStream.rangeClosed;

/**
 * This class enables the performant generation and storage of private-public key
 * pairs
 */
public class KeyPairGenerator {


    private static final Logger log = LoggerFactory.getLogger(KeyPairGenerator.class);

    /**
     * The number of key pairs being generated per thread - this constant is used
     * only in the sync-free version
     */
    private static final int PER_THREAD_BLOCK_SIZE = 1000;

    /**
     * Drives how frequently should we log the creation of key pairs
     */
    private static final long LOGGING_FREQUENCY_FOR_KEYPAIRS = 2000;

    private final BufferedWriter bufferedWriter;

    private final ForkJoinPool executorService;

    private final SummaryStatistics statistics = new SummaryStatistics();

    private final int generationBlockSize;

    /**
     * Fully initializes the object by allocating the extra ForkJoin thread pool etc
     *
     * @param threadCount  the number of threads to be used as a maximum
     *                     (depending on the algorithm being used it might
     *                     be less but not more)
     * @param outputStream the stream to where the component should write its output
     */
    public KeyPairGenerator(final int threadCount, final OutputStream outputStream) {
        bufferedWriter = new BufferedWriter(new OutputStreamWriter(outputStream));
        executorService = new ForkJoinPool(threadCount);
        generationBlockSize = threadCount * PER_THREAD_BLOCK_SIZE;
    }

    /**
     * This version of the generation uses 2 levels of java 8 streams:
     * 1) at the top there is a serial stream, summarizing the statistics and
     * persisting into the output stream happen at this level
     * 2) at the lower level there is a parallel stream created by createKeyPairBlockStream
     * doing the generation and serialization in parallel
     * <p>
     *
     * @param countToGenerate the number of key pairs to be generated
     */
    public void generate(final long countToGenerate) {
        log.info("Starting to generate {} key pairs using the two layered stream approach", countToGenerate);
        // at this level the processing is sequential in order to write the results into the output
        // stream without old school synchronisation
        final long blockCount = countToGenerate / generationBlockSize + 1;

        rangeClosed(0, blockCount).mapToObj(blockIndex -> {
            log.info("Starting to work on block {} out of {}", blockIndex, blockCount);
            final long upperBound = Math.min(countToGenerate, (blockIndex + 1) * generationBlockSize);
            final long blockSize = upperBound - blockIndex * generationBlockSize;
            return createKeyPairBlock(blockSize);
        }).forEach(generatedPairs -> {
                    generatedPairs.forEach(this::persistKeyPair);
                }
        );
        flushWriter();
        logDurationStatistics();
    }

    /**
     * This version of the generation uses a single parallel stream to generate
     * the key pairs and executes the statistics gathering and the handling of
     * the output stream in an explicite synchronized block.
     *
     * @param countToGenerate the number of key pairs to generate
     */
    public void generateSync(final long countToGenerate) {
        log.info("Starting to generate {} key pairs using the single stream approach", countToGenerate);
        final Runnable fullGeneration = () -> createKeyPairBlockStream(countToGenerate).forEach(generatedPair -> {
                    synchronized (bufferedWriter) {
                        persistKeyPair(generatedPair);
                    }
                }

        );
        // executing this stream definition on the custon thread pool in order
        // to guarantee the number of threads being used
        executorService.submit(fullGeneration).join();
        flushWriter();
        logDurationStatistics();
    }

    /**
     * Returns a read-only view of the statistics captured during generation
     *
     * @return the captured generation statistics
     */
    public StatisticalSummary getGenerationStatistics() {
        return statistics;
    }

    private void flushWriter() {
        try {
            bufferedWriter.flush();
        } catch (final IOException e) {
            throw new RuntimeException("Failed to flush the output writer", e);
        }
    }

    /**
     * This private method is being used by both versions of the generation
     * algorithms implemented by this class It creates a parallel stream being
     * capable of generating key pairs also doing the serialization in parallel.
     *
     * @param size the length of the parallel stream to be created
     * @return a parallel stream ready to be executed
     */
    private Stream<KeyPair> createKeyPairBlockStream(final long size) {
        return range(0, size).parallel().mapToObj(index -> {
            final long startTime = System.nanoTime();
            final MasterPrivateKey privateKey = MasterPrivateKey.createNew();
            final MasterPublicKey publicKey = privateKey.getMasterPublic();
            return new KeyPair(
                    System.nanoTime() - startTime,
                    privateKey.serialize(true),
                    publicKey.serialize(true));
        });
    }

    /**
     * This private method creates a list containing size amount of key pairs.
     * The returned data structure is guaranteed to be safely initialized by the
     * stream framework
     *
     * @param size the number of key pairs to be generated
     * @return a thread safe list containing the generated key pairs
     */
    private List<KeyPair> createKeyPairBlock(final long size) {
        try {
            final Callable<List<KeyPair>> keyGeneration =
                    () -> createKeyPairBlockStream(size).collect(Collectors.toList());
            return executorService.submit(keyGeneration).get();
        } catch (final ExecutionException e) {
            throw new RuntimeException("The parallel execution has failed in the fork-join framework", e);
        } catch (final InterruptedException e) {
            throw new RuntimeException("An executor thread got interrupted while executing the generation", e);
        }
    }


    private void persistKeyPair(final KeyPair keyPair) {
        try {
            bufferedWriter.write(keyPair.privateKey);
            bufferedWriter.newLine();
            bufferedWriter.write(keyPair.publicKey);
            bufferedWriter.newLine();
            statistics.addValue(keyPair.duration);
            if (statistics.getN() % LOGGING_FREQUENCY_FOR_KEYPAIRS == 0) {
                log.info("Generated and persisted {} key pairs", statistics.getN());
            }
        } catch (final IOException e) {
            throw new RuntimeException("An error has occurred while writing to the output stream", e);
        }
    }

    private void logDurationStatistics() {
        log.info("Captured the following statistics regarding the generation durations:");
        log.info(statistics.toString());
    }

    /**
     * An immutable internal data structure used to exchange data between stages of the
     * processing
     */
    static class KeyPair {

        final long duration;

        final String privateKey;

        final String publicKey;

        KeyPair(final long duration, final String privateKey, final String publicKey) {
            this.duration = duration;
            this.privateKey = privateKey;
            this.publicKey = publicKey;
        }

    }

}
