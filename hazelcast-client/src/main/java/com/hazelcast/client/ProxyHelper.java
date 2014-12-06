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

import com.hazelcast.client.impl.CollectionWrapper;
import com.hazelcast.impl.ClusterOperation;
import com.hazelcast.query.Predicate;

import java.io.Serializable;
import java.util.Collection;
import java.util.EventListener;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static com.hazelcast.client.Serializer.toByte;
import static com.hazelcast.client.Serializer.toObject;

public class ProxyHelper {
    private final static AtomicLong callIdGen = new AtomicLong(0);
    private final String name;
    private final HazelcastClient client;

    public ProxyHelper(String name, HazelcastClient client) {
        this.name = (name == null) ? "" : name;
        this.client = client;
    }

    public String getName() {
        return name.substring(2);
    }

    protected Packet callAndGetResult(Packet request) {
        Call c = createCall(request);
        return doCall(c);
    }

    protected Packet doCall(Call c) {
        sendCall(c);
        return (Packet) c.getResponse();
    }

    public void sendCall(Call c) {
        if (c == null) {
            throw new NullPointerException();
        }
        client.getOutRunnable().enQueue(c);
    }

    public static Call createCall(Packet request) {
        long id = newCallId();
        return new Call(id, request);
    }

    public static long newCallId() {
        return callIdGen.incrementAndGet();
    }

    private Packet createRequestPacket() {
        Packet request = new Packet();
        request.setName(name);
        request.setThreadId((int) Thread.currentThread().getId());
        return request;
    }

    public Packet createRequestPacket(ClusterOperation operation, byte[] key, byte[] value) {
        Packet request = createRequestPacket();
        request.setOperation(operation);
        request.setKey(key);
        request.setValue(value);
        return request;
    }

    protected Object doOp(ClusterOperation operation, Object key, Object value) {
        Packet request = prepareRequest(operation, key, value);
        Packet response = callAndGetResult(request);
        return getValue(response);
    }

    protected Packet prepareRequest(ClusterOperation operation, Object key,
                                    Object value) {
        byte[] k = null;
        byte[] v = null;
        if (key != null) {
            k = toByte(key);
        }
        if (value != null) {
            v = toByte(value);
        }
        Packet request = createRequestPacket(operation, k, v);
        return request;
    }

    protected Object getValue(Packet response) {
        if (response.getValue() != null) {
            return toObject(response.getValue());
        }
        return null;
    }

    public void destroy() {
        doOp(ClusterOperation.DESTROY, null, null);
        this.client.destroy(name);
    }

    public <K> Collection<K> keys(Predicate predicate) {
        return ((CollectionWrapper<K>) doOp(ClusterOperation.CONCURRENT_MAP_ITERATE_KEYS, null, predicate)).getKeys();
    }

    static void check(Object obj) {
        if (obj == null) {
            throw new NullPointerException("Object cannot be null.");
        }
        if (!(obj instanceof Serializable)) {
            throw new IllegalArgumentException(obj.getClass().getName() + " is not Serializable.");
        }
    }

    static void check(EventListener listener) {
        if (listener == null) {
            throw new NullPointerException("Listener can not be null");
        }
    }

    static void checkTime(long time, TimeUnit timeunit) {
        if (time < 0) {
            throw new IllegalArgumentException("Time can not be less than 0.");
        }
        if (timeunit == null) {
            throw new NullPointerException("TimeUnit can not be null.");
        }
    }

    public HazelcastClient getHazelcastClient() {
        return client;
    }
}
