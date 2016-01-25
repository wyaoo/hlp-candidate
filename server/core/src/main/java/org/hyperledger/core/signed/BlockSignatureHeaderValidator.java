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
package org.hyperledger.core.signed;

import org.hyperledger.common.*;
import org.hyperledger.core.BlockHeaderValidator;
import org.hyperledger.core.BlockStore;
import org.hyperledger.core.ScriptValidator;
import org.hyperledger.core.StoredHeader;
import org.hyperledger.core.bitcoin.JavaBitcoinScriptEvaluation;
import org.hyperledger.core.bitcoin.ScriptVerifyFlag;

import java.io.IOException;
import java.util.EnumSet;
import java.util.List;
import java.util.Stack;

public class BlockSignatureHeaderValidator implements BlockHeaderValidator {
    private final BlockSignatureConfig config;

    public BlockSignatureHeaderValidator(BlockSignatureConfig config) {
        this.config = config;
    }

    @Override
    public void validateHeader(BlockStore blockStore, StoredHeader header) throws HyperLedgerException {
        // assuming that BitcoinValidator is also used, and that would check POW

        if (!(header.getHeader() instanceof HeaderWithSignatures)) {
            throw new HyperLedgerException("block header is not a signed one");
        }

        HeaderWithSignatures hws = (HeaderWithSignatures) header.getHeader();
        Script inScript = hws.getInScript();
        if (!inScript.isPushOnly()) {
            throw new HyperLedgerException("non-push operations in the signature script");
        }

        try {
            WireFormat.ArrayWriter writer = new WireFormat.ArrayWriter();
            hws.toWireBitcoinHeader(writer);
            byte[] headerBytes = writer.toByteArray();
            Stack<byte[]> stack = new Stack<>();
            stack.push(headerBytes);

            ScriptValidator.ScriptValidation scriptEvaluator = getScriptValidation();
            JavaBitcoinScriptEvaluation evaluator = (JavaBitcoinScriptEvaluation) scriptEvaluator;
            HeaderWithSignatures prevHws = (HeaderWithSignatures) blockStore.getHeader(hws.getPreviousID()).getHeader();

            Script hashCheckerScript = Script.create().op(Opcode.OP_HASH160).data(prevHws.getNextScriptHash()).op(Opcode.OP_EQUAL).build();
            Script headerAndInScript = Script.create().data(headerBytes).concat(inScript).build();
            boolean success = evaluator.evaluateScripts(true, headerAndInScript, hashCheckerScript);
            if (!success) {
                throw new HyperLedgerException("header script validation failed");
            }
        } catch (IOException e) {
            // nothing to do
        }

    }

    @Override
    public void validatePath(BlockStore blockStore, StoredHeader join, List<StoredHeader> path) throws HyperLedgerException {
        // nothing to do
    }

    public ScriptValidator.ScriptValidation getScriptValidation() {
        switch (config.getScriptEngine()) {
            case BITCOIN_JAVA:
                return new JavaBitcoinScriptEvaluation(null, 0, null, EnumSet.of(ScriptVerifyFlag.P2SH, ScriptVerifyFlag.SIGNED_HEADER), SignatureOptions.COMMON);
            default:
                // TODO(robert): add native libconsensus validation
            case BITCOIN_LIBCONSENSUS:
                return null;
        }
    }

}
