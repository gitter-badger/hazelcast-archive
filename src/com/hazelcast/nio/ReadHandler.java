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
import java.util.logging.Level;

import com.hazelcast.impl.ClusterService;
import com.hazelcast.nio.InvocationQueue.Invocation;

class ReadHandler extends AbstractSelectionHandler implements Runnable {

	ByteBuffer inBuffer = null;

	Invocation inv = null;

	long messageRead = 0;

	public ReadHandler(final Connection connection) {
		super(connection);
		inBuffer = ByteBuffer.allocate(32 * 1024);
	}

	public final void handle() {
		if (!connection.live())
			return;
		try {
			final int readBytes = socketChannel.read(inBuffer);
			if (readBytes == -1) {
				// End of stream. Closing channel...
				connection.close();
				return;
			}
			if (readBytes <= 0) {
				return;
			}
		} catch (final Exception e) {
			if (inv != null) {
				inv.returnToContainer();
				inv = null;
			}
			handleSocketException(e);
			return;
		}
		try {
			inBuffer.flip();
			while (true) {
				final int remaining = inBuffer.remaining();
				if (remaining <= 0) {
					inBuffer.clear();
					return;
				}
				if (inv == null) {
					if (remaining >= 12) {
						inv = obtainReadable();
						if (inv == null) {
							throw new RuntimeException(messageRead + " Unknown message type  from "
									+ connection.getEndPoint());
						}
					} else {
						inBuffer.compact();
						return;
					}
				}
				final boolean full = inv.read(inBuffer);
				if (full) {
					messageRead++;
					inv.flipBuffers();
					inv.read();
					inv.setFromConnection(connection);
					ClusterService.get().enqueueAndReturn(inv);
					inv = null;
				} else {
					if (inBuffer.hasRemaining()) {
						if (DEBUG) {
							throw new RuntimeException("inbuffer has remaining "
									+ inBuffer.remaining());
						}
					}
				}
			}
		} catch (final Throwable t) {
			logger.log(Level.SEVERE, "Fatal Error at ReadHandler for endPoint: "
					+ connection.getEndPoint(), t);
		} finally {
			registerOp(inSelector.selector, SelectionKey.OP_READ);
		}
	}

	public final void run() {
		registerOp(inSelector.selector, SelectionKey.OP_READ);
	}

	private final Invocation obtainReadable() {
		final Invocation inv = InvocationQueue.get().obtainInvocation();
		inv.reset();
		inv.local = false;
		return inv;
	}

}
