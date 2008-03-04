/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import java.util.Enumeration;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Vector;

import freenet.crypt.BlockCipher;
import freenet.io.comm.AsyncMessageCallback;
import freenet.io.comm.DMT;
import freenet.io.comm.NotConnectedException;
import freenet.io.xfer.PacketThrottle;
import freenet.support.DoublyLinkedList;
import freenet.support.DoublyLinkedListImpl;
import freenet.support.IndexableUpdatableSortedLinkedListItem;
import freenet.support.LimitedRangeIntByteArrayMap;
import freenet.support.LimitedRangeIntByteArrayMapElement;
import freenet.support.Logger;
import freenet.support.ReceivedPacketNumbers;
import freenet.support.TimeUtil;
import freenet.support.UpdatableSortedLinkedListItem;
import freenet.support.UpdatableSortedLinkedListKilledException;
import freenet.support.UpdatableSortedLinkedListWithForeignIndex;
import freenet.support.WouldBlockException;
import freenet.support.DoublyLinkedList.Item;

/**
 * @author amphibian
 * 
 * Class to track everything related to a single key on a single
 * PeerNode. In particular, the key itself, packets sent and
 * received, and packet numbers.
 */
public class KeyTracker {

	private static boolean logMINOR;
	
    /** Parent PeerNode */
    public final PeerNode pn;
    
    /** Are we the secondary key? */
    private boolean isDeprecated;
    
    /** Cipher to both encrypt outgoing packets with and decrypt
     * incoming ones. */
    public final BlockCipher sessionCipher;
    
    /** Key for above cipher, so far for debugging */
    public final byte[] sessionKey;
    
    /** Packets we have sent to the node, minus those that have
     * been acknowledged. */
    private final LimitedRangeIntByteArrayMap sentPacketsContents;
    
    /** Serial numbers of packets that we want to acknowledge,
     * and when they become urgent. We always add to the end,
     * and we always remove from the beginning, so should always
     * be consistent. */
    private final DoublyLinkedList ackQueue;
    
    /** Serial numbers of packets that we have forgotten. Usually
     * when we have forgotten a packet it just means that it has 
     * been shifted to another KeyTracker because this one was
     * deprecated; the messages will get through in the end.
     */
    private final DoublyLinkedList forgottenQueue;
    
    /** The highest incoming serial number we have ever seen
     * from the other side. Includes actual packets and resend
     * requests (provided they are within range). */
    private int highestSeenIncomingSerialNumber;
    
    /** Serial numbers of packets we want to be resent by the
     * other side to us, the time at which they become sendable,
     * and the time at which they become urgent. In order of
     * the latter. */
    private final UpdatableSortedLinkedListWithForeignIndex resendRequestQueue;
    
    /** Serial numbers of packets we want to be acknowledged by
     * the other side, the time at which they become sendable,
     * and the time at which they become urgent. In order of
     * the latter. */
    private final UpdatableSortedLinkedListWithForeignIndex ackRequestQueue;
    
    /** Numbered packets that we need to send to the other side
     * because they asked for them. Just contains the numbers. */
    private final HashSet packetsToResend;
    
    /** Ranges of packet numbers we have received from the other
     * side. */
    private final ReceivedPacketNumbers packetNumbersReceived;
    
    /** Counter to generate the next packet number */
    private int nextPacketNumber;
    
    /** Everything is clear to start with */
    KeyTracker(PeerNode pn, BlockCipher cipher, byte[] sessionKey) {
        this.pn = pn;
        this.sessionCipher = cipher;
        this.sessionKey = sessionKey;
        ackQueue = new DoublyLinkedListImpl();
        forgottenQueue = new DoublyLinkedListImpl();
        highestSeenIncomingSerialNumber = -1;
        // give some leeway
        sentPacketsContents = new LimitedRangeIntByteArrayMap(128);
        resendRequestQueue = new UpdatableSortedLinkedListWithForeignIndex();
        ackRequestQueue = new UpdatableSortedLinkedListWithForeignIndex();
        packetsToResend = new HashSet();
        packetNumbersReceived = new ReceivedPacketNumbers(512);
        isDeprecated = false;
        nextPacketNumber = pn.node.random.nextInt(100*1000);
        logMINOR = Logger.shouldLog(Logger.MINOR, this);
    }

    /**
     * Set the deprecated flag to indicate that we are now
     * no longer the primary key. And wake up any threads trying to lock
     * a packet number; they can be sent with the new KT.
     * 
     * After this, new packets will not be sent. It will not be possible to allocate a new
     * packet number. However, old resend requests etc may still be sent.
     */
    public void deprecated() {
        logMINOR = Logger.shouldLog(Logger.MINOR, this);
        isDeprecated = true;
        sentPacketsContents.interrupt();
    }
    
    /**
     * @return The highest received incoming serial number.
     */
    public int highestReceivedIncomingSeqNumber() {
        return this.highestSeenIncomingSerialNumber;
    }

    /**
     * Received this packet??
     */
    public boolean alreadyReceived(int seqNumber) {
        return packetNumbersReceived.contains(seqNumber);
    }
    
    /** toString() - don't leak the key unless asked to */
    public String toString() {
        return super.toString()+" for "+pn.shortToString();
    }

    /**
     * Queue an ack to be sent back to the node soon.
     * @param seqNumber The number of the packet to be acked.
     */
    public void queueAck(int seqNumber) {
        if(logMINOR) Logger.minor(this, "Queueing ack for "+seqNumber);
        QueuedAck qa = new QueuedAck(seqNumber);
        synchronized(ackQueue) {
            ackQueue.push(qa);
        }
        // Will go urgent in 200ms
    }
    
    public void queueForgotten(int seqNumber) {
    	queueForgotten(seqNumber, true);
    }
    
    public void queueForgotten(int seqNumber, boolean log) {
    	if(log && ((!isDeprecated) || logMINOR)) {
    		String msg = "Queueing forgotten for "+seqNumber+" for "+this;
    		if(!isDeprecated) Logger.error(this, msg);
    		else Logger.minor(this, msg);
    	}
    	QueuedForgotten qf = new QueuedForgotten(seqNumber);
    	synchronized(forgottenQueue) {
    		forgottenQueue.push(qf);
    	}
    }
    
    class PacketActionItem { // anyone got a better name?
        /** Packet sequence number */
        int packetNumber;
        /** Time at which this packet's ack or resend request becomes urgent
         * and can trigger an otherwise empty packet to be sent. */
        long urgentTime;
        
        public String toString() {
            return super.toString()+": packet "+packetNumber+" urgent@"+urgentTime+ '(' +(System.currentTimeMillis()-urgentTime)+ ')';
        }
    }
    
    class QueuedAck extends PacketActionItem implements DoublyLinkedList.Item {
        void sent() {
            synchronized(ackQueue) {
                ackQueue.remove(this);
            }
        }
        
        QueuedAck(int packet) {
            long now = System.currentTimeMillis();
            packetNumber = packet;
            /** If not included on a packet in next 200ms, then
             * force a send of an otherwise empty packet.
             */
            urgentTime = now + 200;
        }

        Item prev;
        Item next;
        
        public Item getNext() {
            return next;
        }

        public Item setNext(Item i) {
            Item old = next;
            next = i;
            return old;
        }

        public Item getPrev() {
            return prev;
        }

        public Item setPrev(Item i) {
            Item old = prev;
            prev = i;
            return old;
        }

        private DoublyLinkedList parent;
        
		public DoublyLinkedList getParent() {
			return parent;
		}

		public DoublyLinkedList setParent(DoublyLinkedList l) {
			DoublyLinkedList old = parent;
			parent = l;
			return old;
		}
    }

    // FIXME this is almost identical to QueuedAck, coalesce the classes
    class QueuedForgotten extends PacketActionItem implements DoublyLinkedList.Item {
        void sent() {
            synchronized(forgottenQueue) {
                forgottenQueue.remove(this);
            }
        }
        
        QueuedForgotten(int packet) {
            long now = System.currentTimeMillis();
            packetNumber = packet;
            /** If not included on a packet in next 500ms, then
             * force a send of an otherwise empty packet.
             */
            urgentTime = now + 500;
        }

        Item prev;
        Item next;
        
        public Item getNext() {
            return next;
        }

        public Item setNext(Item i) {
            Item old = next;
            next = i;
            return old;
        }

        public Item getPrev() {
            return prev;
        }

        public Item setPrev(Item i) {
            Item old = prev;
            prev = i;
            return old;
        }

        private DoublyLinkedList parent;
        
		public DoublyLinkedList getParent() {
			return parent;
		}

		public DoublyLinkedList setParent(DoublyLinkedList l) {
			DoublyLinkedList old = parent;
			parent = l;
			return old;
		}
    }

    private abstract class BaseQueuedResend extends PacketActionItem implements IndexableUpdatableSortedLinkedListItem {
        /** Time at which this item becomes sendable.
         * When we send a resend request, this is reset to t+500ms.
         * 
         * Constraint: urgentTime is always greater than activeTime.
         */
        long activeTime;
        final Integer packetNumberAsInteger;
        
        void sent() throws UpdatableSortedLinkedListKilledException {
            long now = System.currentTimeMillis();
            activeTime = now + 500;
            urgentTime = activeTime + urgentDelay();
            // This is only removed when we actually receive the packet
            // But for now it will sleep
        }
        
        BaseQueuedResend(int packetNumber) {
            this.packetNumber = packetNumber;
            packetNumberAsInteger = new Integer(packetNumber);
            long now = System.currentTimeMillis();
            activeTime = initialActiveTime(now);
            urgentTime = activeTime + urgentDelay();
        }

        abstract long urgentDelay();
        
        abstract long initialActiveTime(long now);

        private Item next;
        private Item prev;
        
        public final Item getNext() {
            return next;
        }

        public final Item setNext(Item i) {
            Item old = next;
            next = i;
            return old;
        }

        public Item getPrev() {
            return prev;
        }

        public Item setPrev(Item i) {
            Item old = prev;
            prev = i;
            return old;
        }

        public int compareTo(Object o) {
            BaseQueuedResend r = (BaseQueuedResend)o;
            if(urgentTime > r.urgentTime) return 1;
            if(urgentTime < r.urgentTime) return -1;
            if(packetNumber > r.packetNumber) return 1;
            if(packetNumber < r.packetNumber) return -1;
            return 0;
        }
        
        public Object indexValue() {
            return packetNumberAsInteger;
        }
        
        private DoublyLinkedList parent;
        
		public DoublyLinkedList getParent() {
			return parent;
		}

		public DoublyLinkedList setParent(DoublyLinkedList l) {
			DoublyLinkedList old = parent;
			parent = l;
			return old;
		}
    }
    
    private class QueuedResendRequest extends BaseQueuedResend {
        long initialActiveTime(long now) {
            return now; // Active immediately; reordering is rare
        }
        
        QueuedResendRequest(int packetNumber) {
            super(packetNumber);
        }
        
        void sent() throws UpdatableSortedLinkedListKilledException {
            synchronized(resendRequestQueue) {
                super.sent();
                resendRequestQueue.update(this);
            }
        }

		long urgentDelay() {
			return PacketSender.MAX_COALESCING_DELAY; // Urgent pretty soon
		}
    }
    
    private class QueuedAckRequest extends BaseQueuedResend {
    	
    	final long createdTime;
    	long activeDelay;
    	
    	long initialActiveTime(long now) {
    		// Request an ack after four RTTs
    		activeDelay = twoRTTs();
    		return now + activeDelay;
        }
        
        QueuedAckRequest(int packetNumber, boolean sendSoon) {
            super(packetNumber);
            this.createdTime = System.currentTimeMillis();
            if(sendSoon) {
                activeTime -= activeDelay;
                urgentTime -= activeDelay;
            }
        }
        
        void sent() throws UpdatableSortedLinkedListKilledException {
            synchronized(ackRequestQueue) {
                super.sent();
                ackRequestQueue.update(this);
            }
        }

        /**
         * Acknowledged.
         */
		public void onAcked() {
			long t = Math.max(0, System.currentTimeMillis() - createdTime);
			pn.reportPing(t);
			if(logMINOR) Logger.minor(this, "Reported round-trip time of "+TimeUtil.formatTime(t, 2, true)+" on "+pn.getPeer()+" (avg "+TimeUtil.formatTime((long)pn.averagePingTime(),2,true)+", #"+packetNumber+ ')');
		}

		long urgentDelay() {
			return PacketSender.MAX_COALESCING_DELAY;
		}
    }
    
    /**
     * Called when we receive a packet.
     * @param seqNumber The packet's serial number.
     * See the comments in FNPPacketMangler.processOutgoing* for
     * the reason for the locking.
     */
    public synchronized void receivedPacket(int seqNumber) {
        logMINOR = Logger.shouldLog(Logger.MINOR, this);
    	if(logMINOR) Logger.minor(this, "Received packet "+seqNumber+" from "+pn.shortToString());
        if(seqNumber == -1) return;
        // FIXME delete this log statement
        if(logMINOR) Logger.minor(this, "Still received packet: "+seqNumber);
        // Received packet
        receivedPacketNumber(seqNumber);
        // Ack it even if it is a resend
        queueAck(seqNumber);
    }

    // TCP uses four RTTs with no ack to resend ... but we have a more drawn out protocol, we
    // should use only two.
    public long twoRTTs() {
    	// FIXME upper bound necessary ?
    	return (long) Math.min(Math.max(250, pn.averagePingTime()*2), 2500);
    }
    
    protected void receivedPacketNumber(int seqNumber) {
    	if(logMINOR) Logger.minor(this, "Handling received packet number "+seqNumber);
        queueResendRequests(seqNumber);
        packetNumbersReceived.got(seqNumber);
        try {
			removeResendRequest(seqNumber);
		} catch (UpdatableSortedLinkedListKilledException e) {
			// Ignore, not our problem
		}
        synchronized(this) {
        	highestSeenIncomingSerialNumber = Math.max(highestSeenIncomingSerialNumber, seqNumber);
        }
        if(logMINOR) Logger.minor(this, "Handled received packet number "+seqNumber);
    }
    
    /**
     * Remove a resend request from the queue.
     * @param seqNumber
     * @throws UpdatableSortedLinkedListKilledException 
     */
    private void removeResendRequest(int seqNumber) throws UpdatableSortedLinkedListKilledException {
    	resendRequestQueue.removeByKey(new Integer(seqNumber));
    }

    /**
     * Add some resend requests if necessary.
     * @param seqNumber The number of the packet we just received.
     */
    private void queueResendRequests(int seqNumber) {
        int max;
        synchronized(this) {
        	max = packetNumbersReceived.highest();
        }
        if(seqNumber > max) {
        	try {
            if((max != -1) && (seqNumber - max > 1)) {
            	if(logMINOR) Logger.minor(this, "Queueing resends from "+max+" to "+seqNumber);
                // Missed some packets out
                for(int i=max+1;i<seqNumber;i++) {
                    queueResendRequest(i);
                }
            }
        	} catch (UpdatableSortedLinkedListKilledException e) {
        		// Ignore (we are decoding packet, not sending one)
        	}
        }
    }

    /**
     * Queue a resend request
     * @param packetNumber the packet serial number to queue a
     * resend request for
     * @throws UpdatableSortedLinkedListKilledException 
     */
    private void queueResendRequest(int packetNumber) throws UpdatableSortedLinkedListKilledException {
    	synchronized(resendRequestQueue) {
    		if(queuedResendRequest(packetNumber)) {
    			if(logMINOR) Logger.minor(this, "Not queueing resend request for "+packetNumber+" - already queued");
    			return;
    		}
    		if(logMINOR) Logger.minor(this, "Queueing resend request for "+packetNumber);
    		QueuedResendRequest qrr = new QueuedResendRequest(packetNumber);
    		resendRequestQueue.add(qrr);
    	}
    }

    /**
     * Queue an ack request
     * @param packetNumber the packet serial number to queue a
     * resend request for
     * @throws UpdatableSortedLinkedListKilledException 
     */
    private void queueAckRequest(int packetNumber) throws UpdatableSortedLinkedListKilledException {
        synchronized(ackRequestQueue) {
            if(queuedAckRequest(packetNumber)) {
            	if(logMINOR) Logger.minor(this, "Not queueing ack request for "+packetNumber+" - already queued");
                return;
            }
            if(logMINOR) Logger.minor(this, "Queueing ack request for "+packetNumber+" on "+this);
            QueuedAckRequest qrr = new QueuedAckRequest(packetNumber, false);
            ackRequestQueue.add(qrr);
        }
    }

    /**
     * Is an ack request queued for this packet number?
     */
    private boolean queuedAckRequest(int packetNumber) {
        return ackRequestQueue.containsKey(new Integer(packetNumber));
    }

    /**
     * Is a resend request queued for this packet number?
     */
    private boolean queuedResendRequest(int packetNumber) {
        return resendRequestQueue.containsKey(new Integer(packetNumber));
    }

    /**
     * Called when we have received several packet acknowledgements.
     * Synchronized for the same reason as the sender code is:
     * So that we don't end up sending packets too late when overloaded,
     * and get horrible problems such as asking to resend packets which
     * haven't been sent yet.
     */
    public synchronized void acknowledgedPackets(int[] seqNos) {
    	AsyncMessageCallback[][] callbacks = new AsyncMessageCallback[seqNos.length][];
	for(int i=0;i<seqNos.length;i++) {
		int realSeqNo = seqNos[i];
		if(logMINOR) Logger.minor(this, "Acknowledged packet: "+realSeqNo);
		try {
			removeAckRequest(realSeqNo);
		} catch (UpdatableSortedLinkedListKilledException e) {
			// Ignore, we are processing an incoming packet
		}
		if(logMINOR) Logger.minor(this, "Removed ack request");
		callbacks[i] = sentPacketsContents.getCallbacks(realSeqNo);
		byte[] buf = sentPacketsContents.get(realSeqNo);
		long timeAdded = sentPacketsContents.getTime(realSeqNo);
		if(sentPacketsContents.remove(realSeqNo)) {
			if(buf.length > Node.PACKET_SIZE) {
				PacketThrottle throttle = getThrottle();
				throttle.notifyOfPacketAcknowledged();
				throttle.setRoundTripTime(System.currentTimeMillis() - timeAdded);
			}
		}
	}
    	int cbCount = 0;
    	for(int i=0;i<callbacks.length;i++) {
    		AsyncMessageCallback[] cbs = callbacks[i];
    		if(cbs != null) {
    			for(int j=0;j<cbs.length;j++) {
    				cbs[j].acknowledged();
    				cbCount++;
    			}
    		}
    	}
    	if(cbCount > 0 && logMINOR)
    		Logger.minor(this, "Executed "+cbCount+" callbacks");
    }
    
    private PacketThrottle _lastThrottle;
    
    PacketThrottle getThrottle() {
    	// pn.getPeer() cannot be null as it has already connected.
    	PacketThrottle newThrottle = PacketThrottle.getThrottle(pn.getPeer(), Node.PACKET_SIZE);
    	PacketThrottle prevThrottle = null;
    	synchronized(this) {
    		if(newThrottle != _lastThrottle) {
    			prevThrottle = _lastThrottle;
    			_lastThrottle = newThrottle;
    		} else return newThrottle;
    	}
    	prevThrottle.maybeDisconnected();
    	return newThrottle;
	}

	/**
     * Called when we have received a packet acknowledgement.
     * @param realSeqNo
     */
    public void acknowledgedPacket(int realSeqNo) {
    	logMINOR = Logger.shouldLog(Logger.MINOR, this);
        AsyncMessageCallback[] callbacks;
        if(logMINOR) Logger.minor(this, "Acknowledged packet: "+realSeqNo);
	try {
		synchronized (this){
			removeAckRequest(realSeqNo);
		}
	} catch (UpdatableSortedLinkedListKilledException e) {
		// Ignore, we are processing an incoming packet
	}
	if(logMINOR) Logger.minor(this, "Removed ack request");
        callbacks = sentPacketsContents.getCallbacks(realSeqNo);
        byte[] buf = sentPacketsContents.get(realSeqNo);
        long timeAdded = sentPacketsContents.getTime(realSeqNo);
        if(sentPacketsContents.remove(realSeqNo)) {
        	if(buf.length > Node.PACKET_SIZE) {
        		PacketThrottle throttle = getThrottle();
        		throttle.notifyOfPacketAcknowledged();
        		throttle.setRoundTripTime(System.currentTimeMillis() - timeAdded);
        	}
        }
        if(callbacks != null) {
            for(int i=0;i<callbacks.length;i++)
                callbacks[i].acknowledged();
            if(logMINOR) Logger.minor(this, "Executed "+callbacks.length+" callbacks");
        }
    }

    /**
     * Remove an ack request from the queue by packet number.
     * @throws UpdatableSortedLinkedListKilledException 
     */
    private void removeAckRequest(int seqNo) throws UpdatableSortedLinkedListKilledException {
        QueuedAckRequest qr = (QueuedAckRequest)ackRequestQueue.removeByKey(new Integer(seqNo));
    	if(qr != null) qr.onAcked();
    	else
    		Logger.normal(this, "Removing ack request twice? Null on "+seqNo+" from "+pn.getPeer()+" ("+TimeUtil.formatTime((int) pn.averagePingTime(), 2, true)+" ping avg)");
    }

    /**
     * Resend (off-thread but ASAP) a packet.
     * @param seqNumber The serial number of the packet to be
     * resent.
     */
    public void resendPacket(int seqNumber) {
        byte[] resendData = sentPacketsContents.get(seqNumber);
        if(resendData != null) {
        	if(resendData.length > Node.PACKET_SIZE)
        		getThrottle().notifyOfPacketLost();
            synchronized(packetsToResend) {
                packetsToResend.add(new Integer(seqNumber));
            }
            pn.node.ps.wakeUp();
        } else {
        	synchronized(this) {
        		String msg = "Asking me to resend packet "+seqNumber+
        			" which we haven't sent yet or which they have already acked (next="+nextPacketNumber+ ')';
        		// Might just be late, but could indicate something serious.
        		if(isDeprecated) {
        			if(logMINOR)
        				Logger.minor(this, "Other side wants us to resend packet "+seqNumber+" for "+this+" - we cannot do this because we are deprecated");
        		} else {
        			Logger.normal(this, msg);
        		}
        	}
        }
    }

    /**
     * Called when we receive an AckRequest.
     * @param packetNumber The packet that the other side wants
     * us to re-ack.
     */
    public synchronized void receivedAckRequest(int packetNumber) {
        if(queuedAck(packetNumber)) {
            // Already going to send an ack
            // Don't speed it up though; wasteful
        } else {
            if(packetNumbersReceived.contains(packetNumber)) {
                // We have received it, so send them an ack
                queueAck(packetNumber);
            } else {
                // We have not received it, so get them to resend it
                try {
					queueResendRequest(packetNumber);
				} catch (UpdatableSortedLinkedListKilledException e) {
					// Ignore, we are decoding, not sending.
				}
                highestSeenIncomingSerialNumber = Math.max(highestSeenIncomingSerialNumber, packetNumber);
            }
        }
    }

    /**
     * Is there a queued ack with the given packet number?
     * FIXME: have a hashtable? The others do, but probably it
     * isn't necessary. We should be consistent about this -
     * either take it out of UpdatableSortedLinkedListWithForeignIndex,
     * or add one here.
     */
    private boolean queuedAck(int packetNumber) {
        synchronized(ackQueue) {
            for(Enumeration e=ackQueue.elements();e.hasMoreElements();) {
                QueuedAck qa = (QueuedAck) e.nextElement();
                if(qa.packetNumber == packetNumber) return true;
            }
        }
        return false;
    }

    /**
     * Destination forgot a packet.
     * This is normal if we are the secondary key.
     * @param seqNumber The packet number lost.
     */
    public synchronized void destForgotPacket(int seqNumber) {
        if(isDeprecated) {
            Logger.normal(this, "Destination forgot packet: "+seqNumber);
        } else {
            Logger.error(this, "Destination forgot packet: "+seqNumber);
        }
        try {
			removeResendRequest(seqNumber);
		} catch (UpdatableSortedLinkedListKilledException e) {
			// Ignore
		}
    }

    /**
     * @return A packet number for a new outgoing packet.
     * This method will block until one is available if
     * necessary.
     * @throws KeyChangedException if the thread is interrupted when waiting
     */
    public int allocateOutgoingPacketNumber() throws KeyChangedException, NotConnectedException {
        int packetNumber;
        if(!pn.isConnected()) throw new NotConnectedException();
        synchronized(this) {
            if(isDeprecated) throw new KeyChangedException();
            packetNumber = nextPacketNumber++;
            if(logMINOR) Logger.minor(this, "Allocated "+packetNumber+" in allocateOutgoingPacketNumber for "+this);
        }
        while(true) {
            try {
                sentPacketsContents.lock(packetNumber);
                return packetNumber;
            } catch (InterruptedException e) {
            	synchronized(this) {
            		if(isDeprecated) throw new KeyChangedException();
            	}
            }
        }
    }

    /**
     * @return A packet number for a new outgoing packet.
     * This method will not block, and will throw an exception
     * if it would need to block.
     * @throws KeyChangedException if the thread is interrupted when waiting
     */
    public int allocateOutgoingPacketNumberNeverBlock() throws KeyChangedException, NotConnectedException, WouldBlockException {
        int packetNumber;
        if(!pn.isConnected()) throw new NotConnectedException();
        synchronized(this) {
            packetNumber = nextPacketNumber;
            if(isDeprecated) throw new KeyChangedException();
            sentPacketsContents.lockNeverBlock(packetNumber);
            nextPacketNumber = packetNumber+1;
            if(logMINOR) Logger.minor(this, "Allocated "+packetNumber+" in allocateOutgoingPacketNumberNeverBlock for "+this);
            return packetNumber;
        }
    }

    public int[] grabForgotten() {
    	if(logMINOR) Logger.minor(this, "Grabbing forgotten packet numbers");
        int[] acks;
        synchronized(forgottenQueue) {
            // Grab the acks and tell them they are sent
            int length = forgottenQueue.size();
            acks = new int[length];
            int i=0;
            for(Enumeration e=forgottenQueue.elements();e.hasMoreElements();) {
                QueuedForgotten ack = (QueuedForgotten)e.nextElement();
                acks[i++] = ack.packetNumber;
                if(logMINOR) Logger.minor(this, "Grabbing ack "+ack.packetNumber+" from "+this);
                ack.sent();
            }
        }
        return acks;
    }

	public void requeueForgot(int[] forgotPackets, int start, int length) {
		synchronized(forgottenQueue) { // It doesn't do anything else does it? REDFLAG
			for(int i=start;i<start+length;i++) {
				queueForgotten(i, false);
			}
		}
	}

    
    /**
     * Grab all the currently queued acks to be sent to this node.
     * @return An array of packet numbers that we need to acknowledge.
     */
    public int[] grabAcks() {
    	if(logMINOR) Logger.minor(this, "Grabbing acks");
        int[] acks;
        synchronized(ackQueue) {
            // Grab the acks and tell them they are sent
            int length = ackQueue.size();
            acks = new int[length];
            int i=0;
            for(Enumeration e=ackQueue.elements();e.hasMoreElements();) {
                QueuedAck ack = (QueuedAck)e.nextElement();
                acks[i++] = ack.packetNumber;
                if(logMINOR) Logger.minor(this, "Grabbing ack "+ack.packetNumber+" from "+this);
                ack.sent();
            }
        }
        return acks;
    }

    /**
     * Grab all the currently queued resend requests.
     * @return An array of the packet numbers of all the packets we want to be resent.
     * @throws NotConnectedException If the peer is no longer connected.
     */
    public int[] grabResendRequests() throws NotConnectedException {
        UpdatableSortedLinkedListItem[] items;
        int[] packetNumbers;
        int realLength;
        long now = System.currentTimeMillis();
        try {
        synchronized(resendRequestQueue) {
            items = resendRequestQueue.toArray();
            int length = items.length;
            packetNumbers = new int[length];
            realLength = 0;
            for(int i=0;i<length;i++) {
                QueuedResendRequest qrr = (QueuedResendRequest)items[i];
                if(packetNumbersReceived.contains(qrr.packetNumber)) {
                	if(logMINOR) Logger.minor(this, "Have already seen "+qrr.packetNumber+", removing from resend list");
                	resendRequestQueue.remove(qrr);
                	continue;
                }
                if(qrr.activeTime <= now) {
                    packetNumbers[realLength++] = qrr.packetNumber;
                    if(logMINOR) Logger.minor(this, "Grabbing resend request: "+qrr.packetNumber+" from "+this);
                    qrr.sent();
                } else {
                	if(logMINOR) Logger.minor(this, "Rejecting resend request: "+qrr.packetNumber+" - in future by "+(qrr.activeTime-now)+"ms for "+this);
                }
            }
        }
        } catch (UpdatableSortedLinkedListKilledException e) {
        	throw new NotConnectedException();
        }
        int[] trimmedPacketNumbers = new int[realLength];
        System.arraycopy(packetNumbers, 0, trimmedPacketNumbers, 0, realLength);
        return trimmedPacketNumbers;
    }

    public int[] grabAckRequests() throws NotConnectedException {
        UpdatableSortedLinkedListItem[] items;
        int[] packetNumbers;
        int realLength;
        if(logMINOR) Logger.minor(this, "Grabbing ack requests");
        try {
        synchronized(ackRequestQueue) {
            long now = System.currentTimeMillis();
            items = ackRequestQueue.toArray();
            int length = items.length;
            packetNumbers = new int[length];
            realLength = 0;
            for(int i=0;i<length;i++) {
                QueuedAckRequest qr = (QueuedAckRequest)items[i];
                int packetNumber = qr.packetNumber;
                if(qr.activeTime <= now) {
                    if(sentPacketsContents.get(packetNumber) == null) {
                    	if(logMINOR) Logger.minor(this, "Asking to ack packet which has already been acked: "+packetNumber+" on "+this+".grabAckRequests");
                        ackRequestQueue.remove(qr);
                        continue;
                    }
                    packetNumbers[realLength++] = packetNumber;
                    if(logMINOR) Logger.minor(this, "Grabbing ack request "+packetNumber+" ("+realLength+") from "+this);
                    qr.sent();
                } else {
                	if(logMINOR) Logger.minor(this, "Ignoring ack request "+packetNumber+" ("+realLength+") - will become active in "+(qr.activeTime-now)+"ms on "+this+" - "+qr);
                }
            }
        }
        } catch (UpdatableSortedLinkedListKilledException e) {
        	throw new NotConnectedException();
        }
        if(logMINOR) Logger.minor(this, "realLength now "+realLength);
        int[] trimmedPacketNumbers = new int[realLength];
        System.arraycopy(packetNumbers, 0, trimmedPacketNumbers, 0, realLength);
        if(logMINOR) Logger.minor(this, "Returning "+trimmedPacketNumbers.length+" ackRequests");
        return trimmedPacketNumbers;
    }

    /**
     * @return The time at which we will have to send some
     * notifications. Or Long.MAX_VALUE if there are none to send.
     */
    public long getNextUrgentTime() {
        long earliestTime = Long.MAX_VALUE;
        synchronized(ackQueue) {
            if(!ackQueue.isEmpty()) {
                QueuedAck qa = (QueuedAck)ackQueue.head();
                earliestTime = qa.urgentTime;
            }
        }
        synchronized(resendRequestQueue) {
            if(!resendRequestQueue.isEmpty()) {
                QueuedResendRequest qr = (QueuedResendRequest) resendRequestQueue.getLowest();
                earliestTime = Math.min(earliestTime, qr.urgentTime);
            }
        }
        synchronized(ackRequestQueue) {
            if(!ackRequestQueue.isEmpty()) {
                QueuedAckRequest qr = (QueuedAckRequest) ackRequestQueue.getLowest();
                earliestTime = Math.min(earliestTime, qr.urgentTime);
            }
        }
        return earliestTime;
    }

    /**
     * @return The last sent new packet number.
     */
    public int getLastOutgoingSeqNumber() {
        synchronized(this) {
            return nextPacketNumber-1;
        }
    }

    /**
     * Report a packet has been sent
     * @param data The data we have just sent (payload only, decrypted). 
     * @param seqNumber The packet number.
     * @throws NotConnectedException 
     */
    public void sentPacket(byte[] data, int seqNumber, AsyncMessageCallback[] callbacks, short priority) throws NotConnectedException {
        if(callbacks != null) {
            for(int i=0;i<callbacks.length;i++) {
                if(callbacks[i] == null)
                    throw new NullPointerException();
            }
        }
        sentPacketsContents.add(seqNumber, data, callbacks, priority);
        try {
			queueAckRequest(seqNumber);
		} catch (UpdatableSortedLinkedListKilledException e) {
			throw new NotConnectedException();
		}
    }

    /**
     * Clear the KeyTracker. Deprecate it, clear all resend, ack, request-ack etc queues.
     * Return the messages we still had in flight. The caller will then either add them to
     * another KeyTracker, or call their callbacks to indicate failure.
     */
    private LimitedRangeIntByteArrayMapElement[] clear() {
    	if(logMINOR) Logger.minor(this, "Clearing "+this);
        isDeprecated = true;
        LimitedRangeIntByteArrayMapElement[] elements;
        synchronized(sentPacketsContents) {
            elements = sentPacketsContents.grabAll(); // will clear
        }
        synchronized(ackQueue) {
            ackQueue.clear();
        }
        resendRequestQueue.kill();
        ackRequestQueue.kill();
        synchronized(packetsToResend) {
            packetsToResend.clear();
        }
    	packetNumbersReceived.clear();
    	return elements;
    }

    /**
     * Completely deprecate the KeyTracker, in favour of a new one. 
     * It will no longer be used for anything. The KeyTracker will be cleared and all outstanding packets
     * moved to the new KeyTracker.
     * 
     * *** Must only be called if the KeyTracker is not to be kept. Otherwise, we may receive some packets twice. ***
     */
    public void completelyDeprecated(KeyTracker newTracker) {
    	if(_lastThrottle != null)
    		_lastThrottle.maybeDisconnected();
    	if(logMINOR) Logger.minor(this, "Completely deprecated: "+this+" in favour of "+newTracker);
    	LimitedRangeIntByteArrayMapElement[] elements = clear();
    	if(elements.length == 0) return; // nothing more to do
        MessageItem[] messages = new MessageItem[elements.length];
        for(int i=0;i<elements.length;i++) {
            LimitedRangeIntByteArrayMapElement element = elements[i];
            byte[] buf = element.data;
            AsyncMessageCallback[] callbacks = element.callbacks;
            // Ignore packet#
            if(logMINOR) Logger.minor(this, "Queueing resend of what was once "+element.packetNumber);
            messages[i] = new MessageItem(buf, callbacks, true, 0, pn.resendByteCounter, element.priority);
        }
        pn.requeueMessageItems(messages, 0, messages.length, true);
        
        pn.node.ps.wakeUp();
    }

    /**
     * Called when the node appears to have been disconnected.
     * Dump all sent messages.
     */
    public void disconnected() {
    	if(_lastThrottle != null)
    		_lastThrottle.maybeDisconnected();
        // Clear everything, call the callbacks
    	LimitedRangeIntByteArrayMapElement[] elements = clear();
        for(int i=0;i<elements.length;i++) {
            LimitedRangeIntByteArrayMapElement element = elements[i];
            AsyncMessageCallback[] callbacks = element.callbacks;
            if(callbacks != null) {
                for(int j=0;j<callbacks.length;j++)
                    callbacks[j].disconnected();
            }
        }
    }

    /**
     * Fill rpiTemp with ResendPacketItems of packets that need to be
     * resent.
     * @return An array of integers which contains the packet numbers
     * to be resent (the RPI's are put into rpiTemp), or null if there
     * are no packets to resend.
     * 
     * Not a very nice API, but it saves a load of allocations, and at
     * least it's documented!
     */
    public int[] grabResendPackets(Vector rpiTemp, int[] numbers) {
    	rpiTemp.clear();
        long now = System.currentTimeMillis();
        long fourRTTs = twoRTTs();
        int count=0;
        synchronized(packetsToResend) {
            int len = packetsToResend.size();
            if(numbers.length < len)
            	numbers = new int[len * 2];
            for(Iterator it=packetsToResend.iterator();it.hasNext();) {
                int packetNo = ((Integer)it.next()).intValue();
                long resentTime = sentPacketsContents.getReaddedTime(packetNo);
                if(now - resentTime > fourRTTs) {
                	// Either never resent, or resent at least 4 RTTs ago
                	numbers[count++] = packetNo;
                	it.remove();
                }
            }
            packetsToResend.clear();
        }
        for(int i=0;i<count;i++) {
            int packetNo = numbers[i];
            byte[] buf = sentPacketsContents.get(packetNo);
            if(buf == null) {
            	if(logMINOR) Logger.minor(this, "Contents null for "+packetNo+" in grabResendPackets on "+this);
                continue; // acked already?
            }
            AsyncMessageCallback[] callbacks = sentPacketsContents.getCallbacks(packetNo);
            short priority = sentPacketsContents.getPriority(packetNo, DMT.PRIORITY_BULK_DATA);
            rpiTemp.add(new ResendPacketItem(buf, packetNo, this, callbacks, priority));
        }
        if(rpiTemp.isEmpty()) return null;
        return numbers;
    }

	public boolean isDeprecated() {
		return this.isDeprecated;
	}

	public int countAckRequests() {
		return ackRequestQueue.size();
	}

	public int countResendRequests() {
		synchronized(resendRequestQueue) {
			return resendRequestQueue.size();
		}
	}

	public int countAcks() {
		synchronized(ackQueue) {
			return ackQueue.size();
		}
	}

}
