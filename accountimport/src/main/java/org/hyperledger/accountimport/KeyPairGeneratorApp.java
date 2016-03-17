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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Runnable class enabling the generation of private-public key pairs.
 * This class is responsible for interpreting the commandline arguments and
 * communicates using std in and out
 */
public class KeyPairGeneratorApp {

    private static final Logger log = LoggerFactory.getLogger(KeyPairGeneratorApp.class);

    static final int SUCCESS = 0;

    static final int RUNTIME_EXCEPTION = 1;

    static final int IO_EXCEPTION = 2;

    static final int ILLEGAL_ARGUMENT_EXCEPTION = 3;

    public static void main(final String[] args) {
        /*
        Usage of arguments:
        - 1. argument: the number of key pairs to generate in decimal integer
            format [mandatory]
        - 2. argument: the file name to store the output into [mandatory]
        - 3. the number of threads to be used [mandatory]
         */

        // exiting explicitly so that i can specify the return code - can be
        // good for wrapper scripts
        System.exit(new KeyPairGeneratorApp().mainInternal(args));
    }

    int mainInternal(final String[] args) {
        try {
            final CommandlineArguments arguments = checkAndExtractArguments(args);
            System.out.println("Will run with the following arguments:");
            System.out.println(arguments.toString());

            // closing and flushing the stream is ensured by the try-catch
            try (final OutputStream outputStream = new FileOutputStream(arguments.filePath)) {
                final KeyPairGenerator generator = new KeyPairGenerator(arguments.threadCount, outputStream);
                final long startingTime = System.currentTimeMillis();
                generator.generate(arguments.countToGenerate);
                System.out.println("The generation has executed successfully, the execution took " +
                        (System.currentTimeMillis() - startingTime) + "ms");
                return SUCCESS;
            } catch (final RuntimeException e) {
                log.error("Caught runtime exception while running generation", e);
                System.err.println(
                        "An unknown error has occurred during the generation," +
                                " for more information please check the logs");
                return RUNTIME_EXCEPTION;
            } catch (final IOException e) {
                log.error("Caught IO exception while running generation", e);
                System.err.println("Either the specified path to use is invalid or the file is not accessible");
                return IO_EXCEPTION;
            }
        } catch (final IllegalArgumentException e) {
            System.err.println(e.getMessage());
            printUsage();
            return ILLEGAL_ARGUMENT_EXCEPTION;
        }
    }

    CommandlineArguments checkAndExtractArguments(final String[] args) {
        try {
            if (args.length != 3) {
                throw new IllegalArgumentException("The number of arguments is not 3 as expected");
            }
            final long countToGenerate = Long.parseLong(args[0]);
            if (countToGenerate < 0) {
                throw new IllegalArgumentException("The number of key pairs to generate can't be negative");
            }
            final String filePath = args[1];
            final int threadCount = Integer.parseInt(args[2]);
            if (threadCount <= 0) {
                throw new IllegalArgumentException("The number of threads to be used must be bigger than 0");
            }
            return new CommandlineArguments(countToGenerate, filePath, threadCount);
        } catch (final NumberFormatException e) {
            throw new IllegalArgumentException("A number field had invalid format", e);
        }
    }

    private void printUsage() {
        System.out.println("Usage of arguments:");
        System.out.println("[[command]] <<number of key pars to generate eg 1000>> " +
                "<<the file path to store the data to>> <<the number of threads to use eg. 8>>");
    }

    /**
     * Class representing the provided commandline arguments
     */
    static class CommandlineArguments {

        final long countToGenerate;

        final String filePath;

        final int threadCount;

        public CommandlineArguments(final long countToGenerate, final String filePath, final int threadCount) {
            this.countToGenerate = countToGenerate;
            this.filePath = filePath;
            this.threadCount = threadCount;
        }

        @Override
        public String toString() {
            final StringBuilder builder = new StringBuilder();
            builder.append("Number of pairs to generate: " + countToGenerate);
            builder.append("\n");
            builder.append("File path to store the data: " + filePath);
            builder.append("\n");
            builder.append("Maximum number of threads to be used: " + threadCount);
            builder.append("\n");
            return builder.toString();
        }
    }
}
