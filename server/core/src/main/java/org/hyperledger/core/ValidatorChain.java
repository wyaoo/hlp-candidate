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
import org.hyperledger.common.Outpoint;
import org.hyperledger.common.Transaction;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class ValidatorChain implements BlockHeaderValidator, BlockBodyValidator, TransactionValidator {

    List<ValidatorFactory> validatorFactories;

    public ValidatorChain(List<ValidatorFactory> validatorFactories) {
        this.validatorFactories = validatorFactories;
    }

    public ValidatorChain(ValidatorFactory... validatorFactories) {
        this.validatorFactories = Arrays.asList(validatorFactories);
    }

    @Override
    public StoredBlock validateBody(BlockStore blockStore, StoredBlock b, Map<Outpoint, Transaction> referred) throws HyperLedgerException {
        for (ValidatorFactory vf : validatorFactories) {
            b = vf.getBodyValidator().validateBody(blockStore, b, referred);
        }
        return b;
    }

    @Override
    public void validateHeader(BlockStore blockStore, StoredHeader b) throws HyperLedgerException {
        for (ValidatorFactory vf : validatorFactories) {
            vf.getHeaderValidator().validateHeader(blockStore, b);
        }
    }

    @Override
    public void validatePath(BlockStore blockStore, StoredHeader join, List<StoredHeader> path) throws HyperLedgerException {
        for (ValidatorFactory vf : validatorFactories) {
            vf.getHeaderValidator().validatePath(blockStore, join, path);
        }
    }

    @Override
    public ValidatedTransaction validateTransaction(Transaction t, int height, Map<Outpoint, Transaction> sources) throws HyperLedgerException {
        for (ValidatorFactory vf : validatorFactories) {
            t = vf.getTransactionValidator().validateTransaction(t, height, sources);
        }
        // cast is valid if validatorFactories had at least one item
        return (ValidatedTransaction) t;
    }

    public <T extends ValidatorFactory> T getValidatorFactory(Class<T> type) {
        for (ValidatorFactory vf : validatorFactories) {
            if (type.isInstance(vf)) return (T) vf;
        }
        return null;
    }

    public ValidatorFactory getValidatorFactory(int index) {
        return validatorFactories.get(index);
    }
}
