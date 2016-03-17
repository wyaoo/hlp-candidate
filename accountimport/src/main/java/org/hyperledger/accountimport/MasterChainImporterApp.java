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

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.Security;

/**
 * Runnable class enabling the importing of private-public key pairs and
 * generating chains using them
 * This class is responsible for interpreting the commandline arguments and
 * communicates using std in and out
 */
public class MasterChainImporterApp {

    private static final Logger log = LoggerFactory.getLogger(MasterChainImporterApp.class);

    static final int SUCCESS = 0;

    static final int RUNTIME_EXCEPTION = 1;

    static final int IO_EXCEPTION = 2;

    static final int ILLEGAL_ARGUMENT_EXCEPTION = 3;

    static {
        Security.addProvider(new BouncyCastleProvider());
    }

    public static void main(final String[] args) {
        /*
        Usage of arguments:
        - 1. argument: the number of key pairs to import in decimal integer
            format [mandatory]
        - 2. argument: the file name to load the keys from [mandatory]
        - 3. the number of threads to be used [mandatory]
        - 4. 'measureMemory' or missing if memory measurement should be executed
         */

        // exiting explicitly so that i can specify the return code - can be
        // good for wrapper scripts
        System.exit(new MasterChainImporterApp().mainInternal(args, MasterChainImporterApp::compactMemory));
    }

    int mainInternal(final String[] args, final MasterChainImporter.MemoryCompacter memoryCompacter) {
        try {
            final CommandlineArguments arguments = checkAndExtractArguments(args);
            System.out.println("Will run with the following arguments:");
            System.out.println(arguments.toString());

            // closing and flushing the stream is ensured by the try-catch
            try (final InputStream inputStream = new FileInputStream(arguments.filePath)) {
                final MasterChainImporter importer = new MasterChainImporter(inputStream, arguments.threadCount, memoryCompacter);
                final long startingTime = System.currentTimeMillis();
                final MasterChainImporter.MeasurementResults results = importer.importChain(arguments.countToImport,
                        arguments.measureMemory);
                System.out.println("The importing has executed successfully, the execution took " +
                        (System.currentTimeMillis() - startingTime) + "ms");
                System.out.println("The gathered statistics are:");
                System.out.println(results.toString());
                return SUCCESS;
            } catch (final RuntimeException e) {
                log.error("Caught runtime exception while running the import", e);
                System.err.println(
                        "An unknown error has occurred during the import," +
                                " for more information please check the logs");
                return RUNTIME_EXCEPTION;
            } catch (final IOException e) {
                log.error("Caught IO exception while running the import", e);
                System.err.println("Either the specified path to use is invalid or the file is not accessible");
                return IO_EXCEPTION;
            }
        } catch (final IllegalArgumentException e) {
            System.err.println(e.getMessage());
            printUsage();
            return ILLEGAL_ARGUMENT_EXCEPTION;
        }
    }

    private static void compactMemory() {
        for (int i = 0; i < 5; i++) {
            Runtime.getRuntime().gc();
            try {
                Thread.sleep(500);
            } catch (final InterruptedException e) {
                // no relevance
            }
        }
    }

    CommandlineArguments checkAndExtractArguments(final String[] args) {
        try {
            if (args.length != 3 && (args.length == 4 && !args[3].equals("measureMemory"))) {
                throw new IllegalArgumentException("The number of arguments is not 3 (no memory measurement) or " +
                        " 4 (run memory measurement) as expected");
            }
            final long countToImport = Long.parseLong(args[0]);
            if (countToImport < 0) {
                throw new IllegalArgumentException("The number of key pairs to import can't be negative");
            }
            final String filePath = args[1];
            final int threadCount = Integer.parseInt(args[2]);
            if (threadCount <= 0) {
                throw new IllegalArgumentException("The number of threads to be used must be bigger than 0");
            }
            return new CommandlineArguments(countToImport, filePath, threadCount, args.length == 4);
        } catch (final NumberFormatException e) {
            throw new IllegalArgumentException("A number field had invalid format", e);
        }
    }

    private void printUsage() {
        System.out.println("Usage of arguments:");
        System.out.println("[[command]] <<number of key pars to import eg 1000>> " +
                "<<the file path to load the data from>> <<the number of threads to use eg. 8>>" +
                " <<'measureMemory' if should measure memory or missing if not>>");
    }

    /**
     * Class representing the provided commandline arguments
     */
    static class CommandlineArguments {

        final long countToImport;

        final String filePath;

        final int threadCount;

        final boolean measureMemory;

        public CommandlineArguments(final long countToImport, final String filePath, final int threadCount,
                                    final boolean measureMemory) {
            this.countToImport = countToImport;
            this.filePath = filePath;
            this.threadCount = threadCount;
            this.measureMemory = measureMemory;
        }

        @Override
        public String toString() {
            final StringBuilder builder = new StringBuilder();
            builder.append("Number of pairs to import: " + countToImport);
            builder.append("\n");
            builder.append("File path to store the data: " + filePath);
            builder.append("\n");
            builder.append("Maximum number of threads to be used: " + threadCount);
            builder.append("\n");
            builder.append("Will measure memory usage: " + measureMemory);
            builder.append("\n");
            return builder.toString();
        }
    }
}
