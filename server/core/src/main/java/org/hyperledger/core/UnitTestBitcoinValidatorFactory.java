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
import org.hyperledger.core.bitcoin.BitcoinProductionValidatorConfig;
import org.hyperledger.core.bitcoin.BitcoinValidatorFactory;

import java.util.List;

public class UnitTestBitcoinValidatorFactory extends BitcoinValidatorFactory {

    public UnitTestBitcoinValidatorFactory() {
        super(new BitcoinProductionValidatorConfig());
    }

    @Override
    public BlockHeaderValidator getHeaderValidator() {
        return new BlockHeaderValidator() {
            @Override
            public void validateHeader(BlockStore blockStore, StoredHeader b) throws HyperLedgerException {
            }

            @Override
            public void validatePath(BlockStore blockStore, StoredHeader join, List<StoredHeader> path) throws HyperLedgerException {
                int height = join.getHeight();
                StoredHeader p = join;
                for (StoredHeader next : path) {
                    ++height;

                    next.setChainWork(
                            p.getChainWork() + 1.0);
                    next.setHeight(height);
                    p = next;
                }
            }

        };
    }

    @Override
    public BlockBodyValidator getBodyValidator() {
        // noop validator, not checking the body
        return (blockStore, b, referred) -> b;
    }

    @Override
    public TransactionValidator getTransactionValidator() {
        // noop validator, not checking the transactions
        return (t, height, sources) -> new ValidatedTransaction(t, 0L);
    }
}
