/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

import com.db4o.ObjectContainer;

import freenet.client.async.ClientContext;
import freenet.client.async.DBJob;
import freenet.node.Node;
import freenet.support.SimpleFieldSet;
import freenet.support.io.NativeThread;

public class ListPersistentRequestsMessage extends FCPMessage {

	static final String NAME = "ListPersistentRequests";
	
	public ListPersistentRequestsMessage(SimpleFieldSet fs) {
		// Do nothing
	}
	
	public SimpleFieldSet getFieldSet() {
		return new SimpleFieldSet(true);
	}
	
	public String getName() {
		return NAME;
	}
	
	public void run(final FCPConnectionHandler handler, Node node)
			throws MessageInvalidException {
		
		FCPClient rebootClient = handler.getRebootClient();
		
		rebootClient.queuePendingMessagesOnConnectionRestart(handler.outputHandler, null);
		rebootClient.queuePendingMessagesFromRunningRequests(handler.outputHandler, null);
		if(handler.getRebootClient().watchGlobal) {
			FCPClient globalRebootClient = handler.server.globalRebootClient;
			globalRebootClient.queuePendingMessagesOnConnectionRestart(handler.outputHandler, null);
			globalRebootClient.queuePendingMessagesFromRunningRequests(handler.outputHandler, null);
		}
		
		node.clientCore.clientContext.jobRunner.queue(new DBJob() {

			public void run(ObjectContainer container, ClientContext context) {
				FCPClient foreverClient = handler.getForeverClient();
				foreverClient.queuePendingMessagesOnConnectionRestart(handler.outputHandler, container);
				foreverClient.queuePendingMessagesFromRunningRequests(handler.outputHandler, container);
				if(handler.getRebootClient().watchGlobal) {
					FCPClient globalForeverClient = handler.server.globalForeverClient;
					globalForeverClient.queuePendingMessagesOnConnectionRestart(handler.outputHandler, container);
					globalForeverClient.queuePendingMessagesFromRunningRequests(handler.outputHandler, container);
				}
				handler.outputHandler.queue(new EndListPersistentRequestsMessage());
			}
			
		}, NativeThread.NORM_PRIORITY, false);
	}
	
}
