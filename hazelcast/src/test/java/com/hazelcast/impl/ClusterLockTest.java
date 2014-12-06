/*
 * Copyright (c) 2008-2012, Hazel Bilisim Ltd. All Rights Reserved.
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
 */

package com.hazelcast.impl;

import com.hazelcast.config.Config;
import com.hazelcast.config.MapConfig;
import com.hazelcast.config.XmlConfigBuilder;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.IMap;
import com.hazelcast.core.Transaction;
import com.hazelcast.impl.base.DistributedLock;
import com.hazelcast.nio.Data;
import junit.framework.Assert;
import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static com.hazelcast.impl.TestUtil.getCMap;
import static com.hazelcast.impl.TestUtil.migrateKey;
import static com.hazelcast.nio.IOUtil.toData;
import static junit.framework.Assert.assertNull;
import static junit.framework.Assert.assertTrue;
import static junit.framework.Assert.fail;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

@RunWith(com.hazelcast.util.RandomBlockJUnit4ClassRunner.class)
public class ClusterLockTest {

    @BeforeClass
    public static void init() throws Exception {
        System.setProperty(GroupProperties.PROP_WAIT_SECONDS_BEFORE_JOIN, "1");
        Hazelcast.shutdownAll();
    }

    @After
    public void cleanup() throws Exception {
        Hazelcast.shutdownAll();
    }

    @Test(timeout = 100000)
    public void testScheduledLockActionForDeadMember() throws Exception {
        final HazelcastInstance h1 = Hazelcast.newHazelcastInstance(new Config());
        final IMap map1 = h1.getMap("default");
        map1.put(1, 1);
        final HazelcastInstance h2 = Hazelcast.newHazelcastInstance(new Config());
        final IMap map2 = h2.getMap("default");
        Assert.assertTrue(map1.tryLock(1));
        new Thread(new Runnable() {
            public void run() {
                try {
                    map2.lock(1);
                    fail("Shouldn't be able to lock!");
                } catch (Throwable e) {
                }
            }
        }).start();
        Thread.sleep(2000);
        h2.getLifecycleService().shutdown();
        Thread.sleep(2000);
        map1.unlock(1);
        Assert.assertTrue(map1.tryLock(1));
    }

    @Test(timeout = 100000)
    public void testLockOwnerDiesWaitingMemberObtains() throws Exception {
        final HazelcastInstance h1 = Hazelcast.newHazelcastInstance(new Config());
        final IMap map1 = h1.getMap("default");
        final HazelcastInstance h2 = Hazelcast.newHazelcastInstance(new Config());
        final IMap map2 = h2.getMap("default");
        final HazelcastInstance h3 = Hazelcast.newHazelcastInstance(new Config());
        final IMap map3 = h3.getMap("default");
        map1.put(1, 1);
        migrateKey(1, h1, h2, 0);
        migrateKey(1, h1, h3, 1);
        final CountDownLatch latchShutdown = new CountDownLatch(1);
        final CountDownLatch latchLock = new CountDownLatch(1);
        Assert.assertTrue(map2.tryLock(1));
        new Thread(new Runnable() {
            public void run() {
                try {
                    map3.lock(1);
                    latchShutdown.countDown();
                    assertTrue(latchLock.await(10, TimeUnit.SECONDS));
                    map3.unlock(1);
                } catch (Throwable e) {
                    fail(e.getMessage());
                }
            }
        }).start();
        Thread.sleep(2000);
        h2.getLifecycleService().shutdown();
        assertTrue(latchShutdown.await(10, TimeUnit.SECONDS));
        Assert.assertFalse(map1.tryLock(1));
        latchLock.countDown();
        Assert.assertTrue(map1.tryLock(1, 10, TimeUnit.SECONDS));
    }

    @Test(timeout = 100000)
    public void testKeyOwnerDies() throws Exception {
        final HazelcastInstance h1 = Hazelcast.newHazelcastInstance(new Config());
        final IMap map1 = h1.getMap("default");
        final HazelcastInstance h2 = Hazelcast.newHazelcastInstance(new Config());
        final IMap map2 = h2.getMap("default");
        final HazelcastInstance h3 = Hazelcast.newHazelcastInstance(new Config());
        final IMap map3 = h3.getMap("default");
        CMap cmap1 = getCMap(h1, "default");
        CMap cmap2 = getCMap(h2, "default");
        CMap cmap3 = getCMap(h3, "default");
        Data dKey = toData(1);
        map1.put(1, 1);
        migrateKey(1, h1, h2, 0);
        migrateKey(1, h1, h3, 1);
        cmap1.startCleanup(true);
        assertTrue(h1.getPartitionService().getPartition(1).getOwner().equals(h2.getCluster().getLocalMember()));
        assertTrue(h3.getPartitionService().getPartition(1).getOwner().equals(h2.getCluster().getLocalMember()));
        assertTrue(h2.getPartitionService().getPartition(1).getOwner().localMember());
        assertTrue(map1.tryLock(1));
        final CountDownLatch latchLock = new CountDownLatch(1);
        new Thread(new Runnable() {
            public void run() {
                try {
                    map3.lock(1);
                    assertTrue(latchLock.await(10, TimeUnit.SECONDS));
                } catch (Throwable e) {
                    fail();
                }
            }
        }).start();
        Thread.sleep(2000);
        Record rec1 = cmap1.getRecord(dKey);
        Record rec2 = cmap2.getRecord(dKey);
        Record rec3 = cmap3.getRecord(dKey);
        assertNull(rec1);
        assertNotNull(rec2);
        assertNotNull(rec3);
        DistributedLock lock2 = rec2.getLock();
        DistributedLock lock3 = rec3.getLock();
        assertEquals(1, rec2.getScheduledActionCount());
        assertTrue(rec2.getScheduledActions().iterator().next().isValid());
        Assert.assertNotNull(lock2);
        Assert.assertNotNull(lock3);
        h2.getLifecycleService().shutdown();
        Thread.sleep(3000);
        assertEquals(h3.getCluster().getLocalMember(), h1.getPartitionService().getPartition(1).getOwner());
        assertEquals(h3.getCluster().getLocalMember(), h3.getPartitionService().getPartition(1).getOwner());
        assertEquals(1, map1.put(1, 2));
        rec3 = cmap3.getRecord(dKey);
        assertEquals(1, rec3.getScheduledActionCount());
        assertTrue(rec3.getScheduledActions().iterator().next().isValid());
        map1.unlock(1);
        lock3 = rec3.getLock();
        assertNotNull(lock3);
        assertEquals(lock3.getLockAddress(), ((MemberImpl) h3.getCluster().getLocalMember()).getAddress());
        assertEquals(1, lock3.getLockCount());
        latchLock.countDown();
        assertFalse(map1.tryLock(1));
    }

    @Test
    public void testUnusedLocksOneNode() throws Exception {
        Config config = new Config();
        config.setProperty(GroupProperties.PROP_REMOVE_DELAY_SECONDS, "0");
        HazelcastInstance h1 = Hazelcast.newHazelcastInstance(config);
        h1.getMap("default");
        IMap map1 = h1.getMap("default");
        CMap cmap1 = getCMap(h1, "default");
        for (int i = 0; i < 1000; i++) {
            map1.lock(i);
            map1.unlock(i);
        }
        Thread.sleep(cmap1.removeDelayMillis + 100);
        assertTrue(cmap1.startCleanup(true));
        Thread.sleep(1000);
        assertEquals(0, cmap1.mapRecords.size());
        for (int i = 0; i < 1000; i++) {
            map1.lock(i);
        }
        Thread.sleep(cmap1.removeDelayMillis + 100);
        assertTrue(cmap1.startCleanup(true));
        Thread.sleep(1000);
        assertEquals(1000, cmap1.mapRecords.size());
    }

    @Test
    public void testUnusedLocks() throws Exception {
        Config config = new Config();
        config.setProperty(GroupProperties.PROP_REMOVE_DELAY_SECONDS, "0");
        HazelcastInstance h1 = Hazelcast.newHazelcastInstance(config);
        HazelcastInstance h2 = Hazelcast.newHazelcastInstance(config);
        h1.getMap("default");
        IMap map2 = h2.getMap("default");
        for (int i = 0; i < 1000; i++) {
            map2.lock(i);
            map2.unlock(i);
        }
        CMap cmap1 = getCMap(h1, "default");
        CMap cmap2 = getCMap(h2, "default");
        Thread.sleep(cmap1.removeDelayMillis + 100);
        assertTrue(cmap1.startCleanup(true));
        assertTrue(cmap2.startCleanup(true));
        Thread.sleep(1000);
        assertEquals(0, cmap1.mapRecords.size());
        assertEquals(0, cmap2.mapRecords.size());
    }

    @Test(timeout = 100000)
    public void testTransactionRollbackRespectLockCount() throws InterruptedException {
        HazelcastInstance h1 = Hazelcast.newHazelcastInstance(new Config());
        final IMap map1 = h1.getMap("default");
        Transaction tx = h1.getTransaction();
        map1.lock(1);
        tx.begin();
        map1.put(1, 1);
        tx.rollback();
        final BlockingQueue<Boolean> q = new LinkedBlockingQueue<Boolean>();
        new Thread(new Runnable() {

            public void run() {
                try {
                    q.put(map1.tryLock(1));
                } catch (Throwable e) {
                }
            }
        }).start();
        Boolean locked = q.poll(5, TimeUnit.SECONDS);
        assertNotNull(locked);
        Assert.assertFalse("should not acquire lock", locked);
        map1.unlock(1);
    }

    @Test(timeout = 100000)
    public void testUnlockInsideTransaction() throws InterruptedException {
        HazelcastInstance h1 = Hazelcast.newHazelcastInstance(new Config());
        final IMap map1 = h1.getMap("default");
        Transaction tx = h1.getTransaction();
        tx.begin();
        map1.put(1, 1);
        map1.lock(1);
        map1.unlock(1);
        final BlockingQueue<Boolean> q = new LinkedBlockingQueue<Boolean>();
        new Thread(new Runnable() {

            public void run() {
                try {
                    q.put(map1.tryLock(1));
                } catch (Throwable e) {
                }
            }
        }).start();
        Boolean locked = q.poll(5, TimeUnit.SECONDS);
        assertNotNull(locked);
        Assert.assertFalse("should not acquire lock", locked);
        tx.commit();
    }

    /**
     * Test for Issue 710
     */
    @Test
    public void testEvictedEntryNotNullAfterLockAndGet() throws Exception {
        String mapName = "testLock";
        Config config = new XmlConfigBuilder().build();
        MapConfig mapConfig = new MapConfig();
        mapConfig.setName(mapName);
        mapConfig.setTimeToLiveSeconds(3);
        config.addMapConfig(mapConfig);
        HazelcastInstance h1 = Hazelcast.newHazelcastInstance(config);
        IMap<Object, Object> m1 = h1.getMap(mapName);
        m1.put(1, 1);
        assertEquals(1, m1.get(1));
        Thread.sleep(5000);
        assertEquals(null, m1.get(1));
        m1.lock(1);
        assertEquals(null, m1.get(1));
        m1.put(1, 1);
        assertEquals(1, m1.get(1));
    }
}

