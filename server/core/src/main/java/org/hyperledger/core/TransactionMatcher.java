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

import org.hyperledger.common.*;

import java.util.List;
import java.util.Set;

public class TransactionMatcher {
    public static boolean passesFilter(Transaction t, BloomFilter filter) {
        boolean found = false;
        if (filter.contains(t.getID().toByteArray())) {
            found = true;
        }
        for (Coin o : t.getCoins()) {
            try {
                List<Script.Token> tokens = o.getOutput().getScript().parse();
                for (Script.Token token : tokens) {
                    if (token.data != null && filter.contains(token.data)) {
                        if (filter.getUpdateMode() == BloomFilter.UpdateMode.all) {
                            filter.addOutpoint(o.getOutpoint());
                        } else if (filter.getUpdateMode() == BloomFilter.UpdateMode.keys) {
                            if (o.getOutput().getScript().isPayToKey() ||
                                    o.getOutput().getScript().isMultiSig()) {
                                filter.addOutpoint(o.getOutpoint());
                            }
                        }
                        found = true;
                        break;
                    }
                }
            } catch (HyperLedgerException ignored) {
            }
        }
        if (found) {
            return true;
        }

        for (TransactionInput in : t.getInputs())
            if (!in.getSourceTransactionID().equals(TID.INVALID)) {
                if (filter.containsOutpoint(in.getSource())) {
                    return true;
                }
                try {
                    List<Script.Token> tokens = in.getScript().parse();
                    for (Script.Token token : tokens) {
                        if (token.data != null && filter.contains(token.data)) {
                            return true;
                        }
                    }
                } catch (HyperLedgerException ignored) {
                }
            }
        return false;
    }

    public static boolean matches(Transaction t, Set<ByteVector> matchSet) {
        boolean found = false;
        for (Coin o : t.getCoins()) {
            try {
                if (matchSet.contains(new ByteVector(o.getOutput().getScript().toByteArray()))) {
                    matchSet.add(new ByteVector(o.getOutpoint().toWire()));
                    found = true;
                }
            } catch (Exception e) {
                // best effort
            }
        }
        for (TransactionInput i : t.getInputs()) {
            if (!i.getSourceTransactionID().equals(TID.INVALID)) {
                ByteVector outpoint = new ByteVector(i.getSource().toWire());
                if (matchSet.contains(outpoint)) {
                    matchSet.remove(outpoint);
                    found = true;
                }
            }
        }
        return found;
    }
}
