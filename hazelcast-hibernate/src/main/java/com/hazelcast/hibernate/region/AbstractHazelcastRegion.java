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

package com.hazelcast.hibernate.region;

import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.IMap;
import com.hazelcast.core.MapEntry;
import org.hibernate.cache.CacheException;
import org.hibernate.cache.Region;
import org.hibernate.cache.Timestamper;

import java.util.Map;

/**
 * @author Leo Kim (lkim@limewire.com)
 */
abstract class AbstractHazelcastRegion implements HazelcastRegion {

    private final IMap cache;
    private final String regionName;

    protected AbstractHazelcastRegion(final String regionName) {
        cache = Hazelcast.getMap(regionName);
        this.regionName = regionName;
    }

    public final IMap getCache() {
        return cache;
    }

    /**
     * Calls <code>{@link IMap#destroy()}</code> on the given <code>{@link Region}</code>.
     */
    public void destroy() throws CacheException {
        getCache().destroy();
    }

    /**
     * @return The size of the internal <code>{@link IMap}</code>.
     */
    public long getElementCountInMemory() {
        return getCache().size();
    }

    /**
     * Hazelcast does not support pushing elements to disk.
     *
     * @return -1 (according to <code>{@link Region}</code>, this value means "unsupported"
     */
    public long getElementCountOnDisk() {
        return -1;
    }

    /**
     * @return The name of the region.
     */
    public String getName() {
        return regionName;
    }

    /**
     * @return a rough estimate of number of bytes used by this region.
     */
    public long getSizeInMemory() {
        long size = 0;
        for (final Object key : getCache().keySet()) {
            final MapEntry entry = getCache().getMapEntry(key);
            if (entry != null) {
                size += entry.getCost();
            }
        }
        return size;
    }

    /**
     * TODO: I am not clear as to what this is a timeout for.
     *
     * @return 60000 (milliseconds)
     */
    public int getTimeout() {
        return Timestamper.ONE_MS * 60000;
    }

    /**
     * @return <code>{@link System#currentTimeMillis}</code>/100.
     */
    public long nextTimestamp() {
        return System.currentTimeMillis() / 100;
    }

    /**
     * Appears to be used only by <code>org.hibernate.stat.SecondLevelCacheStatistics</code>.
     *
     * @return the internal <code>IMap</code> used for this region.
     */
    public Map toMap() {
        return getCache();
    }
}
