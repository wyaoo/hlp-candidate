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

import org.hyperledger.core.CoreOutbox;
import org.hyperledger.core.ValidatedTransaction;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;

/**
 *
 */
public class CoreOutboxTest {

    private CoreOutbox outbox;
    private CoreOutbox.TransactionAdded testEvent;

    @Before
    public void setup() {
        outbox = new CoreOutbox();
        testEvent = new CoreOutbox.TransactionAdded(mock(ValidatedTransaction.class));
    }

    @Test
    public void testEmptyListeners() throws Exception {
        outbox.sendP2PEvent(testEvent);
    }

    @Test(timeout = 1000)
    public void testQueueBasedListener() throws Exception {
        CoreOutbox.QueueBasedServerOutboxListener l = new CoreOutbox.QueueBasedServerOutboxListener();
        outbox.addListener(l);
        outbox.sendP2PEvent(testEvent);

        CoreOutbox.P2PEvent event = l.getNextStoreEvent();

        assertEquals(testEvent, event);
    }

    @Test
    public void testCustomListener() throws Exception {
        List<CoreOutbox.P2PEvent> events = new ArrayList<>();
        CoreOutbox.ServerOutboxListener l = e -> events.add(e);
        outbox.addListener(l);
        outbox.sendP2PEvent(testEvent);

        assertEquals(1, events.size());
        assertEquals(testEvent, events.get(0));
    }
}
