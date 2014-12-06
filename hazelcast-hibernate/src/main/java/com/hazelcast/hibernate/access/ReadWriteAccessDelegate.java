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

package com.hazelcast.hibernate.access;

import com.hazelcast.core.IMap;
import com.hazelcast.hibernate.region.HazelcastRegion;
import org.hibernate.cache.CacheException;
import org.hibernate.cache.access.SoftLock;

/**
 * Makes <b>READ COMMITTED</b> consistency guarantees even in a clustered environment.
 *
 * @author Leo Kim (lkim@limewire.com)
 */
public class ReadWriteAccessDelegate<T extends HazelcastRegion> extends AbstractAccessDelegate<T> {

    public ReadWriteAccessDelegate(final T hazelcastRegion) {
        super(hazelcastRegion);
    }

    public boolean afterInsert(final Object key, final Object value, final Object version) throws CacheException {
        return false;
    }

    public boolean afterUpdate(final Object key, final Object value, final Object currentVersion,
                               final Object previousVersion, final SoftLock lock) throws CacheException {
        return false;
    }

    public void evict(final Object key) throws CacheException {
        final IMap cache = getCache();
        cache.lock(key);
        try {
            cache.remove(key);
        } finally {
            cache.unlock(key);
        }
    }

    public void evictAll() throws CacheException {
        getCache().clear();
    }

    public Object get(final Object key, final long txTimestamp) throws CacheException {
        return getCache().get(key);
    }

    public boolean insert(final Object key, final Object value, final Object version) throws CacheException {
        final IMap cache = getCache();
        cache.lock(key);
        try {
            cache.put(key, value);
        } finally {
            cache.unlock(key);
        }
        return true;
    }

    public SoftLock lockItem(final Object key, final Object version) throws CacheException {
        return null;
    }

    public SoftLock lockRegion() throws CacheException {
        return null;
    }

    public boolean putFromLoad(final Object key, final Object value, final long txTimestamp, final Object version)
            throws CacheException {
        final IMap cache = getCache();
        cache.lock(key);
        try {
            cache.put(key, value);
        } finally {
            cache.unlock(key);
        }
        return true;
    }

    public boolean putFromLoad(final Object key, final Object value, final long txTimestamp, final Object version,
                               final boolean minimalPutOverride) throws CacheException {
        final IMap cache = getCache();
        cache.lock(key);
        try {
            cache.put(key, value);
        } finally {
            cache.unlock(key);
        }
        return true;
    }

    public void remove(final Object key) throws CacheException {
        final IMap cache = getCache();
        cache.lock(key);
        try {
            cache.remove(key);
        } finally {
            cache.unlock(key);
        }
    }

    public void removeAll() throws CacheException {
        getCache().clear();
    }

    public void unlockItem(final Object key, final SoftLock lock) throws CacheException {
    }

    public void unlockRegion(final SoftLock lock) throws CacheException {
    }

    public boolean update(final Object key, final Object value, final Object currentVersion,
                          final Object previousVersion) throws CacheException {
        final IMap cache = getCache();
        cache.lock(key);
        try {
            cache.put(key, value);
        } finally {
            cache.unlock(key);
        }
        return true;
    }
}
