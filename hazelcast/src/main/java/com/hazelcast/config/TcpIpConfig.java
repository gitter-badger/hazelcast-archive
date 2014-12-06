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

package com.hazelcast.config;

import com.hazelcast.nio.Address;

import java.util.ArrayList;
import java.util.List;

public class TcpIpConfig {
    private int connectionTimeoutSeconds = 5;

    private boolean enabled = false;

    private List<String> members = new ArrayList<String>();

    private String requiredMember = null;

    private final List<Address> addresses = new ArrayList<Address>();

    public TcpIpConfig addMember(final String member) {
        members.add(member);
        return this;
    }

    public TcpIpConfig clear() {
        members.clear();
        addresses.clear();
        return this;
    }

    public TcpIpConfig addAddress(final Address address) {
        addresses.add(address);
        return this;
    }

    public List<Address> getAddresses() {
        return addresses;
    }

    /**
     * @return the connectionTimeoutSeconds
     */
    public int getConnectionTimeoutSeconds() {
        return connectionTimeoutSeconds;
    }

    /**
     * @param connectionTimeoutSeconds the connectionTimeoutSeconds to set
     */
    public TcpIpConfig setConnectionTimeoutSeconds(final int connectionTimeoutSeconds) {
        this.connectionTimeoutSeconds = connectionTimeoutSeconds;
        return this;
    }

    /**
     * @return the enabled
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * @param enabled the enabled to set
     */
    public TcpIpConfig setEnabled(final boolean enabled) {
        this.enabled = enabled;
        return this;
    }

    /**
     * @return the lsMembers
     */
    public List<String> getMembers() {
        return members;
    }

    /**
     * @param members the members to set
     */
    public TcpIpConfig setMembers(final List<String> members) {
        this.members = members;
        return this;
    }

    /**
     * @return the requiredMember
     */
    public String getRequiredMember() {
        return requiredMember;
    }

    /**
     * @param requiredMember the requiredMember to set
     */
    public TcpIpConfig setRequiredMember(final String requiredMember) {
        this.requiredMember = requiredMember;
        return this;
    }
}
