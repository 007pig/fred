/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

import freenet.keys.FreenetURI;
import freenet.node.Node;
import freenet.support.SimpleFieldSet;

public class PutSuccessfulMessage extends FCPMessage {

	public final String identifier;
	public final boolean global;
	public final FreenetURI uri;
	public final String startupTime, completionTime;
	
	public PutSuccessfulMessage(String identifier, boolean global, FreenetURI uri, long startupTime, long completionTime) {
		this.identifier = identifier;
		this.global = global;
		this.uri = uri;
		this.startupTime = String.valueOf(startupTime);
		this.completionTime = String.valueOf(completionTime);
	}

	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet fs = new SimpleFieldSet(true);
		fs.putSingle("Identifier", identifier);
		if(global) fs.putSingle("Global", "true");
		// FIXME debug and remove!
		if(uri != null)
			fs.putSingle("URI", uri.toString());
		fs.putSingle("StartupTime", startupTime);
		fs.putSingle("CompletionTime", completionTime);
		return fs;
	}

	public String getName() {
		return "PutSuccessful";
	}

	public void run(FCPConnectionHandler handler, Node node)
			throws MessageInvalidException {
		throw new MessageInvalidException(ProtocolErrorMessage.INVALID_MESSAGE, "InsertSuccessful goes from server to client not the other way around", identifier, global);
	}

}
