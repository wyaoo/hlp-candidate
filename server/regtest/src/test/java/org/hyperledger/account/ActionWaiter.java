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
package org.hyperledger.account;

import org.hyperledger.api.APITransaction;
import org.junit.Assert;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class ActionWaiter {

    private final List<Listener> listeners;

    public interface VoidFunction {
        void run() throws Exception;
    }

    private ActionWaiter(ExpectedEvent... expectedEvents) {
        listeners = new ArrayList<>(expectedEvents.length);
        for (ExpectedEvent e : expectedEvents) {
            Listener listener = new Listener(e.account, e.expectedEventCount);
            e.account.addAccountListener(listener);
            listeners.add(listener);
        }
    }

    static class Listener implements AccountListener {
        private final CountDownLatch latch;
        private final ReadOnlyAccount account;
        private final int expectedEventCount;

        private Listener(ReadOnlyAccount account, int expectedEventCount) {
            this.account = account;
            this.expectedEventCount = expectedEventCount;
            latch = new CountDownLatch(expectedEventCount);
        }

        @Override
        public void accountChanged(ReadOnlyAccount account, APITransaction t) {
            latch.countDown();
        }

        private void await() {
            try {
                if (!latch.await(5, TimeUnit.SECONDS)) {
                    Assert.fail("Only " + (expectedEventCount - latch.getCount()) + " event(s) occurred out of the expected " + expectedEventCount + " on account " + account);
                }
            } catch (InterruptedException e) {
            }
            account.removeAccountListener(this);
        }
    }

    private void awaitActions() {
        for (Listener l : listeners) {
            l.await();
        }
    }

    public static void execute(VoidFunction c, ExpectedEvent... expectedEvents) throws Exception {
        ActionWaiter aw = new ActionWaiter(expectedEvents);
        c.run();
        aw.awaitActions();
    }

    public static ExpectedEvent expected(ReadOnlyAccount account, int expectedEventCount) {
        return new ExpectedEvent(account, expectedEventCount);
    }

    public static ExpectedEvent[] expectedOne(ReadOnlyAccount account) {
        return new ExpectedEvent[]{new ExpectedEvent(account, 1)};
    }

    public static ExpectedEvent[] expectedOneOnEach(ReadOnlyAccount... accounts) {
        ExpectedEvent[] result = new ExpectedEvent[accounts.length];
        int i = 0;
        for (ReadOnlyAccount account : accounts) {
            result[i] = new ExpectedEvent(account, 1);
            i++;
        }
        return result;
    }

    public static class ExpectedEvent {
        private final ReadOnlyAccount account;
        private final int expectedEventCount;

        private ExpectedEvent(ReadOnlyAccount account, int expectedEventCount) {
            this.account = account;
            this.expectedEventCount = expectedEventCount;
        }
    }

}
