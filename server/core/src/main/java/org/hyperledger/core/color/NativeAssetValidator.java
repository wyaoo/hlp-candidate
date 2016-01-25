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

import com.google.common.collect.Maps;
import org.hyperledger.common.*;
import org.hyperledger.common.color.Color;
import org.hyperledger.common.color.ColoredTransactionOutput;
import org.hyperledger.common.color.NativeAsset;
import org.hyperledger.core.BitcoinValidatorConfig;
import org.hyperledger.core.ValidatedTransaction;
import org.hyperledger.core.bitcoin.BitcoinValidator;
import org.hyperledger.core.bitcoin.ScriptVerifyFlag;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;

public class NativeAssetValidator extends BitcoinValidator {
    public NativeAssetValidator(BitcoinValidatorConfig parameters) {
        super(parameters);
    }

    public boolean isAssetDefinition(Transaction tx, int index) {
        List<? extends TransactionInput> inputs = tx.getInputs();
        // Asset is being defined at this index if it's not the last input and the prevout is null
        Outpoint source = inputs.get(index).getSource();
        return source.isNull() && inputs.size() > index + 1;
    }

    public boolean isAssetBeingDefined(Transaction tx, NativeAsset asset) {
        return tx.getID().equals(asset.getTxid()) &&
                isAssetDefinition(tx, asset.getIndex());
    }

    public long getValueOut(Transaction tx, NativeAsset asset) {
        // If the asset is being defined in this tx, it will have an empty txid
        NativeAsset lookup = isAssetBeingDefined(tx, asset) ? new NativeAsset(asset.getIndex()) : asset;
        long value = 0;
        for (TransactionOutput o : tx.getOutputs()) {
            if (o instanceof ColoredTransactionOutput) {
                ColoredTransactionOutput out = ((ColoredTransactionOutput) o);
                if (out.getColor().equals(lookup))
                    value += out.getQuantity();
            }
        }
        return value;
    }

    public Map<NativeAsset, Long> getValuesOut(Transaction tx, boolean includeIssued) {
        Map<NativeAsset, Long> map = Maps.newHashMap();
        for (TransactionOutput output : tx.getOutputs()) {
            applyTransactionOutput(tx, output, map, includeIssued);
        }
        return map;
    }

    private void applyTransactionOutput(Transaction tx, TransactionOutput o, Map<NativeAsset, Long> map, boolean includeIssued) {
        NativeAsset asset = NativeAsset.BITCOIN;
        boolean isDefined = false;
        long value = o.getValue();
        if (o instanceof ColoredTransactionOutput) {
            ColoredTransactionOutput out = (ColoredTransactionOutput) o;
            Color color = out.getColor();
            if (color.isNative() && !color.isToken()) {
                asset = (NativeAsset) color;
                isDefined = asset.isBeingDefined();
                if (isDefined)
                    asset = new NativeAsset(tx.getID(), asset.getIndex());
                value = out.getQuantity();
            }
        }
        if (includeIssued || !isDefined) {
            if (map.containsKey(asset))
                map.put(asset, map.get(asset) + value);
            else
                map.put(asset, value);
        }
    }

    @Override
    public ValidatedTransaction validateTransaction(Transaction t, int height, Map<Outpoint, Transaction> referred) throws HyperLedgerException {
        ValidatedTransaction superValidated = super.validateTransaction(t, height, referred);

        //FIXME ensure no overflow, and other consensus safety
        Map<NativeAsset, Long> mapIn = Maps.newHashMap();

        for (TransactionInput in : t.getInputs()) {
            if (!isAssetDefinition(t, t.getInputs().indexOf(in))) {
                if (in.getSourceTransactionID().equals(TID.INVALID))
                    throw new HyperLedgerException("not a valid asset definition");
                Transaction prevTx = referred.get(in.getSource());
                TransactionOutput prevOut = prevTx.getOutputs().get(in.getOutputIndex());
                applyTransactionOutput(prevTx, prevOut, mapIn, true);
            }
        }

        Map<NativeAsset, Long> mapOut = Maps.newHashMap();

        for (TransactionOutput out : t.getOutputs()) {
            applyTransactionOutput(t, out, mapOut, false);
        }

        for (NativeAsset asset : mapOut.keySet()) {
            if (!mapIn.containsKey(asset))
                throw new HyperLedgerException("output contains " + asset + " but no such input");
            if (mapOut.get(asset) > mapIn.get(asset))
                throw new HyperLedgerException("output contains " + mapOut.get(asset) + " of " + asset + " which is > " + mapIn.get(asset));
        }

        return new ValidatedTransaction(t, superValidated.getFee());
    }

    @Override
    protected boolean skipOutpoint(Outpoint outpoint) {
        return outpoint.isNull(); // Issuance marker
    }

    @Override
    protected EnumSet<ScriptVerifyFlag> flagsForValidateTransaction() {
        EnumSet<ScriptVerifyFlag> flags = super.flagsForValidateTransaction();
        flags.add(ScriptVerifyFlag.NATIVEASSET);
        return flags;
    }
}
