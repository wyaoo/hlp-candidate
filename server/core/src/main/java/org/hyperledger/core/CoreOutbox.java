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

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingDeque;

public class CoreOutbox {

    private final CopyOnWriteArrayList<ServerOutboxListener> listeners = new CopyOnWriteArrayList<>();

    public void addListener(ServerOutboxListener l) {
        listeners.add(l);
    }

    public void sendP2PEvent(P2PEvent event) {
        listeners.forEach(l -> l.onP2PEvent(event));
    }

    @FunctionalInterface
    public interface ServerOutboxListener {
        void onP2PEvent(P2PEvent e);
    }

    public static class QueueBasedServerOutboxListener implements ServerOutboxListener {
        private final LinkedBlockingDeque<P2PEvent> p2pEvents = new LinkedBlockingDeque<>();

        @Override
        public void onP2PEvent(P2PEvent e) {
            p2pEvents.offer(e);
        }

        public P2PEvent getNextStoreEvent() throws InterruptedException {
            return p2pEvents.take();
        }
    }

    public abstract static class P2PEvent<T> {
        private T content;

        public T getContent() {
            return content;
        }

        public P2PEvent(T content) {
            this.content = content;
        }
    }

    public static class TransactionAdded extends P2PEvent<ValidatedTransaction> {
        public TransactionAdded(ValidatedTransaction content) {
            super(content);
        }
    }

    public static class BlockAdded extends P2PEvent<BlockStoredInfo> {
        public BlockAdded(BlockStoredInfo content) {
            super(content);
        }
    }
}
