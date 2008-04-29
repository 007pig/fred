/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

import freenet.client.async.USKCallback;
import freenet.keys.USK;
import freenet.node.NodeClientCore;
import freenet.node.RequestStarter;

public class SubscribeUSK implements USKCallback {

	// FIXME allow client to specify priorities
	final FCPConnectionHandler handler;
	final String identifier;
	final NodeClientCore core;
	final boolean dontPoll;
	
	public SubscribeUSK(SubscribeUSKMessage message, NodeClientCore core, FCPConnectionHandler handler) {
		this.handler = handler;
		this.dontPoll = message.dontPoll;
		this.identifier = message.identifier;
		this.core = core;
		core.uskManager.subscribe(message.key, this, !message.dontPoll, handler.getClient().lowLevelClient);
	}

	public void onFoundEdition(long l, USK key) {
		if(handler.isClosed()) {
			core.uskManager.unsubscribe(key, this, !dontPoll);
			return;
		}
		FCPMessage msg = new SubscribedUSKUpdate(identifier, l, key);
		handler.outputHandler.queue(msg);
	}

	public short getPollingPriorityNormal() {
		return RequestStarter.BULK_SPLITFILE_PRIORITY_CLASS;
	}

	public short getPollingPriorityProgress() {
		return RequestStarter.UPDATE_PRIORITY_CLASS;
	}

}
