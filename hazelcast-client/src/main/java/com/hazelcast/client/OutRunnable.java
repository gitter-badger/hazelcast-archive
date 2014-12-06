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

package com.hazelcast.client;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;


public class OutRunnable extends IORunnable{
	final PacketWriter writer;
	final BlockingQueue<Call> queue = new LinkedBlockingQueue<Call>();
	final BlockingQueue<Call> temp = new LinkedBlockingQueue<Call>();
	Logger logger = Logger.getLogger(this.getClass().toString());
	
	public OutRunnable(final HazelcastClient client, final Map<Long,Call> calls, final PacketWriter writer) {
		super(client,calls);
		this.writer = writer;
	}
	
	Connection connection = null;


	protected void customRun() throws InterruptedException {
		Call call = null;
		try{
			call = queue.poll(100, TimeUnit.MILLISECONDS);
			if(call ==null){
                return;
            }
//			System.out.println("Sending: "+call + " " + call.getRequest().getOperation());
			callMap.put(call.getId(), call);
			
			boolean oldConnectionIsNotNull = (connection!=null);
			long oldConnectionId = -1;
			if(oldConnectionIsNotNull){
				oldConnectionId = connection.getVersion();
			}
			connection = client.connectionManager.getConnection();
			if(restoredConnection(connection, oldConnectionIsNotNull, oldConnectionId)){
				temp.add(call);
				queue.drainTo(temp);
				client.listenerManager.getListenerCalls().drainTo(queue);
				temp.drainTo(queue);
//                System.out.println("finish restore ops");
			}else{
				if(connection!=null){
					writer.write(connection, call.getRequest());
//					System.out.println("Sent: "+call + " " + call.getRequest().getOperation()+" " +connection );
				}
				else{
					interruptWaitingCalls();
				}
			}
		} catch (InterruptedException e) {
			throw e;
		} catch (Throwable io) {
//			io.printStackTrace();
			enQueue(call);
			client.connectionManager.destroyConnection(connection);
		}
	}

    private boolean restoredConnection(Connection connection, boolean oldConnectionIsNotNull, long oldConnectionId) {
        return oldConnectionIsNotNull && connection!=null && connection.getVersion()!=oldConnectionId;
    }

    public void enQueue(Call call){
		try {
			queue.put(call);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}

	public void redoWaitingCalls() {
		BlockingQueue<Call> remainingCalls = new LinkedBlockingQueue<Call>();
		queue.drainTo(remainingCalls);
		Collection<Call> cc = callMap.values();
		List<Call> waitingCalls = new ArrayList<Call>();
		waitingCalls.addAll(cc);
		for (Iterator<Call> iterator = waitingCalls.iterator(); iterator.hasNext();) {
			Call call =  iterator.next();
			redo(call);
		}
		remainingCalls.drainTo(queue);
	}

	private void redo(Call call) {
		logger.info("Redo " + call + " operation:" + call.getRequest().getOperation());
		callMap.remove(call.getId());
		enQueue(call);
	}
}
