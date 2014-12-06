/* 
 * Copyright (c) 2008-2009, Hazel Ltd. All Rights Reserved.
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

package com.hazelcast.core;

import com.hazelcast.config.Config;

import java.util.Collection;
import java.util.concurrent.ExecutorService;

/**
 * Hazelcast instance. Each Hazelcast instance is a member.
 * Multiple Hazelcast instances can be created on a JVM.
 * Each Hazelcast instance has its own socket, threads.
 *
 * @see Hazelcast#newHazelcastInstance(Config config) 
 */
public interface HazelcastInstance {

    /**
     * Returns the name of this Hazelcast instance
     *
     * @return name of this Hazelcast instance
     */
    public String getName();

    /**
     * Returns the distributed queue instance with the specified name.
     *
     * @param name name of the distributed queue
     * @return distributed queue instance with the specified name
     */
    public <E> IQueue<E> getQueue(String name);

    /**
     * Returns the distributed topic instance with the specified name.
     *
     * @param name name of the distributed topic
     * @return distributed topic instance with the specified name
     */
    public <E> ITopic<E> getTopic(String name);

    /**
     * Returns the distributed set instance with the specified name.
     *
     * @param name name of the distributed set
     * @return distributed set instance with the specified name
     */
    public <E> ISet<E> getSet(String name);

    /**
     * Returns the distributed list instance with the specified name.
     * Index based operations on the list are not supported.
     *
     * @param name name of the distributed list
     * @return distributed list instance with the specified name
     */
    public <E> IList<E> getList(String name);

    /**
     * Returns the distributed map instance with the specified name.
     *
     * @param name name of the distributed map
     * @return distributed map instance with the specified name
     */
    public <K, V> IMap<K, V> getMap(String name);

    /**
     * Returns the distributed multimap instance with the specified name.
     *
     * @param name name of the distributed multimap
     * @return distributed multimap instance with the specified name
     */
    public <K, V> MultiMap<K, V> getMultiMap(String name);

    /**
     * Returns the distributed lock instance for the specified key object.
     * The specified object is considered to be the key for this lock.
     * So keys are considered equals cluster-wide as long as
     * they are serialized to the same byte array such as String, long,
     * Integer.
     * <p/>
     * Locks are fail-safe. If a member holds a lock and some of the
     * members go down, cluster will keep your locks safe and available.
     * Moreover, when a member leaves the cluster, all the locks acquired
     * by this dead member will be removed so that these locks can be
     * available for live members immediately.
     * <pre>
     * Lock lock = Hazelcast.getLock("PROCESS_LOCK");
     * lock.lock();
     * try {
     *   // process
     * } finally {
     *   lock.unlock();
     * }
     * </pre>
     *
     * @param key key of the lock instance
     * @return distributed lock instance for the specified key.
     */
    public ILock getLock(Object key);

    /**
     * Returns the Cluster that this Hazelcast instance is part of.
     * Cluster interface allows you to add listener for membership
     * events and learn more about the cluster that this Hazelcast
     * instance is part of.
     *
     * @return cluster that this Hazelcast instance is part of
     */
    public Cluster getCluster();

    /**
     * Returns the distributed executor service. This executor
     * service enables you to run your <tt>Runnable</tt>s and <tt>Callable</tt>s
     * on the Hazelcast cluster.
     *
     * @return distrubuted executor service of this Hazelcast instance
     */
    public ExecutorService getExecutorService();

    /**
     * Returns the transaction instance associated with the current thread,
     * creates a new one if it wasn't already.
     * <p/>
     * Transaction doesn't start until you call <tt>transaction.begin()</tt> and
     * if a transaction is started then all transactional Hazelcast operations
     * are automatically transational.
     * <pre>
     *  Map map = Hazelcast.getMap("mymap");
     *  Transaction txn = Hazelcast.getTransaction();
     *  txn.begin();
     *  try {
     *    map.put ("key", "value");
     *    txn.commit();
     *  }catch (Exception e) {
     *    txn.rollback();
     *  }
     * </pre>
     * Isolation is always <tt>READ_COMMITTED</tt> . If you are in
     * a transaction, you can read the data in your transaction and the data that
     * is already committed and if not in a transaction, you can only read the
     * committed data. Implementation is different for queue and map/set. For
     * queue operations (offer,poll), offered and/or polled objects are copied to
     * the next member in order to safely commit/rollback. For map/set, Hazelcast
     * first acquires the locks for the write operations (put, remove) and holds
     * the differences (what is added/removed/updated) locally for each transaction.
     * When transaction is set to commit, Hazelcast will release the locks and
     * apply the differences. When rolling back, Hazelcast will simply releases
     * the locks and discard the differences. Transaction instance is attached
     * to the current thread and each Hazelcast operation checks if the current
     * thread holds a transaction, if so, operation will be transaction aware.
     * When transaction is committed, rolled back or timed out, it will be detached
     * from the thread holding it.
     *
     * @return transaction for the current thread
     */
    public Transaction getTransaction();

    /**
     * Creates cluster-wide unique IDs. Generated IDs are long type primitive values
     * between <tt>0</tt> and <tt>Long.MAX_VALUE</tt> . Id generation occurs almost at the speed of
     * <tt>AtomicLong.incrementAndGet()</tt> . Generated IDs are unique during the life
     * cycle of the cluster. If the entire cluster is restarted, IDs start from <tt>0</tt> again.
     *
     * @param name
     * @return
     */
    public IdGenerator getIdGenerator(String name);

    /**
     * Detaches this member from the cluster.
     * It doesn't shutdown the entire cluster, it shuts down
     * this local member only.
     */
    public void shutdown();

    /**
     * Detaches this member from the cluster first and then restarts it
     * as a new member.
     */
    public void restart();

    /**
     * Returns all queue, map, set, list, topic, lock, multimap
     * instances created by Hazelcast.
     *
     * @return the collection of instances created by Hazelcast.
     */
    public Collection<Instance> getInstances();

    /**
     * Add a instance listener which will be notified when a
     * new instance such as map, queue, multima, topic, lock is
     * added or removed.
     *
     * @param instanceListener instance listener
     */
    public void addInstanceListener(InstanceListener instanceListener);

    /**
     * Removes the specified instance listener. Returns silently
     * if specified instance listener doesn't exist.
     *
     * @param instanceListener instance listener to remove
     */
    public void removeInstanceListener(InstanceListener instanceListener);

    /**
     * Returns the configuration of this Hazelcast instance.
     *
     * @return configuration of this Hazelcast instance
     */
    public Config getConfig();
}
