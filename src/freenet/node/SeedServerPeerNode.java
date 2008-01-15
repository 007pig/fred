/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import java.net.InetAddress;
import java.util.Vector;

import freenet.io.comm.Peer;
import freenet.io.comm.PeerParseException;
import freenet.io.comm.ReferenceSignatureVerificationException;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;

/**
 * Sender's representation of a seed node.
 * @author toad
 */
public class SeedServerPeerNode extends PeerNode {

	public SeedServerPeerNode(SimpleFieldSet fs, Node node2, NodeCrypto crypto, PeerManager peers, boolean fromLocal, OutgoingPacketMangler mangler) throws FSParseException, PeerParseException, ReferenceSignatureVerificationException {
		super(fs, node2, crypto, peers, fromLocal, false, mangler, true);
	}

	public PeerNodeStatus getStatus(boolean noHeavy) {
		return new PeerNodeStatus(this, noHeavy);
	}

	public boolean isDarknet() {
		return false;
	}

	public boolean isOpennet() {
		return false;
	}

	public boolean isRealConnection() {
		return false;
	}

	public boolean equals(Object o) {
		if(o == this) return true;
		// Only equal to seednode of its own type.
		// Different to an OpennetPeerNode with the same identity!
		if(o instanceof SeedServerPeerNode) {
			return super.equals(o);
		} else return false;
	}
	
	public void onSuccess(boolean insert, boolean ssk) {
		// Ignore
	}

	public boolean isRoutingCompatible() {
		return false;
	}

	public boolean recordStatus() {
		return false;
	}

	protected void sendInitialMessages() {
		super.sendInitialMessages();
		OpennetManager om = node.getOpennet();
		if(om == null) {
			Logger.normal(this, "Opennet turned off while connecting to seednodes");
			node.peers.disconnect(this, true, true);
		} else {
			om.announcer.maybeSendAnnouncement();
		}
	}

	public InetAddress[] getInetAddresses() {
		Peer[] peers = getHandshakeIPs();
		Vector v = new Vector();
		for(int i=0;i<peers.length;i++) {
			InetAddress ia = peers[i].getFreenetAddress().dropHostname().getAddress();
			if(v.contains(ia)) continue;
			v.add(ia);
		}
		return (InetAddress[]) v.toArray(new InetAddress[v.size()]);
	}
	
	public boolean handshakeUnknownInitiator() {
		return true;
	}

	public int handshakeSetupType() {
		return FNPPacketMangler.SETUP_OPENNET_SEEDNODE;
	}

	public boolean disconnected(boolean dumpMessageQueue, boolean dumpTrackers) {
		boolean ret = super.disconnected(dumpMessageQueue, dumpTrackers);
		node.peers.disconnect(this, false, false);
		return ret;
	}

	protected boolean generateIdentityFromPubkey() {
		return false;
	}
}
