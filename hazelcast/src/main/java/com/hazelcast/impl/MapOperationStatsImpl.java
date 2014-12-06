/*
 * Copyright (c) 2008-2010, Hazel Ltd. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.hazelcast.impl;

import com.hazelcast.monitor.LocalMapOperationStats;
import com.hazelcast.nio.DataSerializable;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

public class MapOperationStatsImpl implements LocalMapOperationStats {

    long periodStart;
    long periodEnd;
    OperationStat gets = new OperationStat(0,0);
    OperationStat puts = new OperationStat(0,0);
    OperationStat removes = new OperationStat(0,0);
    long numberOfOtherOperations;
    long numberOfEvents;

    public void writeData(DataOutput out) throws IOException {
        puts.writeData(out);
        gets.writeData(out);
        removes.writeData(out);
        out.writeLong(numberOfOtherOperations);
        out.writeLong(numberOfEvents);
        out.writeLong(periodStart);
        out.writeLong(periodEnd);
    }

    public void readData(DataInput in) throws IOException {
        puts = new OperationStat();
        puts.readData(in);
        gets = new OperationStat();
        gets.readData(in);
        removes = new OperationStat();
        removes.readData(in);
        numberOfOtherOperations = in.readLong();
        numberOfEvents = in.readLong();
        periodStart = in.readLong();
        periodEnd = in.readLong();
    }

    public long total() {
        return puts.count + gets.count + removes.count + numberOfOtherOperations;
    }

    public long getPeriodStart() {
        return periodStart;
    }

    public long getPeriodEnd() {
        return periodEnd;
    }

    public long getNumberOfPuts() {
        return puts.count;
    }

    public long getNumberOfGets() {
        return gets.count;
    }

    public long getTotalPutLatency() {
        return puts.totalLatency;
    }

    public long getTotalGetLatency() {
        return gets.totalLatency;
    }

    public long getTotalRemoveLatency() {
        return removes.totalLatency;
    }

    public long getNumberOfRemoves() {
        return removes.count;
    }

    public long getNumberOfOtherOperations() {
        return numberOfOtherOperations;
    }

    public long getNumberOfEvents() {
        return numberOfEvents;
    }

    public String toString() {
        return "LocalMapOperationStats{" +
                "total= " + total() +
                "\n, puts:" + puts +
                "\n, gets:" + gets +
                "\n, removes:" + removes +
                "\n, others: " + numberOfOtherOperations +
                "\n, received events: " + numberOfEvents +
                "}";
    }

    class OperationStat implements DataSerializable {
        long count;
        long totalLatency;

        public OperationStat() {
            this(0, 0);
        }

        public OperationStat(long c, long l) {
            this.count = c;
            this.totalLatency = l;
        }

        @Override
        public String toString() {
            return "OperationStat{" +
                    "count=" + count +
                    ", averageLatency=" + ((count == 0) ? 0 : totalLatency / count) +
                    '}';
        }

        public void writeData(DataOutput out) throws IOException {
            out.writeLong(count);
            out.writeLong(totalLatency);
        }

        public void readData(DataInput in) throws IOException {
            count = in.readLong();
            totalLatency = in.readLong();
        }

        public void add(long c, long l) {
            count += c;
            totalLatency += l;
        }
    }
}
