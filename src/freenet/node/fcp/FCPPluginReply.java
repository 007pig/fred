/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

import freenet.node.Node;
import freenet.support.SimpleFieldSet;
import freenet.support.api.Bucket;

/**
 * @author saces
 *
 */
public class FCPPluginReply extends DataCarryingMessage {
	
	private static final String NAME = "FCPPluginReply";
	
	public static final String PARAM_PREFIX = "Param";
	
	private final long dataLength;
	private final String plugname;
	private final String identifier;
	private final SimpleFieldSet plugparams;

	public FCPPluginReply(String pluginname, String identifier2, SimpleFieldSet fs, Bucket bucket2) {
		bucket = bucket2;
		if (bucket == null)
			dataLength = -1;
		else {
			bucket.setReadOnly();
			dataLength = bucket.size();
		}
		plugname = pluginname;
		identifier = identifier2;
		plugparams = fs;
	}

	String getIdentifier() {
		return identifier;
	}

	boolean isGlobal() {
		return false;
	}

	long dataLength() {
		return dataLength;
	}
	
	String getEndString() {
		if (dataLength() > 0)
			return "Data";
		else
			return "EndMessage";
	}

	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet sfs = new SimpleFieldSet(true);
		sfs.putSingle("PluginName", plugname);
		sfs.putSingle("Identifier", identifier);
		if (dataLength() > 0)
			sfs.put("DataLength", dataLength());			
		sfs.put("Replies", plugparams);
		return sfs;
	}

	public String getName() {
		return NAME;
	}

	public void run(FCPConnectionHandler handler, Node node) throws MessageInvalidException {
		throw new MessageInvalidException(ProtocolErrorMessage.INVALID_MESSAGE, NAME + " goes from server to client not the other way around", null, false);
	}

}
