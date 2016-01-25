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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

class Mempool {
    private static final Logger log = LoggerFactory.getLogger(DefaultBlockStore.class);

    private final Map<TID, ValidatedTransaction> transactions = new HashMap<>();
    private final Map<Outpoint, TID> spends = new HashMap<>();

    public synchronized ValidatedTransaction get(TID hash) {
        return transactions.get(hash);
    }

    public synchronized int size() {
        return transactions.size();
    }

    public synchronized Set<ValidatedTransaction> getConflicts(Transaction t) {
        Set<ValidatedTransaction> conflicts = new HashSet<>();
        for (TransactionInput in : t.getInputs()) {
            TID ch = spends.get(in.getSource());
            if (ch != null && transactions.containsKey(ch))
                conflicts.add(transactions.get(ch));
        }
        return conflicts;
    }

    public synchronized Set<ValidatedTransaction> getSupported(Transaction t) {
        Set<ValidatedTransaction> supported = new HashSet<>();
        for (Coin o : t.getCoins()) {
            TID sh = spends.get(o.getOutpoint());
            if (sh != null && transactions.containsKey(sh)) {
                ValidatedTransaction sup = transactions.get(sh);
                supported.add(sup);
                supported.addAll(getSupported(sup));
            }
        }
        return supported;
    }

    public synchronized ValidatedTransaction getSpend(Coin c) {
        TID s = spends.get(c.getOutpoint());
        if (s != null && transactions.containsKey(s)) {
            return transactions.get(s);
        }
        return null;
    }

    public static List<ValidatedTransaction> getDependencyOrder(Set<ValidatedTransaction> txs) {
        Map<TID, ValidatedTransaction> context = new HashMap<>();
        Set<TID> used = new HashSet<>(txs.size());
        List<ValidatedTransaction> ordered = new ArrayList<>(txs.size());

        for (ValidatedTransaction t : txs) {
            context.put(t.getID(), t);
        }

        for (ValidatedTransaction t : txs) {
            transitiveOrder(ordered, used, t, context);
        }
        return ordered;
    }

    public synchronized boolean isAvailable(Outpoint outpoint) {
        ValidatedTransaction tv = transactions.get(outpoint.getTransactionId());
        return tv != null && outpoint.getOutputIndex() < tv.getOutputs().size() && !spends.containsKey(outpoint);
    }

    public synchronized void add(ValidatedTransaction t) throws HyperLedgerException {
        // check if inputs would consume spent coins
        for (TransactionInput in : t.getInputs()) {
            if (spends.containsKey(in.getSource())) {
                throw new HyperLedgerException("Transaction " + t + " would double spend " + in.getSource());
            }
        }
        transactions.put(t.getID(), t);
        for (TransactionInput in : t.getInputs()) {
            spends.put(in.getSource(), t.getID());
        }
    }

    public synchronized Set<ValidatedTransaction> remove(TID hash, boolean transitive) {
        Set<ValidatedTransaction> dropped = new HashSet<>();
        if (hash != null) {
            ValidatedTransaction t;
            if ((t = transactions.remove(hash)) != null) {
                dropped.add(t);
                if (transitive) {
                    for (Coin o : t.getCoins()) {
                        dropped.addAll(remove(spends.remove(o.getOutpoint()), true));
                    }
                    return dropped;
                } else {
                    for (Coin o : t.getCoins()) {
                        spends.remove(o.getOutpoint());
                    }
                }
            }
        }
        return dropped;
    }

    public synchronized List<ValidatedTransaction> getInventory() {
        List<ValidatedTransaction> result = new ArrayList<>();
        DependencyOrderedTx dependencyOrder = new DependencyOrderedTx();
        for (ValidatedTransaction tx : dependencyOrder) {
            result.add(tx);
        }
        return result;
    }

    public synchronized List<ValidatedTransaction> scanUnconfirmedPool(Set<ByteVector> matchSet) {
        List<ValidatedTransaction> matched = new ArrayList<>();
        DependencyOrderedTx dependencyOrder = new DependencyOrderedTx();
        for (ValidatedTransaction t : dependencyOrder) {
            if (TransactionMatcher.matches(t, matchSet)) {
                matched.add(t);
            }
        }
        return matched;
    }

    private static void transitiveOrder(List<ValidatedTransaction> ordered, Set<TID> used, ValidatedTransaction t,
                                        Map<TID, ValidatedTransaction> context) {
        for (TransactionInput in : t.getInputs()) {
            if (!used.contains(in.getSourceTransactionID()) && context.containsKey(in.getSourceTransactionID())) {
                transitiveOrder(ordered, used, context.get(in.getSourceTransactionID()), context);
            }
        }
        if (used.add(t.getID())) {
            ordered.add(t);
        }
    }

    private class DependencyOrderedTx implements Iterable<ValidatedTransaction> {
        @Override
        public Iterator<ValidatedTransaction> iterator() {
            synchronized (Mempool.this) {
                Set<TID> used = new HashSet<>(transactions.size());
                List<ValidatedTransaction> ordered = new ArrayList<>(transactions.size());

                List<ValidatedTransaction> feeOrder = new ArrayList<>();
                feeOrder.addAll(transactions.values());
                Collections.sort(feeOrder, (a, b) -> (int) (b.getFee() - a.getFee()));
                for (ValidatedTransaction t : feeOrder) {
                    transitiveOrder(ordered, used, t, transactions);
                }
                return ordered.iterator();
            }
        }
    }
}
