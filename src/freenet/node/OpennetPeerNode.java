package freenet.node;

import freenet.io.comm.PeerParseException;
import freenet.io.comm.ReferenceSignatureVerificationException;
import freenet.support.SimpleFieldSet;

public class OpennetPeerNode extends PeerNode {

	final OpennetManager opennet;
	private long timeLastSuccess;
	
	public OpennetPeerNode(SimpleFieldSet fs, Node node2, NodeCrypto crypto, OpennetManager opennet, PeerManager peers, boolean fromLocal, OutgoingPacketMangler mangler) throws FSParseException, PeerParseException, ReferenceSignatureVerificationException {
		super(fs, node2, crypto, peers, fromLocal, false, mangler, true);
		this.opennet = opennet;
	}

	@Override
	public PeerNodeStatus getStatus(boolean noHeavy) {
		return new OpennetPeerNodeStatus(this, noHeavy);
	}

	@Override
	public boolean isRoutingCompatible() {
		if(!node.isOpennetEnabled()) return false;
		return super.isRoutingCompatible();
	}

	@Override
	public boolean isDarknet() {
		return false;
	}

	@Override
	public boolean isOpennet() {
		return true;
	}

	@Override
	public boolean isSeed() {
		return false;
	}

	public boolean isDroppable(boolean ignoreDisconnect) {
		long now = System.currentTimeMillis();
		if(now - getPeerAddedTime() < OpennetManager.DROP_MIN_AGE)
			return false; // New node
		if(now - node.usm.getStartedTime() < OpennetManager.DROP_STARTUP_DELAY)
			return false; // Give them time to connect after we startup
		int status = getPeerNodeStatus();
		if(!ignoreDisconnect) {
		synchronized(this) {
			if((status == PeerManager.PEER_NODE_STATUS_DISCONNECTED) && (!super.neverConnected()) && 
					now - timeLastDisconnect < OpennetManager.DROP_DISCONNECT_DELAY &&
					now - timePrevDisconnect > OpennetManager.DROP_DISCONNECT_DELAY_COOLDOWN) {
				// Grace period for node restarting
				return false;
			}
		}
		}
		return true;
	}
	
	@Override
	public void onSuccess(boolean insert, boolean ssk) {
		if(insert || ssk) return;
		timeLastSuccess = System.currentTimeMillis();
		opennet.onSuccess(this);
	}

	@Override
	public void onRemove() {
		opennet.onRemove(this);
		super.onRemove();
	}
	
	@Override
	public synchronized SimpleFieldSet exportMetadataFieldSet() {
		SimpleFieldSet fs = super.exportMetadataFieldSet();
		fs.put("timeLastSuccess", timeLastSuccess);
		return fs;
	}

	public final long timeLastSuccess() {
		return timeLastSuccess;
	}
	
	/**
	 * Is the SimpleFieldSet a valid noderef?
	 */
	public static boolean validateRef(SimpleFieldSet ref) {
		if(!ref.getBoolean("opennet", false)) return false;
		return true;
	}

	@Override
	public boolean isRealConnection() {
		return true;
	}

	@Override
	public boolean recordStatus() {
		return true;
	}

	@Override
	protected boolean generateIdentityFromPubkey() {
		return false;
	}
 
	@Override
	public boolean equals(Object o) {
		if(o == this) return true;
		// Only equal to seednode of its own type.
		if(o instanceof OpennetPeerNode) {
			return super.equals(o);
		} else return false;
	}
	
	@Override
	public final boolean shouldDisconnectAndRemoveNow() {
		// Allow announced peers 15 minutes to download the auto-update.
		if(isConnected() && isUnroutableOlderVersion() && System.currentTimeMillis() - timeLastConnectionCompleted() > 60*60*1000)
			return true;
		return false;
	}

	@Override
	protected void onConnect() {
		super.onConnect();
		opennet.crypto.socket.getAddressTracker().setPresumedGuiltyAt(System.currentTimeMillis()+60*60*1000);
	}
	
	private boolean wasDropped;

	synchronized void setWasDropped() {
		wasDropped = true;
	}
	
	synchronized boolean wasDropped() {
		return wasDropped;
	}
	
	synchronized boolean grabWasDropped() {
		boolean ret = wasDropped;
		wasDropped = false;
		return ret;
	}
	
}
