/**
 * 
 */
package com.hazelcast.impl;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import com.hazelcast.nio.Address;

public class NodeBaseVariables {
    final LinkedList<MemberImpl> lsMembers = new LinkedList<MemberImpl>();

    final Map<Address, MemberImpl> mapMembers = new HashMap<Address, MemberImpl>(100);

    final Map<Long, BaseManager.Call> mapCalls = new ConcurrentHashMap<Long, BaseManager.Call>();

    final BaseManager.EventQueue[] eventQueues = new BaseManager.EventQueue[BaseManager.EVENT_QUEUE_COUNT];

    final Map<Long, StreamResponseHandler> mapStreams = new ConcurrentHashMap<Long, StreamResponseHandler>();

    final AtomicLong localIdGen = new AtomicLong(0);

    final Address thisAddress;

    final MemberImpl thisMember;

    NodeBaseVariables(Address thisAddress, MemberImpl thisMember) {
        this.thisAddress = thisAddress;
        this.thisMember = thisMember;
        for (int i = 0; i < BaseManager.EVENT_QUEUE_COUNT; i++) {
            eventQueues[i] = new BaseManager.EventQueue();
        }
    }
}