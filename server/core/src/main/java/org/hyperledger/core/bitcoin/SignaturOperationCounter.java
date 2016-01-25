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
package org.hyperledger.core.bitcoin;

import org.hyperledger.common.*;

import java.util.Map;

public class SignaturOperationCounter {

    private final boolean strictP2SH;
    private final Map<Outpoint, Transaction> referred;
    private final P2SHChecker p2shChecker;
    private int total;

    SignaturOperationCounter(final boolean strictP2SH, final Map<Outpoint, Transaction> referred,
                             final P2SHChecker p2shChecker) {
        this.strictP2SH = strictP2SH;
        this.referred = referred;
        this.p2shChecker = p2shChecker;
    }

    SignaturOperationCounter(final boolean strictP2SH, final Map<Outpoint, Transaction> referred) {
        this(strictP2SH, referred, new ReferralP2SHChecker());
    }

    public void addSignatureOperationCount(final Transaction transaction) throws HyperLedgerException {
        final int outputCount = transaction.getOutputs().stream().mapToInt(t -> t.getScript().sigOpCount(false)).sum();
        int inputCount = transaction.getInputs().stream().mapToInt(t -> t.getScript().sigOpCount(false)).sum();

        if (!transaction.isCoinBase()) {
            // not mapToInt, because getP2SHSigopcount throws exception
            for (TransactionInput input : transaction.getInputs()) {
                if (strictP2SH && p2shChecker.isPayToScriptHash(referred, input)) {
                    inputCount += getP2SHSigopcount(input);
                }
            }
        }

        total += inputCount + outputCount;
    }

    public int getTotal() {
        return total;
    }

    private int getP2SHSigopcount(TransactionInput input) throws HyperLedgerException {
        Script p2ss = null;
        Script.Tokenizer tokenizer = new Script.Tokenizer(input.getScript());
        while (tokenizer.hashMoreElements()) {
            Script.Token token = tokenizer.nextToken();
            if (token.op.ordinal() > 16) {
                break;
            }
            p2ss = new Script(token.data);
        }
        return p2ss == null ? 0 : p2ss.sigOpCount(true);
    }
}
