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

import org.hyperledger.common.BID;

import java.util.ArrayList;
import java.util.List;

public class BlockStoredInfo {
    private final int height;
    private final List<BID> addedToTrunk;
    private final List<BID> removedFromFrunk;

    public BlockStoredInfo(int height, List<BID> addedToTrunk, List<BID> removedFromFrunk) {
        this.height = height;
        this.addedToTrunk = addedToTrunk;
        this.removedFromFrunk = removedFromFrunk;
    }

    public BlockStoredInfo(int height) {
        this.height = height;
        addedToTrunk = new ArrayList<>();
        removedFromFrunk = new ArrayList<>();
    }

    public BlockStoredInfo(int height, BID h) {
        this.height = height;
        addedToTrunk = new ArrayList<>();
        addedToTrunk.add(h);
        removedFromFrunk = new ArrayList<>();
    }

    public int getHeight() {
        return height;
    }

    public List<BID> getAddedToTrunk() {
        return addedToTrunk;
    }

    public List<BID> getRemovedFromFrunk() {
        return removedFromFrunk;
    }
}

