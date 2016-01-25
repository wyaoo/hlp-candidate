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
import org.hyperledger.core.ScriptValidator;
import org.hyperledger.nativelibs.LibBitcoinConsensusJNA;
import org.hyperledger.nativelibs.LibBitcoinConsensusJNA.BitcoinConsensusErrorHolder;

import java.io.IOException;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

public class NativeBitcoinScriptEvaluation implements ScriptValidator.ScriptValidation {
    private final Transaction tx;
    private TransactionOutput source;
    private int inr;
    private final EnumSet<ScriptVerifyFlag> flags;
    private boolean valid = false;

    public NativeBitcoinScriptEvaluation(Transaction tx, int inr, TransactionOutput source, EnumSet<ScriptVerifyFlag> flags, SignatureOptions signatureOptions) {
        this.tx = tx;
        this.inr = inr;
        this.source = source;
        this.flags = flags;
        if (signatureOptions.contains(SignatureOptions.Option.SCIV))
            this.flags.add(ScriptVerifyFlag.SCIV);
    }

    @Override
    public int getInputIndex() {
        return inr;
    }

    @Override
    public Transaction getTransaction() {
        return tx;
    }

    @Override
    public TransactionOutput getSource() {
        return source;
    }

    @Override
    public Boolean isValid() {
        return valid;
    }

    public ScriptValidator.ScriptValidation validate() throws HyperLedgerException {
        BitcoinConsensusErrorHolder error = new BitcoinConsensusErrorHolder();
        WireFormat.ArrayWriter writer = new WireFormat.ArrayWriter();
        try {
            Set<WireFormatter.WireFormatFlags> wireFormatFlagsSet = new HashSet<>();
            if (flags.contains(ScriptVerifyFlag.NATIVEASSET)) {
                wireFormatFlagsSet.add(WireFormatter.WireFormatFlags.NATIVE_ASSET);
            }
            new WireFormatter(wireFormatFlagsSet).toWire(tx, writer);
        } catch (IOException ignored) {
        }
        byte[] txAsByteArray = writer.toByteArray();
        long nInValue = source.getValue();

        valid = LibBitcoinConsensusJNA.verifyScript(source.getScript().toByteArray(), txAsByteArray, nInValue,
                inr, ScriptVerifyFlag.calcVal(flags), error);
        return this;
    }
}
