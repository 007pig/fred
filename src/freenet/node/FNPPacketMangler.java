
/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import freenet.io.comm.SocketHandler;

import java.net.Inet4Address;
import java.security.MessageDigest;
import java.util.Arrays;
import net.i2p.util.NativeBigInteger;
import freenet.crypt.BlockCipher;
import freenet.crypt.DSA;
import freenet.crypt.DSAGroup;
import freenet.crypt.DSAPrivateKey;
import freenet.crypt.DSASignature;
import freenet.crypt.DiffieHellman;
import freenet.crypt.DiffieHellmanContext;
import freenet.crypt.DiffieHellmanLightContext;
import freenet.crypt.EntropySource;
import freenet.crypt.Global;
import freenet.crypt.HMAC;
import freenet.crypt.SHA1;
import freenet.crypt.PCFBMode;
import freenet.crypt.RandomSource;
import freenet.crypt.SHA256;
import freenet.crypt.crypto_Random.eKey;
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
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.util.Map;
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
	/*
	 * Objects cached during JFK message exchange: Message3,Message4 and authenticator
	 * The messages are cached in hashmaps because the message retrieval from the cache 
	 * can be performed in constant time( given the key)
	 * Usage of a linkedList could prove to be much slower due to the allocation time
	 * for each node in the list.
	 */
	final Map message3Cache;
	final Map message4Cache;
	final HashMap authenticatorCache;
	final eKey encryptionKey;
	final DSAGroup g;
	static DSAPrivateKey PKR,PKI;
	final RandomSource r;
	/** We renew it on each *successful* run of the protocol (the spec. says "once a while") - access is synchronized! */
	private DiffieHellmanLightContext currentDHContext = null;
	// TODO: is 64 bits enough ?
	public static final int NONCE_SIZE = 6;
	private static final int MAX_PACKETS_IN_FLIGHT = 256; 
	private static final int RANDOM_BYTES_LENGTH = 12;
	private static final int HASH_LENGTH = SHA256.getDigestLength();
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
		message3Cache = new HashMap();
		message4Cache = new HashMap();
		authenticatorCache = new HashMap();
		encryptionKey = new eKey();
		g = Global.DSAgroupBigA;
		r=node.random;
		fullHeadersLengthMinimum = HEADERS_LENGTH_MINIMUM + sock.getHeadersLength();
		fullHeadersLengthOneMessage = HEADERS_LENGTH_ONE_MESSAGE + sock.getHeadersLength();
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
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
	public void process(byte[] buf, int offset, int length, Peer peer) {
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
				if(tryProcess(buf, offset, length, opn.getCurrentKeyTracker())) return;
				// Try with old key
				if(tryProcess(buf, offset, length, opn.getPreviousKeyTracker())) return;
				// Try with unverified key
				if(tryProcess(buf, offset, length, opn.getUnverifiedKeyTracker())) return;
			}
			if(length > Node.SYMMETRIC_KEY_LENGTH /* iv */ + HASH_LENGTH + 2) {
				// Might be an auth packet
				if(tryProcessAuth(buf, offset, length, opn, peer)) return;
			}
		}
		PeerNode[] peers = crypto.getPeerNodes();
		if(length > HASH_LENGTH + RANDOM_BYTES_LENGTH + 4 + 6) {
			for(int i=0;i<peers.length;i++) {
				pn = peers[i];
				if(pn == opn) continue;
				if(tryProcess(buf, offset, length, pn.getCurrentKeyTracker())) {
					// IP address change
					pn.changedIP(peer);
					return;
				}
				if(tryProcess(buf, offset, length, pn.getPreviousKeyTracker())) {
					// IP address change
					pn.changedIP(peer);
					return;
				}
				if(tryProcess(buf, offset, length, pn.getUnverifiedKeyTracker())) {
					// IP address change
					pn.changedIP(peer);
					return;
				}
			}
		}
		if(length > Node.SYMMETRIC_KEY_LENGTH /* iv */ + HASH_LENGTH + 2) {
			for(int i=0;i<peers.length;i++) {
				pn = peers[i];
				if(pn == opn) continue;
				if(tryProcessAuth(buf, offset, length, pn, peer)) return;
			}
		}
		Logger.normal(this,"Unmatchable packet from "+peer);
	}

	/**
	 * Is this a negotiation packet? If so, process it.
	 * @param buf The buffer to read bytes from
	 * @param offset The offset at which to start reading
	 * @param length The number of bytes to read
	 * @param opn The PeerNode we think is responsible
	 * @param peer The Peer to send a reply to
	 * @return True if we handled a negotiation packet, false otherwise.
	 */
	private boolean tryProcessAuth(byte[] buf, int offset, int length, PeerNode opn, Peer peer) {
		BlockCipher authKey = opn.incomingSetupCipher;
		if(logMINOR) Logger.minor(this, "Decrypt key: "+HexUtil.bytesToHex(opn.incomingSetupKey)+" for "+peer+" : "+opn+" in tryProcessAuth");
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
			processDecryptedAuth(payload, opn, peer);
			opn.reportIncomingBytes(length);
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
	private void processDecryptedAuth(byte[] payload, PeerNode pn, Peer replyTo) {
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
		}else if (negType == 1) {
			// Four stage Diffie-Hellman. 0 = ephemeral, 1 = payload stages are signed (not quite STS)
			// FIXME reduce to 3 stages and implement STS properly (we have a separate validation mechanism in PeerNode)
			// AFAICS this (with negType=1) is equivalent in security to STS; it expands the second phase into a second and a fourth phase.
			// A -> B g^x
			// B -> A g^y
			// A -> B E^k ( ... )
			// B -> A E^k ( ... )

			if((packetType < 0) || (packetType > 3)) {
				Logger.error(this, "Decrypted auth packet but unknown packet type "+packetType+" from "+replyTo+" possibly from "+pn);
				return;
			}

			// We keep one DiffieHellmanContext per node ONLY
			/*
			 * Now, to the real meat
			 * Alice, Bob share a base, g, and a modulus, p
			 * Alice generates a random number r, and: 1: Alice -> Bob: a=g^r
			 * Bob receives this and generates his own random number, s, and: 2: Bob -> Alice: b=g^s
			 * Alice receives this, calculates K = b^r, and: 3: Alice -> Bob: E_K ( H(data) data )
			 *    where data = [ Alice's startup number ]
			 * Bob does exactly the same as Alice for packet 4.
			 * 
			 * At this point we are done.
			 */
			if(packetType == 0) {
				// We are Bob
				// We need to:
				// - Record Alice's a
				// - Generate our own s and b
				// - Send a type 1 packet back to Alice containing this

				DiffieHellmanContext ctx = 
					processDHZeroOrOne(0, payload, pn);
				if(ctx == null) return;
				// Send reply
				sendFirstHalfDHPacket(1, negType, ctx.getOurExponential(), pn, replyTo);
				// Send a type 1, they will reply with a type 2
			} else if(packetType == 1) {
				// We are Alice
				DiffieHellmanContext ctx = 
					processDHZeroOrOne(1, payload, pn);
				if(ctx == null) return;
				sendSignedDHCompletion(2, ctx.getCipher(), pn, replyTo, ctx);
				// Send a type 2
			} else if(packetType == 2) {
				// We are Bob
				// Receiving a completion packet
				// Verify the packet, then complete
				// Format: IV E_K ( H(data) data )
				// Where data = [ long: bob's startup number ]
				processSignedDHTwoOrThree(2, payload, pn, replyTo, true);
			} else if(packetType == 3) {
				// We are Alice
				processSignedDHTwoOrThree(3, payload, pn, replyTo, false);
			}
		}
		else if (negType==2){
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
				ProcessMessage1(payload,pn,replyTo,0);

			}
			else if(packetType==1){
				/*
				 * Responder replies with a signed copy of his own exponential, a random
				 * nonce and an authenticator calculated from a transient hash key private
				 * to the responder.
				 */
				ProcessMessage2(payload,pn,replyTo,1);
			}
			else if(packetType==2){
				/*
				 * Initiator echoes the data sent by the responder.These messages are
				 * cached by the Responder.Receiving a duplicate message simply causes
				 * the responder to Re-transmit the corresponding message4
				 */
				ProcessMessage3(payload, pn, replyTo, 2);
			}
			else if(packetType==3){
				/*
				 * Encrypted message of the signature on both nonces, both exponentials 
				 * using the same keys as in the previous message.
				 * The signature is non-message recovering
				 */
				ProcessMessage4(payload,pn,replyTo,3);
			}
		}
		else {
			Logger.error(this, "Decrypted auth packet but unknown negotiation type "+negType+" from "+replyTo+" possibly from "+pn);
			return;
		}
	}
	/*
	 * Initiator DH Exponential
	 */
	private synchronized byte[] Gi(PeerNode pn){
		DiffieHellmanContext dh=(DiffieHellmanContext)pn.getKeyAgreementSchemeContext();
		if(dh==null)
		{
			if(shouldLogErrorInHandshake())
				Logger.error(this,"Failed getting exponentials");

		}
		return dh.getOurExponential().toByteArray();
	}

	/*
	 * Responder DH Exponential
	 */
	private synchronized byte[] Gr(PeerNode pn){
		DiffieHellmanContext dh=(DiffieHellmanContext)pn.getKeyAgreementSchemeContext();
		if(dh==null)
		{
			if(shouldLogErrorInHandshake())
				Logger.error(this,"Failed getting exponentials");

		}
		return dh.getHisExponential().toByteArray();
	}

	/*
	 * Shared Secret key
	 * Alice generates random number x and computes exponential g^x
	 * Bob generates random number y and computes exponential g^y
	 * Shared secret key= (g^x)^y used as key for computing hash of the encryption key
	 */
	private synchronized byte[] sharedSecretKey(PeerNode pn){
		DiffieHellmanContext dh=(DiffieHellmanContext)pn.getKeyAgreementSchemeContext();
		if(dh==null)
		{
			if(shouldLogErrorInHandshake())
				Logger.error(this,"Failed getting exponentials");

		}
		return dh.getKey();
	}
	/*
	 * The Initiator and Responder nonce are random bytes used to provide key independence
	 */
	private synchronized byte[] iNonce(){

		byte[] n=new byte[16];
		node.random.nextBytes(n);
		return n;
	}
	private synchronized byte[] rNonce(){
		byte[] n=new byte[16];
		node.random.nextBytes(n);
		return n;
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
	private void ProcessMessage1(byte[] payload,PeerNode pn,Peer replyTo,int phase)
	{
		long t1=System.currentTimeMillis();
		
		// FIXME: follow the spec and send IDr' ?
		if(payload.length-3 < NONCE_SIZE + DiffieHellman.modulusLengthInBytes()) {
			Logger.error(this, "Packet too short from "+pn+": "+payload.length+" after decryption in JFK("+phase+"), should be "+(NONCE_SIZE + DiffieHellman.modulusLengthInBytes()));
			return;
		}
		// get Ni
		byte[] nonceInitiator = new byte[NONCE_SIZE];
		System.arraycopy(payload, 3, nonceInitiator, 0, NONCE_SIZE);
		
		// get g^i
		byte[] hisExponential = new byte[DiffieHellman.modulusLengthInBytes()];
		System.arraycopy(payload, 4 + NONCE_SIZE, hisExponential, 0, DiffieHellman.modulusLengthInBytes());
		
		NativeBigInteger _hisExponential = new NativeBigInteger(hisExponential);
		if(_hisExponential.compareTo(NativeBigInteger.ONE) > 0)
			sendMessage2(nonceInitiator, hisExponential, pn, replyTo);
		else
			Logger.error(this, "We can't accept the exponential "+pn+" sent us; it's smaller than 1!!");
		
		long t2=System.currentTimeMillis();
		if((t2-t1)>500)
			Logger.error(this,"Message1 timeout error:Sending packet for"+pn.getPeer());
	}
	
	// FIXME: IDr' ?
	private void sendMessage2(byte[] nonceInitator, byte[] hisExponential, PeerNode pn, Peer replyTo) {
		DiffieHellmanLightContext dhContext = getLightDiffieHellmanContext();
		byte[] idR = new byte[0];
		byte[] myDHGroup = dhContext.group.asBytes();
		byte[] myNonce = new byte[NONCE_SIZE];
		byte[] myExponential = dhContext.myExponential.toByteArray();
		node.random.nextBytes(myNonce);
		byte[] signature = dhContext.signature.toString().getBytes("UTF-8");
		byte[] authenticator = computeHashedJFKAuthenticator(myExponential, myNonce, nonceInitator, idR);
		
		byte[] message2 = new byte[NONCE_SIZE*2+DiffieHellman.modulusLengthInBytes()+myDHGroup.length+
		                           signature.length+
		                           HASH_LENGTH];

		int offset = 0;
		System.arraycopy(nonceInitator, 0, message2, offset, NONCE_SIZE);
		offset += NONCE_SIZE;
		System.arraycopy(myNonce, 0, message2, offset, NONCE_SIZE);
		offset += NONCE_SIZE;
		System.arraycopy(myExponential, 0, message2, offset, myExponential.length);
		offset += myExponential.length;
		System.arraycopy(idR, 0, message2, offset, idR.length);
		offset += idR.length;
		
		System.arraycopy(signature, 0, message2, offset, signature.length);
		offset += signature.length;
		
		System.arraycopy(authenticator, 0, message2, offset, HASH_LENGTH);
		
		sendMessage1or2Packet(1,2,2,message2,pn,replyTo);
	}
	
	/*
	 * Authenticator computed over the Responder exponentials and the Nonces
	 * Used by the responder to verify the authenticity of the received data
	 * 
	 * (costs a HMAC and the allocation of a few bytes)
	 */
	private byte[] computeJFKAuthenticator(byte[] gR, byte[] nR, byte[] nI, byte[] address) {
		byte[] authData=new byte[gR.length+nR.length+nI.length+address.length];
		int offset = 0;
		
		System.arraycopy(gR,0,authData,offset,gR.length);
		offset += gR.length;
		System.arraycopy(nR,0,authData,offset,nR.length);
		offset += nR.length+1;
		System.arraycopy(nI,0,authData,offset,nI.length);
		offset += nI.length+1;
		System.arraycopy(address, 0, authData, offset, address.length);
		
		/*
		 * Calculate the Hash of the Concatenated data(Responder exponentials, nonces)
		 * using a key that will be private to the responder
		 */
		// FIXME: SHA1 or SHA256 there ? does it matter ?
		HMAC hash = new HMAC(SHA1.getInstance());
		// TODO: is that 512 LSB ?
		return hash.mac(gR, authData, 9);
	}
	/*
	 * Hash the authenticator using SHA256
	 */
	private byte[] computeHashedJFKAuthenticator(byte[] gR, byte[] nR, byte[] nI, byte[] address) {
		return SHA256.digest(computeJFKAuthenticator(gR, nR, nI, address));
	}

	/*
	 * Responder Method:Message2
	 * Process Message2: Must involve only minimal work for the responder since at that point
	 * no round trip has yet occured with the initiator. Thus, he must not be allowed to perform 
	 * expensive calculations. In message2, his cost will be a single authentication operation
	 * the cost is invocations of 2 cryptographic hash functions and computation of a random nonce.
	 * Send the Initiator nonce,Responder nonce and DiffieHellman Exponential of the responder
	 * in the clear.
	 * Send a signed copy of his own exponential
	 * Send an authenticator which is a hash of Ni,Nr,g^r calculated over the transient key HKr
	 * @param The packet phase number
	 * @param The peer to which we need to send the packet
	 * @param The peerNode we are talking to
	 */

	private void ProcessMessage2(byte[] payload,PeerNode pn,Peer replyTo,int phase)
	{
		long t1=System.currentTimeMillis();
		
		byte[] Ni = iNonce();
		byte[] Nr = rNonce();
		byte[] DHExpr = Gr(pn);
		byte[] authData=new byte[Ni.length+Nr.length+DHExpr.length+1];
		System.arraycopy(Ni,0,authData,0,Ni.length);
		System.arraycopy(Nr,0,authData,Ni.length+1,Nr.length);
		System.arraycopy(DHExpr,0,authData,Ni.length+Nr.length+1,DHExpr.length);
		byte[] signData=new byte[DHExpr.length+1];
		System.arraycopy(DHExpr,0,signData,0,DHExpr.length);
		//Compute the Signature:DSA
		PKR=new DSAPrivateKey(g, r);
		//Params: Data,DSAGroup,DSAPrivateKey,randomSource
		DSASignature sig = crypto.sign(signData,g,PKR,r);
		byte[] r = sig.getRBytes(Node.SIGNATURE_PARAMETER_LENGTH);
		byte[] s = sig.getSBytes(Node.SIGNATURE_PARAMETER_LENGTH);
		Logger.minor(this, " r="+HexUtil.bytesToHex(sig.getR().toByteArray())+" s="+HexUtil.bytesToHex(sig.getS().toByteArray()));
		if(r.length > 255 || s.length > 255)
			throw new IllegalStateException("R or S is too long: r.length="+r.length+" s.length="+s.length);
		//Data sent in the clear
		byte[] unVerifiedData=new byte[Ni.length+Nr.length+DHExpr.length+1];
		System.arraycopy(Ni,0,unVerifiedData,0,Ni.length);
		System.arraycopy(Nr,0,unVerifiedData,Ni.length+1,Nr.length);
		System.arraycopy(DHExpr,0,unVerifiedData,Ni.length+Nr.length+1,DHExpr.length);
		/*
		 * Compute the authenticator
		 * Used by the responder in Message4 to verify the authenticity of the message
		 * The same authenticator is used in Message3 and identified using the DSAPrivateKey 
		 */
		HKrGenerator trKey=new HKrGenerator(node);
		byte[] hkr=trKey.getNewHKr();
		HMAC hash=new HMAC(SHA1.getInstance());
		byte[] authenticator = hash.mac(hkr,authData,hkr.length);
		authenticatorCache.put(PKR,authenticator);
		byte[] Message2=new byte[authenticator.length+unVerifiedData.length+s.length+r.length+1];
		byte[] signedData=new byte[s.length+r.length];
		System.arraycopy(signedData,0,Message2,0,signedData.length);
		System.arraycopy(unVerifiedData,0,Message2,signData.length+1,unVerifiedData.length);
		System.arraycopy(authenticator,0,Message2,signedData.length+unVerifiedData.length+1,authenticator.length);
		//Send params:Version,negType,phase,data,peernode,peer
		sendMessage1or2Packet(1,2,1,Message2,pn,replyTo);
		long t2=System.currentTimeMillis();
		if((t2-t1)>500)
			Logger.error(this,"Message1 timeout error:Sending packet for"+pn.getPeer());

	}
	/*
	 * Initiator Method:Message3
	 * Process Message3
	 * Send the Initiator nonce,Responder nonce and DiffieHellman Exponential of the responder
	 * and initiator in the clear.(unVerifiedData)
	 * Send the authenticator which allows the responder to verify the legality of the message
	 * Compute the signature of the unVerifiedData and encrypt it using a shared key
	 * which is derived from DHExponentials and the nonces
	 * @param The packet phase number
	 * @param The peer to which we need to send the packet
	 * @param The peerNode we are talking to
	 * @return byte Message3
	 */
	private byte[] ProcessMessage3(byte[] payload, PeerNode pn,Peer replyTo,int phase)			
	{
		// Get the authenticator,which is the latest entry into the cache
		// It is basically a keyed hash(HMAC); size of output is that of the underlying hash function 
		byte[] authenticator = new byte[16];
		try{
			// Intrinsic lock provided by the object authenticatorCache
			synchronized(authenticatorCache){
				authenticator = getBytes(authenticatorCache.get(PKR));
			}
		}
		catch(IOException e){
			Logger.error(this,"Error getting bytes");
		}
		byte[] Ni = iNonce();
		byte[] Nr = rNonce();
		byte[] DHExpi = Gi(pn);
		byte[] DHExpr = Gr(pn);
		byte[] unVerifiedData=new byte[Ni.length+Nr.length+DHExpr.length+DHExpi.length+1];
		System.arraycopy(Ni,0,unVerifiedData,0,Ni.length);
		System.arraycopy(Nr,0,unVerifiedData,Ni.length+1,Nr.length);
		System.arraycopy(DHExpi,0,unVerifiedData,Ni.length+Nr.length+1,DHExpi.length);
		System.arraycopy(DHExpr,0,unVerifiedData,Ni.length+Nr.length+DHExpi.length+1,DHExpr.length);
		/*
		 * Digital Signature of the message with the private key belonging to the initiator/responder
		 * It is assumed to be non-message recovering
		 */
		PKI=new DSAPrivateKey(g, r);
		//Params: Data,DSAGroup,DSAPrivateKey,randomSource
		DSASignature sig = crypto.sign(unVerifiedData,g,PKI,r);
		byte[] r = sig.getRBytes(Node.SIGNATURE_PARAMETER_LENGTH);
		byte[] s = sig.getSBytes(Node.SIGNATURE_PARAMETER_LENGTH);
		Logger.minor(this, " r="+HexUtil.bytesToHex(sig.getR().toByteArray())+" s="+HexUtil.bytesToHex(sig.getS().toByteArray()));
		BlockCipher c=pn.outgoingSetupCipher;
		if(logMINOR)
			Logger.minor(this,"Cipher"+HexUtil.bytesToHex(pn.outgoingSetupKey));
		/*
		 * Initializes the cipher context with the given key
		 * This would avoid the computation of key using the Rijndael key schedule(S boxes,Rcon etc)
		 * The key used is generated from Hash of Message:(Ni, Nr, 1) over the shared key of DH
		 */
		c.initialize(encryptionKey.getEncKey(sharedSecretKey(pn),iNonce(),rNonce()));
		PCFBMode pk=PCFBMode.create(c);
		byte[] iv=new byte[pk.lengthIV()];
		int encryptedDataLength = iv.length + r.length + s.length + 2;
		/*
		 * Data sent in the clear is signed and encrypted using PCFB and rijndael
		 */
		byte[] encryptedData = new byte[encryptedDataLength];
		System.arraycopy(iv, 0, encryptedData, 0, iv.length);
		int count = iv.length;
		if(r.length > 255 || s.length > 255)
			throw new IllegalStateException("R or S is too long: r.length="+r.length+" s.length="+s.length);
		encryptedData[count++] = (byte) r.length;
		System.arraycopy(r, 0, encryptedData, count, r.length);
		count += r.length;
		encryptedData[count++] = (byte) s.length;
		System.arraycopy(s, 0, encryptedData, count, s.length);
		count += s.length;
		pk.blockEncipher(encryptedData, 0, encryptedData.length);
		byte[] message3=new byte[encryptedData.length+authenticator.length+unVerifiedData.length+1];
		System.arraycopy(encryptedData,0,message3,0,encryptedData.length);
		System.arraycopy(authenticator,0,message3,encryptedData.length+1,authenticator.length);
		System.arraycopy(unVerifiedData,0,message3,encryptedData.length+authenticator.length+1,unVerifiedData.length);
		return message3;

	}

	/*
	 * Send computed Message3
	 * @param The packet phase number
	 * @param The peer to which we need to send the packet
	 * @param The peerNode we are talking to
	 */
	private void sendProcessMessage3(PeerNode pn,Peer replyTo,int phase){

		/*
		 * The identifying information of the inititator is sent encrypted hence protected
		 * from both passive and active attackers. The initiator's identity cannot be retrived
		 * from message3 because an active attacker cannot complete the DH computation.
		 */
		byte[] data = ProcessMessage3(pn,replyTo,phase);
		
		byte[] address = replyTo.getAddress().getAddress();
		// FIXME: feed computeJFKAuthenticator with the right parameters ^-^
		sendMessage3Packet(1,2,2,data,pn,replyTo, computeHashedJFKAuthenticator(null, null, null, null));
	}

	/*
	 * Responder Method:Message4
	 * Process Message4
	 * Encrypted message of the signature on both nonces, both exponentials using the same
	 * keys as in the previous message.The Initiator can verify that the Responder is present
	 * and participating in the session, by decrypting the message and verifying the enclosed
	 * signature.
	 * @param The packet phase number
	 * @param The peer to which we need to send the packet
	 * @param The peerNode we are talking to
	 */

	private void ProcessMessage4(byte[] payload,PeerNode pn,Peer replyTo,int phase)
	{

		long t1=System.currentTimeMillis();
		byte[] Ni = iNonce();
		byte[] Nr = rNonce();
		byte[] DHExpi = Gi(pn);
		byte[] DHExpr = Gr(pn);
		byte[] Data=new byte[Ni.length+Nr.length+DHExpr.length+DHExpi.length+1];
		System.arraycopy(Ni,0,Data,0,Ni.length);
		System.arraycopy(Nr,0,Data,Ni.length+1,Nr.length);
		System.arraycopy(DHExpi,0,Data,Ni.length+Nr.length+1,DHExpi.length);
		System.arraycopy(DHExpr,0,Data,Ni.length+Nr.length+DHExpi.length+1,DHExpr.length);
		DSASignature sig = crypto.sign(Data,g,PKR,r);
		byte[] r = sig.getRBytes(Node.SIGNATURE_PARAMETER_LENGTH);
		byte[] s = sig.getSBytes(Node.SIGNATURE_PARAMETER_LENGTH);
		Logger.minor(this, " r="+HexUtil.bytesToHex(sig.getR().toByteArray())+" s="+HexUtil.bytesToHex(sig.getS().toByteArray()));
		BlockCipher c=pn.outgoingSetupCipher;
		if(logMINOR)
			Logger.minor(this,"Cipher"+HexUtil.bytesToHex(pn.outgoingSetupKey));
		/*
		 * Initializes the cipher context with the given key
		 * This would avoid the computation of key using the Rijndael key schedule(S boxes,Rcon etc)
		 * The key used is generated from Hash of Message:(Ni, Nr, 1) over the shared key of DH
		 */
		c.initialize(encryptionKey.getEncKey(sharedSecretKey(pn),iNonce(),rNonce()));
		PCFBMode pk=PCFBMode.create(c);
		byte[] iv=new byte[pk.lengthIV()];
		int message4Length = r.length + s.length + 2;
		byte[] message4 = new byte[message4Length];
		System.arraycopy(iv, 0, message4, 0, iv.length);
		int count = iv.length;
		if(r.length > 255 || s.length > 255)
			throw new IllegalStateException("R or S is too long: r.length="+r.length+" s.length="+s.length);
		message4[count++] = (byte) r.length;
		System.arraycopy(r, 0, message4, count, r.length);
		count += r.length;
		message4[count++] = (byte) s.length;
		System.arraycopy(s, 0, message4, count, s.length);
		count += s.length;
		pk.blockEncipher(message4, 0, message4.length);
		//Send params:Version,negType,phase,data,peernode,peer 
		sendMessage4Packet(1,2,3,message4,pn,replyTo);
		long t2=System.currentTimeMillis();
		if((t2-t1)>500)
			Logger.error(this,"Message4 timeout error:Sending packet for"+pn.getPeer());

	}	

	/*
	 * Send Message1or2 packet
	 * @param version
	 * @param negType
	 * @param The packet phase number
	 * @param Concatenated data
	 * @param The peerNode we are talking to
	 * @param The peer to which we need to send the packet
	 */
	private void sendMessage1or2Packet(int version,int negType,int phase,byte[] data,PeerNode pn,Peer replyTo)
	{
		long now = System.currentTimeMillis();
		long delta = now - pn.lastSentPacketTime();
		byte[] output = new byte[data.length+3];
		output[0] = (byte) version;
		output[1] = (byte) negType;
		output[2] = (byte) phase;
		System.arraycopy(data, 0, output, 3, data.length);
		if(logMINOR) Logger.minor(this, "Sending auth packet for "+pn.getPeer()+" (phase="+phase+", ver="+version+", nt="+negType+") (last packet sent "+TimeUtil.formatTime(delta, 2, true)+" ago) to "+replyTo+" data.length="+data.length);
		try
		{
			sendPacket(output,replyTo,pn,0);
		}catch(LocalAddressException e)
		{
			Logger.error(this, "Tried to send auth packet to local address: "+replyTo+" for "+pn);
		}
	}

	/*
	 * Convert Object to byteArray
	 */
	private byte[] getBytes(Object o) throws IOException
	{
		ByteArrayOutputStream bs = new ByteArrayOutputStream();
		ObjectOutputStream os = new ObjectOutputStream(bs);
		os.writeObject(o);
		os.flush();
		os.close();
		bs.close();
		byte [] output = bs.toByteArray();
		return output;
	}

	/*
	 * Send Message3 packet
	 * Also check for duplicate message
	 * @param version
	 * @param negType
	 * @param The packet phase number
	 * @param Concatenated data
	 * @param The peerNode we are talking to
	 * @param The peer to which we need to send the packet
	 */

	private void sendMessage3Packet(int version,int negType,int phase,byte[] data,PeerNode pn,Peer replyTo, byte[] hashedAuthenticator)
	{
		long now = System.currentTimeMillis();
		long delta = now - pn.lastSentPacketTime();
		byte[] output = new byte[data.length+3];
		if((data.length+3) > sock.getMaxPacketSize())
			throw new IllegalStateException("Packet length too long");
		/*
		 * The key for looking up messages in the cache is the authenticator
		 * This prevents DOS attacks where the attacker randomly tries to replace encrypted blocks 
		 * of a valid message causing a cache miss
		 * This would result in increased processing on the Responder side->CPU exhaustion attacks
		 */
		Object result;
		//All recent messages 3 and 4 are cached
		if(phase==2){
			// Intrinsic lock provided by the object message3Cache
			synchronized(message3Cache) {
				result = message3Cache.get(hashedAuthenticator);
			}
			if(result != null) {
				synchronized(message3Cache) {
					message3Cache.put(hashedAuthenticator,data);
				}
				// We don't want to keep the lock while sending
				try
				{
					sendMessage4Packet(1,2,3,getBytes(message4Cache.get(hashedAuthenticator)),pn,replyTo);
				}
				catch(IOException e){
					Logger.error(this,"Error getting bytes");
				}
			}
			sendProcessMessage3(pn,replyTo,phase);       
		}
		else if(phase==3){
			message4Cache.put(hashedAuthenticator,data.toString());
		}
		else{ 
			Logger.error(this,"Wrong message");

		}   
		output[0] = (byte) version;
		output[1] = (byte) negType;
		output[2] = (byte) phase;
		System.arraycopy(data, 0, output, 3, data.length);
		if(logMINOR) Logger.minor(this, "Sending auth packet for "+pn.getPeer()+" (phase="+phase+", ver="+version+", nt="+negType+") (last packet sent "+TimeUtil.formatTime(delta, 2, true)+" ago) to "+replyTo+" data.length="+data.length);
		try
		{
			sendPacket(output,replyTo,pn,0);
		}catch(LocalAddressException e) {
			Logger.error(this, "Tried to send auth packet to local address: "+replyTo+" for "+pn.getPeer());
		}

	}

	/*
	 * Send Message4 packet
	 * @param version
	 * @param negType
	 * @param The packet phase number
	 * @param Concatenated data
	 * @param The peerNode we are talking to
	 * @param The peer to which we need to send the packet
	 */

	private void sendMessage4Packet(int version,int negType,int phase,byte[] data,PeerNode pn,Peer replyTo)
	{
		long now = System.currentTimeMillis();
		long delta = now - pn.lastSentPacketTime();
		byte[] output = new byte[data.length+3];
		if((data.length+3) > sock.getMaxPacketSize())
			throw new IllegalStateException("Packet length too long");
		output[0] = (byte) version;
		output[1] = (byte) negType;
		output[2] = (byte) phase;
		System.arraycopy(data, 0, output, 3, data.length);
		if(logMINOR) Logger.minor(this, "Sending auth packet for "+pn.getPeer()+" (phase="+phase+", ver="+version+", nt="+negType+") (last packet sent "+TimeUtil.formatTime(delta, 2, true)+" ago) to "+replyTo+" data.length="+data.length);
		try
		{
			sendPacket(output,replyTo,pn,0);
		}catch(LocalAddressException e)
		{
			Logger.error(this, "Tried to send auth packet to local address: "+replyTo+" for "+pn.getPeer());
		}

	}

	/**
	 * Send a signed DH completion message.
	 * Format:
	 * IV
	 * Signature on { My exponential, his exponential, data }
	 * Data
	 * @param phase The packet phase number. Either 2 or 3.
	 * @param cipher The negotiated cipher.
	 * @param pn The PeerNode which we are talking to.
	 * @param replyTo The Peer to which to send the packet (not necessarily the same
	 * as the one on pn as the IP may have changed).
	 */
	private void sendSignedDHCompletion(int phase, BlockCipher cipher, PeerNode pn, Peer replyTo, DiffieHellmanContext ctx) {
		PCFBMode pcfb = PCFBMode.create(cipher);
		byte[] iv = new byte[pcfb.lengthIV()];

		byte[] myRef = crypto.myCompressedSetupRef();
		byte[] data = new byte[myRef.length + 8];
		System.arraycopy(Fields.longToBytes(node.bootID), 0, data, 0, 8);
		System.arraycopy(myRef, 0, data, 8, myRef.length);

		byte[] myExp = ctx.getOurExponential().toByteArray();
		byte[] hisExp = ctx.getHisExponential().toByteArray();

		MessageDigest md = SHA256.getMessageDigest();
		md.update(myExp);
		md.update(hisExp);
		md.update(data);
		byte[] hash = md.digest();

		DSASignature sig = crypto.sign(hash);

		byte[] r = sig.getRBytes(Node.SIGNATURE_PARAMETER_LENGTH);
		byte[] s = sig.getSBytes(Node.SIGNATURE_PARAMETER_LENGTH);

		Logger.minor(this, "Sending DH completion: "+pn+" hash "+HexUtil.bytesToHex(hash)+" r="+HexUtil.bytesToHex(sig.getR().toByteArray())+" s="+HexUtil.bytesToHex(sig.getS().toByteArray()));

		int outputLength = iv.length + data.length + r.length + s.length + 2;

		byte[] output = new byte[outputLength];

		System.arraycopy(iv, 0, output, 0, iv.length);
		int count = iv.length;
		if(r.length > 255 || s.length > 255)
			throw new IllegalStateException("R or S is too long: r.length="+r.length+" s.length="+s.length);
		output[count++] = (byte) r.length;
		System.arraycopy(r, 0, output, count, r.length);
		count += r.length;
		output[count++] = (byte) s.length;
		System.arraycopy(s, 0, output, count, s.length);
		count += s.length;
		System.arraycopy(data, 0, output, count, data.length);

		pcfb.blockEncipher(output, 0, output.length);

		sendAuthPacket(1, 1, phase, output, pn, replyTo);
	}

	/**
	 * Send a first-half (phase 0 or 1) DH negotiation packet to the node.
	 * @param phase The phase of the message to be sent (0 or 1).
	 * @param negType The negotiation type.
	 * @param integer Our exponential
	 * @param replyTo The peer to reply to
	 */
	private void sendFirstHalfDHPacket(int phase, int negType, NativeBigInteger integer, PeerNode pn, Peer replyTo) {
		long time1 = System.currentTimeMillis();
		if(logMINOR) Logger.minor(this, "Sending ("+phase+") "+integer.toHexString()+" to "+pn.getPeer());
		byte[] data = integer.toByteArray();
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
		if(logMINOR) Logger.minor(this, "Processed: "+HexUtil.bytesToHex(data));
		long time2 = System.currentTimeMillis();
		if((time2 - time1) > 200) {
			Logger.error(this, "sendFirstHalfDHPacket: time2 is more than 200ms after time1 ("+(time2 - time1)+") working on "+replyTo+" of "+pn.userToString());
		}
		sendAuthPacket(1, negType, phase, data, pn, replyTo);
		long time3 = System.currentTimeMillis();
		if((time3 - time2) > 500) {
			Logger.error(this, "sendFirstHalfDHPacket:sendAuthPacket() time3 is more than half a second after time2 ("+(time3 - time2)+") working on "+replyTo+" of "+pn.userToString());
		}
		if((time3 - time1) > 500) {
			Logger.error(this, "sendFirstHalfDHPacket: time3 is more than half a second after time1 ("+(time3 - time1)+") working on "+replyTo+" of "+pn.userToString());
		}
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
		if(logMINOR) Logger.minor(this, "Sending auth packet (long) to "+replyTo+" - size "+data.length+" data length: "+output.length);
	}

	private void sendPacket(byte[] data, Peer replyTo, PeerNode pn, int alreadyReportedBytes) throws LocalAddressException {
		if(pn.isIgnoreSource()) {
			Peer p = pn.getPeer();
			if(p != null) replyTo = p;
		}
		sock.sendPacket(data, replyTo, pn.allowLocalAddresses());
		pn.reportOutgoingBytes(data.length);
		node.outputThrottle.forceGrab(data.length - alreadyReportedBytes);
	}

	/**
	 * Process a stage 2 or stage 3 auth packet.
	 * Send a signed DH completion message.
	 * Format:
	 * IV
	 * Signature on { My exponential, his exponential, data }
	 * Data
	 * 
	 * May decrypt in place.
	 */
	private DiffieHellmanContext processSignedDHTwoOrThree(int phase, byte[] payload, PeerNode pn, Peer replyTo, boolean sendCompletion) {
		if(logMINOR) Logger.minor(this, "Handling signed stage "+phase+" auth packet");
		// Get context, cipher, IV
		DiffieHellmanContext ctx = (DiffieHellmanContext) pn.getKeyAgreementSchemeContext();
		if((ctx == null) || !ctx.canGetCipher()) {
			if(shouldLogErrorInHandshake()) {
				Logger.error(this, "Cannot get cipher");
			}
			return null;
		}
		byte[] encKey = ctx.getKey();
		BlockCipher cipher = ctx.getCipher();
		PCFBMode pcfb = PCFBMode.create(cipher);
		int ivLength = pcfb.lengthIV();
		if(payload.length-3 < HASH_LENGTH + ivLength + 8) {
			Logger.error(this, "Too short phase "+phase+" packet from "+replyTo+" probably from "+pn);
			return null;
		}
		pcfb.reset(payload, 3); // IV

		// Decrypt the rest
		pcfb.blockDecipher(payload, 3, payload.length - 3);

		int count = 3 + ivLength;

		// R
		int rLen = payload[count++] & 0xFF;
		byte[] rBytes = new byte[rLen];
		System.arraycopy(payload, count, rBytes, 0, rLen);
		count += rLen;
		NativeBigInteger r = new NativeBigInteger(1, rBytes);

		// S
		int sLen = payload[count++] & 0xFF;
		byte[] sBytes = new byte[sLen];
		System.arraycopy(payload, count, sBytes, 0, sLen);
		count += sLen;
		NativeBigInteger s = new NativeBigInteger(1, sBytes);

		DSASignature sig = new DSASignature(r, s);

		// Data
		byte[] data = new byte[payload.length - count];
		System.arraycopy(payload, count, data, 0, payload.length - count);

		// Now verify
		MessageDigest md = SHA256.getMessageDigest();
		md.update(ctx.getHisExponential().toByteArray());
		md.update(ctx.getOurExponential().toByteArray());
		md.update(data);
		byte[] hash = md.digest();
		if(!pn.verify(hash, sig)) {
			Logger.error(this, "Signature verification failed for "+pn+" hash "+HexUtil.bytesToHex(hash)+" r="+HexUtil.bytesToHex(sig.getR().toByteArray())+" s="+HexUtil.bytesToHex(sig.getS().toByteArray()));
			return null;
		}

		// Success!
		long bootID = Fields.bytesToLong(data);
		// Send the completion before parsing the data, because this is easiest
		// Doesn't really matter - if it fails, we get loads of errors anyway...
		// Only downside is that the other side might still think we are connected for a while.
		// But this should be extremely rare.
		// REDFLAG?
		// We need to send the completion before the PN sends any packets, that's all...
		if(pn.completedHandshake(bootID, data, 8, data.length-8, cipher, encKey, replyTo, phase == 2)) {
			if(sendCompletion)
				sendSignedDHCompletion(3, ctx.getCipher(), pn, replyTo, ctx);
			pn.maybeSendInitialMessages();
		} else {
			Logger.error(this, "Handshake not completed");
		}
		return ctx;
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
	 * Process a phase-0 or phase-1 Diffie-Hellman packet.
	 * @return a DiffieHellmanContext if we succeeded, otherwise null.
	 */
	private DiffieHellmanContext processDHZeroOrOne(int phase, byte[] payload, PeerNode pn) {

		if((phase == 0) && pn.hasLiveHandshake(System.currentTimeMillis())) {
			if(logMINOR) Logger.minor(this, "Rejecting phase "+phase+" handshake on "+pn+" - already running one");
			return null;
		}

		// First, get the BigInteger
		int length = DiffieHellman.modulusLengthInBytes();
		if(payload.length < length + 3) {
			Logger.error(this, "Packet too short: "+payload.length+" after decryption in DH("+phase+"), should be "+(length + 3));
			return null;
		}
		byte[] aAsBytes = new byte[length];
		System.arraycopy(payload, 3, aAsBytes, 0, length);
		NativeBigInteger a = new NativeBigInteger(1, aAsBytes);
		DiffieHellmanContext ctx;
		if(phase == 1) {
			ctx = (DiffieHellmanContext) pn.getKeyAgreementSchemeContext();
			if(ctx == null) {
				if(shouldLogErrorInHandshake())
					Logger.error(this, "Could not get context for phase 1 handshake from "+pn);
				return null;
			}
		} else {
			ctx = DiffieHellman.generateContext();
			// Don't calculate the key until we need it
			pn.setKeyAgreementSchemeContext(ctx);
		}
		ctx.setOtherSideExponential(a);
		if(logMINOR) Logger.minor(this, "His exponential: "+a.toHexString());
		// REDFLAG: This is of course easily DoS'ed if you know the node.
		// We will fix this by means of JFKi.
		return ctx;
	}

	/**
	 * Try to process an incoming packet with a given PeerNode.
	 * We need to know where the packet has come from in order to
	 * decrypt and authenticate it.
	 */
	private boolean tryProcess(byte[] buf, int offset, int length, KeyTracker tracker) {
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
		tracker.pn.reportIncomingBytes(length);
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

		if(seqNumber == -1) {
			if(logMINOR) Logger.minor(this, "Returning because seqno = "+seqNumber);
			return;
		}
		// No sequence number == no messages

		if((seqNumber != -1) && tracker.alreadyReceived(seqNumber)) {
			tracker.queueAck(seqNumber);
			tracker.pn.receivedPacket(false);
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
					kt = pn.getCurrentKeyTracker();
					if(kt == null) {
						if(logMINOR) Logger.minor(this, "kt = null");
						pn.requeueMessageItems(messages, i, messages.length-i, false, "kt = null");
						return;
					}
					int packetNumber = kt.allocateOutgoingPacketNumberNeverBlock();
					this.processOutgoingPreformatted(buf, 0, buf.length, pn.getCurrentKeyTracker(), packetNumber, mi.cb, mi.alreadyReportedBytes);
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
						Logger.error(this, "Peer is connected, yet current tracker is deprecated !!: "+e, e);
						throw new NotConnectedException("Peer is connected, yet current tracker is deprecated !!: "+e);
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
		int negType = pn.bestNegType(this);
		if(negType == -1) {
			if(pn.isRoutingCompatible())
				Logger.error(this, "Could not negotiate with "+pn+" : no common negTypes available!: his negTypes: "+StringArray.toString(pn.negTypes)+" my negTypes: "+StringArray.toString(supportedNegTypes())+" despite being up to date!!");
			else
				Logger.minor(this, "Could not negotiate with "+pn+" : no common negTypes available!: his negTypes: "+StringArray.toString(pn.negTypes)+" my negTypes: "+StringArray.toString(supportedNegTypes())+" (probably just too old)");
			return;
		}
		if(logMINOR) Logger.minor(this, "Possibly sending handshake to "+pn+" negotiation type "+negType);
		DiffieHellmanContext ctx;
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
		} else {
			long DHTime1 = System.currentTimeMillis();
			ctx = DiffieHellman.generateContext();
			long DHTime2 = System.currentTimeMillis();
			if((DHTime2 - DHTime1) > 1000)
				Logger.error(this, "DHTime2 is more than a second after DHTime1 ("+(DHTime2 - DHTime1)+") working on "+pn.userToString());
			pn.setKeyAgreementSchemeContext(ctx);
			long DHTime3 = System.currentTimeMillis();
			if((DHTime3 - DHTime2) > 1000)
				Logger.error(this, "DHTime3 is more than a second after DHTime2 ("+(DHTime3 - DHTime2)+") working on "+pn.userToString());
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
			sendFirstHalfDHPacket(0, negType, ctx.getOurExponential(), pn, peer);
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
	
	private synchronized DiffieHellmanLightContext getLightDiffieHellmanContext() {
		if(currentDHContext == null){
			currentDHContext = DiffieHellman.generateLightContext();
		return currentDHContext;
	}
}
