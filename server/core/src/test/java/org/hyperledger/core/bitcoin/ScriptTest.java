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
import org.hyperledger.core.BitcoinValidatorConfig;
import org.hyperledger.core.ScriptValidator;
import org.json.JSONArray;
import org.json.JSONException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.EnumSet;

import static org.hyperledger.common.Opcode.OP_FALSE;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(Parameterized.class)
public class ScriptTest {
    private static final String SCRIPT_VALID = "script_valid.json";
    private static final String SCRIPT_INVALID = "script_invalid.json";

    @Parameters
    public static Iterable<Object[]> data() {
        return Arrays.asList(new Object[][]{{BitcoinValidatorConfig.ScriptEngine.BITCOIN_JAVA}
//                , {BitcoinValidatorConfig.ScriptEngine.BITCOIN_LIBCONSENSUS}
        });
    }

    @Parameter(0)
    public BitcoinValidatorConfig.ScriptEngine scriptValidationMode;

    private JSONArray readObjectArray(String resource) throws IOException, JSONException {
        InputStream input = this.getClass().getResource("/" + resource).openStream();
        StringBuilder content = new StringBuilder();
        byte[] buffer = new byte[1024 * 16];
        while (input.read(buffer) > 0) {
            content.append(new String(buffer, "UTF-8"));
        }
        return new JSONArray(content.toString());
    }


    @Test
    public void bitcoindValidScriptTest() throws IOException, JSONException, HyperLedgerException {
        JSONArray testData = readObjectArray(SCRIPT_VALID);
        for (int i = 0; i < testData.length(); ++i) {
            JSONArray test = testData.getJSONArray(i);
            if (test.length() > 1) {    // one item tests are comments
                TestCase testCase = new TestCase(test, scriptValidationMode);
                assertTrue("Failure in " + testCase.scriptValidator.getClass().getName() + " implementation: " + testCase.message, testCase.scriptValidator.validate().isValid());
            }

        }
    }

    @Test
    public void bitcoindInvalidScriptTest() throws IOException, JSONException {
        JSONArray testData = readObjectArray(SCRIPT_INVALID);
        for (int i = 0; i < testData.length(); ++i) {
            JSONArray test = testData.getJSONArray(i);
            if (test.length() > 1) {    // one item tests are comments
                TestCase testCase = new TestCase(test, scriptValidationMode);
                try {
                    assertFalse("Failure in " + testCase.scriptValidator.getClass().getName() + " implementation: " + testCase.message, testCase.scriptValidator.validate().isValid());
                } catch (Exception ignored) {
                    // exceptions are OK here
                }
            }
        }
    }

    private static class TestCase {
        private final Script scriptPubKey;
        private final Script scriptSig;
        private final EnumSet<ScriptVerifyFlag> scriptVerifyFlags;
        public final String message;
        private final BitcoinValidatorConfig.ScriptEngine scriptValidationMode;
        private Transaction transaction;
        public ScriptValidator.ScriptValidation scriptValidator;

        private TestCase(JSONArray data, BitcoinValidatorConfig.ScriptEngine scriptValidationMode) throws JSONException {
            this.scriptSig = Script.fromReadable(data.get(0).toString());
            this.scriptPubKey = Script.fromReadable(data.get(1).toString());
            this.scriptValidationMode = scriptValidationMode;
            String scriptVerifyString = "";
            int length = data.length();
            if (length == 1) {
                // one item: it's just a comment, and shouldn't reach here
                this.message = data.get(0).toString();
                assertTrue("Bad Test: one item test: " + data.get(0).toString() + " is comment, shouldn't reach testing", false);
            } else if (length == 2) {
                // two items: invalid
                this.message = data.get(0).toString();
                assertTrue("Bad Test: two item test str1: " + data.get(0).toString() + ", str2: " + data.get(1).toString() + " invalid, fix in JSON", false);
            } else if (length == 3) {
                // three items: first is scriptsig (and comment/testname), 2nd is scriptpubkey, third is flags
                this.message = data.get(1).toString();  // 2nd item is the message AND the scriptPubKey
                scriptVerifyString = data.get(2).toString();
            } else if (length == 4) {
                // four items: first is scriptsig, 2nd is scriptpubkey, third is flags, fourth is comment/testname (with possible [sciv] flag)
                this.message = data.get(3).toString();  // 4th item is the message
                scriptVerifyString = data.get(2).toString();
            } else {
                scriptVerifyString = "";
                this.message = length > 3 ? data.get(3).toString() : data.get(0).toString();
                assertTrue("Bad Test: " + length + " items in test '" + this.message + "', must have 1, 3, or 4 items", false);
            }
            SignatureOptions signatureOptions = SignatureOptions.COMMON;
            this.scriptVerifyFlags = ScriptVerifyFlag.fromString(scriptVerifyString);
            if (this.scriptVerifyFlags.contains(ScriptVerifyFlag.SCIV)) {
                signatureOptions = SignatureOptions.SCIV;
            }
            transaction = buildSpendingTransaction(scriptSig, buildCreditingTransaction(scriptPubKey));
            TransactionOutput txOutput = new TransactionOutput.Builder()
                    .script(scriptPubKey).build();
            scriptValidator = createScriptValidator(transaction, 0, txOutput, this.scriptVerifyFlags, signatureOptions);
        }

        private ScriptValidator.ScriptValidation createScriptValidator(Transaction tx, int inr, TransactionOutput source, EnumSet<ScriptVerifyFlag> flags, SignatureOptions signatureOptions) {
            if (scriptValidationMode == BitcoinValidatorConfig.ScriptEngine.BITCOIN_LIBCONSENSUS) {
                return new NativeBitcoinScriptEvaluation(tx, inr, source, flags, signatureOptions);
            } else {
                return new JavaBitcoinScriptEvaluation(tx, inr, source, flags, signatureOptions);
            }
        }

        private Transaction buildCreditingTransaction(Script scriptPubKey) {
            return new Transaction.Builder()
                    .version(1)
                    .lockTime(0)
                    .inputs(TransactionInput.create()
                            .script(Script.create().op(OP_FALSE).op(OP_FALSE).build())
                            .source(Outpoint.COINBASE)
                            .sequence(-1)    // this matches crediting transaction from bitcoin core
                            .build())
                    .outputs(TransactionOutput.create()
                            .script(scriptPubKey)
                            .value(0)
                            .build())
                    .build();
        }

        private Transaction buildSpendingTransaction(Script scriptSig, Transaction txCredit) {
            return new Transaction.Builder()
                    .version(1)
                    .lockTime(0)
                    .inputs(TransactionInput.create()
                            .source(new Outpoint(txCredit.getID(), 0))
                            .script(scriptSig)
                            .sequence(-1)   // this matches spending transaction from bitcoin core
                            .build())
                    .outputs(TransactionOutput.create()
                            .value(0)
                            .build())
                    .build();
        }


    }

}
