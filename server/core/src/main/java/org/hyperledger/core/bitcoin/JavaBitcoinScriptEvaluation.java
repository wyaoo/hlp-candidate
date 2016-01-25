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

import org.bouncycastle.crypto.digests.RIPEMD160Digest;
import org.hyperledger.common.*;
import org.hyperledger.core.ScriptValidator;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.EmptyStackException;
import java.util.EnumSet;
import java.util.Stack;

public class JavaBitcoinScriptEvaluation implements ScriptValidator.ScriptValidation {
    private Stack<byte[]> stack = new Stack<>();
    private Stack<byte[]> alt = new Stack<>();
    private final Transaction tx;
    private TransactionOutput source;
    private int inr;
    private final EnumSet<ScriptVerifyFlag> flags;
    private boolean valid = false;
    private final SignatureOptions signatureOptions;

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

    private void push(byte[] data) throws HyperLedgerException {
        if (data.length > 520) {
            throw new HyperLedgerException("Push > 520 bytes");
        }
        stack.push(data);
        if (stack.size() + alt.size() > 1000) {
            throw new HyperLedgerException("Stack overflow");
        }
    }

    private void pushInt(long n) throws HyperLedgerException {
        stack.push(new Script.Number(n).toByteArray());
    }

    private long popInt() throws HyperLedgerException {
        return new Script.Number(stack.pop()).intValue();
    }

    private long popOperand() throws HyperLedgerException {
        if (stack.peek().length > 4) {
            throw new HyperLedgerException("Operand overflow");
        }
        long n = new Script.Number(stack.pop()).intValue();
        if (n > 2147483647 || n < -2147483647) {
            throw new HyperLedgerException("Operand overflow");
        }
        return n;
    }

    private static boolean equals(byte[] a, byte[] b) {
        int l = Math.max(a.length, b.length);
        if (a.length < l) {
            byte[] tmp = new byte[l];
            System.arraycopy(a, 0, tmp, 0, a.length);
            a = tmp;
        }
        if (b.length < l) {
            byte[] tmp = new byte[l];
            System.arraycopy(b, 0, tmp, 0, b.length);
            b = tmp;
        }
        return Arrays.equals(a, b);
    }

    private boolean isFalse(byte[] b) {
        return b.length == 0 || b.length == 1 && ((b[0] & 0xff) == 0x80 || b[0] == 0x00);
    }

    private boolean isTrue(byte[] b) {
        return !isFalse(b);
    }

    private boolean popBoolean() {
        return isTrue(stack.pop());
    }

    private boolean peekBoolean() {
        return isTrue(stack.peek());
    }

    public JavaBitcoinScriptEvaluation(Transaction tx, int inr, TransactionOutput source, EnumSet<ScriptVerifyFlag> flags, SignatureOptions signatureOptions) {
        this.tx = tx;
        this.inr = inr;
        this.source = source;
        this.flags = flags;
        this.signatureOptions = signatureOptions;
    }

    public ScriptValidator.ScriptValidation validate() throws HyperLedgerException {
        Script s1 = tx.getInput(inr).getScript();
        Script s2 = source.getScript();

        valid = evaluateScripts(flags.contains(ScriptVerifyFlag.P2SH), s1, s2);
        return this;
    }

    @SuppressWarnings("unchecked")
    public boolean evaluateScripts(boolean validatePSH, Script s1, Script s2) throws HyperLedgerException {
        Stack<byte[]> copy = new Stack<>();
        if (!evaluateSingleScript(s1)) {
            return false;
        }
        boolean psh = s2.isPayToScriptHash() && validatePSH;
        if (psh) {
            copy = (Stack<byte[]>) stack.clone();
        }
        if (!evaluateSingleScript(s2)) {
            return false;
        }
        if (!popBoolean()) {
            return false;
        }
        if (psh) {
            if (!s1.isPushOnly()) {
                throw new HyperLedgerException("input script for PTH should be push only.");
            }
            stack = copy;
            Script script = new Script(stack.pop());
            if (!evaluateSingleScript(script)) {
                return false;
            }
            return popBoolean();
        }
        return true;
    }

    @SuppressWarnings("incomplete-switch")
    public boolean evaluateSingleScript(Script script) throws HyperLedgerException {
        if (script.size() > 10000) {
            return false;
        }

        alt = new Stack<>();

        Script.Tokenizer tokenizer = new Script.Tokenizer(script, flags.contains(ScriptVerifyFlag.SIGNED_HEADER));
        int codeseparator = 0;

        Stack<Boolean> ignoreStack = new Stack<>();
        ignoreStack.push(false);

        int ifdepth = 0;
        int opcodeCount = 0;
        while (tokenizer.hashMoreElements()) {
            Script.Token token;
            try {
                token = tokenizer.nextToken();
                if (token.op.ordinal() > Opcode.OP_16.ordinal() && ++opcodeCount > 201) {
                    return false;
                }
            } catch (HyperLedgerException e) {
                if (ignoreStack.peek()) {
                    continue;
                }
                throw e;
            }
            if (token.data != null) {
                if (!ignoreStack.peek()) {
                    push(token.data);
                } else {
                    if (token.data.length > 520) {
                        return false;
                    }
                }
                continue;
            }
            switch (token.op) {
                case OP_CAT:
                case OP_SUBSTR:
                case OP_LEFT:
                case OP_RIGHT:
                    return false;
                case OP_INVERT:
                case OP_AND:
                case OP_OR:
                case OP_XOR:
                    return false;
                case OP_VERIF:
                case OP_VERNOTIF:
                    return false;
                case OP_MUL:
                case OP_DIV:
                case OP_MOD:
                case OP_LSHIFT:
                case OP_RSHIFT:
                    return false;
                case OP_2MUL:
                case OP_2DIV:
                    return false;

                case OP_IF:
                    ++ifdepth;
                    if (!ignoreStack.peek() && popBoolean()) {
                        ignoreStack.push(false);
                    } else {
                        ignoreStack.push(true);
                    }
                    break;
                case OP_NOTIF:
                    ++ifdepth;
                    if (!ignoreStack.peek() && !popBoolean()) {
                        ignoreStack.push(false);
                    } else {
                        ignoreStack.push(true);
                    }
                    break;
                case OP_ENDIF:
                    --ifdepth;
                    ignoreStack.pop();
                    break;
                case OP_ELSE:
                    ignoreStack.push(!ignoreStack.pop() || ignoreStack.peek());
                    break;
            }
            if (!ignoreStack.peek()) {
                switch (token.op) {
                    case OP_VERIFY:
                        if (!isTrue(stack.peek())) {
                            return false;
                        } else {
                            stack.pop();
                        }
                        break;
                    case OP_RETURN:
                        return false;
                    case OP_NOP:
                        break;
                    case OP_1NEGATE:
                        pushInt(-1);
                        break;
                    case OP_FALSE:
                        push(new byte[0]);
                        break;
                    case OP_1:
                        pushInt(1);
                        break;
                    case OP_2:
                        pushInt(2);
                        break;
                    case OP_3:
                        pushInt(3);
                        break;
                    case OP_4:
                        pushInt(4);
                        break;
                    case OP_5:
                        pushInt(5);
                        break;
                    case OP_6:
                        pushInt(6);
                        break;
                    case OP_7:
                        pushInt(7);
                        break;
                    case OP_8:
                        pushInt(8);
                        break;
                    case OP_9:
                        pushInt(9);
                        break;
                    case OP_10:
                        pushInt(10);
                        break;
                    case OP_11:
                        pushInt(11);
                        break;
                    case OP_12:
                        pushInt(12);
                        break;
                    case OP_13:
                        pushInt(13);
                        break;
                    case OP_14:
                        pushInt(14);
                        break;
                    case OP_15:
                        pushInt(15);
                        break;
                    case OP_16:
                        pushInt(16);
                        break;
                    case OP_TOALTSTACK:
                        alt.push(stack.pop());
                        break;
                    case OP_FROMALTSTACK:
                        push(alt.pop());
                        break;
                    case OP_2DROP:
                        stack.pop();
                        stack.pop();
                        break;
                    case OP_2DUP: {
                        byte[] a1 = stack.pop();
                        byte[] a2 = stack.pop();
                        push(a2);
                        push(a1);
                        push(a2);
                        push(a1);
                    }
                    break;
                    case OP_3DUP: {
                        byte[] a1 = stack.pop();
                        byte[] a2 = stack.pop();
                        byte[] a3 = stack.pop();
                        push(a3);
                        push(a2);
                        push(a1);
                        push(a3);
                        push(a2);
                        push(a1);
                    }
                    break;
                    case OP_2OVER: {
                        byte[] a1 = stack.pop();
                        byte[] a2 = stack.pop();
                        byte[] a3 = stack.pop();
                        byte[] a4 = stack.pop();
                        push(a4);
                        push(a3);
                        push(a2);
                        push(a1);
                        push(a4);
                        push(a3);
                    }
                    break;
                    case OP_2ROT: {
                        byte[] a1 = stack.pop();
                        byte[] a2 = stack.pop();
                        byte[] a3 = stack.pop();
                        byte[] a4 = stack.pop();
                        byte[] a5 = stack.pop();
                        byte[] a6 = stack.pop();
                        push(a4);
                        push(a3);
                        push(a2);
                        push(a1);
                        push(a6);
                        push(a5);
                    }
                    break;
                    case OP_2SWAP: {
                        byte[] a1 = stack.pop();
                        byte[] a2 = stack.pop();
                        byte[] a3 = stack.pop();
                        byte[] a4 = stack.pop();
                        push(a2);
                        push(a1);
                        push(a4);
                        push(a3);
                    }
                    break;
                    case OP_IFDUP:
                        if (peekBoolean()) {
                            push(stack.peek());
                        }
                        break;
                    case OP_DEPTH:
                        pushInt(stack.size());
                        break;
                    case OP_DROP:
                        stack.pop();
                        break;
                    case OP_DUP: {
                        push(stack.peek());
                    }
                    break;
                    case OP_NIP: {
                        byte[] a1 = stack.pop();
                        stack.pop();
                        push(a1);
                    }
                    break;
                    case OP_OVER: {
                        byte[] a1 = stack.pop();
                        byte[] a2 = stack.pop();
                        push(a2);
                        push(a1);
                        push(a2);
                    }
                    break;
                    case OP_PICK: {
                        long n = popInt();
                        push(stack.get(stack.size() - 1 - (int) n));
                    }
                    break;
                    case OP_ROLL: {
                        long n = popInt();
                        byte[] a = stack.get(stack.size() - 1 - (int) n);
                        stack.remove((int) (stack.size() - 1 - n));
                        push(a);
                    }
                    break;
                    case OP_ROT: {
                        byte[] a = stack.get(stack.size() - 1 - 2);
                        stack.remove(stack.size() - 1 - 2);
                        push(a);
                    }
                    break;
                    case OP_SWAP: {
                        byte[] a = stack.pop();
                        byte[] b = stack.pop();
                        push(a);
                        push(b);
                    }
                    break;
                    case OP_TUCK: {
                        byte[] a = stack.pop();
                        byte[] b = stack.pop();
                        push(a);
                        push(b);
                        push(a);
                    }
                    break;
                    case OP_SIZE:
                        pushInt(stack.peek().length);
                        break;
                    case OP_EQUAL:
                    case OP_EQUALVERIFY: {
                        pushInt(equals(stack.pop(), stack.pop()) ? 1 : 0);
                        if (token.op == Opcode.OP_EQUALVERIFY) {
                            if (!isTrue(stack.peek())) {
                                return false;
                            } else {
                                stack.pop();
                            }
                        }
                    }
                    break;
                    case OP_VER:
                    case OP_RESERVED:
                    case OP_RESERVED1:
                    case OP_RESERVED2:
                        return false;
                    case OP_1ADD:// 0x8b in out 1 is added to the input.
                        pushInt(popOperand() + 1);
                        break;
                    case OP_1SUB:// 0x8c in out 1 is subtracted from the
                        // input.
                        pushInt(popOperand() - 1);
                        break;
                    case OP_NEGATE:// 0x8f in out The sign of the input is
                        // flipped.
                        pushInt(-popOperand());
                        break;
                    case OP_ABS:// 0x90 in out The input is made positive.
                        pushInt(Math.abs(popOperand()));
                        break;
                    case OP_NOT: // 0x91 in out If the input is 0 or 1, it
                        // is
                        // flipped. Otherwise the output will be
                        // 0.
                        pushInt(popOperand() == 0 ? 1 : 0);
                        break;
                    case OP_0NOTEQUAL:// 0x92 in out Returns 0 if the input
                        // is
                        // 0. 1 otherwise.
                        pushInt(popOperand() == 0 ? 0 : 1);
                        break;
                    case OP_ADD:// 0x93 a b out a is added to b.
                        pushInt(popOperand() + popOperand());
                        break;
                    case OP_SUB:// 0x94 a b out b is subtracted from a.
                    {
                        long a = popOperand();
                        long b = popOperand();
                        pushInt(b - a);
                    }
                    break;
                    case OP_BOOLAND:// 0x9a a b out If both a and b are not
                        // 0,
                        // the output is 1. Otherwise 0.
                        pushInt(popOperand() != 0 && popOperand() != 0 ? 1 : 0);
                        break;
                    case OP_BOOLOR:// 0x9b a b out If a or b is not 0, the
                        // output is 1. Otherwise 0.
                    {
                        long a = popOperand();
                        long b = popOperand();
                        pushInt(a != 0 || b != 0 ? 1 : 0);
                    }
                    break;
                    case OP_NUMEQUAL:// 0x9c a b out Returns 1 if the
                        // numbers
                        // are equal, 0 otherwise.
                        pushInt(popOperand() == popOperand() ? 1 : 0);
                        break;
                    case OP_NUMEQUALVERIFY:// 0x9d a b out Same as
                        // OP_NUMEQUAL,
                        // but runs OP_VERIFY afterward.
                        if (popOperand() != popOperand()) {
                            return false;
                        }
                        break;
                    case OP_NUMNOTEQUAL:// 0x9e a b out Returns 1 if the
                        // numbers
                        // are not equal, 0 otherwise.
                        pushInt(popOperand() != popOperand() ? 1 : 0);
                        break;

                    case OP_LESSTHAN:// 0x9f a b out Returns 1 if a is less
                        // than
                        // b, 0 otherwise.
                    {
                        long a = popOperand();
                        long b = popOperand();
                        pushInt(b < a ? 1 : 0);
                    }
                    break;
                    case OP_GREATERTHAN:// 0xa0 a b out Returns 1 if a is
                        // greater than b, 0 otherwise.
                    {
                        long a = popOperand();
                        long b = popOperand();
                        pushInt(b > a ? 1 : 0);
                    }
                    break;
                    case OP_LESSTHANOREQUAL:// 0xa1 a b out Returns 1 if a
                        // is
                        // less than or equal to b, 0
                        // otherwise.
                    {
                        long a = popOperand();
                        long b = popOperand();
                        pushInt(b <= a ? 1 : 0);
                    }
                    break;
                    case OP_GREATERTHANOREQUAL:// 0xa2 a b out Returns 1 if
                        // a is
                        // greater than or equal to
                        // b, 0
                        // otherwise.
                    {
                        long a = popOperand();
                        long b = popOperand();
                        pushInt(b >= a ? 1 : 0);
                    }
                    break;
                    case OP_MIN:// 0xa3 a b out Returns the smaller of a and
                        // b.
                        pushInt(Math.min(popOperand(), popOperand()));
                        break;
                    case OP_MAX:// 0xa4 a b out Returns the larger of a and
                        // b.
                        pushInt(Math.max(popOperand(), popOperand()));
                        break;
                    case OP_WITHIN: // 0xa5 x min max out Returns 1 if x is
                        // within the specified range
                        // (left-inclusive), 0 otherwise.
                    {
                        long a = popOperand();
                        long b = popOperand();
                        long c = popOperand();
                        pushInt(c >= b && c < a ? 1 : 0);
                    }
                    break;
                    case OP_RIPEMD160: // 0xa6 in hash The input is hashed
                        // using
                        // RIPEMD-160.
                    {
                        RIPEMD160Digest digest = new RIPEMD160Digest();
                        byte[] data = stack.pop();
                        digest.update(data, 0, data.length);
                        byte[] hash = new byte[20];
                        digest.doFinal(hash, 0);
                        push(hash);
                    }
                    break;
                    case OP_SHA1: // 0xa7 in hash The input is hashed using
                        // SHA-1.
                    {
                        try {
                            MessageDigest a = MessageDigest.getInstance("SHA-1");
                            push(a.digest(stack.pop()));
                        } catch (NoSuchAlgorithmException e) {
                            return false;
                        }
                    }
                    break;
                    case OP_SHA256: // 0xa8 in hash The input is hashed
                        // using
                        // SHA-256.
                    {
                        push(Hash.sha256(stack.pop()));
                    }
                    break;
                    case OP_HASH160: // 0xa9 in hash The input is hashed
                        // twice:
                        // first with SHA-256 and then with
                        // RIPEMD-160.
                    {
                        push(Hash.keyHash(stack.pop()));
                    }
                    break;
                    case OP_HASH256: // 0xaa in hash The input is hashed two
                        // times with SHA-256.
                    {
                        push(Hash.hash(stack.pop()));
                    }
                    break;
                    case OP_CODESEPARATOR: // 0xab Nothing Nothing All of
                        // the
                        // signature checking words will
                        // only match signatures to the
                        // data
                        // after the most
                        // recently-executed
                        // OP_CODESEPARATOR.
                        codeseparator = tokenizer.getCursor();
                        break;
                    case OP_CHECKSIGVERIFY: // 0xad sig pubkey True / false
                        // Same
                        // as OP_CHECKSIG, but OP_VERIFY
                        // is
                        // executed afterward.
                        // / no break;
                    case OP_CHECKSIG: // 0xac sig pubkey True / false The
                        // entire
                        // transaction's outputs, inputs,
                        // and
                        // script (from the most
                        // recently-executed
                        // OP_CODESEPARATOR to
                        // the end) are hashed. The
                        // signature
                        // used by OP_CHECKSIG must be a
                        // valid
                        // signature for this hash and
                        // public
                        // key. If it is, 1 is returned, 0
                        // otherwise.
                    {
                        byte[] pubkey = stack.pop();
                        byte[] sig = stack.pop();

                        Script sts = scriptToSign(script, codeseparator);
                        sts = Script.deleteSignatureFromScript(sts, sig);

                        pushInt(validateSignature(pubkey, sig, sts) ? 1 : 0);
                        if (token.op == Opcode.OP_CHECKSIGVERIFY) {
                            if (!isTrue(stack.peek())) {
                                return false;
                            } else {
                                stack.pop();
                            }
                        }
                    }
                    break;
                    case OP_CHECKMULTISIG: // 0xae x sig1 sig2 ... <number
                        // of
                        // signatures> pub1 pub2 <number
                        // of
                        // public keys> True / False For
                        // each signature and public key
                        // pair, OP_CHECKSIG is
                        // executed. If
                        // more public keys than
                        // signatures
                        // are listed, some key/sig
                        // pairs
                        // can fail. All signatures need
                        // to
                        // match a public key. If all
                        // signatures are valid, 1 is
                        // returned, 0 otherwise. Due to
                        // a
                        // bug, one extra unused value
                        // is
                        // removed from the stack.
                        // / no break;
                    case OP_CHECKMULTISIGVERIFY:// 0xaf x sig1 sig2 ...
                        // <number
                        // of signatures> pub1 pub2
                        // ...
                        // <number of public keys>
                        // True
                        // / False Same as
                        // OP_CHECKMULTISIG, but
                        // OP_VERIFY is executed
                        // afterward.
                    {
                        int nkeys = (int) popInt();
                        if (nkeys <= 0 || nkeys > 20) {
                            return false;
                        }
                        opcodeCount += nkeys;
                        if (opcodeCount > 201) {
                            return false;
                        }

                        byte[][] keys = new byte[nkeys][];
                        for (int i = 0; i < nkeys; ++i) {
                            keys[i] = stack.pop();
                        }
                        int required = (int) popInt();
                        if (required < 0 || required > nkeys) {
                            return false;
                        }

                        Script sts = scriptToSign(script, codeseparator);

                        int havesig = 0;
                        byte[][] sigs = new byte[nkeys][];
                        for (int i = 0; i < nkeys && stack.size() > 1; ++i) {
                            sigs[i] = stack.pop();
                            ++havesig;
                            sts = Script.deleteSignatureFromScript(sts, sigs[i]);
                        }
                        stack.pop(); // reproduce Satoshi client bug

                        boolean fSuccess = true;
                        int isig = 0;
                        int ikey = 0;
                        while (fSuccess && havesig > 0) {
                            try {
                                if (validateSignature(keys[ikey], sigs[isig], sts)) {
                                    isig++;
                                    havesig--;
                                }
                            } catch (Exception e) {
                                // attempt to validate other no matter if there are
                                // format error in this
                            }
                            ikey++;
                            nkeys--;

                            if (havesig > nkeys) {
                                fSuccess = false;
                            }
                        }

                        pushInt(fSuccess ? 1 : 0);

                        if (token.op == Opcode.OP_CHECKMULTISIGVERIFY) {
                            if (!isTrue(stack.peek())) {
                                return false;
                            } else {
                                stack.pop();
                            }
                        }
                    }
                    break;
                    case OP_CHECKMULTISIGONSTACK:
                    case OP_CHECKMULTISIGONSTACKVERIFY:
                        // This operation checks if r signatures out of n (0 <= r <= n)
                        // are valid for the given public keys and hash.
                        // The expected arguments on the stack from the top to the bottom are:
                        //   - number of public keys and signatures
                        //   - public keys in growing order from the bottom
                        //   - hash of the data to be checked, which in our case is the hash of the original header
                        //   - the required minimum number of matching signature/public key pairs out of k
                        //   - signatures in the order that they correspond to the public key with the same index, or a zero byte placeholder if the given signature is not provided
                        //
                        // The OP_CHECKMULTISIGONSTACK leaves the result (either 0 or 1) on the stack while
                        // OP_CHECKMULTISIGONSTACKVERIFY aborts the process if the evaluation fails.
                    {
                        if (!flags.contains(ScriptVerifyFlag.SIGNED_HEADER)) {
                            throw new HyperLedgerException("Operation " + token.op + " is not supported unless blockSignature is enabled in the configuration");
                        }
                        try {
                            // number of public keys and signatures
                            int nKeys = (int) popInt();
                            if (nKeys <= 0 || nKeys > 20) {
                                return false;
                            }
                            opcodeCount += nKeys;
                            if (opcodeCount > 201) {
                                return false;
                            }

                            // public keys in growing order from the bottom
                            byte[][] keys = new byte[nKeys][];
                            for (int i = 0; i < nKeys; ++i) {
                                keys[i] = stack.pop();
                            }

                            //hash of the data to be checked, which in our case is the hash of the original header
                            byte[] hash = stack.pop();

                            // the required minimum number of matching signature/public key pairs out of k
                            int nRequired = (int) popInt();
                            if (nRequired < 0 || nRequired > nKeys) {
                                return false;
                            }

                            // signatures in the order that they correspond to the public key with the same index, or a zero byte placeholder if the given signature is not provided
                            byte[][] sigs = new byte[nKeys][];
                            for (int i = 0; i < nKeys; ++i) {
                                sigs[i] = stack.pop();
                            }

                            // process data
                            int matchCount = 0;
                            boolean success = false;
                            for (int i = 0; i < nKeys; i++) {
                                if (PublicKey.verify(hash, sigs[i], keys[i])) {
                                    if (++matchCount >= nRequired) {
                                        success = true;
                                        break;
                                    }
                                }
                            }

                            if (token.op == Opcode.OP_CHECKMULTISIGONSTACKVERIFY && !success) {
                                return false;
                            } else {
                                pushInt(success ? 1 : 0);
                            }
                        } catch (EmptyStackException e) {
                            return false;
                        }
                    }
                    break;
                }
            }
        }

        return ifdepth == 0;
    }

    private Script scriptToSign(Script script, int codeseparator) throws HyperLedgerException {
        byte[] signedScript = new byte[script.size() - codeseparator];
        System.arraycopy(script.toByteArray(), codeseparator, signedScript, 0, script.size() - codeseparator);
        return new Script(signedScript);
    }

    private boolean validateSignature(byte[] pubkey, byte[] sig, Script script) throws HyperLedgerException {
        byte[] hash = TransactionHasher.hashTransaction(tx, inr, sig[sig.length - 1], script, signatureOptions, source);
        if (flags.contains(ScriptVerifyFlag.DERSIG)) {
            try {
                Script.derSig(sig);
            } catch (HyperLedgerException e) {
                return false;
            }
        }
        return PublicKey.verify(hash, sig, pubkey);
    }
}
