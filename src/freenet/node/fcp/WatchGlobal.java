/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

import com.db4o.ObjectContainer;

import freenet.client.async.ClientContext;
import freenet.client.async.DBJob;
import freenet.node.Node;
import freenet.support.Fields;
import freenet.support.SimpleFieldSet;
import freenet.support.io.NativeThread;

public class WatchGlobal extends FCPMessage {

	final boolean enabled;
	final int verbosityMask;
	static final String NAME = "WatchGlobal";

	public WatchGlobal(SimpleFieldSet fs) throws MessageInvalidException {
		enabled = Fields.stringToBool(fs.get("Enabled"), true);
		String s = fs.get("VerbosityMask");
		if(s != null)
			try {
				verbosityMask = Integer.parseInt(s);
			} catch (NumberFormatException e) {
				throw new MessageInvalidException(ProtocolErrorMessage.ERROR_PARSING_NUMBER, e.toString(), null, false);
			}
		else
			verbosityMask = Integer.MAX_VALUE;
	}
	
	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet fs = new SimpleFieldSet(true);
		fs.put("Enabled", enabled);
		fs.put("VerbosityMask", verbosityMask);
		return fs;
	}

	public String getName() {
		return NAME;
	}

	public void run(final FCPConnectionHandler handler, Node node)
			throws MessageInvalidException {
		handler.getRebootClient().setWatchGlobal(enabled, verbosityMask, node.clientCore.getFCPServer(), null);
		handler.server.core.clientContext.jobRunner.queue(new DBJob() {

			public void run(ObjectContainer container, ClientContext context) {
				handler.getForeverClient(container).setWatchGlobal(enabled, verbosityMask, handler.server, container);
			}
			
		}, NativeThread.NORM_PRIORITY, false);
		
	}

	public void removeFrom(ObjectContainer container) {
		container.delete(this);
	}

}
