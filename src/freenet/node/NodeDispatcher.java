/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Vector;

import freenet.io.comm.DMT;
import freenet.io.comm.Dispatcher;
import freenet.io.comm.Message;
import freenet.io.comm.MessageType;
import freenet.io.comm.NotConnectedException;
import freenet.io.comm.Peer;
import freenet.support.Fields;
import freenet.support.LRUHashtable;
import freenet.support.LRUQueue;
import freenet.support.Logger;
import freenet.support.ShortBuffer;
import freenet.support.StringArray;
import freenet.support.WeakHashSet;

/**
 * @author amphibian
 * 
 * Dispatcher for unmatched FNP messages.
 * 
 * What can we get?
 * 
 * SwapRequests
 * 
 * DataRequests
 * 
 * InsertRequests
 * 
 * Probably a few others; those are the important bits.
 */
public class NodeDispatcher implements Dispatcher {

	private static boolean logMINOR;
	final Node node;
	private NodeStats nodeStats;

	NodeDispatcher(Node node) {
		this.node = node;
		this.nodeStats = node.nodeStats;
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
	}

	public boolean handleMessage(Message m) {
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		PeerNode source = (PeerNode)m.getSource();
		if(logMINOR) Logger.minor(this, "Dispatching "+m+" from "+source);
		MessageType spec = m.getSpec();
		if(spec == DMT.FNPPing) {
			// Send an FNPPong
			Message reply = DMT.createFNPPong(m.getInt(DMT.PING_SEQNO));
			try {
				m.getSource().sendAsync(reply, null, 0, null); // nothing we can do if can't contact source
			} catch (NotConnectedException e) {
				if(logMINOR) Logger.minor(this, "Lost connection replying to "+m);
			}
			return true;
		}else if(spec == DMT.FNPLinkPing) {
			long id = m.getLong(DMT.PING_SEQNO);
			Message msg = DMT.createFNPLinkPong(id);
			try {
				source.sendAsync(msg, null, 0, null);
			} catch (NotConnectedException e) {
				// Ignore
			}
			return true;
		} else if(spec == DMT.FNPLinkPong) {
			long id = m.getLong(DMT.PING_SEQNO);
			source.receivedLinkPong(id);
			return true;
		} else if(spec == DMT.FNPDetectedIPAddress) {
			Peer p = (Peer) m.getObject(DMT.EXTERNAL_ADDRESS);
			source.setRemoteDetectedPeer(p);
			node.ipDetector.redetectAddress();
			return true;
		} else if(spec == DMT.FNPTime) {
			return handleTime(m, source);
		} else if(spec == DMT.FNPVoid) {
			return true;
		} else if(spec == DMT.FNPDisconnect) {
			handleDisconnect(m, source);
			return true;
		} else if(spec == DMT.nodeToNodeMessage) {
			node.receivedNodeToNodeMessage(m);
			return true;
		} else if(spec == DMT.UOMAnnounce) {
			return node.nodeUpdater.uom.handleAnnounce(m, source);
		} else if(spec == DMT.UOMRequestRevocation) {
			return node.nodeUpdater.uom.handleRequestRevocation(m, source);
		} else if(spec == DMT.UOMSendingRevocation) {
			return node.nodeUpdater.uom.handleSendingRevocation(m, source);
		} else if(spec == DMT.UOMRequestMain) {
			return node.nodeUpdater.uom.handleRequestMain(m, source);
		} else if(spec == DMT.UOMSendingMain) {
			return node.nodeUpdater.uom.handleSendingMain(m, source);
		}

		if(!source.isRoutable()) return false;

		if(spec == DMT.FNPLocChangeNotification) {
			double newLoc = m.getDouble(DMT.LOCATION);
			source.updateLocation(newLoc);
			return true;
		} else if(spec == DMT.FNPSwapRequest) {
			return node.lm.handleSwapRequest(m);
		} else if(spec == DMT.FNPSwapReply) {
			return node.lm.handleSwapReply(m);
		} else if(spec == DMT.FNPSwapRejected) {
			return node.lm.handleSwapRejected(m);
		} else if(spec == DMT.FNPSwapCommit) {
			return node.lm.handleSwapCommit(m);
		} else if(spec == DMT.FNPSwapComplete) {
			return node.lm.handleSwapComplete(m);
		} else if(spec == DMT.FNPCHKDataRequest) {
			return handleDataRequest(m, false);
		} else if(spec == DMT.FNPSSKDataRequest) {
			return handleDataRequest(m, true);
		} else if(spec == DMT.FNPInsertRequest) {
			return handleInsertRequest(m, false);
		} else if(spec == DMT.FNPSSKInsertRequest) {
			return handleInsertRequest(m, true);
		} else if(spec == DMT.FNPRoutedPing) {
			return handleRouted(m);
		} else if(spec == DMT.FNPRoutedPong) {
			return handleRoutedReply(m);
		} else if(spec == DMT.FNPRoutedRejected) {
			return handleRoutedRejected(m);
		} else if(spec == DMT.FNPProbeRequest) {
			return handleProbeRequest(m, source);
		} else if(spec == DMT.FNPProbeReply) {
			return handleProbeReply(m, source);
		} else if(spec == DMT.FNPProbeRejected) {
			return handleProbeRejected(m, source);
		} else if(spec == DMT.FNPProbeTrace) {
			return handleProbeTrace(m, source);
		}
		return false;
	}

	private void handleDisconnect(Message m, PeerNode source) {
		source.disconnected();
		// If true, remove from active routing table, likely to be down for a while.
		// Otherwise just dump all current connection state and keep trying to connect.
		boolean remove = m.getBoolean(DMT.REMOVE);
		if(remove)
			node.peers.disconnect(source, false, false);
		// If true, purge all references to this node. Otherwise, we can keep the node
		// around in secondary tables etc in order to more easily reconnect later. 
		// (Mostly used on opennet)
		// Not used at the moment - FIXME
		boolean purge = m.getBoolean(DMT.PURGE);
		// Process parting message
		int type = m.getInt(DMT.NODE_TO_NODE_MESSAGE_TYPE);
		ShortBuffer messageData = (ShortBuffer) m.getObject(DMT.NODE_TO_NODE_MESSAGE_DATA);
		if(messageData.getLength() == 0) return;
		node.receivedNodeToNodeMessage(source, type, messageData, true);
	}

	private boolean handleTime(Message m, PeerNode source) {
		long delta = m.getLong(DMT.TIME) - System.currentTimeMillis();
		source.setTimeDelta(delta);
		return true;
	}

	/**
	 * Handle an incoming FNPDataRequest.
	 */
	private boolean handleDataRequest(Message m, boolean isSSK) {
		long id = m.getLong(DMT.UID);
		if(node.recentlyCompleted(id)) {
			Message rejected = DMT.createFNPRejectedLoop(id);
			try {
				m.getSource().sendAsync(rejected, null, 0, null);
			} catch (NotConnectedException e) {
				Logger.normal(this, "Rejecting data request (loop, finished): "+e);
			}
			return true;
		}
		if(!node.lockUID(id, isSSK, false)) {
			if(logMINOR) Logger.minor(this, "Could not lock ID "+id+" -> rejecting (already running)");
			Message rejected = DMT.createFNPRejectedLoop(id);
			try {
				m.getSource().sendAsync(rejected, null, 0, null);
			} catch (NotConnectedException e) {
				Logger.normal(this, "Rejecting insert request from "+m.getSource().getPeer()+": "+e);
			}
			return true;
		} else {
			if(logMINOR) Logger.minor(this, "Locked "+id);
		}
		String rejectReason = nodeStats.shouldRejectRequest(!isSSK, false, isSSK);
		if(rejectReason != null) {
			// can accept 1 CHK request every so often, but not with SSKs because they aren't throttled so won't sort out bwlimitDelayTime, which was the whole reason for accepting them when overloaded...
			Logger.normal(this, "Rejecting request from "+m.getSource().getPeer()+" preemptively because "+rejectReason);
			Message rejected = DMT.createFNPRejectedOverload(id, true);
			try {
				m.getSource().sendAsync(rejected, null, 0, null);
			} catch (NotConnectedException e) {
				Logger.normal(this, "Rejecting (overload) data request from "+m.getSource().getPeer()+": "+e);
			}
			node.unlockUID(id, isSSK, false, false);
			return true;
		}
		//if(!node.lockUID(id)) return false;
		RequestHandler rh = new RequestHandler(m, id, node);
		node.executor.execute(rh, "RequestHandler for UID "+id);
		return true;
	}

	private boolean handleInsertRequest(Message m, boolean isSSK) {
		long id = m.getLong(DMT.UID);
		if(node.recentlyCompleted(id)) {
			Message rejected = DMT.createFNPRejectedLoop(id);
			try {
				m.getSource().sendAsync(rejected, null, 0, null);
			} catch (NotConnectedException e) {
				Logger.normal(this, "Rejecting insert request from "+m.getSource().getPeer()+": "+e);
			}
			return true;
		}
		if(!node.lockUID(id, isSSK, true)) {
			if(logMINOR) Logger.minor(this, "Could not lock ID "+id+" -> rejecting (already running)");
			Message rejected = DMT.createFNPRejectedLoop(id);
			try {
				m.getSource().sendAsync(rejected, null, 0, null);
			} catch (NotConnectedException e) {
				Logger.normal(this, "Rejecting insert request from "+m.getSource().getPeer()+": "+e);
			}
			return true;
		}
		// SSKs don't fix bwlimitDelayTime so shouldn't be accepted when overloaded.
		String rejectReason = nodeStats.shouldRejectRequest(!isSSK, true, isSSK);
		if(rejectReason != null) {
			Logger.normal(this, "Rejecting insert from "+m.getSource().getPeer()+" preemptively because "+rejectReason);
			Message rejected = DMT.createFNPRejectedOverload(id, true);
			try {
				m.getSource().sendAsync(rejected, null, 0, null);
			} catch (NotConnectedException e) {
				Logger.normal(this, "Rejecting (overload) insert request from "+m.getSource().getPeer()+": "+e);
			}
			node.unlockUID(id, isSSK, true, false);
			return true;
		}
		long now = System.currentTimeMillis();
		if(m.getSpec().equals(DMT.FNPSSKInsertRequest)) {
			SSKInsertHandler rh = new SSKInsertHandler(m, id, node, now);
			node.executor.execute(rh, "InsertHandler for "+id+" on "+node.getDarknetPortNumber());
		} else {
			InsertHandler rh = new InsertHandler(m, id, node, now);
			node.executor.execute(rh, "InsertHandler for "+id+" on "+node.getDarknetPortNumber());
		}
		if(logMINOR) Logger.minor(this, "Started InsertHandler for "+id);
		return true;
	}

	final Hashtable routedContexts = new Hashtable();

	static class RoutedContext {
		long createdTime;
		long accessTime;
		PeerNode source;
		final HashSet routedTo;
		final HashSet notIgnored;
		Message msg;
		short lastHtl;

		RoutedContext(Message msg) {
			createdTime = accessTime = System.currentTimeMillis();
			source = (PeerNode)msg.getSource();
			routedTo = new HashSet();
			notIgnored = new HashSet();
			this.msg = msg;
			lastHtl = msg.getShort(DMT.HTL);
		}

		void addSent(PeerNode n) {
			routedTo.add(n);
		}
	}

	/**
	 * Handle an FNPRoutedRejected message.
	 */
	private boolean handleRoutedRejected(Message m) {
		long id = m.getLong(DMT.UID);
		Long lid = new Long(id);
		RoutedContext rc = (RoutedContext) routedContexts.get(lid);
		if(rc == null) {
			// Gah
			Logger.error(this, "Unrecognized FNPRoutedRejected");
			return false; // locally originated??
		}
		short htl = rc.lastHtl;
		if(rc.source != null)
			htl = rc.source.decrementHTL(htl);
		short ohtl = m.getShort(DMT.HTL);
		if(ohtl < htl) htl = ohtl;
		// Try routing to the next node
		forward(rc.msg, id, rc.source, htl, rc.msg.getDouble(DMT.TARGET_LOCATION), rc);
		return true;
	}

	/**
	 * Handle a routed-to-a-specific-node message.
	 * @param m
	 * @return False if we want the message put back on the queue.
	 */
	boolean handleRouted(Message m) {
		if(logMINOR) Logger.minor(this, "handleRouted("+m+ ')');
		if((m.getSource() != null) && (!(m.getSource() instanceof PeerNode))) {
			Logger.error(this, "Routed message but source "+m.getSource()+" not a PeerNode!");
			return true;
		}
		long id = m.getLong(DMT.UID);
		Long lid = new Long(id);
		PeerNode pn = (PeerNode) (m.getSource());
		short htl = m.getShort(DMT.HTL);
		if(pn != null) htl = pn.decrementHTL(htl);
		RoutedContext ctx;
		ctx = (RoutedContext)routedContexts.get(lid);
		if(ctx != null) {
			try {
				m.getSource().sendAsync(DMT.createFNPRoutedRejected(id, (short)(htl-1)), null, 0, null);
			} catch (NotConnectedException e) {
				if(logMINOR) Logger.minor(this, "Lost connection rejecting "+m);
			}
			return true;
		}
		ctx = new RoutedContext(m);
		routedContexts.put(lid, ctx);
		// pn == null => originated locally, keep full htl
		double target = m.getDouble(DMT.TARGET_LOCATION);
		if(logMINOR) Logger.minor(this, "id "+id+" from "+pn+" htl "+htl+" target "+target);
		if(Math.abs(node.lm.getLocation() - target) <= Double.MIN_VALUE) {
			if(logMINOR) Logger.minor(this, "Dispatching "+m.getSpec()+" on "+node.getDarknetPortNumber());
			// Handle locally
			// Message type specific processing
			dispatchRoutedMessage(m, pn, id);
			return true;
		} else if(htl == 0) {
			Message reject = DMT.createFNPRoutedRejected(id, (short)0);
			if(pn != null) try {
				pn.sendAsync(reject, null, 0, null);
			} catch (NotConnectedException e) {
				if(logMINOR) Logger.minor(this, "Lost connection rejecting "+m);
			}
			return true;
		} else {
			return forward(m, id, pn, htl, target, ctx);
		}
	}

	boolean handleRoutedReply(Message m) {
		long id = m.getLong(DMT.UID);
		if(logMINOR) Logger.minor(this, "Got reply: "+m);
		Long lid = new Long(id);
		RoutedContext ctx = (RoutedContext) routedContexts.get(lid);
		if(ctx == null) {
			Logger.error(this, "Unrecognized routed reply: "+m);
			return false;
		}
		PeerNode pn = ctx.source;
		if(pn == null) return false;
		try {
			pn.sendAsync(m, null, 0, null);
		} catch (NotConnectedException e) {
			if(logMINOR) Logger.minor(this, "Lost connection forwarding "+m+" to "+pn);
		}
		return true;
	}

	private boolean forward(Message m, long id, PeerNode pn, short htl, double target, RoutedContext ctx) {
		if(logMINOR) Logger.minor(this, "Should forward");
		// Forward
		m = preForward(m, htl);
		while(true) {
			PeerNode next = node.peers.closerPeer(pn, ctx.routedTo, ctx.notIgnored, target, true, node.isAdvancedModeEnabled(), -1, null);
			if(logMINOR) Logger.minor(this, "Next: "+next+" message: "+m);
			if(next != null) {
				// next is connected, or at least has been => next.getPeer() CANNOT be null.
				if(logMINOR) Logger.minor(this, "Forwarding "+m.getSpec()+" to "+next.getPeer().getPort());
				ctx.addSent(next);
				try {
					next.sendAsync(m, null, 0, null);
				} catch (NotConnectedException e) {
					continue;
				}
			} else {
				if(logMINOR) Logger.minor(this, "Reached dead end for "+m.getSpec()+" on "+node.getDarknetPortNumber());
				// Reached a dead end...
				Message reject = DMT.createFNPRoutedRejected(id, htl);
				if(pn != null) try {
					pn.sendAsync(reject, null, 0, null);
				} catch (NotConnectedException e) {
					Logger.error(this, "Cannot send reject message back to source "+pn);
					return true;
				}
			}
			return true;
		}
	}

	/**
	 * Prepare a routed-to-node message for forwarding.
	 */
	private Message preForward(Message m, short newHTL) {
		m.set(DMT.HTL, newHTL); // update htl
		if(m.getSpec() == DMT.FNPRoutedPing) {
			int x = m.getInt(DMT.COUNTER);
			x++;
			m.set(DMT.COUNTER, x);
		}
		return m;
	}

	/**
	 * Deal with a routed-to-node message that landed on this node.
	 * This is where message-type-specific code executes. 
	 * @param m
	 * @return
	 */
	private boolean dispatchRoutedMessage(Message m, PeerNode src, long id) {
		if(m.getSpec() == DMT.FNPRoutedPing) {
			if(logMINOR) Logger.minor(this, "RoutedPing reached other side!");
			int x = m.getInt(DMT.COUNTER);
			Message reply = DMT.createFNPRoutedPong(id, x);
			try {
				src.sendAsync(reply, null, 0, null);
			} catch (NotConnectedException e) {
				if(logMINOR) Logger.minor(this, "Lost connection replying to "+m+" in dispatchRoutedMessage");
			}
			return true;
		}
		return false;
	}

	// Probe request handling

	long tLastReceivedProbeRequest;

	static final int MAX_PROBE_CONTEXTS = 1000;
	static final int MAX_PROBE_IDS = 10000;

	class ProbeContext {

		private final WeakReference /* <PeerNode> */ srcRef;
		final WeakHashSet visitedPeers;
		final ProbeCallback cb;
		short counter;
		short linearCounter;
		short htl;
		double nearest;
		double best;
		Vector notVisitedList; // List of best locations not yet visited by this request
		short forkCount;

		public ProbeContext(long id, double target, double best, double nearest, short htl, short counter, PeerNode src, ProbeCallback cb) {
			visitedPeers = new WeakHashSet();
			this.counter = counter;
			this.htl = htl;
			this.nearest = nearest;
			this.best = best;
			this.srcRef = (src == null) ? null : src.myRef;
			this.cb = cb;
		}

		public PeerNode getSource() {
			if(srcRef != null) return (PeerNode) srcRef.get();
			return null;
		}

	}

	final LRUQueue recentProbeRequestIDs = new LRUQueue();
	final LRUHashtable recentProbeContexts = new LRUHashtable();

	/** 
	 * Handle a probe request.
	 * Reject it if it's looped.
	 * Look up (and promote) its context object.
	 * Update its HTL, nearest-seen and best-seen.
	 * Complete it if it has run out of HTL.
	 * Otherwise forward it.
	 **/
	private boolean handleProbeRequest(Message m, PeerNode src) {
		long id = m.getLong(DMT.UID);
		Long lid = new Long(id);
		double target = m.getDouble(DMT.TARGET_LOCATION);
		double best = m.getDouble(DMT.BEST_LOCATION);
		double nearest = m.getDouble(DMT.NEAREST_LOCATION);
		short htl = m.getShort(DMT.HTL);
		short counter = m.getShort(DMT.COUNTER);
		short linearCounter = m.getShort(DMT.LINEAR_COUNTER);
		if(logMINOR)
			Logger.minor(this, "Probe request: "+id+ ' ' +target+ ' ' +best+ ' ' +nearest+ ' ' +htl+ ' ' +counter);
		synchronized(recentProbeContexts) {
			if(recentProbeRequestIDs.contains(lid)) {
				// Reject: Loop
				Message reject = DMT.createFNPProbeRejected(id, target, nearest, best, counter, htl, DMT.PROBE_REJECTED_LOOP, linearCounter);
				try {
					src.sendAsync(reject, null, 0, null);
				} catch (NotConnectedException e) {
					Logger.error(this, "Not connected rejecting a Probe request from "+src);
				}
				return true;
			} else
				Logger.minor(this, "Probe request "+id+" not already present");
			recentProbeRequestIDs.push(lid);
			while(recentProbeRequestIDs.size() > MAX_PROBE_IDS) {
				Object o = recentProbeRequestIDs.pop();
				Logger.minor(this, "Probe request popped "+o);
			}
		}
		Message notVisited = m.getSubMessage(DMT.FNPBestRoutesNotTaken);
		double[] locsNotVisited = null;
		Vector notVisitedList = new Vector();
		if(notVisited != null) {
			locsNotVisited = Fields.bytesToDoubles(((ShortBuffer)notVisited.getObject(DMT.BEST_LOCATIONS_NOT_VISITED)).getData());
			for(int i=0;i<locsNotVisited.length;i++)
				notVisitedList.add(new Double(locsNotVisited[i]));
		}
		innerHandleProbeRequest(src, id, lid, target, best, nearest, htl, counter, true, true, false, true, null, notVisitedList, 2.0, false, ++linearCounter, "request");
		return true;
	}

	static final int MAX_LOCS_NOT_VISITED = 10;
	static final int MAX_FORKS = 2;
	
	/**
	 * 
	 * @param src
	 * @param id
	 * @param lid
	 * @param target
	 * @param best
	 * @param nearest Best-so-far for normal routing purposes.
	 * @param htl
	 * @param counter
	 * @param checkRecent
	 * @param loadLimitRequest True if this is a new request which can be rejected due to load, false if it's an existing
	 * request which we should handle anyway.
	 * @param cb
	 * @param locsNotVisited 
	 * @param maxDistance Don't route to any nodes further away from the target than this distance.
	 * Note that it is a distance, NOT A LOCATION.
	 * @param dontReject If true, don't reject the request, simply return false and the caller will handle it.
	 * @return True unless we rejected the request (due to load, route not found etc), or would have if it weren't for dontReject.
	 */
	private boolean innerHandleProbeRequest(PeerNode src, long id, Long lid, final double target, double best, 
			double nearest, short htl, short counter, boolean checkRecent, boolean loadLimitRequest, 
			boolean fromRejection, boolean isNew, ProbeCallback cb, Vector locsNotVisited, double maxDistance, boolean dontReject,
			short linearCounter, String callerReason) {
		if(fromRejection) {
			nearest = furthestLoc(target); // reject CANNOT change nearest, because it's from a dead-end; "improving"
			// nearest will only result in the request being truncated
			// However it CAN improve best, because we want an accurate "best"
		}
		short max = node.maxHTL();
		if(htl > max) htl = max;
		if(htl <= 1) htl = 1;
		ProbeContext ctx = null;
		boolean rejected = false;
		synchronized(recentProbeContexts) {
			if(checkRecent) {
				long now = System.currentTimeMillis();
				if(now - tLastReceivedProbeRequest < 500 && loadLimitRequest) {
					rejected = true;
				} else {
					tLastReceivedProbeRequest = now;
				}
			}
			counter++; // Increment on every hop even if we reject it, this makes it easier to read the trace
			if(!rejected) {
				ctx = (ProbeContext) recentProbeContexts.get(lid);
				if(ctx == null) {
					if(isNew) {
						ctx = new ProbeContext(id, target, best, nearest, htl, counter, src, cb);
					} else {
						Logger.error(this, "Not creating new context for: "+id);
						return false;
					}
				}
				recentProbeContexts.push(lid, ctx); // promote or add
				while(recentProbeContexts.size() > MAX_PROBE_CONTEXTS)
					recentProbeContexts.popValue();
			}
		}
		PeerNode origSource = ctx.getSource();
		if(linearCounter < 0) linearCounter = ctx.linearCounter;
		ctx.linearCounter = linearCounter;
		if(locsNotVisited != null) {
			if(logMINOR)
				Logger.minor(this, "Locs not visited: "+locsNotVisited);
		}
		if(fromRejection) {
			// Rejected by a dead-end, will not return us a notVisitedList.
			// Would be pointless.
			locsNotVisited = ctx.notVisitedList;
		}
		
		// Add source
		if(src != null) ctx.visitedPeers.add(src);
		if(rejected) {
			if(!dontReject) {
				// Reject: rate limit
				Message reject = DMT.createFNPProbeRejected(id, target, nearest, best, counter, htl, DMT.PROBE_REJECTED_OVERLOAD, linearCounter);
				try {
					src.sendAsync(reject, null, 0, null);
				} catch (NotConnectedException e) {
					Logger.error(this, "Not connected rejecting a probe request from "+src);
				}
			}
			return false;
		}
		if(ctx.counter < counter) ctx.counter = counter;
		if(logMINOR)
			Logger.minor(this, "ctx.nearest="+ctx.nearest+", nearest="+nearest+", target="+target+", htl="+htl+", ctx.htl="+ctx.htl);
		if(ctx.htl > htl) {
			// Rejected can reduce HTL
			ctx.htl = htl;
		}
		Logger.minor(this, "htl="+htl+", nearest="+nearest+", ctx.htl="+ctx.htl+", ctx.nearest="+ctx.nearest);

		PeerNode[] peers = node.peers.connectedPeers;

		double myLoc = node.getLocation();
		for(int i=0;i<locsNotVisited.size();i++) {
			double loc = ((Double) locsNotVisited.get(i)).doubleValue();
			if(Math.abs(loc - myLoc) < Double.MIN_VALUE * 2) {
				locsNotVisited.remove(i);
				break;
			}
		}
		// Update best

		if(myLoc > target && myLoc < best)
			best = myLoc;

		if(ctx.best > target && ctx.best < best)
			best = ctx.best;

		for(int i=0;i<peers.length;i++) {
			double loc = peers[i].getLocation();
			if(logMINOR) Logger.minor(this, "Location: "+loc);
			// We are only interested in locations greater than the target
			if(loc <= (target + 2*Double.MIN_VALUE)) {
				if(logMINOR) Logger.minor(this, "Location is under target");
				continue;
			}
			if(loc < best) {
				if(logMINOR) Logger.minor(this, "New best: "+loc+" was "+best);
				best = loc;
			}
		}

		// Update nearest, htl

		// Rejected, or even reply, cannot make nearest *worse* and thereby prolong the request.
		// In fact, rejected probe requests result in clearing nearest at the beginning of the function, so it is vital
		// that we restore it here.
		if(Location.distance(ctx.nearest, target, true) < Location.distance(nearest, target, true)) {
			nearest = ctx.nearest;
		}
		
		// If we are closer to the target than nearest, update nearest and reset HTL, else decrement HTL
		if(Location.distance(myLoc, target, true) < Location.distance(nearest, target, true)) {
			if(logMINOR)
				Logger.minor(this, "Updating nearest to "+myLoc+" from "+nearest+" for "+target+" and resetting htl from "+htl+" to "+max);
			if(Math.abs(nearest - myLoc) > Double.MIN_VALUE * 2)
				nearest = myLoc;
			htl = max;
			ctx.nearest = nearest;
			ctx.htl = htl;
		} else {
			htl = node.decrementHTL(origSource, htl);
			ctx.htl = htl;
			if(logMINOR)
				Logger.minor(this, "Updated htl to "+htl+" - myLoc="+myLoc+", target="+target+", nearest="+nearest);
		}

		// Complete ?
		if(htl == 0) {
			if(origSource != null) {
				// Complete
				Message complete = DMT.createFNPProbeReply(id, target, nearest, best, counter++, linearCounter);
				Message sub = DMT.createFNPBestRoutesNotTaken((Double[])locsNotVisited.toArray(new Double[locsNotVisited.size()]));
				complete.addSubMessage(sub);
				try {
					origSource.sendAsync(complete, null, 0, null);
				} catch (NotConnectedException e) {
					Logger.error(this, "Not connected completing a probe request from "+src);
				}
				return true; // counts as success
			} else {
				complete("success", target, best, nearest, id, ctx, counter, linearCounter);
			}
		}

		// Otherwise route it

		WeakHashSet visited = ctx.visitedPeers;

		while(true) {

			Vector newBestLocs = new Vector();
			newBestLocs.addAll(locsNotVisited);
			PeerNode pn = node.peers.closerPeer(src, visited, null, target, true, false, 965, newBestLocs, maxDistance);
			
			if(logMINOR)
				Logger.minor(this, "newBestLocs (unsorted): "+newBestLocs);
			
			Double[] locs = (Double[]) newBestLocs.toArray(new Double[newBestLocs.size()]);
			Arrays.sort(locs, new Comparator() {
				public int compare(Object arg0, Object arg1) {
					double d0 = ((Double) arg0).doubleValue();
					double d1 = ((Double) arg1).doubleValue();
					double dist0 = Location.distance(d0, target, true);
					double dist1 = Location.distance(d1, target, true);
					if(dist0 < dist1) return -1; // best at the beginning
					if(dist0 > dist1) return 1;
					return 0; // should not happen
				}
			});
			locsNotVisited.clear();
			for(int i=0;i<Math.min(MAX_LOCS_NOT_VISITED, locs.length);i++)
				locsNotVisited.add(locs[i]);
			
			if(logMINOR)
				Logger.minor(this, "newBestLocs: "+locsNotVisited);
			
			Message sub = DMT.createFNPBestRoutesNotTaken((Double[])locsNotVisited.toArray(new Double[locsNotVisited.size()]));
			
			ctx.notVisitedList = locsNotVisited;

			if(pn == null) {
				// Can't complete, because some HTL left
				// Reject: RNF
				if(!dontReject) {
					if(src != null) {
						Message reject = DMT.createFNPProbeRejected(id, target, nearest, best, counter, htl, DMT.PROBE_REJECTED_RNF, linearCounter);
						reject.addSubMessage(sub);
						try {
							src.sendAsync(reject, null, 0, null);
						} catch (NotConnectedException e) {
							Logger.error(this, "Not connected rejecting a probe request from "+src);
						}
					} else {
						complete("RNF", target, best, nearest, id, ctx, counter, linearCounter);
					}
				}
				return false;
			}

			visited.add(pn);

			if(origSource != null) {
				Message trace =
					DMT.createFNPProbeTrace(id, target, nearest, best, htl, counter, myLoc, node.swapIdentifier, LocationManager.extractLocs(peers, true), LocationManager.extractUIDs(peers), ctx.forkCount, linearCounter, callerReason, src == null ? -1 : src.swapIdentifier);
				trace.addSubMessage(sub);
				try {
					origSource.sendAsync(trace, null, 0, null);
				} catch (NotConnectedException e1) {
					// Ignore
				}
			}
			
			Message forwarded =
				DMT.createFNPProbeRequest(id, target, nearest, best, htl, counter++, linearCounter);
			forwarded.addSubMessage(sub);
			try {
				pn.sendAsync(forwarded, null, 0, null);
				return true;
			} catch (NotConnectedException e) {
				Logger.error(this, "Could not forward message: disconnected: "+pn+" : "+e, e);
				// Try another one
			}
		}

	}

	private void complete(String msg, double target, double best, double nearest, long id, ProbeContext ctx, short counter, short linearHops) {
		Logger.normal(this, "Completed Probe request # "+id+" - RNF - "+msg+": "+best);
		ctx.cb.onCompleted(msg, target, best, nearest, id, counter, linearHops);
	}

	private void reportTrace(ProbeContext ctx, Message msg) {
		long uid = msg.getLong(DMT.UID);
		double target = msg.getDouble(DMT.TARGET_LOCATION);
		double nearest = msg.getDouble(DMT.NEAREST_LOCATION);
		double best = msg.getDouble(DMT.BEST_LOCATION);
		short htl = msg.getShort(DMT.HTL);
		short counter = msg.getShort(DMT.COUNTER);
		double location = msg.getDouble(DMT.LOCATION);
		long nodeUID = msg.getLong(DMT.MY_UID);
		short linearCount = msg.getShort(DMT.LINEAR_COUNTER);
		double[] peerLocs = Fields.bytesToDoubles(((ShortBuffer)msg.getObject(DMT.PEER_LOCATIONS)).getData());
		long[] peerUIDs = Fields.bytesToLongs(((ShortBuffer)msg.getObject(DMT.PEER_UIDS)).getData());
		Message notVisited = msg.getSubMessage(DMT.FNPBestRoutesNotTaken);
		double[] locsNotVisited = null;
		if(notVisited != null) {
			locsNotVisited = Fields.bytesToDoubles(((ShortBuffer)notVisited.getObject(DMT.BEST_LOCATIONS_NOT_VISITED)).getData());
		}
		short forkCount = msg.getShort(DMT.FORK_COUNT);
		String reason = msg.getString(DMT.REASON);
		long prevUID = msg.getLong(DMT.PREV_UID);
		ctx.cb.onTrace(uid, target, nearest, best, htl, counter, location, nodeUID, peerLocs, peerUIDs, locsNotVisited, forkCount, linearCount, reason, prevUID);
	}

	private boolean handleProbeReply(Message m, PeerNode src) {
		long id = m.getLong(DMT.UID);
		Long lid = new Long(id);
		final double target = m.getDouble(DMT.TARGET_LOCATION);
		double best = m.getDouble(DMT.BEST_LOCATION);
		double nearest = m.getDouble(DMT.NEAREST_LOCATION);
		short counter = m.getShort(DMT.COUNTER);
		short linearCounter = m.getShort(DMT.LINEAR_COUNTER);
		if(logMINOR)
			Logger.minor(this, "Probe reply: "+id+ ' ' +target+ ' ' +best+ ' ' +nearest);
		
		// New backtracking algorithm
		
		// Allow forking on the way home - but only if the location we'd fork to would be at least as good as the third best location seen but not visited so far.
		
		// First get the list of not visited so far nodes
		
		Message notVisited = m.getSubMessage(DMT.FNPBestRoutesNotTaken);
		double[] locsNotVisited = null;
		Vector notVisitedList = new Vector();
		if(notVisited != null) {
			locsNotVisited = Fields.bytesToDoubles(((ShortBuffer)notVisited.getObject(DMT.BEST_LOCATIONS_NOT_VISITED)).getData());
			for(int i=0;i<locsNotVisited.length;i++)
				notVisitedList.add(new Double(locsNotVisited[i]));
		}

		// Find it
		ProbeContext ctx;
		synchronized(recentProbeContexts) {
			ctx = (ProbeContext) recentProbeContexts.get(lid);
			if(ctx == null) {
				Logger.normal(this, "Could not forward probe reply back to source for ID "+id);
				return false;
			}
			recentProbeContexts.push(lid, ctx); // promote
			while(recentProbeContexts.size() > MAX_PROBE_CONTEXTS)
				recentProbeContexts.popValue();
		}
		PeerNode origSource = (PeerNode) ctx.getSource();

		Message sub = m.getSubMessage(DMT.FNPBestRoutesNotTaken);

		try {
			// Send a completion trace
			PeerNode[] peers = node.getConnectedPeers();
			Message trace =
				DMT.createFNPProbeTrace(id, target, nearest, best, ctx.htl, counter, node.getLocation(), node.swapIdentifier, LocationManager.extractLocs(peers, true), LocationManager.extractUIDs(peers), ctx.forkCount, linearCounter, "replying", src == null ? -1 : src.swapIdentifier);
			trace.addSubMessage(sub);
			try {
				origSource.sendAsync(trace, null, 0, null);
			} catch (NotConnectedException e1) {
				// Ignore
			}
		} catch (Throwable t) {
			Logger.error(this, "Could not send completion trace: "+t, t);
		}
		
		// Maybe fork
		
		
		
		try {
			double furthestDist = 0.0;
			if(notVisitedList.size() > 0) {
				if(ctx.forkCount < MAX_FORKS) {
					ctx.forkCount++;
					
					Double[] locs = (Double[]) notVisitedList.toArray(new Double[notVisitedList.size()]);
					Arrays.sort(locs, new Comparator() {
						public int compare(Object arg0, Object arg1) {
							double d0 = ((Double) arg0).doubleValue();
							double d1 = ((Double) arg1).doubleValue();
							double dist0 = Location.distance(d0, target, true);
							double dist1 = Location.distance(d1, target, true);
							if(dist0 < dist1) return -1; // best at the beginning
							if(dist0 > dist1) return 1;
							return 0; // should not happen
						}
					});
					
					double mustBeBetterThan = ((Double)locs[Math.min(3,locs.length)]).doubleValue();
					double maxDistance = Location.distance(mustBeBetterThan, target, true);
					
					for(int i=0;i<notVisitedList.size();i++) {
						double loc = ((Double)(notVisitedList.get(i))).doubleValue();
						double dist = Location.distance(loc, target);
						if(dist > furthestDist) {
							furthestDist = dist;
						}
					}
					if(innerHandleProbeRequest(src, id, lid, target, best, nearest, ctx.htl, counter, false, false, false, false, null, notVisitedList, maxDistance, true, linearCounter, "backtracking"))
						return true;
				}
			}
		} catch (Throwable t) {
			// If something happens during the fork attempt, just propagate it
			Logger.error(this, "Caught "+t+" while trying to fork", t);
		}
		
		// Just propagate back to source
		if(origSource != null) {
			Message complete = DMT.createFNPProbeReply(id, target, nearest, best, counter++, linearCounter);
			if(sub != null) complete.addSubMessage(sub);
			try {
				origSource.sendAsync(complete, null, 0, null);
			} catch (NotConnectedException e) {
				Logger.error(this, "Not connected completing a probe request from "+origSource+" (forwarding completion from "+src+ ')');
			}
		} else {
			if(ctx.cb != null)
				complete("Completed", target, best, nearest, id, ctx, counter, linearCounter);
		}
		return true;
	}

	private boolean handleProbeTrace(Message m, PeerNode src) {
		long id = m.getLong(DMT.UID);
		Long lid = new Long(id);
		double target = m.getDouble(DMT.TARGET_LOCATION);
		double best = m.getDouble(DMT.BEST_LOCATION);
		double nearest = m.getDouble(DMT.NEAREST_LOCATION);
		short counter = m.getShort(DMT.COUNTER);
		Message notVisited = m.getSubMessage(DMT.FNPBestRoutesNotTaken);
		double[] locsNotVisited = null;
		if(notVisited != null) {
			locsNotVisited = Fields.bytesToDoubles(((ShortBuffer)notVisited.getObject(DMT.BEST_LOCATIONS_NOT_VISITED)).getData());
		}
		if(logMINOR)
			Logger.minor(this, "Probe trace: "+id+ ' ' +target+ ' ' +best+ ' ' +nearest+' '+counter);
		if(locsNotVisited != null) {
			if(logMINOR)
				Logger.minor(this, "Locs not visited: "+StringArray.toString(locsNotVisited));
		}
		// Just propagate back to source
		ProbeContext ctx;
		synchronized(recentProbeContexts) {
			ctx = (ProbeContext) recentProbeContexts.get(lid);
			if(ctx == null) {
				Logger.normal(this, "Could not forward probe reply back to source for ID "+id);
				return false;
			}
			recentProbeContexts.push(lid, ctx); // promote or add
			while(recentProbeContexts.size() > MAX_PROBE_CONTEXTS)
				recentProbeContexts.popValue();
		}

		PeerNode origSource = ctx.getSource();
		if(origSource != null) {
			try {
				origSource.sendAsync(m, null, 0, null);
			} catch (NotConnectedException e) {
				Logger.error(this, "Not connected forwarding trace to "+origSource+" (from "+src+ ')');
			}
		} else {
			if(ctx.cb != null)
				reportTrace(ctx, m);
		}
		return true;
	}

	private boolean handleProbeRejected(Message m, PeerNode src) {
		long id = m.getLong(DMT.UID);
		Long lid = new Long(id);
		double target = m.getDouble(DMT.TARGET_LOCATION);
		double best = m.getDouble(DMT.BEST_LOCATION);
		double nearest = m.getDouble(DMT.NEAREST_LOCATION);
		short htl = m.getShort(DMT.HTL);
		short counter = m.getShort(DMT.COUNTER);
		short reason = m.getShort(DMT.REASON);
		if(logMINOR)
			Logger.minor(this, "Probe rejected: "+id+ ' ' +target+ ' ' +best+ ' ' +nearest+ ' ' +htl+ ' ' +counter+ ' ' +reason);

		ProbeContext ctx;
		synchronized(recentProbeContexts) {
			ctx = (ProbeContext) recentProbeContexts.get(lid);
			if(ctx == null) {
				Logger.normal(this, "Unknown rejected probe request ID "+id);
				return false;
			}
			recentProbeContexts.push(lid, ctx); // promote or add
			while(recentProbeContexts.size() > MAX_PROBE_CONTEXTS)
				recentProbeContexts.popValue();
		}

		Message notVisited = m.getSubMessage(DMT.FNPBestRoutesNotTaken);
		double[] locsNotVisited = null;
		Vector notVisitedList = new Vector();
		if(notVisited != null) {
			locsNotVisited = Fields.bytesToDoubles(((ShortBuffer)notVisited.getObject(DMT.BEST_LOCATIONS_NOT_VISITED)).getData());
			for(int i=0;i<locsNotVisited.length;i++)
				notVisitedList.add(new Double(locsNotVisited[i]));
		}
		innerHandleProbeRequest(src, id, lid, target, best, nearest, htl, counter, false, false, true, false, null, notVisitedList, 2.0, true, (short)-1, "rejected");
		return true;
	}

	public void startProbe(double d, ProbeCallback cb) {
		long l = node.random.nextLong();
		Long ll = new Long(l);
		synchronized(recentProbeRequestIDs) {
			recentProbeRequestIDs.push(ll);
		}
		double nodeLoc = node.getLocation();
		innerHandleProbeRequest(null, l, ll, d, (nodeLoc > d) ? nodeLoc : furthestGreater(d), nodeLoc, node.maxHTL(), (short)0, false, false, false, true, cb, new Vector(), 2.0, false, (short)-1, "start");
	}
	
	private double furthestLoc(double d) {
		if(d > 0.5) return d - 0.5;
		return d + 0.5;
	}
	
	private double furthestGreater(double d) {
		if(d < 0.5) return d + 0.5;
		return 1.0;
	}

	void start(NodeStats stats) {
		this.nodeStats = stats;
	}

	public static String peersUIDsToString(long[] peerUIDs, double[] peerLocs) {
		StringBuffer sb = new StringBuffer(peerUIDs.length*23+peerLocs.length*26);
		int min=Math.min(peerUIDs.length, peerLocs.length);
		for(int i=0;i<min;i++) {
			double loc = peerLocs[i];
			long uid = peerUIDs[i];
			sb.append(loc);
			sb.append('=');
			sb.append(uid);
			if(i != min-1)
				sb.append('|');
		}
		if(peerUIDs.length > min) {
			for(int i=min;i<peerUIDs.length;i++) {
				sb.append("|U:");
				sb.append(peerUIDs[i]);
			}
		} else if(peerLocs.length > min) {
			for(int i=min;i<peerLocs.length;i++) {
				sb.append("|L:");
				sb.append(peerLocs[i]);
			}
		}
		return sb.toString();
	}
}