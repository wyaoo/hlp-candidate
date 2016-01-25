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

import java.util.List;
import java.util.Map;

public class ValidatorFactory<T extends ValidatorConfig> {
    public T getConfig() {
        return (T) new ValidatorConfig() {
        };
    }

    public BlockHeaderValidator getHeaderValidator() {
        return new BlockHeaderValidator() {
            @Override
            public void validateHeader(BlockStore blockStore, StoredHeader b) throws HyperLedgerException {
                // nothing to do
            }

            @Override
            public void validatePath(BlockStore blockStore, StoredHeader join, List<StoredHeader> path) throws HyperLedgerException {
                // nothing to do
            }
        };
    }

    public BlockBodyValidator getBodyValidator() {
        return new BlockBodyValidator() {
            @Override
            public StoredBlock validateBody(BlockStore blockStore, StoredBlock b, Map<Outpoint, Transaction> referred) throws HyperLedgerException {
                return b;
            }
        };
    }

    public TransactionValidator getTransactionValidator() {
        return new TransactionValidator() {
            @Override
            public ValidatedTransaction validateTransaction(Transaction t, int height, Map<Outpoint, Transaction> sources) throws HyperLedgerException {
                return new ValidatedTransaction(t, 0L);
            }
        };
    }
}
