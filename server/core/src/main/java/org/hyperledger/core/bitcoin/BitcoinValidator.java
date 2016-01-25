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

import org.hyperledger.HyperLedgerSettings;
import org.hyperledger.common.*;
import org.hyperledger.core.*;

import java.io.IOException;
import java.util.*;

public class BitcoinValidator implements BlockHeaderValidator, BlockBodyValidator, TransactionValidator {

    private final BitcoinValidatorConfig parameters;

    public BitcoinValidator(BitcoinValidatorConfig parameters) {
        this.parameters = parameters;
    }

    /**
     * Whether to skip this outpoint in validation - overridden by Native Asset validator
     */
    protected boolean skipOutpoint(Outpoint ignored) {
        return false;
    }

    @Override
    @SuppressWarnings("deprecation")
    public void validateHeader(BlockStore blockStore, StoredHeader b) throws HyperLedgerException {
        // TODO: fix this before 06:28:15 UTC on Sun, 7 February 2106
        if (Integer.toUnsignedLong(b.getCreateTime()) > new Date().getTime() / 1000 + 2 * 60 * 60)
            throw new HyperLedgerException("block header timestamp too far in the future.");

        if (b.getID().toBigInteger().compareTo(BitcoinDifficulty.getTarget(b.getEncodedDifficulty())) > 0)
            throw new HyperLedgerException("block header does not have the required work.");

        if (Integer.compareUnsigned(b.getCreateTime(), ((BitcoinBlockStore) blockStore).medianBlockTime(
                parameters.getBlocktimeMedianWindow())) <= 0)
            throw new HyperLedgerException("block header is too old " + b.getID());

        if (Integer.compareUnsigned(b.getVersion(), 2) < 0 && ((BitcoinBlockStore) blockStore).isBlockVersionUsed(2, b.getPreviousID(),
                parameters.getBlockUpgradeMandatory(), parameters.getVotingWindow())) {
            throw new HyperLedgerException("block header does not have the required version (2).");
        }
        if (Integer.compareUnsigned(b.getVersion(), 3) < 0 && ((BitcoinBlockStore) blockStore).isBlockVersionUsed(3, b.getPreviousID(),
                parameters.getBlockUpgradeMandatory(), parameters.getVotingWindow())) {
            throw new HyperLedgerException("block header does not have the required version (3).");
        }
    }

    @Override
    public void validatePath(BlockStore blockStore, StoredHeader join, List<StoredHeader> path) throws HyperLedgerException {
        int height = join.getHeight();
        int expectedTarget = join.getEncodedDifficulty();
        StoredHeader p = join;
        for (StoredHeader next : path) {
            ++height;

            if (height % parameters.getReviewBlocks() == 0 && height > parameters.getReviewBlocks()) {
                expectedTarget = parameters.getDifficulty().computeNextDifficulty((BitcoinBlockStore) blockStore, p);
            }
            if (next.getEncodedDifficulty() != expectedTarget && !parameters.allowDifficultyMismatch()) {
                throw new HyperLedgerException("Difficulty does not adjust as expected.");
            }
            next.setChainWork(
                    p.getChainWork() + parameters.getDifficulty().getDifficulty(next.getEncodedDifficulty()));
            next.setHeight(height);
            p = next;
        }
    }

    public StoredBlock validateBody(BlockStore blockStore, StoredBlock b, Map<Outpoint, Transaction> referred) throws HyperLedgerException {
        checkBlockSize(b);
        cheapBlockChecks((BitcoinBlockStore) blockStore, b, referred);
        expensiveBlockChecks((BitcoinBlockStore) blockStore, b, referred);
        return b;
    }

    @SuppressWarnings("deprecation")
    private void expensiveBlockChecks(BitcoinBlockStore blockStore, Block b, Map<Outpoint, Transaction> referred) throws HyperLedgerException {
        List<ScriptValidator.ScriptValidation> validations = new ArrayList<>();
        boolean coinbase = true;
        for (Transaction t : b.getTransactions()) {
            if (coinbase) {
                coinbase = false;
            } else {
                for (int i = 0; i < t.getInputs().size(); ++i) {
                    Outpoint source = t.getInput(i).getSource();
                    if (skipOutpoint(source))
                        continue;
                    final TransactionOutput out = referred.get(source).getOutputs().get(source.getOutputIndex());
                    validations.add(getScriptValidation(t, i, out, flagsForExpensiveBlockChecks(blockStore, b)));
                }
            }
        }

        ScriptValidator.ScriptValidationResult result = ScriptValidator.validate(validations);
        if (result.getException() != null)
            throw new HyperLedgerException(result.getException());
        if (!result.isValid()) {
            try {
                throw new HyperLedgerException("script validation fails tx: " + WireFormatter.bitcoin.toWireDump(result.getTransaction())
                        + " [" + result.getInputIndex() + "] " + ByteUtils.toHex(result.getSource().getScript().toByteArray()));
            } catch (IOException e) {
            }
        }
    }

    @SuppressWarnings("deprecation")
    private EnumSet<ScriptVerifyFlag> flagsForExpensiveBlockChecks(BitcoinBlockStore blockStore, Block b) {
        boolean strictP2SH = Integer.compareUnsigned(b.getCreateTime(), parameters.getBIP16SwitchTime()) >= 0;
        boolean bip66 = Integer.compareUnsigned(b.getVersion(), 3) >= 0 &&
                blockStore.isBlockVersionUsed(3, b.getPreviousID(), parameters.getBlockUpgradeMandatory(),
                        parameters.getVotingWindow());

        EnumSet<ScriptVerifyFlag> flags = EnumSet.of(ScriptVerifyFlag.NONE);
        if (strictP2SH) flags.add(ScriptVerifyFlag.P2SH);
        if (bip66) flags.add(ScriptVerifyFlag.DERSIG);
        if (HyperLedgerSettings.getInstance().isCltvEnabled()) flags.add(ScriptVerifyFlag.CHECKLOCKTIMEVERIFY);
        return flags;
    }

    private boolean isFinal(Transaction t, int height, int time) {
        if (t.getLockTime() == 0)
            return true;

        if (Integer.compareUnsigned(t.getLockTime(),
                Integer.compareUnsigned(t.getLockTime(), parameters.getLocktimeThreshold()) < 0 ? height : time) < 0)
            return true;

        for (TransactionInput in : t.getInputs()) {
            if (in.getSequence() != -1)
                return false;
        }
        return true;
    }

    @SuppressWarnings("deprecation")
    private void cheapBlockChecks(BitcoinBlockStore blockStore, StoredBlock b, Map<Outpoint, Transaction> referred) throws HyperLedgerException {
        long totalFee = 0;
        long coinbaseOutput = 0;
        boolean coinbase = true;
        boolean strictP2SH = Integer.compareUnsigned(b.getCreateTime(), parameters.getBIP16SwitchTime()) >= 0;
        final SignaturOperationCounter sigOpCounter = new SignaturOperationCounter(strictP2SH, referred);

        if (b.getTransactions().isEmpty())
            throw new HyperLedgerException("a block must have transactions.");

        Set<TID> notSeen = new HashSet<>();
        for (StoredTransaction t : b.getTransactions()) {
            if (!notSeen.add(t.getID())) // CVE-2012-2459
                throw new HyperLedgerException("duplicate transaction in block.");

            checkTransaction(t);

            if (!isFinal(t, b.getHeight(), b.getCreateTime()))
                throw new HyperLedgerException("contains a non-final transaction.");

            if (coinbase) {
                if (!t.isCoinBase())
                    throw new HyperLedgerException("first transaction in block must be coin base.");
                coinbase = false;
                for (TransactionOutput o : t.getOutputs()) {
                    coinbaseOutput += o.getValue();
                }

                checkCoinbaseHeight(blockStore, b, t);
            } else {
                if (t.isCoinBase())
                    throw new HyperLedgerException("there can only be one coin base in a block.");
                long fee = 0;
                for (TransactionInput in : t.getInputs()) {
                    if (skipOutpoint(in.getSource()))
                        continue;
                    Transaction refTx = referred.get(in.getSource());
                    TransactionOutput r = refTx.getOutputs().get(in.getOutputIndex());
                    fee += r.getValue();
                    if (refTx.isCoinBase() &&
                            b.getHeight() - blockStore.getTransactionHeight(refTx.getID()) < parameters.getCoinbaseMaturity())
                        throw new HyperLedgerException("coin base too young to spend.");
                }
                for (TransactionOutput o : t.getOutputs()) {
                    fee -= o.getValue();
                }
                if (fee < 0) {
                    throw new HyperLedgerException("outputs exceed inputs.");
                }
                t.setFee(fee);
                totalFee += fee;
            }
            sigOpCounter.addSignatureOperationCount(t);
            if (sigOpCounter.getTotal() > parameters.getMaxBlockSigops())
                throw new HyperLedgerException("too many signatures.");
            t.addBlock(b.getID());
        }
        if (coinbaseOutput > parameters.getRewardForHeight(b.getHeight()) + totalFee) {
            throw new HyperLedgerException("coin base output exceeds allowed amount.");
        }
    }

    private void checkCoinbaseHeight(BitcoinBlockStore blockStore, StoredBlock h, StoredTransaction t) throws HyperLedgerException {
        // BIP34
        if (Integer.compareUnsigned(h.getVersion(), 2) >= 0 && blockStore.isBlockVersionUsed(2, h.getPreviousID(),
                parameters.getBlockUpgradeMajority(), parameters.getVotingWindow())) {
            WireFormat.ArrayWriter writer = new WireFormat.ArrayWriter();
            try {
                writer.writeUint32(h.getHeight());
            } catch (IOException e) {
            }
            byte[] expected = writer.toByteArray();
            Script script = t.getInput(0).getScript();
            if (script.size() < expected.length ||
                    !Arrays.equals(Arrays.copyOfRange(script.toByteArray(), 0, expected.length), expected)) {
                throw new HyperLedgerException("block height mismatch in coinbase.");
            }
        }
    }

    private void checkBlockSize(Block b) throws HyperLedgerException {
        WireFormat.SizeWriter writer = new WireFormat.SizeWriter();
        try {
            b.toWire(writer);
        } catch (IOException e) {
        }
        if (writer.size() > parameters.getMaxBlockSize())
            throw new HyperLedgerException("block exceeds size limit.");
    }

    private void checkTransactionSize(Transaction t) throws HyperLedgerException {
        WireFormat.SizeWriter writer = new WireFormat.SizeWriter();
        try {
            HyperLedgerSettings.getInstance().getTxWireFormatter().toWire(t, writer);
        } catch (IOException e) {
        }
        if (writer.size() > parameters.getMaxBlockSize())
            throw new HyperLedgerException("oversize transaction.");
    }

    private void checkTransaction(Transaction t) throws HyperLedgerException {
        if (t.getInputs().isEmpty() || t.getOutputs().isEmpty())
            throw new HyperLedgerException("transaction must have inputs and outputs.");

        long totalOut = 0;
        for (TransactionOutput o : t.getOutputs()) {
            if (o.getValue() < 0 || o.getValue() > parameters.getMaxMoney())
                throw new HyperLedgerException("transaction output is not in money range.");
            totalOut += o.getValue();
            if (totalOut > parameters.getMaxMoney())
                throw new HyperLedgerException("transaction output is not in money range.");
        }

        Set<Outpoint> notSeen = new HashSet<>();
        for (TransactionInput in : t.getInputs()) {
            if (!notSeen.add(in.getSource()))
                throw new HyperLedgerException("duplicate transaction input.");
        }

        if (t.isCoinBase()) {
            Script script = t.getInput(0).getScript();
            if (script.size() < 2 || script.size() > 100)
                throw new HyperLedgerException("coin base script size limit exceeded.");
        } else {
            for (TransactionInput in : t.getInputs()) {
                if (in.getSourceTransactionID().equals(TID.INVALID) && !skipOutpoint(in.getSource()))
                    throw new HyperLedgerException("an input is zero hash.");
            }
        }
    }

    public ScriptValidator.ScriptValidation getScriptValidation(Transaction transaction, int inputIndex, TransactionOutput source,
                                                                EnumSet<ScriptVerifyFlag> flags) {
        switch (parameters.getScriptEngine()) {
            case BITCOIN_JAVA:
                return new JavaBitcoinScriptEvaluation(transaction, inputIndex, source, flags, HyperLedgerSettings.getInstance().getSignatureOptions());
            default:
            case BITCOIN_LIBCONSENSUS:
                return new NativeBitcoinScriptEvaluation(transaction, inputIndex, source, flags, HyperLedgerSettings.getInstance().getSignatureOptions());
        }
    }

    @Override
    public ValidatedTransaction validateTransaction(final Transaction t, int height, Map<Outpoint, Transaction> referred) throws HyperLedgerException {
        checkTransactionSize(t);
        checkTransaction(t);

        if (!isFinal(t, height + 1, (int) new Date().getTime() / 1000))
            throw new HyperLedgerException("not yet final.");

        WireFormat.SizeWriter sizer = new WireFormat.SizeWriter();
        try {
            HyperLedgerSettings.getInstance().getTxWireFormatter().toWire(t, sizer);
        } catch (IOException e) {
        }
        long size = sizer.size();
        if (size > parameters.getMaxBlockSize())
            throw new HyperLedgerException("oversize");

        long fee = 0;
        for (TransactionInput in : t.getInputs()) {
            if (skipOutpoint(in.getSource()))
                continue;

            if (in.getSource().getTransactionId().equals(TID.INVALID))
                throw new HyperLedgerException("coin base only allowed in blocks.");

            if (!in.getSource().isNull())
                fee += referred.get(in.getSource()).getOutputs().get(in.getOutputIndex()).getValue();
        }
        for (TransactionOutput o : t.getOutputs()) {
            fee -= o.getValue();
        }
        if (fee < 0)
            throw new HyperLedgerException("outputs exceed inputs.");

        if (fee < parameters.getMinFee())
            throw new HyperLedgerException("pays no fee.");
        if (fee > parameters.getMaxFee())
            throw new HyperLedgerException("absurdly high fee.");

        List<ScriptValidator.ScriptValidation> validations = new ArrayList<>();
        for (int i = 0; i < t.getInputs().size(); ++i) {
            Outpoint source = t.getInput(i).getSource();
            if (!source.isNull()) {
                final TransactionOutput out = referred.get(source).getOutputs().get(source.getOutputIndex());
                validations.add(getScriptValidation(t, i, out, flagsForValidateTransaction()));
            }
        }

        ScriptValidator.ScriptValidationResult result = ScriptValidator.validate(validations);
        if (result.getException() != null)
            throw new HyperLedgerException(result.getException());
        if (!result.isValid()) {
            try {
                throw new HyperLedgerException("script validation fails tx: " + WireFormatter.bitcoin.toWireDump(result.getTransaction())
                        + " ix: " + result.getInputIndex() + " sig: " + ByteUtils.toHex(result.getSource().getScript().toByteArray()));
            } catch (IOException e) {
            }
        }

        return new ValidatedTransaction(t, fee);
    }

    protected EnumSet<ScriptVerifyFlag> flagsForValidateTransaction() {
        EnumSet<ScriptVerifyFlag> flags = EnumSet.of(ScriptVerifyFlag.P2SH, ScriptVerifyFlag.DERSIG);
        if (HyperLedgerSettings.getInstance().isCltvEnabled()) flags.add(ScriptVerifyFlag.CHECKLOCKTIMEVERIFY);
        return flags;
    }
}
