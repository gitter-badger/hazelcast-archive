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

import com.hazelcast.impl.Util;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Arrays;

public class Data implements DataSerializable {

    public byte[] buffer = null;
    public int partitionHash = -1;

    public Data() {
    }

    public Data(byte[] bytes) {
        this.buffer = bytes;
    }

    public int size() {
        return (buffer == null) ? 0 : buffer.length;
    }

    public void readData(DataInput in) throws IOException {
        int size = in.readInt();
        if (size > 0) {
            buffer = new byte[size];
            in.readFully(buffer);
        }
        partitionHash = in.readInt();
    }

    public void writeData(DataOutput out) throws IOException {
        int size = size();
        out.writeInt(size);
        if (size > 0) {
            out.write(buffer);
        }
        out.writeInt(partitionHash);
    }

    @Override
    public int hashCode() {
        return Util.hashCode(buffer);
    }

    public int getPartitionHash() {
        if (partitionHash == -1) {
            if (buffer == null) {
                throw new IllegalStateException("Cannot hash null buffer");
            }
            partitionHash = hashCode();
        }
        return partitionHash;
    }

    public void setPartitionHash(int partitionHash) {
        this.partitionHash = partitionHash;
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof Data))
            return false;
        if (this == obj)
            return true;
        Data data = (Data) obj;
        return size() == data.size() && Arrays.equals(buffer, data.buffer);
    }

    @Override
    public String toString() {
        return "Data{" +
                "partitionHash=" + partitionHash +
                "} size= " + size();
    }
}
