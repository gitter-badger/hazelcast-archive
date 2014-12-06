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

package com.hazelcast.nio;

import com.hazelcast.impl.ClusterOperation;
import com.hazelcast.impl.Constants;
import com.hazelcast.impl.GroupProperties;
import com.hazelcast.impl.ThreadContext;
import com.hazelcast.util.ByteUtil;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class Packet {

    public String name;

    public ClusterOperation operation = ClusterOperation.NONE;

    public ByteBuffer bbSizes = ByteBuffer.allocate(13);

    public ByteBuffer bbHeader = ByteBuffer.allocate(500);

    public Data key = null;

    public Data value = null;

    public long[] indexes = null;

    public byte[] indexTypes = null;

    public long txnId = -1;

    public int threadId = -1;

    public int lockCount = 0;

    public Address lockAddress = null;

    public long timeout = -1;

    public long ttl = -1;

    public int currentCallCount = 0;

    public int blockId = -1;

    public byte responseType = Constants.ResponseTypes.RESPONSE_NONE;

    public long longValue = Long.MIN_VALUE;

    public long version = -1;

    public long callId = -1;

    public Connection conn;

    public int totalSize = 0;

    public volatile boolean released = false;

    boolean sizeRead = false;

    int totalWritten = 0;

    public boolean client = false;

    public static final byte PACKET_VERSION = GroupProperties.PACKET_VERSION.getByte();

    private static final Logger logger = Logger.getLogger(Packet.class.getName());

    public Packet() {
    }

    private static final Map<String, byte[]> mapStringByteCache = new ConcurrentHashMap<String, byte[]>(1000);

    /**
     * only ServiceThread should call
     */
    private static void putString(ByteBuffer bb, String str) {
        if (str == null) {
            bb.putInt(0);
        } else {
            byte[] bytes = mapStringByteCache.get(str);
            if (bytes == null) {
                bytes = str.getBytes();
                if (mapStringByteCache.size() >= 10000) {
                    mapStringByteCache.clear();
                    logger.log(Level.WARNING, "So many different names!");
                }
                mapStringByteCache.put(str, bytes);
            }
            bb.putInt(bytes.length);
            bb.put(bytes);
        }
    }

    private static String getString(ByteBuffer bb) {
        int length = bb.getInt();
        if (length == 0) return null;
        byte[] bytes = new byte[length];
        bb.get(bytes, 0, length);
        return new String(bytes);
    }

    protected void writeBoolean(ByteBuffer bb, boolean value) {
        bb.put((value) ? (byte) 1 : (byte) 0);
    }

    protected boolean readBoolean(ByteBuffer bb) {
        return (bb.get() == (byte) 1);
    }

    public void write() {
        bbSizes.clear();
        bbHeader.clear();
        if (key != null && key.size > 0) {
            key = new Data(ByteBuffer.wrap(key.buffer.array()));
        }
        if (value != null && value.size > 0) {
            value = new Data(ByteBuffer.wrap(value.buffer.array()));
        }
        bbHeader.put(operation.getValue());
        bbHeader.putInt(blockId);
        bbHeader.putInt(threadId);
        byte booleans = 0;
        if (lockCount != 0) {
            booleans = ByteUtil.setTrue(booleans, 0);
        }
        if (timeout != -1) {
            booleans = ByteUtil.setTrue(booleans, 1);
        }
        if (ttl != -1) {
            booleans = ByteUtil.setTrue(booleans, 2);
        }
        if (txnId != -1) {
            booleans = ByteUtil.setTrue(booleans, 3);
        }
        if (longValue != Long.MIN_VALUE) {
            booleans = ByteUtil.setTrue(booleans, 4);
        }
        if (version != -1) {
            booleans = ByteUtil.setTrue(booleans, 5);
        }
        if (client) {
            booleans = ByteUtil.setTrue(booleans, 6);
        }
        if (lockAddress == null) {
            booleans = ByteUtil.setTrue(booleans, 7);
        }
        bbHeader.put(booleans);
        if (lockCount != 0) {
            bbHeader.putInt(lockCount);
        }
        if (timeout != -1) {
            bbHeader.putLong(timeout);
        }
        if (ttl != -1) {
            bbHeader.putLong(ttl);
        }
        if (txnId != -1) {
            bbHeader.putLong(txnId);
        }
        if (longValue != Long.MIN_VALUE) {
            bbHeader.putLong(longValue);
        }
        if (version != -1) {
            bbHeader.putLong(version);
        }
        if (lockAddress != null) {
            lockAddress.writeObject(bbHeader);
        }
        bbHeader.putLong(callId);
        bbHeader.put(responseType);
        putString(bbHeader, name);
        byte indexCount = (indexes == null) ? 0 : (byte) indexes.length;
        bbHeader.put(indexCount);
        for (byte i = 0; i < indexCount; i++) {
            bbHeader.putLong(indexes[i]);
            bbHeader.put(indexTypes[i]);
        }
        bbHeader.flip();
        bbSizes.putInt(bbHeader.limit());
        bbSizes.putInt(key == null ? 0 : key.size);
        bbSizes.putInt(value == null ? 0 : value.size);
        bbSizes.put(PACKET_VERSION);
        bbSizes.flip();
        totalSize = 0;
        totalSize += bbSizes.limit();
        totalSize += bbHeader.limit();
        totalSize += key == null ? 0 : key.size;
        totalSize += value == null ? 0 : value.size;
    }

    public void read() {
        operation = ClusterOperation.create(bbHeader.get());
        blockId = bbHeader.getInt();
        threadId = bbHeader.getInt();
        byte booleans = bbHeader.get();
        if (ByteUtil.isTrue(booleans, 0)) {
            lockCount = bbHeader.getInt();
        }
        if (ByteUtil.isTrue(booleans, 1)) {
            timeout = bbHeader.getLong();
        }
        if (ByteUtil.isTrue(booleans, 2)) {
            ttl = bbHeader.getLong();
        }
        if (ByteUtil.isTrue(booleans, 3)) {
            txnId = bbHeader.getLong();
        }
        if (ByteUtil.isTrue(booleans, 4)) {
            longValue = bbHeader.getLong();
        }
        if (ByteUtil.isTrue(booleans, 5)) {
            version = bbHeader.getLong();
        }
        client = ByteUtil.isTrue(booleans, 6);
        boolean lockAddressNull = ByteUtil.isTrue(booleans, 7);
        if (!lockAddressNull) {
            lockAddress = new Address();
            lockAddress.readObject(bbHeader);
        }
        callId = bbHeader.getLong();
        responseType = bbHeader.get();
        name = getString(bbHeader);
        byte indexCount = bbHeader.get();
        if (indexCount > 0) {
            indexes = new long[indexCount];
            indexTypes = new byte[indexCount];
            for (byte i = 0; i < indexCount; i++) {
                indexes[i] = bbHeader.getLong();
                indexTypes[i] = bbHeader.get();
            }
        }
    }

    public void reset() {
        name = null;
        operation = ClusterOperation.NONE;
        threadId = -1;
        lockCount = 0;
        lockAddress = null;
        timeout = -1;
        ttl = -1;
        txnId = -1;
        responseType = Constants.ResponseTypes.RESPONSE_NONE;
        currentCallCount = 0;
        blockId = -1;
        longValue = Long.MIN_VALUE;
        version = -1;
        callId = -1;
        client = false;
        bbSizes.clear();
        bbHeader.clear();
        key = null;
        value = null;
        conn = null;
        totalSize = 0;
        totalWritten = 0;
        sizeRead = false;
        indexes = null;
        indexTypes = null;
    }

    public void clearForResponse() {
        this.name = null;
        this.key = null;
        this.value = null;
        this.blockId = -1;
        this.timeout = -1;
        this.ttl = -1;
        this.txnId = -1;
        this.threadId = -1;
        this.lockAddress = null;
        this.lockCount = 0;
        this.longValue = Long.MIN_VALUE;
        this.version = -1;
        this.indexes = null;
        this.indexTypes = null;
    }

    @Override
    public String toString() {
        int keySize = (key == null) ? 0 : key.size();
        int valueSize = (value == null) ? 0 : value.size();
        return "Packet [" + operation + "] name=" + name + ",blockId="
                + blockId + ", keySize=" + keySize + ", valueSize=" + valueSize
                + " client=" + client;
    }

    public void flipBuffers() {
        bbSizes.flip();
        bbHeader.flip();
    }

    public final boolean writeToSocketBuffer(ByteBuffer dest) {
        totalWritten += IOUtil.copyToDirectBuffer(bbSizes, dest);
        totalWritten += IOUtil.copyToDirectBuffer(bbHeader, dest);
        if (key != null && key.size() > 0) {
            totalWritten += IOUtil.copyToDirectBuffer(key.buffer, dest);
        }
        if (value != null && value.size() > 0) {
            totalWritten += IOUtil.copyToDirectBuffer(value.buffer, dest);
        }
        return totalWritten >= totalSize;
    }

    public final boolean read(ByteBuffer bb) {
        while (!sizeRead && bb.hasRemaining() && bbSizes.hasRemaining()) {
            IOUtil.copyToHeapBuffer(bb, bbSizes);
        }
        if (!sizeRead && !bbSizes.hasRemaining()) {
            sizeRead = true;
            bbSizes.flip();
            bbHeader.limit(bbSizes.getInt());
            int keySize = bbSizes.getInt();
            int valueSize = bbSizes.getInt();
            if (keySize > 0) key = new Data(keySize);
            if (valueSize > 0) value = new Data(valueSize);
            if (bbHeader.limit() == 0) {
                throw new RuntimeException("read.bbHeader size cannot be 0");
            }
            byte packetVersion = bbSizes.get();
            if (packetVersion != PACKET_VERSION) {
                String msg = "Packet versions are not the same. Expected " + PACKET_VERSION
                        + " Found: " + packetVersion;
                logger.log(Level.WARNING, msg);
                throw new RuntimeException(msg);
            }
        }
        if (sizeRead) {
            while (bb.hasRemaining() && bbHeader.hasRemaining()) {
                IOUtil.copyToHeapBuffer(bb, bbHeader);
            }
            while (key != null && bb.hasRemaining() && key.shouldRead()) {
                key.read(bb);
            }
            while (value != null && bb.hasRemaining() && value.shouldRead()) {
                value.read(bb);
            }
        }
        if (sizeRead && !bbHeader.hasRemaining() && (key == null || !key.shouldRead()) && (value == null || !value.shouldRead())) {
            sizeRead = false;
            if (key != null) key.postRead();
            if (value != null) value.postRead();
            return true;
        }
        return false;
    }

    public void set(String name, ClusterOperation operation, Object objKey, Object objValue) {
        this.threadId = ThreadContext.get().getThreadId();
        this.name = name;
        this.operation = operation;
        if (objKey != null) {
            key = ThreadContext.get().toData(objKey);
        }
        if (objValue != null) {
            value = ThreadContext.get().toData(objValue);
        }
    }

    public void setFromConnection(Connection conn) {
        this.conn = conn;
        if (lockAddress == null) {
            lockAddress = conn.getEndPoint();
        }
    }
}
