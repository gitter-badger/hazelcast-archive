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

package com.hazelcast.impl.ascii.rest;

import com.hazelcast.nio.IOUtil;

import java.nio.ByteBuffer;

public class HttpGetCommand extends HttpCommand {
    boolean nextLine = false;

    public HttpGetCommand(String uri) {
        super(TextCommandType.HTTP_GET, uri);
    }

    public boolean doRead(ByteBuffer cb) {
        while (cb.hasRemaining()) {
            char c = (char) cb.get();
            if (c == '\n') {
                if (nextLine) {
                    return true;
                }
                nextLine = true;
            } else if (c != '\r') {
                nextLine = false;
            }
        }
        return false;
    }

    /**
     * HTTP/1.0 200 OK
     * Date: Fri, 31 Dec 1999 23:59:59 GMT
     * Content-TextCommandType: text/html
     * Content-Length: 1354
     *
     * @param contentType
     * @param value
     */
    public void setResponse(byte[] contentType, byte[] value) {
        int valueSize = (value == null) ? 0 : value.length;
        byte[] len = String.valueOf(valueSize).getBytes();
        int size = RES_200.length;
        if (contentType != null) {
            size += CONTENT_TYPE.length;
            size += contentType.length;
            size += RETURN.length;
        }
        size += CONTENT_LENGTH.length;
        size += len.length;
        size += RETURN.length;
        size += RETURN.length;
        size += valueSize;
        size += RETURN.length;
        this.response = ByteBuffer.allocate(size);
        response.put(RES_200);
        if (contentType != null) {
            response.put(CONTENT_TYPE);
            response.put(contentType);
            response.put(RETURN);
        }
        response.put(CONTENT_LENGTH);
        response.put(len);
        response.put(RETURN);
        response.put(RETURN);
        if (value != null) {
            response.put(value);
        }
        response.put(RETURN);
        response.flip();
    }

    public boolean writeTo(ByteBuffer bb) {
        IOUtil.copyToHeapBuffer(response, bb);
        return !response.hasRemaining();
    }

    public void sendClusterInfo() {
    }
}