/* 
 * Copyright (c) 2007-2008, Hazel Ltd. All Rights Reserved.
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

import com.hazelcast.core.Member;
import static com.hazelcast.impl.Constants.ClusterOperations.*;
import static com.hazelcast.impl.Constants.NodeTypes.NODE_MEMBER;
import com.hazelcast.nio.*;
import com.hazelcast.nio.Packet;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.logging.Level;

public class ClusterManager extends BaseManager implements ConnectionListener {

    private static final ClusterManager instance = new ClusterManager();

    Set<ScheduledAction> setScheduledActions = new HashSet<ScheduledAction>(1000);

    public static ClusterManager get() {
        return instance;
    }

    private final Set<MemberInfo> setJoins = new LinkedHashSet<MemberInfo>(100);

    private boolean joinInProgress = false;

    private long timeToStartJoin = 0;

    private final List<MemberImpl> lsMembersBefore = new ArrayList<MemberImpl>();

    private final long waitTimeBeforeJoin = 5000;

    private ClusterManager() {
        ClusterService.get().registerPeriodicRunnable(new Runnable() {
            public void run() {
                heartBeater();
            }
        });
        ClusterService.get().registerPeriodicRunnable(new Runnable() {
            public void run() {
                checkScheduledActions();
            }
        });
        ConnectionManager.get().addConnectionListener(this);
        ClusterService.get().registerPacketProcessor(OP_RESPONSE, new PacketProcessor() {
            public void process(Packet packet) {
                handleResponse(packet);
            }
        });
        ClusterService.get().registerPacketProcessor(OP_HEARTBEAT, new PacketProcessor() {
            public void process(Packet packet) {
                packet.returnToContainer();
            }
        });
        ClusterService.get().registerPacketProcessor(OP_REMOTELY_PROCESS_AND_RESPOND,
                new PacketProcessor() {
                    public void process(Packet packet) {
                        Data data = BufferUtil.doTake(packet.value);
                        RemotelyProcessable rp = (RemotelyProcessable) ThreadContext.get()
                                .toObject(data);
                        rp.setConnection(packet.conn);
                        rp.process();
                        sendResponse(packet);
                    }
                });
        ClusterService.get().registerPacketProcessor(OP_REMOTELY_PROCESS,
                new PacketProcessor() {
                    public void process(Packet packet) {
                        Data data = BufferUtil.doTake(packet.value);
                        RemotelyProcessable rp = (RemotelyProcessable) ThreadContext.get()
                                .toObject(data);
                        rp.setConnection(packet.conn);
                        rp.process();
                        packet.returnToContainer();
                    }
                });

        ClusterService.get().registerPacketProcessor(OP_REMOTELY_BOOLEAN_CALLABLE,
                new PacketProcessor() {
                    public void process(Packet packet) {
                        Boolean result = null;
                        try {
                            Data data = BufferUtil.doTake(packet.value);
                            AbstractRemotelyCallable<Boolean> callable = (AbstractRemotelyCallable<Boolean>) ThreadContext
                                    .get().toObject(data);
                            callable.setConnection(packet.conn);
                            result = callable.call();
                        } catch (Exception e) {
                            e.printStackTrace(System.out);
                            result = Boolean.FALSE;
                        }
                        if (result == Boolean.TRUE) {
                            sendResponse(packet);
                        } else {
                            sendResponseFailure(packet);
                        }
                    }
                });

        ClusterService.get().registerPacketProcessor(OP_REMOTELY_OBJECT_CALLABLE,
                new PacketProcessor() {
                    public void process(Packet packet) {
                        Object result = null;
                        try {
                            Data data = BufferUtil.doTake(packet.value);
                            AbstractRemotelyCallable callable = (AbstractRemotelyCallable) ThreadContext
                                    .get().toObject(data);
                            callable.setConnection(packet.conn);
                            result = callable.call();
                        } catch (Exception e) {
                            e.printStackTrace(System.out);
                            result = null;
                        }
                        if (result != null) {
                            Data value = null;
                            if (result instanceof Data) {
                                value = (Data) result;
                            } else {
                                value = ThreadContext.get().toData(result);
                            }
                            BufferUtil.doSet(value, packet.value);
                        }

                        sendResponse(packet);
                    }
                });
    }

    public final void heartBeater() {
        if (!Node.get().joined())
            return;
        long now = System.currentTimeMillis();
        if (isMaster()) {
            List<Address> lsDeadAddresses = null;
            for (MemberImpl memberImpl : lsMembers) {
                final Address address = memberImpl.getAddress();
                if (!thisAddress.equals(address)) {
                    try {
                        Connection conn = ConnectionManager.get().getConnection(address);
                        if (conn != null && conn.live()) {
                            if ((now - memberImpl.getLastRead()) >= 10000) {
                                conn = null;
                                if (lsDeadAddresses == null) {
                                    lsDeadAddresses = new ArrayList<Address>();
                                }
                                lsDeadAddresses.add(address);
                            }
                        }
                        if (conn != null && conn.live()) {
                            if ((now - memberImpl.getLastWrite()) > 500) {
                                Packet packet = obtainPacket("heartbeat", null, null,
                                        OP_HEARTBEAT, 0);
                                sendOrReleasePacket(packet, conn);
                            }
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
            if (lsDeadAddresses != null) {
                for (Address address : lsDeadAddresses) {
                    logger.log(Level.FINEST, "NO HEARTBEAT should remove " + address);
                    doRemoveAddress(address);
                    sendRemoveMemberToOthers(address);
                }
            }
        } else {
            // send heartbeat to master
            if (getMasterAddress() != null) {
                MemberImpl masterMember = getMember(getMasterAddress());
                boolean removed = false;
                if (masterMember != null) {
                    if ((now - masterMember.getLastRead()) >= 10000) {
                        doRemoveAddress(getMasterAddress());
                        removed = true;
                    }
                }
                if (!removed) {
                    Packet packet = obtainPacket("heartbeat", null, null, OP_HEARTBEAT,
                            0);
                    Connection connMaster = ConnectionManager.get().getOrConnect(getMasterAddress());
                    sendOrReleasePacket(packet, connMaster);
                }
            }
            for (MemberImpl member : lsMembers) {
                if (!member.localMember()) {
                    Address address = member.getAddress();
                    if (shouldConnectTo(address)) {
                        Connection conn = ConnectionManager.get().getOrConnect(address);
                        if (conn != null) {
                            Packet packet = obtainPacket("heartbeat", null, null,
                                    OP_HEARTBEAT, 0);
                            sendOrReleasePacket(packet, conn);
                        }
                    } else {
                        Connection conn = ConnectionManager.get().getConnection(address);
                        if (conn != null && conn.live()) {
                            if ((now - member.getLastWrite()) > 1000) {
                                Packet packet = obtainPacket("heartbeat", null, null,
                                        OP_HEARTBEAT, 0);
                                sendOrReleasePacket(packet, conn);
                            }
                        }
                    }
                }
            }
        }
    }

    public boolean shouldConnectTo(Address address) {
        if (!Node.get().joined())
            return true;
        return (lsMembers.indexOf(getMember(thisAddress)) > lsMembers.indexOf(getMember(address)));
    }

    private void sendRemoveMemberToOthers(final Address deadAddress) {
        for (MemberImpl member : lsMembers) {
            Address address = member.getAddress();
            if (!thisAddress.equals(address)) {
                if (!address.equals(deadAddress)) {
                    sendProcessableTo(new MemberRemover(deadAddress), address);
                }
            }
        }
    }

    public void handleMaster(Master master) {
        if (!Node.get().joined()) {
            Node.get().setMasterAddress(master.address);
            Connection connMaster = ConnectionManager.get().getOrConnect(master.address);
            if (connMaster != null) {
                sendJoinRequest(master.address);
            }
        }
    }

    public void handleAddRemoveConnection(AddRemoveConnection addRemoveConnection) {
        boolean add = addRemoveConnection.add;
        Address addressChanged = addRemoveConnection.address;
        if (add) { // Just connect to the new address if not connected already.
            if (!addressChanged.equals(thisAddress)) {
                ConnectionManager.get().getOrConnect(addressChanged);
            }
        } else { // Remove dead member
            addressChanged.setDead();
            final Address deadAddress = addressChanged;
            doRemoveAddress(deadAddress);
        } // end of REMOVE CONNECTION
    }

    void doRemoveAddress(Address deadAddress) {
        if (DEBUG) {
            log("Removing Address " + deadAddress);
        }
        if (deadAddress.equals(thisAddress))
            return;
        if (deadAddress.equals(getMasterAddress())) {
            if (Node.get().joined()) {
                MemberImpl newMaster = getNextMemberAfter(deadAddress, false, 1);
                if (newMaster != null)
                    Node.get().setMasterAddress(newMaster.getAddress());
                else
                    Node.get().setMasterAddress(null);
            } else {
                Node.get().setMasterAddress(null);
            }
            if (DEBUG) {
                log("Now Master " + Node.get().getMasterAddress());
            }
        }

        if (isMaster()) {
            setJoins.remove(new MemberInfo(deadAddress, 0));
        }

        lsMembersBefore.clear();
        for (MemberImpl member : lsMembers) {
            lsMembersBefore.add(member);
        }
        Connection conn = ConnectionManager.get().getConnection(deadAddress);
        if (conn != null) {
            ConnectionManager.get().remove(conn);
        }
        MemberImpl member = getMember(deadAddress);
        if (member != null) {
            removeMember(deadAddress);
        }
        BlockingQueueManager.get().syncForDead(deadAddress);
        ConcurrentMapManager.get().syncForDead(deadAddress);
        ListenerManager.get().syncForDead(deadAddress);
        TopicManager.get().syncForDead(deadAddress);

        Node.get().getClusterImpl().setMembers(lsMembers);

        // toArray will avoid CME as onDisconnect does remove the calls
        Object[] calls = mapCalls.values().toArray();
        for (Object call : calls) {
            ((Call) call).onDisconnect(deadAddress);
        }
        System.out.println(this);
    }

    public List<MemberImpl> getMembersBeforeSync() {
        return lsMembersBefore;
    }

    private void handleJoinRequest(JoinRequest joinRequest) {
        logger.log(Level.FINEST, joinInProgress + " Handling " + joinRequest);
        if (getMember(joinRequest.address) != null)
            return;
        if (DEBUG) {
            // log("Handling  " + joinRequest);
        }
        Connection conn = joinRequest.getConnection();
        if (!Config.get().join.multicastConfig.enabled) {
            if (Node.get().getMasterAddress() != null && !isMaster()) {
                sendProcessableTo(new Master(Node.get().getMasterAddress()), conn);
            }
        }
        if (isMaster()) {
            Address newAddress = joinRequest.address;
            if (!joinInProgress) {
                MemberInfo newMemberInfo = new MemberInfo(newAddress, joinRequest.nodeType);
                if (setJoins.add(newMemberInfo)) {
                    sendProcessableTo(new Master(Node.get().getMasterAddress()), conn);
                    // sendAddRemoveToAllConns(newAddress);
                    timeToStartJoin = System.currentTimeMillis() + waitTimeBeforeJoin;
                } else {
                    if (System.currentTimeMillis() > timeToStartJoin) {
                        startJoin();
                    }
                }
            }
        }
    }

    @Override
    public String toString() {
        StringBuffer sb = new StringBuffer("\n\nMembers [");
        sb.append(lsMembers.size());
        sb.append("] {");
        for (MemberImpl member : lsMembers) {
            sb.append("\n\t" + member);
        }
        sb.append("\n}\n");
        return sb.toString();
    }

    public int getMemberDistance(Address target) {
        int indexThis = -1;
        int indexTarget = -1;
        int index = 0;
        for (MemberImpl member : lsMembers) {
            if (member.getAddress().equals(thisAddress)) {
                indexThis = index;
            } else if (member.getAddress().equals(target)) {
                indexTarget = index;
            }
            if (indexThis > -1 && indexTarget > -1) {
                int distance = indexThis - indexTarget;
                if (distance < 0) {
                    distance += lsMembers.size();
                }
                return distance;
            }
            index++;
        }
        return 0;
    }

    public void sendProcessableTo(RemotelyProcessable rp, Connection conn) {
        Data value = ThreadContext.get().toData(rp);
        Packet packet = obtainPacket();
        try {
            packet.set("remotelyProcess", OP_REMOTELY_PROCESS, null, value);
            boolean sent = send(packet, conn);
            if (!sent) {
                packet.returnToContainer();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    void joinReset() {
        joinInProgress = false;
        setJoins.clear();
        timeToStartJoin = System.currentTimeMillis() + waitTimeBeforeJoin + 1000;
    }

    public class AsyncRemotelyObjectCallable extends TargetAwareOp {
        AbstractRemotelyCallable arp = null;

        public void executeProcess(Address address, AbstractRemotelyCallable arp) {
            this.arp = arp;
            super.target = address;
            doOp(OP_REMOTELY_OBJECT_CALLABLE, "call", null, arp, 0, -1, -1);
        }

        @Override
        public void doLocalOp() {
            Object result;
            try {
                result = arp.call();
                setResult(result);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        @Override
        void setTarget() {
        }
    }

    public class AsyncRemotelyBooleanCallable extends BooleanOp {
        AbstractRemotelyCallable<Boolean> arp = null;

        public void executeProcess(Address address, AbstractRemotelyCallable<Boolean> arp) {
            this.arp = arp;
            super.target = address;
            doOp(OP_REMOTELY_BOOLEAN_CALLABLE, "call", null, arp, 0, -1, -1);
        }

        @Override
        public void doLocalOp() {
            Boolean result;
            try {
                result = arp.call();
                setResult(result);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        @Override
        void setTarget() {
        }
    }

    public class ResponsiveRemoteProcess extends TargetAwareOp {
        AbstractRemotelyProcessable arp = null;

        public boolean executeProcess(Address address, AbstractRemotelyProcessable arp) {
            this.arp = arp;
            super.target = address;
            return booleanCall(OP_REMOTELY_PROCESS_AND_RESPOND, "exe", null, arp, 0, -1, -1);
        }

        @Override
        public void doLocalOp() {
            arp.process();
            setResult(Boolean.TRUE);
        }

        @Override
        void setTarget() {
        }
    }

    void finalizeJoin() {
        List<AsyncRemotelyBooleanCallable> calls = new ArrayList<AsyncRemotelyBooleanCallable>();
        for (final MemberImpl member : lsMembers) {
            if (!member.localMember()) {
                AsyncRemotelyBooleanCallable rrp = new AsyncRemotelyBooleanCallable();
                rrp.executeProcess(member.getAddress(), new FinalizeJoin());
                calls.add(rrp);
            }
        }
        for (AsyncRemotelyBooleanCallable call : calls) {
            call.getResultAsBoolean();
        }
    }

    void startJoin() {
        joinInProgress = true;
        final MembersUpdateCall membersUpdate = new MembersUpdateCall(lsMembers);
        if (setJoins != null && setJoins.size() > 0) {
            for (MemberInfo memberJoined : setJoins) {
                membersUpdate.addMemberInfo(memberJoined);
            }
        }

        executeLocally(new Runnable() {
            public void run() {
                List<MemberInfo> lsMemberInfos = membersUpdate.lsMemberInfos;
                List<AsyncRemotelyBooleanCallable> calls = new ArrayList<AsyncRemotelyBooleanCallable>();
                for (final MemberInfo memberInfo : lsMemberInfos) {
                    AsyncRemotelyBooleanCallable rrp = new AsyncRemotelyBooleanCallable();
                    rrp.executeProcess(memberInfo.address, membersUpdate);
                    calls.add(rrp);
                }
                for (AsyncRemotelyBooleanCallable call : calls) {
                    call.getResultAsBoolean();
                }
                calls.clear();
                for (final MemberInfo memberInfo : lsMemberInfos) {
                    AsyncRemotelyBooleanCallable call = new AsyncRemotelyBooleanCallable();
                    call.executeProcess(memberInfo.address, new SyncProcess());
                    calls.add(call);
                }
                for (AsyncRemotelyBooleanCallable call : calls) {
                    call.getResultAsBoolean();
                }
                calls.clear();
                AbstractRemotelyCallable<Boolean> connCheckcallable = new ConnectionCheckCall();
                for (final MemberInfo memberInfo : lsMemberInfos) {
                    AsyncRemotelyBooleanCallable call = new AsyncRemotelyBooleanCallable();
                    call.executeProcess(memberInfo.address, connCheckcallable);
                    calls.add(call);
                }
                for (AsyncRemotelyBooleanCallable call : calls) {
                    call.getResultAsBoolean();
                }
            }
        });
    }

    public static class SyncProcess extends AbstractRemotelyCallable<Boolean> implements
            RemotelyProcessable {

        Connection conn;

        public Connection getConnection() {
            return conn;
        }

        public void setConnection(Connection conn) {
            this.conn = conn;
        }

        public void readData(DataInput in) throws IOException {
        }

        public void writeData(DataOutput out) throws IOException {
        }

        public Boolean call() {
            process();
            return Boolean.TRUE;
        }

        public void process() {
            ConcurrentMapManager.get().syncForAdd();
            BlockingQueueManager.get().syncForAdd();
            ListenerManager.get().syncForAdd();
            TopicManager.get().syncForAdd();
            ClusterManager.get().joinReset();
        }
    }

    public static abstract class AbstractRemotelyCallable<T> implements DataSerializable,
            Callable<T> {
        Connection conn;

        public Connection getConnection() {
            return conn;
        }

        public void setConnection(Connection conn) {
            this.conn = conn;
        }

        public void readData(DataInput in) throws IOException {
        }

        public void writeData(DataOutput out) throws IOException {
        }
    }

    public static abstract class AbstractRemotelyProcessable implements RemotelyProcessable {
        Connection conn;

        public Connection getConnection() {
            return conn;
        }

        public void setConnection(Connection conn) {
            this.conn = conn;
        }

        public void readData(DataInput in) throws IOException {
        }

        public void writeData(DataOutput out) throws IOException {
        }
    }

    public static class MultiRemotelyProcessable extends AbstractRemotelyProcessable {
        List<RemotelyProcessable> lsProcessables = new LinkedList<RemotelyProcessable>();

        public void add(RemotelyProcessable rp) {
            if (rp != null) {
                lsProcessables.add(rp);
            }
        }

        @Override
        public void readData(DataInput in) throws IOException {
            int size = in.readInt();
            for (int i = 0; i < size; i++) {
                String className = in.readUTF();
                try {
                    RemotelyProcessable rp = (RemotelyProcessable) Class.forName(className)
                            .newInstance();
                    rp.readData(in);
                    lsProcessables.add(rp);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }

        @Override
        public void writeData(DataOutput out) throws IOException {
            out.writeInt(lsProcessables.size());
            for (RemotelyProcessable remotelyProcessable : lsProcessables) {
                out.writeUTF(remotelyProcessable.getClass().getName());
                remotelyProcessable.writeData(out);
            }
        }

        public void process() {
            for (RemotelyProcessable remotelyProcessable : lsProcessables) {
                remotelyProcessable.process();
            }
        }
    }

    interface RemotelyProcessable extends DataSerializable, Processable {
        void setConnection(Connection conn);
    }

    void updateMembers(List<MemberInfo> lsMemberInfos) {
        if (DEBUG) {
            log("MEMBERS UPDATE!!");
        }
        lsMembersBefore.clear();
        Map<Address, MemberImpl> mapOldMembers = new HashMap<Address, MemberImpl>();
        for (MemberImpl member : lsMembers) {
            lsMembersBefore.add(member);
            mapOldMembers.put(member.getAddress(), member);
        }
        lsMembers.clear();
        for (MemberInfo memberInfo : lsMemberInfos) {
            MemberImpl member = mapOldMembers.get(memberInfo.address);
            if (member == null) {
                member = addMember(memberInfo.address, memberInfo.nodeType);
            } else {
                addMember(member);
            }
            member.didRead();
        }
        mapOldMembers.clear();
        if (!lsMembers.contains(thisMember)) {
            throw new RuntimeException("Member list doesn't contain local member!");
        }
        mapMembers.clear();
        for (MemberImpl member : lsMembers) {
            mapMembers.put(member.getAddress(), member);
        }
        heartBeater();
        Node.get().getClusterImpl().setMembers(lsMembers);
        Node.get().unlock();
        logger.log(Level.INFO, this.toString());
    }

    public static class MemberInfo implements DataSerializable {
        Address address = null;
        int nodeType = NODE_MEMBER;

        public MemberInfo() {
        }

        public MemberInfo(Address address, int nodeType) {
            super();
            this.address = address;
            this.nodeType = nodeType;
        }

        public void readData(DataInput in) throws IOException {
            address = new Address();
            address.readData(in);
            nodeType = in.readInt();
        }

        public void writeData(DataOutput out) throws IOException {
            address.writeData(out);
            out.writeInt(nodeType);
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + ((address == null) ? 0 : address.hashCode());
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            MemberInfo other = (MemberInfo) obj;
            if (address == null) {
                if (other.address != null)
                    return false;
            } else if (!address.equals(other.address))
                return false;
            return true;
        }
    }

    public static class FinalizeJoin extends AbstractRemotelyCallable<Boolean> {
        public Boolean call() throws Exception {
            ListenerManager.get().syncForAdd(getConnection().getEndPoint());
            return Boolean.TRUE;
        }
    }

    public static class ConnectionCheckCall extends AbstractRemotelyCallable<Boolean> {
        public Boolean call() throws Exception {
            for (MemberImpl member : lsMembers) {
                if (!member.localMember()) {
                    if (ConnectionManager.get().getConnection(member.getAddress()) == null) {
                        return Boolean.FALSE;
                    }
                }
            }
            return Boolean.TRUE;
        }
    }

    public static class MembersUpdateCall extends AbstractRemotelyCallable<Boolean> {

        public List<MemberInfo> lsMemberInfos = null;

        public MembersUpdateCall() {
        }

        public MembersUpdateCall(List<MemberImpl> lsMembers) {
            int size = lsMembers.size();
            lsMemberInfos = new ArrayList<MemberInfo>(size);
            for (int i = 0; i < size; i++) {
                MemberImpl member = lsMembers.get(i);
                lsMemberInfos.add(new MemberInfo(member.getAddress(), member.getNodeType()));
            }
        }

        public Boolean call() {
            ClusterManager.get().updateMembers(lsMemberInfos);
            return Boolean.TRUE;
        }

        public void addMemberInfo(MemberInfo address) {
            if (!lsMemberInfos.contains(address)) {
                lsMemberInfos.add(address);
            }
        }

        @Override
        public void readData(DataInput in) throws IOException {
            int size = in.readInt();
            lsMemberInfos = new ArrayList<MemberInfo>(size);
            for (int i = 0; i < size; i++) {
                MemberInfo memberInfo = new MemberInfo();
                memberInfo.readData(in);
                lsMemberInfos.add(memberInfo);
            }
        }

        @Override
        public void writeData(DataOutput out) throws IOException {
            int size = lsMemberInfos.size();
            out.writeInt(size);
            for (int i = 0; i < size; i++) {
                MemberInfo memberInfo = lsMemberInfos.get(i);
                memberInfo.writeData(out);
            }
        }

        @Override
        public String toString() {
            StringBuffer sb = new StringBuffer("MembersUpdateCall {");
            for (MemberInfo address : lsMemberInfos) {
                sb.append("\n" + address);
            }
            sb.append("\n}");
            return sb.toString();
        }

    }

    public void sendJoinRequest(Address toAddress) {
        if (toAddress == null) {
            toAddress = Node.get().getMasterAddress();
        }
        sendProcessableTo(new JoinRequest(thisAddress, Config.get().groupName,
                Config.get().groupPassword, Node.get().getLocalNodeType()), toAddress);
    }


    public static class JoinRequest extends AbstractRemotelyProcessable {

        int nodeType = NODE_MEMBER;
        Address address;
        String groupName;
        String groupPassword;

        public JoinRequest() {
        }

        public JoinRequest(Address address, String groupName, String groupPassword, int type) {
            super();
            this.address = address;
            this.groupName = groupName;
            this.groupPassword = groupPassword;
            this.nodeType = type;
        }

        @Override
        public void readData(DataInput in) throws IOException {
            address = new Address();
            address.readData(in);
            nodeType = in.readInt();
            groupName = in.readUTF();
            groupPassword = in.readUTF();
        }

        @Override
        public void writeData(DataOutput out) throws IOException {
            address.writeData(out);
            out.writeInt(nodeType);
            out.writeUTF(groupName);
            out.writeUTF(groupPassword);
        }

        @Override
        public String toString() {
            return "JoinRequest{" +
                    "nodeType=" + nodeType +
                    ", address=" + address +
                    ", groupName='" + groupName + '\'' +
                    ", groupPassword='" + groupPassword + '\'' +
                    '}';
        }

        public void process() {
            ClusterManager.get().handleJoinRequest(this);
        }
    }

    public static class AddRemoveConnection extends AbstractRemotelyProcessable {
        public Address address = null;

        public boolean add = true;

        public AddRemoveConnection() {

        }

        public AddRemoveConnection(Address address, boolean add) {
            super();
            this.address = address;
            this.add = add;
        }

        @Override
        public void readData(DataInput in) throws IOException {
            address = new Address();
            address.readData(in);
            add = in.readBoolean();
        }

        @Override
        public void writeData(DataOutput out) throws IOException {
            address.writeData(out);
            out.writeBoolean(add);
        }

        @Override
        public String toString() {
            return "AddRemoveConnection add=" + add + ", " + address;
        }

        public void process() {
            ClusterManager.get().handleAddRemoveConnection(this);
        }
    }

    public void sendBindRequest(Connection toConnection) {
        sendProcessableTo(new Bind(thisAddress), toConnection);
    }

    public static class Bind extends Master {

        public Bind() {
        }

        public Bind(Address localAddress) {
            super(localAddress);
        }

        @Override
        public String toString() {
            return "Bind " + address;
        }

        public void process() {
            ConnectionManager.get().bind(address, getConnection(), true);
        }
    }

    public static class Master extends AbstractRemotelyProcessable {
        public Address address = null;

        public Master() {

        }

        public Master(Address originAddress) {
            super();
            this.address = originAddress;
        }

        @Override
        public void readData(DataInput in) throws IOException {
            address = new Address();
            address.readData(in);
        }

        @Override
        public void writeData(DataOutput out) throws IOException {
            address.writeData(out);
        }

        @Override
        public String toString() {
            return "Master " + address;
        }

        public void process() {
            ClusterManager.get().handleMaster(this);
        }
    }

    public static class CreateProxy extends AbstractRemotelyProcessable {

        public String name;

        public CreateProxy() {
        }

        public CreateProxy(String name) {
            this.name = name;
        }

        public void process() {
            FactoryImpl.createProxy(name);
        }

        @Override
        public void readData(DataInput in) throws IOException {
            name = in.readUTF();
        }

        @Override
        public void writeData(DataOutput out) throws IOException {
            out.writeUTF(name);
        }

        @Override
        public String toString() {
            return "CreateProxy [" + name + "]";
        }
    }

    public void registerScheduledAction(ScheduledAction scheduledAction) {
        setScheduledActions.add(scheduledAction);
    }

    protected void deregisterScheduledAction(ScheduledAction scheduledAction) {
        setScheduledActions.remove(scheduledAction);
    }

    public void checkScheduledActions() {
        if (setScheduledActions.size() > 0) {
            Iterator<ScheduledAction> it = setScheduledActions.iterator();
            while (it.hasNext()) {
                ScheduledAction sa = it.next();
                if (sa.expired()) {
                    sa.onExpire();
                    it.remove();
                }
            }
        }
    }

    public void connectionAdded(final Connection connection) {
        enqueueAndReturn(new Processable() {
            public void process() {
                MemberImpl member = getMember(connection.getEndPoint());
                if (member != null) {
                    member.didRead();
                }
            }
        });
    }

    public void connectionRemoved(Connection connection) {
    }

    public static class MemberRemover implements RemotelyProcessable {
        private Address deadAddress = null;

        public MemberRemover() {
        }

        public MemberRemover(Address deadAddress) {
            super();
            this.deadAddress = deadAddress;
        }

        public void process() {
            ClusterManager.get().doRemoveAddress(deadAddress);
        }

        public void setConnection(Connection conn) {
        }

        public void readData(DataInput in) throws IOException {
            deadAddress = new Address();
            deadAddress.readData(in);
        }

        public void writeData(DataOutput out) throws IOException {
            deadAddress.writeData(out);
        }
    }

    protected Member addMember(MemberImpl member) {
        if (DEBUG) {
            log("ClusterManager adding " + member);
        }
        if (lsMembers.contains(member)) {
            for (MemberImpl m : lsMembers) {
                if (m.equals(member))
                    member = m;
            }
        } else {
            if (!member.getAddress().equals(thisAddress)) {
                ConnectionManager.get().getConnection(member.getAddress());
            }
            lsMembers.add(member);
        }
        return member;
    }

    protected void removeMember(Address address) {
        if (DEBUG) {
            log("removing  " + address);
        }
        Member member = mapMembers.remove(address);
        if (member != null) {
            lsMembers.remove(member);
        }
    }

    protected MemberImpl createMember(Address address, int nodeType) {
        return new MemberImpl(address, thisAddress.equals(address), nodeType);
    }

    protected MemberImpl getMember(Address address) {
        return mapMembers.get(address);
    }

    final public MemberImpl addMember(Address address, int nodeType) {
        if (address == null) {
            if (DEBUG) {
                log("Address cannot be null");
                return null;
            }
        }
        MemberImpl member = getMember(address);
        if (member == null)
            member = createMember(address, nodeType);
        addMember(member);
        return member;
    }

    public void stop() {
        setJoins.clear();
        timeToStartJoin = 0;
        lsMembers.clear();
    }

}
