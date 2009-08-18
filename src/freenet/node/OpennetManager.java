/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Enumeration;

import freenet.io.comm.ByteCounter;
import freenet.io.comm.DMT;
import freenet.io.comm.DisconnectedException;
import freenet.io.comm.Message;
import freenet.io.comm.MessageFilter;
import freenet.io.comm.NotConnectedException;
import freenet.io.comm.Peer;
import freenet.io.comm.PeerParseException;
import freenet.io.comm.ReferenceSignatureVerificationException;
import freenet.io.comm.RetrievalException;
import freenet.io.xfer.BulkReceiver;
import freenet.io.xfer.BulkTransmitter;
import freenet.io.xfer.PartiallyReceivedBulk;
import freenet.support.LRUQueue;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;
import freenet.support.SizeUtil;
import freenet.support.TimeSortedHashtable;
import freenet.support.io.ByteArrayRandomAccessThing;
import freenet.support.io.Closer;
import freenet.support.io.FileUtil;
import freenet.support.transport.ip.HostnameSyntaxException;

/**
 * Central location for all things opennet.
 * In particular:
 * - Opennet crypto
 * - LRU connections
 * @author toad
 */
public class OpennetManager {
	
	final Node node;
	final NodeCrypto crypto;
	final Announcer announcer;
	
	/** Our peers. PeerNode's are promoted when they successfully fetch a key. Normally we take
	 * the bottom peer, but if that isn't eligible to be dropped, we iterate up the list. */
	private final LRUQueue<PeerNode> peersLRU;
	/** Old peers. Opennet peers which we dropped but would still like to talk to
	 * if we have no other option. */
	private final LRUQueue<PeerNode> oldPeers;
	/** Maximum number of old peers */
	static final int MAX_OLD_PEERS = 25;
	/** Time at which last dropped a peer */
	private long timeLastDropped;
	/** Number of successful CHK requests since last added a node */
	private long successCount;
	
	/** Only drop a connection after at least this many successful requests */
	// FIXME should be a function of # opennet peers? max # opennet peers? ...
	public static final int MIN_SUCCESS_BETWEEN_DROP_CONNS = 10;
	/** Chance of resetting path folding (for plausible deniability) is 1 in this number. */
	public static final int RESET_PATH_FOLDING_PROB = 20;
	/** Don't re-add a node until it's been up and disconnected for at least this long */
	public static final int DONT_READD_TIME = 60*1000;
	/** Don't drop a node until it's at least this old */
	public static final int DROP_MIN_AGE = 300*1000;
	/** Don't drop a node until this long after startup */
	public static final int DROP_STARTUP_DELAY = 120*1000;
	/** Don't drop a node until this long after losing connection to it.
	 * This should be long enough to cover a typical reboot, but not so long as to result in a lot
	 * of disconnected nodes in the Strangers list. */
	public static final int DROP_DISCONNECT_DELAY = 10*60*1000;
	/** But if it has disconnected more than once in this period, allow it to be dropped anyway */
	public static final int DROP_DISCONNECT_DELAY_COOLDOWN = 60*60*1000;
	/** Every DROP_CONNECTED_TIME, we may drop a peer even though it is connected */
	public static final int DROP_CONNECTED_TIME = 10*60*1000;
	/** Minimum time between offers, if we have maximum peers. Less than the above limits,
	 * since an offer may not be accepted. */
	public static final int MIN_TIME_BETWEEN_OFFERS = 30*1000;
	private static boolean logMINOR;

	/** How big to pad opennet noderefs to? If they are bigger than this then we won't send them. */
	public static final int PADDED_NODEREF_SIZE = 3072;
	/** Allow for future expansion. However at any given time all noderefs should be PADDED_NODEREF_SIZE */
	public static final int MAX_OPENNET_NODEREF_LENGTH = 32768;
	
	/** Enable scaling of peers with bandwidth? */
	public static final boolean ENABLE_PEERS_PER_KB_OUTPUT = false;
	/** Target bandwidth usage - above this, we use MAX_PEERS_FOR_SCALING */
	public static final int TARGET_BANDWIDTH_USAGE = 20*1024; // 20 peers at 20K/sec.
	/** Minimum number of peers */
	public static final int MIN_PEERS_FOR_SCALING = 10;
	/** Maximum number of peers */
	public static final int MAX_PEERS_FOR_SCALING = 20;
	/** Maximum number of peers for purposes of FOAF attack/sanity check */
	public static final int PANIC_MAX_PEERS = 40;
	/** Stop trying to reconnect to an old-opennet-peer after a month. */
	public static final long MAX_TIME_ON_OLD_OPENNET_PEERS = 31 * 24 * 60 * 60 * 1000;
	
	private final long creationTime;
    
	public OpennetManager(Node node, NodeCryptoConfig opennetConfig, long startupTime, boolean enableAnnouncement) throws NodeInitException {
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		this.creationTime = System.currentTimeMillis();
		this.node = node;
		crypto =
			new NodeCrypto(node, true, opennetConfig, startupTime, node.enableARKs);

		File nodeFile = new File(node.nodeDir, "opennet-"+crypto.portNumber);
		File backupNodeFile = new File("opennet-"+crypto.portNumber+".bak");
		
		// Keep opennet crypto details in a separate file
		try {
			readFile(nodeFile);
		} catch (IOException e) {
			try {
				readFile(backupNodeFile);
			} catch (IOException e1) {
				crypto.initCrypto();
			}
		}
		peersLRU = new LRUQueue<PeerNode>();
		oldPeers = new LRUQueue<PeerNode>();
		node.peers.tryReadPeers(new File(node.nodeDir, "openpeers-"+crypto.portNumber).toString(), crypto, this, true, false);
		OpennetPeerNode[] nodes = node.peers.getOpennetPeers();
		Arrays.sort(nodes, new Comparator<OpennetPeerNode>() {
			public int compare(OpennetPeerNode pn1, OpennetPeerNode pn2) {
				long lastSuccess1 = pn1.timeLastSuccess();
				long lastSuccess2 = pn2.timeLastSuccess();
				
				if(lastSuccess1 > lastSuccess2) return 1;
				if(lastSuccess2 > lastSuccess1) return -1;
				
				boolean neverConnected1 = pn1.neverConnected();
				boolean neverConnected2 = pn2.neverConnected();
				if(neverConnected1 && (!neverConnected2))
					return -1;
				if((!neverConnected1) && neverConnected2)
					return 1;
				return pn1.hashCode - pn2.hashCode;
			}
		});
		for(int i=0;i<nodes.length;i++)
			peersLRU.push(nodes[i]);
		dropExcessPeers();
		writeFile(nodeFile, backupNodeFile);
		// Read old peers
		node.peers.tryReadPeers(new File(node.nodeDir, "openpeers-old-"+crypto.portNumber).toString(), crypto, this, true, true);
		announcer = (enableAnnouncement ? new Announcer(this) : null);
		if(logMINOR) {
			Logger.minor(this, "My full compressed ref: "+crypto.myCompressedFullRef().length);
			Logger.minor(this, "My full setup ref: "+crypto.myCompressedSetupRef().length);
			Logger.minor(this, "My heavy setup ref: "+crypto.myCompressedHeavySetupRef().length);
		}
	}

	public void writeFile() {
		File nodeFile = new File(node.nodeDir, "opennet-"+crypto.portNumber);
		File backupNodeFile = new File("opennet-"+crypto.portNumber+".bak");
		writeFile(nodeFile, backupNodeFile);
	}
	
	private void writeFile(File orig, File backup) {
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		SimpleFieldSet fs = crypto.exportPrivateFieldSet();
		
		if(orig.exists()) backup.delete();
		
		FileOutputStream fos = null;
		OutputStreamWriter osr = null;
		BufferedWriter bw = null;
		try {
			fos = new FileOutputStream(backup);
			osr = new OutputStreamWriter(fos, "UTF-8");
			bw = new BufferedWriter(osr);
			fs.writeTo(bw);
			
			bw.close();
			FileUtil.renameTo(backup, orig);
		} catch (IOException e) {
			Closer.close(bw);
			Closer.close(osr);
			Closer.close(fos);
		}
	}

	private void readFile(File filename) throws IOException {
		// REDFLAG: Any way to share this code with Node and NodePeer?
		FileInputStream fis = new FileInputStream(filename);
		InputStreamReader isr = new InputStreamReader(fis, "UTF-8");
		BufferedReader br = new BufferedReader(isr);
		SimpleFieldSet fs = new SimpleFieldSet(br, false, true);
		br.close();
		// Read contents
		String[] udp = fs.getAll("physical.udp");
		if((udp != null) && (udp.length > 0)) {
			for(int i=0;i<udp.length;i++) {
				// Just keep the first one with the correct port number.
				Peer p;
				try {
					p = new Peer(udp[i], false, true);
				} catch (HostnameSyntaxException e) {
					Logger.error(this, "Invalid hostname or IP Address syntax error while loading opennet peer node reference: "+udp[i]);
					System.err.println("Invalid hostname or IP Address syntax error while loading opennet peer node reference: "+udp[i]);
					continue;
				} catch (PeerParseException e) {
					IOException e1 = new IOException();
					e1.initCause(e);
					throw e1;
				}
				if(p.getPort() == crypto.portNumber) {
					// DNSRequester doesn't deal with our own node
					node.ipDetector.setOldIPAddress(p.getFreenetAddress());
					break;
				}
			}
		}
		
		crypto.readCrypto(fs);
	}

	public void start() {
		crypto.start();
		if(announcer!= null)
			announcer.start();
	}

	/**
	 * Called when opennet is disabled
	 */
	public void stop(boolean purge) {
		if(announcer != null)
			announcer.stop();
		crypto.stop();
		if(purge)
			node.peers.removeOpennetPeers();
		crypto.socket.getAddressTracker().setPresumedInnocent();
	}

	public OpennetPeerNode addNewOpennetNode(SimpleFieldSet fs) throws FSParseException, PeerParseException, ReferenceSignatureVerificationException {
		try {
		OpennetPeerNode pn = new OpennetPeerNode(fs, node, crypto, this, node.peers, false, crypto.packetMangler);
		if(Arrays.equals(pn.getIdentity(), crypto.myIdentity)) {
			if(logMINOR) Logger.minor(this, "Not adding self as opennet peer");
			return null; // Equal to myself
		}
		if(peersLRU.contains(pn)) {
			if(logMINOR) Logger.minor(this, "Not adding "+pn.userToString()+" to opennet list as already there");
			return null;
		}
		if(wantPeer(pn, true, false, false)) return pn;
		else return null;
		// Start at bottom. Node must prove itself.
		} catch (Throwable t) {
			// Don't break the code flow in the caller which is normally a request. 
			Logger.error(this, "Caught "+t+" adding opennet node from fieldset", t);
			return null;
		}
		
	}

	/** When did we last offer our noderef to some other node? */
	private long timeLastOffered;
	
	void forceAddPeer(PeerNode nodeToAddNow, boolean addAtLRU) {
		synchronized(this) {
			if(addAtLRU)
				peersLRU.pushLeast(nodeToAddNow);
			else
				peersLRU.push(nodeToAddNow);
			oldPeers.remove(nodeToAddNow);
		}
		dropExcessPeers();
	}
	
	private long timeLastAddedOldOpennetPeer = -1;
	
	private static final int OLD_OPENNET_PEER_INTERVAL = 30*1000;
	
	/**
	 * Trim the peers list and possibly add a new node. Note that if we are not adding a new node,
	 * we will only return true every MIN_TIME_BETWEEN_OFFERS, to prevent problems caused by many
	 * pending offers being accepted simultaneously.
	 * @param nodeToAddNow Node to add.
	 * @param addAtLRU If there is a node to add, add it at the bottom rather than the top. Normally
	 * we set this on new path folded nodes so that they will be replaced if during the trial period,
	 * plus the time it takes to get a new path folding offer, they don't have a successful request.
	 * @param justChecking If true, we want to know whether there is space for a node to be added
	 * RIGHT NOW. If false, the normal behaviour applies: if nodeToAddNow is passed in, we decide
	 * whether to add that node, if it's null, we decide whether to send an offer subject to the
	 * inter-offer time.
	 * @param oldOpennetPeer If true, we are trying to add an old-opennet-peer which has reconnected.
	 * There is a throttle, we accept no more than one old-opennet-peer every 30 seconds. On receiving
	 * a packet, we call once to decide whether to try to parse it against the old-opennet-peers, and
	 * then again to decide whether it is worth keeping; in the latter case if we decide not, the
	 * old-opennet-peer will be told to disconnect and go away, but normally we don't reach that point
	 * because of the first check.
	 * @return True if the node was added / should be added.
	 */
	public boolean wantPeer(PeerNode nodeToAddNow, boolean addAtLRU, boolean justChecking, boolean oldOpennetPeer) {
		boolean notMany = false;
		boolean noDisconnect;
		synchronized(this) {
			if(nodeToAddNow != null &&
					peersLRU.contains(nodeToAddNow)) {
				if(logMINOR)
					Logger.minor(this, "Opennet peer already present in LRU: "+nodeToAddNow);
				return true;
			}
			if(getSize() < getNumberOfConnectedPeersToAim()) {
				if(nodeToAddNow != null) {
					if(logMINOR) Logger.minor(this, "Added opennet peer "+nodeToAddNow+" as opennet peers list not full");
					if(addAtLRU)
						peersLRU.pushLeast(nodeToAddNow);
					else
						peersLRU.push(nodeToAddNow);
					oldPeers.remove(nodeToAddNow);
				} else {
					if(logMINOR) Logger.minor(this, "Want peer because not enough opennet nodes");
				}
				if(nodeToAddNow != null || !justChecking)
					timeLastOffered = System.currentTimeMillis();
				notMany = true;
				// Don't check timeLastAddedOldOpennetPeer, since we want it anyway. But do update it.
				if(oldOpennetPeer)
					timeLastAddedOldOpennetPeer = System.currentTimeMillis();
			}
			noDisconnect = successCount < MIN_SUCCESS_BETWEEN_DROP_CONNS;
		}
		if(notMany) {
			if(nodeToAddNow != null)
				node.peers.addPeer(nodeToAddNow, true, true); // Add to peers outside the OM lock
			return true;
		}
		boolean canAdd = true;
		ArrayList<OpennetPeerNode> dropList = new ArrayList<OpennetPeerNode>();
		synchronized(this) {
			int maxPeers = getNumberOfConnectedPeersToAim();
			// If we have dropped a disconnected peer, then the inter-peer offer cooldown doesn't apply: we can accept immediately.
			boolean hasDisconnected = false;
			if(getSize() == maxPeers && nodeToAddNow == null) {
				PeerNode toDrop = peerToDrop(true, false);
				if(toDrop != null)
					hasDisconnected = !toDrop.isConnected();
			} else while(getSize() > maxPeers - (nodeToAddNow == null ? 0 : 1)) {
				OpennetPeerNode toDrop;
				// can drop peers which are over the limit
				toDrop = peerToDrop(noDisconnect || nodeToAddNow == null, false);
				if(toDrop == null) {
					if(logMINOR)
						Logger.minor(this, "No more peers to drop, still "+peersLRU.size()+" peers, cannot accept peer"+(nodeToAddNow == null ? "" : nodeToAddNow.toString()));
					canAdd = false;
					break;
				}
				if(logMINOR)
					Logger.minor(this, "Drop opennet peer: "+toDrop+" (connected="+toDrop.isConnected()+") of "+peersLRU.size()+":"+getSize());
				if(!toDrop.isConnected())
					hasDisconnected = true;
				peersLRU.remove(toDrop);
				dropList.add(toDrop);
			}
			long now = System.currentTimeMillis();
			if(canAdd && oldOpennetPeer) {
				if(timeLastAddedOldOpennetPeer > 0 && now - timeLastAddedOldOpennetPeer > OLD_OPENNET_PEER_INTERVAL)
					canAdd = false;
			}
			if(canAdd && !justChecking) {
				if(nodeToAddNow != null) {
					successCount = 0;
					if(addAtLRU)
						peersLRU.pushLeast(nodeToAddNow);
					else
						peersLRU.push(nodeToAddNow);
					if(logMINOR) Logger.minor(this, "Added opennet peer "+nodeToAddNow+" after clearing "+dropList.size()+" items - now have "+peersLRU.size()+" opennet peers");
					oldPeers.remove(nodeToAddNow);
					if(!dropList.isEmpty())
						timeLastDropped = now;
					if(oldOpennetPeer)
						timeLastAddedOldOpennetPeer = now;
				} else {
					if(now - timeLastOffered <= MIN_TIME_BETWEEN_OFFERS && !hasDisconnected) {
						if(logMINOR)
							Logger.minor(this, "Cannot make offer because of minimum time between offers (last offered "+(now-timeLastOffered)+" ms ago)");
						// Cancel
						canAdd = false;
					} else {
						if(!dropList.isEmpty())
							timeLastDropped = now;
						if(!justChecking) {
							timeLastOffered = now;
							if(logMINOR)
								Logger.minor(this, "Sending offer");
						}
					}
				}
			}
		}
		if(nodeToAddNow != null && canAdd && !node.peers.addPeer(nodeToAddNow, true, true)) {
			if(logMINOR)
				Logger.minor(this, "Already in global peers list: "+nodeToAddNow+" when adding opennet node");
			// Just because it's in the global peers list doesn't mean its in the LRU, it may be an old-opennet-peers reconnection.
			// In which case we add it to the global peers list *before* adding it here.
		}
		for(OpennetPeerNode pn : dropList) {
			if(logMINOR) Logger.minor(this, "Dropping LRU opennet peer: "+pn);
			node.peers.disconnect(pn, true, true, true);
		}
		return canAdd;
	}

	void dropExcessPeers() {
		while(getSize() > getNumberOfConnectedPeersToAim()) {
			if(logMINOR)
				Logger.minor(this, "Dropping opennet peers: currently "+peersLRU.size());
			PeerNode toDrop;
			toDrop = peerToDrop(false, false);
			if(toDrop == null) toDrop = peerToDrop(false, true);
			if(toDrop == null) return;
			peersLRU.remove(toDrop);
			if(logMINOR)
				Logger.minor(this, "Dropping "+toDrop);
			node.peers.disconnect(toDrop, true, true, true);
		}
	}
	
	/**
	 * How many opennet peers do we have?
	 * Connected but out of date nodes don't count towards the connection limit. Let them connect for
	 * long enough to auto-update. They will be disconnected eventually, and then removed: 
	 * @see OpennetPeerNode.shouldDisconnectAndRemoveNow()
	 */
	synchronized public int getSize() {
		int x = 0;
		for (Enumeration<PeerNode> e = peersLRU.elements(); e.hasMoreElements();) {
			PeerNode pn = e.nextElement();
			if(!(pn.isConnected() && pn.isUnroutableOlderVersion())) x++;
		}
		return x;
	}

	synchronized OpennetPeerNode peerToDrop(boolean noDisconnect, boolean force) {
		if(getSize() < getNumberOfConnectedPeersToAim()) {
			// Don't drop any peers
			return null;
		} else {
			// Do we want it?
			OpennetPeerNode[] peers = peersLRU.toArrayOrdered(new OpennetPeerNode[peersLRU.size()]);
			for(int i=0;i<peers.length;i++) {
				OpennetPeerNode pn = peers[i];
				if(pn.isConnected() && pn.isUnroutableOlderVersion()) {
					// Doesn't count anyway.
					continue;
				}
				if(pn == null) continue;
				if((!pn.isDroppable(false)) && !force) continue;
				// LOCKING: Always take the OpennetManager lock first
				if(!pn.isConnected()) {
					if(Logger.shouldLog(Logger.MINOR, this))
						Logger.minor(this, "Possibly dropping opennet peer "+pn+" as is disconnected");
					pn.setWasDropped();
					return pn;
				}
			}
			if(System.currentTimeMillis() - timeLastDropped < DROP_CONNECTED_TIME)
				return null;
			if(noDisconnect) return null;
			for(int i=0;i<peers.length;i++) {
				OpennetPeerNode pn = peers[i];
				if(pn == null) continue;
				if(pn.isConnected() && pn.isUnroutableOlderVersion()) {
					// Doesn't count anyway.
					continue;
				}
				if((!pn.isDroppable(false)) && !force) continue;
				if(Logger.shouldLog(Logger.MINOR, this))
					Logger.minor(this, "Possibly dropping opennet peer "+pn+" "+
							(System.currentTimeMillis() - timeLastDropped)+" ms since last dropped peer");
				pn.setWasDropped();
				return pn;
			}
		}
		return null;
	}

	public void onSuccess(OpennetPeerNode pn) {
		synchronized(this) {
			successCount++;
			if(peersLRU.contains(pn)) {
				peersLRU.push(pn);
				if(logMINOR) Logger.minor(this, "Opennet peer "+pn+" promoted to top of LRU because of successful request");
				return;
			} else {
				if(logMINOR) Logger.minor(this, "Success on opennet peer which isn't in the LRU!: "+pn, new Exception("debug"));
				// Re-add it: nasty race condition when we have few peers
			}
		}
		if(!wantPeer(pn, false, false, false)) // Start at top as it just succeeded
			node.peers.disconnect(pn, true, false, true);
	}

	public void onRemove(OpennetPeerNode pn) {
		synchronized (this) {
			peersLRU.remove(pn);
			if(pn.isDroppable(true) && !pn.grabWasDropped()) {
				if(logMINOR) Logger.minor(this, "onRemove() for "+pn);
				oldPeers.push(pn);
				while (oldPeers.size() > MAX_OLD_PEERS)
					oldPeers.pop();
			}
		}
	}
	
	synchronized PeerNode[] getOldPeers() {
		return oldPeers.toArrayOrdered(new PeerNode[oldPeers.size()]);
	}
	
	synchronized PeerNode[] getUnsortedOldPeers() {
		return oldPeers.toArray(new PeerNode[oldPeers.size()]);
	}
	
	/**
	 * Add an old opennet node - a node which might try to reconnect, and which we should accept
	 * if we are desperate.
	 * @param pn The node to add to the old opennet nodes LRU.
	 */
	synchronized void addOldOpennetNode(PeerNode pn) {
		oldPeers.push(pn);
	}

	String getOldPeersFilename() {
		return new File(node.nodeDir, "openpeers-old-"+crypto.portNumber).toString();
	}

	synchronized int countOldOpennetPeers() {
		return oldPeers.size();
	}

	PeerNode randomOldOpennetNode() {
		PeerNode[] nodes = getUnsortedOldPeers();
		if(nodes.length == 0) return null;
		return nodes[node.random.nextInt(nodes.length)];
	}

	public synchronized void purgeOldOpennetPeer(PeerNode source) {
		oldPeers.remove(source);
	}

	protected int getNumberOfConnectedPeersToAim() {
		int max = node.getMaxOpennetPeers();
		if(ENABLE_PEERS_PER_KB_OUTPUT) {
			int obwLimit = node.getOutputBandwidthLimit();
			if(obwLimit >= TARGET_BANDWIDTH_USAGE) {
				max = Math.min(max, MAX_PEERS_FOR_SCALING);
			} else {
				int limit = Math.min(max, obwLimit * MAX_PEERS_FOR_SCALING / TARGET_BANDWIDTH_USAGE);
				if(limit < MIN_PEERS_FOR_SCALING) limit = MIN_PEERS_FOR_SCALING;
				max = Math.min(max, limit);
			}
		}
		return max - node.peers.countConnectedDarknetPeers();
	}

	/**
	 * Send our opennet noderef to a node.
	 * @param isReply If true, send an FNPOpennetConnectReply, else send an FNPOpennetConnectDestination.
	 * @param uid The unique ID of the request chain involved.
	 * @param peer The node to send the noderef to.
	 * @param cs The full compressed noderef to send.
	 * @throws NotConnectedException If the peer becomes disconnected while we are trying to send the noderef.
	 */
	public void sendOpennetRef(boolean isReply, long uid, PeerNode peer, byte[] noderef, ByteCounter ctr) throws NotConnectedException {
		byte[] padded = new byte[paddedSize(noderef.length)];
		if(noderef.length > padded.length) {
			Logger.error(this, "Noderef too big: "+noderef.length+" bytes");
			return;
		}
		node.fastWeakRandom.nextBytes(padded); // FIXME implement nextBytes(buf,offset, length)
		System.arraycopy(noderef, 0, padded, 0, noderef.length);
		long xferUID = node.random.nextLong();
		Message msg2 = isReply ? DMT.createFNPOpennetConnectReplyNew(uid, xferUID, noderef.length, padded.length) :
			DMT.createFNPOpennetConnectDestinationNew(uid, xferUID, noderef.length, padded.length);
		peer.sendAsync(msg2, null, ctr);
		innerSendOpennetRef(xferUID, padded, peer, ctr);
	}

	/**
	 * Just the actual transfer.
	 * @param xferUID The transfer UID
	 * @param padded The length of the data to transfer.
	 * @param peer The peer to send it to.
	 * @throws NotConnectedException If the peer is not connected, or we lose the connection to the peer,
	 * or it restarts.
	 */
	private void innerSendOpennetRef(long xferUID, byte[] padded, PeerNode peer, ByteCounter ctr) throws NotConnectedException {
		ByteArrayRandomAccessThing raf = new ByteArrayRandomAccessThing(padded);
		raf.setReadOnly();
		PartiallyReceivedBulk prb =
			new PartiallyReceivedBulk(node.usm, padded.length, Node.PACKET_SIZE, raf, true);
		try {
			BulkTransmitter bt =
				new BulkTransmitter(prb, peer, xferUID, true, ctr);
			bt.send();
		} catch (DisconnectedException e) {
			throw new NotConnectedException(e);
		}
	}

	public long startSendAnnouncementRequest(long uid, PeerNode peer, byte[] noderef, ByteCounter ctr, 
			double target, short htl) throws NotConnectedException {
		long xferUID = node.random.nextLong();
		Message msg = DMT.createFNPOpennetAnnounceRequest(uid, xferUID, noderef.length, 
				paddedSize(noderef.length), target, htl);
		peer.sendAsync(msg, null, ctr);
		return xferUID;
	}
	
	public void finishSentAnnouncementRequest(PeerNode peer, byte[] noderef, ByteCounter ctr, 
			long xferUID) throws NotConnectedException {
		byte[] padded = new byte[paddedSize(noderef.length)];
		node.fastWeakRandom.nextBytes(padded); // FIXME implement nextBytes(buf,offset, length)
		System.arraycopy(noderef, 0, padded, 0, noderef.length);
		innerSendOpennetRef(xferUID, padded, peer, ctr);
	}
	
	private int paddedSize(int length) {
		if(length < PADDED_NODEREF_SIZE) return PADDED_NODEREF_SIZE;
		Logger.normal(this, "Large noderef: "+length);
		if(length > MAX_OPENNET_NODEREF_LENGTH)
			throw new IllegalArgumentException("Too big noderef: "+length+" limit is "+MAX_OPENNET_NODEREF_LENGTH);
		return ((length >>> 10) + ((length & 1023) == 0 ? 0 : 1)) << 10;
	}

	public void sendAnnouncementReply(long uid, PeerNode peer, byte[] noderef, ByteCounter ctr) 
	throws NotConnectedException {
		byte[] padded = new byte[PADDED_NODEREF_SIZE];
		if(noderef.length > padded.length) {
			Logger.error(this, "Noderef too big: "+noderef.length+" bytes");
			return;
		}
		System.arraycopy(noderef, 0, padded, 0, noderef.length);
		long xferUID = node.random.nextLong();
		Message msg = DMT.createFNPOpennetAnnounceReply(uid, xferUID, noderef.length, 
				padded.length);
		peer.sendAsync(msg, null, ctr);
		innerSendOpennetRef(xferUID, padded, peer, ctr);
	}
	
	/**
	 * Wait for an opennet noderef.
	 * @param isReply If true, wait for an FNPOpennetConnectReply[New], if false wait for an FNPOpennetConnectDestination[New].
	 * @param uid The UID of the parent request.
	 * @return An opennet noderef.
	 */
	public byte[] waitForOpennetNoderef(boolean isReply, PeerNode source, long uid, ByteCounter ctr) {
		// FIXME remove back compat code
		MessageFilter mf =
			MessageFilter.create().setSource(source).setField(DMT.UID, uid).setTimeout(RequestSender.OPENNET_TIMEOUT).
			setType(isReply ? DMT.FNPOpennetConnectReplyNew : DMT.FNPOpennetConnectDestinationNew);
		if(!isReply) {
			// Also waiting for an ack
			MessageFilter mfAck = 
				MessageFilter.create().setSource(source).setField(DMT.UID, uid).setTimeout(RequestSender.OPENNET_TIMEOUT).setType(DMT.FNPOpennetCompletedAck);
			mf = mfAck.or(mf);
		}
		Message msg;
		
		try {
			msg = node.usm.waitFor(mf, ctr);
		} catch (DisconnectedException e) {
			Logger.normal(this, "No opennet response because node disconnected on "+this);
			return null; // Lost connection with request source
		}
		
		if(msg == null) {
			// Timeout
			Logger.normal(this, "Timeout waiting for opennet peer on "+this);
			return null;
		}
		
		if(msg.getSpec() == DMT.FNPOpennetCompletedAck)
			return null; // Acked (only possible if !isReply)
		
		// Noderef bulk transfer
    	long xferUID = msg.getLong(DMT.TRANSFER_UID);
    	int paddedLength = msg.getInt(DMT.PADDED_LENGTH);
    	int realLength = msg.getInt(DMT.NODEREF_LENGTH);
    	return innerWaitForOpennetNoderef(xferUID, paddedLength, realLength, source, isReply, uid, false, ctr);
	}

	byte[] innerWaitForOpennetNoderef(long xferUID, int paddedLength, int realLength, PeerNode source, boolean isReply, long uid, boolean sendReject, ByteCounter ctr) {
    	if(paddedLength > OpennetManager.MAX_OPENNET_NODEREF_LENGTH) {
    		Logger.error(this, "Noderef too big: "+SizeUtil.formatSize(paddedLength)+" real length "+SizeUtil.formatSize(realLength));
    		if(sendReject) rejectRef(uid, source, DMT.NODEREF_REJECTED_TOO_BIG, ctr);
    		return null;
    	}
    	if(realLength > paddedLength) {
    		Logger.error(this, "Real length larger than padded length: "+SizeUtil.formatSize(paddedLength)+" real length "+SizeUtil.formatSize(realLength));
    		if(sendReject) rejectRef(uid, source, DMT.NODEREF_REJECTED_REAL_BIGGER_THAN_PADDED, ctr);
    		return null;
    	}
    	byte[] buf = new byte[paddedLength];
    	ByteArrayRandomAccessThing raf = new ByteArrayRandomAccessThing(buf);
    	PartiallyReceivedBulk prb = new PartiallyReceivedBulk(node.usm, buf.length, Node.PACKET_SIZE, raf, false);
    	BulkReceiver br = new BulkReceiver(prb, source, xferUID, ctr);
    	if(logMINOR)
    		Logger.minor(this, "Receiving noderef (reply="+isReply+") as bulk transfer for request uid "+uid+" with transfer "+xferUID+" from "+source);
    	if(!br.receive()) {
    		if(source.isConnected()) {
    			String msg = "Failed to receive noderef bulk transfer for "+this+" : "+RetrievalException.getErrString(prb.getAbortReason())+" : "+prb.getAbortDescription()+" from "+source;
    			if(prb.getAbortReason() != RetrievalException.SENDER_DISCONNECTED)
    				Logger.error(this, msg);
    			else
    				Logger.normal(this, msg);
    			if(sendReject) rejectRef(uid, source, DMT.NODEREF_REJECTED_TRANSFER_FAILED, ctr);
    		}
   			return null;
    	}
    	byte[] noderef = new byte[realLength];
    	System.arraycopy(buf, 0, noderef, 0, realLength);
    	return noderef;
	}

	public void rejectRef(long uid, PeerNode source, int reason, ByteCounter ctr) {
		Message msg = DMT.createFNPOpennetNoderefRejected(uid, reason);
		try {
			source.sendAsync(msg, null, ctr);
		} catch (NotConnectedException e) {
			// Ignore
		}
	}

	public SimpleFieldSet validateNoderef(byte[] noderef, int offset, int length, PeerNode from, boolean forceOpennetEnabled) {
    	SimpleFieldSet ref;
		try {
			ref = PeerNode.compressedNoderefToFieldSet(noderef, 0, noderef.length);
		} catch (FSParseException e) {
			Logger.error(this, "Invalid noderef: "+e, e);
			return null;
		}
		if(forceOpennetEnabled)
			ref.put("opennet", true);
		
		if(!OpennetPeerNode.validateRef(ref)) {
			Logger.error(this, "Could not parse opennet noderef for "+this+" from "+from);
			return null;
		}
    	
		if (ref != null) {
			String identity = ref.get("identity");
			if (identity != null) // N2N_MESSAGE_TYPE_DIFFNODEREF don't have identity
				registerKnownIdentity(identity);
		}
		return ref;
	}

	/** Do an announcement !!
	 * @param target The location to announce to. In 0.7 we don't try to prevent nodes from choosing their
	 * announcement location, because it is easy for them to get the location they want later on anyway,
	 * and we can do a much more effective announcement this way. */
	public void announce(double target, AnnouncementCallback cb) {
		AnnounceSender sender = new AnnounceSender(target, this, node, cb, null);
		node.executor.execute(sender, "Announcement to "+target);
	}

	public long getCreationTime() {
		return creationTime;
	}


	private static final long MAX_AGE = 7 * 24 * 60 * 60 * 1000;
	private final TimeSortedHashtable<String> knownIds = new TimeSortedHashtable<String>();

	private void registerKnownIdentity(String d) {
		if (logMINOR)
			Logger.minor(this, "Known Id: " + d);
		long now = System.currentTimeMillis();

		synchronized (knownIds) {
			Logger.minor(this, "Adding Id " + d + " knownIds size " + knownIds.size());
			knownIds.push(d, now);
			Logger.minor(this, "Added Id " + d + " knownIds size " + knownIds.size());
			knownIds.removeBefore(now - MAX_AGE);
			Logger.minor(this, "Added and pruned location " + d + " knownIds size " + knownIds.size());
		}
		if (logMINOR)
			Logger.minor(this, "Estimated opennet size(session): " + knownIds.size());
	}
    //Return the estimated network size based on locations seen after timestamp or for the whole session if -1
	public int getNetworkSizeEstimate(long timestamp) {
		return knownIds.countValuesAfter(timestamp);
	}
}
