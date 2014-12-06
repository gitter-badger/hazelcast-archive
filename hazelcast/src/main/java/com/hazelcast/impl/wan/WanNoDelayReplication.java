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

package com.hazelcast.impl.wan;

import com.hazelcast.impl.ClusterOperation;
import com.hazelcast.impl.Node;
import com.hazelcast.impl.Record;
import com.hazelcast.impl.base.DataRecordEntry;
import com.hazelcast.nio.Address;
import com.hazelcast.nio.Connection;
import com.hazelcast.nio.Packet;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import static com.hazelcast.nio.IOUtil.toData;

public class WanNoDelayReplication implements Runnable, WanReplicationEndpoint {

    private Node node;
    private String groupName;
    private String password;
    private final LinkedBlockingQueue<String> addressQueue = new LinkedBlockingQueue<String>();
    private final LinkedList<RecordUpdate> failureQ = new LinkedList<RecordUpdate>();
    private final BlockingQueue<RecordUpdate> q = new ArrayBlockingQueue<RecordUpdate>(100000);
    private volatile boolean running = true;

    public void init(Node node, String groupName, String password, String... targets) {
        this.node = node;
        this.groupName = groupName;
        this.password = password;
        addressQueue.addAll(Arrays.asList(targets));
        node.executorManager.executeNow(this);
    }

    /**
     * Only ServiceThread will call this
     */
    public void recordUpdated(Record record) {
        DataRecordEntry dataRecordEntry = new DataRecordEntry(record);
        RecordUpdate ru = (new RecordUpdate(dataRecordEntry, record.getName()));
        if (!q.offer(ru)) {
            q.poll();
            q.offer(ru);
        }
    }

    public void run() {
        Connection conn = null;
        while (running) {
            try {
                RecordUpdate ru = (failureQ.size() > 0) ? failureQ.removeFirst() : q.take();
                if (conn == null) {
                    conn = getConnection();
                }
                conn.getWriteHandler().enqueueSocketWritable(ru.toNewPacket());
                if (!conn.live()) {
                    failureQ.addFirst(ru);
                }
            } catch (InterruptedException e) {
                running = false;
            } catch (Throwable e) {
                e.printStackTrace();
            }
        }
    }

    Connection getConnection() throws InterruptedException {
        while (true) {
            String targetStr = addressQueue.take();
            Address target = null;
            try {
                target = null;
                int colon = targetStr.indexOf(':');
                if (colon == -1) {
                    target = new Address(targetStr, node.getConfig().getPort());
                } else {
                    target = new Address(targetStr.substring(0, colon), Integer.parseInt(targetStr.substring(colon + 1)));
                }
                Connection conn = node.getConnectionManager().getOrConnect(target);
                for (int i = 0; i < 10; i++) {
                    conn = node.getConnectionManager().getConnection(target);
                    if (conn == null) {
                        Thread.sleep(1000);
                    } else {
                        return conn;
                    }
                }
            } catch (Throwable e) {
                Thread.sleep(1000);
            }
            addressQueue.offer(targetStr);
        }
    }

    class RecordUpdate {
        final DataRecordEntry dataRecordEntry;
        final String name;

        RecordUpdate(DataRecordEntry dataRecordEntry, String name) {
            this.dataRecordEntry = dataRecordEntry;
            this.name = name;
        }

        public Packet toNewPacket() {
            Packet packet = new Packet();
            packet.name = name;
            packet.operation = ClusterOperation.CONCURRENT_MAP_ASYNC_MERGE;
            packet.setKey(dataRecordEntry.getKeyData());
            packet.setValue(toData(dataRecordEntry));
            return packet;
        }
    }
}
