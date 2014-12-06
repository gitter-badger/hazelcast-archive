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

package com.hazelcast.nio;

import com.hazelcast.impl.Node;

import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

public class SelectorBase implements Runnable {

    protected final Logger logger = Logger.getLogger(this.getClass().getName());

    protected final Selector selector;

    protected final BlockingQueue<Runnable> selectorQueue = new LinkedBlockingQueue<Runnable>();

    protected volatile boolean live = true;

    protected int waitTime = 16;

    AtomicInteger size = new AtomicInteger();

    final Node node;

    public SelectorBase(Node node) {
        this.node = node;
        selectorQueue.clear();
        size.set(0);
        Selector selectorTemp = null;
        try {
            selectorTemp = Selector.open();
        } catch (final IOException e) {
            handleSelectorException(e);
        }
        this.selector = selectorTemp;
        live = true;
    }

    public void shutdown() {
        if (selectorQueue != null) {
            selectorQueue.clear();
        }
        try {
            final CountDownLatch l = new CountDownLatch(1);
            addTask(new Runnable() {
                public void run() {
                    live = false;
                    l.countDown();
                }
            });
            l.await();
        } catch (InterruptedException ignored) {
        }
    }

    public int addTask(final Runnable runnable) {
        try {
            selectorQueue.put(runnable);
            return size.incrementAndGet();
        } catch (final InterruptedException e) {
            node.handleInterruptedException(Thread.currentThread(), e);
            return 0;
        }
    }

    public void processSelectionQueue() {
        while (live) {
            final Runnable runnable = selectorQueue.poll();
            if (runnable == null)
                return;
            runnable.run();
            size.decrementAndGet();
        }
    }

    public void run() {
        while (live) {
            if (size.get() > 0) {
                processSelectionQueue();
            }
            if (!live) return;
            int selectedKeys;
            try {
                selectedKeys = selector.select(waitTime);
                if (Thread.interrupted()) {
                    node.handleInterruptedException(Thread.currentThread(),
                            new RuntimeException());
                }
            } catch (final Throwable exp) {
                continue;
            }
            if (selectedKeys == 0) {
                continue;
            }
            final Set<SelectionKey> setSelectedKeys = selector.selectedKeys();
            final Iterator<SelectionKey> it = setSelectedKeys.iterator();
            while (it.hasNext()) {
                final SelectionKey sk = it.next();
                it.remove();
                try {
                    sk.interestOps(sk.interestOps() & ~sk.readyOps());
                    final SelectionHandler selectionHandler = (SelectionHandler) sk.attachment();
                    selectionHandler.handle();
                } catch (final Exception e) {
                    handleSelectorException(e);
                }
            }
        }
        try {
            selector.close();
        } catch (final Exception e) {
            e.printStackTrace();
        }
    }

    protected void handleSelectorException(final Exception e) {
        String msg = "Selector exception at  " + Thread.currentThread().getName();
        msg += ", cause= " + e.toString();
        logger.log(Level.FINEST, msg, e);
    }

    protected Connection initChannel(final SocketChannel socketChannel, final boolean acceptor)
            throws Exception {
        socketChannel.socket().setReceiveBufferSize(AbstractSelectionHandler.RECEIVE_SOCKET_BUFFER_SIZE);
        socketChannel.socket().setSendBufferSize(AbstractSelectionHandler.SEND_SOCKET_BUFFER_SIZE);
        socketChannel.socket().setKeepAlive(true);
//        socketChannel.socket().setTcpNoDelay(true);
        socketChannel.configureBlocking(false);
        return node.connectionManager.createConnection(socketChannel,
                acceptor);
    }
}
