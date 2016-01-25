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
package org.hyperledger.core.color;

import org.hyperledger.common.HyperLedgerException;
import org.hyperledger.common.Outpoint;
import org.hyperledger.common.Transaction;
import org.hyperledger.core.BlockBodyValidator;
import org.hyperledger.core.BlockStore;
import org.hyperledger.core.StoredBlock;
import org.hyperledger.core.StoredTransaction;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ColoredBlockValidator implements BlockBodyValidator {

    private final ColoredValidatorConfig config;

    public ColoredBlockValidator(ColoredValidatorConfig config) {
        this.config = config;
    }

    @Override
    public StoredBlock validateBody(BlockStore blockStore, StoredBlock b, Map<Outpoint, Transaction> referred) throws HyperLedgerException {
        List<StoredTransaction> colorValidated = new ArrayList<>();
        for (StoredTransaction t : b.getTransactions()) {
            try {
                colorValidated.add((StoredTransaction) ColoredTransactionValidator.coloredTransactionValidator(t, referred));
            } catch (HyperLedgerException e) {
                if (config.isEnforceColorRulesInBlocks())
                    throw e;
                colorValidated.add(t);
            }
        }

        return new StoredBlock(b.getHeader(), colorValidated);
    }
}
