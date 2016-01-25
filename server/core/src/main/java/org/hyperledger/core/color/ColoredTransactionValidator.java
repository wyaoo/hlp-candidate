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

import org.hyperledger.common.*;
import org.hyperledger.common.color.Color;
import org.hyperledger.common.color.ColoredTransactionOutput;
import org.hyperledger.common.color.DigitalAssetAnnotation;
import org.hyperledger.common.color.ForeignAsset;
import org.hyperledger.core.StoredTransaction;
import org.hyperledger.core.TransactionValidator;
import org.hyperledger.core.ValidatedTransaction;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class ColoredTransactionValidator implements TransactionValidator {
    private final ColoredValidatorConfig config;

    public ColoredTransactionValidator(ColoredValidatorConfig config) {
        this.config = config;
    }

    private static class ColoredInput {
        Color color;
        long quantity;

        public ColoredInput(Color color, long quantity) {
            this.color = color;
            this.quantity = quantity;
        }
    }

    @Override
    public ValidatedTransaction validateTransaction(Transaction t, int height, Map<Outpoint, Transaction> sources) throws HyperLedgerException {
        ValidatedTransaction validated;
        if (t instanceof ValidatedTransaction) {
            validated = (ValidatedTransaction) t;
        } else {
            validated = new ValidatedTransaction(t, 0L);
        }

        try {
            return coloredTransactionValidator(validated, sources);
        } catch (HyperLedgerException e) {
            if (config.isEnforceColorRulesInMempool())
                throw e;
        }
        return validated;
    }

    public static ValidatedTransaction coloredTransactionValidator(ValidatedTransaction transaction, Map<Outpoint, Transaction> sources) throws HyperLedgerException {
        boolean colored = false;
        List<Long> allocation = new ArrayList<>();
        int allocationAt = 0;
        int ix = 0;
        for (TransactionOutput o : transaction.getOutputs()) {
            byte[] data = o.getScript().getPrunableData();
            if (data != null && DigitalAssetAnnotation.isDigitalAsset(o.getScript())) {
                if (colored) {
                    // there should only be one annotated output;
                    throw new HyperLedgerException("More than one Digital Asset allocation in transaction " + transaction.getID());
                }
                colored = true;
                allocation = DigitalAssetAnnotation.getColors(o.getScript());
                allocationAt = ix;
            }
            ++ix;
        }
        if (colored) {
            Transaction.Builder builder =
                    Transaction.create().inputs(transaction.getInputs()).lockTime(transaction.getLockTime()).version(transaction.getVersion());
            if (allocationAt > 0) {
                //issue
                // first input address is the color
                if (allocation.size() != allocationAt) {
                    throw new HyperLedgerException("Allocation invalid for issueing color in " + transaction.getID());
                }

                Color color = new ForeignAsset(sources.get(transaction.getSource(0)).getOutput(
                        transaction.getSource(0).getOutputIndex()).getOutputAddress());
                int i;
                for (i = 0; i < allocationAt; ++i) {
                    TransactionOutput o = transaction.getOutput(i);
                    builder.outputs(new ColoredTransactionOutput(o.getValue(), o.getScript(), color, allocation.get(i)));
                }
                for (; i < transaction.getOutputs().size(); ++i) {
                    builder.outputs(transaction.getOutput(i));
                }

            } else {
                // transfer
                List<ColoredInput> coloredInputs = new ArrayList<>();
                for (TransactionInput input : transaction.getInputs()) {
                    TransactionOutput coloredSource = sources.get(input.getSource()).getOutput(input.getSource().getOutputIndex());
                    if (coloredSource instanceof ColoredTransactionOutput) {
                        ColoredTransactionOutput co = (ColoredTransactionOutput) coloredSource;
                        coloredInputs.add(new ColoredInput(co.getColor(), co.getQuantity()));
                    }
                }

                if (!coloredInputs.isEmpty()) {
                    // copy annotation
                    builder.outputs(transaction.getOutput(0));

                    Iterator<ColoredInput> current = coloredInputs.iterator();
                    Iterator<Long> allocIterator = allocation.iterator();
                    ColoredInput c = current.next();
                    long remaining = c.quantity;
                    int i = 1; // skip annotation
                    for (; allocIterator.hasNext() && i < transaction.getOutputs().size(); ++i) {
                        long a = allocIterator.next();
                        if (a > 0) {
                            TransactionOutput o = transaction.getOutput(i);
                            builder.outputs(new ColoredTransactionOutput(o.getValue(), o.getScript(), c.color, Math.min(remaining, a)));
                            remaining -= Math.min(remaining, a);
                            if (remaining == 0) {
                                if (current.hasNext()) {
                                    c = current.next();
                                    remaining = c.quantity;
                                }
                            }
                        } else {
                            builder.outputs(transaction.getOutput(i));
                        }
                    }
                    if (allocIterator.hasNext()) {
                        throw new HyperLedgerException("missing colored imputs for allocation");
                    }
                    for (; i < transaction.getOutputs().size(); ++i) {
                        builder.outputs(transaction.getOutput(i));
                    }
                } else if (!allocation.isEmpty()) {
                    throw new HyperLedgerException("missing colored imputs for allocation");
                }
            }
            ValidatedTransaction replacement;
            if (transaction instanceof StoredTransaction) {
                replacement = new StoredTransaction(new ValidatedTransaction(builder.build(), transaction.getFee()),
                        ((StoredTransaction) transaction).getBlocks());
            } else {
                replacement = new ValidatedTransaction(builder.build(), transaction.getFee());
            }
            for (Coin c : transaction.getCoins()) {
                if (sources.containsKey(c.getOutpoint())) {
                    sources.put(c.getOutpoint(), replacement);
                }
            }
            return replacement;
        }

        return transaction;
    }
}
