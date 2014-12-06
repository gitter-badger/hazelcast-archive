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

import com.hazelcast.impl.ThreadContext;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.ByteBuffer;

public final class IOUtil {

    public static int copyToHeapBuffer(ByteBuffer src, ByteBuffer dest) {
        if (src == null) return 0;
        int n = Math.min(src.remaining(), dest.remaining());
        if (n > 0) {
            if (n < 16) {
                for (int i = 0; i < n; i++) {
                    dest.put(src.get());
                }
            } else {
                int srcPosition = src.position();
                int destPosition = dest.position();
                System.arraycopy(src.array(), srcPosition, dest.array(), destPosition, n);
                src.position(srcPosition + n);
                dest.position(destPosition + n);
            }
        }
        return n;
    }

    public static int copyToDirectBuffer(ByteBuffer src, ByteBuffer dest) {
        int n = Math.min(src.remaining(), dest.remaining());
        if (n > 0) {
            dest.put(src.array(), src.position(), n);
            src.position(src.position() + n);
        }
        return n;
    }

    public static void putBoolean(ByteBuffer bb, boolean value) {
        bb.put((byte) (value ? 1 : 0));
    }

    public static boolean getBoolean(ByteBuffer bb) {
        return bb.get() == 1;
    }

    public static Data toData(Object obj) {
        return ThreadContext.get().toData(obj);
    }

    public static long getLong(Data longData) {
        byte[] b = longData.buffer;
        ByteBuffer current = ByteBuffer.wrap(b);
        current.get(); // type
        return current.getLong();
    }

    public static Data addDelta(Data longData, long delta) {
        long longValue = (Long) toObject(longData);
        return toData(longValue + delta);
    }

    public static Object toObject(Data data) {
        return ThreadContext.get().toObject(data);
    }

    public static Object toObject(DataHolder dataHolder) {
        return toObject(dataHolder.toData());
    }

    public static Object serializeToObject(byte[] bytes) throws Exception {
        if (bytes == null) return null;
        ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes));
        Object obj = in.readObject();
        in.close();
        return obj;
    }

    public static byte[] serializeToBytes(Object object) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ObjectOutputStream out = new ObjectOutputStream(bos);
        out.writeObject(object);
        out.close();
        return bos.toByteArray();
    }
}
