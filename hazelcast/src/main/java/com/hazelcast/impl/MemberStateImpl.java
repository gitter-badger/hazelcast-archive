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

import com.hazelcast.monitor.LocalMapStats;
import com.hazelcast.monitor.LocalQueueStats;
import com.hazelcast.monitor.LocalTopicStats;
import com.hazelcast.monitor.MemberState;
import com.hazelcast.nio.Address;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.*;

public class MemberStateImpl implements MemberState {
    /**
     *
     */
    private static final long serialVersionUID = -1817978625085375340L;

    Address address = new Address();
    MemberHealthStatsImpl memberHealthStats = new MemberHealthStatsImpl();
    Map<String, LocalMapStatsImpl> mapStats = new HashMap<String, LocalMapStatsImpl>();
    Map<String, LocalQueueStatsImpl> queueStats = new HashMap<String, LocalQueueStatsImpl>();
    Map<String, LocalTopicStatsImpl> topicStats = new HashMap<String, LocalTopicStatsImpl>();
    List<Integer> lsPartitions = new ArrayList<Integer>(271);

    public void writeData(DataOutput out) throws IOException {
        address.writeData(out);
        memberHealthStats.writeData(out);
        int mapCount = mapStats.size();
        int queueCount = queueStats.size();
        int topicCount = topicStats.size();
        out.writeInt(mapCount);
        Set<Map.Entry<String, LocalMapStatsImpl>> maps = mapStats.entrySet();
        for (Map.Entry<String, LocalMapStatsImpl> mapStatsEntry : maps) {
            out.writeUTF(mapStatsEntry.getKey());
            mapStatsEntry.getValue().writeData(out);
        }
        out.writeInt(queueCount);
        Set<Map.Entry<String, LocalQueueStatsImpl>> queueStatEntries = queueStats.entrySet();
        for (Map.Entry<String, LocalQueueStatsImpl> queueStatEntry : queueStatEntries) {
            out.writeUTF(queueStatEntry.getKey());
            queueStatEntry.getValue().writeData(out);
        }
        out.writeInt(topicCount);
        Set<Map.Entry<String, LocalTopicStatsImpl>> topicStatEntries = topicStats.entrySet();
        for (Map.Entry<String, LocalTopicStatsImpl> topicStatEntry : topicStatEntries) {
            out.writeUTF(topicStatEntry.getKey());
            topicStatEntry.getValue().writeData(out);
        }
        int partitionCount = lsPartitions.size();
        out.writeInt(partitionCount);
        for (int i = 0; i < partitionCount; i++) {
            out.writeInt(lsPartitions.get(i));
        }
    }

    public void readData(DataInput in) throws IOException {
        address.readData(in);
        memberHealthStats.readData(in);
        int mapCount = in.readInt();
        for (int i = 0; i < mapCount; i++) {
            String mapName = in.readUTF();
            LocalMapStatsImpl localMapStatsImpl = new LocalMapStatsImpl();
            localMapStatsImpl.readData(in);
            mapStats.put(mapName, localMapStatsImpl);
        }
        int queueCount = in.readInt();
        for (int i = 0; i < queueCount; i++) {
            String queueName = in.readUTF();
            LocalQueueStatsImpl localQueueStats = new LocalQueueStatsImpl();
            localQueueStats.readData(in);
            queueStats.put(queueName, localQueueStats);
        }
        int topicCount = in.readInt();
        for (int i = 0; i < topicCount; i++) {
            String topicName = in.readUTF();
            LocalTopicStatsImpl localTopicStats = new LocalTopicStatsImpl();
            localTopicStats.readData(in);
            topicStats.put(topicName, localTopicStats);
        }
        int partitionCount = in.readInt();
        for (int i = 0; i < partitionCount; i++) {
            lsPartitions.add(in.readInt());
        }
    }

    public void clearPartitions() {
        lsPartitions.clear();
    }

    public void addPartition(int partitionId) {
        lsPartitions.add(partitionId);
    }

    public List<Integer> getPartitions() {
        return lsPartitions;
    }

    public MemberHealthStatsImpl getMemberHealthStats() {
        return memberHealthStats;
    }

    public LocalMapStats getLocalMapStats(String mapName) {
        return mapStats.get(mapName);
    }

    public LocalQueueStats getLocalQueueStats(String queueName) {
        return queueStats.get(queueName);
    }

    public LocalTopicStats getLocalTopicStats(String topicName) {
        return topicStats.get(topicName);
    }

    public Address getAddress() {
        return address;
    }

    public void setAddress(Address address) {
        this.address = address;
    }

    public void putLocalMapStats(String mapName, LocalMapStatsImpl localMapStats) {
        mapStats.put(mapName, localMapStats);
    }

    public void putLocalQueueStats(String queueName, LocalQueueStatsImpl localQueueStats) {
        queueStats.put(queueName, localQueueStats);
    }

    public void putLocalTopicStats(String name, LocalTopicStatsImpl localTopicStats) {
        topicStats.put(name, localTopicStats);
    }

    @Override
    public String toString() {
        return "MemberStateImpl [" + address + "] " +
                "\n{ " +
                "\n\t" + memberHealthStats +
                "\n\tmapStats=" + mapStats +
                "\n\tqueueStats=" + queueStats +
                "\n\tpartitions=" + lsPartitions +
                "\n}";
    }
}