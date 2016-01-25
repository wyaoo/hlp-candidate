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
package org.hyperledger.core;

import org.hyperledger.common.*;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.*;

public class MempoolTest {

    private void assertMempool(Mempool mempool, Transaction... txs) {
        assertEquals(Arrays.asList(txs), mempool.getInventory());
    }

    @Test
    public void test() throws HyperLedgerException {
        Mempool mempool = new Mempool();

        // addTransaction, remove
        ValidatedTransaction t1 = new ValidatedTransaction(new Transaction.Builder().outputs(
                new TransactionOutput.Builder().value(1).build()).build(), 0);
        mempool.add(t1);
        assertMempool(mempool, t1);
        assertNotNull(mempool.get(t1.getID()));
        mempool.remove(t1.getID(), false);
        assertMempool(mempool);
        assertNull(mempool.get(t1.getID()));

        ValidatedTransaction t2 = new ValidatedTransaction(
                new Transaction.Builder().inputs(
                        new TransactionInput.Builder().source(new Outpoint(t1.getID(), 0)
                        ).build())
                        .outputs(
                                new TransactionOutput.Builder().value(1).build()
                        ).build(), 0);


        mempool.add(t1);
        mempool.add(t2);

        // double spend
        boolean exception = false;
        try {
            mempool.add(t2);
        } catch (HyperLedgerException e) {
            exception = true;
        }
        assertTrue(exception);


        // transitive remove
        mempool.remove(t1.getID(), true);
        assertNull(mempool.get(t2.getID()));
        assertMempool(mempool);

        // non transitive remove
        mempool.add(t1);
        mempool.add(t2);
        mempool.remove(t1.getID(), false);
        assertNotNull(mempool.get(t2.getID()));
        assertMempool(mempool, t2);

        mempool.remove(t2.getID(), true);
        assertNull(mempool.get(t2.getID()));
        assertMempool(mempool);
    }
}
