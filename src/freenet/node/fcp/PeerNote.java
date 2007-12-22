/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

import java.io.UnsupportedEncodingException;

import freenet.node.Node;
import freenet.support.Base64;
import freenet.support.SimpleFieldSet;

public class PeerNote extends FCPMessage {
	static final String name = "PeerNote";
	
	final String noteText;
	final int peerNoteType;
	final String nodeIdentifier;
	
	public PeerNote(String nodeIdentifier, String noteText, int peerNoteType) {
		this.nodeIdentifier = nodeIdentifier;
		this.noteText = noteText;
		this.peerNoteType = peerNoteType;
	}
	
	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet fs = new SimpleFieldSet(true);
		fs.putSingle("NodeIdentifier", nodeIdentifier);
		fs.putSingle("PeerNoteType", Integer.toString(peerNoteType));
		try {
			fs.putSingle("NoteText", Base64.encode(noteText.getBytes("UTF-8"), true));
		} catch (UnsupportedEncodingException e) {
			throw new Error(e);
		}
		return fs;
	}

	public String getName() {
		return name;
	}

	public void run(FCPConnectionHandler handler, Node node)
			throws MessageInvalidException {
		throw new MessageInvalidException(ProtocolErrorMessage.INVALID_MESSAGE, "PeerNote goes from server to client not the other way around", null, false);
	}

}
