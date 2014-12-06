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

import com.hazelcast.cluster.ClusterImpl;
import com.hazelcast.cluster.ClusterManager;
import com.hazelcast.cluster.ClusterService;
import com.hazelcast.config.Config;
import com.hazelcast.config.Join;
import com.hazelcast.impl.MulticastService.JoinInfo;
import com.hazelcast.nio.*;

import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.nio.channels.ServerSocketChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.StringTokenizer;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.logging.Level;
import java.util.logging.Logger;

public class Node {
    final Logger logger = Logger.getLogger(Node.class.getName());

    volatile Address address = null;

    volatile MemberImpl localMember = null;

    volatile Address masterAddress = null;

    private final static Node instance = new Node();

    private volatile boolean joined = false;

    private volatile boolean active = false;

    private volatile boolean completelyShutdown = false;

    private ClusterImpl clusterImpl = null;

    private final CoreDump coreDump = new CoreDump();

    private Thread firstMainThread = null;

    private final List<Thread> threads = new ArrayList<Thread>(3);

    private final BlockingQueue<Address> failedConnections = new LinkedBlockingQueue<Address>();

    private final boolean superClient;

    private final Type localNodeType;

    private final String version;

    private final String build;


    public enum Type {
        MEMBER(1),
        SUPER_CLIENT(2),
        JAVA_CLIENT(3),
        CSHARP_CLIENT(4);

        Type(int type) {
            this.value = type;
        }

        private int value;

        public int getValue() {
            return value;
        }

        public static Type create(int value) {
            switch (value) {
                case 1:
                    return MEMBER;
                case 2:
                    return SUPER_CLIENT;
                case 3:
                    return JAVA_CLIENT;
                case 4:
                    return CSHARP_CLIENT;
                default:
                    return null;
            }
        }
    }

    private Node() {
        boolean sClient = false;
        final String superClientProp = System.getProperty("hazelcast.super.client");
        if (superClientProp != null) {
            if ("true".equalsIgnoreCase(superClientProp)) {
                sClient = true;
            }
        }
        superClient = sClient;
        localNodeType = (superClient) ? Type.SUPER_CLIENT : Type.MEMBER;
        String versionTemp = "unknown";
        String buildTemp = "unknown";
        try {
            InputStream inRuntimeProperties = Node.class.getClassLoader().getResourceAsStream("hazelcast-runtime.properties");
            if (inRuntimeProperties != null) {
                Properties runtimeProperties = new Properties();
                runtimeProperties.load(inRuntimeProperties);
                versionTemp = runtimeProperties.getProperty("hazelcast.version");
                buildTemp = runtimeProperties.getProperty("hazelcast.build");
            }
        } catch (Exception ignored) {
        }
        version = versionTemp;
        build = buildTemp;
    }

    public static Node get() {
        return instance;
    }

    public void dumpCore(final Throwable ex) {
        try {
            final StringBuffer sb = new StringBuffer();
            if (ex != null) {
                sb.append(BufferUtil.exceptionToString(ex, address));
            }

            sb.append("Hazelcast.version : ").append(version).append("\n");
            sb.append("Hazelcast.build   : ").append(build).append("\n");
            sb.append("Hazelcast.address   : ").append(address).append("\n");
            sb.append("joined : ").append(joined).append("\n");
            sb.append(AddressPicker.createCoreDump());
            coreDump.getPrintWriter().write(sb.toString());
            coreDump.getPrintWriter().write("\n");
            coreDump.getPrintWriter().write("\n");
            for (final Thread thread : threads) {
                thread.interrupt();
            }
            if (!joined) {
                if (firstMainThread != null) {
                    try {
                        firstMainThread.interrupt();
                    } catch (final Exception ignore) {
                    }
                }
            }
            String fileName = "hz-core";
            if (address != null)
                fileName += "-" + address.getHost() + "_" + address.getPort();
            fileName += ".txt";
            final FileOutputStream fos = new FileOutputStream(fileName);
            Util.writeText(coreDump.toString(), fos);
            fos.flush();
            fos.close();
        } catch (final Exception e) {
            e.printStackTrace();
        }
    }


    public void failedConnection(final Address address) {
        failedConnections.offer(address);
    }

    public ClusterImpl getClusterImpl() {
        return clusterImpl;
    }

    public CoreDump getCoreDump() {
        return coreDump;
    }

    public MemberImpl getLocalMember() {
        return localMember;
    }

    public final Node.Type getLocalNodeType() {
        return localNodeType;
    }

    public Address getMasterAddress() {
        return masterAddress;
    }

    public Address getThisAddress() {
        return address;
    }

    public synchronized void handleInterruptedException(final Thread thread, final Exception e) {
        final PrintWriter pw = coreDump.getPrintWriter();
        pw.write(thread.toString());
        pw.write("\n");
        final StackTraceElement[] stEls = e.getStackTrace();
        for (final StackTraceElement stackTraceElement : stEls) {
            pw.write("\tat " + stackTraceElement + "\n");
        }
        final Throwable cause = e.getCause();
        if (cause != null) {
            pw.write("\tcaused by " + cause);
        }
    }

    public static boolean isIP(final String address) {
        if (address.indexOf('.') == -1) {
            return false;
        } else {
            final StringTokenizer st = new StringTokenizer(address, ".");
            int tokenCount = 0;
            while (st.hasMoreTokens()) {
                final String token = st.nextToken();
                tokenCount++;
                try {
                    Integer.parseInt(token);
                } catch (final Exception e) {
                    return false;
                }
            }
            if (tokenCount != 4)
                return false;
        }
        return true;
    }

    public boolean isMaster(final Address address) {
        return (address.equals(masterAddress));
    }

    public final boolean isSuperClient() {
        return superClient;
    }

    public boolean joined() {
        return joined;
    }

    public boolean master() {
        return address != null && address.equals(masterAddress);
    }

    public void reJoin() {
        logger.log(Level.FINEST, "REJOINING...");
        joined = false;
        masterAddress = null;
        join();
    }

    public void restart() {
        shutdown();
        start();
    }

    public void setMasterAddress(final Address master) {
        masterAddress = master;
    }

    public void shutdown() {
        try {
            if (active) {
                // set the joined=false first so that
                // threads do not process unnecessary
                // events, such as removeaddress
                joined = false;
                active = false;
                ConcurrentMapManager.get().reset();
                ClusterService.get().stop();
                MulticastService.get().stop();
                ConnectionManager.get().shutdown();
                ExecutorManager.get().stop();
                InSelector.get().shutdown();
                OutSelector.get().shutdown();
                address = null;
                masterAddress = null;
                FactoryImpl.inited = false;
                ClusterManager.get().stop();
            }
        } catch (Throwable e) {
            if (logger != null) logger.log(Level.FINEST, "shutdown exception", e);
        }
    }

    private boolean init() {
        try {
            final String preferIPv4Stack = System.getProperty("java.net.preferIPv4Stack");
            final String preferIPv6Address = System.getProperty("java.net.preferIPv6Addresses");
            if (preferIPv6Address == null && preferIPv4Stack == null) {
                System.setProperty("java.net.preferIPv4Stack", "true");
            }
            final Config config = Config.get();
            final ServerSocketChannel serverSocketChannel = ServerSocketChannel.open();
            address = AddressPicker.pickAddress(serverSocketChannel);
            address.setThisAddress(true);
            localMember = new MemberImpl(address, true, localNodeType);
            //initialize managers..
            ClusterService.get().start();
            ClusterManager.get().init();
            ConcurrentMapManager.get().init();
            BlockingQueueManager.get().init();
            ExecutorManager.get().init();
            ListenerManager.get().init();
            TopicManager.get().init();

            ClusterManager.get().addMember(localMember);
            InSelector.get().start();
            OutSelector.get().start();
            InSelector.get().setServerSocketChannel(serverSocketChannel);
            if (address == null)
                return false;
            Logger systemLogger = Logger.getLogger("com.hazelcast.system");
            systemLogger.log(Level.INFO, "Hazelcast " + version + " ("
                    + build + ") starting at " + address);
            systemLogger.log(Level.INFO, "Copyright (C) 2009 Hazelcast.com");
            Join join = config.getNetworkConfig().getJoin();
            if (join.getMulticastConfig().isEnabled()) {
                final MulticastSocket multicastSocket = new MulticastSocket(null);
                multicastSocket.setReuseAddress(true);
                // bind to receive interface
                multicastSocket.bind(new InetSocketAddress(
                        join.getMulticastConfig().getMulticastPort()));
                multicastSocket.setTimeToLive(32);
                // set the send interface
                multicastSocket.setInterface(address.getInetAddress());
                multicastSocket.setReceiveBufferSize(1024);
                multicastSocket.setSendBufferSize(1024);
                multicastSocket.joinGroup(InetAddress
                        .getByName(join.getMulticastConfig().getMulticastGroup()));
                multicastSocket.setSoTimeout(1000);
                MulticastService.get().init(multicastSocket);
            }

        } catch (final Exception e) {
            dumpCore(e);
            e.printStackTrace();
            return false;
        }
        return true;
    }

    public void start() {
        if (completelyShutdown) return;
        firstMainThread = Thread.currentThread();
        clusterImpl = new ClusterImpl();
        final boolean inited = init();
        if (!inited)
            return;
        final Thread inThread = new Thread(InSelector.get(), "hz.InThread");
        inThread.start();
        inThread.setPriority(8);
        threads.add(inThread);

        final Thread outThread = new Thread(OutSelector.get(), "hz.OutThread");
        outThread.start();
        outThread.setPriority(8);
        threads.add(outThread);

        final Thread clusterServiceThread = new Thread(ClusterService.get(), "hz.ServiceThread");
        clusterServiceThread.start();
        clusterServiceThread.setPriority(7);
        threads.add(clusterServiceThread);

        if (Config.get().getNetworkConfig().getJoin().getMulticastConfig().isEnabled()) {
            startMulticastService();
        }
        active = true;
        join();


        firstMainThread = null;
        if (!completelyShutdown) {
            Runtime.getRuntime().addShutdownHook(new Thread() {
                @Override
                public void run() {
                    try {
                        completelyShutdown = true;
                        if (logger != null) {
                            logger.log(Level.INFO, "Hazelcast ShutdownHook is shutting down!");
                        }
                        shutdown();
                    } catch (Exception e) {
                        if (logger != null) {
                            logger.log(Level.WARNING, "Hazelcast shutdownhook exception:", e);
                        }
                    }
                }
            });
        }
    }

    public void startMulticastService() {
        final Thread multicastServiceThread = new Thread(MulticastService.get(), "hz.MulticastThread");
        multicastServiceThread.start();
        multicastServiceThread.setPriority(6);
    }

    public void unlock() {
        joined = true;
    }

    void setAsMaster() {
        masterAddress = address;
        logger.log(Level.FINEST, "adding member myself");
        ClusterManager.get().addMember(address, getLocalNodeType()); // add
        // myself
        clusterImpl.setMembers(BaseManager.lsMembers);
        unlock();
    }

    private Address findMaster() {
        final Config config = Config.get();
        try {
            final String ip = System.getProperty("join.ip");
            if (ip == null) {
                JoinInfo joinInfo = new JoinInfo(true, address, config.getGroupName(),
                        config.getGroupPassword(), getLocalNodeType());

                for (int i = 0; i < 200; i++) {
                    MulticastService.get().send(joinInfo);
                    if (masterAddress == null) {
                        Thread.sleep(10);
                    } else {
                        return masterAddress;
                    }
                }

            } else {
                logger.log(Level.FINEST, "RETURNING join.ip");
                return new Address(ip, config.getPort());
            }

        } catch (final Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    private Address getAddressFor(final String host) {
        final Config config = Config.get();
        int port = config.getPort();
        final int indexColon = host.indexOf(':');
        if (indexColon != -1) {
            port = Integer.parseInt(host.substring(indexColon + 1));
        }
        final boolean ip = isIP(host);
        try {
            if (ip) {
                return new Address(host, port, true);
            } else {
                final InetAddress[] allAddresses = InetAddress.getAllByName(host);
                for (final InetAddress inetAddress : allAddresses) {
                    boolean shouldCheck = true;
                    Address address;
                    if (config.getNetworkConfig().getInterfaces().isEnabled()) {
                        address = new Address(inetAddress.getAddress(), config.getPort());
                        shouldCheck = AddressPicker.matchAddress(address.getHost());
                    }
                    if (shouldCheck) {
                        return new Address(inetAddress.getAddress(), port);
                    }
                }
            }
        } catch (final Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    private List<Address> getPossibleMembers() {
        final Config config = Config.get();
        Join join = config.getNetworkConfig().getJoin();
        final List<String> lsJoinMembers = join.getJoinMembers().getMembers();
        final List<Address> lsPossibleAddresses = new ArrayList<Address>();
        for (final String host : lsJoinMembers) {
            // check if host is hostname of ip address
            final boolean ip = isIP(host);
            try {
                if (ip) {
                    for (int i = 0; i < 3; i++) {
                        final Address addrs = new Address(host, config.getPort() + i, true);
                        if (!addrs.equals(getThisAddress())) {
                            lsPossibleAddresses.add(addrs);
                        }
                    }
                } else {
                    final InetAddress[] allAddresses = InetAddress.getAllByName(host);
                    for (final InetAddress inetAddress : allAddresses) {
                        boolean shouldCheck = true;
                        Address addrs;
                        if (config.getNetworkConfig().getInterfaces().isEnabled()) {
                            addrs = new Address(inetAddress.getAddress(), config.getPort());
                            shouldCheck = AddressPicker.matchAddress(addrs.getHost());
                        }
                        if (shouldCheck) {
                            for (int i = 0; i < 3; i++) {
                                final Address addressProper = new Address(inetAddress.getAddress(),
                                        config.getPort() + i);
                                if (!addressProper.equals(getThisAddress())) {
                                    lsPossibleAddresses.add(addressProper);
                                }
                            }
                        }
                    }
                }
            } catch (final Exception e) {
                e.printStackTrace();
            }
        }
        lsPossibleAddresses.addAll(config.getNetworkConfig().getJoin().getJoinMembers().getAddresses());
        return lsPossibleAddresses;
    }


    private void join() {
        final Config config = Config.get();
        if (!config.getNetworkConfig().getJoin().getMulticastConfig().isEnabled()) {
            joinWithTCP();
        } else {
            joinWithMulticast();
        }
        logger.log(Level.FINEST, "Join DONE");
        ClusterManager.get().finalizeJoin();
        if (BaseManager.lsMembers.size() == 1) {
            final StringBuilder sb = new StringBuilder();
            sb.append("\n");
            sb.append(ClusterManager.get());
            logger.log(Level.INFO, sb.toString());
        }
    }

    private void joinWithMulticast() {
        masterAddress = findMaster();
        logger.log(Level.FINEST, address + " master: " + masterAddress);
        if (masterAddress == null || masterAddress.equals(address)) {
            ClusterManager.get().addMember(address, getLocalNodeType()); // add
            // myself
            masterAddress = address;
            clusterImpl.setMembers(BaseManager.lsMembers);
            unlock();
        } else {
            while (!joined) {
                try {
                    logger.log(Level.FINEST, "joining... " + masterAddress);
                    if (masterAddress == null) {
                        joinWithMulticast();
                    } else if (masterAddress.equals(address)) {
                        setAsMaster();
                    }
                    joinExisting(masterAddress);
                    Thread.sleep(500);
                } catch (final Exception e) {
                    logger.log(Level.FINEST, "multicast join", e);
                }
            }
        }
    }

    private void joinExisting(final Address masterAddress) throws Exception {
        if (masterAddress == null) return;
        if (masterAddress.equals(getThisAddress())) return;
        Connection conn = ConnectionManager.get().getOrConnect(masterAddress);
        if (conn == null)
            Thread.sleep(1000);
        conn = ConnectionManager.get().getConnection(masterAddress);
        logger.log(Level.FINEST, "Master connnection " + conn);
        if (conn != null)
            ClusterManager.get().sendJoinRequest(masterAddress);
    }

    private void joinViaPossibleMembers() {
        final Config config = Config.get();
        try {
            final List<Address> lsPossibleAddresses = getPossibleMembers();
            lsPossibleAddresses.remove(address);
            for (final Address adrs : lsPossibleAddresses) {
                logger.log(Level.FINEST, "connecting to " + adrs);
                ConnectionManager.get().getOrConnect(adrs);
            }
            boolean found = false;
            int numberOfSeconds = 0;
            while (!found
                    && numberOfSeconds < config.getNetworkConfig().getJoin().getJoinMembers().getConnectionTimeoutSeconds()) {
                Address addressFailed;
                while ((addressFailed = failedConnections.poll()) != null) {
                    lsPossibleAddresses.remove(addressFailed);
                }
                if (lsPossibleAddresses.size() == 0)
                    break;
                Thread.sleep(1000);
                numberOfSeconds++;
                int numberOfJoinReq = 0;
                for (final Address adrs : lsPossibleAddresses) {
                    final Connection conn = ConnectionManager.get().getOrConnect(adrs);
                    logger.log(Level.FINEST, "conn " + conn);
                    if (conn != null && numberOfJoinReq < 5) {
                        found = true;
                        ClusterManager.get().sendJoinRequest(adrs);
                        numberOfJoinReq++;
                    }
                }
            }
            logger.log(Level.FINEST, "FOUND " + found);
            if (!found) {
                setAsMaster();
            } else {
                while (!joined) {
                    int numberOfJoinReq = 0;
                    for (final Address adrs : lsPossibleAddresses) {
                        final Connection conn = ConnectionManager.get().getOrConnect(adrs);
                        if (conn != null && numberOfJoinReq < 5) {
                            ClusterManager.get().sendJoinRequest(adrs);
                            numberOfJoinReq++;
                        }
                    }
                    Thread.sleep(2000);
                    if (masterAddress == null) { // no-one knows the master
                        boolean masterCandidate = true;
                        for (final Address address : lsPossibleAddresses) {
                            if (this.address.hashCode() > address.hashCode())
                                masterCandidate = false;
                        }
                        if (masterCandidate) {
                            setAsMaster();
                        }
                    }
                }

            }
            lsPossibleAddresses.clear();
            failedConnections.clear();
        } catch (final Exception e) {
            e.printStackTrace();
        }
    }

    private void joinViaRequiredMember() {

        try {
            final Config config = Config.get();
            final Address requiredAddress = getAddressFor(config.getNetworkConfig().getJoin().getJoinMembers().getRequiredMember());
            logger.log(Level.FINEST, "Joining over required member " + requiredAddress);
            if (requiredAddress == null) {
                throw new RuntimeException("Invalid required member "
                        + config.getNetworkConfig().getJoin().getJoinMembers().getRequiredMember());
            }
            if (requiredAddress.equals(address)) {
                setAsMaster();
                return;
            }
            ConnectionManager.get().getOrConnect(requiredAddress);
            Connection conn = null;
            while (conn == null) {
                conn = ConnectionManager.get().getOrConnect(requiredAddress);
                Thread.sleep(1000);
            }
            while (!joined) {
                final Connection connection = ConnectionManager.get().getOrConnect(requiredAddress);
                if (connection == null)
                    joinViaRequiredMember();
                logger.log(Level.FINEST, "Sending joinRequest " + requiredAddress);
                ClusterManager.get().sendJoinRequest(requiredAddress);

                Thread.sleep(2000);
            }
        } catch (final Exception e) {
            e.printStackTrace();
        }
    }

    private void joinWithTCP() {
        final Config config = Config.get();
        if (config.getNetworkConfig().getJoin().getJoinMembers().getRequiredMember() != null) {
            joinViaRequiredMember();
        } else {
            joinViaPossibleMembers();
        }
    }
}
