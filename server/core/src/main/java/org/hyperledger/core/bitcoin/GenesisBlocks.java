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

import org.hyperledger.common.*;
import org.hyperledger.common.color.ColoredTransactionOutput;

public class GenesisBlocks {

    public static final Block satoshi = new Block.Builder()
            .header(BitcoinHeader.create()
                    .version(1)
                    .createTime(1231006505)
                    .difficultyTarget(0x1d00ffff)
                    .nonce(2083236893)
                    .previousID(BID.INVALID)
                    .build())
            .transactions(
                    new Transaction.Builder()
                            .version(1)
                            .inputs(
                                    new TransactionInput(new Outpoint(TID.INVALID, 0), -1,
                                            new Script(ByteUtils.fromHex("04" + "ffff001d" + // difficulty target
                                                    "010445" +
                                                    // "The Times 03/Jan/2009 Chancellor on brink of second bailout for banks"
                                                    "5468652054696d65732030332f4a616e2f32303039204368616e63656c6c6f72206f6e206272696e6b206f66207365636f6e64206261696c6f757420666f722062616e6b73"))
                                    )
                            )
                            .outputs(
                                    new TransactionOutput(5000000000L,
                                            new Script(ByteUtils.fromHex("4104678afdb0fe5548271967f1a67130b7105cd6a828e03909a67962e0ea1f61deb649f6bc3f4cef38c4f35504e51ec112de5c384df7ba0b8d578a4c702b6bf11d5fac"))
                                    )
                            ).build()
            ).build();

    public static final Block testnet3 = new Block.Builder()
            .header(BitcoinHeader.create()
                    .version(1)
                    .createTime(1296688602)
                    .difficultyTarget(0x1d00ffff)
                    .nonce(414098458)
                    .previousID(BID.INVALID)
                    .build())
            .transactions(
                    new Transaction.Builder()
                            .version(1)
                            .inputs(
                                    new TransactionInput(new Outpoint(TID.INVALID, 0), -1,
                                            new Script(ByteUtils.fromHex("04" + "ffff001d" + // difficulty target
                                                    "010445" +
                                                    // "The Times 03/Jan/2009 Chancellor on brink of second bailout for banks"
                                                    "5468652054696d65732030332f4a616e2f32303039204368616e63656c6c6f72206f6e206272696e6b206f66207365636f6e64206261696c6f757420666f722062616e6b73"))
                                    )
                            )
                            .outputs(
                                    new TransactionOutput(5000000000L,
                                            new Script(ByteUtils.fromHex("4104678afdb0fe5548271967f1a67130b7105cd6a828e03909a67962e0ea1f61deb649f6bc3f4cef38c4f35504e51ec112de5c384df7ba0b8d578a4c702b6bf11d5fac"))
                                    )
                            ).build()
            ).build();

    public static final Block regtest = new Block.Builder()
            .header(BitcoinHeader.create()
                    .version(1)
                    .createTime(1296688602)
                    .difficultyTarget(0x207fffff)
                    .nonce(2)
                    .previousID(BID.INVALID)
                    .build())
            .transactions(
                    new Transaction.Builder()
                            .version(1)
                            .inputs(
                                    new TransactionInput(new Outpoint(TID.INVALID, 0), -1,
                                            new Script(ByteUtils.fromHex("04" + "ffff001d" + // difficulty target
                                                    "010445" +
                                                    // "The Times 03/Jan/2009 Chancellor on brink of second bailout for banks"
                                                    "5468652054696d65732030332f4a616e2f32303039204368616e63656c6c6f72206f6e206272696e6b206f66207365636f6e64206261696c6f757420666f722062616e6b73"))
                                    )
                            )
                            .outputs(
                                    new TransactionOutput(5000000000L,
                                            new Script(ByteUtils.fromHex("4104678afdb0fe5548271967f1a67130b7105cd6a828e03909a67962e0ea1f61deb649f6bc3f4cef38c4f35504e51ec112de5c384df7ba0b8d578a4c702b6bf11d5fac"))
                                    )
                            ).build()
            ).build();

    public static Block regtestWithHeaderSignature(byte[] inScriptBytes) {
        byte[] nextScriptHash = Hash.keyHash(inScriptBytes);
        return regtestWithHeaderSignature(inScriptBytes, nextScriptHash);
    }

    public static Block regtestWithHeaderSignature(byte[] inScriptBytes, byte[] nextScriptHash) {
        return new Block.Builder()
                .header(HeaderWithSignatures.create()
                        .inScript(inScriptBytes)
                        .nextScriptHash(nextScriptHash)
                        .version(1)
                        .createTime(1296688602)
                        .difficultyTarget(0x1f06f363)
//                        .difficultyTarget(0x1f00f363) // mining happens roughly in every 30 seconds on current hardware
                        .nonce(180)
                        .previousID(BID.INVALID)
                        .build())
                .transactions(
                        new Transaction.Builder()
                                .version(1)
                                .inputs(
                                        new TransactionInput(new Outpoint(TID.INVALID, 0), -1,
                                                new Script(ByteUtils.fromHex("04" + "ffff001d" + // difficulty target
                                                        "010445" +
                                                        // "The Times 03/Jan/2009 Chancellor on brink of second bailout for banks"
                                                        "5468652054696d65732030332f4a616e2f32303039204368616e63656c6c6f72206f6e206272696e6b206f66207365636f6e64206261696c6f757420666f722062616e6b73"))
                                        )
                                )
                                .outputs(
                                        new TransactionOutput(5000000000L,
                                                new Script(ByteUtils.fromHex("4104678afdb0fe5548271967f1a67130b7105cd6a828e03909a67962e0ea1f61deb649f6bc3f4cef38c4f35504e51ec112de5c384df7ba0b8d578a4c702b6bf11d5fac"))
                                        )
                                ).build()
                ).build();
    }

        public static Block unittestWithHeaderSignature(byte[] inScriptBytes) {
                byte[] nextScriptHash = Hash.keyHash(inScriptBytes);
                return unittestWithHeaderSignature(inScriptBytes, nextScriptHash);
        }

        public static Block unittestWithHeaderSignature(byte[] inScriptBytes, byte[] nextScriptHash) {
                return new Block.Builder()
                        .header(HeaderWithSignatures.create()
                                .inScript(inScriptBytes)
                                .nextScriptHash(nextScriptHash)
                                .version(1)
                                .createTime(1296688602)
                                .difficultyTarget(0x207fffff)
                                .nonce(180)
                                .previousID(BID.INVALID)
                                .build())
                        .transactions(
                                new Transaction.Builder()
                                        .version(1)
                                        .inputs(
                                                new TransactionInput(new Outpoint(TID.INVALID, 0), -1,
                                                        new Script(ByteUtils.fromHex("04" + "ffff001d" + // difficulty target
                                                                "010445" +
                                                                // "The Times 03/Jan/2009 Chancellor on brink of second bailout for banks"
                                                                "5468652054696d65732030332f4a616e2f32303039204368616e63656c6c6f72206f6e206272696e6b206f66207365636f6e64206261696c6f757420666f722062616e6b73"))
                                                )
                                        )
                                        .outputs(
                                                new TransactionOutput(5000000000L,
                                                        new Script(ByteUtils.fromHex("4104678afdb0fe5548271967f1a67130b7105cd6a828e03909a67962e0ea1f61deb649f6bc3f4cef38c4f35504e51ec112de5c384df7ba0b8d578a4c702b6bf11d5fac"))
                                                )
                                        ).build()
                        ).build();
        }

    public static final Block regtestNative = new Block.Builder()
            .header(BitcoinHeader.create()
                    .version(1)
                    .createTime(1296688602)
                    .difficultyTarget(0x207fffff)
                    .nonce(2)
                    .previousID(BID.INVALID)
                    .build())
            .transactions(
                    new Transaction.Builder()
                            .version(1)
                            .inputs(
                                    new TransactionInput(new Outpoint(TID.INVALID, 0), -1,
                                            new Script(ByteUtils.fromHex("04" + "ffff001d" + // difficulty target
                                                    "010445" +
                                                    // "The Times 03/Jan/2009 Chancellor on brink of second bailout for banks"
                                                    "5468652054696d65732030332f4a616e2f32303039204368616e63656c6c6f72206f6e206272696e6b206f66207365636f6e64206261696c6f757420666f722062616e6b73"))
                                    )
                            )
                            .outputs(
                                    new ColoredTransactionOutput(5000000000L,
                                            new Script(ByteUtils.fromHex("4104678afdb0fe5548271967f1a67130b7105cd6a828e03909a67962e0ea1f61deb649f6bc3f4cef38c4f35504e51ec112de5c384df7ba0b8d578a4c702b6bf11d5fac"))
                                    )
                            ).build()
            ).build();
}
