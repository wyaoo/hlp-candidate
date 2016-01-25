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

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import org.hyperledger.api.*;
import org.hyperledger.common.*;
import org.hyperledger.connector.*;
import org.hyperledger.core.*;
import org.hyperledger.core.bitcoin.BitcoinMiner;
import org.hyperledger.core.conf.CoreAssembly;
import org.hyperledger.core.conf.CoreAssemblyFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public class BCSAPIServer {
    private static final Logger log = LoggerFactory.getLogger(BCSAPIServer.class);

    private final BlockStore store;
    private final ClientEventQueue inbox;

    private final ConnectorFactory connectionFactory;
    private final ExecutorService requestProcessor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
    private final String serverVersion;
    private Connector connection;

    public BCSAPIServer(CoreAssembly assembly, ConnectorFactory connectorFactory) {
        this(assembly.getBlockStore(), assembly.getClientEventQueue(), connectorFactory);
    }

    public BCSAPIServer(BlockStore store, ClientEventQueue inbox, ConnectorFactory connectorFactory) {
        this.connectionFactory = connectorFactory;
        this.store = store;
        this.inbox = inbox;
        BufferedReader input = new BufferedReader(new InputStreamReader(
                getClass().getClassLoader().getResourceAsStream("version.txt")));
        String version = "unknown";
        try {
            version = input.readLine();
            input.close();
        } catch (IOException ignored) {
        }
        serverVersion = version;
    }

    public ConnectorFactory getConnectorFactory() {
        return connectionFactory;
    }

    public String getServerVersion() {
        return serverVersion;
    }

    private static void closeSession(ConnectorSession session) {
        if (session != null) {
            try {
                session.close();
            } catch (ConnectorException ignored) {
            }
        }
    }

    private void addTopicListener(String topic, SessionMessageListener listener) throws ConnectorException {
        ConnectorSession session = connection.createSession();
        listener.setSession(session);
        ConnectorDestination destination = session.createTopic(topic);
        ConnectorConsumer consumer = session.createConsumer(destination);
        consumer.setMessageListener(listener);
    }

    private void addQueueListener(String queue, SessionMessageListener listener) throws ConnectorException {
        ConnectorSession session = connection.createSession();
        listener.setSession(session);
        ConnectorDestination destination = session.createQueue(queue);
        ConnectorConsumer consumer = session.createConsumer(destination);
        consumer.setMessageListener(listener);
    }

    private void startInboxProcessor() {
        Thread inboxProcessor = new Thread(() -> {
            while (true) {
                try {
                    ClientEventQueue.StoreEvent event = inbox.getNextStoreEvent();
                    if (event instanceof ClientEventQueue.TransactionAdded) {
                        process(((ClientEventQueue.TransactionAdded) event).getContent());
                    } else if (event instanceof ClientEventQueue.BlockAdded) {
                        BlockStoredInfo info = ((ClientEventQueue.BlockAdded) event).getContent();
                        blockUpdate(info.getRemovedFromFrunk(), info.getAddedToTrunk());
                    } else if (event instanceof ClientEventQueue.HeaderAdded) {
                        HeaderStoredInfo info = ((ClientEventQueue.HeaderAdded) event).getContent();
                        headerUpdate(info.getRemovedFromFrunk(), info.getAddedToTrunk());
                    } else if (event instanceof ClientEventQueue.Rejected) {
                        Hash hash = ((ClientEventQueue.Rejected) event).getContent();
                        String command = ((ClientEventQueue.Rejected) event).getCommand();
                        int code = ((ClientEventQueue.Rejected) event).getCode();
                        rejected(command, hash, code);
                    }
                } catch (Exception ignored) {
                }
            }
        });
        inboxProcessor.setName("server inbox processor");
        inboxProcessor.setDaemon(true);
        inboxProcessor.start();
    }

    public void init() {
        try {
            connection = connectionFactory.getConnector();
            connection.setClientID("hyperledger");
            connection.start();
            addNewTransactionListener();
            addNewBlockListener();
            addChainHeightRequestListener();
            addBlockIdsRequestListener();
            addBlockrequestListener();
            addBlockHeaderRequestListener();
            addTransactionRequestListener();
            addInputTransactionsRequestListener();
            addMatchScanListener();
            addPingListener();
            addAccountScanListener();
            addCatchUpListener();
            addMineListener();
            addDescentantsListener();
            addSpendingTransactionsRequestListener();
            startInboxProcessor();
        } catch (ConnectorException e) {
            log.error("Error creating producer", e);
        }
    }

    public void destroy() {
        try {
            if (connection != null)
                connection.close();
        } catch (ConnectorException ignored) {
        }
    }

    private void addMatchScanListener() throws ConnectorException {
        addMatchScanListener(false, "matchRequest");
        addMatchScanListener(true, "utxoMatchRequest");
    }

    private void addMatchScanListener(final boolean utxo, String topic) throws ConnectorException {
        addQueueListener(topic, new SessionMessageListener() {
            @Override
            public void onMessage(final ConnectorMessage msg) {
                byte[] body;
                try {
                    body = msg.getPayload();
                    BCSAPIMessage.ExactMatchRequest request = BCSAPIMessage.ExactMatchRequest.parseFrom(body);
                    final Set<ByteVector> match = new HashSet<>();
                    for (ByteString bs : request.getMatchList()) {
                        match.add(new ByteVector(bs.toByteArray()));
                    }
                    final long after = request.hasAfter() ? request.getAfter() : 0;
                    log.debug("matchRequest " + (utxo ? "UTXO" : "") + " for ", match.size() + " patterns after " + after);
                    requestProcessor.execute(() -> {
                        ConnectorSession session = null;
                        ConnectorProducer producer = null;
                        try {
                            session = connection.createSession();
                            producer = msg.getReplyProducer();
                            final ConnectorProducer passInProducer = producer;
                            final ConnectorSession passInSession = session;
                            final AtomicInteger counter = new AtomicInteger(0);

                            TransactionProcessor processor = tx -> {
                                APITransaction transaction = toBCSAPITransaction(tx);
                                ConnectorMessage m;
                                try {
                                    m = passInSession.createMessage();
                                    m.setPayload(transaction.toBCSAPIMessage().toByteArray());
                                    passInProducer.send(m);
                                    counter.incrementAndGet();
                                } catch (ConnectorException ignored) {
                                }
                            };

                            for (StoredTransaction tx : store.filterTransactions(match))
                                processor.process(tx);

                            int c = counter.get();
                            log.debug("matchRequest returns " + counter.get() + " transactions from blockchain");

                            for (ValidatedTransaction tx : store.scanUnconfirmedPool(match))
                                processor.process(tx);

                            log.debug("matchRequest returns " + (counter.get() - c) + " transactions from mempool");

                            try {
                                ConnectorMessage m = session.createMessage();
                                producer.send(m); // indicate EOF
                                producer.close();
                            } catch (ConnectorException ignored) {
                            }
                        } catch (HyperLedgerException | ConnectorException e) {
                            log.error("Error while scanning", e);
                            if (session != null && producer != null) {
                                try {
                                    ConnectorMessage m = session.createMessage();
                                    producer.send(m); // indicate EOF
                                    producer.close();
                                } catch (ConnectorException ignored) {
                                }
                            }
                        } finally {
                            closeSession(session);
                        }
                    });
                } catch (ConnectorException | InvalidProtocolBufferException e) {
                    log.error("invalid matchRequest request", e);
                }
            }
        });
    }


    private void addDescentantsListener() throws ConnectorException {
        addQueueListener("descendants", new SessionMessageListener() {
            @Override
            public void onMessage(final ConnectorMessage msg) {
                byte[] body;
                try {
                    body = msg.getPayload();
                    BCSAPIMessage.Hash request = BCSAPIMessage.Hash.parseFrom(body);
                    final List<TID> tids = new ArrayList<TID>();
                    for (ByteString bs : request.getHashList()) {
                        tids.add(new TID(bs.toByteArray()));
                    }
                    log.debug("descendants for " + tids);
                    final AtomicInteger counter = new AtomicInteger(0);
                    requestProcessor.execute(() -> {
                        ConnectorSession session = null;
                        ConnectorProducer producer = null;
                        try {
                            session = connection.createSession();
                            producer = msg.getReplyProducer();
                            final ConnectorProducer passInProducer = producer;
                            final ConnectorSession passInSession = session;
                            TransactionProcessor processor = tx -> {
                                APITransaction transaction = toBCSAPITransaction(tx);
                                try {
                                    ConnectorMessage m = passInSession.createMessage();
                                    m.setPayload(transaction.toBCSAPIMessage().toByteArray());
                                    passInProducer.send(m);
                                    counter.incrementAndGet();
                                } catch (ConnectorException ignored) {
                                }
                            };
                            log.debug("descendants scan starts");
                            for (ValidatedTransaction tx : store.getDescendants(tids))
                                processor.process(tx);

                            log.debug("descendants scan returns " + counter.get() + " transactions");

                            try {
                                ConnectorMessage m = session.createMessage();
                                producer.send(m); // indicate EOF
                                producer.close();
                            } catch (ConnectorException ignored) {
                            }
                        } catch (Exception e) {
                            log.error("Error while scanning", e);
                            if (session != null && producer != null) {
                                try {
                                    ConnectorMessage m = session.createMessage();
                                    producer.send(m); // indicate EOF
                                    producer.close();
                                } catch (ConnectorException ignored) {
                                }
                            }
                        } finally {
                            closeSession(session);
                        }
                    });
                } catch (ConnectorException | InvalidProtocolBufferException e) {
                    log.error("Invalid descendant scan request", e);
                }
            }
        });
    }


    private void addSpendingTransactionsRequestListener() throws ConnectorException {
        addQueueListener("spendingTransactions", new SessionMessageListener() {
            @Override
            public void onMessage(final ConnectorMessage msg) {
                byte[] body;
                try {
                    body = msg.getPayload();
                    BCSAPIMessage.Hash request = BCSAPIMessage.Hash.parseFrom(body);
                    final List<TID> tids = new ArrayList<TID>();
                    for (ByteString bs : request.getHashList()) {
                        tids.add(new TID(bs.toByteArray()));
                    }
                    log.debug("spending transactions for " + tids);
                    final AtomicInteger counter = new AtomicInteger(0);
                    requestProcessor.execute(() -> {
                        ConnectorSession session = null;
                        ConnectorProducer producer = null;
                        try {
                            session = connection.createSession();
                            producer = msg.getReplyProducer();
                            final ConnectorProducer passInProducer = producer;
                            final ConnectorSession passInSession = session;
                            TransactionProcessor processor = tx -> {
                                APITransaction transaction = toBCSAPITransaction(tx);
                                try {
                                    ConnectorMessage m = passInSession.createMessage();
                                    m.setPayload(transaction.toBCSAPIMessage().toByteArray());
                                    passInProducer.send(m);
                                    counter.incrementAndGet();
                                } catch (ConnectorException ignored) {
                                }
                            };
                            log.debug("scan for spending transactions starts");
                            for (ValidatedTransaction tx : store.getSpendingTransactions(tids))
                                processor.process(tx);

                            log.debug("scan for spending transactions returns " + counter.get() + " transactions");

                            try {
                                ConnectorMessage m = session.createMessage();
                                producer.send(m); // indicate EOF
                                producer.close();
                            } catch (ConnectorException ignored) {
                            }
                        } catch (Exception e) {
                            log.error("Error while scanning for spending transactions", e);
                            if (session != null && producer != null) {
                                try {
                                    ConnectorMessage m = session.createMessage();
                                    producer.send(m); // indicate EOF
                                    producer.close();
                                } catch (ConnectorException ignored) {
                                }
                            }
                        } finally {
                            closeSession(session);
                        }
                    });
                } catch (ConnectorException | InvalidProtocolBufferException e) {
                    log.error("Invalid scan for spending transactions request", e);
                }
            }
        });
    }

    private void addAccountScanListener() throws ConnectorException {
        addAccountScanListener("accountRequest");
    }

    private void addAccountScanListener(String topic) throws ConnectorException {
        addQueueListener(topic, new SessionMessageListener() {
            @Override
            public void onMessage(final ConnectorMessage msg) {
                byte[] body;
                try {
                    body = msg.getPayload();
                    BCSAPIMessage.AccountRequest request = BCSAPIMessage.AccountRequest.parseFrom(body);
                    final MasterKey ek = MasterPublicKey.parse(request.getPublicKey());
                    final int lookAhead = request.getLookAhead();
                    log.debug("accountRequest for " + ek.serialize(true));
                    final Set<ByteVector> match = new HashSet<>();
                    final AtomicInteger counter = new AtomicInteger(0);
                    requestProcessor.execute(() -> {
                        ConnectorSession session = null;
                        ConnectorProducer producer = null;
                        try {
                            session = connection.createSession();
                            producer = msg.getReplyProducer();
                            final ConnectorProducer passInProducer = producer;
                            final ConnectorSession passInSession = session;
                            TransactionProcessor processor = tx -> {
                                APITransaction transaction = toBCSAPITransaction(tx);

                                try {
                                    ConnectorMessage m = passInSession.createMessage();
                                    m.setPayload(transaction.toBCSAPIMessage().toByteArray());
                                    passInProducer.send(m);
                                    counter.incrementAndGet();
                                } catch (ConnectorException ignored) {
                                }
                            };
                            log.debug("accountRequest starts " + ek + " " + counter.get() + " transactions from blockchain");
                            for (StoredTransaction tx : store.filterTransactions(match, ek, lookAhead))
                                processor.process(tx);

                            int c = counter.get();
                            log.debug("accountRequest returns " + ek + " " + counter.get() + " transactions from blockchain");

                            for (ValidatedTransaction tx : store.scanUnconfirmedPool(match))
                                processor.process(tx);

                            log.debug("accountRequest returns " + ek + " " + (counter.get() - c)
                                    + " transactions from mempool");
                            try {
                                ConnectorMessage m = session.createMessage();
                                producer.send(m); // indicate EOF
                                producer.close();
                            } catch (ConnectorException ignored) {
                            }
                        } catch (Exception e) {
                            log.error("Error while scanning", e);
                            if (session != null && producer != null) {
                                try {
                                    ConnectorMessage m = session.createMessage();
                                    producer.send(m); // indicate EOF
                                    producer.close();
                                } catch (ConnectorException ignored) {
                                }
                            }
                        } finally {
                            closeSession(session);
                        }
                    });
                } catch (ConnectorException | InvalidProtocolBufferException e) {
                    log.error("invalid filter request", e);
                } catch (HyperLedgerException e) {
                    log.error("Invalid scan account request", e);
                }
            }
        });
    }

    private void addCatchUpListener() throws ConnectorException {
        addQueueListener("catchUpRequest", new SessionMessageListener() {
            @Override
            public void onMessage(final ConnectorMessage o) {
                try {
                    byte[] body = o.getPayload();
                    List<BID> inventory = new ArrayList<>();
                    BCSAPIMessage.CatchUpRequest catchup = BCSAPIMessage.CatchUpRequest.parseFrom(body);
                    for (ByteString hash : catchup.getInventoryList()) {
                        inventory.add(new BID(hash.toByteArray()));
                    }
                    int limit = catchup.getLimit();
                    boolean headers = catchup.getHeaders();
                    log.debug("catchUpRequest");
                    if (headers) {
                        List<BID> added = store.catchUpHeaders(inventory, limit);

                        BCSAPIMessage.TrunkUpdate.Builder blocksBuilder = BCSAPIMessage.TrunkUpdate.newBuilder();
                        for (BID h : added) {
                            blocksBuilder.addAdded(toBCSAPIHeader(store.getHeader(h)).toProtobuf());
                        }

                        reply(o.getReplyProducer(), blocksBuilder.build().toByteArray());
                    } else {
                        List<BID> added = store.catchUpBlocks(inventory, limit);

                        BCSAPIMessage.TrunkUpdate.Builder blocksBuilder = BCSAPIMessage.TrunkUpdate.newBuilder();
                        for (BID blk : added) {
                            blocksBuilder.addAdded(toBCSAPIBlock(store.getBlock(blk)).toBCSAPIMessage());
                        }

                        reply(o.getReplyProducer(), blocksBuilder.build().toByteArray());
                    }
                    log.debug("catchUpRequest replied");
                } catch (Exception e) {
                    log.debug("Rejected invalid catchUpRequest request ", e);
                }
            }
        });
    }

    private void addTransactionRequestListener() throws ConnectorException {
        addQueueListener("transactionRequest", new SessionMessageListener() {
            @Override
            public void onMessage(final ConnectorMessage o) {
                APITransaction t = null;
                try {
                    byte[] body = o.getPayload();
                    TID hash = new TID(BCSAPIMessage.Hash.parseFrom(body).getHash(0).toByteArray());
                    log.debug("transactionRequest for " + hash);
                    t = getTransaction(hash);
                    log.debug("transactionRequest for " + hash + " replied");
                } catch (Exception e) {
                    log.debug("Rejected invalid transaction request ", e);
                } finally {
                    try {
                        if (t != null) {
                            reply(o.getReplyProducer(), t.toBCSAPIMessage().toByteArray());
                        } else {
                            reply(o.getReplyProducer(), null);
                        }
                    } catch (ConnectorException e) {
                    }
                }
            }
        });
    }

    private void addInputTransactionsRequestListener() throws ConnectorException {
        addQueueListener("inputTransactionsRequest", new SessionMessageListener() {
            @Override
            public void onMessage(final ConnectorMessage o) {
                List<APITransaction> txs = null;
                try {
                    byte[] body = o.getPayload();
                    TID hash = new TID(BCSAPIMessage.Hash.parseFrom(body).getHash(0).toByteArray());
                    log.debug("inputTransactionsRequest for " + hash);
                    txs = getInputTransactions(hash);
                    log.debug("inputTransactionsRequest for " + hash + " replied");
                } catch (Exception e) {
                    log.debug("Rejected invalid input transactions request ", e);
                } finally {
                    try {
                        if (txs != null) {
                            BCSAPIMessage.TXS.Builder builder = BCSAPIMessage.TXS.newBuilder();
                            for (APITransaction tx : txs) {
                                if (tx != null) {
                                    builder.addTxs(BCSAPIMessage.OPTIONAL_TX.newBuilder().setTransaction(tx.toBCSAPIMessage()));
                                } else {
                                    builder.addTxs(BCSAPIMessage.OPTIONAL_TX.newBuilder().setIsNull(true));
                                }
                            }
                            BCSAPIMessage.TXS message = builder.build();
                            reply(o.getReplyProducer(), message.toByteArray());
                        } else {
                            reply(o.getReplyProducer(), null);
                        }
                    } catch (ConnectorException e) {
                    }
                }
            }
        });
    }

    private void addChainHeightRequestListener() throws ConnectorException {
        addQueueListener("chainHeightRequest", new SessionMessageListener() {
            @Override
            public void onMessage(final ConnectorMessage o) {
                int height = getChainHeight();
                try {
                    BCSAPIMessage.HEIGHT.Builder builder = BCSAPIMessage.HEIGHT.newBuilder();
                    BCSAPIMessage.HEIGHT heightMessage = builder.setHeight(height).build();

                    reply(o.getReplyProducer(), heightMessage.toByteArray());
                } catch (ConnectorException e) {
                }
            }
        });
    }

    private void addBlockIdsRequestListener() throws ConnectorException {
        addQueueListener("blockIdsRequest", new SessionMessageListener() {
            @Override
            public void onMessage(final ConnectorMessage o) {
                APIBlockIdList ids = null;
                try {
                    byte[] body = o.getPayload();
                    BCSAPIMessage.BLKIDSREQ request = BCSAPIMessage.BLKIDSREQ.parseFrom(body);
                    BID hash = request.hasBlockHash() ? new BID(request.getBlockHash().toByteArray()) : null;
                    int count = request.getCount();
                    log.debug("blockIdsRequest for " + count + " block ids from " + hash);
                    ids = getBlockIds(hash, count);
                    log.debug("blockIdsRequest for " + count + " block ids from " + hash + " replied");
                } catch (Exception e) {
                    log.debug("Rejected invalid block ids request ", e);
                } finally {
                    try {
                        if (ids != null) {
                            BCSAPIMessage.BLKIDS.Builder builder = BCSAPIMessage.BLKIDS.newBuilder();
                            for (BID id : ids.idList) {
                                builder.addBlockIds(ByteString.copyFrom(id.unsafeGetArray()));
                            }
                            builder.setHeight(ids.height);
                            if (ids.previousBlockId != null)
                                builder.setPreviousBlockId(ByteString.copyFrom(ids.previousBlockId.unsafeGetArray()));
                            BCSAPIMessage.BLKIDS message = builder.build();
                            reply(o.getReplyProducer(), message.toByteArray());
                        } else {
                            reply(o.getReplyProducer(), null);
                        }
                    } catch (ConnectorException e) {
                    }
                }
            }
        });
    }

    private void addBlockrequestListener() throws ConnectorException {
        addQueueListener("blockRequest", new SessionMessageListener() {
            @Override
            public void onMessage(final ConnectorMessage o) {
                APIBlock b = null;
                try {
                    byte[] body = o.getPayload();
                    BID hash = new BID(BCSAPIMessage.Hash.parseFrom(body).getHash(0).toByteArray());
                    log.debug("blockRequest for " + hash);
                    b = getBlock(hash);
                    log.debug("blockRequest for " + hash + " replied");
                } catch (Exception e) {
                    log.debug("Rejected invalid block request ", e);
                } finally {
                    try {
                        if (b != null) {
                            reply(o.getReplyProducer(), b.toBCSAPIMessage().toByteArray());
                        } else {
                            reply(o.getReplyProducer(), null);
                        }
                    } catch (ConnectorException e) {
                    }
                }
            }
        });
    }

    private void addBlockHeaderRequestListener() throws ConnectorException {
        addQueueListener("headerRequest", new SessionMessageListener() {
            @Override
            public void onMessage(final ConnectorMessage o) {
                APIHeader b = null;
                try {
                    byte[] body = o.getPayload();
                    BID hash = new BID(BCSAPIMessage.Hash.parseFrom(body).getHash(0).toByteArray());
                    log.debug("headerRequest for " + hash);
                    b = getBlockHeader(hash);
                    log.debug("headerRequest for " + hash + " replied");
                } catch (Exception e) {
                    log.trace("Rejected invalid header block request ", e);
                } finally {
                    try {
                        if (b != null) {
                            reply(o.getReplyProducer(), b.toProtobuf().toByteArray());
                        } else {
                            reply(o.getReplyProducer(), null);
                        }
                    } catch (ConnectorException e) {
                    }
                }
            }
        });
    }

    private void addNewBlockListener() throws ConnectorException {
        addTopicListener("newBlock", new SessionMessageListener() {
            @Override
            public void onMessage(final ConnectorMessage o) {
                try {
                    byte[] body = o.getPayload();
                    Block block = APIBlock.fromProtobuf(BCSAPIMessage.BLK.parseFrom(body));
                    log.debug("newBlock for " + block.getID());
                    store.addBlock(block);
                    reply(o.getReplyProducer(), null);
                    log.debug("newBlock " + block.getID() + " accepted");
                } catch (Exception e) {
                    BCSAPIMessage.ExceptionMessage.Builder builder = BCSAPIMessage.ExceptionMessage.newBuilder();
                    builder.addMessage(e.getMessage());
                    try {
                        reply(o.getReplyProducer(), builder.build().toByteArray());
                        log.debug("newBlock rejected");
                    } catch (ConnectorException ignored) {
                    }
                }
            }
        });
    }

    private void addPingListener() throws ConnectorException {
        addQueueListener("ping", new SessionMessageListener() {
            @Override
            public void onMessage(ConnectorMessage o) {
                try {
                    byte[] body = o.getPayload();
                    BCSAPIMessage.Ping ping = BCSAPIMessage.Ping.parseFrom(body);
                    BCSAPIMessage.Ping.Builder builder = BCSAPIMessage.Ping.newBuilder();
                    builder.setNonce(ping.getNonce());
                    builder.setClientVersion(ping.getClientVersion());
                    builder.setServerVersion(serverVersion);
                    reply(o.getReplyProducer(), builder.build().toByteArray());
                    log.debug("ping replied");
                } catch (Exception ignored) {
                }
            }
        });
    }

    private void addNewTransactionListener() throws ConnectorException {
        addTopicListener("newTransaction", new SessionMessageListener() {
            @Override
            public void onMessage(ConnectorMessage o) {
                try {
                    byte[] body = o.getPayload();
                    APITransaction transaction = APITransaction.fromProtobuf(BCSAPIMessage.TX.parseFrom(body));
                    log.debug("newTransaction " + transaction.getID());
                    sendTransaction(transaction);
                    reply(o.getReplyProducer(), null);
                    log.debug("newTransaction " + transaction.getID() + " accepted");
                } catch (Exception e) {
                    BCSAPIMessage.ExceptionMessage.Builder builder = BCSAPIMessage.ExceptionMessage.newBuilder();
                    if (e.getMessage() != null)
                        builder.addMessage(e.getMessage());
                    try {
                        log.debug("newTransaction rejected");
                        reply(o.getReplyProducer(), builder.build().toByteArray());
                    } catch (ConnectorException ignored) {
                    }
                }
            }
        });
    }

    private void addMineListener() throws ConnectorException {
        addTopicListener("mine", new SessionMessageListener() {
            @Override
            public void onMessage(ConnectorMessage o) {
                try {
                    byte[] body = o.getPayload();
                    Script script =
                            new Script(BCSAPIMessage.Script.parseFrom(body).getScript().toByteArray());
                    Address address = script.getAddress();
                    log.debug("mine into {}", address);
                    BitcoinMiner miner = CoreAssemblyFactory.getAssembly().getMiner();
                    Address originalAddress = miner.getMinerAddress();
                    miner.setMinerAddress(address);
                    Block block = miner.mineAndStoreOneBlock();
                    miner.setMinerAddress(originalAddress);
                    log.debug("new block {}", block);
                    reply(o.getReplyProducer(), new APIHeader(block.getHeader(), store.getFullHeight()).toProtobuf().toByteArray());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        });
    }

    public APITransaction getTransaction(final TID hash) {
        log.trace("get transaction " + hash);
        ValidatedTransaction t;
        try {
            t = store.getTransaction(hash);
            if (t != null)
                return toBCSAPITransaction(t);
        } catch (HyperLedgerException ignored) {
        } finally {
            log.trace("get transaction returned " + hash);
        }
        return null;
    }

    public List<APITransaction> getInputTransactions(final TID hash) {
        log.trace("get input transactions " + hash);
        ValidatedTransaction tx;
        List<APITransaction> txs = new ArrayList<>();
        try {
            tx = store.getTransaction(hash);
            if (tx != null) {
                for (TransactionInput input : tx.getInputs()) {
                    ValidatedTransaction inputTx = store.getTransaction(input.getSourceTransactionID());
                    if (inputTx != null) {
                        txs.add(toBCSAPITransaction(inputTx));
                    } else {
                        txs.add(null);
                    }
                }
            }
            return txs;
        } catch (HyperLedgerException ignored) {
        } finally {
            log.trace("get input transactions returned " + hash);
        }
        return null;
    }

    public int getChainHeight() {
        log.trace("get chain height");
        return store.getFullHeight();
    }

    public APIBlockIdList getBlockIds(BID blockId, int count) {
        log.trace("get " + count + " block ids from " + blockId);

        List<BID> result = new ArrayList<>(count);

        try {
            StoredBlock block = blockId == null ? store.getBlock(store.getFullTop()) : store.getBlock(blockId);
            int height = block.getHeight();
            BID prevBlockId = block.getPreviousID();
            result.add(block.getID());
            for (int i = 0; i < count - 1 && !BID.INVALID.equals(prevBlockId); i++) {
                block = store.getBlock(prevBlockId);
                result.add(prevBlockId);
                prevBlockId = block.getPreviousID();
            }
            return new APIBlockIdList(result, height, BID.INVALID.equals(prevBlockId) ? null : prevBlockId);
        } catch (HyperLedgerException e) {
            // nothing to do
        }
        return null;
    }

    public APIBlock getBlock(final BID hash) {
        log.trace("get block " + hash);
        StoredBlock b;
        APIBlock block = null;
        try {
            if (hash.equals(BID.INVALID))
                b = store.getHighestBlock();
            else
                b = store.getBlock(hash);
            if (b != null) {
                block = toBCSAPIBlock(b);
            }
        } catch (HyperLedgerException ignored) {
        }

        log.trace("get block returned " + hash);
        return block;
    }

    public APIHeader getBlockHeader(final BID hash) {
        APIHeader block = null;
        StoredHeader b;
        try {
            if (hash.equals(BID.INVALID))
                b = store.getHighestHeader();
            else
                b = store.getHeader(hash);
            if (b != null) {
                block = toBCSAPIHeader(b);
            }
        } catch (HyperLedgerException ignored) {
        }
        return block;
    }

    private void sendTransaction(Transaction transaction) throws HyperLedgerException {
        log.trace("send transaction " + transaction.getID());
        store.addClientTransaction(transaction);
    }


    public void process(ValidatedTransaction tx) {
        ConnectorSession session = null;
        try {
            APITransaction transaction = toBCSAPITransaction(tx);
            session = connection.createSession();
            ConnectorMessage m = session.createMessage();
            m.setPayload(transaction.toBCSAPIMessage().toByteArray());
            ConnectorProducer transactionProducer = session.createProducer(session.createTopic("transaction"));
            transactionProducer.send(m);
        } catch (Exception e) {
            log.error("Can not send message ", e);
        } finally {
            closeSession(session);
        }
    }

    private APITransaction toBCSAPITransaction(ValidatedTransaction tx) {
        if (tx instanceof StoredTransaction) {
            StoredTransaction st = (StoredTransaction) tx;
            return new APITransaction(st, store.getTrunkBlockID(tx));
        } else {
            return new APITransaction(tx, null);
        }
    }

    private APIBlock toBCSAPIBlock(StoredBlock b) {
        List<APITransaction> at = b.getTransactions().stream().map(t -> new APITransaction(t, b.getID())).collect(Collectors.toList());

        return new APIBlock(new APIHeader(b.getHeader(), b.getHeight()), at);
    }

    private APIHeader toBCSAPIHeader(StoredHeader b) {
        return new APIHeader(b, b.getHeight());
    }

    public void blockUpdate(final List<BID> removed, final List<BID> extended) {
        ConnectorSession session = null;
        try {
            session = connection.createSession();
            ConnectorProducer trunkProducer = session.createProducer(session.createTopic("trunk"));

            BCSAPIMessage.TrunkUpdate.Builder blocksBuilder = BCSAPIMessage.TrunkUpdate.newBuilder();
            for (BID blk : extended) {
                blocksBuilder.addAdded(toBCSAPIBlock(store.getBlock(blk)).toBCSAPIMessage());
            }

            ConnectorMessage m = session.createMessage();
            m.setPayload(blocksBuilder.build().toByteArray());
            trunkProducer.send(m);
        } catch (Exception e) {
            log.error("Can not send message ", e);
        } finally {
            closeSession(session);
        }
    }

    public void headerUpdate(final List<BID> removed, final List<BID> extended) {
        ConnectorSession session = null;
        try {
            session = connection.createSession();
            ConnectorProducer trunkProducer = session.createProducer(session.createTopic("header"));

            BCSAPIMessage.TrunkUpdate.Builder blocksBuilder = BCSAPIMessage.TrunkUpdate.newBuilder();
            for (BID blk : extended) {
                blocksBuilder.addAdded(toBCSAPIHeader(store.getHeader(blk)).toProtobuf());
            }

            ConnectorMessage m = session.createMessage();
            m.setPayload(blocksBuilder.build().toByteArray());
            trunkProducer.send(m);
        } catch (Exception e) {
            log.error("Can not send message ", e);
        } finally {
            closeSession(session);
        }
    }

    private void rejected(String command, Hash hash, int code) {
        ConnectorSession session = null;
        try {
            session = connection.createSession();
            ConnectorProducer rejectProducer = session.createProducer(session.createTopic("reject"));
            BCSAPIMessage.Reject msg = BCSAPIMessage.Reject.newBuilder().setCommand(command)
                    .setHash(ByteString.copyFrom(hash.unsafeGetArray())).setRejectCode(code).build();
            ConnectorMessage m = session.createMessage();
            m.setPayload(msg.toByteArray());
            rejectProducer.send(m);
        } catch (Exception e) {
            log.error("Can not send message ", e);
        } finally {
            closeSession(session);
        }
    }

    private abstract static class SessionMessageListener implements ConnectorListener {
        private ConnectorSession session;

        protected void reply(ConnectorProducer replier, byte[] msg) {
            try {
                ConnectorMessage m = session.createMessage();
                if (msg != null) {
                    m.setPayload(msg);
                }
                replier.send(m);
            } catch (ConnectorException e) {
                log.trace("can not reply", e);
            } finally {
                try {
                    if (replier != null) {
                        replier.close();
                    }
                } catch (ConnectorException ignored) {
                }
            }
        }

        public void setSession(ConnectorSession session) {
            this.session = session;
        }
    }

    public interface TransactionProcessor {
        void process(ValidatedTransaction transaction);
    }
}
