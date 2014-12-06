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

import com.hazelcast.nio.Address;
import com.hazelcast.nio.Data;
import com.hazelcast.nio.Packet;

public class Request {

    enum ResponseType {
        OBJECT, BOOLEAN, LONG
    }

    public final static long DEFAULT_TIMEOUT = -1;
    public final static long DEFAULT_TTL = -1;
    public final static int DEFAULT_REDO_COUNT = 0;
    public final static long DEFAULT_TXN_ID = -1;
    public final static int DEFAULT_LOCK_COUNT = 0;
    public final static int DEFAULT_LOCK_THREAD_ID = -1;
    public final static int DEFAULT_BLOCK_ID = -1;
    public final static long DEFAULT_CALL_ID = -1;
    public final static long DEFAULT_VERSION = -1;

    public String name = null;
    public Data key = null;
    public Data value = null;
    public long timeout = DEFAULT_TIMEOUT;
    public long ttl = DEFAULT_TTL;

    public boolean local = true;
    public boolean scheduled = false;
    public ClusterOperation operation;

    public Address caller = null;
    public Address lockAddress = null;

    public int redoCount = DEFAULT_REDO_COUNT;
    public int lockCount = DEFAULT_LOCK_COUNT;
    public int lockThreadId = DEFAULT_LOCK_THREAD_ID;
    public int blockId = DEFAULT_BLOCK_ID;
    public long callId = DEFAULT_CALL_ID;
    public long longValue = Long.MIN_VALUE;
    public long version = DEFAULT_VERSION;
    public long txnId = DEFAULT_TXN_ID;
    public long[] indexes;
    public byte[] indexTypes;
    public Object attachment = null;
    public Object response = null;
    public ResponseType responseType = ResponseType.OBJECT;

    public boolean hasEnoughTimeToSchedule() {
        return (timeout == -1) || (timeout > 100);
    }

    @Override
    public String toString() {
        return "Request{" +
                "name='" + name + '\'' +
                "," + operation +
                ", redoCount='" + redoCount + '\'' +
                ", lockThreadId='" + lockThreadId + '\'' +
                '}';
    }

    public void reset() {
        this.local = true;
        this.operation = ClusterOperation.NONE;
        this.name = null;
        this.key = null;
        this.value = null;
        this.blockId = DEFAULT_BLOCK_ID;
        this.timeout = DEFAULT_TIMEOUT;
        this.ttl = DEFAULT_TTL;
        this.txnId = DEFAULT_TXN_ID;
        this.callId = DEFAULT_CALL_ID;
        this.lockThreadId = DEFAULT_LOCK_THREAD_ID;
        this.lockAddress = null;
        this.lockCount = DEFAULT_LOCK_COUNT;
        this.caller = null;
        this.longValue = Long.MIN_VALUE;
        this.version = DEFAULT_VERSION;
        this.response = null;
        this.scheduled = false;
        this.attachment = null;
        this.redoCount = DEFAULT_REDO_COUNT;
        this.indexes = null;
        this.indexTypes = null;
        this.responseType = ResponseType.OBJECT;
    }

    public void setIndexes(long[] newIndexes, byte[] indexTypes) {
        this.indexes = newIndexes;
        this.indexTypes = indexTypes;
        if (indexes.length != indexTypes.length) {
            throw new RuntimeException("Indexes length and indexTypes length has to be the same."
                    + indexes.length + " vs. " + indexTypes.length);
        }
    }

    private void set(final boolean local, final ClusterOperation operation, final String name,
                     final Data key, final Data value, final int blockId, final long timeout, long ttl,
                     final long txnId, final long callId, final int lockThreadId,
                     final Address lockAddress, final int lockCount, final Address caller,
                     final long longValue, final long version) {
        this.local = local;
        this.operation = operation;
        this.name = name;
        this.key = key;
        this.value = value;
        this.blockId = blockId;
        this.timeout = timeout;
        this.ttl = ttl;
        this.txnId = txnId;
        this.callId = callId;
        this.lockThreadId = lockThreadId;
        this.lockAddress = lockAddress;
        this.lockCount = lockCount;
        this.caller = caller;
        this.longValue = longValue;
        this.version = version;
    }

    public void setLocal(final ClusterOperation operation, final String name, final Data key,
                         final Data value, final int blockId, final long timeout, final long ttl,
                         final Address thisAddress) {
        //set the defaults here//
        this.response = null;
        this.scheduled = false;
        this.attachment = null;
        this.redoCount = DEFAULT_REDO_COUNT;
        this.indexes = null;
        this.indexTypes = null;
        this.responseType = ResponseType.OBJECT;
        // set the values //
        set(true,
                operation,
                name,
                key,
                value,
                blockId,
                timeout,
                ttl,
                ThreadContext.get().getTxnId(),
                DEFAULT_CALL_ID,
                ThreadContext.get().getThreadId(),
                thisAddress,
                DEFAULT_LOCK_COUNT,
                thisAddress,
                Long.MIN_VALUE,
                DEFAULT_VERSION);
    }

    public void setFromRequest(Request req) {
        set(req.local, req.operation, req.name, req.key, req.value, req.blockId, req.timeout, req.ttl,
                req.txnId, req.callId, req.lockThreadId, req.lockAddress, req.lockCount,
                req.caller, req.longValue, req.version);
        attachment = req.attachment;
        response = req.response;
        scheduled = req.scheduled;
        indexes = req.indexes;
        indexTypes = req.indexTypes;
    }

    public static Request copy(Packet packet) {
        final Request copy = new Request();
        copy.set(false, packet.operation, packet.name, packet.key, packet.value,
                packet.blockId, packet.timeout, packet.ttl, packet.txnId, packet.callId, packet.threadId,
                packet.lockAddress, packet.lockCount, packet.conn.getEndPoint(), packet.longValue,
                packet.version);
        copy.indexes = packet.indexes;
        copy.indexTypes = packet.indexTypes;
        return copy;
    }

    public void setFromRecord(Record record) {
        reset();
        name = record.getName();
        version = record.getVersion();
        blockId = record.getBlockId();
        lockThreadId = record.getLockThreadId();
        lockAddress = record.getLockAddress();
        lockCount = record.getLockCount();
        longValue = record.getCopyCount();
        ttl = record.getRemainingTTL();
        timeout = record.getRemainingIdle();
        key = record.getKey();
        value = record.getValue();
        if (record.getIndexes() != null) {
            setIndexes(record.getIndexes(), record.getIndexTypes());
        }
    }

    public Request hardCopy() {
        final Request copy = new Request();
        copy.setFromRequest(this);
        return copy;
    }

    public void setPacket(Packet packet) {
        packet.operation = operation;
        packet.name = name;
        packet.key = key;
        packet.value = value;
        packet.blockId = blockId;
        packet.timeout = timeout;
        packet.ttl = ttl;
        packet.txnId = txnId;
        packet.callId = callId;
        packet.threadId = lockThreadId;
        packet.lockAddress = lockAddress;
        packet.lockCount = lockCount;
        packet.longValue = longValue;
        packet.version = version;
        packet.indexes = indexes;
        packet.indexTypes = indexTypes;
    }

    public void clearForResponse() {
        if (!this.local) {
            this.name = null;
            this.key = null;
            this.value = null;
            this.blockId = DEFAULT_BLOCK_ID;
            this.timeout = DEFAULT_TIMEOUT;
            this.ttl = DEFAULT_TTL;
            this.txnId = DEFAULT_TXN_ID;
            this.lockThreadId = DEFAULT_LOCK_THREAD_ID;
            this.lockAddress = null;
            this.lockCount = DEFAULT_LOCK_COUNT;
            this.longValue = Long.MIN_VALUE;
            this.version = DEFAULT_VERSION;
            this.indexes = null;
            this.indexTypes = null;
        }
    }

    public void setLongRequest() {
        responseType = ResponseType.LONG;
    }

    public void setBooleanRequest() {
        responseType = ResponseType.BOOLEAN;
    }

    public void setObjectRequest() {
        responseType = ResponseType.OBJECT;
    }

    public boolean isLongRequest() {
        return (responseType == ResponseType.LONG);
    }

    public boolean isBooleanRequest() {
        return (responseType == ResponseType.BOOLEAN);
    }

    public boolean isObjectRequest() {
        return (responseType == ResponseType.OBJECT);
    }
}
