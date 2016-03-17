# GOAL
Merge contributions of IBM, Digital Asset and Blocksteram to a code base that satisfies below requirements.

## Working network
The code SHOULD implement nodes of a network. Nodes SHOULD store and extend their own copy of the block chain in accordance with network wide consenus.
Nodes SHOULD be able leave and re-join the network any time. The block chain of newly joined or re-joined nodes SHOULD converge to the network wide consensus.

### Tasks
  * a dummy implementation of permissioning

## Structural block chain API
Nodes SHOULD offer structural API implementing to DA's BCSAPI interface. The implementation MAY ignore functions other than:
```java
    /**
     * Get chain height of the trunk list
     *
     * @throws BCSAPIException
     */
     int getChainHeight() throws BCSAPIException;
    
    /**
     * Get a list of block ids starting from the block with the given id
     *
     * @param blockId - block hash to start the listing from, zero hash means start from the top block
     * @param count   - how many block ids to return, default is 20 if a non-positive number is provided
     * @return header list or null if block id is unknown
     * @throws BCSAPIException
     */
    APIBlockIdList getBlockIds(BID blockId, int count) throws BCSAPIException;

    /**
     * Get block header for the hash
     *
     * @param hash - block hash
     * @return block header or null if hash is unknown
     * @throws BCSAPIException
     */
    APIHeader getBlockHeader(BID hash) throws BCSAPIException;

    /**
     * Get block for the hash
     *
     * @param hash - block hash
     * @return block or null if hash is unknown
     * @throws BCSAPIException
     */
    APIBlock getBlock(BID hash) throws BCSAPIException;

    /**
     * Get the transaction identified by the hash, if it is on the current trunk (longest chain)
     *
     * @param hash - transaction hash (id)
     * @return transaction or null if no transaction with that hash on the trunk
     * @throws BCSAPIException
     */
    APITransaction getTransaction(TID hash) throws BCSAPIException;

    /**
     * Get the input transactions of the given transaction
     *
     * @param txId - transaction id
     * @return list of input transactions
     */
    List<APITransaction> getInputTransactions(TID txId) throws BCSAPIException;

    /**
     * Send a signed transaction to the network.
     *
     * @param transaction - a signed transaction
     * @throws BCSAPIException
     */
    void sendTransaction(Transaction transaction) throws BCSAPIException;
    
    /**
     * Register a transactions listener.
     * All valid transactions observed on the network will be forwarded to this listener.
     *
     * @param listener will be called for every validated transaction
     * @throws BCSAPIException
     */
    void registerTransactionListener(TransactionListener listener) throws BCSAPIException;

    /**
     * Remove a listener for validated transactions
     *
     * @param listener - a previously registered transaction listener
     */
    public void removeTransactionListener(TransactionListener listener);

    /**
     * Register a block listener.
     * All validated new blocks on the network, that extend the longest chain, will be forwarded to this listener.
     *
     * @param listener will be called for every validated new block
     * @throws BCSAPIException
     */
    void registerTrunkListener(TrunkListener listener) throws BCSAPIException;

    /**
     * remove a trunk listener previously registered
     *
     * @param listener
     */
    void removeTrunkListener(TrunkListener listener);

    /**
     * Generate a trunk update to catch up from current inventory.
     * Useful for a client that was disconnected from the network. The client might provide a trunk list of his
     * knowledge and the server replies with the appropriate extension list. The extension might not continue at
     * the last hast of the client's inventory, but on one earlier, indicating that the longest chain is a fork of that.
     *
     * @param inventory of block hashes known, highest first
     * @param limit     maximum number of blocks or header expected, if inventory is empty
     * @param headers   indicate if headers or full blocks are expected
     * @param listener  a listener for trunk extensions
     * @throws BCSAPIException
     */
    void catchUp(List<BID> inventory, int limit, boolean headers, TrunkListener listener) throws BCSAPIException;
    
    /**
     * Scan transactions using an address in the given set.
     * The listener will be called for every transaction matching the search criteria AND with every transaction
     * spending an output of a transaction that matches the criteria.
     * This call will not return until the listener is called for all transactions identified.
     *
     * @param addresses - address set
     * @param listener  - the transaction listener will be called for all transactions found, in chronological order.
     * @throws BCSAPIException
     */
    void scanTransactionsForAddresses(Set<Address> addresses, TransactionListener listener)
            throws BCSAPIException;

    /**
     * Scan transactions for reference of any address of a master key.
     * The listener will be called for every transaction matching the search criteria AND with every transaction
     * spending an output of a transaction that matches the criteria. The calls will happen in chronological order.
     * This call will not return until the listener is called for all transactions identified.
     *
     * @param master    - public master key
     * @param lookAhead - look ahead window while scanning for addresses. The server assumes that the gap between consecutive addresses of the master key used on the
     *                  block chain is not bigger than lookAhead.
     * @param listener  - the transaction listener will be called for all transactions found, in chronological order.
     * @throws BCSAPIException
     */
    void scanTransactions(MasterPublicKey master, int lookAhead, TransactionListener listener) throws BCSAPIException;

```

### Tasks
  * Map above BCSAPI to structural API available in OBC, bridge the gap doing subsequent tasks.
  * Implement serialization for DA - Blockstream transaction
  * Implement scan methods using an OBC provided generic transaction content search functions.


## Validate blocks
Nodes SHOULD validate blocks with a plug-able code. The context of the validation is the block candidate and the block chain as recorded earlier.
The node SHOULD NOT store a block if the validation marks it invalid. Block SHOULD contain at least following information:
  * block creation time
  * block version
  * ordered list of transactions
  
  
### Tasks
  * Define block validation interface
  * Execute transaction validations within block context
  

## Validate transactions
Nodes SHOULD validate transactions by executing a chain code. The node SHOULD NOT store or propagate a transaction if chain code marks it invalid.
Transaction validation SHOULD be executed also as part of block validation in the order transactions are included into the block. 
Any transaction validation error SHOULD render the enclosing block invalid. A transaction SHOULD contain at least following information:
  * list of referred earlier transaction outputs
  * list of provided transaction outputs
    
The transaction validation starts with retrieving referred transaction outputs. An output might be referred only by one transaction, 
an already referenced or missing input SHOULD make the transaction invalid. Both transaction inputs and transaction outputs contain a script
to be executed as part of the validation. The script of the transaction input SHOULD be concatenated with the transaction output script of
the refferred output and SHOULD be validated through execution of Blockstream's libconsenus library.

### Tasks
  * Write chain code that wraps existing validators

