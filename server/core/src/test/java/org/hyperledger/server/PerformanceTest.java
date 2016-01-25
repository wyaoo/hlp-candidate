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
package org.hyperledger.server;

import org.hyperledger.common.*;
import org.hyperledger.core.BitcoinValidatorConfig;
import org.hyperledger.core.ScriptValidator;
import org.hyperledger.core.bitcoin.JavaBitcoinScriptEvaluation;
import org.hyperledger.core.bitcoin.NativeBitcoinScriptEvaluation;
import org.hyperledger.core.bitcoin.ScriptVerifyFlag;
import org.junit.Ignore;
import org.junit.Test;

import java.io.IOException;
import java.util.EnumSet;
import java.util.concurrent.*;

import static org.junit.Assert.assertTrue;

@Ignore
public class PerformanceTest {
    WireFormatter wireFormatter = WireFormatter.bitcoin;

    @Test
    public void nativeTest() throws HyperLedgerException, IOException {
        Transaction t1 =
                wireFormatter.fromWireDump("01000000012312503f2491a2a97fcd775f11e108a540a5528b5d4dee7a3c68ae4add01dab300000000fdfe0000483045022100f6649b0eddfdfd4ad55426663385090d51ee86c3481bdc6b0c18ea6c0ece2c0b0220561c315b07cffa6f7dd9df96dbae9200c2dee09bf93cc35ca05e6cdf613340aa0148304502207aacee820e08b0b174e248abd8d7a34ed63b5da3abedb99934df9fddd65c05c4022100dfe87896ab5ee3df476c2655f9fbe5bd089dccbef3e4ea05b5d121169fe7f5f4014c695221031d11db38972b712a9fe1fc023577c7ae3ddb4a3004187d41c45121eecfdbb5b7210207ec36911b6ad2382860d32989c7b8728e9489d7bbc94a6b5509ef0029be128821024ea9fac06f666a4adc3fc1357b7bec1fd0bdece2b9d08579226a8ebde53058e453aeffffffff0180380100000000001976a914c9b99cddf847d10685a4fabaa0baf505f7c3dfab88ac00000000");
        Transaction t2 =
                wireFormatter.fromWireDump("0100000001f7301459ac875fe8a28ccf45ceaab6370a7c5791a4248a1234d54b5ccde2951d000000006c493046022100ec0f98b5d40e80d1ed766e098ea7d19d2238f4d0c3b282d588b8226738b16758022100c8c59c2d04d1234fbd1ba07792bba9477d8934f43b35c02f200c0590b9b059ef0121021803e40e13a344074cb437b8666712400c9ea17dbda076368c7c5a337d567039ffffffff02905f01000000000017a914b1ce99298d5f07364b57b1e5c9cc00be0b04a95487a0860100000000001976a914c9b99cddf847d10685a4fabaa0baf505f7c3dfab88ac00000000");

        int runCount = 1000;


        long nanontime = runAndMeasure(t1, 0, t2.getOutputs().get(0), runCount, BitcoinValidatorConfig.ScriptEngine.BITCOIN_JAVA);
        System.out.println("Running java  " + 1000000000.0 / (nanontime / runCount) + " ops/s");

        nanontime = runAndMeasure(t1, 0, t2.getOutputs().get(0), runCount, BitcoinValidatorConfig.ScriptEngine.BITCOIN_LIBCONSENSUS);
        System.out.println("Running native  " + 1000000000.0 / (nanontime / runCount) + " ops/s");
    }

    private final ExecutorService scriptValidators = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());

    private long runAndMeasure(Transaction t1, int inr, TransactionOutput o, int count, BitcoinValidatorConfig.ScriptEngine scriptValidationMode) throws HyperLedgerException {

        CompletionService<Boolean> cs = new ExecutorCompletionService<>(scriptValidators);
        long start = System.nanoTime();
        for (int i = 0; i < count; i++) {
            cs.submit(() -> {
                ScriptValidator.ScriptValidation scriptValidator = createScriptValidator(t1, inr, o, EnumSet.of(ScriptVerifyFlag.P2SH), scriptValidationMode);
                return scriptValidator.validate().isValid();
            });
        }
        try {
            for (int i = 0; i < count; ++i)
                assertTrue(cs.take().get());
        } catch (InterruptedException | ExecutionException e) {
        }

        return System.nanoTime() - start;
    }

    private ScriptValidator.ScriptValidation createScriptValidator(Transaction tx, int inr, TransactionOutput source, EnumSet<ScriptVerifyFlag> flags, BitcoinValidatorConfig.ScriptEngine scriptValidationMode) {
        if (scriptValidationMode == BitcoinValidatorConfig.ScriptEngine.BITCOIN_LIBCONSENSUS) {
            return new NativeBitcoinScriptEvaluation(tx, inr, source, flags, SignatureOptions.COMMON);
        } else {
            return new JavaBitcoinScriptEvaluation(tx, inr, source, flags, SignatureOptions.COMMON);
        }
    }
}
