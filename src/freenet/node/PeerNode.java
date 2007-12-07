package freenet.node;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.io.Writer;
import java.lang.ref.WeakReference;
import java.math.BigInteger;
import java.net.MalformedURLException;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Vector;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import net.i2p.util.NativeBigInteger;
import freenet.client.FetchResult;
import freenet.client.async.USKRetriever;
import freenet.client.async.USKRetrieverCallback;
import freenet.crypt.BlockCipher;
import freenet.crypt.DSA;
import freenet.crypt.DSAGroup;
import freenet.crypt.DSAPublicKey;
import freenet.crypt.DSASignature;
import freenet.crypt.KeyAgreementSchemeContext;
import freenet.crypt.SHA256;
import freenet.crypt.UnsupportedCipherException;
import freenet.crypt.ciphers.Rijndael;
import freenet.io.AddressTracker;
import freenet.io.comm.AsyncMessageCallback;
import freenet.io.comm.ByteCounter;
import freenet.io.comm.DMT;
import freenet.io.comm.DisconnectedException;
import freenet.io.comm.FreenetInetAddress;
import freenet.io.comm.Message;
import freenet.io.comm.MessageFilter;
import freenet.io.comm.NotConnectedException;
import freenet.io.comm.Peer;
import freenet.io.comm.PeerContext;
import freenet.io.comm.PeerParseException;
import freenet.io.comm.PortForwardSensitiveSocketHandler;
import freenet.io.comm.ReferenceSignatureVerificationException;
import freenet.io.comm.SocketHandler;
import freenet.io.xfer.PacketThrottle;
import freenet.keys.ClientSSK;
import freenet.keys.FreenetURI;
import freenet.keys.Key;
import freenet.keys.USK;
import freenet.support.Base64;
import freenet.support.Fields;
import freenet.support.HexUtil;
import freenet.support.IllegalBase64Exception;
import freenet.support.LRUHashtable;
import freenet.support.Logger;
import freenet.support.ShortBuffer;
import freenet.support.SimpleFieldSet;
import freenet.support.TimeUtil;
import freenet.support.WouldBlockException;
import freenet.support.math.RunningAverage;
import freenet.support.math.SimpleRunningAverage;
import freenet.support.math.TimeDecayingRunningAverage;
import freenet.support.transport.ip.HostnameSyntaxException;

/**
 * @author amphibian
 * 
 * Represents a peer we are connected to. One of the major issues
 * is that we can rekey, or a node can go down and come back up
 * while we are connected to it, and we want to reinitialize the
 * packet numbers when this happens. Hence we separate a lot of
 * code into KeyTracker, which handles all communications to and
 * from this peer over the duration of a single key.
 */
public abstract class PeerNode implements PeerContext, USKRetrieverCallback {

	private String lastGoodVersion;
	/** Set to true based on a relevant incoming handshake from this peer
	*  Set true if this peer has a incompatible older build than we are
	*/
	protected boolean verifiedIncompatibleOlderVersion;
	/** Set to true based on a relevant incoming handshake from this peer
	*  Set true if this peer has a incompatible newer build than we are
	*/
	protected boolean verifiedIncompatibleNewerVersion;
	protected boolean disableRouting;
	protected boolean disableRoutingHasBeenSetLocally;
	protected boolean disableRoutingHasBeenSetRemotely;
	/*
	* Buffer of Ni,Nr,g^i,g^r,ID
	*/
	private byte[] jfkBuffer;
	//TODO: sync ?

	protected byte[] jfkKa;
	protected byte[] jfkKe;
	protected byte[] jfkKs;
	protected byte[] jfkMyRef;
	// The following is used only if we are the initiator

	protected long jfkContextLifetime = 0;
	/** My low-level address for SocketManager purposes */
	private Peer detectedPeer;
	/** My OutgoingPacketMangler i.e. the object which encrypts packets sent to this node */
	private final OutgoingPacketMangler outgoingMangler;
	/** Advertised addresses */
	protected Vector nominalPeer;
	/** The PeerNode's report of our IP address */
	private Peer remoteDetectedPeer;
	/** Is this a testnet node? */
	public final boolean testnetEnabled;
	/** Packets sent/received on the current preferred key */
	private KeyTracker currentTracker;
	/** Previous key - has a separate packet number space */
	private KeyTracker previousTracker;
	/** When did we last rekey (promote the unverified tracker to new) ? */
	private long timeLastRekeyed;
	/** How much data did we send with the current tracker ? */
	private long totalBytesExchangedWithCurrentTracker = 0;
	/** Are we rekeying ? */
	private boolean isRekeying = false;
	/** Unverified tracker - will be promoted to currentTracker if
	* we receive packets on it
	*/
	private KeyTracker unverifiedTracker;
	/** When did we last send a packet? */
	private long timeLastSentPacket;
	/** When did we last receive a packet? */
	private long timeLastReceivedPacket;
	/** When was isConnected() last true? */
	private long timeLastConnected;
	/** When was isRoutingCompatible() last true? */
	private long timeLastRoutable;
	/** Time added or restarted (reset on startup unlike peerAddedTime) */
	private long timeAddedOrRestarted;
	/** Are we connected? If not, we need to start trying to
	* handshake.
	*/
	private boolean isConnected;
	private boolean isRoutable;

	/** Used by maybeOnConnect */
	private boolean wasDisconnected;
	/**
	* ARK fetcher.
	*/
	private USKRetriever arkFetcher;
	/** My ARK SSK public key; edition is the next one, not the current one, 
	* so this is what we want to fetch. */
	private USK myARK;
	/** Number of handshake attempts since last successful connection or ARK fetch */
	private int handshakeCount;
	/** After this many failed handshakes, we start the ARK fetcher. */
	private static final int MAX_HANDSHAKE_COUNT = 2;
	/** Current location in the keyspace */
	private double currentLocation;
	/** Node identity; for now a block of data, in future a
	* public key (FIXME). Cannot be changed.
	*/
	final byte[] identity;
	/** Hash of node identity. Used in setup key. */
	final byte[] identityHash;
	/** Hash of hash of node identity. Used in setup key. */
	final byte[] identityHashHash;
	/** Semi-unique ID used to help in mapping the network (see the code that uses it). Note this is for diagnostic
	* purposes only and should be removed along with the code that uses it eventually - FIXME */
	final long swapIdentifier;
	/** Negotiation types supported */
	int[] negTypes;
	/** Integer hash of node identity. Used as hashCode(). */
	final int hashCode;
	/** The Node we serve */
	final Node node;
	/** The PeerManager we serve */
	final PeerManager peers;
	/** MessageItem's to send ASAP. 
	 * LOCKING: Lock on self, always take that lock last. Sometimes used inside PeerNode.this lock. */
	private final LinkedList messagesToSendNow;
	/** When did we last receive a SwapRequest? */
	private long timeLastReceivedSwapRequest;
	/** Average interval between SwapRequest's */
	private final RunningAverage swapRequestsInterval;
	/** Should we decrement HTL when it is at the maximum? 
	* This decision is made once per node to prevent giving
	* away information that can make correlation attacks much
	* easier.
	*/
	final boolean decrementHTLAtMaximum;
	/** Should we decrement HTL when it is at the minimum (1)? */
	final boolean decrementHTLAtMinimum;

	/** Time at which we should send the next handshake request */
	protected long sendHandshakeTime;
	/** Time after which we log message requeues while rate limiting */
	private long nextMessageRequeueLogTime;
	/** Interval between rate limited message requeue logs (in milliseconds) */
	private long messageRequeueLogRateLimitInterval = 1000;
	/** Number of messages to be requeued after which we rate limit logging of such */
	private int messageRequeueLogRateLimitThreshold = 15;
	/** Version of the node */
	private String version;
	/** Peer node crypto group; changing this means new noderef */
	final DSAGroup peerCryptoGroup;

	/** Peer node public key; changing this means new noderef */
	final DSAPublicKey peerPubKey;
	private boolean isSignatureVerificationSuccessfull;
	/** Incoming setup key. Used to decrypt incoming auth packets.
	* Specifically: K_node XOR H(setupKey).
	*/
	final byte[] incomingSetupKey;
	/** Outgoing setup key. Used to encrypt outgoing auth packets.
	* Specifically: setupKey XOR H(K_node).
	*/
	final byte[] outgoingSetupKey;
	/** Incoming setup cipher (see above) */
	final BlockCipher incomingSetupCipher;
	/** Outgoing setup cipher (see above) */
	final BlockCipher outgoingSetupCipher;
	/** Anonymous-connect cipher. This is used in link setup if
	 * we are trying to get a connection to this node even though
	 * it doesn't know us, e.g. as a seednode. */
	final BlockCipher anonymousInitiatorSetupCipher;
	/** The context object for the currently running negotiation. */
	private KeyAgreementSchemeContext ctx;
	/** The other side's boot ID. This is a random number generated
	* at startup.
	*/
	private long bootID;

	/** If true, this means last time we tried, we got a bogus noderef */
	private boolean bogusNoderef;
	/** The time at which we last completed a connection setup. */
	private long connectedTime;
	/** The status of this peer node in terms of Node.PEER_NODE_STATUS_* */
	public int peerNodeStatus = PeerManager.PEER_NODE_STATUS_DISCONNECTED;

	/** Holds a String-Long pair that shows which message types (as name) have been send to this peer. */
	private final Hashtable localNodeSentMessageTypes = new Hashtable();
	/** Holds a String-Long pair that shows which message types (as name) have been received by this peer. */
	private final Hashtable localNodeReceivedMessageTypes = new Hashtable();

	/** Hold collected IP addresses for handshake attempts, populated by DNSRequestor */
	private Peer[] handshakeIPs;
	/** The last time we attempted to update handshakeIPs */
	private long lastAttemptedHandshakeIPUpdateTime;
	/** True if we have never connected to this peer since it was added to this node */
	private boolean neverConnected;
	/** When this peer was added to this node */
	private long peerAddedTime = 1;
	/** Average proportion of requests which are rejected or timed out */
	private TimeDecayingRunningAverage pRejected;
	/** Total low-level input bytes */
	private long totalBytesIn;
	/** Total low-level output bytes */
	private long totalBytesOut;
	/** Times had routable connection when checked */
	private long hadRoutableConnectionCount;
	/** Times checked for routable connection */
	private long routableConnectionCheckCount;
	/** Delta between our clock and his clock (positive = his clock is fast, negative = our clock is fast) */
	private long clockDelta;

	/** If the clock delta is more than this constant, we don't talk to the node. Reason: It may not be up to date,
	* it will have difficulty resolving date-based content etc. */
	private static final long MAX_CLOCK_DELTA = 24L * 60L * 60L * 1000L;
	/** 1 hour after the node is disconnected, if it is still disconnected and hasn't connected in that time, 
	 * clear the message queue */
	private static final long CLEAR_MESSAGE_QUEUE_AFTER = 60 * 60 * 1000L;
	/** A WeakReference to this object. Can be taken whenever a node object needs to refer to this object for a 
	 * long time, but without preventing it from being GC'ed. */
	final WeakReference myRef;
	/** The node is being disconnected, but it may take a while. */
	private boolean disconnecting;
    /** When did we last disconnect? Not Disconnected because a discrete event */
    long timeLastDisconnect;
    /** Previous time of disconnection */
    long timePrevDisconnect;
    
    // Burst-only mode
    /** True if we are currently sending this peer a burst of handshake requests */
    private boolean isBursting;
    /** Number of handshake attempts (while in ListenOnly mode) since the beginning of this burst */
    private int listeningHandshakeBurstCount;
    /** Total number of handshake attempts (while in ListenOnly mode) to be in this burst */
    private int listeningHandshakeBurstSize;
    
    
	/**
	 * For FNP link setup:
	 *  The initiator has to ensure that nonces send back by the
	 *  responder in message2 match what was chosen in message 1
	 */
	protected final HashMap jfkNoncesSent = new HashMap();
	private static boolean logMINOR;
	
	/**
	* Create a PeerNode from a SimpleFieldSet containing a
	* node reference for one. This must contain the following
	* fields:
	* - identity
	* - version
	* - location
	* - physical.udp
	* - setupKey
	* Do not add self to PeerManager.
	* @param fs The SimpleFieldSet to parse
	* @param node2 The running Node we are part of.
	*/
	public PeerNode(SimpleFieldSet fs, Node node2, NodeCrypto crypto, PeerManager peers, boolean fromLocal, OutgoingPacketMangler mangler, boolean isOpennet) throws FSParseException, PeerParseException, ReferenceSignatureVerificationException {
		logMINOR = Logger.shouldLog(Logger.MINOR, PeerNode.class);
		myRef = new WeakReference(this);
		this.outgoingMangler = mangler;
		this.node = node2;
		this.peers = peers;
		this.backedOffPercent = new TimeDecayingRunningAverage(0.0, 180000, 0.0, 1.0, node);
		String identityString = fs.get("identity");
		if(identityString == null)
			throw new PeerParseException("No identity!");
		try {
			identity = Base64.decode(identityString);
		} catch(NumberFormatException e) {
			throw new FSParseException(e);
		} catch(IllegalBase64Exception e) {
			throw new FSParseException(e);
		}

		if(identity == null)
			throw new FSParseException("No identity");
		identityHash = SHA256.digest(identity);
		identityHashHash = SHA256.digest(identityHash);
		swapIdentifier = Fields.bytesToLong(identityHashHash);
		hashCode = Fields.hashCode(identityHash);
		version = fs.get("version");
		Version.seenVersion(version);
		String locationString = fs.get("location");
		try {
			currentLocation = Location.getLocation(locationString);
		} catch(FSParseException e) {
			// Wait for them to send us an FNPLocChangeNotification
			currentLocation = -1.0;
		}

		disableRouting = disableRoutingHasBeenSetLocally = false;
		disableRoutingHasBeenSetRemotely = false; // Assume so
		
		// FIXME make mandatory once everyone has upgraded
		lastGoodVersion = fs.get("lastGoodVersion");
		updateShouldDisconnectNow();

		String testnet = fs.get("testnet");
		testnetEnabled = Fields.stringToBool(fs.get("testnet"), true);
		if(node.testnetEnabled != testnetEnabled) {
			String err = "Ignoring incompatible node " + detectedPeer + " - peer.testnet=" + testnetEnabled + '(' + testnet + ") but node.testnet=" + node.testnetEnabled;
			Logger.error(this, err);
			throw new PeerParseException(err);
		}

		nominalPeer = new Vector();
		try {
			String physical[] = fs.getAll("physical.udp");
			if(physical == null) {
				// Leave it empty
			} else {
				for(int i = 0; i < physical.length; i++) {
					Peer p;
					try {
						p = new Peer(physical[i], true, true);
					} catch(HostnameSyntaxException e) {
						if(fromLocal)
							Logger.error(this, "Invalid hostname or IP Address syntax error while parsing peer reference in local peers list: " + physical[i]);
						System.err.println("Invalid hostname or IP Address syntax error while parsing peer reference: " + physical[i]);
						continue;
					}
					if(!nominalPeer.contains(p))
						nominalPeer.addElement(p);
				}
			}
		} catch(Exception e1) {
			throw new FSParseException(e1);
		}
		if(nominalPeer.isEmpty()) {
			Logger.normal(this, "No IP addresses found for identity '" + Base64.encode(identity) + "', possibly at location '" + Double.toString(currentLocation) + ": " + userToString());
			detectedPeer = null;
		} else {
			detectedPeer = (Peer) nominalPeer.firstElement();
		}
		negTypes = fs.getIntArray("auth.negTypes");
		if(negTypes == null || negTypes.length == 0)
			negTypes = new int[]{0};

		if((!fromLocal) && fs.getBoolean("opennet", false) != isOpennet)
			throw new FSParseException("Trying to parse a darknet peer as opennet or an opennet peer as darknet");

		/* Read the DSA key material for the peer */
		try {
			SimpleFieldSet sfs = fs.subset("dsaGroup");
			if(sfs == null)
				throw new FSParseException("No dsaGroup - very old reference?");
			else
				this.peerCryptoGroup = DSAGroup.create(sfs);

			sfs = fs.subset("dsaPubKey");
			if(sfs == null || peerCryptoGroup == null)
				throw new FSParseException("No dsaPubKey - very old reference?");
			else
				this.peerPubKey = DSAPublicKey.create(sfs, peerCryptoGroup);

			String signature = fs.get("sig");
			fs.removeValue("sig");
			if(!fromLocal) {
				try {
					boolean failed = false;
					if(signature == null || peerCryptoGroup == null || peerPubKey == null ||
						(failed = !(DSA.verify(peerPubKey, new DSASignature(signature), new BigInteger(1, SHA256.digest(fs.toOrderedString().getBytes("UTF-8"))), false)))) {
						String errCause = "";
						if(signature == null)
							errCause += " (No signature)";
						if(peerCryptoGroup == null)
							errCause += " (No peer crypto group)";
						if(peerPubKey == null)
							errCause += " (No peer public key)";
						if(failed)
							errCause += " (VERIFICATION FAILED)";
						Logger.error(this, "The integrity of the reference has been compromized!" + errCause + " fs was\n" + fs.toOrderedString());
						this.isSignatureVerificationSuccessfull = false;
						fs.putSingle("sig", signature);
						throw new ReferenceSignatureVerificationException("The integrity of the reference has been compromized!" + errCause);
					} else
						this.isSignatureVerificationSuccessfull = true;
				} catch(NumberFormatException e) {
					Logger.error(this, "Invalid reference: " + e, e);
					throw new ReferenceSignatureVerificationException("The node reference you added is invalid: It does not have a valid signature.");
				} catch(UnsupportedEncodingException e) {
					//   duh ?
					Logger.error(this, "Error while signing the node identity!" + e);
					System.err.println("Error while signing the node identity!" + e);
					e.printStackTrace();
					node.exit(NodeInitException.EXIT_CRAPPY_JVM);
				}
			} else {
				// Local is always good (assumed)
				this.isSignatureVerificationSuccessfull = true;
			}
		} catch(IllegalBase64Exception e) {
			Logger.error(this, "Caught " + e, e);
			throw new FSParseException(e);
		}

		// Setup incoming and outgoing setup ciphers
		byte[] nodeKey = crypto.identityHash;
		byte[] nodeKeyHash = crypto.identityHashHash;

		int digestLength = SHA256.getDigestLength();
		incomingSetupKey = new byte[digestLength];
		for(int i = 0; i < incomingSetupKey.length; i++)
			incomingSetupKey[i] = (byte) (nodeKey[i] ^ identityHashHash[i]);
		outgoingSetupKey = new byte[digestLength];
		for(int i = 0; i < outgoingSetupKey.length; i++)
			outgoingSetupKey[i] = (byte) (nodeKeyHash[i] ^ identityHash[i]);
		if(logMINOR)
			Logger.minor(this, "Keys:\nIdentity:  " + HexUtil.bytesToHex(crypto.myIdentity) +
				"\nThisIdent: " + HexUtil.bytesToHex(identity) +
				"\nNode:      " + HexUtil.bytesToHex(nodeKey) +
				"\nNode hash: " + HexUtil.bytesToHex(nodeKeyHash) +
				"\nThis:      " + HexUtil.bytesToHex(identityHash) +
				"\nThis hash: " + HexUtil.bytesToHex(identityHashHash) +
				"\nFor:       " + getPeer());

		try {
			incomingSetupCipher = new Rijndael(256, 256);
			incomingSetupCipher.initialize(incomingSetupKey);
			outgoingSetupCipher = new Rijndael(256, 256);
			outgoingSetupCipher.initialize(outgoingSetupKey);
			anonymousInitiatorSetupCipher = new Rijndael(256, 256);
			anonymousInitiatorSetupCipher.initialize(identityHash);
		} catch(UnsupportedCipherException e1) {
			Logger.error(this, "Caught: " + e1);
			throw new Error(e1);
		}

		// Don't create trackers until we have a key
		currentTracker = null;
		previousTracker = null;

		timeLastSentPacket = -1;
		timeLastReceivedPacket = -1;
		timeLastReceivedSwapRequest = -1;
		timeLastConnected = -1;
		timeLastRoutable = -1;
		timeAddedOrRestarted = System.currentTimeMillis();

		swapRequestsInterval = new SimpleRunningAverage(50, Node.MIN_INTERVAL_BETWEEN_INCOMING_SWAP_REQUESTS);

		// Not connected yet; need to handshake
		isConnected = false;

		messagesToSendNow = new LinkedList();

		decrementHTLAtMaximum = node.random.nextFloat() < Node.DECREMENT_AT_MAX_PROB;
		decrementHTLAtMinimum = node.random.nextFloat() < Node.DECREMENT_AT_MIN_PROB;

		pingNumber = node.random.nextLong();

		// A SimpleRunningAverage would be a bad choice because it would cause oscillations.
		// So go for a filter.
		pingAverage =
			new TimeDecayingRunningAverage(1, 600 * 1000 /* should be significantly longer than a typical transfer */, 0, NodePinger.CRAZY_MAX_PING_TIME, node);

		// TDRA for probability of rejection
		pRejected =
			new TimeDecayingRunningAverage(0, 600 * 1000, 0.0, 1.0, node);

		// ARK stuff.

		parseARK(fs, true);

		// Now for the metadata.
		// The metadata sub-fieldset contains data about the node which is not part of the node reference.
		// It belongs to this node, not to the node being described.
		// Therefore, if we are parsing a remotely supplied ref, ignore it.

		long now = System.currentTimeMillis();
		if(fromLocal) {

			SimpleFieldSet metadata = fs.subset("metadata");

			if(metadata != null) {

				// Don't be tolerant of nonexistant domains; this should be an IP address.
				Peer p;
				try {
					String detectedUDPString = metadata.get("detected.udp");
					p = null;
					if(detectedUDPString != null)
						p = new Peer(detectedUDPString, false);
				} catch(UnknownHostException e) {
					p = null;
					Logger.error(this, "detected.udp = " + metadata.get("detected.udp") + " - " + e, e);
				} catch(PeerParseException e) {
					p = null;
					Logger.error(this, "detected.udp = " + metadata.get("detected.udp") + " - " + e, e);
				}
				if(p != null)
					detectedPeer = p;
				String tempTimeLastReceivedPacketString = metadata.get("timeLastReceivedPacket");
				if(tempTimeLastReceivedPacketString != null) {
					long tempTimeLastReceivedPacket = Long.parseLong(tempTimeLastReceivedPacketString);
					timeLastReceivedPacket = tempTimeLastReceivedPacket;
				}
				String tempTimeLastConnectedString = metadata.get("timeLastConnected");
				if(tempTimeLastConnectedString != null) {
					long tempTimeLastConnected = Long.parseLong(tempTimeLastConnectedString);
					timeLastConnected = tempTimeLastConnected;
				}
				String tempTimeLastRoutableString = metadata.get("timeLastRoutable");
				if(tempTimeLastRoutableString != null) {
					long tempTimeLastRoutable = Long.parseLong(tempTimeLastRoutableString);
					timeLastRoutable = tempTimeLastRoutable;
				}
				if(timeLastConnected < 1 && timeLastReceivedPacket > 1)
					timeLastConnected = timeLastReceivedPacket;
				if(timeLastRoutable < 1 && timeLastReceivedPacket > 1)
					timeLastRoutable = timeLastReceivedPacket;
				String tempPeerAddedTimeString = metadata.get("peerAddedTime");
				if(tempPeerAddedTimeString != null) {
					long tempPeerAddedTime = Long.parseLong(tempPeerAddedTimeString);
					peerAddedTime = tempPeerAddedTime;
				} else
					peerAddedTime = 0;
				neverConnected = Fields.stringToBool(metadata.get("neverConnected"), false);
				if((now - peerAddedTime) > (((long) 30) * 24 * 60 * 60 * 1000))  // 30 days
					peerAddedTime = 0;
				if(!neverConnected)
					peerAddedTime = 0;
				String tempHadRoutableConnectionCountString = metadata.get("hadRoutableConnectionCount");
				if(tempHadRoutableConnectionCountString != null) {
					long tempHadRoutableConnectionCount = Long.parseLong(tempHadRoutableConnectionCountString);
					hadRoutableConnectionCount = tempHadRoutableConnectionCount;
				} else
					hadRoutableConnectionCount = 0;
				String tempRoutableConnectionCheckCountString = metadata.get("routableConnectionCheckCount");
				if(tempRoutableConnectionCheckCountString != null) {
					long tempRoutableConnectionCheckCount = Long.parseLong(tempRoutableConnectionCheckCountString);
					routableConnectionCheckCount = tempRoutableConnectionCheckCount;
				} else
					routableConnectionCheckCount = 0;
			}
		} else {
			neverConnected = true;
			peerAddedTime = now;
		}
		// populate handshakeIPs so handshakes can start ASAP
		lastAttemptedHandshakeIPUpdateTime = 0;
		maybeUpdateHandshakeIPs(true);

        listeningHandshakeBurstCount = 0;
        listeningHandshakeBurstSize = Node.MIN_BURSTING_HANDSHAKE_BURST_SIZE
        	+ node.random.nextInt(Node.RANDOMIZED_BURSTING_HANDSHAKE_BURST_SIZE);
		
        if(isBurstOnly()) {
        	Logger.minor(this, "First BurstOnly mode handshake in "+(sendHandshakeTime - now)+"ms for "+shortToString()+" (count: "+listeningHandshakeBurstCount+", size: "+listeningHandshakeBurstSize+ ')');
        }

		if(fromLocal)
			innerCalcNextHandshake(false, false, now); // Let them connect so we can recognise we are NATed

		else
			sendHandshakeTime = now;  // Be sure we're ready to handshake right away

	// status may have changed from PEER_NODE_STATUS_DISCONNECTED to PEER_NODE_STATUS_NEVER_CONNECTED
	}

	private boolean parseARK(SimpleFieldSet fs, boolean onStartup) {
		USK ark = null;
		long arkNo = 0;
		try {
			String arkNumber = fs.get("ark.number");

			if(arkNumber != null) {
				arkNo = Long.parseLong(arkNumber) + (onStartup ? 0 : 1);
				// this is the number of the ref we are parsing.
				// we want the number of the next edition.
				// on startup we want to fetch the old edition in case there's been a corruption.
			}
			String arkPubKey = fs.get("ark.pubURI");
			if(arkPubKey != null) {
				FreenetURI uri = new FreenetURI(arkPubKey);
				ClientSSK ssk = new ClientSSK(uri);
				ark = new USK(ssk, arkNo);
			}
		} catch(MalformedURLException e) {
			Logger.error(this, "Couldn't parse ARK info for " + this + ": " + e, e);
		} catch(NumberFormatException e) {
			Logger.error(this, "Couldn't parse ARK info for " + this + ": " + e, e);
		}

		synchronized(this) {
			if(ark != null) {
				if((myARK == null) || ((myARK != ark) && !myARK.equals(ark))) {
					myARK = ark;
					return true;
				}
			}
		}
		return false;
	}

	/**
	* Get my low-level address. This is the address that packets have been received from from this node.
	* 
	* Normally this is the address that packets have been received from from this node.
	* However, if ignoreSourcePort is set, we will search for a similar address with a different port 
	* number in the node reference.
	*/
	public synchronized Peer getPeer() {
		return detectedPeer;
	}

	/**
	* Returns an array with the advertised addresses and the detected one
	*/
	protected synchronized Peer[] getHandshakeIPs() {
		return handshakeIPs;
	}

	private String handshakeIPsToString() {
		Peer[] localHandshakeIPs;
		synchronized(this) {
			localHandshakeIPs = handshakeIPs;
		}
		if(localHandshakeIPs == null)
			return "null";
		StringBuffer toOutputString = new StringBuffer(1024);
		boolean needSep = false;
		toOutputString.append("[ ");
		for(int i = 0; i < localHandshakeIPs.length; i++) {
			if(needSep)
				toOutputString.append(", ");
			if(localHandshakeIPs[i] == null) {
				toOutputString.append("null");
				needSep = true;
				continue;
			}
			toOutputString.append('\'');
			// Actually do the DNS request for the member Peer of localHandshakeIPs
			toOutputString.append(localHandshakeIPs[i].getAddress(false));
			toOutputString.append('\'');
			needSep = true;
		}
		toOutputString.append(" ]");
		return toOutputString.toString();
	}

	/**
	* Do the maybeUpdateHandshakeIPs DNS requests, but only if ignoreHostnames is false
	* This method should only be called by maybeUpdateHandshakeIPs.
	* Also removes dupes post-lookup.
	*/
	private Peer[] updateHandshakeIPs(Peer[] localHandshakeIPs, boolean ignoreHostnames) {
		for(int i = 0; i < localHandshakeIPs.length; i++) {
			if(ignoreHostnames) {
				// Don't do a DNS request on the first cycle through PeerNodes by DNSRequest
				// upon startup (I suspect the following won't do anything, but just in case)
				if(logMINOR)
					Logger.debug(this, "updateHandshakeIPs: calling getAddress(false) on Peer '" + localHandshakeIPs[i] + "' for " + shortToString() + " (" + ignoreHostnames + ')');
				localHandshakeIPs[i].getAddress(false);
			} else {
				// Actually do the DNS request for the member Peer of localHandshakeIPs
				if(logMINOR)
					Logger.debug(this, "updateHandshakeIPs: calling getHandshakeAddress() on Peer '" + localHandshakeIPs[i] + "' for " + shortToString() + " (" + ignoreHostnames + ')');
				localHandshakeIPs[i].getHandshakeAddress();
			}
		}
		// De-dupe
		HashSet ret = new HashSet();
		for(int i = 0; i < localHandshakeIPs.length; i++)
			ret.add(localHandshakeIPs[i]);
		return (Peer[]) ret.toArray(new Peer[ret.size()]);
	}

	/**
	* Do occasional DNS requests, but ignoreHostnames should be true
	* on PeerNode construction
	*/
	public void maybeUpdateHandshakeIPs(boolean ignoreHostnames) {
		long now = System.currentTimeMillis();
		Peer localDetectedPeer = null;
		synchronized(this) {
			localDetectedPeer = detectedPeer;
			if((now - lastAttemptedHandshakeIPUpdateTime) < (5 * 60 * 1000)) {  // 5 minutes
	    		//Logger.minor(this, "Looked up recently (localDetectedPeer = "+localDetectedPeer + " : "+((localDetectedPeer == null) ? "" : localDetectedPeer.getAddress(false).toString()));
				return;
			}
			// We want to come back right away for DNS requesting if this is our first time through
			if(!ignoreHostnames)
				lastAttemptedHandshakeIPUpdateTime = now;
		}
		if(logMINOR)
			Logger.minor(this, "Updating handshake IPs for peer '" + shortToString() + "' (" + ignoreHostnames + ')');
		Peer[] localHandshakeIPs;
		Peer[] myNominalPeer;

		// Don't synchronize while doing lookups which may take a long time!
		synchronized(this) {
			myNominalPeer = (Peer[]) nominalPeer.toArray(new Peer[nominalPeer.size()]);
		}

		if(myNominalPeer.length == 0) {
			if(localDetectedPeer == null) {
				localHandshakeIPs = null;
				synchronized(this) {
					handshakeIPs = null;
				}
				if(logMINOR)
					Logger.minor(this, "1: maybeUpdateHandshakeIPs got a result of: " + handshakeIPsToString());
				return;
			}
			localHandshakeIPs = new Peer[]{localDetectedPeer};
			localHandshakeIPs = updateHandshakeIPs(localHandshakeIPs, ignoreHostnames);
			synchronized(this) {
				handshakeIPs = localHandshakeIPs;
			}
			if(logMINOR)
				Logger.minor(this, "2: maybeUpdateHandshakeIPs got a result of: " + handshakeIPsToString());
			return;
		}

		// Hack for two nodes on the same IP that can't talk over inet for routing reasons
		FreenetInetAddress localhost = node.fLocalhostAddress;
		Peer[] nodePeers = outgoingMangler.getPrimaryIPAddress();

		Vector peers = null;
		synchronized(this) {
			peers = new Vector(nominalPeer);
		}

		boolean addedLocalhost = false;
		Peer detectedDuplicate = null;
		for(int i = 0; i < myNominalPeer.length; i++) {
			Peer p = myNominalPeer[i];
			if(p == null)
				continue;
			if(localDetectedPeer != null) {
				if((p != localDetectedPeer) && p.equals(localDetectedPeer)) {
					// Equal but not the same object; need to update the copy.
					detectedDuplicate = p;
				}
			}
			FreenetInetAddress addr = p.getFreenetAddress();
			if(addr.equals(localhost)) {
				if(addedLocalhost)
					continue;
				addedLocalhost = true;
			}
			for(int j = 0; j < nodePeers.length; j++) {
				// REDFLAG - Two lines so we can see which variable is null when it NPEs
				FreenetInetAddress myAddr = nodePeers[j].getFreenetAddress();
				if(myAddr.equals(addr)) {
					if(!addedLocalhost)
						peers.add(new Peer(localhost, p.getPort()));
					addedLocalhost = true;
				}
			}
			if(peers.contains(p))
				continue;
			peers.add(p);
		}

		localHandshakeIPs = (Peer[]) peers.toArray(new Peer[peers.size()]);
		localHandshakeIPs = updateHandshakeIPs(localHandshakeIPs, ignoreHostnames);
		synchronized(this) {
			handshakeIPs = localHandshakeIPs;
			if((detectedDuplicate != null) && detectedDuplicate.equals(localDetectedPeer))
				localDetectedPeer = detectedPeer = detectedDuplicate;
		}
		if(logMINOR) {
			Logger.minor(this, "3: detectedPeer = " + localDetectedPeer + " (" + localDetectedPeer.getAddress(false) + ')');
			Logger.minor(this, "3: maybeUpdateHandshakeIPs got a result of: " + handshakeIPsToString());
		}
	}

	/**
	* What is my current keyspace location?
	*/
	public synchronized double getLocation() {
		return currentLocation;
	}

	/**
	* Returns a unique node identifier (usefull to compare 2 pn)
	*/
	public int getIdentityHash() {
		return hashCode;
	}

	/**
	* Is this peer too old for us? (i.e. our lastGoodVersion is newer than it's version)
	* 
	*/
	public synchronized boolean isVerifiedIncompatibleOlderVersion() {
		return verifiedIncompatibleOlderVersion;
	}

	/**
	* Is this peer too new for us? (i.e. our version is older than it's lastGoodVersion)
	* 
	*/
	public synchronized boolean isVerifiedIncompatibleNewerVersion() {
		return verifiedIncompatibleNewerVersion;
	}

	/**
	* Is this peer currently connected? (And routing-compatible, i.e. can we route
	* requests to it, ignoring backoff)
	* 
	* Note possible deadlocks! PeerManager calls this, we call
	* PeerManager in e.g. verified.
	*/
	public boolean isRoutable() {
		return isConnected() && isRoutingCompatible() &&
			!(currentLocation < 0.0 || currentLocation > 1.0);
	}

	public boolean isRoutingCompatible() {
		long now = System.currentTimeMillis();
		synchronized(this) {
			if(isRoutable && !disableRouting) {
				timeLastRoutable = now;
				return true;
			}
			return false;
		}
	}

	public boolean isConnected() {
		long now = System.currentTimeMillis();
		synchronized(this) {
			if(isConnected && currentTracker != null && !currentTracker.isDeprecated()) {
				timeLastConnected = now;
				return true;
			}
			return false;
		}
	}

	/**
	* Send a message, off-thread, to this node.
	* @param msg The message to be sent.
	* @param cb The callback to be called when the packet has been sent, or null.
	* @param alreadyReportedBytes The number of bytes already reported to the throttle
	* relating to this packet (normally set when we have delayed a packet in order to
	* throttle it).
	*/
	public void sendAsync(Message msg, AsyncMessageCallback cb, int alreadyReportedBytes, ByteCounter ctr) throws NotConnectedException {
		if(logMINOR)
			Logger.minor(this, "Sending async: " + msg + " : " + cb + " on " + this);
		if(!isConnected())
			throw new NotConnectedException();
		addToLocalNodeSentMessagesToStatistic(msg);
		MessageItem item = new MessageItem(msg, cb == null ? null : new AsyncMessageCallback[]{cb}, alreadyReportedBytes, ctr);
		item.getData(this);
		long now = System.currentTimeMillis();
		reportBackoffStatus(now);
		int x = 0;
		synchronized(messagesToSendNow) {
			messagesToSendNow.addLast(item);
			Iterator i = messagesToSendNow.iterator();
			for(; i.hasNext();) {
				MessageItem it = (MessageItem) (i.next());
				x += it.getData(this).length + 2;
				if(x > 1024)
					break;
			}
		}
		if(x > 1024) {
			// If there is a packet's worth to send, wake up the packetsender.
			node.ps.wakeUp();
		}
		// Otherwise we do not need to wake up the PacketSender
        // It will wake up before the maximum coalescing delay (100ms) because
        // it wakes up every 100ms *anyway*.
	}

	public long getMessageQueueLengthBytes() {
		long x = 0;
		synchronized(messagesToSendNow) {
			Iterator i = messagesToSendNow.iterator();
			for(; i.hasNext();) {
				MessageItem it = (MessageItem) (i.next());
				x += it.getData(this).length + 2;
			}
		}
		return x;
	}
	
	/**
	* @return The last time we received a packet.
	*/
	public synchronized long lastReceivedPacketTime() {
		return timeLastReceivedPacket;
	}

	public synchronized long timeLastConnected() {
		return timeLastConnected;
	}

	public synchronized long timeLastRoutable() {
		return timeLastRoutable;
	}

	protected void maybeRekey() {
		long now = System.currentTimeMillis();
		boolean shouldDisconnect = false;
		boolean shouldReturn = false;
		boolean shouldRekey = false;
		long timeWhenRekeyingShouldOccur = 0;
		
		synchronized (this) {
			timeWhenRekeyingShouldOccur = timeLastRekeyed + FNPPacketMangler.SESSION_KEY_REKEYING_INTERVAL;
			shouldDisconnect = (timeWhenRekeyingShouldOccur + FNPPacketMangler.MAX_SESSION_KEY_REKEYING_DELAY < now) && isRekeying;
			shouldReturn = isRekeying || !isConnected;
			shouldRekey = (timeWhenRekeyingShouldOccur < now);
			if((!shouldRekey) && totalBytesExchangedWithCurrentTracker > FNPPacketMangler.AMOUNT_OF_BYTES_ALLOWED_BEFORE_WE_REKEY) {
				shouldRekey = true;
				timeWhenRekeyingShouldOccur = now;
			}
		}
		
		if(shouldDisconnect) {
			String time = TimeUtil.formatTime(FNPPacketMangler.MAX_SESSION_KEY_REKEYING_DELAY);
			System.err.println("The peer (" + this + ") has been asked to rekey " + time + " ago... force disconnect.");
			Logger.error(this, "The peer (" + this + ") has been asked to rekey " + time + " ago... force disconnect.");
			forceDisconnect(false);
		} else if (shouldReturn || hasLiveHandshake(now)) {
			return;
		} else if(shouldRekey) {
			synchronized(this) {
				isRekeying = true;
				sendHandshakeTime = now; // Immediately
				ctx = null;
			}
			Logger.normal(this, "We are asking for the key to be renewed (" + this.detectedPeer + ')');
		}
	}

	/**
	* @return The time this PeerNode was added to the node (persistent across restarts).
	*/
	public synchronized long getPeerAddedTime() {
		return peerAddedTime;
	}

	/**
	* @return The time elapsed since this PeerNode was added to the node, or the node started up.
	*/
	public synchronized long timeSinceAddedOrRestarted() {
		return System.currentTimeMillis() - timeAddedOrRestarted;
	}

	/**
	* Disconnected e.g. due to not receiving a packet for ages.
	* @param dumpMessageQueue If true, clear the messages-to-send queue.
	* @param dumpTrackers If true, dump the KeyTracker's.
	* @return True if the node was connected, false if it was not.
	*/
	public boolean disconnected(boolean dumpMessageQueue, boolean dumpTrackers) {
		final long now = System.currentTimeMillis();
		Logger.normal(this, "Disconnected " + this);
		node.usm.onDisconnect(this);
		node.peers.disconnected(this);
		boolean ret;
		synchronized(this) {
			ret = isConnected;
			// Force renegotiation.
			isConnected = false;
			isRoutable = false;
			isRekeying = false;
			// Prevent sending packets to the node until that happens.
			if(currentTracker != null)
				currentTracker.disconnected();
			if(previousTracker != null)
				previousTracker.disconnected();
			if(unverifiedTracker != null)
				unverifiedTracker.disconnected();
			if(dumpTrackers) {
				currentTracker = null;
				previousTracker = null;
				unverifiedTracker = null;
			}
			// Else DO NOT clear trackers, because hopefully it's a temporary connectivity glitch.
			sendHandshakeTime = now;
    		synchronized(this) {
    			timePrevDisconnect = timeLastDisconnect;
    			timeLastDisconnect = now;
    		}
			if(dumpMessageQueue) {
				synchronized(messagesToSendNow) {
					messagesToSendNow.clear();
				}
			}
		}
		node.lm.lostOrRestartedNode(this);
		setPeerNodeStatus(now);
		if(!dumpMessageQueue) {
			node.getTicker().queueTimedJob(new Runnable() {
				public void run() {
					if((!PeerNode.this.isConnected()) &&
							timeLastDisconnect == now)
						synchronized(PeerNode.this.messagesToSendNow) {
							PeerNode.this.messagesToSendNow.clear();
						}
				}
			}, CLEAR_MESSAGE_QUEUE_AFTER);
		}
		return ret;
	}
	
	private boolean forceDisconnectCalled = false;

	public void forceDisconnect(boolean purge) {
		Logger.error(this, "Forcing disconnect on " + this, new Exception("debug"));
		synchronized(this) {
			forceDisconnectCalled = true;
		}
		disconnected(purge, true); // always dump trackers, maybe dump messages
	}

	boolean forceDisconnectCalled() {
		return forceDisconnectCalled;
	}

	/**
	* Grab all queued Message's.
	* @return Null if no messages are queued, or an array of
	* Message's.
	*/
	public MessageItem[] grabQueuedMessageItems() {
		synchronized(messagesToSendNow) {
			if(messagesToSendNow.size() == 0)
				return null;
			MessageItem[] messages = new MessageItem[messagesToSendNow.size()];
			messages = (MessageItem[]) messagesToSendNow.toArray(messages);
			messagesToSendNow.clear();
			return messages;
		}
	}

	public void requeueMessageItems(MessageItem[] messages, int offset, int length, boolean dontLog) {
		requeueMessageItems(messages, offset, length, dontLog, "");
	}

	public void requeueMessageItems(MessageItem[] messages, int offset, int length, boolean dontLog, String reason) {
		// Will usually indicate serious problems
		if(!dontLog) {
			long now = System.currentTimeMillis();
			String rateLimitWrapper = "";
			boolean rateLimitLogging = false;
			if(messages.length > messageRequeueLogRateLimitThreshold) {
				rateLimitWrapper = " (log message rate limited)";
				if(nextMessageRequeueLogTime <= now) {
					nextMessageRequeueLogTime = now + messageRequeueLogRateLimitInterval;
				} else {
					rateLimitLogging = true;
				}
			}
			if(!rateLimitLogging) {
				String reasonWrapper = "";
				if(0 <= reason.length()) {
					reasonWrapper = " because of '" + reason + '\'';
				}
				Logger.normal(this, "Requeueing " + messages.length + " messages" + reasonWrapper + " on " + this + rateLimitWrapper);
			}
		}
		synchronized(messagesToSendNow) {
			for(int i = offset; i < offset + length; i++)
				if(messages[i] != null)
					messagesToSendNow.add(messages[i]);
		}
	}

	/**
	* @return The time at which we must send a packet, even if
	* it means it will only contains ack requests etc., or
	* Long.MAX_VALUE if we have no pending ack request/acks/etc.
	*/
	public synchronized long getNextUrgentTime() {
		long t = Long.MAX_VALUE;
		KeyTracker kt = currentTracker;
		if(kt != null)
			t = Math.min(t, kt.getNextUrgentTime());
		kt = previousTracker;
		if(kt != null)
			t = Math.min(t, kt.getNextUrgentTime());
		return t;
	}

	/**
	* @return The time at which we last sent a packet.
	*/
	public long lastSentPacketTime() {
		return timeLastSentPacket;
	}

	/**
	* @return True, if we are disconnected and it has been a
	* sufficient time period since we last sent a handshake
	* attempt.
	*/
	public boolean shouldSendHandshake() {
		long now = System.currentTimeMillis();
		boolean tempShouldSendHandshake = false;
		synchronized(this) {
			tempShouldSendHandshake = ((isRekeying || !isConnected()) && (handshakeIPs != null) && (now > sendHandshakeTime));
		}
		if(tempShouldSendHandshake && (hasLiveHandshake(now)))
			tempShouldSendHandshake = false;
		if(tempShouldSendHandshake) {
    		if(isBurstOnly()) {
    			synchronized(this) {
        			isBursting = true;
    			}
    			setPeerNodeStatus(System.currentTimeMillis());
    		} else
    			return true;
		}
		return tempShouldSendHandshake;
	}

	/**
	* Does the node have a live handshake in progress?
	* @param now The current time.
	*/
	public boolean hasLiveHandshake(long now) {
		KeyAgreementSchemeContext c = null;
		synchronized(this) {
			c = ctx;
		}
		if(c != null && logMINOR)
			Logger.minor(this, "Last used: " + (now - c.lastUsedTime()));
		return !((c == null) || (now - c.lastUsedTime() > Node.HANDSHAKE_TIMEOUT));
	}
	boolean firstHandshake = true;

	/**
	 * Set sendHandshakeTime, and return whether to fetch the ARK.
	 */
	protected synchronized boolean innerCalcNextHandshake(boolean successfulHandshakeSend, boolean dontFetchARK, long now) {
		if(isBurstOnly())
			return calcNextHandshakeBurstOnly(now);
		if(verifiedIncompatibleOlderVersion || verifiedIncompatibleNewerVersion || disableRouting) {
			// Let them know we're here, but have no hope of connecting
			sendHandshakeTime = now + Node.MIN_TIME_BETWEEN_VERSION_SENDS + node.random.nextInt(Node.RANDOMIZED_TIME_BETWEEN_VERSION_SENDS);
		} else if(invalidVersion() && !firstHandshake) {
			sendHandshakeTime = now + Node.MIN_TIME_BETWEEN_VERSION_PROBES + node.random.nextInt(Node.RANDOMIZED_TIME_BETWEEN_VERSION_PROBES);
		} else {
			sendHandshakeTime = now + Node.MIN_TIME_BETWEEN_HANDSHAKE_SENDS + node.random.nextInt(Node.RANDOMIZED_TIME_BETWEEN_HANDSHAKE_SENDS);
		}
		
		if(successfulHandshakeSend)
			firstHandshake = false;
		handshakeCount++;
		return ((handshakeCount == MAX_HANDSHAKE_COUNT) && !(verifiedIncompatibleOlderVersion || verifiedIncompatibleNewerVersion));
	}

	private synchronized boolean calcNextHandshakeBurstOnly(long now) {
		boolean fetchARKFlag = false;
		listeningHandshakeBurstCount++;
		if(isBurstOnly()) {
			if(listeningHandshakeBurstCount >= listeningHandshakeBurstSize) {
				listeningHandshakeBurstCount = 0;
				fetchARKFlag = true;
			}
		}
		if(listeningHandshakeBurstCount == 0) {  // 0 only if we just reset it above
			sendHandshakeTime = now + Node.MIN_TIME_BETWEEN_BURSTING_HANDSHAKE_BURSTS
				+ node.random.nextInt(Node.RANDOMIZED_TIME_BETWEEN_BURSTING_HANDSHAKE_BURSTS);
			listeningHandshakeBurstSize = Node.MIN_BURSTING_HANDSHAKE_BURST_SIZE
					+ node.random.nextInt(Node.RANDOMIZED_BURSTING_HANDSHAKE_BURST_SIZE);
			isBursting = false;
		} else {
			sendHandshakeTime = now + Node.MIN_TIME_BETWEEN_HANDSHAKE_SENDS
				+ node.random.nextInt(Node.RANDOMIZED_TIME_BETWEEN_HANDSHAKE_SENDS);
		}
		if(logMINOR) Logger.minor(this, "Next BurstOnly mode handshake in "+(sendHandshakeTime - now)+"ms for "+shortToString()+" (count: "+listeningHandshakeBurstCount+", size: "+listeningHandshakeBurstSize+ ')', new Exception("double-called debug"));
		return fetchARKFlag;
	}

	protected void calcNextHandshake(boolean successfulHandshakeSend, boolean dontFetchARK) {
		long now = System.currentTimeMillis();
		boolean fetchARKFlag = false;
		fetchARKFlag = innerCalcNextHandshake(successfulHandshakeSend, dontFetchARK, now);
		setPeerNodeStatus(now);  // Because of isBursting being set above and it can't hurt others
		// Don't fetch ARKs for peers we have verified (through handshake) to be incompatible with us
		if(fetchARKFlag && !dontFetchARK) {
			long arkFetcherStartTime1 = System.currentTimeMillis();
			startARKFetcher();
			long arkFetcherStartTime2 = System.currentTimeMillis();
			if((arkFetcherStartTime2 - arkFetcherStartTime1) > 500)
				Logger.normal(this, "arkFetcherStartTime2 is more than half a second after arkFetcherStartTime1 (" + (arkFetcherStartTime2 - arkFetcherStartTime1) + ") working on " + shortToString());
		}
	}

	/** If the outgoingMangler allows bursting, we still don't want to burst *all the time*, because it may be mistaken
	 * in its detection of a port forward. So from time to time we will aggressively handshake anyway. This flag is set
	 * once every UPDATE_BURST_NOW_PERIOD. */
	private boolean burstNow;
	private long timeSetBurstNow;
	static final int UPDATE_BURST_NOW_PERIOD = 5*60*1000;
	/** Burst only 19 in 20 times if definitely port forwarded. Save entropy by writing this as 20 not 0.95. */
	static final int P_BURST_IF_DEFINITELY_FORWARDED = 20;
	
	public boolean isBurstOnly() {
		int status = outgoingMangler.getConnectivityStatus();
		if(status == AddressTracker.DONT_KNOW) return false;
		if(status == AddressTracker.DEFINITELY_NATED) return false;
		
		// For now. FIXME try it with a lower probability when we're sure that the packet-deltas mechanisms works.
		if(status == AddressTracker.MAYBE_PORT_FORWARDED) return false;
		long now = System.currentTimeMillis();
		if(now - timeSetBurstNow > UPDATE_BURST_NOW_PERIOD) {
			burstNow = (node.random.nextInt(P_BURST_IF_DEFINITELY_FORWARDED) == 0);
			timeSetBurstNow = now;
		}
		return burstNow;
	}

	/**
	* Call this method when a handshake request has been
	* sent.
	*/
	public void sentHandshake() {
		if(logMINOR)
			Logger.minor(this, "sentHandshake(): " + this);
		calcNextHandshake(true, false);
	}

	/**
	* Call this method when a handshake request could not be sent (i.e. no IP address available)
	* sent.
	*/
	public void couldNotSendHandshake() {
		if(logMINOR)
			Logger.minor(this, "couldNotSendHandshake(): " + this);
		calcNextHandshake(false, false);
	}

	/**
	* @return The maximum time between received packets.
	*/
	public int maxTimeBetweenReceivedPackets() {
		return Node.MAX_PEER_INACTIVITY;
	}

	/**
	* Low-level ping this node.
	* @return True if we received a reply inside 2000ms.
	* (If we have heavy packet loss, it can take that long to resend).
	*/
	public boolean ping(int pingID) throws NotConnectedException {
		Message ping = DMT.createFNPPing(pingID);
		node.usm.send(this, ping, null);
		Message msg;
		try {
			msg = node.usm.waitFor(MessageFilter.create().setTimeout(2000).setType(DMT.FNPPong).setField(DMT.PING_SEQNO, pingID), null);
		} catch(DisconnectedException e) {
			throw new NotConnectedException("Disconnected while waiting for pong");
		}
		return msg != null;
	}

	/**
	* Decrement the HTL (or not), in accordance with our 
	* probabilistic HTL rules.
	* @param htl The old HTL.
	* @return The new HTL.
	*/
	public short decrementHTL(short htl) {
		short max = node.maxHTL();
		if(htl > max)
			htl = max;
		if(htl <= 0)
			htl = 1;
		if(htl == max) {
			if(decrementHTLAtMaximum && !node.disableProbabilisticHTLs)
				htl--;
			return htl;
		}
		if(htl == 1) {
			if(decrementHTLAtMinimum && !node.disableProbabilisticHTLs)
				htl--;
			return htl;
		}
		htl--;
		return htl;
	}

	/**
	* Enqueue a message to be sent to this node and wait up to a minute for it to be transmitted.
	*/
	public void sendSync(Message req, ByteCounter ctr) throws NotConnectedException {
		SyncMessageCallback cb = new SyncMessageCallback();
		sendAsync(req, cb, 0, ctr);
		cb.waitForSend(60 * 1000);
	}

	private class SyncMessageCallback implements AsyncMessageCallback {

		private boolean done = false;
		private boolean disconnected = false;

		public synchronized void waitForSend(long maxWaitInterval) throws NotConnectedException {
			long now = System.currentTimeMillis();
			long end = now + maxWaitInterval;
			while((now = System.currentTimeMillis()) < end) {
				if(done) {
					if(disconnected)
						throw new NotConnectedException();
					return;
				}
				int waitTime = (int) (Math.min(end - now, Integer.MAX_VALUE));
				try {
					wait(waitTime);
				} catch(InterruptedException e) {
				// Ignore
				}
			}
			Logger.error(this, "Waited too long for a blocking send on " + this + " for " + PeerNode.this, new Exception("error"));
		}

		public void acknowledged() {
			synchronized(this) {
				if(!done)
					Logger.error(this, "Acknowledged but not sent?! on " + this + " for " + PeerNode.this);
				else
					return;
				done = true;
				notifyAll();
			}
		}

		public void disconnected() {
			synchronized(this) {
				done = true;
				disconnected = true;
				notifyAll();
			}
		}

		public void fatalError() {
			synchronized(this) {
				done = true;
				notifyAll();
			}
		}

		public void sent() {
			synchronized(this) {
				done = true;
				notifyAll();
			}
		}
	}

	/**
	* Update the Location to a new value.
	*/
	public void updateLocation(double newLoc) {
		logMINOR = Logger.shouldLog(Logger.MINOR, PeerNode.class);
		if(newLoc < 0.0 || newLoc > 1.0) {
			Logger.error(this, "Invalid location update for " + this, new Exception("error"));
			// Ignore it
			return;
		}
		synchronized(this) {
			currentLocation = newLoc;
		}
		node.peers.writePeers();
	}

	/**
	* Should we reject a swap request?
	*/
	public boolean shouldRejectSwapRequest() {
		long now = System.currentTimeMillis();
		synchronized(this) {
			if(timeLastReceivedSwapRequest > 0) {
				long timeSinceLastTime = now - timeLastReceivedSwapRequest;
				swapRequestsInterval.report(timeSinceLastTime);
				double averageInterval = swapRequestsInterval.currentValue();
				if(averageInterval < Node.MIN_INTERVAL_BETWEEN_INCOMING_SWAP_REQUESTS) {
					double p =
						(Node.MIN_INTERVAL_BETWEEN_INCOMING_SWAP_REQUESTS - averageInterval) /
						Node.MIN_INTERVAL_BETWEEN_INCOMING_SWAP_REQUESTS;
					return node.random.nextDouble() < p;
				} else
					return false;

			}
			timeLastReceivedSwapRequest = now;
		}
		return false;
	}

	/**
	* IP on the other side appears to have changed...
	* @param newPeer The new address of the peer.
	*/
	public void changedIP(Peer newPeer) {
		setDetectedPeer(newPeer);
	}

	private void setDetectedPeer(Peer newPeer) {
		// Only clear lastAttemptedHandshakeIPUpdateTime if we have a new IP.
		// Also, we need to call .equals() to propagate any DNS lookups that have been done if the two have the same domain.
		synchronized(this) {
			if((newPeer != null) && ((detectedPeer == null) || !detectedPeer.equals(newPeer))) {
				this.detectedPeer = newPeer;
				this.lastAttemptedHandshakeIPUpdateTime = 0;
				if(!isConnected)
					return;
				// Prevent leak by clearing, *but keep the current handshake*
				Object o = jfkNoncesSent.get(newPeer);
				jfkNoncesSent.clear();
				jfkNoncesSent.put(newPeer, o);
			} else
				return;
		}
		sendIPAddressMessage();
	}

	/**
	* @return The current primary KeyTracker, or null if we
	* don't have one.
	*/
	public synchronized KeyTracker getCurrentKeyTracker() {
		return currentTracker;
	}

	/**
	* @return The previous primary KeyTracker, or null if we
	* don't have one.
	*/
	public synchronized KeyTracker getPreviousKeyTracker() {
		return previousTracker;
	}

	/**
	* @return The unverified KeyTracker, if any, or null if we
	* don't have one. The caller MUST call verified(KT) if a
	* decrypt succeeds with this KT.
	*/
	public synchronized KeyTracker getUnverifiedKeyTracker() {
		return unverifiedTracker;
	}

	/**
	* @return short version of toString()
	* *** Note that this is not synchronized! It is used by logging in code paths that
	* will deadlock if it is synchronized! ***
	*/
	public String shortToString() {
		return super.toString() + '@' + detectedPeer + '@' + HexUtil.bytesToHex(identity);
	}

	public String toString() {
		// FIXME?
		return shortToString();
	}

	/**
	* Update timeLastReceivedPacket
	* @throws NotConnectedException 
	* @param dontLog If true, don't log an error or throw an exception if we are not connected. This
	* can be used in handshaking when the connection hasn't been verified yet.
	*/
	void receivedPacket(boolean dontLog) {
		synchronized(this) {
			if((!isConnected) && (!dontLog)) {
				if((unverifiedTracker == null) && (currentTracker == null))
					Logger.error(this, "Received packet while disconnected!: " + this, new Exception("error"));
				else
					if(logMINOR)
						Logger.minor(this, "Received packet while disconnected on " + this + " - recently disconnected() ?");
			}
		}
		long now = System.currentTimeMillis();
		synchronized(this) {
			timeLastReceivedPacket = now;
		}
	}

	/**
	* Update timeLastSentPacket
	*/
	public void sentPacket() {
		timeLastSentPacket = System.currentTimeMillis();
	}

	public synchronized KeyAgreementSchemeContext getKeyAgreementSchemeContext() {
		return ctx;
	}

	public synchronized void setKeyAgreementSchemeContext(KeyAgreementSchemeContext ctx2) {
		this.ctx = ctx2;
		if(logMINOR)
			Logger.minor(this, "setKeyAgreementSchemeContext(" + ctx2 + ") on " + this);
	}

	/**
	* Called when we have completed a handshake, and have a new session key.
	* Creates a new tracker and demotes the old one. Deletes the old one if
	* the bootID isn't recognized, since if the node has restarted we cannot
	* recover old messages. In more detail:
	* <ul>
	* <li>Process the new noderef (check if it's valid, pick up any new information etc).</li>
	* <li>Handle version conflicts (if the node is too old, or we are too old, we mark it as 
	* non-routable, but some messages will still be exchanged e.g. Update Over Mandatory stuff).</li>
	* <li>Deal with key trackers (if we just got message 4, the new key tracker becomes current; 
	* if we just got message 3, it's possible that our message 4 will be lost in transit, so we 
	* make the new tracker unverified. It will be promoted to current if we get a packet on it..
	* if the node has restarted, we dump the old key trackers, otherwise current becomes previous).</li>
	* <li>Complete the connection process: update the node's status, send initial messages, update
	* the last-received-packet timestamp, etc.</li>
	* @param thisBootID The boot ID of the peer we have just connected to.
	* This is simply a random number regenerated on every startup of the node.
	* We use it to determine whether the node has restarted since we last saw 
	* it.
	* @param data Byte array from which to read the new noderef.
	* @param offset Offset to start reading at.
	* @param length Number of bytes to read.
	* @param encKey The new session key.
	* @param replyTo The IP the handshake came in on.
	* @return True unless we rejected the handshake, or it failed to parse.
	*/
	public boolean completedHandshake(long thisBootID, byte[] data, int offset, int length, BlockCipher encCipher, byte[] encKey, Peer replyTo, boolean unverified) {
		logMINOR = Logger.shouldLog(Logger.MINOR, PeerNode.class);
		long now = System.currentTimeMillis();

		// Update sendHandshakeTime; don't send another handshake for a while.
		// If unverified, "a while" determines the timeout; if not, it's just good practice to avoid a race below.
		calcNextHandshake(true, true);
		stopARKFetcher();
		try {
			// First, the new noderef
			processNewNoderef(data, offset, length);
		} catch(FSParseException e1) {
			synchronized(this) {
				bogusNoderef = true;
				// Disconnect, something broke
				isConnected = false;
			}
			Logger.error(this, "Failed to parse new noderef for " + this + ": " + e1, e1);
			node.peers.disconnected(this);
			return false;
		}
		boolean routable = true;
		boolean newer = false;
		boolean older = false;
		if(bogusNoderef) {
			Logger.normal(this, "Not connecting to " + this + " - bogus noderef");
			routable = false;
		} else if(reverseInvalidVersion()) {
			try {
				node.setNewestPeerLastGoodVersion(Version.getArbitraryBuildNumber(getLastGoodVersion()));
			} catch(NumberFormatException e) {
			// ignore
			}
			Logger.normal(this, "Not connecting to " + this + " - reverse invalid version " + Version.getVersionString() + " for peer's lastGoodversion: " + getLastGoodVersion());
			newer = true;
		} else
			newer = false;
		if(forwardInvalidVersion()) {
			Logger.normal(this, "Not connecting to " + this + " - invalid version " + getVersion());
			older = true;
			routable = false;
		} else
			older = false;
		KeyTracker newTracker = new KeyTracker(this, encCipher, encKey);
		changedIP(replyTo);
		boolean bootIDChanged = false;
		boolean wasARekey = false;
		KeyTracker oldPrev = null;
		KeyTracker oldCur = null;
		KeyTracker prev = null;
		synchronized(this) {
			handshakeCount = 0;
			bogusNoderef = false;
			// Don't reset the uptime if we rekey
			if(!isConnected) {
				connectedTime = now;
				sentInitialMessages = false;
			} else
				wasARekey = true;
			isConnected = true;
			disableRouting = disableRoutingHasBeenSetLocally || disableRoutingHasBeenSetRemotely;
			isRoutable = routable;
			verifiedIncompatibleNewerVersion = newer;
			verifiedIncompatibleOlderVersion = older;
			bootIDChanged = (thisBootID != this.bootID);
			if(bootIDChanged && logMINOR)
				Logger.minor(this, "Changed boot ID from " + bootID + " to " + thisBootID + " for " + getPeer());
			this.bootID = thisBootID;
			if(bootIDChanged) {
				oldPrev = previousTracker;
				oldCur = currentTracker;
				previousTracker = null;
				currentTracker = null;
				// Messages do not persist across restarts.
				// Generally they would be incomprehensible, anything that isn't should be sent as
				// connection initial messages by maybeOnConnect().
				synchronized(messagesToSendNow) {
					messagesToSendNow.clear();
				}
			} // else it's a rekey
			if(unverified) {
				if(unverifiedTracker != null) {
					// Keep the old unverified tracker if possible.
					if(previousTracker == null)
						previousTracker = unverifiedTracker;
				}
				unverifiedTracker = newTracker;
				if(currentTracker == null || currentTracker.isDeprecated())
					isConnected = false;
			} else {
				prev = currentTracker;
				previousTracker = prev;
				currentTracker = newTracker;
				unverifiedTracker = null;
				neverConnected = false;
				peerAddedTime = 0;  // don't store anymore
			}
			ctx = null;
			isRekeying = false;
			timeLastRekeyed = now;
			totalBytesExchangedWithCurrentTracker = 0;
		}

		if(bootIDChanged) {
			node.lm.lostOrRestartedNode(this);
			node.usm.onRestart(this);
		}
		if(oldPrev != null)
			oldPrev.completelyDeprecated(newTracker);
		if(oldCur != null)
			oldCur.completelyDeprecated(newTracker);
		if(prev != null)
			prev.deprecated();
		Logger.normal(this, "Completed handshake with " + this + " on " + replyTo + " - current: " + currentTracker +
			" old: " + previousTracker + " unverified: " + unverifiedTracker + " bootID: " + thisBootID + " for " + shortToString());

		// Received a packet
		receivedPacket(unverified);

		if(newer || older || !isConnected())
			node.peers.disconnected(this);
		else if(!wasARekey) {
			node.peers.addConnectedPeer(this);
			onConnect();
		}
		
		setPeerNodeStatus(now);
		return true;
	}

	public long getBootID() {
		return bootID;
	}
	private volatile Object arkFetcherSync = new Object();

	void startARKFetcher() {
		// FIXME any way to reduce locking here?
		synchronized(arkFetcherSync) {
			if(myARK == null) {
				Logger.minor(this, "No ARK for " + this + " !!!!");
				return;
			}
			Logger.minor(this, "Starting ARK fetcher for " + this + " : " + myARK);
			if(arkFetcher == null)
				arkFetcher = node.clientCore.uskManager.subscribeContent(myARK, this, true, node.arkFetcherContext, RequestStarter.IMMEDIATE_SPLITFILE_PRIORITY_CLASS, node);
		}
	}

	protected void stopARKFetcher() {
		Logger.minor(this, "Stopping ARK fetcher for " + this + " : " + myARK);
		// FIXME any way to reduce locking here?
		synchronized(arkFetcherSync) {
			if(arkFetcher == null)
				return;
			node.clientCore.uskManager.unsubscribeContent(myARK, this.arkFetcher, true);
			arkFetcher = null;
		}
	}
	boolean sentInitialMessages;

	void maybeSendInitialMessages() {
		synchronized(this) {
			if(sentInitialMessages)
				return;
			if(currentTracker != null)
				sentInitialMessages = true;
			else
				return;
			if(unverifiedTracker != null)
				return;
		}

		sendInitialMessages();
	}

	/**
	* Send any high level messages that need to be sent on connect.
	*/
	protected void sendInitialMessages() {
		Message locMsg = DMT.createFNPLocChangeNotification(node.lm.getLocation());
		Message ipMsg = DMT.createFNPDetectedIPAddress(detectedPeer);
		Message timeMsg = DMT.createFNPTime(System.currentTimeMillis());
		Message packetsMsg = createSentPacketsMessage();
		Message dRouting = DMT.createRoutingStatus(!disableRoutingHasBeenSetLocally);

		try {
			if(isRoutable())
				sendAsync(locMsg, null, 0, null);
			sendAsync(ipMsg, null, 0, null);
			sendAsync(timeMsg, null, 0, null);
			sendAsync(packetsMsg, null, 0, null);
			sendAsync(dRouting, null, 0, null);
		} catch(NotConnectedException e) {
			Logger.error(this, "Completed handshake with " + getPeer() + " but disconnected (" + isConnected + ':' + currentTracker + "!!!: " + e, e);
		}

		if(node.nodeUpdater != null)
			node.nodeUpdater.maybeSendUOMAnnounce(this);
	}

	private Message createSentPacketsMessage() {
		long[][] sent = getSentPacketTimesHashes();
		long[] times = sent[0];
		long[] hashes = sent[1];
		long now = System.currentTimeMillis();
		long horizon = now - Integer.MAX_VALUE;
		int skip = 0;
		for(int i = 0; i < times.length; i++) {
			long time = times[i];
			if(time < horizon)
				skip++;
			else
				break;
		}
		int[] timeDeltas = new int[times.length - skip];
		for(int i = skip; i < times.length; i++)
			timeDeltas[i] = (int) (now - times[i]);
		if(skip != 0) {
			// Unlikely code path, only happens with very long uptime.
    		// Trim hashes too.
			long[] newHashes = new long[hashes.length - skip];
			System.arraycopy(hashes, skip, newHashes, 0, hashes.length - skip);
		}
		return DMT.createFNPSentPackets(timeDeltas, hashes, now);
	}

	private void sendIPAddressMessage() {
		Message ipMsg = DMT.createFNPDetectedIPAddress(detectedPeer);
		try {
			sendAsync(ipMsg, null, 0, null);
		} catch(NotConnectedException e) {
			Logger.normal(this, "Sending IP change message to " + this + " but disconnected: " + e, e);
		}
	}

	/**
	* Called when a packet is successfully decrypted on a given
	* KeyTracker for this node. Will promote the unverifiedTracker
	* if necessary.
	*/
	public void verified(KeyTracker tracker) {
		long now = System.currentTimeMillis();
		KeyTracker completelyDeprecatedTracker;
		synchronized(this) {
			if(tracker == unverifiedTracker && !tracker.isDeprecated()) {
				if(logMINOR)
					Logger.minor(this, "Promoting unverified tracker " + tracker + " for " + getPeer());
				completelyDeprecatedTracker = previousTracker;
				previousTracker = currentTracker;
				if(previousTracker != null)
					previousTracker.deprecated();
				currentTracker = unverifiedTracker;
				unverifiedTracker = null;
				isConnected = true;
				neverConnected = false;
				peerAddedTime = 0;  // don't store anymore
				ctx = null;
			} else
				return;
		}
		maybeSendInitialMessages();
		setPeerNodeStatus(now);
		node.peers.addConnectedPeer(this);
		if(completelyDeprecatedTracker != null)
			completelyDeprecatedTracker.completelyDeprecated(tracker);
	}

	private synchronized boolean invalidVersion() {
		return bogusNoderef || forwardInvalidVersion() || reverseInvalidVersion();
	}

	private synchronized boolean forwardInvalidVersion() {
		return !Version.checkGoodVersion(version);
	}

	private synchronized boolean reverseInvalidVersion() {
		return !Version.checkArbitraryGoodVersion(Version.getVersionString(), lastGoodVersion);
	}

	public boolean publicInvalidVersion() {
		return verifiedIncompatibleOlderVersion;
	}

	public synchronized boolean publicReverseInvalidVersion() {
		return verifiedIncompatibleNewerVersion;
	}
	
	public synchronized boolean dontRoute() {
		return disableRouting;
	}

	/**
	* Process a new nodereference, in compressed form.
	* The identity must not change, or we throw.
	*/
	private void processNewNoderef(byte[] data, int offset, int length) throws FSParseException {
		SimpleFieldSet fs = compressedNoderefToFieldSet(data, offset, length);
		processNewNoderef(fs, false);
	}

	static SimpleFieldSet compressedNoderefToFieldSet(byte[] data, int offset, int length) throws FSParseException {
		if(length == 0)
			throw new FSParseException("Too short");
		// Firstly, is it compressed?
		if(data[offset] == 1) {
			// Gzipped
			Inflater i = new Inflater();
			i.setInput(data, offset + 1, length - 1);
			byte[] output = new byte[4096];
			int outputPointer = 1;
			while(true) {
				try {
					int x = i.inflate(output, outputPointer, output.length - outputPointer);
					if(x == output.length - outputPointer) {
						// More to decompress!
						byte[] newOutput = new byte[output.length * 2];
						System.arraycopy(output, 0, newOutput, 0, output.length);
						continue;
					} else {
						// Finished
						data = output;
						offset = 0;
						length = outputPointer + x;
						break;
					}
				} catch(DataFormatException e) {
					throw new FSParseException("Invalid compressed data");
				}
			}
		}
		if(logMINOR)
			Logger.minor(PeerNode.class, "Reference: " + new String(data, offset, length) + '(' + length + ')');
		// Now decode it
		ByteArrayInputStream bais = new ByteArrayInputStream(data, offset + 1, length - 1);
		InputStreamReader isr;
		try {
			isr = new InputStreamReader(bais, "UTF-8");
		} catch(UnsupportedEncodingException e1) {
			throw new Error(e1);
		}
		BufferedReader br = new BufferedReader(isr);
		try {
			return new SimpleFieldSet(br, false, true);
		} catch(IOException e) {
			FSParseException ex = new FSParseException("Impossible: " + e);
			ex.initCause(e);
			throw ex;
		}
	}

	/**
	* Process a new nodereference, as a SimpleFieldSet.
	*/
	private void processNewNoderef(SimpleFieldSet fs, boolean forARK) throws FSParseException {
		if(logMINOR)
			Logger.minor(this, "Parsing: \n" + fs);
		boolean changedAnything = innerProcessNewNoderef(fs, forARK);
		if(changedAnything)
			node.peers.writePeers();
	}

	/**
	* The synchronized part of processNewNoderef 
	* @throws FSParseException
	*/
	protected synchronized boolean innerProcessNewNoderef(SimpleFieldSet fs, boolean forARK) throws FSParseException {
		boolean changedAnything = false;
		if(node.testnetEnabled != Fields.stringToBool(fs.get("testnet"), true)) {
			String err = "Preventing connection to node " + detectedPeer + " - peer.testnet=" + !node.testnetEnabled + '(' + fs.get("testnet") + ") but node.testnet=" + node.testnetEnabled;
			Logger.error(this, err);
			throw new FSParseException(err);
		}
		String newVersion = fs.get("version");
		if(newVersion == null) {
			// Version may be ommitted for an ARK.
			if(!forARK)
				throw new FSParseException("No version");
		} else {
			if(!newVersion.equals(version))
				changedAnything = true;
			version = newVersion;
			Version.seenVersion(newVersion);
		}
		lastGoodVersion = fs.get("lastGoodVersion");

		updateShouldDisconnectNow();

		String locationString = fs.get("location");
		if(locationString == null) {
			// Location WILL be ommitted for an ARK.
			if(!forARK)
				throw new FSParseException("No location");
		} else {
			try {
				double newLoc = Location.getLocation(locationString);
				if(!Location.equals(newLoc, currentLocation)) {
					changedAnything = true;
					currentLocation = newLoc;
				}
			} catch(FSParseException e) {
				// Location is optional, we will wait for FNPLocChangeNotification
				if(logMINOR)
					Logger.minor(this, "Invalid or null location, waiting for FNPLocChangeNotification: " + e);
			}
		}
		Vector oldNominalPeer = nominalPeer;

		if(nominalPeer == null)
			nominalPeer = new Vector();
		nominalPeer.removeAllElements();

		Peer[] oldPeers = (Peer[]) nominalPeer.toArray(new Peer[nominalPeer.size()]);

		try {
			String physical[] = fs.getAll("physical.udp");
			if(physical == null) {
				try {
					Peer p = new Peer(fs.get("physical.udp"), true, true);
					nominalPeer.addElement(p);
				} catch(HostnameSyntaxException e) {
					Logger.error(this, "Invalid hostname or IP Address syntax error while parsing new peer reference: " + fs.get("physical.udp"));
				}
			} else {
				for(int i = 0; i < physical.length; i++) {
					Peer p;
					try {
						p = new Peer(physical[i], true, true);
					} catch(HostnameSyntaxException e) {
						Logger.error(this, "Invalid hostname or IP Address syntax error while parsing new peer reference: " + physical[i]);
						continue;
					}
					if(!nominalPeer.contains(p)) {
						if(oldNominalPeer.contains(p)) {
						// Do nothing
				    		// .contains() will .equals() on each, and equals() will propagate the looked-up IP if necessary.
				    		// This is obviously O(n^2), but it doesn't matter, there will be very few peers.
						}
						nominalPeer.addElement(p);
					}
				}
			}
		} catch(Exception e1) {
			throw new FSParseException(e1);
		}

		if(!Arrays.equals(oldPeers, nominalPeer.toArray(new Peer[nominalPeer.size()]))) {
			changedAnything = true;
			lastAttemptedHandshakeIPUpdateTime = 0;
			// Clear nonces to prevent leak. Will kill any in-progress connect attempts, but that is okay because
			// either we got an ARK which changed our peers list, or we just connected.
			jfkNoncesSent.clear();
		}

		// DO NOT change detectedPeer !!!
		// The given physical.udp may be WRONG!!!

		// In future, ARKs may support automatic transition when the ARK key is changed.
		// So parse it anyway. If it fails, no big loss; it won't even log an error.

		if(logMINOR)
			Logger.minor(this, "Parsed successfully; changedAnything = " + changedAnything);

		int[] newNegTypes = fs.getIntArray("auth.negTypes");
		if(newNegTypes == null || newNegTypes.length == 0)
			newNegTypes = new int[]{0};
		if(!Arrays.equals(negTypes, newNegTypes)) {
			changedAnything = true;
			negTypes = newNegTypes;
		}

		if(parseARK(fs, false))
			changedAnything = true;
		return changedAnything;
	}

	/**
	* Send a payload-less packet on either key if necessary.
	* @throws PacketSequenceException If there is an error sending the packet
	* caused by a sequence inconsistency. 
	*/
	public void sendAnyUrgentNotifications() throws PacketSequenceException {
		if(logMINOR)
			Logger.minor(this, "sendAnyUrgentNotifications");
		long now = System.currentTimeMillis();
		KeyTracker cur,
		 prev;
		synchronized(this) {
			cur = currentTracker;
			prev = previousTracker;
		}
		KeyTracker tracker = cur;
		if(tracker != null) {
			long t = tracker.getNextUrgentTime();
			if(t < now) {
				try {
					outgoingMangler.processOutgoing(null, 0, 0, tracker, 0);
				} catch(NotConnectedException e) {
				// Ignore
				} catch(KeyChangedException e) {
				// Ignore
				} catch(WouldBlockException e) {
				// Impossible, ignore
				}
			}
		}
		tracker = prev;
		if(tracker != null) {
			long t = tracker.getNextUrgentTime();
			if(t < now)
				try {
					outgoingMangler.processOutgoing(null, 0, 0, tracker, 0);
				} catch(NotConnectedException e) {
				// Ignore
				} catch(KeyChangedException e) {
				// Ignore
				} catch(WouldBlockException e) {
					Logger.error(this, "Impossible: " + e, e);
				}
		}
	}

	public abstract PeerNodeStatus getStatus();

	public String getTMCIPeerInfo() {
		long now = System.currentTimeMillis();
		int idle = -1;
		synchronized(this) {
			idle = (int) ((now - timeLastReceivedPacket) / 1000);
		}
		if((getPeerNodeStatus() == PeerManager.PEER_NODE_STATUS_NEVER_CONNECTED) && (getPeerAddedTime() > 1))
			idle = (int) ((now - getPeerAddedTime()) / 1000);
		return String.valueOf(getPeer()) + '\t' + getIdentityString() + '\t' + getLocation() + '\t' + getPeerNodeStatusString() + '\t' + idle;
	}

	public String getFreevizOutput() {
		return getStatus().toString() + '|' + Base64.encode(identity);
	}

	public synchronized String getVersion() {
		return version;
	}

	private synchronized String getLastGoodVersion() {
		return lastGoodVersion;
	}

	public String getSimpleVersion() {
		return String.valueOf(Version.getArbitraryBuildNumber(getVersion()));
	}

	/**
	* Write the peer's noderef to disk
	*/
	public void write(Writer w) throws IOException {
		SimpleFieldSet fs = exportFieldSet();
		SimpleFieldSet meta = exportMetadataFieldSet();
		if(!meta.isEmpty())
			fs.put("metadata", meta);
		fs.writeTo(w);
	}

	/**
	* Export metadata about the node as a SimpleFieldSet
	*/
	public synchronized SimpleFieldSet exportMetadataFieldSet() {
		SimpleFieldSet fs = new SimpleFieldSet(true);
		if(detectedPeer != null)
			fs.putSingle("detected.udp", detectedPeer.toStringPrefNumeric());
		if(lastReceivedPacketTime() > 0)
			fs.putSingle("timeLastReceivedPacket", Long.toString(timeLastReceivedPacket));
		if(timeLastConnected() > 0)
			fs.putSingle("timeLastConnected", Long.toString(timeLastConnected));
		if(timeLastRoutable() > 0)
			fs.putSingle("timeLastRoutable", Long.toString(timeLastRoutable));
		if(getPeerAddedTime() > 0)
			fs.putSingle("peerAddedTime", Long.toString(peerAddedTime));
		if(neverConnected)
			fs.putSingle("neverConnected", "true");
		if(hadRoutableConnectionCount > 0)
			fs.putSingle("hadRoutableConnectionCount", Long.toString(hadRoutableConnectionCount));
		if(routableConnectionCheckCount > 0)
			fs.putSingle("routableConnectionCheckCount", Long.toString(routableConnectionCheckCount));
		return fs;
	}

	/**
	* Export volatile data about the node as a SimpleFieldSet
	*/
	public SimpleFieldSet exportVolatileFieldSet() {
		SimpleFieldSet fs = new SimpleFieldSet(true);
		long now = System.currentTimeMillis();
		synchronized(this) {
			fs.putSingle("averagePingTime", Double.toString(averagePingTime()));
			long idle = now - lastReceivedPacketTime();
			if(idle > (60 * 1000) && -1 != lastReceivedPacketTime())  // 1 minute

				fs.putSingle("idle", Long.toString(idle));
			if(peerAddedTime > 1)
				fs.putSingle("peerAddedTime", Long.toString(peerAddedTime));
			fs.putSingle("lastRoutingBackoffReason", lastRoutingBackoffReason);
			fs.putSingle("routingBackoffPercent", Double.toString(backedOffPercent.currentValue() * 100));
			fs.putSingle("routingBackoff", Long.toString((Math.max(routingBackedOffUntil - now, 0))));
			fs.putSingle("routingBackoffLength", Integer.toString(routingBackoffLength));
			fs.putSingle("overloadProbability", Double.toString(getPRejected() * 100));
			fs.putSingle("percentTimeRoutableConnection", Double.toString(getPercentTimeRoutableConnection() * 100));
			fs.putSingle("totalBytesIn", Long.toString(totalBytesIn));
			fs.putSingle("totalBytesOut", Long.toString(totalBytesOut));
		}
		fs.putSingle("status", getPeerNodeStatusString());
		return fs;
	}

	/**
	* Export the peer's noderef as a SimpleFieldSet
	*/
	public synchronized SimpleFieldSet exportFieldSet() {
		SimpleFieldSet fs = new SimpleFieldSet(true);
		if(getLastGoodVersion() != null)
			fs.putSingle("lastGoodVersion", lastGoodVersion);
		for(int i = 0; i < nominalPeer.size(); i++)
			fs.putAppend("physical.udp", nominalPeer.get(i).toString());
		fs.put("auth.negTypes", negTypes);
		fs.putSingle("identity", getIdentityString());
		fs.putSingle("location", Double.toString(currentLocation));
		fs.putSingle("testnet", Boolean.toString(testnetEnabled));
		fs.putSingle("version", version);
		if(peerCryptoGroup != null)
			fs.put("dsaGroup", peerCryptoGroup.asFieldSet());
		if(peerPubKey != null)
			fs.put("dsaPubKey", peerPubKey.asFieldSet());
		if(myARK != null) {
			// Decrement it because we keep the number we would like to fetch, not the last one fetched.
			fs.putSingle("ark.number", Long.toString(myARK.suggestedEdition - 1));
			fs.putSingle("ark.pubURI", myARK.getBaseSSK().toString(false, false));
		}
		fs.put("opennet", isOpennet());
		return fs;
	}

	public abstract boolean isOpennet();

	/**
	* @return The time at which we last connected (or reconnected).
	*/
	public synchronized long timeLastConnectionCompleted() {
		return connectedTime;
	}

	/**
	* Requeue ResendPacketItem[]s if they are not sent.
	* @param resendItems
	*/
	public void requeueResendItems(Vector resendItems) {
		KeyTracker cur,
		 prev,
		 unv;
		synchronized(this) {
			cur = currentTracker;
			prev = previousTracker;
			unv = unverifiedTracker;
		}
		for(int i = 0; i < resendItems.size(); i++) {
			ResendPacketItem item = (ResendPacketItem) resendItems.get(i);
			if(item.pn != this)
				throw new IllegalArgumentException("item.pn != this!");
			KeyTracker kt = cur;
			if((kt != null) && (item.kt == kt)) {
				kt.resendPacket(item.packetNumber);
				continue;
			}
			kt = prev;
			if((kt != null) && (item.kt == kt)) {
				kt.resendPacket(item.packetNumber);
				continue;
			}
			kt = unv;
			if((kt != null) && (item.kt == kt)) {
				kt.resendPacket(item.packetNumber);
				continue;
			}
			// Doesn't match any of these, need to resend the data
			kt = cur == null ? unv : cur;
			if(kt == null) {
				Logger.error(this, "No tracker to resend packet " + item.packetNumber + " on");
				continue;
			}
			MessageItem mi = new MessageItem(item.buf, item.callbacks, true, 0, null);
			requeueMessageItems(new MessageItem[]{mi}, 0, 1, true);
		}
	}

	public boolean equals(Object o) {
		if(o == this)
			return true;
		if(o instanceof PeerNode) {
			PeerNode pn = (PeerNode) o;
			return Arrays.equals(pn.identity, identity);
		} else
			return false;
	}

	public int hashCode() {
		return hashCode;
	}

	public boolean isRoutingBackedOff() {
		long now = System.currentTimeMillis();
		synchronized(this) {
			return now < routingBackedOffUntil;
		}
	}
	long routingBackedOffUntil = -1;
	/** Initial nominal routing backoff length */
	static final int INITIAL_ROUTING_BACKOFF_LENGTH = 1000;  // 1 second
	/** How much to multiply by during fast routing backoff */

	static final int BACKOFF_MULTIPLIER = 2;
	/** Maximum upper limit to routing backoff slow or fast */
	static final int MAX_ROUTING_BACKOFF_LENGTH = 3 * 60 * 60 * 1000;  // 3 hours
	/** Current nominal routing backoff length */

	int routingBackoffLength = INITIAL_ROUTING_BACKOFF_LENGTH;
	/** Last backoff reason */
	String lastRoutingBackoffReason;
	/** Previous backoff reason (used by setPeerNodeStatus)*/
	String previousRoutingBackoffReason;
	/* percent of time this peer is backed off */
	public final RunningAverage backedOffPercent;
	/* time of last sample */
	private long lastSampleTime = Long.MAX_VALUE;

	/**
	 * Got a local RejectedOverload.
	 * Back off this node for a while.
	 */
	public void localRejectedOverload() {
		localRejectedOverload("");
	}

	/** 
	 * Track the percentage of time a peer spends backed off
	 */
	private void reportBackoffStatus(long now) {
		synchronized(this) {
			if(now > lastSampleTime) { // don't report twice in the same millisecond
				if(now > routingBackedOffUntil) { // not backed off
					if(lastSampleTime > routingBackedOffUntil) { // last sample after last backoff
						backedOffPercent.report(0.0);
					}else {
						if(routingBackedOffUntil > 0)
							backedOffPercent.report((double) (routingBackedOffUntil - lastSampleTime) / (double) (now - lastSampleTime));
					}
				} else {
					backedOffPercent.report(1.0);
				}
			}
			lastSampleTime = now;
		}
	}

	/**
	 * Got a local RejectedOverload.
	 * Back off this node for a while.
	 */
	public void localRejectedOverload(String reason) {
		pRejected.report(1.0);
		if(logMINOR)
			Logger.minor(this, "Local rejected overload (" + reason + ") on " + this + " : pRejected=" + pRejected.currentValue());
		long now = System.currentTimeMillis();
		Peer peer = getPeer();
		reportBackoffStatus(now);
		// We need it because of nested locking on getStatus()
		synchronized(this) {
			// Don't back off any further if we are already backed off
			if(now > routingBackedOffUntil) {
				routingBackoffLength = routingBackoffLength * BACKOFF_MULTIPLIER;
				if(routingBackoffLength > MAX_ROUTING_BACKOFF_LENGTH)
					routingBackoffLength = MAX_ROUTING_BACKOFF_LENGTH;
				int x = node.random.nextInt(routingBackoffLength);
				routingBackedOffUntil = now + x;
				String reasonWrapper = "";
				if(0 <= reason.length())
					reasonWrapper = " because of '" + reason + '\'';
				if(logMINOR)
					Logger.minor(this, "Backing off" + reasonWrapper + ": routingBackoffLength=" + routingBackoffLength + ", until " + x + "ms on " + peer);
			} else {
				if(logMINOR)
					Logger.minor(this, "Ignoring localRejectedOverload: " + (routingBackedOffUntil - now) + "ms remaining on routing backoff on " + peer);
				return;
			}
			setLastBackoffReason(reason);
		}
		setPeerNodeStatus(now);
	}

	/**
	 * Didn't get RejectedOverload.
	 * Reset routing backoff.
	 */
	public void successNotOverload() {
		pRejected.report(0.0);
		if(logMINOR)
			Logger.minor(this, "Success not overload on " + this + " : pRejected=" + pRejected.currentValue());
		Peer peer = getPeer();
		long now = System.currentTimeMillis();
		reportBackoffStatus(now);
		synchronized(this) {
			// Don't un-backoff if still backed off
			if(now > routingBackedOffUntil) {
				routingBackoffLength = INITIAL_ROUTING_BACKOFF_LENGTH;
				if(logMINOR)
					Logger.minor(this, "Resetting routing backoff on " + peer);
			} else {
				if(logMINOR)
					Logger.minor(this, "Ignoring successNotOverload: " + (routingBackedOffUntil - now) + "ms remaining on routing backoff on " + peer);
				return;
			}
		}
		setPeerNodeStatus(now);
	}
	Object pingSync = new Object();
	// Relatively few as we only get one every 200ms*#nodes
	// We want to get reasonably early feedback if it's dropping all of them...

	final static int MAX_PINGS = 5;
	final LRUHashtable pingsSentTimes = new LRUHashtable();
	long pingNumber;
	final RunningAverage pingAverage;

	/**
	 * @return The probability of a request sent to this peer being rejected (locally)
	 * due to overload, or timing out after being accepted.
	 */
	public double getPRejected() {
		return pRejected.currentValue();
	}

	public void sendPing() {
		long pingNo;
		long now = System.currentTimeMillis();
		Long lPingNo;
		synchronized(pingSync) {
			pingNo = pingNumber++;
			lPingNo = new Long(pingNo);
			Long lnow = new Long(now);
			pingsSentTimes.push(lPingNo, lnow);
			if(logMINOR)
				Logger.minor(this, "Pushed " + lPingNo + ' ' + lnow);
			while(pingsSentTimes.size() > MAX_PINGS) {
				Long l = (Long) pingsSentTimes.popValue();
				if(logMINOR)
					Logger.minor(this, "pingsSentTimes.size()=" + pingsSentTimes.size() + ", l=" + l);
				long tStarted = l.longValue();
				pingAverage.report(now - tStarted);
				if(logMINOR)
					Logger.minor(this, "Reporting dumped ping time to " + this + " : " + (now - tStarted));
			}
		}
		Message msg = DMT.createFNPLinkPing(pingNo);
		try {
			sendAsync(msg, null, 0, null);
		} catch(NotConnectedException e) {
			synchronized(pingSync) {
				pingsSentTimes.removeKey(lPingNo);
			}
		}
	}

	public void receivedLinkPong(long id) {
		Long lid = new Long(id);
		long startTime;
		long now = System.currentTimeMillis();
		synchronized(pingSync) {
			Long s = (Long) pingsSentTimes.get(lid);
			if(s == null) {
				Logger.normal(this, "Dropping ping " + id + " on " + this);
				return;
			}
			startTime = s.longValue();
			pingsSentTimes.removeKey(lid);
			pingAverage.report(now - startTime);
			if(logMINOR)
				Logger.minor(this, "Reporting ping time to " + this + " : " + (now - startTime));
		}

		if(noLongerRoutable()) {
			invalidate();
			setPeerNodeStatus(now);
		}
	}

	public double averagePingTime() {
		return pingAverage.currentValue();
	}

	public void reportThrottledPacketSendTime(long timeDiff) {
		node.nodeStats.throttledPacketSendAverage.report(timeDiff);
		if(logMINOR)
			Logger.minor(this, "Reporting throttled packet send time: " + timeDiff + " to " + getPeer());
	}

	public void setRemoteDetectedPeer(Peer p) {
		this.remoteDetectedPeer = p;
	}

	public Peer getRemoteDetectedPeer() {
		return remoteDetectedPeer;
	}

	public synchronized int getRoutingBackoffLength() {
		return routingBackoffLength;
	}

	public synchronized long getRoutingBackedOffUntil() {
		return routingBackedOffUntil;
	}

	public synchronized String getLastBackoffReason() {
		return lastRoutingBackoffReason;
	}

	public synchronized String getPreviousBackoffReason() {
		return previousRoutingBackoffReason;
	}

	public synchronized void setLastBackoffReason(String s) {
		lastRoutingBackoffReason = s;
	}

	public void addToLocalNodeSentMessagesToStatistic(Message m) {
		String messageSpecName;
		Long count;

		messageSpecName = m.getSpec().getName();
		// Synchronize to make increments atomic.
		synchronized(this) {
			count = (Long) localNodeSentMessageTypes.get(messageSpecName);
			if(count == null)
				count = new Long(1);
			else
				count = new Long(count.longValue() + 1);
			localNodeSentMessageTypes.put(messageSpecName, count);
		}
	}

	public void addToLocalNodeReceivedMessagesFromStatistic(Message m) {
		String messageSpecName;
		Long count;

		messageSpecName = m.getSpec().getName();
		// Synchronize to make increments atomic.
		synchronized(this) {
			count = (Long) localNodeReceivedMessageTypes.get(messageSpecName);
			if(count == null)
				count = new Long(1);
			else
				count = new Long(count.longValue() + 1);
			localNodeReceivedMessageTypes.put(messageSpecName, count);
		}
	}

	public synchronized Hashtable getLocalNodeSentMessagesToStatistic() {
		return new Hashtable(localNodeSentMessageTypes);
	}

	// Must be synchronized *during the copy*
	public synchronized Hashtable getLocalNodeReceivedMessagesFromStatistic() {
		return new Hashtable(localNodeReceivedMessageTypes);
	}

	synchronized USK getARK() {
		return myARK;
	}

	public void gotARK(SimpleFieldSet fs, long fetchedEdition) {
		try {
			synchronized(this) {
				handshakeCount = 0;
				// edition +1 because we store the ARK edition that we want to fetch.
				if(myARK.suggestedEdition < fetchedEdition + 1)
					myARK = myARK.copy(fetchedEdition + 1);
			}
			processNewNoderef(fs, true);
		} catch(FSParseException e) {
			Logger.error(this, "Invalid ARK update: " + e, e);
			// This is ok as ARKs are limited to 4K anyway.
			Logger.error(this, "Data was: \n" + fs.toString());
			synchronized(this) {
				handshakeCount = PeerNode.MAX_HANDSHAKE_COUNT;
			}
		}
	}

	public synchronized int getPeerNodeStatus() {
		return peerNodeStatus;
	}

	public String getPeerNodeStatusString() {
		int status = getPeerNodeStatus();
		return getPeerNodeStatusString(status);
	}

	public static String getPeerNodeStatusString(int status) {
		if(status == PeerManager.PEER_NODE_STATUS_CONNECTED)
			return "CONNECTED";
		if(status == PeerManager.PEER_NODE_STATUS_ROUTING_BACKED_OFF)
			return "BACKED OFF";
		if(status == PeerManager.PEER_NODE_STATUS_TOO_NEW)
			return "TOO NEW";
		if(status == PeerManager.PEER_NODE_STATUS_TOO_OLD)
			return "TOO OLD";
		if(status == PeerManager.PEER_NODE_STATUS_DISCONNECTED)
			return "DISCONNECTED";
		if(status == PeerManager.PEER_NODE_STATUS_NEVER_CONNECTED)
			return "NEVER CONNECTED";
		if(status == PeerManager.PEER_NODE_STATUS_DISABLED)
			return "DISABLED";
		if(status == PeerManager.PEER_NODE_STATUS_CLOCK_PROBLEM)
			return "CLOCK PROBLEM";
		if(status == PeerManager.PEER_NODE_STATUS_CONN_ERROR)
			return "CONNECTION ERROR";
		if(status == PeerManager.PEER_NODE_STATUS_ROUTING_DISABLED)
			return "ROUTING DISABLED";
		if(status == PeerManager.PEER_NODE_STATUS_LISTEN_ONLY)
			return "LISTEN ONLY";
		if(status == PeerManager.PEER_NODE_STATUS_LISTENING)
			return "LISTENING";
		if(status == PeerManager.PEER_NODE_STATUS_BURSTING)
			return "BURSTING";
		if(status == PeerManager.PEER_NODE_STATUS_DISCONNECTING)
			return "DISCONNECTING";
		return "UNKNOWN STATUS";
	}

	public String getPeerNodeStatusCSSClassName() {
		int status = getPeerNodeStatus();
		return getPeerNodeStatusCSSClassName(status);
	}

	public static String getPeerNodeStatusCSSClassName(int status) {
		if(status == PeerManager.PEER_NODE_STATUS_CONNECTED)
			return "peer_connected";
		else if(status == PeerManager.PEER_NODE_STATUS_ROUTING_BACKED_OFF)
			return "peer_backed_off";
		else if(status == PeerManager.PEER_NODE_STATUS_TOO_NEW)
			return "peer_too_new";
		else if(status == PeerManager.PEER_NODE_STATUS_TOO_OLD)
			return "peer_too_old";
		else if(status == PeerManager.PEER_NODE_STATUS_DISCONNECTED)
			return "peer_disconnected";
		else if(status == PeerManager.PEER_NODE_STATUS_NEVER_CONNECTED)
			return "peer_never_connected";
		else if(status == PeerManager.PEER_NODE_STATUS_DISABLED)
			return "peer_disabled";
		else if(status == PeerManager.PEER_NODE_STATUS_ROUTING_DISABLED)
			return "peer_routing_disabled";
		else if(status == PeerManager.PEER_NODE_STATUS_BURSTING)
			return "peer_bursting";
		else if(status == PeerManager.PEER_NODE_STATUS_CLOCK_PROBLEM)
			return "peer_clock_problem";
		else if(status == PeerManager.PEER_NODE_STATUS_LISTENING)
			return "peer_listening";
		else if(status == PeerManager.PEER_NODE_STATUS_LISTEN_ONLY)
			return "peer_listen_only";
		else if(status == PeerManager.PEER_NODE_STATUS_DISCONNECTING)
			return "peer_disconnecting";
		else 
			return "peer_unknown_status";
	}

	protected synchronized int getPeerNodeStatus(long now, long routingBackedOffUntil) {
		checkConnectionsAndTrackers();
		if(disconnecting)
			return PeerManager.PEER_NODE_STATUS_DISCONNECTING;
		if(isRoutable()) {  // Function use also updates timeLastConnected and timeLastRoutable
			peerNodeStatus = PeerManager.PEER_NODE_STATUS_CONNECTED;
			if(now < routingBackedOffUntil) {
				peerNodeStatus = PeerManager.PEER_NODE_STATUS_ROUTING_BACKED_OFF;
				if(!lastRoutingBackoffReason.equals(previousRoutingBackoffReason) || (previousRoutingBackoffReason == null)) {
					if(previousRoutingBackoffReason != null) {
						peers.removePeerNodeRoutingBackoffReason(previousRoutingBackoffReason, this);
					}
					peers.addPeerNodeRoutingBackoffReason(lastRoutingBackoffReason, this);
					previousRoutingBackoffReason = lastRoutingBackoffReason;
				}
			} else {
				if(previousRoutingBackoffReason != null) {
					peers.removePeerNodeRoutingBackoffReason(previousRoutingBackoffReason, this);
					previousRoutingBackoffReason = null;
				}
			}
		} else if(isConnected() && bogusNoderef)
			peerNodeStatus = PeerManager.PEER_NODE_STATUS_CONN_ERROR;
		else if(isConnected() && verifiedIncompatibleNewerVersion)
			peerNodeStatus = PeerManager.PEER_NODE_STATUS_TOO_NEW;
		else if(isConnected && verifiedIncompatibleOlderVersion)
			peerNodeStatus = PeerManager.PEER_NODE_STATUS_TOO_OLD;
		else if(isConnected && disableRouting)
			peerNodeStatus = PeerManager.PEER_NODE_STATUS_ROUTING_DISABLED;
		else if(isConnected && Math.abs(clockDelta) > MAX_CLOCK_DELTA)
			peerNodeStatus = PeerManager.PEER_NODE_STATUS_CLOCK_PROBLEM;
		else if(neverConnected)
			peerNodeStatus = PeerManager.PEER_NODE_STATUS_NEVER_CONNECTED;
		else if(isBursting)
			return PeerManager.PEER_NODE_STATUS_BURSTING;
		else
			peerNodeStatus = PeerManager.PEER_NODE_STATUS_DISCONNECTED;
		if(!isConnected && (previousRoutingBackoffReason != null)) {
			peers.removePeerNodeRoutingBackoffReason(previousRoutingBackoffReason, this);
			previousRoutingBackoffReason = null;
		}
		return peerNodeStatus;
	}

	public int setPeerNodeStatus(long now) {
		return setPeerNodeStatus(now, false);
	}

	public int setPeerNodeStatus(long now, boolean noLog) {
		long routingBackedOffUntil = getRoutingBackedOffUntil();
		synchronized(this) {
			int oldPeerNodeStatus = peerNodeStatus;
			peerNodeStatus = getPeerNodeStatus(now, routingBackedOffUntil);

			if(peerNodeStatus != oldPeerNodeStatus && recordStatus()) {
				peers.removePeerNodeStatus(oldPeerNodeStatus, this, noLog);
				peers.addPeerNodeStatus(peerNodeStatus, this, noLog);
			}

		}
		return peerNodeStatus;
	}

	public abstract boolean recordStatus();
	
	private synchronized void checkConnectionsAndTrackers() {
		if(isConnected) {
			if(currentTracker == null) {
				if(unverifiedTracker != null) {
					if(unverifiedTracker.isDeprecated())
						Logger.error(this, "Connected but primary tracker is null and unverified is deprecated ! " + unverifiedTracker + " for " + this, new Exception("debug"));
					else if(logMINOR)
						Logger.minor(this, "Connected but primary tracker is null, but unverified = " + unverifiedTracker + " for " + this, new Exception("debug"));
				} else {
					Logger.error(this, "Connected but both primary and unverified are null on " + this, new Exception("debug"));
				}
			} else if(currentTracker.isDeprecated()) {
				if(unverifiedTracker != null) {
					if(unverifiedTracker.isDeprecated())
						Logger.error(this, "Connected but primary tracker is deprecated, unverified is deprecated: primary=" + currentTracker + " unverified: " + unverifiedTracker + " for " + this, new Exception("debug"));
					else if(logMINOR)
						Logger.minor(this, "Connected, primary tracker deprecated, unverified is valid, " + unverifiedTracker + " for " + this, new Exception("debug"));
				} else {
					// !!!!!!!
					Logger.error(this, "Connected but primary tracker is deprecated and unverified tracker is null on " + this, new Exception("debug"));
					isConnected = false;
				}
			}
		}
	}

	public String getIdentityString() {
		return Base64.encode(identity);
	}

	public boolean isFetchingARK() {
		return arkFetcher != null;
	}

	public synchronized int getHandshakeCount() {
		return handshakeCount;
	}

	synchronized void updateShouldDisconnectNow() {
		verifiedIncompatibleOlderVersion = forwardInvalidVersion();
		verifiedIncompatibleNewerVersion = reverseInvalidVersion();
	}

	/**
	 * Has the node gone from being routable to being non-routable?
	 * This will return true if our lastGoodBuild has changed due to a timed mandatory.
	 */
	public synchronized boolean noLongerRoutable() {
		// TODO: We should disconnect here if "protocol version mismatch", maybe throwing an exception
		// TODO: shouldDisconnectNow() is hopefully only called when we're connected, otherwise we're breaking the meaning of verifiedIncompable[Older|Newer]Version
		if(verifiedIncompatibleNewerVersion || verifiedIncompatibleOlderVersion || disableRouting)
			return true;
		return false;
	}

	protected synchronized void invalidate() {
		isRoutable = false;
		Logger.normal(this, "Invalidated " + this);
	}

	public void maybeOnConnect() {
		if(wasDisconnected && isConnected()) {
			synchronized(this) {
				wasDisconnected = false;
			}
			onConnect();
		} else if(!isConnected()) {
			synchronized(this) {
				wasDisconnected = true;
			}
		}
	}

	/**
	 * A method to be called once at the beginning of every time isConnected() is true
	 */
	protected void onConnect() {
		// Do nothing in the default impl
	}

	public void onFound(long edition, FetchResult result) {
		if(isConnected() || myARK.suggestedEdition > edition) {
			result.asBucket().free();
			return;
		}

		byte[] data;
		try {
			data = result.asByteArray();
		} catch(IOException e) {
			Logger.error(this, "I/O error reading fetched ARK: " + e, e);
			return;
		}

		String ref;
		try {
			ref = new String(data, "UTF-8");
		} catch(UnsupportedEncodingException e) {
			// Yeah, right.
			throw new Error(e);
		}

		SimpleFieldSet fs;
		try {
			fs = new SimpleFieldSet(ref, false, true);
			if(logMINOR)
				Logger.minor(this, "Got ARK for " + this);
			gotARK(fs, edition);
		} catch(IOException e) {
			// Corrupt ref.
			Logger.error(this, "Corrupt ARK reference? Fetched " + myARK.copy(edition) + " got while parsing: " + e + " from:\n" + ref, e);
		}

	}

	public synchronized boolean noContactDetails() {
		return handshakeIPs == null || handshakeIPs.length == 0;
	}

	private synchronized void reportIncomingBytes(int length) {
		totalBytesIn += length;
		totalBytesExchangedWithCurrentTracker += length;
	}

	private synchronized void reportOutgoingBytes(int length) {
		totalBytesOut += length;
		totalBytesExchangedWithCurrentTracker += length;
	}

	public synchronized long getTotalInputBytes() {
		return totalBytesIn;
	}

	public synchronized long getTotalOutputBytes() {
		return totalBytesOut;
	}

	public boolean isSignatureVerificationSuccessfull() {
		return isSignatureVerificationSuccessfull;
	}

	public void checkRoutableConnectionStatus() {
		synchronized(this) {
			if(isRoutable())
				hadRoutableConnectionCount += 1;
			routableConnectionCheckCount += 1;
			// prevent the average from moving too slowly by capping the checkcount to 200000,
			// which, at 7 seconds between counts, works out to about 2 weeks.  This also prevents
			// knowing how long we've had a particular peer long term.
			if(routableConnectionCheckCount >= 200000) {
				// divide both sides by the same amount to keep the same ratio
				hadRoutableConnectionCount = hadRoutableConnectionCount / 2;
				routableConnectionCheckCount = routableConnectionCheckCount / 2;
			}
		}
	}

	public synchronized double getPercentTimeRoutableConnection() {
		if(hadRoutableConnectionCount == 0)
			return 0.0;
		return ((double) hadRoutableConnectionCount) / routableConnectionCheckCount;
	}

	public int getVersionNumber() {
		return Version.getArbitraryBuildNumber(getVersion());
	}

	public PacketThrottle getThrottle() {
		if(currentTracker != null)
			return currentTracker.getThrottle();
		if(unverifiedTracker != null)
			return unverifiedTracker.getThrottle();
		if(previousTracker != null)
			return previousTracker.getThrottle();
		return null;
	}

	/**
	 * Select the most appropriate negType, taking the user's preference into account
	 * order matters
	 * 
	 * @param mangler
	 * @return -1 if no common negType has been found
	 */
	public int selectNegType(OutgoingPacketMangler mangler) {
		int[] hisNegTypes;
		int[] myNegTypes = mangler.supportedNegTypes();
		synchronized(this) {
			hisNegTypes = negTypes;
		}
		int bestNegType = -1;
		for(int i = 0; i < myNegTypes.length; i++) {
			int negType = myNegTypes[i];
			for(int j = 0; j < hisNegTypes.length; j++) {
				if(hisNegTypes[j] == negType) {
					bestNegType = negType;
					break;
				}
			}
		}
		return bestNegType;
	}

	/** Verify a hash */
	public boolean verify(byte[] hash, DSASignature sig) {
		return DSA.verify(peerPubKey, sig, new NativeBigInteger(1, hash), false);
	}

	public String userToString() {
		return "" + getPeer();
	}

	public void setTimeDelta(long delta) {
		synchronized(this) {
			clockDelta = delta;
			if(Math.abs(clockDelta) > MAX_CLOCK_DELTA)
				isRoutable = false;
		}
		setPeerNodeStatus(System.currentTimeMillis());
	}

	public long getClockDelta() {
		return clockDelta;
	}

	/** Offer a key to this node */
	public void offer(Key key) {
		Message msg = DMT.createFNPOfferKey(key);
		try {
			sendAsync(msg, null, 0, null);
		} catch(NotConnectedException e) {
		// Ignore
		}
	}

	public OutgoingPacketMangler getOutgoingMangler() {
		return outgoingMangler;
	}

	public SocketHandler getSocketHandler() {
		return outgoingMangler.getSocketHandler();
	}

	/** Is this peer disabled? I.e. has the user explicitly disabled it? */
	public boolean isDisabled() {
		return false;
	}

	/** Is this peer allowed local addresses? If false, we will never connect to this peer via
	 * a local address even if it advertises them.
	 */
	public boolean allowLocalAddresses() {
		return this.outgoingMangler.alwaysAllowLocalAddresses();
	}

	/** Is this peer set to ignore source address? If so, we will always reply to the peer's official
	 * address, even if we get packets from somewhere else. @see DarknetPeerNode.isIgnoreSourcePort().
	 */
	public boolean isIgnoreSource() {
		return false;
	}

	/**
	 * Create a DarknetPeerNode or an OpennetPeerNode as appropriate
	 */
	public static PeerNode create(SimpleFieldSet fs, Node node2, NodeCrypto crypto, OpennetManager opennet, PeerManager manager, boolean b, OutgoingPacketMangler mangler) throws FSParseException, PeerParseException, ReferenceSignatureVerificationException {
		if(crypto.isOpennet)
			return new OpennetPeerNode(fs, node2, crypto, opennet, manager, b, mangler);
		else
			return new DarknetPeerNode(fs, node2, crypto, manager, b, mangler);
	}

	public byte[] getIdentity() {
		return identity;
	}

	public boolean neverConnected() {
		return neverConnected;
	}

	/** Called when a request or insert succeeds. Used by opennet. */
	public abstract void onSuccess(boolean insert, boolean ssk);

	/** Called when a delayed disconnect is occurring. Tell the node that it is being disconnected, but
	 * that the process may take a while. */
	public void notifyDisconnecting() {
		synchronized(this) {
			disconnecting = true;
			jfkNoncesSent.clear();
		}
		setPeerNodeStatus(System.currentTimeMillis());
	}

	/** Called to cancel a delayed disconnect. Always succeeds even if the node was not being 
	 * disconnected. */
	public void forceCancelDisconnecting() {
		synchronized(this) {
			if(!disconnecting)
				return;
			disconnecting = false;
		}
		setPeerNodeStatus(System.currentTimeMillis(), true);
	}

	/** Called when the peer is removed from the PeerManager */
	public void onRemove() {
		disconnected(true, true);
	}

	public synchronized boolean isDisconnecting() {
		return disconnecting;
	}

	protected byte[] getJFKBuffer() {
		return jfkBuffer;
	}

	protected void setJFKBuffer(byte[] bufferJFK) {
		this.jfkBuffer = bufferJFK;
	}

	public int getSigParamsByteLength() {
		int bitLen = this.peerCryptoGroup.getQ().bitLength();
		int byteLen = bitLen / 8 + (bitLen % 8 != 0 ? 1 : 0);
		return byteLen;
	}

	// Recent packets sent/received
	// We record times and weak short hashes of the last 64 packets 
	// sent/received. When we connect successfully, we send the data
	// on what packets we have sent, and the recipient can compare
	// this to their records of received packets to determine if there
	// is a problem, which usually indicates not being port forwarded.

	static final short TRACK_PACKETS = 64;
	private final long[] packetsSentTimes = new long[TRACK_PACKETS];
	private final long[] packetsRecvTimes = new long[TRACK_PACKETS];
	private final long[] packetsSentHashes = new long[TRACK_PACKETS];
	private final long[] packetsRecvHashes = new long[TRACK_PACKETS];
	private short sentPtr;
	private short recvPtr;
	private boolean sentTrackPackets;
	private boolean recvTrackPackets;

	public void reportIncomingPacket(byte[] buf, int offset, int length, long now) {
		reportIncomingBytes(length);
		long hash = Fields.longHashCode(buf, offset, length);
		synchronized(this) {
			packetsRecvTimes[recvPtr] = now;
			packetsRecvHashes[recvPtr] = hash;
			recvPtr++;
			if(recvPtr == TRACK_PACKETS) {
				recvPtr = 0;
				recvTrackPackets = true;
			}
		}
	}

	public void reportOutgoingPacket(byte[] buf, int offset, int length, long now) {
		reportOutgoingBytes(length);
		long hash = Fields.longHashCode(buf, offset, length);
		synchronized(this) {
			packetsSentTimes[sentPtr] = now;
			packetsSentHashes[sentPtr] = hash;
			sentPtr++;
			if(sentPtr == TRACK_PACKETS) {
				sentPtr = 0;
				sentTrackPackets = true;
			}
		}
	}

	/**
	 * @return a long[] consisting of two arrays, the first being packet times,
	 * the second being packet hashes.
	 */
	public synchronized long[][] getSentPacketTimesHashes() {
		short count = sentTrackPackets ? TRACK_PACKETS : sentPtr;
		long[] times = new long[count];
		long[] hashes = new long[count];
		if(!sentTrackPackets) {
			System.arraycopy(packetsSentTimes, 0, times, 0, sentPtr);
			System.arraycopy(packetsSentHashes, 0, hashes, 0, sentPtr);
		} else {
			System.arraycopy(packetsSentTimes, sentPtr, times, 0, TRACK_PACKETS - sentPtr);
			System.arraycopy(packetsSentTimes, 0, times, TRACK_PACKETS - sentPtr, sentPtr);
			System.arraycopy(packetsSentHashes, sentPtr, hashes, 0, TRACK_PACKETS - sentPtr);
			System.arraycopy(packetsSentHashes, 0, hashes, TRACK_PACKETS - sentPtr, sentPtr);
		}
		return new long[][]{times, hashes};
	}

	/**
	 * @return a long[] consisting of two arrays, the first being packet times,
	 * the second being packet hashes.
	 */
	public synchronized long[][] getRecvPacketTimesHashes() {
		short count = recvTrackPackets ? TRACK_PACKETS : recvPtr;
		long[] times = new long[count];
		long[] hashes = new long[count];
		if(!recvTrackPackets) {
			System.arraycopy(packetsRecvTimes, 0, times, 0, recvPtr);
			System.arraycopy(packetsRecvHashes, 0, hashes, 0, recvPtr);
		} else {
			System.arraycopy(packetsRecvTimes, recvPtr, times, 0, TRACK_PACKETS - recvPtr);
			System.arraycopy(packetsRecvTimes, 0, times, TRACK_PACKETS - recvPtr, recvPtr);
			System.arraycopy(packetsRecvHashes, recvPtr, hashes, 0, TRACK_PACKETS - recvPtr);
			System.arraycopy(packetsRecvHashes, 0, hashes, TRACK_PACKETS - recvPtr, recvPtr);
		}
		return new long[][]{times, hashes};
	}
	static final int SENT_PACKETS_MAX_TIME_AFTER_CONNECT = 5 * 60 * 1000;

	/**
	 * Handle an FNPSentPackets message
	 */
	public void handleSentPackets(Message m) {
		long now = System.currentTimeMillis();
		synchronized(this) {
			if(forceDisconnectCalled)
				return;
			if(now - this.timeLastConnected < SENT_PACKETS_MAX_TIME_AFTER_CONNECT)
				return;
		}
		long baseTime = m.getLong(DMT.TIME);
		baseTime += this.clockDelta;
		// Should be a reasonable approximation now
		int[] timeDeltas = Fields.bytesToInts(((ShortBuffer) m.getObject(DMT.TIME_DELTAS)).getData());
		long[] packetHashes = Fields.bytesToLongs(((ShortBuffer) m.getObject(DMT.TIME_DELTAS)).getData());
		long[] times = new long[timeDeltas.length];
		for(int i = 0; i < times.length; i++)
			times[i] = baseTime - timeDeltas[i];
		long tolerance = 60 * 1000 + (Math.abs(timeDeltas[0]) / 20); // 1 minute or 5% of full interval
		synchronized(this) {
			// They are in increasing order
			// Loop backwards
			long otime = Long.MAX_VALUE;
			long[][] sent = getSentPacketTimesHashes();
			long[] sentTimes = sent[0];
			long[] sentHashes = sent[1];
			short sentPtr = (short) (sent.length - 1);
			short notFoundCount = 0;
			short consecutiveNotFound = 0;
			short longestConsecutiveNotFound = 0;
			for(short i = (short) times.length; i >= 0; i--) {
				long time = times[i];
				if(time > otime) {
					Logger.error(this, "Inconsistent time order: [" + i + "]=" + time + " but [" + (i + 1) + "] is " + otime);
					return;
				} else
					otime = time;
				long hash = packetHashes[i];
				// Search for the hash.
				short match = -1;
				// First try forwards
				for(short j = sentPtr; j < sentTimes.length; j++) {
					long ttime = sentTimes[j];
					if(sentHashes[j] == hash) {
						match = j;
						sentPtr = j;
						break;
					}
					if(ttime - time > tolerance)
						break;
				}
				if(match == -1)
					for(short j = (short) (sentPtr - 1); j >= 0; j--) {
						long ttime = sentTimes[j];
						if(sentHashes[j] == hash) {
							match = j;
							sentPtr = j;
							break;
						}
						if(time - ttime > tolerance)
							break;
					}
				if(match == -1) {
					// Not found
					consecutiveNotFound++;
					notFoundCount++;
				} else {
					if(consecutiveNotFound > longestConsecutiveNotFound)
						longestConsecutiveNotFound = consecutiveNotFound;
					consecutiveNotFound = 0;
				}
			}
			if(consecutiveNotFound > longestConsecutiveNotFound)
				longestConsecutiveNotFound = consecutiveNotFound;
			if(consecutiveNotFound > TRACK_PACKETS / 2) {
				manyPacketsClaimedSentNotReceived = true;
				Logger.error(this, "" + consecutiveNotFound + " consecutive packets not found on " + userToString());
				SocketHandler handler = outgoingMangler.getSocketHandler();
				if(handler instanceof PortForwardSensitiveSocketHandler) {
					((PortForwardSensitiveSocketHandler) handler).rescanPortForward();
				}
			}
		}
	}
	private boolean manyPacketsClaimedSentNotReceived = false;

	synchronized boolean manyPacketsClaimedSentNotReceived() {
		return manyPacketsClaimedSentNotReceived;
	}
	
	static final int MAX_SIMULTANEOUS_ANNOUNCEMENTS = 1;
	static final int MAX_ANNOUNCE_DELAY = 1000;
	private long timeLastAcceptedAnnouncement;
	private long[] runningAnnounceUIDs = new long[0];
	
	public synchronized boolean shouldAcceptAnnounce(long uid) {
		long now = System.currentTimeMillis();
		if(runningAnnounceUIDs.length < MAX_SIMULTANEOUS_ANNOUNCEMENTS &&
				now - timeLastAcceptedAnnouncement > MAX_ANNOUNCE_DELAY) {
			long[] newList = new long[runningAnnounceUIDs.length + 1];
			if(runningAnnounceUIDs.length > 0)
				System.arraycopy(runningAnnounceUIDs, 0, newList, 0, runningAnnounceUIDs.length);
			newList[runningAnnounceUIDs.length] = uid;
			timeLastAcceptedAnnouncement = now;
			return true;
		} else {
			return false;
		}
	}
	
	public synchronized boolean completedAnnounce(long uid) {
		if(runningAnnounceUIDs.length == 0) return false;
		long[] newList = new long[runningAnnounceUIDs.length - 1];
		int x = 0;
		for(int i=0;i<runningAnnounceUIDs.length;i++) {
			if(i == runningAnnounceUIDs.length) return false;
			long l = runningAnnounceUIDs[i];
			if(l == uid) continue;
			newList[x++] = l;
		}
		runningAnnounceUIDs = newList;
		if(x < runningAnnounceUIDs.length) {
			newList = new long[x];
			System.arraycopy(runningAnnounceUIDs, 0, newList, 0, x);
			runningAnnounceUIDs = newList;
		}
		return true;
	}

    public synchronized long timeLastDisconnect() {
    	return timeLastDisconnect;
    }

    /** Does this peernode want to be returned by for example PeerManager.getByPeer() ? */
	public abstract boolean isSearchable();

	/** Can we accept announcements from this node? */
	public boolean canAcceptAnnouncements() {
		return isOpennet() || node.passOpennetRefsThroughDarknet();
	}

	public boolean handshakeUnknownInitiator() {
		return false;
	}

	public int handshakeSetupType() {
		return -1;
	}

	public WeakReference getWeakRef() {
		return myRef;
	}
	
}
