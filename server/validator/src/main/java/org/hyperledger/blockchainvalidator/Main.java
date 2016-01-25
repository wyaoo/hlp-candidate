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

import org.hyperledger.blockchainvalidator.helper.StopWatch;
import org.hyperledger.common.BID;
import org.hyperledger.common.HyperLedgerException;
import org.hyperledger.common.Header;
import org.hyperledger.core.StoredHeader;
import org.hyperledger.core.bitcoin.BitcoinPersistentBlocks;
import org.hyperledger.core.kvstore.LevelDBStore;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class Main {

    private static BitcoinPersistentBlocks persistentBlocks;
    private static int merkleTreeValidationBlockLimit;
    private static int transactionMatchCheckBlockLimit;
    private static int minHeight;
    private static int maxHeight;

    private Main() {
    }

    public static void main(String[] args) throws HyperLedgerException {
        int argCount = 0;
        String pathToDataFiles = ".";
        if (args.length >= argCount) pathToDataFiles = args[argCount];

        argCount++;
        try {
            if (args.length >= argCount) minHeight = Math.max(0, Integer.valueOf(args[argCount]));
        } catch (NumberFormatException e) {
            System.out.println("Invalid argument [" + args[argCount] + "]. minHeight argument is optional and must be a number");
            System.exit(1);
        }

        argCount++;
        try {
            if (args.length >= argCount) maxHeight = Math.min(Integer.MAX_VALUE, Integer.valueOf(args[argCount]));
        } catch (NumberFormatException e) {
            System.out.println("Invalid argument [" + args[argCount] + "]. maxHeight argument is optional and must be a number");
            System.exit(1);
        }

        argCount++;
        try {
            if (args.length >= argCount) merkleTreeValidationBlockLimit = Math.max(0, Integer.valueOf(args[argCount]));
        } catch (NumberFormatException e) {
            System.out.println("Invalid argument [" + args[argCount] + "]. merkleTreeValidationBlockLimit argument is optional and must be a number");
            System.exit(1);
        }

        argCount++;
        try {
            if (args.length >= argCount) transactionMatchCheckBlockLimit = Math.max(0, Integer.valueOf(args[argCount]));
        } catch (NumberFormatException e) {
            System.out.println("Invalid argument [" + args[argCount] + "]. transactionMatchCheckBlockLimit argument is optional and must be a number");
            System.exit(1);
        }
        validate(pathToDataFiles, minHeight, maxHeight);
    }

    private static void validate(String pathToDataFiles, int minHeight, int maxHeight) throws HyperLedgerException {
        StopWatch.start(1);
        StopWatch.start(0);
        Map<BID, StoredHeader> headers = readHeaders(pathToDataFiles);
        Validator validator = new Validator(persistentBlocks, headers, minHeight, maxHeight, merkleTreeValidationBlockLimit, transactionMatchCheckBlockLimit);
        StopWatch.stop(0);
        System.out.println("[" + StopWatch.getEllapsedTime(0) + " ms] " + "Header count: " + headers.size());

        checkTransactionMatch(validator);

        checkIfSingleTree(validator);

        checkDifficulty(validator);

        checkDifficultyIncrease(validator);

        checkMerkleRoot(validator);

        StopWatch.stop(1);
        System.out.println("Total execution time: [" + StopWatch.getEllapsedTime(1) + " ms]");

    }

    private static void checkMerkleRoot(Validator validator) throws HyperLedgerException {
        StopWatch.start(0);
        Set<BID> violators = validator.checkMerkleRoot();
        StopWatch.stop(0);
        if (merkleTreeValidationBlockLimit > 0) {
            System.out.println("[" + StopWatch.getEllapsedTime(0) + " ms] " + "Merkle tree problem count for the first " + merkleTreeValidationBlockLimit + " blocks: " + violators.size());
        } else {
            System.out.println("[" + StopWatch.getEllapsedTime(0) + " ms] " + "Merkle tree problem count: " + violators.size());
        }
        printItems(violators);
    }

    private static void checkDifficultyIncrease(Validator validator) {
        StopWatch.start(0);
        Set<BID> headersWithInvalidDifficultyIncrease = validator.checkDifficultyIncrease();
        StopWatch.stop(0);
        System.out.println("[" + StopWatch.getEllapsedTime(0) + " ms] " + "Header count with invalid difficulty increase: " + headersWithInvalidDifficultyIncrease.size());
        printItems(headersWithInvalidDifficultyIncrease);
    }

    private static void checkDifficulty(Validator validator) {
        StopWatch.start(0);
        Set<Header> headersWithInvalidDifficulty = validator.checkDifficulty();
        StopWatch.stop(0);
        System.out.println("[" + StopWatch.getEllapsedTime(0) + " ms] " + "Headers count with invalid difficulty: " + headersWithInvalidDifficulty.size());
        printItems(headersWithInvalidDifficulty);
    }

    private static void checkIfSingleTree(Validator validator) {
        StopWatch.start(0);
        List<Validator.HeaderChain> chains = validator.checkIfSingleTree();
        StopWatch.stop(0);
        System.out.println("[" + StopWatch.getEllapsedTime(0) + " ms] " + "Separate chain count: " + chains.size());
        printItems(chains);
    }

    private static void checkTransactionMatch(Validator validator) throws HyperLedgerException {
        StopWatch.start(0);
        Validator.TransactionCheckResult transactionCheckResult = validator.checkTransactionMatch();
        StopWatch.stop(0);
        if (merkleTreeValidationBlockLimit > 0) {
            System.out.println("[" + StopWatch.getEllapsedTime(0) + " ms] " + "Transaction count without input source for the first " + transactionMatchCheckBlockLimit + " blocks: " + transactionCheckResult.transactionsWithNoMatchingInput.size());
            printItems(transactionCheckResult.transactionsWithNoMatchingInput);
            System.out.println("[" + StopWatch.getEllapsedTime(0) + " ms] " + "Overspending transaction count for the first " + transactionMatchCheckBlockLimit + " blocks: " + transactionCheckResult.overspendingTransactions.size());
            printItems(transactionCheckResult.overspendingTransactions);
            System.out.println("[" + StopWatch.getEllapsedTime(0) + " ms] " + "Overspending block count without input source for the first " + transactionMatchCheckBlockLimit + " blocks: " + transactionCheckResult.overspendingBlocks.size());
            printItems(transactionCheckResult.overspendingBlocks);
        } else {
            System.out.println("[" + StopWatch.getEllapsedTime(0) + " ms] " + "Transaction count without input source: " + transactionCheckResult.transactionsWithNoMatchingInput.size());
            printItems(transactionCheckResult.transactionsWithNoMatchingInput);
            System.out.println("[" + StopWatch.getEllapsedTime(0) + " ms] " + "Overspending transaction count: " + transactionCheckResult.overspendingTransactions.size());
            printItems(transactionCheckResult.overspendingTransactions);
            System.out.println("[" + StopWatch.getEllapsedTime(0) + " ms] " + "Overspending block count: " + transactionCheckResult.overspendingBlocks.size());
            printItems(transactionCheckResult.overspendingBlocks);
        }
    }

    private static void printItems(Iterable it) {
        int i = 0;
        for (Object item : it) {
            System.out.println("  " + i + ": " + item);
            i++;
        }
    }

    public static Map<BID, StoredHeader> readHeaders(String pathToDataFiles) throws HyperLedgerException {
        System.out.println("Creating block store...");
        persistentBlocks = new BitcoinPersistentBlocks(new LevelDBStore(pathToDataFiles, 100 * 1048576));
        System.out.println("  ...completed");
        System.out.println("Starting block store...");
        persistentBlocks.start();
        System.out.println("  ...completed");
        System.out.println("Reading headers...");
        Map<BID, StoredHeader> headers = new HashMap<>();
        persistentBlocks.readHeaders(headers);
        System.out.println("  ...completed");
        return headers;
    }

}
