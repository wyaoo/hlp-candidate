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
package org.hyperledger.perftest;

import java.util.List;
import java.util.concurrent.Callable;

public abstract class BaseMeasure implements Callable<List<Long>> {

    private final int rounds;
    private final int count;

    public BaseMeasure(int rounds, int count) {
        this.rounds = rounds;
        this.count = count;
    }

    @Override
    public List<Long> call() throws Exception {
        try {
            setup();
            return measure();
        } finally {
            tearDown();
        }
    }

    protected abstract List<Long> measure() throws Exception;

    protected void setup() throws Exception {
    }

    protected void tearDown() throws Exception {
    }

    public String resultsToThroughputPerSec(List<Long> results) {
        StringBuilder sb = new StringBuilder();
        for (long r : results) {
            sb.append(Math.round(count / (((double) r) / 1000))).append("\n");
        }
        return sb.toString();
    }

    public int getRounds() {
        return rounds;
    }

    public int getCount() {
        return count;
    }
}
