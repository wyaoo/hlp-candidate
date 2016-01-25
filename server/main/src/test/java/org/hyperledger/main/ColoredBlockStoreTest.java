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
package org.hyperledger.main;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.hyperledger.account.*;
import org.hyperledger.account.color.ColorIssuer;
import org.hyperledger.account.color.ColoredBaseTransactionFactory;
import org.hyperledger.account.color.ColoredTransactionFactory;
import org.hyperledger.api.BCSAPIException;
import org.hyperledger.common.*;
import org.hyperledger.common.color.Color;
import org.hyperledger.common.color.ColoredTransactionOutput;
import org.hyperledger.common.color.DigitalAssetAnnotation;
import org.hyperledger.common.color.ForeignAsset;
import org.hyperledger.connector.BCSAPIClient;
import org.hyperledger.connector.ConnectorFactory;
import org.hyperledger.connector.InMemoryConnectorFactory;
import org.hyperledger.core.*;
import org.hyperledger.core.bitcoin.BitcoinPersistentBlocks;
import org.hyperledger.core.bitcoin.GenesisBlocks;
import org.hyperledger.core.color.ColoredValidatorConfig;
import org.hyperledger.core.color.ColoredValidatorFactory;
import org.hyperledger.core.kvstore.MemoryStore;
import org.hyperledger.core.signed.BlockSignatureConfig;
import org.junit.Assert;
import org.junit.Test;

import java.security.Security;

public class ColoredBlockStoreTest {
    static {
        Security.addProvider(new BouncyCastleProvider());
    }

    //@Test
    public void colorTest() throws HyperLedgerException {
        ValidatorChain validatorChain = new ValidatorChain(
                new UnitTestBitcoinValidatorFactory(),
                new ColoredValidatorFactory(new ColoredValidatorConfig(true, false))
        );

        DefaultBlockStore blockStore = new DefaultBlockStore(
                validatorChain,
                new BitcoinPersistentBlocks(new MemoryStore()), new CoreOutbox(), new ClientEventQueue(), PrunerSettings.NO_PRUNING, BlockSignatureConfig.DISABLED);

        Block genesis = GenesisBlocks.regtest;

        blockStore.addGenesis(genesis);

        PrivateKey key = PrivateKey.createNew(true);

        Transaction colorGenesis = Transaction.create().inputs(
                TransactionInput.create().source(genesis.getTransaction(0).getID(), 0).build()
        )
                .outputs(
                        TransactionOutput.create().payTo(key.getAddress()).value(5000000000L).build(),
                        TransactionOutput.create().script(DigitalAssetAnnotation.indicateColors(100)).build()
                ).build();
        ValidatedTransaction vt = blockStore.addTransaction(colorGenesis);
        Assert.assertTrue(vt.getOutputs().get(0) instanceof ColoredTransactionOutput);
        ColoredTransactionOutput cto = (ColoredTransactionOutput) vt.getOutputs().get(0);
        Address colorId = genesis.getTransaction(0).getOutput(0).getOutputAddress();
        Assert.assertEquals(colorId, ((ForeignAsset) cto.getColor()).getAssetAddress());

        Block b1 = Block.create().header(BitcoinHeader.create().previousID(genesis.getID()).build()).transactions(colorGenesis).build();
        blockStore.addBlock(b1);
        Block reRead = blockStore.getBlock(b1.getID());
        Assert.assertEquals(colorId, ((ForeignAsset) ((ColoredTransactionOutput) reRead.getTransaction(0).getOutput(0)).getColor()).getAssetAddress());

        Transaction transfer = Transaction.create()
                .inputs(TransactionInput.create().source(colorGenesis.getID(), 0).build())
                .outputs(TransactionOutput.create().script(DigitalAssetAnnotation.indicateColors(50, 50)).build())
                .outputs(TransactionOutput.create().payTo(key.getAddress()).value(2500000000L).build())
                .outputs(TransactionOutput.create().payTo(key.getAddress()).value(2500000000L).build())
                .build();

        vt = blockStore.addTransaction(transfer);
        Assert.assertTrue(vt.getOutputs().get(1) instanceof ColoredTransactionOutput);
        Assert.assertTrue(vt.getOutputs().get(2) instanceof ColoredTransactionOutput);
        cto = (ColoredTransactionOutput) vt.getOutputs().get(1);
        Assert.assertEquals(colorId, ((ForeignAsset) cto.getColor()).getAssetAddress());
        Assert.assertEquals(50, cto.getQuantity());
        cto = (ColoredTransactionOutput) vt.getOutputs().get(2);
        Assert.assertEquals(colorId, ((ForeignAsset) cto.getColor()).getAssetAddress());
        Assert.assertEquals(50, cto.getQuantity());

        Block b2 = Block.create().header(BitcoinHeader.create().previousID(b1.getID()).build()).transactions(transfer).build();
        blockStore.addBlock(b2);
        reRead = blockStore.getBlock(b2.getID());
        Assert.assertEquals(colorId, ((ForeignAsset) ((ColoredTransactionOutput) reRead.getTransaction(0).getOutput(1)).getColor()).getAssetAddress());
        Assert.assertEquals(colorId, ((ForeignAsset) ((ColoredTransactionOutput) reRead.getTransaction(0).getOutput(2)).getColor()).getAssetAddress());
    }

    @Test
    public void issueTest() throws HyperLedgerException, BCSAPIException {
        ValidatorChain validatorChain = new ValidatorChain(
                new UnitTestBitcoinValidatorFactory(),
                new ColoredValidatorFactory(new ColoredValidatorConfig(true, false))
        );

        ClientEventQueue inbox = new ClientEventQueue();
        DefaultBlockStore blockStore = new DefaultBlockStore(
                validatorChain,
                new BitcoinPersistentBlocks(new MemoryStore()), new CoreOutbox(), inbox, PrunerSettings.NO_PRUNING, BlockSignatureConfig.DISABLED);

        ConnectorFactory connectorFactory = new InMemoryConnectorFactory();
        BCSAPIClient api = new BCSAPIClient(connectorFactory);
        api.init();
        BCSAPIServer server = new BCSAPIServer(blockStore, inbox, connectorFactory);
        server.init();

        Block genesis = GenesisBlocks.regtest;

        blockStore.addGenesis(genesis);

        PrivateKey assetKey = PrivateKey.createNew();
        ColorIssuer colorIssuer = new ColorIssuer(assetKey);
        Color color = colorIssuer.getColor();

        ConfirmationManager confirmations = new ConfirmationManager();
        confirmations.init(api, 20);

        confirmations.addConfirmationListener(colorIssuer);
        colorIssuer.sync(api);
        api.registerTransactionListener(colorIssuer);

        Transaction funding = Transaction.create().inputs(TransactionInput.create().source(genesis.getTransaction(0).getID(), 0).build())
                .outputs(TransactionOutput.create().payTo(colorIssuer.getFundingAddress()).value(5000000000L).build()).build();

        api.sendTransaction(funding);

        Block b1 = Block.create().header(BitcoinHeader.create().previousID(genesis.getID()).build()).transactions(funding).build();
        api.sendBlock(b1);

        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
        }

        PrivateKey holderKey = PrivateKey.createNew();

        Transaction issuing = colorIssuer.issueTokens(holderKey.getAddress(), 100, 50000, TransactionFactory.MINIMUM_FEE);

        Assert.assertTrue(issuing.getInput(0).getSource().equals(new Outpoint(funding.getID(), 0)));
        Assert.assertTrue(issuing.getInputs().size() == 1);
        Assert.assertTrue(issuing.getOutput(0).getValue() == 50000);
        Assert.assertTrue(issuing.getOutput(1).getScript().equals(DigitalAssetAnnotation.indicateColors(100)));
        Assert.assertTrue(issuing.getOutput(2).getValue() == 5000000000L - 50000 - TransactionFactory.MINIMUM_FEE);

        api.sendTransaction(issuing);

        Block b2 = Block.create().header(BitcoinHeader.create().previousID(b1.getID()).build()).transactions(issuing).build();
        api.sendBlock(b2);

        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
        }


        Account holderAccount = new BaseAccount(new KeyListChain(holderKey));
        holderAccount.sync(api);
        Assert.assertEquals(100, holderAccount.getConfirmedCoins().getColoredCoins().getCoins(color).getTotalQuantity());

        PrivateKey target = PrivateKey.createNew(true);
        KeyListChain keylist = new KeyListChain();
        keylist.addKey(target);
        ReadOnlyAccount receiver = new BaseReadOnlyAccount(keylist);
        api.registerTransactionListener(receiver);
        confirmations.addConfirmationListener(receiver);

        ColoredTransactionFactory transactionFactory = new ColoredBaseTransactionFactory(holderAccount);

        Transaction transfer = transactionFactory.proposeColored(target.getAddress(), color, 10).sign(holderAccount.getChain());

        api.sendTransaction(transfer);

        Block b3 = Block.create().header(BitcoinHeader.create().previousID(b2.getID()).build()).transactions(transfer).build();
        api.sendBlock(b3);

        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
        }
        holderAccount.sync(api);

        Assert.assertEquals(90, holderAccount.getConfirmedCoins().getColoredCoins().getCoins(color).getTotalQuantity());
        Assert.assertEquals(10, receiver.getConfirmedCoins().getColoredCoins().getCoins(color).getTotalQuantity());
    }
}
