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

import org.hyperledger.common.*;
import org.hyperledger.core.*;
import org.hyperledger.core.bitcoin.BitcoinDifficulty;
import org.hyperledger.core.bitcoin.BitcoinPersistentBlocks;
import org.hyperledger.core.bitcoin.BitcoinValidatorFactory;
import org.hyperledger.core.kvstore.LevelDBStore;
import org.hyperledger.core.signed.BlockSignatureConfig;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.*;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class BootstrapTest {
    static Map<BID, StoredHeader> headers = new HashMap<>();
    private static Map<BID, URL> filenames = new HashMap<>();

    @Test
    public void bootstrap() {
        List<URL> files = new ArrayList<>();

        URL resource = null;
        for (int i = 0; i < 100000; i++) {
            String resourceName = "blk" + String.format("%05d", i) + ".dat";
            resource = ClassLoader.getSystemClassLoader().getResource(resourceName);
            if (resource == null) break;
            files.add(resource);
        }
        assertFalse("Missing data file blk00000.dat", files.isEmpty());
        try {
            CatStream cs = new CatStream(files);
            readHeadersFromBlockFile(cs);
            cs.close();

            if (!headers.isEmpty()) {
                BitcoinValidatorFactory bitcoinValidatorFactory = new BitcoinValidatorFactory();
                ValidatorChain validatorChain = new ValidatorChain(bitcoinValidatorFactory);
                computeWork(bitcoinValidatorFactory.getConfig().getDifficulty());

                LinkedList<StoredHeader> trunk = computeTrunk();

                BlockStore blockStore = new DefaultBlockStore(validatorChain,
                        new BitcoinPersistentBlocks(new LevelDBStore("data", 100 * 1048576)),
                        new CoreOutbox(), new ClientEventQueue(), PrunerSettings.NO_PRUNING, BlockSignatureConfig.DISABLED);

                blockStore.start();

                System.out.println("Read " + trunk.size() + " block headers on trunk");

                // announce header on trunk
                for (StoredHeader h : trunk) {
                    if (blockStore.getHeader(h.getID()) == null) {
                        blockStore.addHeader(h);
                    }
                }

                URL notStored = trunk.size() == 0 ? null : filenames.get(trunk.getFirst().getID());
                List<URL> remaining = new ArrayList<>();
                boolean found = false;
                for (URL f : files) {
                    if (f.equals(notStored))
                        found = true;
                    if (found)
                        remaining.add(f);
                }
                cs = new CatStream(remaining);
                loadBlocksFromBlockFile(blockStore, trunk, cs);

            }
        } catch (IOException | HyperLedgerException e) {
            e.printStackTrace();
        }
    }

    private static class CatStream extends InputStream {
        private InputStream current;
        private Iterator<URL> next;
        private URL currentFile;

        public CatStream(List<URL> filenames) {
            next = filenames.iterator();
        }

        public URL getCurrentFile() {
            return currentFile;
        }

        private void step() throws IOException {
            if (current == null || current.available() == 0)
                if (next.hasNext()) {
                    if (current != null)
                        current.close();
                    URL file = currentFile = next.next();
                    System.out.println("Reading " + file + " ...");
                    current = file.openStream();
                }
        }

        @Override
        public int read() throws IOException {
            step();
            return current.read();
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            step();
            return current.read(b, off, len);
        }

        @Override
        public int read(byte[] b) throws IOException {
            step();
            return current.read(b);
        }

        @Override
        public int available() throws IOException {
            if (current == null || current.available() == 0) {
                step();
            }
            return current.available();
        }

        @Override
        public long skip(long n) throws IOException {
            step();
            // The underlying BufferedInputStream coming from URL.openStream() does not skip n bytes if it would
            // require a buffer refill. Below is a workaround for this.
            long skipped = current.skip(n);
            long skippedTotal = skipped;
            while (skippedTotal != n) {
                // try skipping twice to distinguish a skip to the end of the buffer and reaching the end of stream
                for (int i = 0; i < 2; i++) {
                    long remaining = n - skippedTotal;
                    skipped = current.skip(remaining);
                    if (skipped > 0) break;
                }
                // we could not skip further, i.e.reached the end of the stream, let 's return
                if (skipped == 0) {
                    return skippedTotal;
                }
                skippedTotal += skipped;
            }
            return skippedTotal;
        }

        @Override
        public void close() throws IOException {
            current.close();
        }
    }

    private void loadBlocksFromBlockFile(BlockStore blockStore, List<StoredHeader> trunk, InputStream inputStream) throws IOException, HyperLedgerException {
        Map<BID, Block> pushback = new HashMap<>();
        WireFormat.Reader reader = new WireFormat.Reader(inputStream);

        Iterator<StoredHeader> sh = trunk.iterator();
        BID expecting = sh.next().getID();
        while (sh.hasNext() && inputStream.available() > 0) {
            int magic = reader.readUint32();
            assertEquals(0xd9b4bef9, magic);
            int size = reader.readUint32();
            while (sh.hasNext() && pushback.containsKey(expecting)) {
                if (blockStore.getBlock(expecting) == null)
                    blockStore.addBlock(pushback.remove(expecting));
                expecting = sh.next().getID();
            }
            Block b = Block.fromWire(reader, WireFormatter.bitcoin, BitcoinHeader.class);
            if (b.getID().equals(expecting)) {
                if (blockStore.getBlock(expecting) == null)
                    blockStore.addBlock(b);
                expecting = sh.next().getID();
            } else {
                pushback.put(b.getID(), b);
            }
        }
    }


    private void readHeadersFromBlockFile(CatStream inputStream) throws IOException, HyperLedgerException {
        WireFormat.Reader reader = new WireFormat.Reader(inputStream);

        while (inputStream.available() > 0) {
            int magic = reader.readUint32();
            assertEquals(0xd9b4bef9, magic);
            int size = reader.readUint32();
            BitcoinHeader b = BitcoinHeader.fromWire(reader);
            StoredHeader sh = new StoredHeader(b, 0.0, 0);
            headers.put(b.getID(), sh);
            filenames.put(b.getID(), inputStream.getCurrentFile());
            inputStream.skip(size - 80);
        }
    }

    private LinkedList<StoredHeader> computeTrunk() {
        System.out.println("Computing trunk...");
        BID top = BID.INVALID;
        double highestWork = 0.0;

        for (StoredHeader h : headers.values()) {
            if (h.getChainWork() > highestWork) {
                highestWork = h.getChainWork();
                top = h.getID();
            }
        }

        LinkedList<StoredHeader> trunk = new LinkedList<>();
        StoredHeader t = headers.get(top);
        while (!t.getPreviousID().equals(BID.INVALID)) {
            trunk.addFirst(t);
            t = headers.get(t.getPreviousID());
        }
        return trunk;
    }

    private void computeWork(BitcoinDifficulty diff) {
        System.out.println("Computing work...");
        // this is ugly, but simple recursive code would cause stack overflow
        for (StoredHeader h : headers.values()) {
            if (h.getHeight() == 0) {
                LinkedList<StoredHeader> path = new LinkedList<>();
                StoredHeader prev = null;
                StoredHeader current = h;
                do {
                    if (current.getPreviousID().equals(BID.INVALID))
                        break;
                    path.addFirst(current);
                    prev = headers.get(current.getPreviousID());
                    current = prev;
                } while (prev != null && prev.getHeight() == 0);
                if (prev != null) {
                    for (StoredHeader n : path) {
                        n.setChainWork(prev.getChainWork() + diff.getDifficulty(n.getEncodedDifficulty()));
                        n.setHeight(prev.getHeight() + 1);
                        prev = n;
                    }
                }
            }
        }
    }
}
