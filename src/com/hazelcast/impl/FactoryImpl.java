/* 
 * Copyright (c) 2007-2008, Hazel Ltd. All Rights Reserved.
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

import com.hazelcast.core.*;
import com.hazelcast.impl.BaseManager.Processable;
import com.hazelcast.impl.BlockingQueueManager.*;
import com.hazelcast.impl.ClusterManager.CreateProxy;
import com.hazelcast.impl.ConcurrentMapManager.*;
import static com.hazelcast.impl.Constants.MapTypes.*;
import com.hazelcast.nio.Data;
import com.hazelcast.nio.DataSerializable;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.io.Serializable;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;

public class FactoryImpl implements Constants {

    private static ConcurrentMap<String, ICommon> proxies = new ConcurrentHashMap<String, ICommon>(1000);

    private static MProxy locksMapProxy = new MProxyImpl("m:__hz_Locks");

    private static MProxy idGeneratorMapProxy = new MProxyImpl("m:__hz_IdGenerator");

    private static ConcurrentMap<Object, LockProxy> mapLockProxies = new ConcurrentHashMap<Object, LockProxy>(100);

    private static ConcurrentMap<String, IdGenerator> mapIdGenerators = new ConcurrentHashMap<String, IdGenerator>(100);

    static final ExecutorServiceProxy executorServiceImpl = new ExecutorServiceProxy();

    private static Node node = null;

    static AtomicBoolean inited = new AtomicBoolean(false);

    static void init() {

        if (!inited.get()) {
            synchronized (Node.class) {
                if (!inited.get()) {
                    node = Node.get();
                    node.start();
                    inited.set(true);
                }
            }
        }
    }

    public static void shutdown() {
        Node.get().shutdown();
    }

    public static Collection<ICommon> getInstances() {
        List<ICommon> lsProxies = new ArrayList<ICommon>(proxies.size() + mapLockProxies.size());
        lsProxies.addAll(proxies.values());
        lsProxies.addAll(mapLockProxies.values());
        lsProxies.addAll(mapIdGenerators.values());
        return lsProxies;
    }

    static Collection getProxies() {
        if (!inited.get())
            init();
        return proxies.values();
    }

    public static ExecutorService getExecutorService() {
        if (!inited.get())
            init();
        return executorServiceImpl;
    }

    public static ClusterImpl getCluster() {
        if (!inited.get())
            init();
        return node.getClusterImpl();
    }

    public static IdGenerator getIdGenerator(String name) {
        if (!inited.get()) {
            init();
        }
        IdGenerator idGenerator = mapIdGenerators.get(name);
        if (idGenerator != null)
            return idGenerator;
        synchronized (IdGeneratorProxy.class) {
            idGenerator = new IdGeneratorProxy(name);
            IdGeneratorProxy old = (IdGeneratorProxy) mapIdGenerators.putIfAbsent(name, idGenerator);
            if (old != null)
                idGenerator = old;
        }
        return idGenerator;
    }

    public static <K, V> IMap<K, V> getMap(String name) {
        name = "c:" + name;
        return (IMap<K, V>) getProxy(name);
    }

    public static <E> IQueue<E> getQueue(String name) {
        name = "q:" + name;
        return (IQueue) getProxy(name);
    }

    public static <E> ITopic<E> getTopic(String name) {
        name = "t:" + name;
        return (ITopic) getProxy(name);
    }

    public static <E> ISet<E> getSet(String name) {
        name = "m:s:" + name;
        return (ISet) getProxy(name);
    }

    public static <E> IList<E> getList(String name) {
        name = "m:l:" + name;
        return (IList) getProxy(name);
    }

    public static <K, V> MultiMap<K, V> getMultiMap(String name) {
        name = "m:u:" + name;
        return (MultiMap<K, V>) getProxy(name);
    }

    public static Transaction getTransaction() {
        if (!inited.get())
            init();
        ThreadContext threadContext = ThreadContext.get();
        Transaction txn = threadContext.txn;
        if (txn == null)
            txn = threadContext.getTransaction();
        return txn;
    }

    public static ILock getLock(Object key) {
        if (!inited.get())
            init();
        check(key);
        LockProxy lockProxy = mapLockProxies.get(key);
        if (lockProxy == null) {
            lockProxy = new LockProxy(key);
            mapLockProxies.put(key, lockProxy);
        }
        return lockProxy;
    }

    public static Object getProxy(final String name) {
        if (!inited.get())
            init();
        Object proxy = proxies.get(name);
        if (proxy == null) {
            CreateProxyProcess createProxyProcess = new CreateProxyProcess(name);
            synchronized (createProxyProcess) {
                ClusterService.get().enqueueAndReturn(createProxyProcess);
                try {
                    createProxyProcess.wait();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            proxy = createProxyProcess.getProxy();
        }
        return proxy;
    }

    // should only be called from service thread!!
    static Object createProxy(String name) {
        ICommon proxy = proxies.get(name);
        if (proxy == null) {
            if (name.startsWith("q:")) {
                proxy = proxies.get(name);
                if (proxy == null) {
                    proxy = new QProxyImpl(name);
                    proxies.put(name, proxy);
                }
            } else if (name.startsWith("t:")) {
                proxy = proxies.get(name);
                if (proxy == null) {
                    proxy = new TopicProxyImpl(name);
                    proxies.put(name, proxy);
                }
            } else if (name.startsWith("c:")) {
                proxy = proxies.get(name);
                if (proxy == null) {
                    proxy = new MProxyImpl(name);
                    proxies.put(name, proxy);
                }
            } else if (name.startsWith("m:")) {
                proxy = proxies.get(name);
                if (proxy == null) {
                    if (BaseManager.getInstanceType(name) == ICommon.InstanceType.MULTIMAP) {
                        proxy = new MultiMapProxy(name);
                    } else {
                        proxy = new CollectionProxyImpl(name);
                    }
                    proxies.put(name, proxy);
                }
            }
        }
        return proxy;
    }

    public static class LockProxy implements ILock, DataSerializable {

        private Object key = null;
        private transient ILock base = null;

        public LockProxy() {
        }

        public LockProxy(Object key) {
            super();
            this.key = key;
            base = new LockProxyBase();
        }

        private void ensure() {
            if (base == null) {
                base = getLock(key);
            }
        }

        @Override
        public String toString() {
            return "ILock [" + key + "]";
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            LockProxy lockProxy = (LockProxy) o;

            if (key != null ? !key.equals(lockProxy.key) : lockProxy.key != null) return false;

            return true;
        }

        @Override
        public int hashCode() {
            return key != null ? key.hashCode() : 0;
        }

        public void writeData(DataOutput out) throws IOException {
            Data data = ThreadContext.get().toData(key);
            data.writeData(out);
        }

        public void readData(DataInput in) throws IOException {
            Data data = new Data();
            data.readData(in);
            key = ThreadContext.get().toObject(data);
        }

        public void lock() {
            ensure();
            base.lock();
        }

        public void lockInterruptibly() throws InterruptedException {
            ensure();
            base.lockInterruptibly();
        }

        public boolean tryLock() {
            ensure();
            return base.tryLock();
        }

        public boolean tryLock(long time, TimeUnit unit) throws InterruptedException {
            ensure();
            return base.tryLock(time, unit);
        }

        public void unlock() {
            ensure();
            base.unlock();
        }

        public Condition newCondition() {
            ensure();
            return base.newCondition();
        }

        public InstanceType getInstanceType() {
            ensure();
            return InstanceType.LOCK;
        }

        public void destroy() {
            ensure();
            base.destroy();
        }

        private class LockProxyBase implements ILock {
            public void lock() {
                locksMapProxy.lock(key);
            }

            public void lockInterruptibly() throws InterruptedException {
            }

            public Condition newCondition() {
                return null;
            }

            public boolean tryLock() {
                return locksMapProxy.tryLock(key);
            }

            public boolean tryLock(long time, TimeUnit unit) throws InterruptedException {
                return locksMapProxy.tryLock(key, time, unit);
            }

            public void unlock() {
                locksMapProxy.unlock(key);
            }

            public void destroy() {
                locksMapProxy.remove(key);
                mapLockProxies.remove(key);
            }

            public InstanceType getInstanceType() {
                return InstanceType.LOCK;
            }
        }
    }

    private static class CreateProxyProcess implements Processable, Constants {
        String name;

        Object proxy = null;

        public CreateProxyProcess(String name) {
            super();
            this.name = name;
        }

        public void process() {
            proxy = createProxy(name);
            ClusterManager.get().sendProcessableToAll(new CreateProxy(name), false);
            synchronized (CreateProxyProcess.this) {
                CreateProxyProcess.this.notify();
            }
        }

        public Object getProxy() {
            return proxy;
        }
    }

    interface TopicProxy extends ITopic, ICommon {

    }

    public static class TopicProxyImpl implements TopicProxy, DataSerializable {
        private transient TopicProxy base = null;
        private String name = null;

        public TopicProxyImpl() {
        }

        public TopicProxyImpl(String name) {
            this.name = name;
            base = new TopicProxyReal();
        }

        private void ensure() {
            if (base == null) {
                base = (TopicProxy) getProxy(name);
            }
        }

        @Override
        public String toString() {
            return "Topic [" + getName() + "]";
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            TopicProxyImpl that = (TopicProxyImpl) o;

            if (name != null ? !name.equals(that.name) : that.name != null) return false;

            return true;
        }

        @Override
        public int hashCode() {
            return name != null ? name.hashCode() : 0;
        }

        public void writeData(DataOutput out) throws IOException {
            out.writeUTF(name);
        }

        public void readData(DataInput in) throws IOException {
            name = in.readUTF();
        }

        public void publish(Object msg) {
            ensure();
            base.publish(msg);
        }

        public void addMessageListener(MessageListener listener) {
            ensure();
            base.addMessageListener(listener);
        }

        public void removeMessageListener(MessageListener listener) {
            ensure();
            base.removeMessageListener(listener);
        }

        public void destroy() {
            ensure();
            base.destroy();
        }

        public InstanceType getInstanceType() {
            ensure();
            return base.getInstanceType();
        }

        public String getName() {
            ensure();
            return base.getName();
        }

        class TopicProxyReal implements TopicProxy {

            public void publish(Object msg) {
                TopicManager.get().doPublish(name, msg);
            }

            public void addMessageListener(MessageListener listener) {
                ListenerManager.get().addListener(name, listener, null, true,
                        ListenerManager.LISTENER_TYPE_MESSAGE);
            }

            public void removeMessageListener(MessageListener listener) {
                ListenerManager.get().removeListener(name, listener, null);
            }

            public void destroy() {
                TopicManager.TopicDestroy topicDestroy = TopicManager.get().new TopicDestroy();
                topicDestroy.destroy(name);
                proxies.remove(name);
            }

            public ICommon.InstanceType getInstanceType() {
                return ICommon.InstanceType.TOPIC;
            }

            public String getName() {
                return name.substring(2);
            }
        }
    }

    interface CollectionProxy extends IRemoveAwareProxy, ISet, IList {

    }

    public static class CollectionProxyImpl extends BaseCollection implements CollectionProxy, DataSerializable {
        String name = null;
        private transient CollectionProxy base = null;

        public CollectionProxyImpl() {
        }

        public CollectionProxyImpl(String name) {
            this.name = name;
            this.base = new CollectionProxyReal();
        }

        private void ensure() {
            if (base == null) {
                base = (CollectionProxy) getProxy(name);
            }
        }

        @Override
        public String toString() {
            ensure();
            if (getInstanceType() == InstanceType.SET) {
                return "Set [+ " + getName() + "]";
            } else {
                return "List [+ " + getName() + "]";
            }
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            CollectionProxyImpl that = (CollectionProxyImpl) o;

            if (name != null ? !name.equals(that.name) : that.name != null) return false;

            return true;
        }

        @Override
        public int hashCode() {
            return name != null ? name.hashCode() : 0;
        }

        public int size() {
            ensure();
            return base.size();
        }

        public boolean contains(Object o) {
            ensure();
            return base.contains(o);
        }

        public Iterator iterator() {
            ensure();
            return base.iterator();
        }

        public boolean add(Object o) {
            ensure();
            return base.add(o);
        }

        public boolean remove(Object o) {
            ensure();
            return base.remove(o);
        }

        public void clear() {
            ensure();
            base.clear();
        }

        public InstanceType getInstanceType() {
            ensure();
            return base.getInstanceType();
        }

        public void destroy() {
            ensure();
            base.destroy();
        }

        public void writeData(DataOutput out) throws IOException {
            out.writeUTF(name);
        }

        public void readData(DataInput in) throws IOException {
            name = in.readUTF();
        }

        public String getName() {
            ensure();
            return base.getName();
        }

        public void addItemListener(ItemListener itemListener, boolean includeValue) {
            ensure();
            base.addItemListener(itemListener, includeValue);
        }

        public void removeItemListener(ItemListener itemListener) {
            ensure();
            base.removeItemListener(itemListener);
        }

        public boolean removeKey(Object key) {
            ensure();
            return base.removeKey(key);
        }

        class CollectionProxyReal extends BaseCollection implements CollectionProxy {

            final MProxy mapProxy;

            public CollectionProxyReal() {
                mapProxy = new MProxyImpl(name);
            }

            @Override
            public boolean equals(Object o) {
                return CollectionProxyImpl.this.equals(o);
            }

            @Override
            public int hashCode() {
                return CollectionProxyImpl.this.hashCode();
            }

            public InstanceType getInstanceType() {
                return BaseManager.getInstanceType(name);
            }

            public void addItemListener(ItemListener listener, boolean includeValue) {
                mapProxy.addGenericListener(listener, null, includeValue,
                        ListenerManager.LISTENER_TYPE_ITEM);
            }

            public void removeItemListener(ItemListener listener) {
                mapProxy.removeGenericListener(listener, null);
            }

            public String getName() {
                return name.substring(4);
            }

            @Override
            public boolean add(Object obj) {
                return mapProxy.add(obj);
            }

            @Override
            public boolean remove(Object obj) {
                return mapProxy.removeKey(obj);
            }

            public boolean removeKey(Object obj) {
                return mapProxy.removeKey(obj);
            }

            @Override
            public boolean contains(Object obj) {
                return mapProxy.containsKey(obj);
            }

            @Override
            public Iterator iterator() {
                return mapProxy.keySet().iterator();
            }

            @Override
            public int size() {
                return mapProxy.size();
            }

            public MProxy getCProxy() {
                return mapProxy;
            }

            public void destroy() {
                mapProxy.destroy();
                proxies.remove(name);
            }

        }

    }

    public static abstract class BaseCollection extends AbstractCollection implements List {

        public void add(int index, Object element) {
            throw new UnsupportedOperationException();
        }

        public boolean addAll(int index, Collection c) {
            throw new UnsupportedOperationException();
        }

        public Object get(int index) {
            throw new UnsupportedOperationException();
        }

        public int indexOf(Object o) {
            throw new UnsupportedOperationException();
        }

        public int lastIndexOf(Object o) {
            throw new UnsupportedOperationException();
        }

        public ListIterator listIterator() {
            throw new UnsupportedOperationException();
        }

        public ListIterator listIterator(int index) {
            throw new UnsupportedOperationException();
        }

        public Object remove(int index) {
            throw new UnsupportedOperationException();
        }

        public Object set(int index, Object element) {
            throw new UnsupportedOperationException();
        }

        public List subList(int fromIndex, int toIndex) {
            throw new UnsupportedOperationException();
        }
    }

    interface QProxy extends IQueue {

        boolean offer(Object obj);

        boolean offer(Object obj, long timeout, TimeUnit unit) throws InterruptedException;

        void put(Object obj) throws InterruptedException;

        Object peek();

        Object poll();

        Object poll(long timeout, TimeUnit unit) throws InterruptedException;

        Object take() throws InterruptedException;

        int remainingCapacity();

        Iterator iterator();

        int size();

        void addItemListener(ItemListener listener, boolean includeValue);

        void removeItemListener(ItemListener listener);

        String getName();

        boolean remove(Object obj);

        int drainTo(Collection c);

        int drainTo(Collection c, int maxElements);

        void destroy();

        InstanceType getInstanceType();
    }

    public static class QProxyImpl extends AbstractQueue implements QProxy, DataSerializable {
        private transient QProxy qproxyReal = null;
        private String name = null;

        public QProxyImpl() {
        }

        private QProxyImpl(String name) {
            this.name = name;
            qproxyReal = new QProxyReal();
        }

        private void ensure() {
            if (qproxyReal == null) {
                qproxyReal = (QProxy) getProxy(name);
            }
        }

        @Override
        public String toString() {
            return "Queue [" + getName() + "]";
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            QProxyImpl qProxy = (QProxyImpl) o;

            if (name != null ? !name.equals(qProxy.name) : qProxy.name != null) return false;

            return true;
        }

        @Override
        public int hashCode() {
            return name != null ? name.hashCode() : 0;
        }

        public void writeData(DataOutput out) throws IOException {
            out.writeUTF(name);
        }

        public void readData(DataInput in) throws IOException {
            name = in.readUTF();
        }

        public Iterator iterator() {
            ensure();
            return qproxyReal.iterator();
        }

        public int size() {
            ensure();
            return qproxyReal.size();
        }

        public void addItemListener(ItemListener listener, boolean includeValue) {
            ensure();
            qproxyReal.addItemListener(listener, includeValue);
        }

        public void removeItemListener(ItemListener listener) {
            ensure();
            qproxyReal.removeItemListener(listener);
        }

        public String getName() {
            ensure();
            return qproxyReal.getName();
        }

        public int drainTo(Collection c) {
            ensure();
            return qproxyReal.drainTo(c);
        }

        public int drainTo(Collection c, int maxElements) {
            ensure();
            return qproxyReal.drainTo(c, maxElements);
        }

        public void destroy() {
            ensure();
            qproxyReal.destroy();
        }

        public InstanceType getInstanceType() {
            ensure();
            return qproxyReal.getInstanceType();
        }

        public boolean offer(Object o) {
            ensure();
            return qproxyReal.offer(o);
        }

        public boolean offer(Object obj, long timeout, TimeUnit unit) throws InterruptedException {
            ensure();
            return qproxyReal.offer(obj, timeout, unit);
        }

        public void put(Object obj) throws InterruptedException {
            ensure();
            qproxyReal.put(obj);
        }

        public Object poll() {
            ensure();
            return qproxyReal.poll();
        }

        public Object poll(long timeout, TimeUnit unit) throws InterruptedException {
            ensure();
            return qproxyReal.poll(timeout, unit);
        }

        public Object take() throws InterruptedException {
            ensure();
            return qproxyReal.take();
        }

        public int remainingCapacity() {
            ensure();
            return qproxyReal.remainingCapacity();
        }

        public Object peek() {
            ensure();
            return qproxyReal.peek();
        }

        private class QProxyReal extends AbstractQueue implements QProxy {


            public QProxyReal() {
            }

            public boolean offer(Object obj) {
                Offer offer = ThreadContext.get().getOffer();
                return offer.offer(name, obj, 0, ThreadContext.get().getTxnId());
            }

            public boolean offer(Object obj, long timeout, TimeUnit unit) throws InterruptedException {
                if (timeout < 0) {
                    timeout = 0;
                }
                Offer offer = ThreadContext.get().getOffer();
                return offer.offer(name, obj, unit.toMillis(timeout), ThreadContext.get().getTxnId());
            }

            public void put(Object obj) throws InterruptedException {
                Offer offer = ThreadContext.get().getOffer();
                offer.offer(name, obj, -1, ThreadContext.get().getTxnId());
            }

            public Object peek() {
                Poll poll = BlockingQueueManager.get().new Poll();
                return poll.peek(name);
            }

            public Object poll() {
                Poll poll = BlockingQueueManager.get().new Poll();
                return poll.poll(name, 0);
            }

            public Object poll(long timeout, TimeUnit unit) throws InterruptedException {
                if (timeout < 0) {
                    timeout = 0;
                }
                Poll poll = BlockingQueueManager.get().new Poll();
                return poll.poll(name, unit.toMillis(timeout));
            }

            public Object take() throws InterruptedException {
                Poll poll = BlockingQueueManager.get().new Poll();
                return poll.poll(name, -1);
            }

            public int remainingCapacity() {
                throw new UnsupportedOperationException();
            }

            @Override
            public Iterator iterator() {
                QIterator iterator = BlockingQueueManager.get().new QIterator();
                iterator.set(name);
                return iterator;
            }

            @Override
            public int size() {
                Size size = BlockingQueueManager.get().new Size();
                return size.getSize(name);
            }

            public void addItemListener(ItemListener listener, boolean includeValue) {
                ListenerManager.get().addListener(name, listener, null, includeValue,
                        ListenerManager.LISTENER_TYPE_ITEM);
            }

            public void removeItemListener(ItemListener listener) {
                ListenerManager.get().removeListener(name, listener, null);
            }

            public String getName() {
                return name;
            }

            @Override
            public boolean remove(Object obj) {
                throw new UnsupportedOperationException();
            }

            public int drainTo(Collection c) {
                throw new UnsupportedOperationException();
            }

            public int drainTo(Collection c, int maxElements) {
                throw new UnsupportedOperationException();
            }

            public void destroy() {
                clear();
                QDestroy qDestroy = BlockingQueueManager.get().new QDestroy();
                qDestroy.destroy(name);
                proxies.remove(name);
            }

            public ICommon.InstanceType getInstanceType() {
                return ICommon.InstanceType.QUEUE;
            }
        }
    }

    public static class MultiMapProxy implements MultiMap, DataSerializable, IGetAwareProxy {

        private String name = null;

        private transient MultiMapBase base = null;

        public MultiMapProxy() {
        }

        public MultiMapProxy(String name) {
            this.name = name;
            this.base = new MultiMapBase();
        }

        private void ensure() {
            if (base == null) {
                base = (MultiMapBase) getProxy(name);
            }
        }

        @Override
        public String toString() {
            return "MultiMap [" + getName() + "]";
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            MultiMapProxy that = (MultiMapProxy) o;

            if (name != null ? !name.equals(that.name) : that.name != null) return false;

            return true;
        }

        @Override
        public int hashCode() {
            return name != null ? name.hashCode() : 0;
        }

        public void writeData(DataOutput out) throws IOException {
            out.writeUTF(name);
        }

        public void readData(DataInput in) throws IOException {
            name = in.readUTF();
        }

        public InstanceType getInstanceType() {
            ensure();
            return base.getInstanceType();
        }

        public void destroy() {
            ensure();
            base.destroy();
        }

        public String getName() {
            ensure();
            return base.getName();
        }

        public boolean put(Object key, Object value) {
            ensure();
            return base.put(key, value);
        }

        public Collection get(Object key) {
            ensure();
            return base.get(key);
        }

        public boolean remove(Object key, Object value) {
            ensure();
            return base.remove(key, value);
        }

        public Collection remove(Object key) {
            ensure();
            return base.remove(key);
        }

        public Set keySet() {
            ensure();
            return base.keySet();
        }

        public Collection values() {
            ensure();
            return base.values();
        }

        public Set entrySet() {
            ensure();
            return base.entrySet();
        }

        public boolean containsKey(Object key) {
            ensure();
            return base.containsKey(key);
        }

        public boolean containsValue(Object value) {
            ensure();
            return base.containsValue(value);
        }

        public boolean containsEntry(Object key, Object value) {
            ensure();
            return base.containsEntry(key, value);
        }

        public int size() {
            ensure();
            return base.size();
        }

        public void clear() {
            ensure();
            base.clear();
        }

        private class MultiMapBase implements MultiMap, IGetAwareProxy {
            final MProxy mapProxy;

            private MultiMapBase() {
                mapProxy = new MProxyImpl(name);
            }

            public String getName() {
                return name.substring(4);
            }

            public void clear() {
                mapProxy.clear();
            }

            public boolean containsEntry(Object key, Object value) {
                return mapProxy.containsEntry(key, value);
            }

            public boolean containsKey(Object key) {
                return mapProxy.containsKey(key);
            }

            public boolean containsValue(Object value) {
                return mapProxy.containsValue(value);
            }

            public Collection get(Object key) {
                return (Collection) mapProxy.get(key);
            }

            public boolean put(Object key, Object value) {
                return mapProxy.putMulti(key, value);
            }

            public boolean remove(Object key, Object value) {
                return mapProxy.removeMulti(key, value);
            }

            public Collection remove(Object key) {
                return (Collection) mapProxy.remove(key);
            }

            public int size() {
                return mapProxy.size();
            }

            public Set keySet() {
                return mapProxy.keySet();
            }

            public Collection values() {
                return mapProxy.values();
            }

            public Set entrySet() {
                return mapProxy.entrySet();
            }

            public InstanceType getInstanceType() {
                return InstanceType.MULTIMAP;
            }

            public void destroy() {
                mapProxy.destroy();
                proxies.remove(name);
            }
        }
    }

    interface IRemoveAwareProxy {

        boolean removeKey(Object key);
    }

    interface IGetAwareProxy {

        Object get(Object key);
    }

    private static void check(Object obj) {
        if (obj == null)
            throw new RuntimeException("Object cannot be null.");

        if (!(obj instanceof Serializable)) {
            throw new IllegalArgumentException(obj.getClass().getName() + " is not Serializable.");
        }
    }

    interface MProxy extends IMap, IRemoveAwareProxy, IGetAwareProxy {
        String getLongName();

        void addGenericListener(Object listener, Object key, boolean includeValue, int listenerType);

        void removeGenericListener(Object listener, Object key);

        boolean containsEntry(Object key, Object value);

        boolean putMulti(Object key, Object value);

        boolean removeMulti(Object key, Object value);

        boolean add(Object value);
    }


    public static class MProxyImpl implements MProxy, DataSerializable {

        private String name = null;

        private transient MProxy mproxyReal = null;

        public MProxyImpl() {
        }

        private MProxyImpl(String name) {
            this.name = name;
            mproxyReal = new MProxyReal();
        }

        public void writeData(DataOutput out) throws IOException {
            out.writeUTF(name);
        }

        public void readData(DataInput in) throws IOException {
            name = in.readUTF();
        }

        private void ensure() {
            if (mproxyReal == null) {
                mproxyReal = (MProxy) getProxy(name);
            }
        }

        @Override
        public String toString() {
            return "Map [" + getName() + "]";
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            MProxyImpl mProxy = (MProxyImpl) o;

            if (name != null ? !name.equals(mProxy.name) : mProxy.name != null) return false;

            return true;
        }

        @Override
        public int hashCode() {
            return name != null ? name.hashCode() : 0;
        }

        public void destroy() {
            ensure();
            mproxyReal.destroy();
        }

        public ICommon.InstanceType getInstanceType() {
            ensure();
            return mproxyReal.getInstanceType();
        }

        public boolean removeKey(Object key) {
            ensure();
            return mproxyReal.removeKey(key);
        }

        public int size() {
            ensure();
            return mproxyReal.size();
        }

        public boolean isEmpty() {
            ensure();
            return mproxyReal.isEmpty();
        }

        public boolean containsKey(Object key) {
            ensure();
            return mproxyReal.containsKey(key);
        }

        public boolean containsValue(Object value) {
            ensure();
            return mproxyReal.containsValue(value);
        }

        public MapEntry getMapEntry(Object key) {
            ensure();
            return mproxyReal.getMapEntry(key);
        }

        public Object get(Object key) {
            ensure();
            return mproxyReal.get(key);
        }

        public Object put(Object key, Object value) {
            ensure();
            return mproxyReal.put(key, value);
        }

        public Object remove(Object key) {
            ensure();
            return mproxyReal.remove(key);
        }

        public void putAll(Map t) {
            ensure();
            mproxyReal.putAll(t);
        }

        public void clear() {
            ensure();
            mproxyReal.clear();
        }

        public Set keySet() {
            ensure();
            return mproxyReal.keySet();
        }

        public Collection values() {
            ensure();
            return mproxyReal.values();
        }

        public Set entrySet() {
            ensure();
            return mproxyReal.entrySet();
        }

        public Object putIfAbsent(Object key, Object value) {
            ensure();
            return mproxyReal.putIfAbsent(key, value);
        }

        public boolean remove(Object key, Object value) {
            ensure();
            return mproxyReal.remove(key, value);
        }

        public boolean replace(Object key, Object oldValue, Object newValue) {
            ensure();
            return mproxyReal.replace(key, oldValue, newValue);
        }

        public Object replace(Object key, Object value) {
            ensure();
            return mproxyReal.replace(key, value);
        }

        public String getName() {
            ensure();
            return mproxyReal.getName();
        }

        public void lock(Object key) {
            ensure();
            mproxyReal.lock(key);
        }

        public boolean tryLock(Object key) {
            ensure();
            return mproxyReal.tryLock(key);
        }

        public boolean tryLock(Object key, long time, TimeUnit timeunit) {
            ensure();
            return mproxyReal.tryLock(key, time, timeunit);
        }

        public void unlock(Object key) {
            ensure();
            mproxyReal.unlock(key);
        }

        public String getLongName() {
            ensure();
            return mproxyReal.getLongName();
        }

        public void addGenericListener(Object listener, Object key, boolean includeValue, int listenerType) {
            ensure();
            mproxyReal.addGenericListener(listener, key, includeValue, listenerType);
        }

        public void removeGenericListener(Object listener, Object key) {
            ensure();
            mproxyReal.removeGenericListener(listener, key);
        }

        public void addEntryListener(EntryListener listener, boolean includeValue) {
            ensure();
            mproxyReal.addEntryListener(listener, includeValue);
        }

        public void addEntryListener(EntryListener listener, Object key, boolean includeValue) {
            ensure();
            mproxyReal.addEntryListener(listener, key, includeValue);
        }

        public void removeEntryListener(EntryListener listener) {
            ensure();
            mproxyReal.removeEntryListener(listener);
        }

        public void removeEntryListener(EntryListener listener, Object key) {
            ensure();
            mproxyReal.removeEntryListener(listener, key);
        }

        public boolean containsEntry(Object key, Object value) {
            ensure();
            return mproxyReal.containsEntry(key, value);
        }

        public boolean putMulti(Object key, Object value) {
            ensure();
            return mproxyReal.putMulti(key, value);
        }

        public boolean removeMulti(Object key, Object value) {
            ensure();
            return mproxyReal.removeMulti(key, value);
        }

        public boolean add(Object value) {
            ensure();
            return mproxyReal.add(value);
        }

        private class MProxyReal implements MProxy {

            final InstanceType instanceType;

            public MProxyReal() {
                super();
                this.instanceType = BaseManager.getInstanceType(name);
            }

            @Override
            public String toString() {
                return "Map [" + getName() + "]";
            }

            public InstanceType getInstanceType() {
                return instanceType;
            }

            @Override
            public boolean equals(Object o) {
                return MProxyImpl.this.equals(o);
            }

            @Override
            public int hashCode() {
                return MProxyImpl.this.hashCode();
            }

            public String getLongName() {
                return name;
            }

            public String getName() {
                return name.substring(2);
            }

            public MapEntry getMapEntry(Object key) {
                check(key);
                MGetMapEntry mgetMapEntry = ConcurrentMapManager.get().new MGetMapEntry();
                return mgetMapEntry.get(name, key);
            }

            public boolean putMulti(Object key, Object value) {
                check(key);
                check(value);
                MPutMulti mput = ThreadContext.get().getMPutMulti();
                return mput.put(name, key, value);
            }

            public Object put(Object key, Object value) {
                check(key);
                check(value);
                MPut mput = ThreadContext.get().getMPut();
                return mput.put(name, key, value, -1, -1);
            }

            public Object get(Object key) {
                check(key);
                MGet mget = ThreadContext.get().getMGet();
                return mget.get(name, key, -1, -1);
            }

            public Object remove(Object key) {
                check(key);
                MRemove mremove = ThreadContext.get().getMRemove();
                return mremove.remove(name, key, -1, -1);
            }

            public int size() {
                //            MSize msize = ConcurrentMapManager.get().new MSize();
                //            int size = msize.getSize(name);
                MSize msize = ConcurrentMapManager.get().new MSize(name);
                int size = (Integer) msize.call();
                TransactionImpl txn = ThreadContext.get().txn;
                if (txn != null) {
                    size += txn.size(name);
                }
                return (size < 0) ? 0 : size;
            }

            public Object putIfAbsent(Object key, Object value) {
                check(key);
                check(value);
                MPut mput = ThreadContext.get().getMPut();
                return mput.putIfAbsent(name, key, value, -1, -1);
            }

            public boolean removeMulti(Object key, Object value) {
                check(key);
                check(value);
                MRemoveMulti mremove = ThreadContext.get().getMRemoveMulti();
                return mremove.remove(name, key, value);
            }

            public boolean remove(Object key, Object value) {
                check(key);
                check(value);
                MRemove mremove = ThreadContext.get().getMRemove();
                return (mremove.removeIfSame(name, key, value, -1, -1) != null);
            }

            public Object replace(Object key, Object value) {
                check(key);
                check(value);
                MPut mput = ThreadContext.get().getMPut();
                return mput.replace(name, key, value, -1, -1);
            }

            public boolean replace(Object key, Object oldValue, Object newValue) {
                check(key);
                check(newValue);
                throw new UnsupportedOperationException();
            }

            public void lock(Object key) {
                check(key);
                MLock mlock = ThreadContext.get().getMLock();
                mlock.lock(name, key, -1, -1);
            }

            public boolean tryLock(Object key) {
                check(key);
                MLock mlock = ThreadContext.get().getMLock();
                return mlock.lock(name, key, 0, -1);
            }

            public boolean tryLock(Object key, long time, TimeUnit timeunit) {
                check(key);
                if (time < 0)
                    throw new IllegalArgumentException("Time cannot be negative. time = " + time);
                MLock mlock = ThreadContext.get().getMLock();
                return mlock.lock(name, key, timeunit.toMillis(time), -1);
            }

            public void unlock(Object key) {
                check(key);
                MLock mlock = ThreadContext.get().getMLock();
                mlock.unlock(name, key, 0, -1);
            }

            public void addGenericListener(Object listener, Object key, boolean includeValue, int listenerType) {
                if (listener == null)
                    throw new IllegalArgumentException("Listener cannot be null");
                ListenerManager.get().addListener(name, listener, key, includeValue, listenerType);
            }

            public void removeGenericListener(Object listener, Object key) {
                if (listener == null)
                    throw new IllegalArgumentException("Listener cannot be null");
                ListenerManager.get().removeListener(name, listener, key);
            }

            public void addEntryListener(EntryListener listener, boolean includeValue) {
                if (listener == null)
                    throw new IllegalArgumentException("Listener cannot be null");
                addGenericListener(listener, null, includeValue, ListenerManager.LISTENER_TYPE_MAP);
            }

            public void addEntryListener(EntryListener listener, Object key, boolean includeValue) {
                if (listener == null)
                    throw new IllegalArgumentException("Listener cannot be null");
                check(key);
                addGenericListener(listener, key, includeValue, ListenerManager.LISTENER_TYPE_MAP);
            }

            public void removeEntryListener(EntryListener listener) {
                if (listener == null)
                    throw new IllegalArgumentException("Listener cannot be null");
                removeGenericListener(listener, null);
            }

            public void removeEntryListener(EntryListener listener, Object key) {
                if (listener == null)
                    throw new IllegalArgumentException("Listener cannot be null");
                check(key);
                removeGenericListener(listener, key);
            }

            public boolean containsEntry(Object key, Object value) {
                check(key);
                check(value);
                TransactionImpl txn = ThreadContext.get().txn;
                if (txn != null) {
                    if (txn.has(name, key)) {
                        Object v = txn.get(name, key);
                        if (v == null)
                            return false; // removed inside the txn
                        else
                            return true;
                    }
                }
                MContainsKey mContainsKey = ConcurrentMapManager.get().new MContainsKey();
                return mContainsKey.containsEntry(name, key, value, -1);
            }

            public boolean containsKey(Object key) {
                check(key);
                TransactionImpl txn = ThreadContext.get().txn;
                if (txn != null) {
                    if (txn.has(name, key)) {
                        Object value = txn.get(name, key);
                        if (value == null)
                            return false; // removed inside the txn
                        else
                            return true;
                    }
                }
                MContainsKey mContainsKey = ConcurrentMapManager.get().new MContainsKey();
                return mContainsKey.containsKey(name, key, -1);
            }

            public boolean containsValue(Object value) {
                check(value);
                TransactionImpl txn = ThreadContext.get().txn;
                if (txn != null) {
                    if (txn.containsValue(name, value))
                        return true;
                }
                MContainsValue mContainsValue = ConcurrentMapManager.get().new MContainsValue(name, value);
                return (Boolean) mContainsValue.call();
            }

            public boolean isEmpty() {
                return (size() == 0);
            }

            public void putAll(Map map) {
                Set<Entry> entries = map.entrySet();
                for (Entry entry : entries) {
                    put(entry.getKey(), entry.getValue());
                }
            }

            public boolean add(Object value) {
                if (value == null)
                    throw new NullPointerException();
                MAdd madd = ThreadContext.get().getMAdd();
                if (instanceType == InstanceType.LIST) {
                    return madd.addToList(name, value);
                } else {
                    return madd.addToSet(name, value);
                }
            }

            public boolean removeKey(Object key) {
                if (key == null)
                    throw new NullPointerException();
                MRemoveItem mRemoveItem = ConcurrentMapManager.get().new MRemoveItem();
                return mRemoveItem.removeItem(name, key);
            }

            public void clear() {
                Set keys = keySet();
                for (Object key : keys) {
                    removeKey(key);
                }
            }

            public Set entrySet() {
                return (Set) iterate(MIterate.TYPE_ENTRIES);
            }

            public Set keySet() {
                return (Set) iterate(MIterate.TYPE_KEYS);
            }

            public Collection values() {
                return iterate(MIterate.TYPE_VALUES);

            }

            private Collection iterate(int iteratorType) {
                MIterate miterate = ConcurrentMapManager.get().new MIterate(name, iteratorType);
                return (Collection) miterate.call();
            }

            public void destroy() {
                clear();
                MDestroy mDestroy = ConcurrentMapManager.get().new MDestroy();
                mDestroy.destroy(name);
                proxies.remove(name);
            }
        }
    }

    public static class IdGeneratorProxy implements IdGenerator, DataSerializable {

        private String name = null;
        private transient IdGenerator base = null;

        public IdGeneratorProxy() {
        }

        public IdGeneratorProxy(String name) {
            this.name = name;
            base = new IdGeneratorBase();
        }


        private void ensure() {
            if (base == null) {
                base = getIdGenerator(name);
            }
        }

        @Override
        public String toString() {
            return "IdGenerator [" + getName() + "]";
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            IdGeneratorProxy that = (IdGeneratorProxy) o;

            if (name != null ? !name.equals(that.name) : that.name != null) return false;

            return true;
        }

        @Override
        public int hashCode() {
            return name != null ? name.hashCode() : 0;
        }

        public void writeData(DataOutput out) throws IOException {
            out.writeUTF(name);
        }

        public void readData(DataInput in) throws IOException {
            name = in.readUTF();
        }


        public ICommon.InstanceType getInstanceType() {
            ensure();
            return base.getInstanceType();
        }

        public void destroy() {
            ensure();
            base.destroy();
        }

        public String getName() {
            ensure();
            return base.getName();
        }

        public long newId() {
            ensure();
            return base.newId();
        }


        private class IdGeneratorBase implements IdGenerator {

            private static final long BILLION = 1 * 1000 * 1000;

            AtomicLong billion = new AtomicLong(-1);

            AtomicLong currentId = new AtomicLong(2 * BILLION);

            AtomicBoolean fetching = new AtomicBoolean(false);


            public String getName() {
                return name;
            }

            public long newId() {
                long billionNow = billion.get();
                long idAddition = currentId.incrementAndGet();
                if (idAddition >= BILLION) {
                    synchronized (this) {
                        try {
                            billionNow = billion.get();
                            idAddition = currentId.incrementAndGet();
                            if (idAddition >= BILLION) {
                                Long idBillion = getNewBillion();
                                long newBillion = idBillion.longValue() * BILLION;
                                billion.set(newBillion);
                                currentId.set(0);
                            }
                            billionNow = billion.get();
                            idAddition = currentId.incrementAndGet();
                        } catch (Throwable t) {
                            t.printStackTrace();
                        }
                    }

                }
                long result = billionNow + idAddition;
                return result;
            }

            private Long getNewBillion() {
                try {
                    DistributedTask<Long> task = new DistributedTask<Long>(new IncrementTask(name));
                    FactoryImpl.executorServiceImpl.execute(task);
                    return task.get();
                } catch (Exception e) {
                    e.printStackTrace();
                }
                return null;
            }

            public InstanceType getInstanceType() {
                return InstanceType.ID_GENERATOR;
            }

            public void destroy() {
                mapIdGenerators.remove(name);
                idGeneratorMapProxy.remove(name);
            }
        }
    }

    public static class IncrementTask implements Callable<Long>, Serializable {
        String name = null;

        public IncrementTask() {
            super();
        }

        public IncrementTask(String uuidName) {
            super();
            this.name = uuidName;
        }

        public Long call() {
            MProxy map = FactoryImpl.idGeneratorMapProxy;
            map.lock(name);
            try {
                Long max = (Long) map.get(name);
                if (max == null) {
                    max = Long.valueOf(0l);
                    map.put(name, Long.valueOf(0));
                    return max;
                } else {
                    Long newMax = Long.valueOf(max.longValue() + 1);
                    map.put(name, newMax);
                    return newMax;
                }
            } finally {
                map.unlock(name);
            }
        }
    }

}
