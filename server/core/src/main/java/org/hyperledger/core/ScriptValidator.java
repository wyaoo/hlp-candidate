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

import org.hyperledger.common.HyperLedgerException;
import org.hyperledger.common.Transaction;
import org.hyperledger.common.TransactionOutput;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveTask;

public class ScriptValidator {

    private static final int FORKJOIN_WORKSIZE = 4;
    private static final ForkJoinPool scriptValidators = new ForkJoinPool();

    public static class ScriptValidationResult {
        private final boolean valid;
        private final Transaction transaction;
        private final int inputIndex;
        private final TransactionOutput source;
        private final Exception exception;

        public ScriptValidationResult(boolean valid, Transaction transaction, int inputIndex, TransactionOutput source, Exception exception) {
            this.valid = valid;
            this.transaction = transaction;
            this.inputIndex = inputIndex;
            this.source = source;
            this.exception = exception;
        }

        public ScriptValidationResult(boolean valid) {
            this.valid = valid;
            this.transaction = null;
            this.inputIndex = 0;
            this.source = null;
            this.exception = null;
        }

        public Exception getException() {
            return exception;
        }

        public boolean isValid() {
            return valid;
        }

        public Transaction getTransaction() {
            return transaction;
        }

        public int getInputIndex() {
            return inputIndex;
        }

        public TransactionOutput getSource() {
            return source;
        }
    }

    private static class ScriptValidatorTask extends RecursiveTask<ScriptValidationResult> {
        private final List<ScriptValidation> validations;

        public ScriptValidatorTask(List<ScriptValidation> validations) {
            this.validations = validations;
        }

        private ScriptValidationResult computeAll() {
            for (ScriptValidation v : validations) {
                try {
                    if (!v.validate().isValid()) {
                        return new ScriptValidationResult(false, v.getTransaction(), v.getInputIndex(), v.getSource(), null);
                    }
                } catch (Exception e) {
                    return new ScriptValidationResult(false, v.getTransaction(), v.getInputIndex(), v.getSource(), e);
                }
            }
            return new ScriptValidationResult(true);
        }

        @Override
        protected ScriptValidationResult compute() {
            int n = validations.size();
            if (n <= FORKJOIN_WORKSIZE) {
                return computeAll();
            } else {
                ScriptValidatorTask left = new ScriptValidatorTask(validations.subList(0, n / 2));
                left.fork();
                ScriptValidatorTask right = new ScriptValidatorTask(validations.subList(n / 2, n));
                right.fork();
                ScriptValidationResult leftResult = left.join();
                if (!leftResult.isValid()) {
                    right.cancel(true);
                    return leftResult;
                }
                return right.join();
            }
        }
    }


    public static ScriptValidationResult validate(List<ScriptValidation> validations) throws HyperLedgerException {
        try {
            return scriptValidators.submit(new ScriptValidatorTask(validations)).get();
        } catch (InterruptedException e) {
            throw new HyperLedgerException(e);
        } catch (ExecutionException e) {
            throw new HyperLedgerException(e.getCause());
        }
    }

    public interface ScriptValidation {
        Transaction getTransaction();

        int getInputIndex();

        TransactionOutput getSource();

        Boolean isValid();

        ScriptValidation validate() throws HyperLedgerException;
    }
}
