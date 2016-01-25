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
import com.typesafe.config.ConfigFactory;
import org.hyperledger.HyperLedgerSettings;
import org.hyperledger.account.CoinBucket;
import org.hyperledger.account.KeyListChain;
import org.hyperledger.account.color.ColoredTransactionProposal;
import org.hyperledger.common.*;
import org.hyperledger.common.color.Color;
import org.hyperledger.common.color.ColoredTransactionOutput;
import org.hyperledger.common.color.NativeAsset;
import org.hyperledger.core.bitcoin.BitcoinValidatorFactory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

public class NativeAssetValidatorTest {

    private Address myAddress;
    private Transaction validTx;
    private TID validTxID;
    private NativeAssetValidator validator;
    private NativeAsset asset;
    private Transaction prevTx;
    private HashMap<Outpoint, Transaction> sources;
    private KeyListChain myChain;

    @After
    public void tearDown() {
        HyperLedgerSettings.initialize(ConfigFactory.load(ConfigFactory.parseString("hyperledger{feature{native-assets: false}}")));
    }

    @Before
    public void setUp() throws Exception {
        HyperLedgerSettings.initialize(ConfigFactory.load(ConfigFactory.parseString("hyperledger{feature{native-assets: true}}")));

        PrivateKey myKey = PrivateKey.parseWIF("KwT7YSs8gKqBjJRGKT3XhrBR4pL8CK8fvv7EjBseAkQ7oKFuoSvG");
        myAddress = myKey.getAddress();
        myChain = new KeyListChain(myKey);
        prevTx = Transaction.create()
                .outputs(TransactionOutput.create().payTo(myAddress).value(100000).build())
                .build();
        sources = Maps.newHashMap();
        addToSources(prevTx, 0, sources);

        Transaction validTxProposed = Transaction.create()
                .inputs(
                        TransactionInput.create().source(Outpoint.NULL).build(),
                        TransactionInput.create().source(prevTx.getID(), 0).build())
                .outputs(
                        ColoredTransactionOutput.create().color(new NativeAsset(TID.INVALID, 0)).payTo(myAddress).quantity(123456).build(),
                        TransactionOutput.create().payTo(myAddress).value(95000).build())
                .build();
        validTx = sign(validTxProposed, prevTx);
        validTxID = validTx.getID();
        BitcoinValidatorFactory validationFactory = new BitcoinValidatorFactory();
        validator = new NativeAssetValidator(validationFactory.getConfig());
        asset = new NativeAsset(validTxID, 0);
    }

    private Transaction sign(Transaction proposed, Transaction... prevs) throws HyperLedgerException {
        CoinBucket bucket = new CoinBucket();
        for (Transaction prev : prevs) {
            bucket.addAll(prev.getCoins());
        }
        ColoredTransactionProposal proposal = new ColoredTransactionProposal(bucket, proposed);
        return proposal.sign(myChain);
    }

    @Test
    public void testGetValueOut() throws Exception {
        assertTrue(validator.isAssetDefinition(validTx, 0));
        assertTrue(validator.isAssetBeingDefined(validTx, asset));
        assertEquals(123456, validator.getValueOut(validTx, asset));
    }

    @Test
    public void testGetValuesOut() throws Exception {
        assertEquals(1, validator.getValuesOut(validTx, false).size());
        Map<NativeAsset, Long> values = validator.getValuesOut(validTx, true);
        assertEquals(95000, (long) values.get(NativeAsset.BITCOIN));

        Map<NativeAsset, Long> values1 = validator.getValuesOut(validTx, true);
        assertEquals(2, values1.size());
        assertEquals(123456, (long) values1.get(asset));
    }

    @Test
    public void testValidate() throws Exception {
        validator.validateTransaction(validTx, 1, sources);
        Transaction txProposed1 = Transaction.create()
                .inputs(TransactionInput.create().source(validTxID, 0).build(),
                        TransactionInput.create().source(validTxID, 1).build())
                .outputs(ColoredTransactionOutput.create().color(asset).quantity(123450).build(),
                        TransactionOutput.create().payTo(myAddress).value(90000).build())
                .build();
        Transaction tx1 = sign(txProposed1, validTx);

        HashMap<Outpoint, Transaction> sources1 = Maps.newHashMap();
        addToSources(validTx, 0, sources1);
        addToSources(validTx, 1, sources1);
        validator.validateTransaction(tx1, 1, sources1);
    }

    @Test
    public void testValidateMulti() throws Exception {
        Transaction prev1 = Transaction.create()
                .outputs(TransactionOutput.create().payTo(myAddress).value(5000).build())
                .build();
        Transaction txProposed1 = Transaction.create()
                .inputs(TransactionInput.create().source(validTxID, 0).build(),
                        TransactionInput.create().source(Outpoint.NULL).build(),
                        TransactionInput.create().source(prev1.getID(), 0).build())
                .outputs(ColoredTransactionOutput.create().color(asset).quantity(123000).build(),
                        ColoredTransactionOutput.create().color(asset).quantity(456).build(),
                        ColoredTransactionOutput.create().color(new NativeAsset(TID.INVALID, 1)).quantity(55555).build())
                .build();
        Transaction tx1 = sign(txProposed1, validTx, prev1);

        HashMap<Outpoint, Transaction> sources1 = Maps.newHashMap();
        addToSources(validTx, 0, sources1);
        addToSources(prev1, 0, sources1);
        validator.validateTransaction(tx1, 1, sources1);
        assertEquals(123456, validator.getValueOut(tx1, asset));
        assertEquals(55555, validator.getValueOut(tx1, new NativeAsset(tx1.getID(), 1)));
        assertEquals(0, validator.getValueOut(tx1, Color.BITCOIN));
    }

    @Test
    public void testInvalid() throws Exception {
        // No output after creation input
        Transaction invalid1 = Transaction.create()
                .inputs(TransactionInput.create().source(Outpoint.NULL).build())
                .outputs(ColoredTransactionOutput.create().color(new NativeAsset(TID.INVALID, 0)).quantity(123456).build(),
                        TransactionOutput.create().payTo(myAddress).build())
                .build();
        assertFalse(validator.isAssetDefinition(invalid1, 0));
        try {
            validator.validateTransaction(invalid1, 1, sources);
            fail();
        } catch (HyperLedgerException ignored) {
        }

        // Valid, but isn't a creation
        Transaction invalidProposed2 = Transaction.create()
                .inputs(TransactionInput.create().source(prevTx.getID(), 0).build())
                .outputs(ColoredTransactionOutput.create().color(new NativeAsset(TID.INVALID, 0)).quantity(123456).build(),
                        TransactionOutput.create().payTo(myAddress).build())
                .build();
        Transaction invalid2 = sign(invalidProposed2, prevTx);
        assertFalse(validator.isAssetDefinition(invalid2, 0));
        validator.validateTransaction(invalid2, 1, sources);

        // Too much output
        Transaction invalidProposed3 = Transaction.create()
                .inputs(TransactionInput.create().source(validTxID, 0).build(),
                        TransactionInput.create().source(validTxID, 1).build())
                .outputs(ColoredTransactionOutput.create().color(asset).quantity(123457).build(),
                        TransactionOutput.create().payTo(myAddress).value(90000).build())
                .build();
        Transaction invalid3 = sign(invalidProposed3, validTx);
        HashMap<Outpoint, Transaction> sources3 = Maps.newHashMap();
        addToSources(validTx, 0, sources3);
        addToSources(validTx, 1, sources3);
        try {
            validator.validateTransaction(invalid3, 1, sources3);
            fail();
        } catch (HyperLedgerException ex) {
            assertTrue(ex.getMessage().contains("which is >"));
        }

        // Unknown asset on output
        Transaction invalidProposed4 = Transaction.create()
                .inputs(TransactionInput.create().source(validTxID, 0).build(),
                        TransactionInput.create().source(validTxID, 1).build())
                .outputs(ColoredTransactionOutput.create().color(new NativeAsset(prevTx.getID(), 123)).quantity(123457).build(),
                        TransactionOutput.create().payTo(myAddress).value(90000).build())
                .build();
        Transaction invalid4 = sign(invalidProposed4, validTx);
        HashMap<Outpoint, Transaction> sources4 = Maps.newHashMap();
        addToSources(validTx, 0, sources4);
        addToSources(validTx, 1, sources4);
        try {
            validator.validateTransaction(invalid4, 1, sources4);
            fail();
        } catch (HyperLedgerException ex) {
            assertTrue(ex.getMessage().contains("no such"));
        }
    }

    private void addToSources(Transaction tx, int index, HashMap<Outpoint, Transaction> sources1) {
        sources1.put(new Outpoint(tx.getID(), index), tx);
    }
}
