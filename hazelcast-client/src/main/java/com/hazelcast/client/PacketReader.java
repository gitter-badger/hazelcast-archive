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

package com.hazelcast.client;

import java.io.DataInputStream;
import java.io.IOException;

public class PacketReader extends PacketHandler {

    public Packet readPacket(Connection connection) throws IOException {
        if (!connection.headerRead) {
            connection.getInputStream().readFully(new byte[3]);
            connection.headerRead = true;
        }
        DataInputStream dis = connection.getInputStream();
        Packet response = new Packet();
        response.readFrom(dis);
        return response;
    }
}
