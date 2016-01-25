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

import org.hyperledger.common.Header;
import org.hyperledger.core.BlockStore;

import java.math.BigInteger;

public class BitcoinDifficulty {

    private final BigInteger minimumTarget;

    private final int reviewBlocks;

    private final int targetBlockTime;

    public static BigInteger getTarget(int compactTarget) {
        return BigInteger.valueOf(compactTarget & 0x7fffffL).shiftLeft((int) (8 * ((compactTarget >>> 24) - 3)));
    }

    public static int getEncodedDifficulty(BigInteger target) {
        int log2 = target.bitLength();
        int s = (log2 / 8 + 1) * 8;

        return (int) ((target.shiftRight(s - 24)).or(BigInteger.valueOf((s - 24) / 8 + 3).shiftLeft(24)).longValue()
                & 0xffffffff);
    }

    public BitcoinDifficulty(BigInteger minimumTarget, int reviewBlocks, int targetBlockTime) {
        this.minimumTarget = minimumTarget;
        this.reviewBlocks = reviewBlocks;
        this.targetBlockTime = targetBlockTime;
    }

    public int getReviewBlocks() {
        return reviewBlocks;
    }

    public double getDifficulty(int compactTarget) {
        return minimumTarget.divide(getTarget(compactTarget)).doubleValue();
    }

    public int getNextEncodedDifficulty(int periodLength, int currentCompactTarget) {
        // Limit the adjustment step.
        periodLength = Math.max(Math.min(periodLength, targetBlockTime * 4), targetBlockTime / 4);
        BigInteger newTarget =
                (getTarget(currentCompactTarget).multiply(BigInteger.valueOf(periodLength)))
                        .divide(BigInteger.valueOf(targetBlockTime));
        // not simpler than this
        if (newTarget.compareTo(minimumTarget) > 0) {
            newTarget = minimumTarget;
        }
        return getEncodedDifficulty(newTarget);
    }

    @SuppressWarnings("deprecation")
    public int computeNextDifficulty(BlockStore blockStore, Header prev) {
        Header p = prev;
        for (int i = 0; i < getReviewBlocks() - 1; ++i) {
            p = blockStore.getHeader(p.getPreviousID());
        }
        return getNextEncodedDifficulty(
                (int) (Integer.toUnsignedLong(prev.getCreateTime()) -
                        Integer.toUnsignedLong(p.getCreateTime())), prev.getEncodedDifficulty());
    }
}
