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

package com.hazelcast.client;

import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.fail;
import static org.junit.Assert.*;
import org.junit.Ignore;
import org.junit.Test;

import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.hazelcast.core.*;
import static com.hazelcast.client.TestUtility.getHazelcastClient;

public class HazelcastClientTest {

    @Test
    public void testGetClusterMemberSize() {
        Cluster cluster = getHazelcastClient().getCluster();
        Set<Member> members = cluster.getMembers();
        //Tests are run with only one member in the cluster, this may change later
        assertEquals(1, members.size());
    }

    @Test
    public void testGetClusterTime() {
        Cluster cluster = getHazelcastClient().getCluster();
        long clusterTime = cluster.getClusterTime();
        assertTrue(clusterTime>0);
        System.out.println(clusterTime);
    }

    @Test
    public void testProxySerialization() {
        IMap mapProxy = getHazelcastClient().getMap("proxySerialization");
        ILock mapLock = getHazelcastClient().getLock(mapProxy);
    }

    @Test
    public void testMapGetName() {
        IMap<String, String> map = getHazelcastClient().getMap("testMapGetName");
        assertEquals("testMapGetName", map.getName());
    }

    @Test
    public void testMapValuesSize() {
        Map<String, String> map = getHazelcastClient().getMap("testMapValuesSize");
        map.put("Hello", "World");
        assertEquals(1, map.values().size());
    }

    @Test
    public void testMapPutAndGet() {
        IMap<String, String> map = getHazelcastClient().getMap("testMapPutAndGet");
        String value = map.put("Hello", "World");
        assertEquals("World", map.get("Hello"));
        assertEquals(1, map.size());
        assertNull(value);
        value = map.put("Hello", "World");
        assertEquals("World", map.get("Hello"));
        assertEquals(1, map.size());
        assertEquals("World", value);
        value = map.put("Hello", "New World");
        assertEquals("New World", map.get("Hello"));
        assertEquals(1, map.size());
        assertEquals("World", value);
    }

    @Test
    public void testMapReplaceIfSame() {
        IMap<String, String> map = getHazelcastClient().getMap("testMapReplaceIfSame");
        assertFalse(map.replace("Hello", "Java", "World"));
        String value = map.put("Hello", "World");
        assertEquals("World", map.get("Hello"));
        assertEquals(1, map.size());
        assertNull(value);
        assertFalse(map.replace("Hello", "Java", "NewWorld"));
        assertTrue(map.replace("Hello", "World", "NewWorld"));
        assertEquals("NewWorld", map.get("Hello"));
        assertEquals(1, map.size());
    }

    @Test
    public void testMapContainsKey() {
        IMap<String, String> map = getHazelcastClient().getMap("testMapContainsKey");
        map.put("Hello", "World");
        assertTrue(map.containsKey("Hello"));
    }

    @Test
    public void testMapContainsValue() {
        IMap<String, String> map = getHazelcastClient().getMap("testMapContainsValue");
        map.put("Hello", "World");
        assertTrue(map.containsValue("World"));
    }

    @Test
    public void testMapClear() {
        IMap<String, String> map = getHazelcastClient().getMap("testMapClear");
        String value = map.put("Hello", "World");
        assertEquals(null, value);
        map.clear();
        assertEquals(0, map.size());
        value = map.put("Hello", "World");
        assertEquals(null, value);
        assertEquals("World", map.get("Hello"));
        assertEquals(1, map.size());
        map.remove("Hello");
        assertEquals(0, map.size());
    }

    @Test
    public void testMapRemove() {
        IMap<String, String> map = getHazelcastClient().getMap("testMapRemove");
        map.put("Hello", "World");
        assertEquals(1, map.size());
        assertEquals(1, map.keySet().size());
        map.remove("Hello");
        assertEquals(0, map.size());
        assertEquals(0, map.keySet().size());
        map.put("Hello", "World");
        assertEquals(1, map.size());
        assertEquals(1, map.keySet().size());
    }

    @Test
    public void testMapPutAll() {
        IMap<String, String> map = getHazelcastClient().getMap("testMapPutAll");
        Map<String, String> m = new HashMap<String, String>();
        m.put("Hello", "World");
        m.put("hazel", "cast");
        map.putAll(m);
        assertEquals(2, map.size());
        assertTrue(map.containsKey("Hello"));
        assertTrue(map.containsKey("hazel"));
    }

    @Test
    public void testMapEntrySet() {
        IMap<String, String> map = getHazelcastClient().getMap("testMapEntrySet");
        map.put("Hello", "World");
        Set<IMap.Entry<String, String>> set = map.entrySet();
        for (IMap.Entry<String, String> e : set) {
            assertEquals("Hello", e.getKey());
            assertEquals("World", e.getValue());
        }
    }

    @Test
    public void testMapEntrySetWhenRemoved() {
        IMap<String, String> map = getHazelcastClient().getMap("testMapEntrySetWhenRemoved");
        map.put("Hello", "World");
        Set<IMap.Entry<String, String>> set = map.entrySet();
        map.remove("Hello");
        for (IMap.Entry<String, String> e : set) {
            fail("Iterator should not contain removed entry");
        }
    }

    @Test
    public void mapEntrySetWhenRemoved() {
        IMap<String, String> map = getHazelcastClient().getMap("testMapEntrySetWhenRemoved");
        map.put("Hello", "World");
        map.put("HC","Rocks");
        Set<IMap.Entry<String, String>> set = map.entrySet();
        map.remove("Hello");
        map.remove("HC");
        for (IMap.Entry<String, String> e : set) {
            fail("Iterator should not contain removed entry");
        }
    }

    @Test
    public void testMapEntryListener() {
        IMap<String, String> map = getHazelcastClient().getMap("testMapEntrySet");
        final CountDownLatch latchAdded = new CountDownLatch(1);
        final CountDownLatch latchRemoved = new CountDownLatch(1);
        final CountDownLatch latchUpdated = new CountDownLatch(1);
        map.addEntryListener(new EntryListener() {
            public void entryAdded(EntryEvent event) {
                assertEquals("world", event.getValue());
                assertEquals("hello", event.getKey());
                latchAdded.countDown();
            }

            public void entryRemoved(EntryEvent event) {
                assertEquals("hello", event.getKey());
                assertEquals("new world", event.getValue());
                latchRemoved.countDown();
            }

            public void entryUpdated(EntryEvent event) {
                assertEquals("new world", event.getValue());
                assertEquals("hello", event.getKey());
                latchUpdated.countDown();
            }

            public void entryEvicted(EntryEvent event) {
                entryRemoved(event);
            }
        }, true);
        map.put("hello", "world");
        map.put("hello", "new world");
        map.remove("hello");
        try {
            assertTrue(latchAdded.await(50000, TimeUnit.MILLISECONDS));
            assertTrue(latchUpdated.await(10, TimeUnit.MILLISECONDS));
            assertTrue(latchRemoved.await(10, TimeUnit.MILLISECONDS));
        } catch (InterruptedException e) {
            e.printStackTrace();
            assertFalse(e.getMessage(), true);
        }
    }

    @Test
    public void testMapEvict() {
        IMap<String, String> map = getHazelcastClient().getMap("testMapEviction");
        map.put("currentIteratedKey", "currentIteratedValue");
        assertEquals(true, map.containsKey("currentIteratedKey"));
        map.evict("currentIteratedKey");
        assertEquals(false, map.containsKey("currentIteratedKey"));
    }

    @Test
    public void testListAdd() {
        IList<String> list = getHazelcastClient().getList("testListAdd");
        list.add("Hello World");
        assertEquals(1, list.size());
        assertEquals("Hello World", list.iterator().next());
    }

    @Test
    public void testListContains() {
        IList<String> list = getHazelcastClient().getList("testListContains");
        list.add("Hello World");
        assertTrue(list.contains("Hello World"));
    }

    @Test
    public void testListGet() {
        // Unsupported
        //IList<String> list = getHazelcastClient().getList("testListGet");
        //list.add("Hello World");
        //assertEquals("Hello World", list.get(0));
    }

    @Test
    public void testListIterator() {
        IList<String> list = getHazelcastClient().getList("testListIterator");
        list.add("Hello World");
        assertEquals("Hello World", list.iterator().next());
    }

    @Test
    public void testListListIterator() {
        // Unsupported
        //IList<String> list = getHazelcastClient().getList("testListListIterator");
        //list.add("Hello World");
        //assertEquals("Hello World", list.listIterator().next());
    }

    @Test
    public void testListIndexOf() {
        // Unsupported
        //IList<String> list = getHazelcastClient().getList("testListIndexOf");
        //list.add("Hello World");
        //assertEquals(0, list.indexOf("Hello World"));
    }

    @Test
    public void testListIsEmpty() {
        IList<String> list = getHazelcastClient().getList("testListIsEmpty");
        assertTrue(list.isEmpty());
        list.add("Hello World");
        assertFalse(list.isEmpty());
    }

    @Test
    @Ignore
    public void testListItemListener(){
        final CountDownLatch latch = new CountDownLatch(2);
        IList<String> list = getHazelcastClient().getList("testListListener");
        list.addItemListener(new ItemListener<String>(){
         public void itemAdded(String item) {
            assertEquals("hello", item);
            latch.countDown();
        }

        public void itemRemoved(String item) {
            assertEquals("hello", item);
            latch.countDown();
            }
        }, true);
        list.add("hello");
        list.remove("hello");
        try {
            assertTrue (latch.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException ignored) {
        }
    }

    @Test
    @Ignore
    public void testSetItemListener(){
        final CountDownLatch latch = new CountDownLatch(2);
        ISet<String> set = getHazelcastClient().getSet("testSetListener");
        set.addItemListener(new ItemListener<String>(){
         public void itemAdded(String item) {
            assertEquals("hello", item);
            latch.countDown();
        }

        public void itemRemoved(String item) {
            assertEquals("hello", item);
            latch.countDown();
            }
        }, true);
        set.add("hello");
        set.remove("hello");
        try {
            assertTrue (latch.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException ignored) {
        }
    }

    @Test(expected = UnsupportedOperationException.class)
    public void testQueueItemListener(){
        final CountDownLatch latch = new CountDownLatch(2);
        IQueue<String> queue = getHazelcastClient().getQueue("testQueueListener");
        queue.addItemListener(new ItemListener<String>(){
            public void itemAdded(String item) {
                assertEquals("hello", item);
                latch.countDown();
            }

            public void itemRemoved(String item) {
                assertEquals("hello", item);
                latch.countDown();
            }
        }, true);
        queue.offer("hello");
        assertEquals("hello", queue.poll());
        try {
            assertTrue (latch.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException ignored) {
        }
    }

    @Test
    public void testSetAdd() {
        ISet<String> set = getHazelcastClient().getSet("testSetAdd");
        boolean added = set.add("HelloWorld");
        assertEquals(true, added);
        added = set.add("HelloWorld");
        assertFalse(added);
        assertEquals(1, set.size());
    }

    @Test
    public void testSetIterator() {
        ISet<String> set = getHazelcastClient().getSet("testSetIterator");
        boolean added = set.add("HelloWorld");
        assertTrue(added);
        assertEquals("HelloWorld", set.iterator().next());
    }

    @Test
    public void testSetContains() {
        ISet<String> set = getHazelcastClient().getSet("testSetContains");
        boolean added = set.add("HelloWorld");
        assertTrue(added);
        boolean contains = set.contains("HelloWorld");
        assertTrue(contains);
    }

    @Test
    public void testSetClear() {
        ISet<String> set = getHazelcastClient().getSet("testSetClear");
        boolean added = set.add("HelloWorld");
        assertTrue(added);
        set.clear();
        assertEquals(0, set.size());
    }

    @Test
    public void testSetRemove() {
        ISet<String> set = getHazelcastClient().getSet("testSetRemove");
        boolean added = set.add("HelloWorld");
        assertTrue(added);
        set.remove("HelloWorld");
        assertEquals(0, set.size());
        assertTrue(set.add("HelloWorld"));
        assertFalse(set.add("HelloWorld"));
        assertEquals(1, set.size());
    }

    @Test
    public void testSetGetName() {
        ISet<String> set = getHazelcastClient().getSet("testSetGetName");
        assertEquals("testSetGetName", set.getName());
    }

    @Test
    public void testSetAddAll() {
        ISet<String> set = getHazelcastClient().getSet("testSetAddAll");
        String[] items = new String[]{"one", "two", "three", "four"};
        set.addAll(Arrays.asList(items));
        assertEquals(4, set.size());
        items = new String[]{"four", "five"};
        set.addAll(Arrays.asList(items));
        assertEquals(5, set.size());
    }

    @Test
    public void testTopicGetName() {
        ITopic<String> topic = getHazelcastClient().getTopic("testTopicGetName");
        assertEquals("testTopicGetName", topic.getName());
    }

    @Test
    public void testTopicPublish() {
        ITopic<String> topic = getHazelcastClient().getTopic("testTopicPublish");
        final CountDownLatch latch = new CountDownLatch(1);
        topic.addMessageListener(new MessageListener() {
            public void onMessage(Object msg) {
                assertEquals("Hello World", msg);
                latch.countDown();
            }
        });
        topic.publish("Hello World");
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException ignored) {
        }
    }

    @Test
    public void testQueueAdd() {
        IQueue<String> queue = getHazelcastClient().getQueue("testQueueAdd");
        queue.add("Hello World");
        assertEquals(1, queue.size());
    }

    @Test
    public void testQueueAddAll() {
        IQueue<String> queue = getHazelcastClient().getQueue("testQueueAddAll");
        String[] items = new String[]{"one", "two", "three", "four"};
        queue.addAll(Arrays.asList(items));
        assertEquals(4, queue.size());
        queue.addAll(Arrays.asList(items));
        assertEquals(8, queue.size());
    }

    @Test(expected = UnsupportedOperationException.class)
    public void testQueueContains() {
        IQueue<String> queue = getHazelcastClient().getQueue("testQueueContains");
        String[] items = new String[]{"one", "two", "three", "four"};
        queue.addAll(Arrays.asList(items));
        assertTrue(queue.contains("one"));
        assertTrue(queue.contains("two"));
        assertTrue(queue.contains("three"));
        assertTrue(queue.contains("four"));
    }

    @Test(expected = UnsupportedOperationException.class)
    public void testQueueContainsAll() {
        IQueue<String> queue = getHazelcastClient().getQueue("testQueueContainsAll");
        String[] items = new String[]{"one", "two", "three", "four"};
        List<String> list = Arrays.asList(items);
        queue.addAll(list);
        assertTrue(queue.containsAll(list));
    }

    @Test
    public void testMultiMapPutAndGet() {
        MultiMap<String, String> map = getHazelcastClient().getMultiMap("testMultiMapPutAndGet");
        map.put("Hello", "World");
        Collection<String> values = map.get("Hello");
        assertEquals("World", values.iterator().next());
        map.put("Hello", "Europe");
        map.put("Hello", "America");
        map.put("Hello", "Asia");
        map.put("Hello", "Africa");
        map.put("Hello", "Antartica");
        map.put("Hello", "Australia");
        values = map.get("Hello");
        assertEquals(7, values.size());
    }

    @Test
    public void testMultiMapGetNameAndType() {
        MultiMap<String, String> map = getHazelcastClient().getMultiMap("testMultiMapGetNameAndType");
        assertEquals("testMultiMapGetNameAndType", map.getName());
        Instance.InstanceType type = map.getInstanceType();
        assertEquals(Instance.InstanceType.MULTIMAP, type);
    }

    @Test
    public void testMultiMapClear() {
        MultiMap<String, String> map = getHazelcastClient().getMultiMap("testMultiMapClear");
        map.put("Hello", "World");
        assertEquals(1, map.size());
        map.clear();
        assertEquals(0, map.size());
    }

    @Test
    public void testMultiMapContainsKey() {
        MultiMap<String, String> map = getHazelcastClient().getMultiMap("testMultiMapContainsKey");
        map.put("Hello", "World");
        assertTrue(map.containsKey("Hello"));
    }

    @Test
    public void testMultiMapContainsValue() {
        MultiMap<String, String> map = getHazelcastClient().getMultiMap("testMultiMapContainsValue");
        map.put("Hello", "World");
        assertTrue(map.containsValue("World"));
    }

    @Test
    public void testMultiMapContainsEntry() {
        MultiMap<String, String> map = getHazelcastClient().getMultiMap("testMultiMapContainsEntry");
        map.put("Hello", "World");
        assertTrue(map.containsEntry("Hello", "World"));
    }

    @Test
    public void testMultiMapKeySet() {
        MultiMap<String, String> map = getHazelcastClient().getMultiMap("testMultiMapKeySet");
        map.put("Hello", "World");
        map.put("Hello", "Europe");
        map.put("Hello", "America");
        map.put("Hello", "Asia");
        map.put("Hello", "Africa");
        map.put("Hello", "Antartica");
        map.put("Hello", "Australia");
        Set<String> keys = map.keySet();
        assertEquals(1, keys.size());
    }

    @Test
    public void testMultiMapValues() {
        MultiMap<String, String> map = getHazelcastClient().getMultiMap("testMultiMapValues");
        map.put("Hello", "World");
        map.put("Hello", "Europe");
        map.put("Hello", "America");
        map.put("Hello", "Asia");
        map.put("Hello", "Africa");
        map.put("Hello", "Antartica");
        map.put("Hello", "Australia");
        Collection<String> values = map.values();
        assertEquals(7, values.size());
    }

    @Test
    public void testMultiMapRemove() {
        MultiMap<String, String> map = getHazelcastClient().getMultiMap("testMultiMapRemove");
        map.put("Hello", "World");
        map.put("Hello", "Europe");
        map.put("Hello", "America");
        map.put("Hello", "Asia");
        map.put("Hello", "Africa");
        map.put("Hello", "Antartica");
        map.put("Hello", "Australia");
        assertEquals(7, map.size());
        assertEquals(1, map.keySet().size());
        Collection<String> values = map.remove("Hello");
        assertEquals(7, values.size());
        assertEquals(0, map.size());
        assertEquals(0, map.keySet().size());
        map.put("Hello", "World");
        assertEquals(1, map.size());
        assertEquals(1, map.keySet().size());
    }

    @Test
    public void testMultiMapRemoveEntries() {
        MultiMap<String, String> map = getHazelcastClient().getMultiMap("testMultiMapRemoveEntries");
        map.put("Hello", "World");
        map.put("Hello", "Europe");
        map.put("Hello", "America");
        map.put("Hello", "Asia");
        map.put("Hello", "Africa");
        map.put("Hello", "Antartica");
        map.put("Hello", "Australia");
        boolean removed = map.remove("Hello", "World");
        assertTrue(removed);
        assertEquals(6, map.size());
    }

    @Test
    public void testMultiMapEntrySet() {
        MultiMap<String, String> map = getHazelcastClient().getMultiMap("testMultiMapEntrySet");
        map.put("Hello", "World");
        map.put("Hello", "Europe");
        map.put("Hello", "America");
        map.put("Hello", "Asia");
        map.put("Hello", "Africa");
        map.put("Hello", "Antartica");
        map.put("Hello", "Australia");
        Set<Map.Entry<String, String>> entries = map.entrySet();
        assertEquals(7, entries.size());
        int itCount = 0;
        for (Map.Entry<String, String> entry : entries) {
            assertEquals("Hello", entry.getKey());
            itCount++;
        }
        assertEquals(7, itCount);
    }

    @Test
    public void testMultiMapValueCount() {
        MultiMap<Integer, String> map = getHazelcastClient().getMultiMap("testMultiMapValueCount");
        map.put(1, "World");
        map.put(2, "Africa");
        map.put(1, "America");
        map.put(2, "Antartica");
        map.put(1, "Asia");
        map.put(1, "Europe");
        map.put(2, "Australia");
        assertEquals(4, map.valueCount(1));
        assertEquals(3, map.valueCount(2));
    }

    @Test
    public void testIdGenerator() {
        IdGenerator id = getHazelcastClient().getIdGenerator("testIdGenerator");
        assertEquals(1, id.newId());
        assertEquals(2, id.newId());
        assertEquals("testIdGenerator", id.getName());
    }

    @Test
    public void testLock() {
        ILock lock = getHazelcastClient().getLock("testLock");
        assertTrue(lock.tryLock());
        lock.unlock();
    }

    @Test
    public void testGetMapEntryHits() {
        IMap<String, String> map = getHazelcastClient().getMap("testGetMapEntryHits");
        map.put("Hello", "World");
        MapEntry me = map.getMapEntry("Hello");
        assertEquals(0, me.getHits());
        map.get("Hello");
        map.get("Hello");
        map.get("Hello");
        me = map.getMapEntry("Hello");
        assertEquals(3, me.getHits());
    }

    @Test
    public void testGetMapEntryVersion() {
        IMap<String, String> map = getHazelcastClient().getMap("testGetMapEntryVersion");
        map.put("Hello", "World");
        MapEntry me = map.getMapEntry("Hello");
        assertEquals(0, me.getVersion());
        map.put("Hello", "1");
        map.put("Hello", "2");
        map.put("Hello", "3");
        me = map.getMapEntry("Hello");
        assertEquals(3, me.getVersion());
    }

    @Test
    @Ignore
    public void testMapInstanceDestroy() throws Exception {
        IMap<String, String> map = getHazelcastClient().getMap("testMapDestroy");
        Thread.sleep(1000);
        Collection<Instance> instances = getHazelcastClient().getInstances();
        boolean found = false;
        for (Instance instance : instances) {
            if (instance.getInstanceType() == Instance.InstanceType.MAP) {
                IMap imap = (IMap) instance;
                if (imap.getName().equals("testMapDestroy")) {
                    found = true;
                }
            }
        }
        assertTrue(found);
        map.destroy();
        Thread.sleep(1000);
        found = false;
        instances = getHazelcastClient().getInstances();
        for (Instance instance : instances) {
            if (instance.getInstanceType() == Instance.InstanceType.MAP) {
                IMap imap = (IMap) instance;
                if (imap.getName().equals("testMapDestroy")) {
                    found = true;
                }
            }
        }
        assertFalse(found);
    }
}
