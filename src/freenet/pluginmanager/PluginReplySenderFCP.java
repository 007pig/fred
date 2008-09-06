/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.pluginmanager;

import freenet.node.fcp.FCPConnectionHandler;
import freenet.node.fcp.FCPPluginReply;
import freenet.support.SimpleFieldSet;
import freenet.support.api.Bucket;

/**
 * @author saces
 *
 */
public class PluginReplySenderFCP extends PluginReplySender {
	
	final FCPConnectionHandler handler; 

	public PluginReplySenderFCP(FCPConnectionHandler handler2, String pluginname2, String identifier2) {
		super(pluginname2, identifier2);
		handler = handler2;
	}

	@Override
	public void send(SimpleFieldSet params, Bucket bucket) {
		FCPPluginReply reply = new FCPPluginReply(pluginname, identifier, params, bucket);
		handler.outputHandler.queue(reply);
	}

}
