/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import freenet.crypt.DSAPublicKey;
import freenet.io.comm.AsyncMessageCallback;
import freenet.io.comm.ByteCounter;
import freenet.io.comm.DMT;
import freenet.io.comm.Message;
import freenet.io.comm.NotConnectedException;
import freenet.io.comm.PeerParseException;
import freenet.io.comm.ReferenceSignatureVerificationException;
import freenet.io.xfer.BlockTransmitter;
import freenet.io.xfer.PartiallyReceivedBlock;
import freenet.keys.CHKBlock;
import freenet.keys.Key;
import freenet.keys.KeyBlock;
import freenet.keys.NodeCHK;
import freenet.keys.NodeSSK;
import freenet.keys.SSKBlock;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;
import freenet.support.TimeUtil;
import freenet.support.io.NativeThread;

/**
 * Handle an incoming request. Does not do the actual fetching; that
 * is separated off into RequestSender so we get transfer coalescing
 * and both ends for free. 
 */
public class RequestHandler implements PrioRunnable, ByteCounter, RequestSender.Listener {

	private static boolean logMINOR;
    final Message req;
    final Node node;
    final long uid;
    private short htl;
    final PeerNode source;
    private boolean needsPubKey;
    final Key key;
    private boolean finalTransferFailed = false;
    /** The RequestSender, if any */
    private RequestSender rs;
    private int status = RequestSender.NOT_FINISHED;
	private boolean appliedByteCounts=false;
	private boolean sentRejectedOverload = false;
	private long searchStartTime;
	private long responseDeadline;
	private BlockTransmitter bt;	
	
    public String toString() {
        return super.toString()+" for "+uid;
    }
    
    public RequestHandler(Message m, PeerNode source, long id, Node n, short htl, Key key) {
        req = m;
        node = n;
        uid = id;
        this.source = source;
        this.htl = htl;
        if(htl <= 0) htl = 1;
        this.key = key;
        if(key instanceof NodeSSK)
        	needsPubKey = m.getBoolean(DMT.NEED_PUB_KEY);
        logMINOR = Logger.shouldLog(Logger.MINOR, this);
        receivedBytes(m.receivedByteCount());
    }

    public void run() {
	    freenet.support.Logger.OSThread.logPID(this);
        try {
        	realRun();
			//The last thing that realRun() does is register as a request-sender listener, so any exception here is the end.
        } catch (NotConnectedException e) {
        	Logger.normal(this, "requestor gone, could not start request handler wait");
			node.removeTransferringRequestHandler(uid);
            node.unlockUID(uid, key instanceof NodeSSK, false, false, false);
        } catch (Throwable t) {
            Logger.error(this, "Caught "+t, t);
			node.removeTransferringRequestHandler(uid);
            node.unlockUID(uid, key instanceof NodeSSK, false, false, false);
        }
    }
    
	private Exception previousApplyByteCountCall;
	
    private void applyByteCounts() {
		if (appliedByteCounts) {
			Logger.error(this, "applyByteCounts already called", new Exception("error"));
			Logger.error(this, "first called here", previousApplyByteCountCall);
			return;
		}
		previousApplyByteCountCall=new Exception("first call to applyByteCounts");
		appliedByteCounts=true;
        if((!finalTransferFailed) && rs != null && status != RequestSender.TIMED_OUT && status != RequestSender.GENERATED_REJECTED_OVERLOAD 
           && status != RequestSender.INTERNAL_ERROR) {
            	int sent, rcvd;
            	synchronized(bytesSync) {
            		sent = sentBytes;
            		rcvd = receivedBytes;
            	}
            	sent += rs.getTotalSentBytes();
            	rcvd += rs.getTotalReceivedBytes();
            	if(key instanceof NodeSSK) {
            		if(logMINOR) Logger.minor(this, "Remote SSK fetch cost "+sent+ '/' +rcvd+" bytes ("+status+ ')');
                	node.nodeStats.remoteSskFetchBytesSentAverage.report(sent);
                	node.nodeStats.remoteSskFetchBytesReceivedAverage.report(rcvd);
                	if(status == RequestSender.SUCCESS) {
                		// Can report both parts, because we had both a Handler and a Sender
                		node.nodeStats.successfulSskFetchBytesSentAverage.report(sent);
                		node.nodeStats.successfulSskFetchBytesReceivedAverage.report(rcvd);
                        node.sentPayload(rs.getSSKData().length); // won't be sentPayload()ed by BlockTransmitter
                        sentPayload(rs.getSSKData().length);
                	}
            	} else {
            		if(logMINOR) Logger.minor(this, "Remote CHK fetch cost "+sent+ '/' +rcvd+" bytes ("+status+ ')');
                	node.nodeStats.remoteChkFetchBytesSentAverage.report(sent);
                	node.nodeStats.remoteChkFetchBytesReceivedAverage.report(rcvd);
                	if(status == RequestSender.SUCCESS) {
                		// Can report both parts, because we had both a Handler and a Sender
                		node.nodeStats.successfulChkFetchBytesSentAverage.report(sent);
                		node.nodeStats.successfulChkFetchBytesReceivedAverage.report(rcvd);
                	}
            	}
        }
    }

    private void realRun() throws NotConnectedException {
        if(logMINOR) Logger.minor(this, "Handling a request: "+uid);
        
        Message accepted = DMT.createFNPAccepted(uid);
        source.sendAsync(accepted, null, 0, this);
        
        Object o = node.makeRequestSender(key, htl, uid, source, false, true, false, false, this);
        if(o instanceof KeyBlock) {
        	returnLocalData((KeyBlock)o);
            return;
        }
        rs = (RequestSender) o;
        
        if(rs == null) { // ran out of htl?
            Message dnf = DMT.createFNPDataNotFound(uid);
            status = RequestSender.DATA_NOT_FOUND; // for byte logging
            node.failureTable.onFinalFailure(key, null, htl, FailureTable.REJECT_TIME, source);
            sendTerminal(dnf);
            return;
        }
        
        //If we cannot respond before this time, the 'source' node has already fatally timed out (and we need not return packets which will not be claimed)
		searchStartTime = System.currentTimeMillis();
		responseDeadline = searchStartTime + RequestSender.FETCH_TIMEOUT + source.getProbableSendQueueTime();
	}
		
	public void onReceivedRejectOverload() {
		try {
            if(!sentRejectedOverload) {
            	// Forward RejectedOverload
				//Note: This message is only decernable from the terminal messages by the IS_LOCAL flag being false. (!IS_LOCAL)->!Terminal
            	Message msg = DMT.createFNPRejectedOverload(uid, false);
            	source.sendAsync(msg, null, 0, this);
				//If the status changes (e.g. to SUCCESS), there is little need to send yet another reject overload.
				sentRejectedOverload=true;
            }
		} catch (NotConnectedException e) {
			Logger.normal(this, "requestor is gone, can't forward reject overload");
		}
	}
	
	public void onCHKTransferBegins() {
		try {
            	// Is a CHK.
                Message df = DMT.createFNPCHKDataFound(uid, rs.getHeaders());
                source.sendAsync(df, null, 0, this);
                
                PartiallyReceivedBlock prb = rs.getPRB();
            	bt =
            	    new BlockTransmitter(node.usm, source, uid, prb, node.outputThrottle, this);
            	node.addTransferringRequestHandler(uid);
			bt.sendAsync(node.executor);
		} catch (NotConnectedException e) {
			Logger.normal(this, "requestor is gone, can't begin CHK transfer");
		}
	}
	
	private void waitAndFinishCHKTransferOffThread() {
		node.executor.execute(new Runnable() {
			public void run() {
				try {
					waitAndFinishCHKTransfer();
				} catch (NotConnectedException e) {
					//for byte logging, since the block is the 'terminal' message.
					applyByteCounts();
					unregisterRequestHandlerWithNode();
				}
			}
		}, "Finish CHK transfer for "+key);
	}
	
	private void waitAndFinishCHKTransfer() throws NotConnectedException {
		if (logMINOR) Logger.minor(this, "Waiting for CHK transfer to finish");
		if(bt.getAsyncExitStatus()) {
			status = rs.getStatus();
			// Successful CHK transfer, maybe path fold
			finishOpennetChecked();
		} else {
			finalTransferFailed = true;
			status = rs.getStatus();
			//for byte logging, since the block is the 'terminal' message.
			applyByteCounts();
			unregisterRequestHandlerWithNode();
		}
	}
	
	public void onRequestSenderFinished(int status) {
		long now = System.currentTimeMillis();
		this.status=status;
		
		if (now > responseDeadline) {
			// Offer the data if there is any.
			node.failureTable.onFinalFailure(key, null, htl, -1, source);
			PeerNode routedLast = rs == null ? null : rs.routedLast();
			Logger.error(this, "requestsender took too long to respond to requestor ("+TimeUtil.formatTime((now - searchStartTime), 2, true)+"/"+(rs == null ? "null" : rs.getStatusString())+") routed to "+(routedLast == null ? "null" : routedLast.shortToString())); 
			applyByteCounts();
			unregisterRequestHandlerWithNode();
			return;
		}
	
		if(status == RequestSender.NOT_FINISHED)
			Logger.error(this, "onFinished() but not finished?");
            
		try {
            switch(status) {
            	case RequestSender.NOT_FINISHED:
            	case RequestSender.DATA_NOT_FOUND:
                    Message dnf = DMT.createFNPDataNotFound(uid);
            		sendTerminal(dnf);
            		return;
            	case RequestSender.RECENTLY_FAILED:
            		Message rf = DMT.createFNPRecentlyFailed(uid, rs.getRecentlyFailedTimeLeft());
            		sendTerminal(rf);
            		return;
            	case RequestSender.GENERATED_REJECTED_OVERLOAD:
            	case RequestSender.TIMED_OUT:
            	case RequestSender.INTERNAL_ERROR:
            		// Locally generated.
            	    // Propagate back to source who needs to reduce send rate
            	    Message reject = DMT.createFNPRejectedOverload(uid, true);
            		sendTerminal(reject);
            		return;
            	case RequestSender.ROUTE_NOT_FOUND:
            	    // Tell source
            	    Message rnf = DMT.createFNPRouteNotFound(uid, rs.getHTL());
            		sendTerminal(rnf);
            		return;
            	case RequestSender.SUCCESS:
            		if(key instanceof NodeSSK) {
            			// SUCCESS requires that BOTH the pubkey AND the data/headers have been received.
            			// The pubKey will have been set on the SSK key, and the SSKBlock will have been constructed.
            			Message df = DMT.createFNPSSKDataFound(uid, rs.getHeaders(), rs.getSSKData());
            			if(needsPubKey) {
            				source.sendAsync(df, null, 0, this);
            				Message pk = DMT.createFNPSSKPubKey(uid, ((NodeSSK)rs.getSSKBlock().getKey()).getPubKey());
            				sendTerminal(pk);
            			} else {
            				sendTerminal(df);
            			}
            		} else {
            			if(bt == null) {
            				// Bug! This is impossible!
            				Logger.error(this, "Status is SUCCESS but we never started a transfer on "+uid);
            				// Obviously this node is confused, send a terminal reject to make sure the requestor is not waiting forever.
                    	    reject = DMT.createFNPRejectedOverload(uid, true);
                    		sendTerminal(reject);
            			} else {
            				waitAndFinishCHKTransferOffThread();
            			}
            		}
					return;
            	case RequestSender.VERIFY_FAILURE:
            	case RequestSender.GET_OFFER_VERIFY_FAILURE:
            		if(key instanceof NodeCHK) {
						if(bt == null) {
            				// Bug! This is impossible!
            				Logger.error(this, "Status is VERIFY_FAILURE but we never started a transfer on "+uid);
							// Obviously this node is confused, send a terminal reject to make sure the requestor is not waiting forever.
                    	    reject = DMT.createFNPRejectedOverload(uid, true);
                    		sendTerminal(reject);
            			} else {
							//Verify fails after receive() is complete, so we might as well propagate it...
            				waitAndFinishCHKTransferOffThread();
            			}
						return;
            		}
            	    reject = DMT.createFNPRejectedOverload(uid, true);
            		sendTerminal(reject);
            		return;
            	case RequestSender.TRANSFER_FAILED:
            	case RequestSender.GET_OFFER_TRANSFER_FAILED:
            		if(key instanceof NodeCHK) {
            			if(bt == null) {
            				// Bug! This is impossible!
            				Logger.error(this, "Status is TRANSFER_FAILED but we never started a transfer on "+uid);
							// Obviously this node is confused, send a terminal reject to make sure the requestor is not waiting forever.
                    	    reject = DMT.createFNPRejectedOverload(uid, true);
                    		sendTerminal(reject);
            			} else {
            				waitAndFinishCHKTransferOffThread();
            			}
						return;
            		}
            		Logger.error(this, "finish(TRANSFER_FAILED) should not be called on SSK?!?!", new Exception("error"));
            		return;
            	default:
            		// Treat as internal error
            	    reject = DMT.createFNPRejectedOverload(uid, true);
            		sendTerminal(reject);
            	    throw new IllegalStateException("Unknown status code "+status);
            }
		} catch (NotConnectedException e) {
			Logger.normal(this, "requestor is gone, can't send terminal message");
			applyByteCounts();
			unregisterRequestHandlerWithNode();
		}
	}

    /**
     * Return data from the datastore.
     * @param block The block we found in the datastore.
     * @throws NotConnectedException If we lose the connected to the request source.
     */
    private void returnLocalData(KeyBlock block) throws NotConnectedException {
        Message df = createDataFound(block);
        if(key instanceof NodeSSK) {
            if(needsPubKey) {
            	source.sendAsync(df, null, 0, this);
            	DSAPublicKey key = ((NodeSSK)block.getKey()).getPubKey();
            	Message pk = DMT.createFNPSSKPubKey(uid, key);
            	if(logMINOR) Logger.minor(this, "Sending PK: "+key+ ' ' +key.toLongString());
            	sendTerminal(pk);
            } else {
            	sendTerminal(df);
            }
            status = RequestSender.SUCCESS; // for byte logging
        	// Sent from datastore.
            node.sentPayload(block.getRawData().length); // won't be sentPayload()ed by BlockTransmitter
            sentPayload(block.getRawData().length);
        } else if(block instanceof CHKBlock) {
        	PartiallyReceivedBlock prb =
        		new PartiallyReceivedBlock(Node.PACKETS_IN_BLOCK, Node.PACKET_SIZE, block.getRawData());
        	BlockTransmitter bt =
        		new BlockTransmitter(node.usm, source, uid, prb, node.outputThrottle, this);
        	node.addTransferringRequestHandler(uid);
			source.sendAsync(df, null, 0, this);
        	if(bt.send(node.executor)) {
                // for byte logging
        		status = RequestSender.SUCCESS;
        		// We've fetched it from our datastore, so there won't be a downstream noderef.
        		// But we want to send at least an FNPOpennetCompletedAck, otherwise the request source
        		// may have to timeout waiting for one. That will be the terminal message.
       			finishOpennetNoRelay();
			} else {
                //also for byte logging, since the block is the 'terminal' message.
                applyByteCounts();
				unregisterRequestHandlerWithNode();
        	}
        } else throw new IllegalStateException();
	}

	private void unregisterRequestHandlerWithNode() {
		node.removeTransferringRequestHandler(uid);
		node.unlockUID(uid, key instanceof NodeSSK, false, false, false);
	}
	
	/**
     * Sends the 'final' packet of a request in such a way that the thread can be freed (made non-runnable/exit)
     * and the byte counter will still be accurate.
     */
    private void sendTerminal(Message msg) throws NotConnectedException {
    	if(logMINOR)
    		Logger.minor(this, "sendTerminal("+msg+")", new Exception("debug"));
        if (sendTerminalCalled)
            throw new IllegalStateException("sendTerminal should only be called once");
        else
            sendTerminalCalled=true;
        
        source.sendAsync(msg, new TerminalMessageByteCountCollector(), 0, this);
    }
    
    boolean sendTerminalCalled=false;
    
    /**
     * Note well! These functions are not executed on the RequestHandler thread.
     */
    private class TerminalMessageByteCountCollector implements AsyncMessageCallback {
        
    	private boolean completed = false;
    	
		public void acknowledged() {
            //terminalMessage ack'd by remote peer
			complete();
		}
        
		public void disconnected() {
            Logger.minor(this, "Peer disconnected before terminal message sent for "+RequestHandler.this);
            complete();
		}
        
		public void fatalError() {
			Logger.error(this, "Error sending terminal message?! for " + RequestHandler.this);
			complete();
		}
        
		public void sent() {
			complete();
        }
		
		private void complete() {
			synchronized(this) {
				if(completed) return;
				completed = true;
			}
            //For byte counting, this relies on the fact that the callback will only be excuted once.
			applyByteCounts();
			unregisterRequestHandlerWithNode();
		}
	}
    
    /**
     * Either send an ack, indicating we've finished and aren't interested in opennet, 
     * or wait for a noderef and relay it and wait for a response and relay that,
     * or send our own noderef and wait for a response and add that.
     */
	private void finishOpennetChecked() throws NotConnectedException {
		OpennetManager om = node.getOpennet();
		if(om != null &&
				(node.passOpennetRefsThroughDarknet() || source.isOpennet()) &&
		   finishOpennetInner(om)) {
			applyByteCounts();
			unregisterRequestHandlerWithNode();
			return;
		}
		
		Message msg = DMT.createFNPOpennetCompletedAck(uid);
		sendTerminal(msg);
	}
	
	/**
	 * There is no noderef to pass downstream. If we want a connection, send our 
	 * noderef and wait for a reply, otherwise just send an ack.
	 */
	private void finishOpennetNoRelay() throws NotConnectedException {
		OpennetManager om = node.getOpennet();
		
		if(om != null && (source.isOpennet() || node.passOpennetRefsThroughDarknet()) &&
		   finishOpennetNoRelayInner(om)) {
			applyByteCounts();
			unregisterRequestHandlerWithNode();
			return;
		}
		
		// Otherwise just ack it.
		Message msg = DMT.createFNPOpennetCompletedAck(uid);
		sendTerminal(msg);
	}
	
	private boolean finishOpennetInner(OpennetManager om) {
		byte[] noderef = rs.waitForOpennetNoderef();
		if(noderef == null) {
			return finishOpennetNoRelayInner(om);
		}
		
		if(node.random.nextInt(OpennetManager.RESET_PATH_FOLDING_PROB) == 0) {
			return finishOpennetNoRelayInner(om);
		}
		
    	finishOpennetRelay(noderef, om);
    	return true;
    }

	/**
	 * Send our noderef to the request source, wait for a reply, if we get one add it. Called when either the request
	 * wasn't routed, or the node it was routed to didn't return a noderef.
	 * @return True if success, or lost connection; false if we need to send an ack.
	 */
    private boolean finishOpennetNoRelayInner(OpennetManager om) {
    	if(logMINOR)
    		Logger.minor(this, "Finishing opennet: sending own reference");
		if(!om.wantPeer(null, false)) return false; // Don't want a reference
		
		try {
			om.sendOpennetRef(false, uid, source, om.crypto.myCompressedFullRef(), this);
		} catch (NotConnectedException e) {
			Logger.normal(this, "Can't send opennet ref because node disconnected on "+this);
			// Oh well...
			return true;
		}
		
		// Wait for response
		
		byte[] noderef = 
			om.waitForOpennetNoderef(true, source, uid, this);
		
		if(noderef == null)
			return false;
		
		SimpleFieldSet ref = om.validateNoderef(noderef, 0, noderef.length, source, false);
		
		if(ref == null) 
			return false;
		
	    try {
			if(node.addNewOpennetNode(ref) == null) {
				Logger.normal(this, "Asked for opennet ref but didn't want it for "+this+" :\n"+ref);
			} else {
				Logger.normal(this, "Added opennet noderef in "+this);
			}
		} catch (FSParseException e) {
			Logger.error(this, "Could not parse opennet noderef for "+this+" from "+source, e);
		} catch (PeerParseException e) {
			Logger.error(this, "Could not parse opennet noderef for "+this+" from "+source, e);
		} catch (ReferenceSignatureVerificationException e) {
			Logger.error(this, "Bad signature on opennet noderef for "+this+" from "+source+" : "+e, e);
		}
		return true;
    }    

    /**
     * Called when the node we routed the request to returned a valid noderef, and we don't want it.
     * So we relay it downstream to somebody who does, and wait to relay the response back upstream.
     * @param noderef
     * @param om
     */
	private void finishOpennetRelay(byte[] noderef, OpennetManager om) {
    	if(logMINOR)
    		Logger.minor(this, "Finishing opennet: relaying reference from "+rs.successFrom());
		// Send it back to the handler, then wait for the ConnectReply
		PeerNode dataSource = rs.successFrom();
		
		try {
			om.sendOpennetRef(false, uid, source, noderef, this);
		} catch (NotConnectedException e) {
			// Lost contact with request source, nothing we can do
			return;
		}
		
		// Now wait for reply from the request source.
		
		byte[] newNoderef = om.waitForOpennetNoderef(true, source, uid, this);
		
		if(newNoderef == null) {
			// Already sent a ref, no way to tell upstream that we didn't receive one. :(
			return;
		}
		
		// Send it forward to the data source, if it is valid.
		
		if(om.validateNoderef(newNoderef, 0, newNoderef.length, source, false) != null) {
			try {
				om.sendOpennetRef(true, uid, dataSource, newNoderef, this);
			} catch (NotConnectedException e) {
				// How sad
				return;
			}
		}
	}

	private Message createDataFound(KeyBlock block) {
		if(block instanceof CHKBlock)
			return DMT.createFNPCHKDataFound(uid, block.getRawHeaders());
		else if(block instanceof SSKBlock) {
            // FIXME called before payload is actually sent
			node.sentPayload(block.getRawData().length); // won't be sentPayload()ed by BlockTransmitter
			return DMT.createFNPSSKDataFound(uid, block.getRawHeaders(), block.getRawData());
		} else
			throw new IllegalStateException("Unknown key block type: "+block.getClass());
	}

	private int sentBytes;
	private int receivedBytes;
	private volatile Object bytesSync = new Object();
	
	public void sentBytes(int x) {
		synchronized(bytesSync) {
			sentBytes += x;
		}
		node.nodeStats.requestSentBytes(key instanceof NodeSSK, x);
	}

	public void receivedBytes(int x) {
		synchronized(bytesSync) {
			receivedBytes += x;
		}
		node.nodeStats.requestReceivedBytes(key instanceof NodeSSK, x);
	}

	public void sentPayload(int x) {
		node.sentPayload(x);
		node.nodeStats.requestSentBytes(key instanceof NodeSSK, -x);
	}

	public int getPriority() {
		return NativeThread.HIGH_PRIORITY;
	}
    
}
