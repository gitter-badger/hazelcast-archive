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

import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

import com.hazelcast.impl.Node;
import com.hazelcast.nio.InvocationQueue.Invocation;

public final class WriteHandler extends AbstractSelectionHandler implements Runnable {

	private static final boolean DEBUG = false;

	private static final ByteBuffer bbOut = ByteBuffer.allocateDirect(1024 * 1024); 

	private final BlockingQueue writeQueue = new LinkedBlockingQueue();

	private final AtomicBoolean informSelector = new AtomicBoolean(true);

	boolean ready = false; 

	WriteHandler(final Connection connection) {
		super(connection);
	}

	public void enqueueInvocation(final Invocation inv) {
		try {
			writeQueue.put(inv); 
		} catch (final InterruptedException e) {
			Node.get().handleInterruptedException(Thread.currentThread(), e);
		}
		if (informSelector.get()) {
			informSelector.set(false);
			outSelector.addTask(this);
			if (inv.currentCallCount < 2) { 
				outSelector.selector.wakeup();
			}
		}
	}

	public void handle() { 
		if (writeQueue.size() == 0) {
			ready = true;
			return;
		}
		if (!connection.live())
			return;
		try {
			bbOut.clear();
			copyLoop: while (bbOut.position() < (32 * 1024)) {
				final Invocation inv = (Invocation) writeQueue.poll();
				if (inv == null)
					break copyLoop; 
				inv.write(bbOut);
				inv.returnToContainer();
			}
			if (bbOut.position() == 0)
				return;
			bbOut.flip();
			int remaining = bbOut.remaining();
			int loopCount = 0; 
			while (remaining > 0) {
				try {
					final int written = socketChannel.write(bbOut);
					remaining -= written;
					loopCount++;
					if (DEBUG) {
						if (loopCount > 1) {
							logger.log(Level.FINEST, "loopcount " + loopCount);
						}
					}
				} catch (final Exception e) {
					handleSocketException(e);
					return;
				}
			}
		} catch (final Throwable t) {
			logger.log(Level.SEVERE, "Fatal Error at WriteHandler for endPoint: " + connection.getEndPoint(), t);
		} finally {
			ready = false;
			registerWrite();
		}
	}

	public void run() {
		informSelector.set(true);
		if (ready) {
			handle();
		} else {
			registerWrite();
		}
		ready = false;
	}

	private void registerWrite() {
		registerOp(outSelector.selector, SelectionKey.OP_WRITE);
	}

	@Override
	public void shutdown() { 
		Invocation inv = (Invocation) writeQueue.poll();
		while (inv != null) {
			inv.returnToContainer();
			inv = (Invocation) writeQueue.poll();
		}
		writeQueue.clear();
	} 
}
