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
package org.hyperledger.nativelibs;

import com.sun.jna.Library;

public class LibBitcoinConsensusJNA {

    public interface Libconsensus extends Library {
        int bitcoinconsensus_verify_script(byte[] scriptPubKey, int sl, byte[] txTo, int tl, long nInValue, int nIn, int flags, int[] err);
    }

    private final static Libconsensus instance = (Libconsensus) NativeLibraryLoader.loadLibrary("bitcoinconsensus", Libconsensus.class);

    public static final boolean verifyScript(byte[] scriptPubKey, byte[] txTo, long nInValue, int nIn, int flags, BitcoinConsensusErrorHolder errorHolder) {
        int[] err = new int[1];
        int result = instance.bitcoinconsensus_verify_script(scriptPubKey, scriptPubKey.length, txTo, txTo.length, nInValue, nIn, flags, err);
        errorHolder.setErrorCode(BitcoinConsensusErrorCode.fromValue(err[0]));
        return result == 1;
    }

    public static class BitcoinConsensusErrorHolder {
        private BitcoinConsensusErrorCode errorCode = BitcoinConsensusErrorCode.OK;

        public BitcoinConsensusErrorHolder() {
        }

        public BitcoinConsensusErrorCode getErrorCode() {
            return errorCode;
        }

        public void setErrorCode(BitcoinConsensusErrorCode errorCode) {
            this.errorCode = errorCode;
        }
    }

    public enum BitcoinConsensusErrorCode {
        // values must match with 'typedef enum bitcoinconsensus_error_t' in bitcoinconsensus.h

        OK(0),
        TX_INDEX(1),
        TX_SIZE_MISMATCH(2),
        TX_DESERIALIZE(3);

        private int code;

        BitcoinConsensusErrorCode(int code) {
            this.code = code;
        }

        public static BitcoinConsensusErrorCode fromValue(int value) throws IllegalArgumentException {
            try {
                return BitcoinConsensusErrorCode.values()[value];
            } catch (ArrayIndexOutOfBoundsException e) {
                throw new IllegalArgumentException("Unknown BitcoinConsensusErrorCode enum value :" + value);
            }
        }
    }
}
