
/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import freenet.io.comm.SocketHandler;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedList;

import net.i2p.util.NativeBigInteger;
import freenet.crypt.BlockCipher;
import freenet.crypt.DSA;
import freenet.crypt.DSAGroup;
import freenet.crypt.DSASignature;
import freenet.crypt.DiffieHellman;
import freenet.crypt.DiffieHellmanLightContext;
import freenet.crypt.EntropySource;
import freenet.crypt.Global;
import freenet.crypt.HMAC;
import freenet.crypt.PCFBMode;
import freenet.crypt.SHA256;
import freenet.crypt.UnsupportedCipherException;
import freenet.crypt.ciphers.Rijndael;
import freenet.io.comm.AsyncMessageCallback;
import freenet.io.comm.FreenetInetAddress;
import freenet.io.comm.IncomingPacketFilter;
import freenet.io.comm.Message;
import freenet.io.comm.MessageCore;
import freenet.io.comm.NotConnectedException;
import freenet.io.comm.Peer.LocalAddressException;
import freenet.support.Fields;
import freenet.io.comm.PacketSocketHandler;
import freenet.io.comm.Peer;
import freenet.io.comm.PeerContext;
import freenet.support.HexUtil;
import freenet.support.Logger;
import freenet.support.StringArray;
import freenet.support.TimeUtil;
import freenet.support.WouldBlockException;
import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
import java.util.HashMap;

/**
 * @author amphibian
 * 
 * Encodes and decodes packets for FNP.
 * 
 * This includes encryption, authentication, and may later
 * include queueing etc. (that may require some interface
 * changes in IncomingPacketFilter).
 */
public class FNPPacketMangler implements OutgoingPacketMangler, IncomingPacketFilter {

	private static boolean logMINOR;
	final Node node;
	final NodeCrypto crypto;
	final MessageCore usm;
	final PacketSocketHandler sock;
	final EntropySource fnpTimingSource;
	final EntropySource myPacketDataSource;
	/**
	 * Objects cached during JFK message exchange: JFK(3,4) with authenticator as key
	 * The messages are cached in hashmaps because the message retrieval from the cache 
	 * can be performed in constant time( given the key)
	 */
	private final HashMap authenticatorCache;
	/** The following is used in the HMAC calculation of JFK message3 and message4 */
	private static final byte[] JFK_PREFIX_INITIATOR, JFK_PREFIX_RESPONDER;
	static {
		byte[] I = null,R = null;
		try { I = "I".getBytes("UTF-8"); } catch (UnsupportedEncodingException e) {}
		try { R = "R".getBytes("UTF-8"); } catch (UnsupportedEncodingException e) {}
		
		JFK_PREFIX_INITIATOR = I;
		JFK_PREFIX_RESPONDER = R;
	}
	
	public final static int DH_CONTEXT_BUFFER_SIZE = 10;
	private final LinkedList dhContextFIFO = new LinkedList();
	/* Get a lock on dhContextFIFO before touching it! */
	private DiffieHellmanLightContext dhContextToBePrunned = null;
	private long jfkDHLastGenerationTimestamp = 0;
	
	protected static final int NONCE_SIZE = 8;
	/**
	 * How big can the authenticator get before we flush it ?
	 * roughly n*(sizeof(message3|message4) + H(authenticator))
	 * 
	 * We push to it until we reach the cap where we rekey
	 */
	private static final int AUTHENTICATOR_CACHE_SIZE = 30;
	private static final int MAX_PACKETS_IN_FLIGHT = 256; 
	private static final int RANDOM_BYTES_LENGTH = 12;
	private static final int HASH_LENGTH = SHA256.getDigestLength();
	/** The size of the key used to authenticate the hmac */
	private static final int TRANSIENT_KEY_SIZE = HASH_LENGTH;
	/** The key used to authenticate the hmac */
	private final byte[] transientKey = new byte[TRANSIENT_KEY_SIZE];
	public static final int TRANSIENT_KEY_REKEYING_MIN_INTERVAL = 30*60*1000;
        /** The rekeying interval for the session key (keytrackers) */
        public static final int SESSION_KEY_REKEYING_INTERVAL = 60*60*1000;
	/** The max amount of time we will accept to use the current tracker when it should have been replaced */
	public static final int MAX_SESSION_KEY_REKEYING_DELAY = 5*60*1000;
        /** The amount of data sent before we ask for a rekey */
        public static final int AMOUNT_OF_BYTES_ALLOWED_BEFORE_WE_REKEY = 1024 * 1024 * 1024;
	/** The Runnable in charge of rekeying on a regular basis */
	private final Runnable transientKeyRekeyer = new Runnable() {
		public void run() {
			maybeResetTransientKey();
		}
	};
	/** Minimum headers overhead */
	private static final int HEADERS_LENGTH_MINIMUM =
		4 + // sequence number
		RANDOM_BYTES_LENGTH + // random junk
		1 + // version
		1 + // assume seqno != -1; otherwise would be 4
		4 + // other side's seqno
		1 + // number of acks
		0 + // assume no acks
		1 + // number of resend reqs
		0 + // assume no resend requests
		1 + // number of ack requests
		0 + // assume no ack requests
		1 + // no forgotten packets
		HASH_LENGTH + // hash
		1; // number of messages
	/** Headers overhead if there is one message and no acks. */
	static public final int HEADERS_LENGTH_ONE_MESSAGE = 
		HEADERS_LENGTH_MINIMUM + 2; // 2 bytes = length of message. rest is the same.

	final int fullHeadersLengthMinimum;
	final int fullHeadersLengthOneMessage;


	public FNPPacketMangler(Node node, NodeCrypto crypt, PacketSocketHandler sock) {
		this.node = node;
		this.crypto = crypt;
		this.usm = node.usm;
		this.sock = sock;
		fnpTimingSource = new EntropySource();
		myPacketDataSource = new EntropySource();
		authenticatorCache = new HashMap();
		
		fullHeadersLengthMinimum = HEADERS_LENGTH_MINIMUM + sock.getHeadersLength();
		fullHeadersLengthOneMessage = HEADERS_LENGTH_ONE_MESSAGE + sock.getHeadersLength();
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
	}
	
	/**
	 * Start up the FNPPacketMangler. By the time this is called, all objects will have been constructed,
	 * but not all will have been started yet.
	 */
	public void start() {
		// Run it directly so that the transient key is set.
		maybeResetTransientKey();
		// Fill the DH FIFO on-thread
		for(int i=0;i<DH_CONTEXT_BUFFER_SIZE;i++)
			_fillJFKDHFIFO();
	}


	/**
	 * Packet format:
	 *
	 * E_session_ecb(
	 *         4 bytes:  sequence number XOR first 4 bytes of node identity
	 *         12 bytes: first 12 bytes of H(data)
	 *         )
	 * E_session_ecb(
	 *         16 bytes: bytes 12-28 of H(data)
	 *         ) XOR previous ciphertext XOR previous plaintext
	 * 4 bytes: bytes 28-32 of H(data) XOR bytes 0-4 of H(data)
	 * E_session_pcfb(data) // IV = first 32 bytes of packet
	 * 
	 */

	/**
	 * Decrypt and authenticate packet.
	 * Then feed it to USM.checkFilters.
	 * Packets generated should have a PeerNode on them.
	 * Note that the buffer can be modified by this method.
	 */
	public void process(byte[] buf, int offset, int length, Peer peer, long now) {
		node.random.acceptTimerEntropy(fnpTimingSource, 0.25);
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		if(logMINOR) Logger.minor(this, "Packet length "+length+" from "+peer);

		/**
		 * Look up the Peer.
		 * If we know it, check the packet with that key.
		 * Otherwise try all of them (on the theory that nodes 
		 * occasionally change their IP addresses).
		 */
		PeerNode opn = node.peers.getByPeer(peer);
		if(opn != null && opn.getOutgoingMangler() != this) {
			Logger.error(this, "Apparently contacted by "+opn+") on "+this);
			opn = null;
		}
		PeerNode pn;

		if(opn != null) {
			if(logMINOR) Logger.minor(this, "Trying exact match");
			if(length > HEADERS_LENGTH_MINIMUM) {
				if(tryProcess(buf, offset, length, opn.getCurrentKeyTracker(), now)) return;
				// Try with old key
				if(tryProcess(buf, offset, length, opn.getPreviousKeyTracker(), now)) return;
				// Try with unverified key
				if(tryProcess(buf, offset, length, opn.getUnverifiedKeyTracker(), now)) return;
			}
			if(length > Node.SYMMETRIC_KEY_LENGTH /* iv */ + HASH_LENGTH + 2 && !node.isStopping()) {
				// Might be an auth packet
				if(tryProcessAuth(buf, offset, length, opn, peer, false, now)) return;
			}
		}
		PeerNode[] peers = crypto.getPeerNodes();
		// Existing connection, changed IP address?
		if(length > HASH_LENGTH + RANDOM_BYTES_LENGTH + 4 + 6) {
			for(int i=0;i<peers.length;i++) {
				pn = peers[i];
				if(pn == opn) continue;
				if(tryProcess(buf, offset, length, pn.getCurrentKeyTracker(), now)) {
					// IP address change
					pn.changedIP(peer);
					return;
				}
				if(tryProcess(buf, offset, length, pn.getPreviousKeyTracker(), now)) {
					// IP address change
					pn.changedIP(peer);
					return;
				}
				if(tryProcess(buf, offset, length, pn.getUnverifiedKeyTracker(), now)) {
					// IP address change
					pn.changedIP(peer);
					return;
				}
			}
		}
		if(node.isStopping()) return;
		// Disconnected node connecting on a new IP address?
		if(length > Node.SYMMETRIC_KEY_LENGTH /* iv */ + HASH_LENGTH + 2) {
			for(int i=0;i<peers.length;i++) {
				pn = peers[i];
				if(pn == opn) continue;
				if(tryProcessAuth(buf, offset, length, pn, peer,false, now)) return;
			}
		}
		OpennetManager opennet = node.getOpennet();
		if(opennet != null) {
			// Try old opennet connections.
			if(opennet.wantPeer(null, false)) {
				// We want a peer.
				// Try old connections.
				PeerNode[] oldPeers = opennet.getOldPeers();
				for(int i=0;i<oldPeers.length;i++) {
					if(tryProcessAuth(buf, offset, length, oldPeers[i], peer, true, now)) return;
				}
			}
		}
		Logger.normal(this,"Unmatchable packet from "+peer);
	}

	/**
	 * Is this a negotiation packet? If so, process it.
	 * @param buf The buffer to read bytes from
	 * @param offset The offset at which to start reading
	 * @param length The number of bytes to read
	 * @param pn The PeerNode we think is responsible
	 * @param peer The Peer to send a reply to
	 * @param now The time at which the packet was received
	 * @return True if we handled a negotiation packet, false otherwise.
	 */
	private boolean tryProcessAuth(byte[] buf, int offset, int length, PeerNode pn, Peer peer, boolean oldOpennetPeer, long now) {
		BlockCipher authKey = pn.incomingSetupCipher;
		if(logMINOR) Logger.minor(this, "Decrypt key: "+HexUtil.bytesToHex(pn.incomingSetupKey)+" for "+peer+" : "+pn+" in tryProcessAuth");
		// Does the packet match IV E( H(data) data ) ?
		PCFBMode pcfb = PCFBMode.create(authKey);
		int ivLength = pcfb.lengthIV();
		MessageDigest md = SHA256.getMessageDigest();
		int digestLength = HASH_LENGTH;
		if(length < digestLength + ivLength + 4) {
			if(logMINOR) Logger.minor(this, "Too short: "+length+" should be at least "+(digestLength + ivLength + 4));
			SHA256.returnMessageDigest(md);
			return false;
		}
		// IV at the beginning
		pcfb.reset(buf, offset);
		// Then the hash, then the data
		// => Data starts at ivLength + digestLength
		// Decrypt the hash
		byte[] hash = new byte[digestLength];
		System.arraycopy(buf, offset+ivLength, hash, 0, digestLength);
		pcfb.blockDecipher(hash, 0, hash.length);

		int dataStart = ivLength + digestLength + offset+2;

		int byte1 = ((pcfb.decipher(buf[dataStart-2])) & 0xff);
		int byte2 = ((pcfb.decipher(buf[dataStart-1])) & 0xff);
		int dataLength = (byte1 << 8) + byte2;
		if(logMINOR) Logger.minor(this, "Data length: "+dataLength+" (1 = "+byte1+" 2 = "+byte2+ ')');
		if(dataLength > length - (ivLength+hash.length+2)) {
			if(logMINOR) Logger.minor(this, "Invalid data length "+dataLength+" ("+(length - (ivLength+hash.length+2))+") in tryProcessAuth");
			SHA256.returnMessageDigest(md);
			return false;
		}
		// Decrypt the data
		byte[] payload = new byte[dataLength];
		System.arraycopy(buf, dataStart, payload, 0, dataLength);
		pcfb.blockDecipher(payload, 0, payload.length);

		md.update(payload);
		byte[] realHash = md.digest();
		SHA256.returnMessageDigest(md); md = null;

		if(Arrays.equals(realHash, hash)) {
			// Got one
			processDecryptedAuth(payload, pn, peer, oldOpennetPeer);
			pn.reportIncomingPacket(buf, offset, length, now);
			return true;
		} else {
			if(logMINOR) Logger.minor(this, "Incorrect hash in tryProcessAuth for "+peer+" (length="+dataLength+"): \nreal hash="+HexUtil.bytesToHex(realHash)+"\n bad hash="+HexUtil.bytesToHex(hash));
			return false;
		}
	}

	/**
	 * Process a decrypted, authenticated auth packet.
	 * @param payload The packet payload, after it has been decrypted.
	 */
	private void processDecryptedAuth(byte[] payload, PeerNode pn, Peer replyTo, boolean oldOpennetPeer) {
		if(logMINOR) Logger.minor(this, "Processing decrypted auth packet from "+replyTo+" for "+pn);
		if(pn.isDisabled()) {
			if(logMINOR) Logger.minor(this, "Won't connect to a disabled peer ("+pn+ ')');
			return;  // We don't connect to disabled peers
		}

		long now = System.currentTimeMillis();
		int delta = (int) (now - pn.lastSentPacketTime());

		int negType = payload[1];
		int packetType = payload[2];
		int version = payload[0];

		if(logMINOR) Logger.minor(this, "Received auth packet for "+pn.getPeer()+" (phase="+packetType+", v="+version+", nt="+negType+") (last packet sent "+TimeUtil.formatTime(delta, 2, true)+" ago) from "+replyTo+"");

		/* Format:
		 * 1 byte - version number (1)
		 * 1 byte - negotiation type (0 = simple DH, will not be supported when implement JFKi || 1 = StS)
		 * 1 byte - packet type (0-3)
		 */
		if(version != 1) {
			Logger.error(this, "Decrypted auth packet but invalid version: "+version);
			return;
		}

		if(negType == 0) {
			Logger.error(this, "Old ephemeral Diffie-Hellman (negType 0) not supported.");
			return;
		} else if (negType == 1) {
			Logger.error(this, "Old StationToStation (negType 1) not supported.");
			return;
		} else if (negType==2){
			/*
			 * We implement Just Fast Keying key management protocol with active identity protection
			 * for the initiator and no identity protection for the responder
			 * M1:
			 * This is a straightforward DiffieHellman exponential.
			 * The Initiator Nonce serves two purposes;it allows the initiator to use the same
			 * exponentials during different sessions while ensuring that the resulting session
			 * key will be different,can be used to differentiate between parallel sessions 
			 * M2:
			 * Responder replies with a signed copy of his own exponential, a random nonce and 
			 * an authenticator which provides sufficient defense against forgeries,replays
			 * We slightly deviate JFK here;we do not send any public key information as specified in the JFK docs 
			 * M3:
			 * Initiator echoes the data sent by the responder including the authenticator. 
			 * This helps the responder verify the authenticity of the returned data. 
			 * M4:
			 * Encrypted message of the signature on both nonces, both exponentials using the same keys as in the previous message
			 */ 
			if(packetType<0 || packetType>3){
				Logger.error(this,"Unknown PacketType" + packetType + "from" + replyTo + "from" +pn); 
				return ;
			}
			else if(packetType==0){
				/*
				 * Initiator- This is a straightforward DiffieHellman exponential.
				 * The Initiator Nonce serves two purposes;it allows the initiator to use the same
				 * exponentials during different sessions while ensuring that the resulting
				 * session key will be different,can be used to differentiate between
				 * parallel sessions
				 */
				processJFKMessage1(payload,pn,replyTo);

			}
			else if(packetType==1){
				/*
				 * Responder replies with a signed copy of his own exponential, a random
				 * nonce and an authenticator calculated from a transient hash key private
				 * to the responder.
				 */
				processJFKMessage2(payload,pn,replyTo);
			}
			else if(packetType==2){
				/*
				 * Initiator echoes the data sent by the responder.These messages are
				 * cached by the Responder.Receiving a duplicate message simply causes
				 * the responder to Re-transmit the corresponding message4
				 */
				processJFKMessage3(payload, pn, replyTo, oldOpennetPeer);
			}
			else if(packetType==3){
				/*
				 * Encrypted message of the signature on both nonces, both exponentials 
				 * using the same keys as in the previous message.
				 * The signature is non-message recovering
				 */
				processJFKMessage4(payload, pn, replyTo, oldOpennetPeer);
			}
		} else {
			Logger.error(this, "Decrypted auth packet but unknown negotiation type "+negType+" from "+replyTo+" possibly from "+pn);
			return;
		}
	}

	/*
	 * Initiator Method:Message1
	 * Process Message1
	 * Send the Initiator nonce and DiffieHellman Exponential
	 * @param The packet phase number
	 * @param The peerNode we are talking to
	 * @param The peer to which we need to send the packet
	 * 
	 * format :
	 * Ni
	 * g^i
	 * IDr'
	 */	
	private void processJFKMessage1(byte[] payload,PeerNode pn,Peer replyTo)
	{
		long t1=System.currentTimeMillis();
		if(logMINOR) Logger.minor(this, "Got a JFK(1) message, processing it - "+pn);
		// FIXME: follow the spec and send IDr' ?
		if(payload.length < NONCE_SIZE + DiffieHellman.modulusLengthInBytes() + 3) {
			Logger.error(this, "Packet too short from "+pn+": "+payload.length+" after decryption in JFK(1), should be "+(NONCE_SIZE + DiffieHellman.modulusLengthInBytes()));
			return;
		}
		int offset=3;
		// get Ni
		byte[] nonceInitiator = new byte[NONCE_SIZE];
		System.arraycopy(payload, offset, nonceInitiator, 0, NONCE_SIZE);
		offset += NONCE_SIZE;

		// get g^i
		byte[] hisExponential = new byte[DiffieHellman.modulusLengthInBytes()];
		System.arraycopy(payload, offset, hisExponential, 0, DiffieHellman.modulusLengthInBytes());
		NativeBigInteger _hisExponential = new NativeBigInteger(1,hisExponential);
		if(DiffieHellman.checkDHExponentialValidity(this.getClass(), _hisExponential)) {
			sendJFKMessage2(nonceInitiator, hisExponential, pn, replyTo);
		}else
			Logger.error(this, "We can't accept the exponential "+pn+" sent us!! REDFLAG: IT CAN'T HAPPEN UNLESS AGAINST AN ACTIVE ATTACKER!!");

		long t2=System.currentTimeMillis();
		if((t2-t1)>500)
			Logger.error(this,"Message1 timeout error:Sending packet for"+pn.getPeer());
	}

	/*
	 * format:
	 * Ni,g^i
	 * NB: we don't send IDr as we know to who we are talking to (darknet)
	 */
	private void sendJFKMessage1(PeerNode pn, Peer replyTo) {
		if(logMINOR) Logger.minor(this, "Sending a JFK(1) message to "+pn);
		final long now = System.currentTimeMillis();
		DiffieHellmanLightContext ctx = (DiffieHellmanLightContext) pn.getKeyAgreementSchemeContext();
		if((ctx == null) || ((pn.jfkContextLifetime + 15*60*1000) < now)) {
			pn.jfkContextLifetime = now;
			pn.setKeyAgreementSchemeContext(ctx = getLightDiffieHellmanContext());
		}
		int offset = 0;
		byte[] myExponential = stripBigIntegerToNetworkFormat(ctx.myExponential);
		byte[] nonce = new byte[NONCE_SIZE];
		node.random.nextBytes(nonce);
		
		synchronized (pn) {
			pn.jfkNoncesSent.put(replyTo, nonce);
		}
		
		byte[] message1 = new byte[NONCE_SIZE+DiffieHellman.modulusLengthInBytes()];

		System.arraycopy(nonce, 0, message1, offset, NONCE_SIZE);
		offset += NONCE_SIZE;
		System.arraycopy(myExponential, 0, message1, offset, DiffieHellman.modulusLengthInBytes());

		sendAuthPacket(1,2,0,message1,pn,replyTo);
	}

	/*
	 * format:
	 * Ni,Nr,g^r
	 * Signature[g^r,grpInfo(r)] - R, S
	 * Hashed JFKAuthenticator : HMAC{Hkr}[g^r, g^i, Nr, Ni, IPi]
	 * 
	 * NB: we don't send IDr nor groupinfo as we know them (darknet)
	 */
	private void sendJFKMessage2(byte[] nonceInitator, byte[] hisExponential, PeerNode pn, Peer replyTo) {
		if(logMINOR) Logger.minor(this, "Sending a JFK(2) message to "+pn);
		DiffieHellmanLightContext ctx = getLightDiffieHellmanContext();
		// g^r
		byte[] myExponential = stripBigIntegerToNetworkFormat(ctx.myExponential);
		// Nr
		byte[] myNonce = new byte[NONCE_SIZE];
		node.random.nextBytes(myNonce);
		byte[] r = ctx.signature.getRBytes(Node.SIGNATURE_PARAMETER_LENGTH);
		byte[] s = ctx.signature.getSBytes(Node.SIGNATURE_PARAMETER_LENGTH);
		HMAC hash = new HMAC(SHA256.getInstance());
		byte[] authenticator = hash.mac(getTransientKey(),assembleJFKAuthenticator(myExponential, hisExponential, myNonce, nonceInitator, replyTo.getAddress().getAddress()), HASH_LENGTH);
		if(logMINOR) Logger.minor(this, "We are using the following HMAC : " + HexUtil.bytesToHex(authenticator));

		byte[] message2 = new byte[NONCE_SIZE*2+DiffieHellman.modulusLengthInBytes()+
		                           Node.SIGNATURE_PARAMETER_LENGTH*2+
		                           HASH_LENGTH];

		int offset = 0;
		System.arraycopy(nonceInitator, 0, message2, offset, NONCE_SIZE);
		offset += NONCE_SIZE;
		System.arraycopy(myNonce, 0, message2, offset, NONCE_SIZE);
		offset += NONCE_SIZE;
		System.arraycopy(myExponential, 0, message2, offset, DiffieHellman.modulusLengthInBytes());
		offset += DiffieHellman.modulusLengthInBytes();

		System.arraycopy(r, 0, message2, offset, Node.SIGNATURE_PARAMETER_LENGTH);
		offset += Node.SIGNATURE_PARAMETER_LENGTH;
		System.arraycopy(s, 0, message2, offset, Node.SIGNATURE_PARAMETER_LENGTH);
		offset += Node.SIGNATURE_PARAMETER_LENGTH;

		System.arraycopy(authenticator, 0, message2, offset, HASH_LENGTH);

		sendAuthPacket(1,2,1,message2,pn,replyTo);
	}

	/*
	 * Assemble what will be the jfk-Authenticator : 
	 * computed over the Responder exponentials and the Nonces and
	 * used by the responder to verify that the round-trip has been done
	 * 
	 */
	private byte[] assembleJFKAuthenticator(byte[] gR, byte[] gI, byte[] nR, byte[] nI, byte[] address) {
		byte[] authData=new byte[gR.length + gI.length + nR.length + nI.length + address.length];
		int offset = 0;

		System.arraycopy(gR, 0, authData, offset ,gR.length);
		offset += gR.length;
		System.arraycopy(gI, 0, authData, offset, gI.length);
		offset += gI.length;
		System.arraycopy(nR, 0,authData, offset, nR.length);
		offset += nR.length;
		System.arraycopy(nI, 0,authData, offset, nI.length);
		offset += nI.length;
		System.arraycopy(address, 0, authData, offset, address.length);

		return authData;
	}

	/*
	 * Initiator Method:Message2
	 * @see{sendJFKMessage2} for packet format details
	 * 
	 * @param Payload
	 * @param The peer to which we need to send the packet
	 * @param The peerNode we are talking to
	 */

	private void processJFKMessage2(byte[] payload,PeerNode pn,Peer replyTo)
	{
		long t1=System.currentTimeMillis();
		if(logMINOR) Logger.minor(this, "Got a JFK(2) message, processing it - "+pn);
		// FIXME: follow the spec and send IDr' ?
		int expectedLength = NONCE_SIZE*2 + DiffieHellman.modulusLengthInBytes() + HASH_LENGTH*2;
		if(payload.length < expectedLength + 3) {
			Logger.error(this, "Packet too short from "+pn+": "+payload.length+" after decryption in JFK(2), should be "+(expectedLength + 3));
			return;
		}

		int inputOffset=3;
		byte[] nonceInitiator = new byte[NONCE_SIZE];
		System.arraycopy(payload, inputOffset, nonceInitiator, 0, NONCE_SIZE);
		inputOffset += NONCE_SIZE;
		byte[] nonceResponder = new byte[NONCE_SIZE];
		System.arraycopy(payload, inputOffset, nonceResponder, 0, NONCE_SIZE);
		inputOffset += NONCE_SIZE;

		byte[] hisExponential = new byte[DiffieHellman.modulusLengthInBytes()];
		System.arraycopy(payload, inputOffset, hisExponential, 0, DiffieHellman.modulusLengthInBytes());
		inputOffset += DiffieHellman.modulusLengthInBytes();
		NativeBigInteger _hisExponential = new NativeBigInteger(1,hisExponential);

		byte[] r = new byte[Node.SIGNATURE_PARAMETER_LENGTH];
		System.arraycopy(payload, inputOffset, r, 0, Node.SIGNATURE_PARAMETER_LENGTH);
		inputOffset += Node.SIGNATURE_PARAMETER_LENGTH;
		byte[] s = new byte[Node.SIGNATURE_PARAMETER_LENGTH];
		System.arraycopy(payload, inputOffset, s, 0, Node.SIGNATURE_PARAMETER_LENGTH);
		inputOffset += Node.SIGNATURE_PARAMETER_LENGTH;

		byte[] authenticator = new byte[HASH_LENGTH];
		System.arraycopy(payload, inputOffset, authenticator, 0, HASH_LENGTH);
		inputOffset += HASH_LENGTH;
		
		// Check try to find the authenticator in the cache.
		// If authenticator is already present, indicates duplicate/replayed message2
		// Now simply transmit the corresponding message3
		Object message3 = null;
		synchronized (authenticatorCache) {
			message3 = authenticatorCache.get(authenticator);
		}
		if(message3 != null) {
			Logger.normal(this, "We replayed a message from the cache (shouldn't happen often) -"+pn);
			sendAuthPacket(1, 2, 3, (byte[]) message3, pn, replyTo);
			return;
		}
		
		// sanity check
		byte[] myNi = null;
		synchronized (pn) {
			myNi = (byte[]) pn.jfkNoncesSent.get(replyTo);
		}
		// We don't except such a message;
		if(myNi == null) {
			Logger.normal(this, "We received an unexpected JFK(2) message from "+pn);
			return;
		} else if(!Arrays.equals(myNi, nonceInitiator)){
			if(logMINOR)
				Logger.minor(this, "Ignoring old JFK(2) (different nonce to the one we sent - either a timing artefact or an attempt to change the nonce)");
			return;
		}
		
		if(!DiffieHellman.checkDHExponentialValidity(this.getClass(), _hisExponential)) {
			Logger.error(this, "We can't accept the exponential "+pn+" sent us!! REDFLAG: IT CAN'T HAPPEN UNLESS AGAINST AN ACTIVE ATTACKER!!");
			return;
		}
		
		// Verify the DSA signature
		DSASignature remoteSignature = new DSASignature(new NativeBigInteger(1,r), new NativeBigInteger(1,s));
		// At that point we don't know if it's "him"; let's check it out
		byte[] locallyExpectedExponentials = assembleDHParams(_hisExponential, pn.peerCryptoGroup);

		if(!DSA.verify(pn.peerPubKey, remoteSignature, new NativeBigInteger(1, SHA256.digest(locallyExpectedExponentials)), false)) {
			Logger.error(this, "The signature verification has failed in JFK(2)!! "+pn);
			return;
		}
		
		// At this point we know it's from the peer, so we can report a packet received.
		pn.receivedPacket(true);
		
		sendJFKMessage3(1, 2, 3, nonceInitiator, nonceResponder, hisExponential, authenticator, pn, replyTo);

		long t2=System.currentTimeMillis();
		if((t2-t1)>500)
			Logger.error(this,"Message1 timeout error:Sending packet for"+pn.getPeer());
	}

	/*
	 * Initiator Method:Message3
	 * Process Message3
	 * Send the Initiator nonce,Responder nonce and DiffieHellman Exponential of the responder
	 * and initiator in the clear.(unVerifiedData)
	 * Send the authenticator which allows the responder to verify that a roundtrip occured
	 * Compute the signature of the unVerifiedData and encrypt it using a shared key
	 * which is derived from DHExponentials and the nonces; add a HMAC to protect it
	 * 
	 * Format:
	 * Ni, Nr, g^i, g^r
	 * Authenticator - HMAC{g^ir}(g^r, g^i, Nr, Ni, IP)
	 * HMAC{Ka}(cyphertext)
	 * IV + E{KE}[S{i}[Ni,Nr,g^i,g^r,idR, bootID, znoderefI], bootID, znoderefI]
	 * 
	 * 
	 * @param Payload
	 * @param The peer to which we need to send the packet
	 * @param The peerNode we are talking to
	 * @return byte Message3
	 */
	private void processJFKMessage3(byte[] payload, PeerNode pn,Peer replyTo, boolean oldOpennetPeer)
	{
		final long t1 = System.currentTimeMillis();
		if(logMINOR) Logger.minor(this, "Got a JFK(3) message, processing it - "+pn);
		
		BlockCipher c = null;
		try { c = new Rijndael(256, 256); } catch (UnsupportedCipherException e) {}
		int inputOffset=3;
		
		final int expectedLength =	NONCE_SIZE*2 + DiffieHellman.modulusLengthInBytes()*2 +
									HASH_LENGTH + // authenticator
									HASH_LENGTH + // HMAC of the cyphertext
									(c.getBlockSize() >> 3) + // IV
									HASH_LENGTH + // it's at least a signature
									8;			  // a bootid
		if(payload.length < expectedLength + 3) {
			Logger.error(this, "Packet too short from "+pn+": "+payload.length+" after decryption in JFK(3), should be "+(expectedLength + 3));
			return;
		}
		
		// Ni
		byte[] nonceInitiator = new byte[NONCE_SIZE];
		System.arraycopy(payload, inputOffset, nonceInitiator, 0, NONCE_SIZE);
		inputOffset += NONCE_SIZE;
		// Nr
		byte[] nonceResponder = new byte[NONCE_SIZE];
		System.arraycopy(payload, inputOffset, nonceResponder, 0, NONCE_SIZE);
		inputOffset += NONCE_SIZE;
		// g^i
		byte[] initiatorExponential = new byte[DiffieHellman.modulusLengthInBytes()];
		System.arraycopy(payload, inputOffset, initiatorExponential, 0, DiffieHellman.modulusLengthInBytes());
		inputOffset += DiffieHellman.modulusLengthInBytes();
		// g^r
		byte[] responderExponential = new byte[DiffieHellman.modulusLengthInBytes()];
		System.arraycopy(payload, inputOffset, responderExponential, 0, DiffieHellman.modulusLengthInBytes());
		inputOffset += DiffieHellman.modulusLengthInBytes();
		
		byte[] authenticator = new byte[HASH_LENGTH];
		System.arraycopy(payload, inputOffset, authenticator, 0, HASH_LENGTH);
		inputOffset += HASH_LENGTH;

		// FIXME: check the cache before or after the hmac verification ?
		// is it cheaper to wait for the lock on authenticatorCache or to verify the hmac ?
		HMAC mac = new HMAC(SHA256.getInstance());
		if(!mac.verify(getTransientKey(), assembleJFKAuthenticator(responderExponential, initiatorExponential, nonceResponder, nonceInitiator, replyTo.getAddress().getAddress()) , authenticator)) {
			Logger.error(this, "The HMAC doesn't match; let's discard the packet (either we rekeyed or we are victim of forgery)");
			return;
		} else if(pn.isConnected() && !pn.firstHandshake)
			return;
		
		// Check try to find the authenticator in the cache.
		// If authenticator is already present, indicates duplicate/replayed message3
		// Now simply transmit the corresponding message4
		Object message4 = null;
		synchronized (authenticatorCache) {
			message4 = authenticatorCache.get(authenticator);
		}
		if(message4 != null) {
			Logger.normal(this, "We replayed a message from the cache (shouldn't happen often) - "+pn);
			sendAuthPacket(1, 2, 3, (byte[]) message4, pn, replyTo);
			return;
		}
		
		NativeBigInteger _hisExponential = new NativeBigInteger(1, initiatorExponential);
		NativeBigInteger _ourExponential = new NativeBigInteger(1, responderExponential);
		
		byte[] hmac = new byte[HASH_LENGTH];
		System.arraycopy(payload, inputOffset, hmac, 0, HASH_LENGTH);
		inputOffset += HASH_LENGTH;
		
		DiffieHellmanLightContext ctx = findContextByExponential(_ourExponential);
		if(ctx == null) {
			// It ca be null if we have just rekeyed and we have more than one handshake/jfk run
			if(!pn.isConnected())
				Logger.error(this, "WTF? the HMAC verified but we don't know about that exponential! SHOULDN'T HAPPEN!");
			return;
		}
		BigInteger computedExponential = ctx.getHMACKey(_hisExponential, Global.DHgroupA);
		byte[] Ks = computeJFKSharedKey(computedExponential, nonceInitiator, nonceResponder, "0");
		byte[] Ke = computeJFKSharedKey(computedExponential, nonceInitiator, nonceResponder, "1");
		byte[] Ka = computeJFKSharedKey(computedExponential, nonceInitiator, nonceResponder, "2");
		c.initialize(Ke);
		final PCFBMode pk = PCFBMode.create(c);
		int ivLength = pk.lengthIV();
		int decypheredPayloadOffset = 0;
		// We compute the HMAC of ("I"+cyphertext) : the cyphertext includes the IV!
		byte[] decypheredPayload = new byte[JFK_PREFIX_INITIATOR.length + payload.length - inputOffset];
		System.arraycopy(JFK_PREFIX_INITIATOR, 0, decypheredPayload, decypheredPayloadOffset, JFK_PREFIX_INITIATOR.length);
		decypheredPayloadOffset += JFK_PREFIX_INITIATOR.length;
		System.arraycopy(payload, inputOffset, decypheredPayload, decypheredPayloadOffset, decypheredPayload.length-decypheredPayloadOffset);
		if(!mac.verify(Ka, decypheredPayload, hmac)) {
			Logger.error(this, "The digest-HMAC doesn't match; let's discard the packet JFK(3) - "+pn);
			return;
		}
		
		// Get the IV
		pk.reset(decypheredPayload, decypheredPayloadOffset);
		decypheredPayloadOffset += ivLength;
		// Decrypt the payload
		pk.blockDecipher(decypheredPayload, decypheredPayloadOffset, decypheredPayload.length-decypheredPayloadOffset);
		/*
		 * DecipheredData Format:
		 * Signature-r,s
		 * Node Data (starting with BootID)
		 */
		byte[] r = new byte[Node.SIGNATURE_PARAMETER_LENGTH];
		System.arraycopy(decypheredPayload, decypheredPayloadOffset, r, 0, Node.SIGNATURE_PARAMETER_LENGTH);
		decypheredPayloadOffset += Node.SIGNATURE_PARAMETER_LENGTH;
		byte[] s = new byte[Node.SIGNATURE_PARAMETER_LENGTH];
		System.arraycopy(decypheredPayload, decypheredPayloadOffset, s, 0, Node.SIGNATURE_PARAMETER_LENGTH);
		decypheredPayloadOffset += Node.SIGNATURE_PARAMETER_LENGTH;
		byte[] data = new byte[decypheredPayload.length - decypheredPayloadOffset];
		System.arraycopy(decypheredPayload, decypheredPayloadOffset, data, 0, decypheredPayload.length - decypheredPayloadOffset);
		long bootID = Fields.bytesToLong(data);
		byte[] hisRef = new byte[data.length -8];
		System.arraycopy(data, 8, hisRef, 0, hisRef.length);
		
		// verify the signature
		DSASignature remoteSignature = new DSASignature(new NativeBigInteger(1,r), new NativeBigInteger(1,s)); 
		if(!DSA.verify(pn.peerPubKey, remoteSignature, new NativeBigInteger(1, SHA256.digest(assembleDHParams(nonceInitiator, nonceResponder, _hisExponential, _ourExponential, crypto.myIdentity, data))), false)) {
			Logger.error(this, "The signature verification has failed!! JFK(3) - "+pn);
			return;
		}
		
		// At this point we know it's from the peer, so we can report a packet received.
		pn.receivedPacket(true);
		
		// Send reply
		sendJFKMessage4(1, 2, 3, nonceInitiator, nonceResponder,initiatorExponential, responderExponential, c, Ke, Ka, authenticator, hisRef, pn, replyTo);
		c.initialize(Ks);
		
		// Promote if necessary
		boolean dontWant = false;
		if(oldOpennetPeer) {
			OpennetManager opennet = node.getOpennet();
			if(opennet == null) {
				Logger.normal(this, "Dumping incoming old-opennet peer as opennet just turned off: "+pn+".");
				return;
			}
			if(!opennet.wantPeer(pn, true)) {
				Logger.normal(this, "No longer want peer "+pn+" - dumping it after connecting");
				dontWant = true;
			}
			// wantPeer will call node.peers.addPeer(), we don't have to.
		}
		
		if(pn.completedHandshake(bootID, hisRef, 0, hisRef.length, c, Ks, replyTo, true)) {
			if(dontWant)
				node.peers.disconnect(pn, true, false);
			else
				pn.maybeSendInitialMessages();
		} else {
			Logger.error(this, "Handshake failure! with "+pn);
		}
		
		final long t2=System.currentTimeMillis();
		if((t2-t1)>500)
			Logger.error(this,"Message3 Sending packet for"+pn.getPeer()+" took "+TimeUtil.formatTime(t2-t1, 3, true));
	}
	
	/*
	 * Responder Method:Message4
	 * Process Message4
	 * 
	 * Format:
	 * HMAC{Ka}[cyphertext]
	 * IV + E{Ke}[S{R}[Ni, Nr, g^i, g^r, IDi, bootID, znoderefR, znoderefI], bootID, znoderefR]
	 * 
	 * @param Payload
	 * @param The peerNode we are talking to
	 * @param replyTo the Peer we are replying to
	 */
	private void processJFKMessage4(byte[] payload, PeerNode pn, Peer replyTo, boolean oldOpennetPeer)
	{
		final long t1 = System.currentTimeMillis();
		if(logMINOR) Logger.minor(this, "Got a JFK(4) message, processing it - "+pn);
		BlockCipher c = null;
		try { c = new Rijndael(256, 256); } catch (UnsupportedCipherException e) {}
		int inputOffset=3;
		
		final int expectedLength =	HASH_LENGTH + // HMAC of the cyphertext
									(c.getBlockSize() >> 3) + // IV
									HASH_LENGTH + // the signature
									pn.jfkMyRef.length			  // the bootid + mynoderef
									;
		if(payload.length < expectedLength + 3) {
			Logger.error(this, "Packet too short from "+pn+": "+payload.length+" after decryption in JFK(4), should be "+(expectedLength + 3));
			return;
		}
		byte[] jfkBuffer = pn.getJFKBuffer();
		if(jfkBuffer == null) {
			Logger.normal(this, "We have already handled this message... might be a replay or a bug - "+pn);
			return;
		}

		byte[] hmac = new byte[HASH_LENGTH];
		System.arraycopy(payload, inputOffset, hmac, 0, HASH_LENGTH);
		inputOffset += HASH_LENGTH;
		
		c.initialize(pn.jfkKe);
		final PCFBMode pk = PCFBMode.create(c);
		int ivLength = pk.lengthIV();
		int decypheredPayloadOffset = 0;
		// We compute the HMAC of ("R"+cyphertext) : the cyphertext includes the IV!
		byte[] decypheredPayload = new byte[JFK_PREFIX_RESPONDER.length + (payload.length-inputOffset)];
		System.arraycopy(JFK_PREFIX_RESPONDER, 0, decypheredPayload, decypheredPayloadOffset, JFK_PREFIX_RESPONDER.length);
		decypheredPayloadOffset += JFK_PREFIX_RESPONDER.length;
		System.arraycopy(payload, inputOffset, decypheredPayload, decypheredPayloadOffset, payload.length-inputOffset);
		HMAC mac = new HMAC(SHA256.getInstance());
		if(!mac.verify(pn.jfkKa, decypheredPayload, hmac)) {
			Logger.error(this, "The digest-HMAC doesn't match; let's discard the packet");
			return;
		}
		
		// Get the IV
		pk.reset(decypheredPayload, decypheredPayloadOffset);
		decypheredPayloadOffset += ivLength;
		// Decrypt the payload
		pk.blockDecipher(decypheredPayload, decypheredPayloadOffset, decypheredPayload.length - decypheredPayloadOffset);
		/*
		 * DecipheredData Format:
		 * Signature-r,s
		 * bootID, znoderef
		 */
		byte[] r = new byte[Node.SIGNATURE_PARAMETER_LENGTH];
		System.arraycopy(decypheredPayload, decypheredPayloadOffset, r, 0, Node.SIGNATURE_PARAMETER_LENGTH);
		decypheredPayloadOffset += Node.SIGNATURE_PARAMETER_LENGTH;
		byte[] s = new byte[Node.SIGNATURE_PARAMETER_LENGTH];
		System.arraycopy(decypheredPayload, decypheredPayloadOffset, s, 0, Node.SIGNATURE_PARAMETER_LENGTH);
		decypheredPayloadOffset += Node.SIGNATURE_PARAMETER_LENGTH;
		byte[] data = new byte[decypheredPayload.length - decypheredPayloadOffset];
		System.arraycopy(decypheredPayload, decypheredPayloadOffset, data, 0, decypheredPayload.length - decypheredPayloadOffset);
		long bootID = Fields.bytesToLong(data);
		byte[] hisRef = new byte[data.length - pn.jfkMyRef.length - 8];
		System.arraycopy(data, 8, hisRef, 0, hisRef.length);
		
		// verify the signature
		DSASignature remoteSignature = new DSASignature(new NativeBigInteger(1,r), new NativeBigInteger(1,s));
		byte[] locallyGeneratedText = new byte[NONCE_SIZE * 2 + DiffieHellman.modulusLengthInBytes() * 2 + crypto.myIdentity.length + 8 /*bootID*/ + hisRef.length + pn.jfkMyRef.length];
		int bufferOffset = NONCE_SIZE * 2 + DiffieHellman.modulusLengthInBytes()*2;
		System.arraycopy(jfkBuffer, 0, locallyGeneratedText, 0, bufferOffset);
		System.arraycopy(crypto.myIdentity, 0, locallyGeneratedText, bufferOffset, crypto.myIdentity.length);
		bufferOffset += crypto.myIdentity.length;
		// bootID
		System.arraycopy(data, 0, locallyGeneratedText, bufferOffset, data.length - pn.jfkMyRef.length);
		bufferOffset += data.length - pn.jfkMyRef.length;
		System.arraycopy(pn.jfkMyRef, 0, locallyGeneratedText, bufferOffset, pn.jfkMyRef.length);
		if(!DSA.verify(pn.peerPubKey, remoteSignature, new NativeBigInteger(1, SHA256.digest(locallyGeneratedText)), false)) {
			Logger.error(this, "The signature verification has failed!! JFK(4) -"+pn);
			return;
		}
		
		// Promote if necessary
		boolean dontWant = false;
		if(oldOpennetPeer) {
			OpennetManager opennet = node.getOpennet();
			if(opennet == null) {
				Logger.normal(this, "Dumping incoming old-opennet peer as opennet just turned off: "+pn+".");
				return;
			}
			if(!opennet.wantPeer(pn, true)) {
				Logger.normal(this, "No longer want peer "+pn+" - dumping it after connecting");
				dontWant = true;
			}
			// wantPeer will call node.peers.addPeer(), we don't have to.
		}
		
		// We change the key
		c.initialize(pn.jfkKs);
		if(pn.completedHandshake(bootID, data, 8, data.length - 8, c, pn.jfkKs, replyTo, false)) {
			if(dontWant)
				node.peers.disconnect(pn, true, false);
			else
				pn.maybeSendInitialMessages();
		} else {
			Logger.error(this, "Handshake failed!");
		}
		
		// cleanup
                // FIXME: maybe we should copy zeros/garbage into it before leaving it to the GC
		pn.setJFKBuffer(null);
		pn.jfkKa = null;
		pn.jfkKe = null;
		pn.jfkKs = null;
		// We want to clear it here so that new handshake requests
		// will be sent with a different DH pair
		pn.setKeyAgreementSchemeContext(null);
		synchronized (pn) {
			// FIXME TRUE MULTI-HOMING: winner-takes-all, kill all other connection attempts since we can't deal with multiple active connections
			// Also avoids leaking
			pn.jfkNoncesSent.clear();
		}
		
		final long t2=System.currentTimeMillis();
		if((t2-t1)>500)
			Logger.error(this,"Message3 timeout error:Sending packet for"+pn.getPeer());
	}

	/*
	 * Format:
	 * Ni, Nr, g^i, g^r
	 * Authenticator - HMAC{g^ir}(g^r, Nr, Ni, IP)
	 * HMAC{Ka}(cyphertext)
	 * IV + E{KE}[S{i}[Ni,Nr,g^i,g^r,idR, bootID, znoderefI], bootID, znoderefI]
	 * 
	 */

	private void sendJFKMessage3(int version,int negType,int phase,byte[] nonceInitiator,byte[] nonceResponder,byte[] hisExponential, byte[] authenticator, final PeerNode pn, final Peer replyTo)
	{
		if(logMINOR) Logger.minor(this, "Sending a JFK(3) message to "+pn);
		BlockCipher c = null;
		try { c = new Rijndael(256, 256); } catch (UnsupportedCipherException e) {}
		DiffieHellmanLightContext ctx = (DiffieHellmanLightContext) pn.getKeyAgreementSchemeContext();
		if(ctx == null) return;
		byte[] ourExponential = stripBigIntegerToNetworkFormat(ctx.myExponential);
		pn.jfkMyRef = crypto.myCompressedSetupRef();
		byte[] data = new byte[8 + pn.jfkMyRef.length];
		System.arraycopy(Fields.longToBytes(node.bootID), 0, data, 0, 8);
		System.arraycopy(pn.jfkMyRef, 0, data, 8, pn.jfkMyRef.length);
		final byte[] message3 = new byte[NONCE_SIZE*2 + // nI, nR
		                           DiffieHellman.modulusLengthInBytes()*2 + // g^i, g^r
		                           HASH_LENGTH + // authenticator
		                           HASH_LENGTH + // HMAC(cyphertext)
		                           (c.getBlockSize() >> 3) + // IV
		                           Node.SIGNATURE_PARAMETER_LENGTH * 2 + // Signature (R,S)
		                           data.length]; // The bootid+noderef
		int offset = 0;
		// Ni
		System.arraycopy(nonceInitiator, 0, message3, offset, NONCE_SIZE);
		offset += NONCE_SIZE;
		// Nr
		System.arraycopy(nonceResponder, 0, message3, offset, NONCE_SIZE);
		offset += NONCE_SIZE;
		// g^i
		System.arraycopy(ourExponential, 0,message3, offset, ourExponential.length);
		offset += ourExponential.length;
		// g^r
		System.arraycopy(hisExponential, 0,message3, offset, hisExponential.length);
		offset += hisExponential.length;

		// Authenticator
		System.arraycopy(authenticator, 0, message3, offset, HASH_LENGTH);
		offset += HASH_LENGTH;
		/*
		 * Digital Signature of the message with the private key belonging to the initiator/responder
		 * It is assumed to be non-message recovering
		 */
		NativeBigInteger _ourExponential = new NativeBigInteger(1,ourExponential);
		NativeBigInteger _hisExponential = new NativeBigInteger(1,hisExponential);
		// save parameters so that we can verify message4
		byte[] toSign = assembleDHParams(nonceInitiator, nonceResponder, _ourExponential, _hisExponential, pn.identity, data);
		pn.setJFKBuffer(toSign);
		DSASignature localSignature = crypto.sign(SHA256.digest(toSign));
		byte[] r = localSignature.getRBytes(Node.SIGNATURE_PARAMETER_LENGTH);
		byte[] s = localSignature.getSBytes(Node.SIGNATURE_PARAMETER_LENGTH);
		
		BigInteger computedExponential = ctx.getHMACKey(_hisExponential, Global.DHgroupA);
		pn.jfkKs = computeJFKSharedKey(computedExponential, nonceInitiator, nonceResponder, "0");
		pn.jfkKe = computeJFKSharedKey(computedExponential, nonceInitiator, nonceResponder, "1");
		pn.jfkKa = computeJFKSharedKey(computedExponential, nonceInitiator, nonceResponder, "2");
		c.initialize(pn.jfkKe);
		PCFBMode pcfb = PCFBMode.create(c);
		int ivLength = pcfb.lengthIV();
		byte[] iv = new byte[ivLength];
		node.random.nextBytes(iv);
		pcfb.reset(iv);
		int cleartextOffset = 0;
		byte[] prefix = null;
		try { prefix = "I".getBytes("UTF-8"); } catch (UnsupportedEncodingException e) {}
		
		byte[] cleartext = new byte[prefix.length + ivLength + Node.SIGNATURE_PARAMETER_LENGTH * 2 + data.length];
		System.arraycopy(prefix, 0, cleartext, cleartextOffset, prefix.length);
		cleartextOffset += prefix.length;
		System.arraycopy(iv, 0, cleartext, cleartextOffset, ivLength);
		cleartextOffset += ivLength;
		System.arraycopy(r, 0, cleartext, cleartextOffset, Node.SIGNATURE_PARAMETER_LENGTH);
		cleartextOffset += Node.SIGNATURE_PARAMETER_LENGTH;
		System.arraycopy(s, 0, cleartext, cleartextOffset, Node.SIGNATURE_PARAMETER_LENGTH);
		cleartextOffset += Node.SIGNATURE_PARAMETER_LENGTH;
		System.arraycopy(data, 0, cleartext, cleartextOffset, data.length);
		cleartextOffset += data.length;
		
		int cleartextToEncypherOffset = prefix.length + ivLength;
		pcfb.blockEncipher(cleartext, cleartextToEncypherOffset, cleartext.length-cleartextToEncypherOffset);
		
		// We compute the HMAC of (prefix + cyphertext) Includes the IV!
		HMAC mac = new HMAC(SHA256.getInstance());
		byte[] hmac = mac.mac(pn.jfkKa, cleartext, HASH_LENGTH);
		
		// copy stuffs back to the message
		System.arraycopy(hmac, 0, message3, offset, HASH_LENGTH);
		offset += HASH_LENGTH;
		System.arraycopy(iv, 0, message3, offset, ivLength);
		offset += ivLength;
		System.arraycopy(cleartext, cleartextToEncypherOffset, message3, offset, cleartext.length-cleartextToEncypherOffset);
		
		// cache the message
		synchronized (authenticatorCache) {
			if(!maybeResetTransientKey())
				authenticatorCache.put(authenticator,message3);
		}		
		sendAuthPacket(1, 2, 2, message3, pn, replyTo);
		
		/* Re-send the packet after 5sec if we don't get any reply */
		node.getTicker().queueTimedJob(new Runnable() {
			public void run() {
				if(pn.timeLastConnected() >= pn.lastReceivedPacketTime()) {
					sendAuthPacket(1, 2, 2, message3, pn, replyTo);
				}
			}
		}, 5*1000);
	}

	
	/*
	 * Format:
	 * HMAC{Ka}(cyphertext)
	 * IV, E{Ke}[S{R}[Ni,Nr,g^i,g^r,idI, bootID, znoderefR, znoderefI],bootID,znoderefR]
	 * 
	 */
	private void sendJFKMessage4(int version,int negType,int phase,byte[] nonceInitiator,byte[] nonceResponder,byte[] initiatorExponential,byte[] responderExponential, BlockCipher c, byte[] Ke, byte[] Ka, byte[] authenticator, byte[] hisRef, PeerNode pn, Peer replyTo)
	{
		if(logMINOR)
			Logger.minor(this, "Sending a JFK(4) message to "+pn);
		NativeBigInteger _responderExponential = new NativeBigInteger(1,responderExponential);
		NativeBigInteger _initiatorExponential = new NativeBigInteger(1,initiatorExponential);
		
		byte[] myRef = crypto.myCompressedSetupRef();
		byte[] data = new byte[8 + myRef.length + hisRef.length];
		System.arraycopy(Fields.longToBytes(node.bootID), 0, data, 0, 8);
		System.arraycopy(myRef, 0, data, 8, myRef.length);
		System.arraycopy(hisRef, 0, data, 8 + myRef.length, hisRef.length);
		
		DSASignature localSignature = crypto.sign(SHA256.digest(assembleDHParams(nonceInitiator, nonceResponder, _initiatorExponential, _responderExponential, pn.identity, data)));
		byte[] r = localSignature.getRBytes(Node.SIGNATURE_PARAMETER_LENGTH);
		byte[] s = localSignature.getSBytes(Node.SIGNATURE_PARAMETER_LENGTH);
		
		PCFBMode pk=PCFBMode.create(c);
		int ivLength = pk.lengthIV();
		byte[] iv=new byte[ivLength];
		node.random.nextBytes(iv);
		pk.reset(iv);
		byte[] prefix = null;
		try { prefix = "R".getBytes("UTF-8"); } catch (UnsupportedEncodingException e) {}

		byte[] cyphertext = new byte[prefix.length + ivLength + Node.SIGNATURE_PARAMETER_LENGTH * 2 + data.length];
		int cleartextOffset = 0;
		System.arraycopy(prefix, 0, cyphertext, cleartextOffset, prefix.length);
		cleartextOffset += prefix.length;
		System.arraycopy(iv, 0, cyphertext, cleartextOffset, ivLength);
		cleartextOffset += ivLength;
		System.arraycopy(r, 0, cyphertext, cleartextOffset, Node.SIGNATURE_PARAMETER_LENGTH);
		cleartextOffset += Node.SIGNATURE_PARAMETER_LENGTH;
		System.arraycopy(s, 0, cyphertext, cleartextOffset, Node.SIGNATURE_PARAMETER_LENGTH);
		cleartextOffset += Node.SIGNATURE_PARAMETER_LENGTH;
		System.arraycopy(data, 0, cyphertext, cleartextOffset, data.length);
		cleartextOffset += data.length;
		// Now encrypt the cleartext[Signature]
		int cleartextToEncypherOffset = prefix.length + ivLength;
		pk.blockEncipher(cyphertext, cleartextToEncypherOffset, cyphertext.length - cleartextToEncypherOffset);
		
		// We compute the HMAC of (prefix + iv + signature)
		HMAC mac = new HMAC(SHA256.getInstance());
		byte[] hmac = mac.mac(Ka, cyphertext, HASH_LENGTH);
		
		// Message4 = hmac + IV + encryptedSignature
		byte[] message4 = new byte[HASH_LENGTH + ivLength + (cyphertext.length - cleartextToEncypherOffset)]; 
		int offset = 0;
		System.arraycopy(hmac, 0, message4, offset, HASH_LENGTH);
		offset += HASH_LENGTH;
		System.arraycopy(iv, 0, message4, offset, ivLength);
		offset += ivLength;
		System.arraycopy(cyphertext, cleartextToEncypherOffset, message4, offset, cyphertext.length - cleartextToEncypherOffset);
		
		// cache the message
		synchronized (authenticatorCache) {
			if(!maybeResetTransientKey())
				authenticatorCache.put(authenticator, message4);
		}
		
		sendAuthPacket(1, 2, 3, message4, pn, replyTo);
	}

	/**
	 * Send an auth packet.
	 */
	private void sendAuthPacket(int version, int negType, int phase, byte[] data, PeerNode pn, Peer replyTo) {
		long now = System.currentTimeMillis();
		long delta = now - pn.lastSentPacketTime();
		byte[] output = new byte[data.length+3];
		output[0] = (byte) version;
		output[1] = (byte) negType;
		output[2] = (byte) phase;
		System.arraycopy(data, 0, output, 3, data.length);
		if(logMINOR) Logger.minor(this, "Sending auth packet for "+pn.getPeer()+" (phase="+phase+", ver="+version+", nt="+negType+") (last packet sent "+TimeUtil.formatTime(delta, 2, true)+" ago) to "+replyTo+" data.length="+data.length);
		sendAuthPacket(output, pn, replyTo);
	}

	/**
	 * Send an auth packet (we have constructed the payload, now hash it, pad it, encrypt it).
	 */
	private void sendAuthPacket(byte[] output, PeerNode pn, Peer replyTo) {
		int length = output.length;
		if(length > sock.getMaxPacketSize()) {
			throw new IllegalStateException("Cannot send auth packet: too long: "+length);
		}
		BlockCipher cipher = pn.outgoingSetupCipher;
		if(logMINOR) Logger.minor(this, "Outgoing cipher: "+HexUtil.bytesToHex(pn.outgoingSetupKey));
		PCFBMode pcfb = PCFBMode.create(cipher);
		int paddingLength = node.fastWeakRandom.nextInt(100);
		byte[] iv = new byte[pcfb.lengthIV()];
		node.random.nextBytes(iv);
		byte[] hash = SHA256.digest(output);
		if(logMINOR) Logger.minor(this, "Data hash: "+HexUtil.bytesToHex(hash));
		byte[] data = new byte[iv.length + hash.length + 2 /* length */ + output.length + paddingLength];
		pcfb.reset(iv);
		System.arraycopy(iv, 0, data, 0, iv.length);
		pcfb.blockEncipher(hash, 0, hash.length);
		System.arraycopy(hash, 0, data, iv.length, hash.length);
		if(logMINOR) Logger.minor(this, "Payload length: "+length);
		data[hash.length+iv.length] = (byte) pcfb.encipher((byte)(length>>8));
		data[hash.length+iv.length+1] = (byte) pcfb.encipher((byte)length);
		pcfb.blockEncipher(output, 0, output.length);
		System.arraycopy(output, 0, data, hash.length+iv.length+2, output.length);
		byte[] random = new byte[paddingLength];
		node.fastWeakRandom.nextBytes(random);
		System.arraycopy(random, 0, data, hash.length+iv.length+2+output.length, random.length);
		try {
			sendPacket(data, replyTo, pn, 0);
		} catch (LocalAddressException e) {
			Logger.error(this, "Tried to send auth packet to local address: "+replyTo+" for "+pn+" - maybe you should set allowLocalAddresses for this peer??");
		}
	}

	private void sendPacket(byte[] data, Peer replyTo, PeerNode pn, int alreadyReportedBytes) throws LocalAddressException {
		if(pn.isIgnoreSource()) {
			Peer p = pn.getPeer();
			if(p != null) replyTo = p;
		}
		sock.sendPacket(data, replyTo, pn.allowLocalAddresses());
		pn.reportOutgoingPacket(data, 0, data.length, System.currentTimeMillis());
		node.outputThrottle.forceGrab(data.length - alreadyReportedBytes);
	}

	/**
	 * Should we log an error for an event that could easily be
	 * caused by a handshake across a restart boundary?
	 */
	private boolean shouldLogErrorInHandshake() {
		long now = System.currentTimeMillis();
		if(now - node.startupTime < Node.HANDSHAKE_TIMEOUT*2)
			return false;
		return true;
	}

	/**
	 * Try to process an incoming packet with a given PeerNode.
	 * We need to know where the packet has come from in order to
	 * decrypt and authenticate it.
	 */
	private boolean tryProcess(byte[] buf, int offset, int length, KeyTracker tracker, long now) {
		// Need to be able to call with tracker == null to simplify code above
		if(tracker == null) {
			if(Logger.shouldLog(Logger.DEBUG, this)) Logger.debug(this, "Tracker == null");
			return false;
		}
		if(logMINOR) Logger.minor(this,"Entering tryProcess: "+Fields.hashCode(buf)+ ',' +offset+ ',' +length+ ',' +tracker);
		/**
		 * E_pcbc_session(H(seq+random+data)) E_pcfb_session(seq+random+data)
		 * 
		 * So first two blocks are the hash, PCBC encoded (meaning the
		 * first one is ECB, and the second one is ECB XORed with the 
		 * ciphertext and plaintext of the first block).
		 */
		BlockCipher sessionCipher = tracker.sessionCipher;
		if(sessionCipher == null) {
			if(logMINOR) Logger.minor(this, "No cipher");
			return false;
		}
		if(logMINOR) Logger.minor(this, "Decrypting with "+HexUtil.bytesToHex(tracker.sessionKey));
		int blockSize = sessionCipher.getBlockSize() >> 3;
		if(sessionCipher.getKeySize() != sessionCipher.getBlockSize())
			throw new IllegalStateException("Block size must be equal to key size");

		if(HASH_LENGTH != blockSize)
			throw new IllegalStateException("Block size must be digest length!");

		byte[] packetHash = new byte[HASH_LENGTH];
		System.arraycopy(buf, offset, packetHash, 0, HASH_LENGTH);

		// Decrypt the sequence number and see if it's plausible
		// Verify the hash later

		PCFBMode pcfb;
		pcfb = PCFBMode.create(sessionCipher);
		// Set IV to the hash, after it is encrypted
		pcfb.reset(packetHash);
		//Logger.minor(this,"IV:\n"+HexUtil.bytesToHex(packetHash));

		byte[] seqBuf = new byte[4];
		System.arraycopy(buf, offset+HASH_LENGTH, seqBuf, 0, 4);
		//Logger.minor(this, "Encypted sequence number: "+HexUtil.bytesToHex(seqBuf));
		pcfb.blockDecipher(seqBuf, 0, 4);
		//Logger.minor(this, "Decrypted sequence number: "+HexUtil.bytesToHex(seqBuf));

		int seqNumber = ((((((seqBuf[0] & 0xff) << 8)
				+ (seqBuf[1] & 0xff)) << 8) + 
				(seqBuf[2] & 0xff)) << 8) +
				(seqBuf[3] & 0xff);

		int targetSeqNumber = tracker.highestReceivedIncomingSeqNumber();
		if(logMINOR) Logger.minor(this, "Seqno: "+seqNumber+" (highest seen "+targetSeqNumber+") receiving packet from "+tracker.pn.getPeer());

		if(seqNumber == -1) {
			// Ack/resendreq-only packet
		} else {
			// Now is it credible?
			// As long as it's within +/- 256, this is valid.
			if((targetSeqNumber != -1) && (Math.abs(targetSeqNumber - seqNumber) > MAX_PACKETS_IN_FLIGHT))
				return false;
		}
		if(logMINOR) Logger.minor(this, "Sequence number received: "+seqNumber);

		// Plausible, so lets decrypt the rest of the data

		byte[] plaintext = new byte[length-(4+HASH_LENGTH)];
		System.arraycopy(buf, offset+HASH_LENGTH+4, plaintext, 0, length-(HASH_LENGTH+4));

		pcfb.blockDecipher(plaintext, 0, length-(HASH_LENGTH+4));

		//Logger.minor(this, "Plaintext:\n"+HexUtil.bytesToHex(plaintext));

		MessageDigest md = SHA256.getMessageDigest();
		md.update(seqBuf);
		md.update(plaintext);
		byte[] realHash = md.digest();
		SHA256.returnMessageDigest(md); md = null;

		// Now decrypt the original hash

		byte[] temp = new byte[blockSize];
		System.arraycopy(buf, offset, temp, 0, blockSize);
		sessionCipher.decipher(temp, temp);
		System.arraycopy(temp, 0, packetHash, 0, blockSize);

		// Check the hash
		if(!Arrays.equals(packetHash, realHash)) {
			if(logMINOR) Logger.minor(this, "Packet possibly from "+tracker+" hash does not match:\npacketHash="+
					HexUtil.bytesToHex(packetHash)+"\n  realHash="+HexUtil.bytesToHex(realHash)+" ("+(length-HASH_LENGTH)+" bytes payload)");
			return false;
		}

		// Verify
		tracker.pn.verified(tracker);

		for(int i=0;i<HASH_LENGTH;i++) {
			packetHash[i] ^= buf[offset+i];
		}
		if(logMINOR) Logger.minor(this, "Contributing entropy");
		node.random.acceptEntropyBytes(myPacketDataSource, packetHash, 0, HASH_LENGTH, 0.5);
		if(logMINOR) Logger.minor(this, "Contributed entropy");

		// Lots more to do yet!
		processDecryptedData(plaintext, seqNumber, tracker, length - plaintext.length);
		tracker.pn.reportIncomingPacket(buf, offset, length, now);
		return true;
	}

	/**
	 * Process an incoming packet, once it has been decrypted.
	 * @param decrypted The packet's contents.
	 * @param seqNumber The detected sequence number of the packet.
	 * @param tracker The KeyTracker responsible for the key used to encrypt the packet.
	 */
	private void processDecryptedData(byte[] decrypted, int seqNumber, KeyTracker tracker, int overhead) {
		/**
		 * Decoded format:
		 * 1 byte - version number (0)
		 * 1 byte - number of acknowledgements
		 * Acknowledgements:
		 * 1 byte - ack (+ve integer, subtract from seq-1) to get seq# to ack
		 * 
		 * 1 byte - number of explicit retransmit requests
		 * Explicit retransmit requests:
		 * 1 byte - retransmit request (+ve integer, subtract from seq-1) to get seq# to resend
		 * 
		 * 1 byte - number of packets forgotten
		 * Forgotten packets:
		 * 1 byte - forgotten packet seq# (+ve integer, subtract from seq-1) to get seq# lost
		 * 
		 * 1 byte - number of messages
		 * 2 bytes - message length
		 * first message
		 * 2 bytes - second message length
		 * second message
		 * ...
		 * last message
		 * anything beyond this point is padding, to be ignored
		 */ 

		// Use ptr to simplify code
		int ptr = RANDOM_BYTES_LENGTH;

		int version = decrypted[ptr++];
		if(ptr > decrypted.length) {
			Logger.error(this, "Packet not long enough at byte "+ptr+" on "+tracker);
			return;
		}
		if(version != 0) {
			Logger.error(this,"Packet from "+tracker+" decrypted but invalid version: "+version);
			return;
		}

		/** Highest sequence number sent - not the same as this packet's seq number */
		int realSeqNumber = seqNumber;

		if(seqNumber == -1) {
			if(ptr+4 > decrypted.length) {
				Logger.error(this, "Packet not long enough at byte "+ptr+" on "+tracker);
				return;
			}
			realSeqNumber =
				((((((decrypted[ptr+0] & 0xff) << 8) + (decrypted[ptr+1] & 0xff)) << 8) + 
						(decrypted[ptr+2] & 0xff)) << 8) + (decrypted[ptr+3] & 0xff);
			ptr+=4;
		} else {
			if(ptr > decrypted.length) {
				Logger.error(this, "Packet not long enough at byte "+ptr+" on "+tracker);
				return;
			}
			realSeqNumber = seqNumber + (decrypted[ptr++] & 0xff);
		}
		if(logMINOR)
			Logger.minor(this, "Real sequence number: "+realSeqNumber);

		//Logger.minor(this, "Reference seq number: "+HexUtil.bytesToHex(decrypted, ptr, 4));

		if(ptr+4 > decrypted.length) {
			Logger.error(this, "Packet not long enough at byte "+ptr+" on "+tracker);
			return;
		}
		int referenceSeqNumber = 
			((((((decrypted[ptr+0] & 0xff) << 8) + (decrypted[ptr+1] & 0xff)) << 8) + 
					(decrypted[ptr+2] & 0xff)) << 8) + (decrypted[ptr+3] & 0xff);
		ptr+=4;

		if(logMINOR) Logger.minor(this, "Reference sequence number: "+referenceSeqNumber);

		int ackCount = decrypted[ptr++] & 0xff;
		if(logMINOR) Logger.minor(this, "Acks: "+ackCount);

		int[] acks = new int[ackCount];
		for(int i=0;i<ackCount;i++) {
			int offset = decrypted[ptr++] & 0xff;
			if(ptr > decrypted.length) {
				Logger.error(this, "Packet not long enough at byte "+ptr+" on "+tracker);
				return;
			}
			acks[i] = referenceSeqNumber - offset;
		}

		tracker.acknowledgedPackets(acks);

		int retransmitCount = decrypted[ptr++] & 0xff;
		if(logMINOR) Logger.minor(this, "Retransmit requests: "+retransmitCount);

		for(int i=0;i<retransmitCount;i++) {
			int offset = decrypted[ptr++] & 0xff;
			if(ptr > decrypted.length) {
				Logger.error(this, "Packet not long enough at byte "+ptr+" on "+tracker);
			}
			int realSeqNo = referenceSeqNumber - offset;
			if(logMINOR) Logger.minor(this, "RetransmitRequest: "+realSeqNo);
			tracker.resendPacket(realSeqNo);
		}

		int ackRequestsCount = decrypted[ptr++] & 0xff;
		if(logMINOR) Logger.minor(this, "Ack requests: "+ackRequestsCount);

		// These two are relative to our outgoing packet number
		// Because they relate to packets we have sent.
		for(int i=0;i<ackRequestsCount;i++) {
			int offset = decrypted[ptr++] & 0xff;
			if(ptr > decrypted.length) {
				Logger.error(this, "Packet not long enough at byte "+ptr+" on "+tracker);
			}
			int realSeqNo = realSeqNumber - offset;
			if(logMINOR) Logger.minor(this, "AckRequest: "+realSeqNo);
			tracker.receivedAckRequest(realSeqNo);
		}

		int forgottenCount = decrypted[ptr++] & 0xff;
		if(logMINOR) Logger.minor(this, "Forgotten packets: "+forgottenCount);

		for(int i=0;i<forgottenCount;i++) {
			int offset = decrypted[ptr++] & 0xff;
			if(ptr > decrypted.length) {
				Logger.error(this, "Packet not long enough at byte "+ptr+" on "+tracker);
			}
			int realSeqNo = realSeqNumber - offset;
			tracker.destForgotPacket(realSeqNo);
		}

		tracker.pn.receivedPacket(false); // Must keep the connection open, even if it's an ack packet only and on an incompatible connection - we may want to do a UOM transfer e.g.

		if(seqNumber == -1) {
			if(logMINOR) Logger.minor(this, "Returning because seqno = "+seqNumber);
			return;
		}
		// No sequence number == no messages

		if((seqNumber != -1) && tracker.alreadyReceived(seqNumber)) {
			tracker.queueAck(seqNumber); // Must keep the connection open!
			Logger.error(this, "Received packet twice ("+seqNumber+") from "+tracker.pn.getPeer()+": "+seqNumber+" ("+TimeUtil.formatTime((long) tracker.pn.pingAverage.currentValue(), 2, true)+" ping avg)");
			return;
		}

		tracker.receivedPacket(seqNumber);

		int messages = decrypted[ptr++] & 0xff;

		overhead += ptr;

		for(int i=0;i<messages;i++) {
			if(ptr+1 >= decrypted.length) {
				Logger.error(this, "Packet not long enough at byte "+ptr+" on "+tracker);
			}
			int length = ((decrypted[ptr++] & 0xff) << 8) +
			(decrypted[ptr++] & 0xff);
			if(length > decrypted.length - ptr) {
				Logger.error(this, "Message longer than remaining space: "+length);
				return;
			}
			if(logMINOR) Logger.minor(this, "Message "+i+" length "+length+", hash code: "+Fields.hashCode(decrypted, ptr, length));
			Message m = usm.decodeSingleMessage(decrypted, ptr, length, tracker.pn, 1 + (overhead / messages));
			ptr+=length;
			if(m != null) {
				//Logger.minor(this, "Dispatching packet: "+m);
				usm.checkFilters(m, sock);
			}
		}
                
		tracker.pn.maybeRekey();
		if(logMINOR) Logger.minor(this, "Done");
	}

	/* (non-Javadoc)
	 * @see freenet.node.OutgoingPacketMangler#processOutgoingOrRequeue(freenet.node.MessageItem[], freenet.node.PeerNode, boolean, boolean)
	 */
	public void processOutgoingOrRequeue(MessageItem[] messages, PeerNode pn, boolean neverWaitForPacketNumber, boolean dontRequeue) {
		String requeueLogString = "";
		if(!dontRequeue) {
			requeueLogString = ", requeueing";
		}
		if(logMINOR) Logger.minor(this, "processOutgoingOrRequeue "+messages.length+" messages for "+pn+" ("+neverWaitForPacketNumber+ ')');
		byte[][] messageData = new byte[messages.length][];
		int[] alreadyReported = new int[messages.length];
		MessageItem[] newMsgs = new MessageItem[messages.length];
		KeyTracker kt = pn.getCurrentKeyTracker();
		if(kt == null) {
			Logger.error(this, "Not connected while sending packets: "+pn);
			return;
		}
		int length = 1;
		length += kt.countAcks() + kt.countAckRequests() + kt.countResendRequests();
		int callbacksCount = 0;
		int x = 0;
		String mi_name = null;
		for(int i=0;i<messageData.length;i++) {
			MessageItem mi = messages[i];
			if(logMINOR) Logger.minor(this, "Handling formatted MessageItem "+mi+" : "+mi.getData(pn).length);
			mi_name = (mi.msg == null ? "(not a Message)" : mi.msg.getSpec().getName());
			if(mi.formatted) {
				try {
					byte[] buf = mi.getData(pn);
					if(kt == null) {
						if(logMINOR) Logger.minor(this, "kt = null");
						pn.requeueMessageItems(messages, i, messages.length-i, false, "kt = null");
						return;
					}
					int packetNumber = kt.allocateOutgoingPacketNumberNeverBlock();
					this.processOutgoingPreformatted(buf, 0, buf.length, kt, packetNumber, mi.cb, mi.alreadyReportedBytes);
					mi.onSent(buf.length + fullHeadersLengthOneMessage);
				} catch (NotConnectedException e) {
					Logger.normal(this, "Caught "+e+" while sending messages ("+mi_name+") to "+pn.getPeer()+requeueLogString);
					// Requeue
					if(!dontRequeue) {
						pn.requeueMessageItems(messages, 0, x, false, "NotConnectedException(1a)");
						pn.requeueMessageItems(messages, i, messages.length-i, false, "NotConnectedException(1b)");
					}
					return;
				} catch (WouldBlockException e) {
					if(logMINOR) Logger.minor(this, "Caught "+e+" while sending messages ("+mi_name+") to "+pn.getPeer()+requeueLogString, e);
					// Requeue
					if(!dontRequeue) {
						pn.requeueMessageItems(messages, 0, x, false, "WouldBlockException(1a)");
						pn.requeueMessageItems(messages, i, messages.length-i, false, "WouldBlockException(1b)");
					}
					return;
				} catch (KeyChangedException e) {
					if(logMINOR) Logger.minor(this, "Caught "+e+" while sending messages ("+mi_name+") to "+pn.getPeer()+requeueLogString, e);
					// Requeue
					if(!dontRequeue) {
						pn.requeueMessageItems(messages, 0, x, false, "KeyChangedException(1a)");
						pn.requeueMessageItems(messages, i, messages.length-i, false, "KeyChangedException(1b)");
					}
					return;
				} catch (Throwable e) {
					Logger.error(this, "Caught "+e+" while sending messages ("+mi_name+") to "+pn.getPeer()+requeueLogString, e);
					// Requeue
					if(!dontRequeue) {
						pn.requeueMessageItems(messages, 0, x, false, "Throwable(1)");
						pn.requeueMessageItems(messages, i, messages.length-i, false, "Throwable(1)");
					}
					return;
				}
			} else {
				byte[] data = mi.getData(pn);
				messageData[x] = data;
				if(data.length > sock.getMaxPacketSize()) {
					Logger.error(this, "Message exceeds packet size: "+messages[i]+" size "+data.length+" message "+mi.msg);
					// Will be handled later
				}
				newMsgs[x] = mi;
				alreadyReported[x] = mi.alreadyReportedBytes;
				x++;
				if(mi.cb != null) callbacksCount += mi.cb.length;
				if(logMINOR) Logger.minor(this, "Sending: "+mi+" length "+data.length+" cb "+ StringArray.toString(mi.cb));
				length += (data.length + 2);
			}
		}
		if(x != messageData.length) {
			byte[][] newMessageData = new byte[x][];
			System.arraycopy(messageData, 0, newMessageData, 0, x);
			messageData = newMessageData;
			messages = newMsgs;
			newMsgs = new MessageItem[x];
			System.arraycopy(messages, 0, newMsgs, 0, x);
			messages = newMsgs;
		}
		AsyncMessageCallback callbacks[] = new AsyncMessageCallback[callbacksCount];
		x=0;
		int alreadyReportedBytes = 0;
		for(int i=0;i<messages.length;i++) {
			if(messages[i].formatted) continue;
			if(messages[i].cb != null) {
				alreadyReportedBytes += messages[i].alreadyReportedBytes;
				System.arraycopy(messages[i].cb, 0, callbacks, x, messages[i].cb.length);
				x += messages[i].cb.length;
			}
		}
		if(x != callbacksCount) throw new IllegalStateException();

		if((length + HEADERS_LENGTH_MINIMUM < sock.getMaxPacketSize()) &&
				(messageData.length < 256)) {
			mi_name = null;
			try {
				innerProcessOutgoing(messageData, 0, messageData.length, length, pn, neverWaitForPacketNumber, callbacks, alreadyReportedBytes);
				for(int i=0;i<messageData.length;i++) {
					MessageItem mi = newMsgs[i];
					mi_name = (mi.msg == null ? "(not a Message)" : mi.msg.getSpec().getName());
					mi.onSent(messageData[i].length + 2 + (fullHeadersLengthMinimum / messageData.length));
				}
			} catch (NotConnectedException e) {
				Logger.normal(this, "Caught "+e+" while sending messages ("+mi_name+") to "+pn.getPeer()+requeueLogString);
				// Requeue
				if(!dontRequeue)
					pn.requeueMessageItems(messages, 0, messages.length, false, "NotConnectedException(2)");
				return;
			} catch (WouldBlockException e) {
				if(logMINOR) Logger.minor(this, "Caught "+e+" while sending messages ("+mi_name+") to "+pn.getPeer()+requeueLogString, e);
				// Requeue
				if(!dontRequeue)
					pn.requeueMessageItems(messages, 0, messages.length, false, "WouldBlockException(2)");
				return;
			} catch (Throwable e) {
				Logger.error(this, "Caught "+e+" while sending messages ("+mi_name+") to "+pn.getPeer()+requeueLogString, e);
				// Requeue
				if(!dontRequeue)
					pn.requeueMessageItems(messages, 0, messages.length, false, "Throwable(2)");
				return;

			}
		} else {
			if(!dontRequeue) {
				requeueLogString = ", requeueing remaining messages";
			}
			length = 1;
			length += kt.countAcks() + kt.countAckRequests() + kt.countResendRequests();
			int count = 0;
			int lastIndex = 0;
			alreadyReportedBytes = 0;
			for(int i=0;i<=messageData.length;i++) {
				int thisLength;
				if(i == messages.length) thisLength = 0;
				else thisLength = (messageData[i].length + 2);
				int newLength = length + thisLength;
				count++;
				if((newLength + HEADERS_LENGTH_MINIMUM > sock.getMaxPacketSize()) || (count > 255) || (i == messages.length)) {
					// lastIndex up to the message right before this one
					// e.g. lastIndex = 0, i = 1, we just send message 0
					if(lastIndex != i) {
						mi_name = null;
						try {
							innerProcessOutgoing(messageData, lastIndex, i-lastIndex, length, pn, neverWaitForPacketNumber, callbacks, alreadyReportedBytes);
							for(int j=lastIndex;j<i;j++) {
								MessageItem mi = newMsgs[j];
								mi_name = (mi.msg == null ? "(not a Message)" : mi.msg.getSpec().getName());
								mi.onSent(messageData[j].length + 2 + (fullHeadersLengthMinimum / (i-lastIndex)));
							}
						} catch (NotConnectedException e) {
							Logger.normal(this, "Caught "+e+" while sending messages ("+mi_name+") to "+pn.getPeer()+requeueLogString);
							// Requeue
							if(!dontRequeue)
								pn.requeueMessageItems(messages, lastIndex, messages.length - lastIndex, false, "NotConnectedException(3)");
							return;
						} catch (WouldBlockException e) {
							if(logMINOR) Logger.minor(this, "Caught "+e+" while sending messages ("+mi_name+") to "+pn.getPeer()+requeueLogString, e);
							// Requeue
							if(!dontRequeue)
								pn.requeueMessageItems(messages, lastIndex, messages.length - lastIndex, false, "WouldBlockException(3)");
							return;
						} catch (Throwable e) {
							Logger.error(this, "Caught "+e+" while sending messages ("+mi_name+") to "+pn.getPeer()+requeueLogString, e);
							// Requeue
							if(!dontRequeue)
								pn.requeueMessageItems(messages, lastIndex, messages.length - lastIndex, false, "Throwable(3)");
							return;
						}
					}
					lastIndex = i;
					if(i != messageData.length)
						length = 1 + (messageData[i].length + 2);
					count = 0;
				} else {
					length = newLength;
					alreadyReportedBytes += alreadyReported[i];
				}
			}
		}
	}

	/**
	 * Send some messages.
	 * @param messageData An array block of messages.
	 * @param start Index to start reading the array.
	 * @param length Number of messages to read.
	 * @param bufferLength Size of the buffer to write into.
	 * @param pn Node to send the messages to.
	 * @throws PacketSequenceException 
	 */
	private void innerProcessOutgoing(byte[][] messageData, int start, int length, int bufferLength, 
			PeerNode pn, boolean neverWaitForPacketNumber, AsyncMessageCallback[] callbacks, int alreadyReportedBytes) throws NotConnectedException, WouldBlockException, PacketSequenceException {
		if(logMINOR) Logger.minor(this, "innerProcessOutgoing(...,"+start+ ',' +length+ ',' +bufferLength+ ')');
		byte[] buf = new byte[bufferLength];
		buf[0] = (byte)length;
		int loc = 1;
		for(int i=start;i<(start+length);i++) {
			byte[] data = messageData[i];
			int len = data.length;
			buf[loc++] = (byte)(len >> 8);
			buf[loc++] = (byte)len;
			System.arraycopy(data, 0, buf, loc, len);
			loc += len;
		}
		processOutgoingPreformatted(buf, 0, loc, pn, neverWaitForPacketNumber, callbacks, alreadyReportedBytes);
	}

	/* (non-Javadoc)
	 * @see freenet.node.OutgoingPacketMangler#processOutgoing(byte[], int, int, freenet.node.KeyTracker, int)
	 */
	public void processOutgoing(byte[] buf, int offset, int length, KeyTracker tracker, int alreadyReportedBytes) throws KeyChangedException, NotConnectedException, PacketSequenceException, WouldBlockException {
		byte[] newBuf = preformat(buf, offset, length);
		processOutgoingPreformatted(newBuf, 0, newBuf.length, tracker, -1, null, alreadyReportedBytes);
	}

	/**
	 * Send a packet using the current key. Retry if it fails solely because
	 * the key changes.
	 * @throws PacketSequenceException 
	 */
	void processOutgoingPreformatted(byte[] buf, int offset, int length, PeerNode peer, boolean neverWaitForPacketNumber, AsyncMessageCallback[] callbacks, int alreadyReportedBytes) throws NotConnectedException, WouldBlockException, PacketSequenceException {
		KeyTracker last = null;
		while(true) {
			try {
				if(!peer.isConnected())
					throw new NotConnectedException();
				KeyTracker tracker = peer.getCurrentKeyTracker();
				last = tracker;
				if(tracker == null) {
					Logger.normal(this, "Dropping packet: Not connected to "+peer.getPeer()+" yet(2)");
					throw new NotConnectedException();
				}
				int seqNo = neverWaitForPacketNumber ? tracker.allocateOutgoingPacketNumberNeverBlock() :
					tracker.allocateOutgoingPacketNumber();
				processOutgoingPreformatted(buf, offset, length, tracker, seqNo, callbacks, alreadyReportedBytes);
				return;
			} catch (KeyChangedException e) {
				Logger.normal(this, "Key changed(2) for "+peer.getPeer());
				if(last == peer.getCurrentKeyTracker()) {
					if(peer.isConnected()) {
						Logger.error(this, "Peer is connected, yet current tracker is deprecated !! (rekey ?): "+e, e);
						throw new NotConnectedException("Peer is connected, yet current tracker is deprecated !! (rekey ?): "+e);
					}
				}
				// Go around again
			}
		}
	}

	byte[] preformat(byte[] buf, int offset, int length) {
		byte[] newBuf;
		if(buf != null) {
			newBuf = new byte[length+3];
			newBuf[0] = 1;
			newBuf[1] = (byte)(length >> 8);
			newBuf[2] = (byte)length;
			System.arraycopy(buf, offset, newBuf, 3, length);
		} else {
			newBuf = new byte[1];
			newBuf[0] = 0;
		}
		return newBuf;
	}

	/* (non-Javadoc)
	 * @see freenet.node.OutgoingPacketMangler#processOutgoingPreformatted(byte[], int, int, freenet.node.KeyTracker, int, freenet.node.AsyncMessageCallback[], int)
	 */
	public void processOutgoingPreformatted(byte[] buf, int offset, int length, KeyTracker tracker, int packetNumber, AsyncMessageCallback[] callbacks, int alreadyReportedBytes) throws KeyChangedException, NotConnectedException, PacketSequenceException, WouldBlockException {
		if(logMINOR) {
			String log = "processOutgoingPreformatted("+Fields.hashCode(buf)+", "+offset+ ',' +length+ ',' +tracker+ ',' +packetNumber+ ',';
			if(callbacks == null) log += "null";
			else log += (""+callbacks.length+(callbacks.length >= 1 ? String.valueOf(callbacks[0]) : ""));
			Logger.minor(this, log);
		}
		if((tracker == null) || (!tracker.pn.isConnected())) {
			throw new NotConnectedException();
		}

		// We do not support forgotten packets at present

		int[] acks, resendRequests, ackRequests, forgotPackets;
		int seqNumber;
		/* Locking:
		 * Avoid allocating a packet number, then a long pause due to 
		 * overload, during which many other packets are sent, 
		 * resulting in the other side asking us to resend a packet 
		 * which doesn't exist yet.
		 * => grabbing resend reqs, packet no etc must be as
		 * close together as possible.
		 * 
		 * HOWEVER, tracker.allocateOutgoingPacketNumber can block,
		 * so should not be locked.
		 */

		if(packetNumber > 0)
			seqNumber = packetNumber;
		else {
			if(buf.length == 1)
				// Ack/resendreq only packet
				seqNumber = -1;
			else
				seqNumber = tracker.allocateOutgoingPacketNumberNeverBlock();
		}

		if(logMINOR) Logger.minor(this, "Sequence number (sending): "+seqNumber+" ("+packetNumber+") to "+tracker.pn.getPeer());

		/** The last sent sequence number, so that we can refer to packets
		 * sent after this packet was originally sent (it may be a resend) */
		int realSeqNumber;

		int otherSideSeqNumber;

		synchronized(tracker) {
			acks = tracker.grabAcks();
			forgotPackets = tracker.grabForgotten();
			resendRequests = tracker.grabResendRequests();
			ackRequests = tracker.grabAckRequests();
			realSeqNumber = tracker.getLastOutgoingSeqNumber();
			otherSideSeqNumber = tracker.highestReceivedIncomingSeqNumber();
			if(logMINOR) Logger.minor(this, "Sending packet to "+tracker.pn.getPeer()+", other side max seqno: "+otherSideSeqNumber);
		}

		int packetLength = 4 + // seq number
		RANDOM_BYTES_LENGTH + // random junk
		1 + // version
		((packetNumber == -1) ? 4 : 1) + // highest sent seqno - 4 bytes if seqno = -1
		4 + // other side's seqno
		1 + // number of acks
		acks.length + // acks
		1 + // number of resend reqs
		resendRequests.length + // resend requests
		1 + // number of ack requests
		ackRequests.length + // ack requests
		1 + // no forgotten packets
		length; // the payload !

		// Padding
		// This will do an adequate job of disguising the contents, and a poor (but not totally
		// worthless) job of disguising the traffic. FIXME!!!!!
		// Ideally we'd mimic the size profile - and the session bytes! - of a common protocol.

		int paddedLen = ((packetLength + 63) / 64) * 64;
		paddedLen += node.fastWeakRandom.nextInt(64);
		if(packetLength <= 1280 && paddedLen > 1280) paddedLen = 1280;

		byte[] padding = new byte[paddedLen - packetLength];
		node.fastWeakRandom.nextBytes(padding);

		packetLength = paddedLen;

		if(logMINOR) Logger.minor(this, "Packet length: "+packetLength+" ("+length+")");

		byte[] plaintext = new byte[packetLength];

		byte[] randomJunk = new byte[RANDOM_BYTES_LENGTH];

		int ptr = offset;

		plaintext[ptr++] = (byte)(seqNumber >> 24);
		plaintext[ptr++] = (byte)(seqNumber >> 16);
		plaintext[ptr++] = (byte)(seqNumber >> 8);
		plaintext[ptr++] = (byte)seqNumber;

		if(logMINOR) Logger.minor(this, "Getting random junk");
		node.random.nextBytes(randomJunk);
		System.arraycopy(randomJunk, 0, plaintext, ptr, RANDOM_BYTES_LENGTH);
		ptr += RANDOM_BYTES_LENGTH;

		plaintext[ptr++] = 0; // version number

		if(seqNumber == -1) {
			plaintext[ptr++] = (byte)(realSeqNumber >> 24);
			plaintext[ptr++] = (byte)(realSeqNumber >> 16);
			plaintext[ptr++] = (byte)(realSeqNumber >> 8);
			plaintext[ptr++] = (byte)realSeqNumber;
		} else {
			plaintext[ptr++] = (byte)(realSeqNumber - seqNumber);
		}

		plaintext[ptr++] = (byte)(otherSideSeqNumber >> 24);
		plaintext[ptr++] = (byte)(otherSideSeqNumber >> 16);
		plaintext[ptr++] = (byte)(otherSideSeqNumber >> 8);
		plaintext[ptr++] = (byte)otherSideSeqNumber;

		plaintext[ptr++] = (byte) acks.length;
		for(int i=0;i<acks.length;i++) {
			int ackSeq = acks[i];
			if(logMINOR) Logger.minor(this, "Acking "+ackSeq);
			int offsetSeq = otherSideSeqNumber - ackSeq;
			if((offsetSeq > 255) || (offsetSeq < 0))
				throw new PacketSequenceException("bad ack offset "+offsetSeq+
						" - seqNumber="+otherSideSeqNumber+", ackNumber="+ackSeq+" talking to "+tracker.pn.getPeer());
			plaintext[ptr++] = (byte)offsetSeq;
		}

		plaintext[ptr++] = (byte) resendRequests.length;
		for(int i=0;i<resendRequests.length;i++) {
			int reqSeq = resendRequests[i];
			if(logMINOR) Logger.minor(this, "Resend req: "+reqSeq);
			int offsetSeq = otherSideSeqNumber - reqSeq;
			if((offsetSeq > 255) || (offsetSeq < 0))
				throw new PacketSequenceException("bad resend request offset "+offsetSeq+
						" - reqSeq="+reqSeq+", otherSideSeqNumber="+otherSideSeqNumber+" talking to "+tracker.pn.getPeer());
			plaintext[ptr++] = (byte)offsetSeq;
		}

		plaintext[ptr++] = (byte) ackRequests.length;
		if(logMINOR) Logger.minor(this, "Ackrequests: "+ackRequests.length);
		for(int i=0;i<ackRequests.length;i++) {
			int ackReqSeq = ackRequests[i];
			if(logMINOR) Logger.minor(this, "Ack request "+i+": "+ackReqSeq);
			// Relative to packetNumber - we are asking them to ack
			// a packet we sent to them.
			int offsetSeq = realSeqNumber - ackReqSeq;
			if((offsetSeq > 255) || (offsetSeq < 0))
				throw new PacketSequenceException("bad ack requests offset: "+offsetSeq+
						" - ackReqSeq="+ackReqSeq+", packetNumber="+realSeqNumber+" talking to "+tracker.pn.getPeer());
			plaintext[ptr++] = (byte)offsetSeq;
		}

		byte[] forgotOffsets = null;
		int forgotCount = 0;

		if(forgotPackets.length > 0) {
			for(int i=0;i<forgotPackets.length;i++) {
				int seq = forgotPackets[i];
				if(logMINOR) Logger.minor(this, "Forgot packet "+i+": "+seq);
				int offsetSeq = realSeqNumber - seq;
				if((offsetSeq > 255) || (offsetSeq < 0)) {
					if(tracker.isDeprecated()) {
						// Oh well
						Logger.error(this, "Dropping forgot-packet notification on deprecated tracker: "+seq+" on "+tracker+" - real seq="+realSeqNumber);
						// Ignore it
						continue;
					} else {
						Logger.error(this, "bad forgot packet offset: "+offsetSeq+
								" - forgotSeq="+seq+", packetNumber="+realSeqNumber+" talking to "+tracker.pn.getPeer(), new Exception("error"));
					}
				} else {
					if(forgotOffsets == null)
						forgotOffsets = new byte[forgotPackets.length - i];
					forgotOffsets[i] = (byte) offsetSeq;
					forgotCount++;
					if(forgotCount == 256)
						tracker.requeueForgot(forgotPackets, forgotCount, forgotPackets.length - forgotCount);
				}
			}
		}

		plaintext[ptr++] = (byte) forgotCount;

		if(forgotOffsets != null) {
			System.arraycopy(forgotOffsets, 0, plaintext, ptr, forgotCount);
			ptr += forgotCount;
		}

		System.arraycopy(buf, offset, plaintext, ptr, length);
		ptr += length;

		System.arraycopy(padding, 0, plaintext, ptr, padding.length);
		ptr += padding.length;

		if(ptr != plaintext.length) {
			Logger.error(this, "Inconsistent length: "+plaintext.length+" buffer but "+(ptr)+" actual");
			byte[] newBuf = new byte[ptr];
			System.arraycopy(plaintext, 0, newBuf, 0, ptr);
			plaintext = newBuf;
		}

		if(seqNumber != -1) {
			byte[] saveable = new byte[length];
			System.arraycopy(buf, offset, saveable, 0, length);
			tracker.sentPacket(saveable, seqNumber, callbacks);
		}

		if(logMINOR) Logger.minor(this, "Sending... "+seqNumber);

		processOutgoingFullyFormatted(plaintext, tracker, alreadyReportedBytes);
		if(logMINOR) Logger.minor(this, "Sent packet "+seqNumber);
	}

	/**
	 * Encrypt and send a packet.
	 * @param plaintext The packet's plaintext, including all formatting,
	 * including acks and resend requests. Is clobbered.
	 */
	private void processOutgoingFullyFormatted(byte[] plaintext, KeyTracker kt, int alreadyReportedBytes) {
		BlockCipher sessionCipher = kt.sessionCipher;
		if(logMINOR) Logger.minor(this, "Encrypting with "+HexUtil.bytesToHex(kt.sessionKey));
		if(sessionCipher == null) {
			Logger.error(this, "Dropping packet send - have not handshaked yet");
			return;
		}
		int blockSize = sessionCipher.getBlockSize() >> 3;
		if(sessionCipher.getKeySize() != sessionCipher.getBlockSize())
			throw new IllegalStateException("Block size must be half key size: blockSize="+
					sessionCipher.getBlockSize()+", keySize="+sessionCipher.getKeySize());

		MessageDigest md = SHA256.getMessageDigest();

		int digestLength = md.getDigestLength();

		if(digestLength != blockSize)
			throw new IllegalStateException("Block size must be digest length!");

		byte[] output = new byte[plaintext.length + digestLength];
		System.arraycopy(plaintext, 0, output, digestLength, plaintext.length);

		md.update(plaintext);

		//Logger.minor(this, "Plaintext:\n"+HexUtil.bytesToHex(plaintext));

		byte[] digestTemp;

		digestTemp = md.digest();

		SHA256.returnMessageDigest(md); md = null;

		if(logMINOR) Logger.minor(this, "\nHash:      "+HexUtil.bytesToHex(digestTemp));

		// Put encrypted digest in output
		sessionCipher.encipher(digestTemp, digestTemp);

		// Now copy it back
		System.arraycopy(digestTemp, 0, output, 0, digestLength);
		// Yay, we have an encrypted hash

		if(logMINOR) Logger.minor(this, "\nEncrypted: "+HexUtil.bytesToHex(digestTemp)+" ("+plaintext.length+" bytes plaintext)");

		PCFBMode pcfb = PCFBMode.create(sessionCipher, digestTemp);
		pcfb.blockEncipher(output, digestLength, plaintext.length);

		//Logger.minor(this, "Ciphertext:\n"+HexUtil.bytesToHex(output, digestLength, plaintext.length));

		// We have a packet
		// Send it

		if(logMINOR) Logger.minor(this,"Sending packet of length "+output.length+" (" + Fields.hashCode(output) + " to "+kt.pn);

		// pn.getPeer() cannot be null
		try {
			sendPacket(output, kt.pn.getPeer(), kt.pn, alreadyReportedBytes);
		} catch (LocalAddressException e) {
			Logger.error(this, "Tried to send data packet to local address: "+kt.pn.getPeer()+" for "+kt.pn.allowLocalAddresses());
		}
		kt.pn.sentPacket();
	}

	/* (non-Javadoc)
	 * @see freenet.node.OutgoingPacketMangler#sendHandshake(freenet.node.PeerNode)
	 */
	public void sendHandshake(PeerNode pn) {
		int negType = pn.selectNegType(this);
		if(negType == -1) {
			// Pick a random negType from what I do support
			int[] negTypes = supportedNegTypes();
			negType = negTypes[node.random.nextInt(negTypes.length)];
			Logger.normal(this, "Cannot send handshake to "+pn+" because no common negTypes, choosing random negType of "+negType);
		}
		if(logMINOR) Logger.minor(this, "Possibly sending handshake to "+pn+" negotiation type "+negType);
		Peer[] handshakeIPs;
		if(!pn.shouldSendHandshake()) {
			if(logMINOR) Logger.minor(this, "Not sending handshake to "+pn.getPeer()+" because pn.shouldSendHandshake() returned false");
			return;
		}
		long firstTime = System.currentTimeMillis();
		handshakeIPs = pn.getHandshakeIPs();
		long secondTime = System.currentTimeMillis();
		if((secondTime - firstTime) > 1000)
			Logger.error(this, "getHandshakeIPs() took more than a second to execute ("+(secondTime - firstTime)+") working on "+pn.userToString());
		if(handshakeIPs.length == 0) {
			pn.couldNotSendHandshake();
			long thirdTime = System.currentTimeMillis();
			if((thirdTime - secondTime) > 1000)
				Logger.error(this, "couldNotSendHandshake() (after getHandshakeIPs()) took more than a second to execute ("+(thirdTime - secondTime)+") working on "+pn.userToString());
			return;
		}
		int sentCount = 0;
		long loopTime1 = System.currentTimeMillis();
		for(int i=0;i<handshakeIPs.length;i++){
			Peer peer = handshakeIPs[i];
			FreenetInetAddress addr = peer.getFreenetAddress();
			if(!crypto.allowConnection(pn, addr)) {
				if(logMINOR)
					Logger.minor(this, "Not sending handshake packet to "+peer+" for "+pn);
			}
			if(peer.getAddress(false) == null) {
				if(logMINOR) Logger.minor(this, "Not sending handshake to "+handshakeIPs[i]+" for "+pn.getPeer()+" because the DNS lookup failed or it's a currently unsupported IPv6 address");
				continue;
			}
			if((!pn.allowLocalAddresses()) && (!peer.isRealInternetAddress(false, false))) {
				if(logMINOR) Logger.minor(this, "Not sending handshake to "+handshakeIPs[i]+" for "+pn.getPeer()+" because it's not a real Internet address and metadata.allowLocalAddresses is not true");
				continue;
			}
			sendJFKMessage1(pn, peer);
			if(logMINOR)
				Logger.minor(this, "Sending handshake to "+peer+" for "+pn+" ("+i+" of "+handshakeIPs.length);
			pn.sentHandshake();
			sentCount += 1;
		}
		long loopTime2 = System.currentTimeMillis();
		if((loopTime2 - loopTime1) > 1000)
			Logger.normal(this, "loopTime2 is more than a second after loopTime1 ("+(loopTime2 - loopTime1)+") working on "+pn.userToString());
		if(sentCount==0) {
			pn.couldNotSendHandshake();
		}
	}

	/* (non-Javadoc)
	 * @see freenet.node.OutgoingPacketMangler#isDisconnected(freenet.io.comm.PeerContext)
	 */
	public boolean isDisconnected(PeerContext context) {
		if(context == null) return false;
		return !((PeerNode)context).isConnected();
	}

	public void resend(ResendPacketItem item) throws PacketSequenceException, WouldBlockException, KeyChangedException, NotConnectedException {
		processOutgoingPreformatted(item.buf, 0, item.buf.length, item.kt, item.packetNumber, item.callbacks, 0);
	}

	public int[] supportedNegTypes() {
		return new int[] { 2 };
	}

	public int fullHeadersLengthOneMessage() {
		return fullHeadersLengthOneMessage;
	}

	public SocketHandler getSocketHandler() {
		return sock;
	}

	public Peer[] getPrimaryIPAddress() {
		return crypto.detector.getPrimaryPeers();
	}

	public byte[] getCompressedNoderef() {
		return crypto.myCompressedFullRef();
	}

	public boolean alwaysAllowLocalAddresses() {
		return crypto.config.alwaysAllowLocalAddresses();
	}

	private DiffieHellmanLightContext _genLightDiffieHellmanContext() {
		final DiffieHellmanLightContext ctx = DiffieHellman.generateLightContext();
		ctx.setSignature(crypto.sign(SHA256.digest(assembleDHParams(ctx.myExponential, crypto.getCryptoGroup()))));
		
		return ctx;
	}
	
	private final void _fillJFKDHFIFOOffThread() {
		// do it off-thread
		node.executor.execute(new Runnable() {
			public void run() {
				_fillJFKDHFIFO();
			}
		}, "DiffieHellman exponential signing");
	}
	
	private void _fillJFKDHFIFO() {
		synchronized (dhContextFIFO) {
			dhContextFIFO.addLast(_genLightDiffieHellmanContext());
			if(dhContextFIFO.size() > DH_CONTEXT_BUFFER_SIZE)
				dhContextFIFO.remove(findOldestContext());
		}
	}

	/**
	 * Change the DH Exponents on a regular basis but at most once every 30sec
	 * 
	 * @return {@link DiffieHellmanLightContext}
	 */
	private DiffieHellmanLightContext getLightDiffieHellmanContext() {
		final long now = System.currentTimeMillis();
		DiffieHellmanLightContext result = null;
		
		synchronized (dhContextFIFO) {
			
			result = (DiffieHellmanLightContext) dhContextFIFO.removeFirst();
			
			// Shall we replace one element of the queue ?
			if((jfkDHLastGenerationTimestamp + 30000 /*30sec*/) < now) {
				jfkDHLastGenerationTimestamp = now;
				_fillJFKDHFIFOOffThread();
			}
			
			dhContextFIFO.addLast(result);
		}
		
		Logger.minor(this, "getLightDiffieHellmanContext() is serving "+result.hashCode());
		return result;
	}
	
	/**
	 * Used in processJFK[3|4]
	 * That's O^(n) ... but we have only a few elements and
	 * we call it only once a round-trip has been done
	 * 
	 * @param exponential
	 * @return the corresponding DiffieHellmanLightContext with the right exponent
	 */
	private DiffieHellmanLightContext findContextByExponential(BigInteger exponential) {
		DiffieHellmanLightContext result = null;
		synchronized (dhContextFIFO) {
			Iterator it = dhContextFIFO.iterator();
			while(it.hasNext()) {
				result = (DiffieHellmanLightContext) it.next();
				if(exponential.equals(result.myExponential)) {
					return result;
				}
			}
			if((dhContextToBePrunned.myExponential).equals(result.myExponential))
				return dhContextToBePrunned;
		}
		return null;
	}
	
	/**
	 * Used to prune the oldest context
	 * That's O^(N)... but we have only a few elements.
	 * 
	 * @return the oldest DiffieHellmanLightContext
	 */
	private DiffieHellmanLightContext findOldestContext() {
		DiffieHellmanLightContext result = null, tmp;
		long oldestSeen = Long.MAX_VALUE;
		
		synchronized (dhContextFIFO) {
			Iterator it = dhContextFIFO.iterator();
			while(it.hasNext()) {
				tmp = (DiffieHellmanLightContext) it.next();
				if(tmp.lifetime < oldestSeen) {
					oldestSeen = tmp.lifetime;
					result = tmp;
				}
			}
			dhContextToBePrunned = result;
		}
		return result;
	}

	/*
	 * Prepare DH parameters of message2 for them to be signed (useful in message3 to check the sig)
	 */
	private byte[] assembleDHParams(BigInteger exponential, DSAGroup group) {
		byte[] _myExponential = stripBigIntegerToNetworkFormat(exponential);
		byte[] _myGroup = group.getP().toByteArray();
		byte[] toSign = new byte[_myExponential.length + _myGroup.length];

		System.arraycopy(_myExponential, 0, toSign, 0, _myExponential.length);
		System.arraycopy(_myGroup, 0, toSign, _myExponential.length, _myGroup.length);

		return toSign;
	}

	private byte[] assembleDHParams(byte[] nonceInitiator,byte[] nonceResponder,BigInteger initiatorExponential, BigInteger responderExponential, byte[] id, byte[] sa) {
		byte[] _initiatorExponential = stripBigIntegerToNetworkFormat(initiatorExponential);
		byte[] _responderExponential = stripBigIntegerToNetworkFormat(responderExponential);
		byte[] result = new byte[nonceInitiator.length + nonceResponder.length + _initiatorExponential.length + _responderExponential.length + id.length + sa.length];
		int offset = 0;
		
		System.arraycopy(nonceInitiator, 0,result,offset,nonceInitiator.length);
		offset += nonceInitiator.length;
		System.arraycopy(nonceResponder,0 ,result,offset,nonceResponder.length);
		offset += nonceResponder.length;
		System.arraycopy(_initiatorExponential, 0, result,offset, _initiatorExponential.length);
		offset += _initiatorExponential.length;
		System.arraycopy(_responderExponential, 0, result, offset, _responderExponential.length);
		offset += _responderExponential.length;
		System.arraycopy(id, 0, result , offset,id.length);
		offset += id.length;
		System.arraycopy(sa, 0, result , offset,sa.length);

		return result;
	}
	
	private byte[] getTransientKey() {
		synchronized (authenticatorCache) {
			return transientKey;
		}
	}
	
	private byte[] computeJFKSharedKey(BigInteger exponential, byte[] nI, byte[] nR, String what) {
		assert("0".equals(what) || "1".equals(what) || "2".equals(what));
		HMAC mac = new HMAC(SHA256.getInstance());
		byte[] number = null;
		try { number = what.getBytes("UTF-8"); } catch (UnsupportedEncodingException e) {}
		
		byte[] toHash = new byte[NONCE_SIZE * 2 + number.length];
		int offset = 0;
		System.arraycopy(nI, 0, toHash, offset, NONCE_SIZE);
		offset += NONCE_SIZE;
		System.arraycopy(nR, 0, toHash, offset, NONCE_SIZE);
		offset += NONCE_SIZE;
		System.arraycopy(number, 0, toHash, offset, number.length);
		
		return mac.mac(exponential.toByteArray(), toHash, HASH_LENGTH);
	}

	private long timeLastReset = -1;
	
	/**
	 * Change the transient key used by JFK.
	 * 
	 * It will determine the PFS interval, hence we call it at least once every 30mins.
	 * 
	 * @return True if we reset the transient key and therefore the authenticator cache.
	 */
	private boolean maybeResetTransientKey() {
		synchronized (authenticatorCache) {
			long now = System.currentTimeMillis();;
			if(authenticatorCache.size() < AUTHENTICATOR_CACHE_SIZE) {
				if(now - timeLastReset < TRANSIENT_KEY_REKEYING_MIN_INTERVAL)
					return false;
			}
			node.random.nextBytes(transientKey);
			
			// reset the authenticator cache
			authenticatorCache.clear();
			
			timeLastReset = now;
		}
		node.getTicker().queueTimedJob(transientKeyRekeyer, TRANSIENT_KEY_REKEYING_MIN_INTERVAL);
		Logger.normal(this, "JFK's TransientKey has been changed and the message cache flushed.");
		return true;
	}

	private byte[] stripBigIntegerToNetworkFormat(BigInteger exponential) {
		byte[] data = exponential.toByteArray();
		int targetLength = DiffieHellman.modulusLengthInBytes();

		if(data.length != targetLength) {
			byte[] newData = new byte[targetLength];
			if((data.length == targetLength+1) && (data[0] == 0)) {
				// Sign bit
				System.arraycopy(data, 1, newData, 0, targetLength);
			} else if(data.length < targetLength) {
				System.arraycopy(data, 0, newData, targetLength-data.length, data.length);
			} else {
				throw new IllegalStateException("Too long!");
			}
			data = newData;
		}
		return data;
	}

}
