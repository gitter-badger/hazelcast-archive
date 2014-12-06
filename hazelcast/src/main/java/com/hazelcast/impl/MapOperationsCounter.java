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

import com.hazelcast.monitor.LocalMapOperationStats;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public class MapOperationsCounter {
    private AtomicLong puts = new AtomicLong();
    private AtomicLong gets = new AtomicLong();
    private AtomicLong removes = new AtomicLong();
    private AtomicLong others = new AtomicLong();
    private AtomicLong events = new AtomicLong();
    private long startTime = now();
    private long endTime = Long.MAX_VALUE;
    private transient LocalMapOperationStats published = null;
    private List<MapOperationsCounter> listOfSubStats = new ArrayList<MapOperationsCounter>();
    final private Object lock = new Object();
    final private LocalMapOperationStats empty = new MapOperationStatsImpl();

    final private long interval;

    public MapOperationsCounter() {
        this(5000);
    }

    public MapOperationsCounter(long interval) {
        this.interval = interval;
    }

    private MapOperationsCounter getAndReset() {
        long putsNow = puts.getAndSet(0);
        long getsNow = gets.getAndSet(0);
        long removesNow = removes.getAndSet(0);
        long othersNow = others.getAndSet(0);
        long eventsNow = events.getAndSet(0);
        MapOperationsCounter newOne = new MapOperationsCounter();
        newOne.puts.set(putsNow);
        newOne.gets.set(getsNow);
        newOne.removes.set(removesNow);
        newOne.others.set(othersNow);
        newOne.events.set(eventsNow);
        newOne.startTime = this.startTime;
        newOne.endTime = now();
        this.startTime = newOne.endTime;
        return newOne;
    }

    public LocalMapOperationStats getPublishedStats() {
        if (published == null) {
            synchronized (lock) {
                if (published == null) {
                    published = getThis();
                }
            }
        }
        if(published.getPeriodEnd() < now() - interval){
            return empty;
        }
        return published;
    }

    public void incrementPuts() {
        puts.incrementAndGet();
        publishSubResult();
    }

    public void incrementGets() {
        gets.incrementAndGet();
        publishSubResult();
    }

    public void incrementRemoves() {
        removes.incrementAndGet();
        publishSubResult();
    }

    public void incrementOtherOperations() {
        others.incrementAndGet();
        publishSubResult();
    }

    public void incrementReceivedEvents() {
        events.incrementAndGet();
        publishSubResult();
    }

    long now() {
        return System.currentTimeMillis();
    }

    private void publishSubResult() {
        long subInterval = interval / 5;
        if (now() - startTime > subInterval) {
            synchronized (lock) {
                if (now() - startTime >= subInterval) {
                    MapOperationsCounter copy = getAndReset();
                    if (listOfSubStats.size() == 5) {
                        listOfSubStats.remove(0);
                    }
                    listOfSubStats.add(copy);
                    this.published = aggregate(listOfSubStats);
                }
            }
        }
    }

    private LocalMapOperationStats aggregate(List<MapOperationsCounter> list) {
        MapOperationStatsImpl stats = new MapOperationStatsImpl();
        stats.periodStart = list.get(0).startTime;
        for (int i = 0; i < list.size(); i++) {
            MapOperationsCounter sub = list.get(i);
            stats.numberOfGets += sub.gets.get();
            stats.numberOfPuts += sub.puts.get();
            stats.numberOfRemoves += sub.removes.get();
            stats.numberOfOtherOperations += sub.others.get();
            stats.numberOfEvents += sub.events.get();
            stats.periodEnd = sub.endTime;
        }
        return stats;
    }

    private LocalMapOperationStats getThis() {
        MapOperationStatsImpl stats = new MapOperationStatsImpl();
        stats.periodStart = this.startTime;
        stats.numberOfGets = this.gets.get();
        stats.numberOfPuts = this.puts.get();
        stats.numberOfRemoves = this.removes.get();
        stats.numberOfEvents = this.events.get();
        stats.periodEnd = now();
        return stats;
    }
}
