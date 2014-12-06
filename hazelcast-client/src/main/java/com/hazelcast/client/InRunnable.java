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

package com.hazelcast.client;

import com.hazelcast.impl.ClusterOperation;
import com.hazelcast.logging.ILogger;
import com.hazelcast.logging.Logger;

import java.io.IOException;
import java.util.Map;
import java.util.logging.Level;

public class InRunnable extends IORunnable implements Runnable {
    final PacketReader reader;
    final ILogger logger = Logger.getLogger(this.getClass().getName());
    Connection connection = null;

    public InRunnable(HazelcastClient client, Map<Long, Call> calls, PacketReader reader) {
        super(client, calls);
        this.reader = reader;
    }

    protected void customRun() {
        Packet packet;
        try {
            Connection oldConnection = connection;
            connection = client.connectionManager.getConnection();
            if (restoredConnection(oldConnection, connection)) {
                onDisconnect(oldConnection);
            }
            if (connection == null) {
                interruptWaitingCalls();
                Thread.sleep(50);
            } else {
                packet = reader.readPacket(connection);
                logger.log(Level.FINEST, "Reading " + packet.getOperation() + " Call id: " + packet.getCallId());
                Call call = callMap.remove(packet.getCallId());
                if (call != null) {
                    call.setResponse(packet);
                } else {
                    if (packet.getOperation().equals(ClusterOperation.EVENT)) {
                        client.getListenerManager().enqueue(packet);
                    }
                    if (packet.getCallId() != -1) {
                        logger.log(Level.SEVERE, "In Thread can not handle: " + packet.getOperation() + " : " + packet.getCallId());
                    }
                }
            }
        } catch (RuntimeException re) {
            throw re;
        } catch (Exception e) {
            logger.log(Level.FINE, "InRunnable got an exception:" + e.toString());
            client.connectionManager.destroyConnection(connection);
        }
    }

    public void shutdown() {
        synchronized (monitor) {
            if (running) {
                this.running = false;
                try {
                    Connection connection = client.connectionManager.getConnection();
                    if (connection != null) {
                        connection.getSocket().close();
                    }
                } catch (IOException ignored) {
                }
                try {
                    monitor.wait();
                } catch (InterruptedException ignored) {
                }
            }
        }
    }
}
