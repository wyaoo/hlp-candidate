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

import java.util.EnumSet;
import java.util.StringTokenizer;

public enum ScriptVerifyFlag {
    NONE(0),

    // Evaluate P2SH subscripts (softfork safe, BIP16).
    P2SH(1 << 0),

    // Passing a non-strict-DER signature or one with undefined hashtype to a checksig operation causes script failure.
    // Evaluating a pubkey that is not (0x04 + 64 bytes) or (0x02 or 0x03 + 32 bytes) by checksig causes script failure.
    // (softfork safe, but not used or intended as a consensus rule).
    STRICTENC(1 << 1),

    // Passing a non-strict-DER signature to a checksig operation causes script failure (softfork safe, BIP62 rule 1)
    DERSIG(1 << 2),

    // Passing a non-strict-DER signature or one with S > order/2 to a checksig operation causes script failure
    // (softfork safe, BIP62 rule 5).
    LOW_S(1 << 3),

    // verify dummy stack item consumed by CHECKMULTISIG is of zero-length (softfork safe, BIP62 rule 7).
    NULLDUMMY(1 << 4),

    // Using a non-push operator in the scriptSig causes script failure (softfork safe, BIP62 rule 2).
    SIGPUSHONLY(1 << 5),

    // Require minimal encodings for all push operations (OP_0... OP_16, OP_1NEGATE where possible, direct
    // pushes up to 75 bytes, OP_PUSHDATA up to 255 bytes, OP_PUSHDATA2 for anything larger). Evaluating
    // any other push causes the script to fail (BIP62 rule 3).
    // In addition, whenever a stack element is interpreted as a number, it must be of minimal length (BIP62 rule 4).
    // (softfork safe)
    MINIMALDATA(1 << 6),

    // Discourage use of NOPs reserved for upgrades (NOP1-10)
    //
    // Provided so that nodes can avoid accepting or mining transactions
    // containing executed NOP's whose meaning may change after a soft-fork,
    // thus rendering the script invalid; with this flag set executing
    // discouraged NOPs fails the script. This verification flag will never be
    // a mandatory flag applied to scripts in a block. NOPs that are not
    // executed, e.g.  within an unexecuted IF ENDIF block, are *not* rejected.
    DISCOURAGE_UPGRADABLE_NOPS(1 << 7),

    // Require that only a single stack element remains after evaluation. This changes the success criterion from
    // "At least one stack element must remain, and when interpreted as a boolean, it must be true" to
    // "Exactly one stack element must remain, and when interpreted as a boolean, it must be true".
    // (softfork safe, BIP62 rule 6)
    // Note: CLEANSTACK should never be used without P2SH.
    CLEANSTACK(1 << 8),

    // Verify CHECKLOCKTIMEVERIFY
    //
    // See BIP65 for details.
    CHECKLOCKTIMEVERIFY(1 << 9),

    // indicates if header signature and related opcodes are allowed
    SIGNED_HEADER(1 << 10),

    // DAH specific: enable SCIV/SCIC
    SCIV(1 << 30),

    // DAH specific: enable native assets
    NATIVEASSET(1 << 31),;

    private static final String DELIMITER = ",";

    public static EnumSet<ScriptVerifyFlag> defaultSet() {
        return EnumSet.of(DERSIG);
    }

    public static EnumSet<ScriptVerifyFlag> fromString(String str) {
        StringTokenizer st = new StringTokenizer(str, DELIMITER);
        EnumSet<ScriptVerifyFlag> flags = EnumSet.noneOf(ScriptVerifyFlag.class);
        while (st.hasMoreTokens()) {
            String token = st.nextToken().trim();
            flags.add(ScriptVerifyFlag.valueOf(token));
        }
        return flags;
    }

    public static int calcVal(EnumSet<ScriptVerifyFlag> flags) {
        int flagsValue = 0;
        for (ScriptVerifyFlag flag : flags) {
            flagsValue |= flag.val;
        }
        return flagsValue;
    }

    private final int val;

    ScriptVerifyFlag(int val) {
        this.val = val;
    }
}
