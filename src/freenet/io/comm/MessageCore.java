/*
 * Dijjer - A Peer to Peer HTTP Cache
 * Copyright (C) 2004,2005 Change.Tv, Inc
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */
package freenet.io.comm;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.ListIterator;
import java.util.Map;
import java.util.Vector;

import freenet.node.PeerNode;
import freenet.node.Ticker;
import freenet.support.Logger;
import freenet.support.TimeUtil;

public class MessageCore {

	public static final String VERSION = "$Id: MessageCore.java,v 1.22 2005/08/25 17:28:19 amphibian Exp $";
	private static boolean logMINOR; 
	private Dispatcher _dispatcher;
	/** _filters serves as lock for both */
	private final LinkedList _filters = new LinkedList();
	private final LinkedList _unclaimed = new LinkedList();
	private static final int MAX_UNMATCHED_FIFO_SIZE = 50000;
	private static final long MAX_UNCLAIMED_FIFO_ITEM_LIFETIME = 10*60*1000;  // 10 minutes; maybe this should be per message type??
	// Every second, remove all timed out filters
	private static final int FILTER_REMOVE_TIME = 1000;
	private long startedTime;
	
	public synchronized long getStartedTime() {
		return startedTime;
	}

	public MessageCore() {
		_timedOutFilters = new Vector(32);
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
	}

	/**
	 * Decode a packet from data and a peer.
	 * Can be called by IncomingPacketFilter's.
     * @param data
     * @param offset
     * @param length
     * @param peer
     */
    public Message decodeSingleMessage(byte[] data, int offset, int length, PeerContext peer, int overhead) {
        try {
            return Message.decodeMessageFromPacket(data, offset, length, peer, overhead);
        } catch (Throwable t) {
            Logger.error(this, "Could not decode packet: "+t, t);
            return null;
        }
    }

    /** Only used by removeTimedOutFilters() - if future code uses this elsewhere, we need to
     * reconsider its locking. */
    private final Vector _timedOutFilters;
    
    public void start(final Ticker ticker) {
    	synchronized(this) {
    		startedTime = System.currentTimeMillis();
    	}
    	ticker.queueTimedJob(new Runnable() {

			public void run() {
				try {
					removeTimedOutFilters();
				} catch (Throwable t) {
					Logger.error(this, "Failed to remove timed out filters: "+t, t);
				} finally {
					ticker.queueTimedJob(this, FILTER_REMOVE_TIME);
				}
				// TODO Auto-generated method stub
				
			}
    		
    	}, FILTER_REMOVE_TIME);
    }
    
    // FIXME debugging paranoia - turn off for maximum performance
    static final boolean DEBUG_CHECK_REST_OF_QUEUE = true;
    
    /**
     * Remove timed out filters.
     */
	void removeTimedOutFilters() {
		long tStart = System.currentTimeMillis() + 1;
		// Extra millisecond to give waitFor() a chance to remove the filter.
		// Avoids exhaustive and unsuccessful search in waitFor() removal of a timed out filter.
		if(logMINOR)
			Logger.minor(this, "Removing timed out filters");
		synchronized (_filters) {
			for (ListIterator i = _filters.listIterator(); i.hasNext();) {
				MessageFilter f = (MessageFilter) i.next();
				if (f.timedOut(tStart)) {
					if(logMINOR)
						Logger.minor(this, "Removing "+f);
					i.remove();
					_timedOutFilters.add(f);
				} else {
					// Because _filters are in order of timeout, we
					// can abort the iteration as soon as we find one that
					// doesn't timeout
					if(Logger.shouldLog(Logger.DEBUG, this))
						Logger.debug(this, "Stopping removing timed out filters at "+f+" : timeout = "+f.getTimeout()+" initial timeout = "+f.getInitialTimeout());
					if(DEBUG_CHECK_REST_OF_QUEUE) {
						while(i.hasNext()) {
							MessageFilter f1 = (MessageFilter) i.next();
							if(f1.getTimeout() < tStart) {
								Logger.error(this, "Still failing to timeout all filters! Filter "+f1+" timeout "+f1.getTimeout()+" matched but would have stopped at filter "+f+" timeout "+f.getTimeout()+" at time "+tStart);
								i.remove();
								_timedOutFilters.add(f);
							}
								
						}
					}
					break;
				}
			}
		}
		
		for(int i=0;i<_timedOutFilters.size();i++) {
			MessageFilter f = (MessageFilter) _timedOutFilters.get(i);
			f.setMessage(null);
			f.onTimedOut();
		}
		_timedOutFilters.clear();
		
		long tEnd = System.currentTimeMillis();
		if(tEnd - tStart > 50) {
			if(tEnd - tStart > 3000)
				Logger.error(this, "removeTimedOutFilters took "+(tEnd-tStart)+"ms");
			else
				if(logMINOR) Logger.minor(this, "removeTimedOutFilters took "+(tEnd-tStart)+"ms");
		}
	}

	/**
	 * Dispatch a message to a waiting filter, or feed it to the
	 * Dispatcher if none are found.
	 * @param m The Message to dispatch.
	 */
	public void checkFilters(Message m, PacketSocketHandler from) {
		long tStart = System.currentTimeMillis();
		if(logMINOR) Logger.minor(this, "checkFilters: "+m+" from "+m.getSource());
		if ((m.getSource()) instanceof PeerNode)
		{
			((PeerNode)m.getSource()).addToLocalNodeReceivedMessagesFromStatistic(m);
		}
		boolean matched = false;
		if ((!(m.getSpec().equals(DMT.packetTransmit))) && logMINOR) {
			if(logMINOR) Logger.minor(this, "" + (System.currentTimeMillis() % 60000) + ' ' + from + " <- "
					+ m.getSource() + " : " + m);
		}
		MessageFilter match = null;
		synchronized (_filters) {
			for (ListIterator i = _filters.listIterator(); i.hasNext();) {
				MessageFilter f = (MessageFilter) i.next();
				if (f.match(m)) {
					matched = true;
					i.remove();
					match = f;
					if(logMINOR) Logger.minor(this, "Matched: "+f);
					break; // Only one match permitted per message
				}
			}
		}
		if(match != null) {
			match.setMessage(m);
			match.onMatched();
		}
		// Feed unmatched messages to the dispatcher
		if ((!matched) && (_dispatcher != null)) {
		    try {
		    	if(logMINOR) Logger.minor(this, "Feeding to dispatcher: "+m);
		        matched = _dispatcher.handleMessage(m);
		    } catch (Throwable t) {
		        Logger.error(this, "Dispatcher threw "+t, t);
		    }
		}
		// Keep the last few _unclaimed messages around in case the intended receiver isn't receiving yet
		if (!matched) {
			if(logMINOR) Logger.minor(this, "Unclaimed: "+m);
		    /** Check filters and then add to _unmatched is ATOMIC
		     * It has to be atomic, because otherwise we can get a
		     * race condition that results in timeouts on MFs.
		     * 
		     * Specifically:
		     * - Thread A receives packet
		     * - Thread A checks filters. It doesn't match any.
		     * - Thread A feeds to Dispatcher.
		     * - Thread B creates filter.
		     * - Thread B checks _unmatched.
		     * - Thread B adds filter.
		     * - Thread B sleeps.
		     * - Thread A returns from Dispatcher. Which didn't match.
		     * - Thread A adds to _unmatched.
		     * 
		     * OOPS!
		     * The only way to fix this is to have checking the
		     * filters and unmatched be a single atomic operation.
		     * Another race is possible if we merely recheck the
		     * filters after we return from dispatcher, for example.
		     */
			synchronized (_filters) {
				if(logMINOR) Logger.minor(this, "Rechecking filters and adding message");
				for (ListIterator i = _filters.listIterator(); i.hasNext();) {
					MessageFilter f = (MessageFilter) i.next();
					if (f.match(m)) {
						matched = true;
						match = f;
						i.remove();
						if(logMINOR) Logger.minor(this, "Matched: "+f);
						break; // Only one match permitted per message
					}
				}
				if(!matched) {
				    while (_unclaimed.size() > MAX_UNMATCHED_FIFO_SIZE) {
				        Message removed = (Message)_unclaimed.removeFirst();
				        long messageLifeTime = System.currentTimeMillis() - removed.localInstantiationTime;
				        if ((removed.getSource()) instanceof PeerNode) {
				            Logger.normal(this, "Dropping unclaimed from "+removed.getSource().getPeer()+", lived "+TimeUtil.formatTime(messageLifeTime, 2, true)+" (quantity)"+": "+removed);
				        } else {
				            Logger.normal(this, "Dropping unclaimed, lived "+TimeUtil.formatTime(messageLifeTime, 2, true)+" (quantity)"+": "+removed);
				        }
				    }
				    _unclaimed.addLast(m);
				    if(logMINOR) Logger.minor(this, "Done");
				}
			}
			if(match != null) {
				match.setMessage(m);
				match.onMatched();
			}
		}
		long tEnd = System.currentTimeMillis();
		if(tEnd - tStart > 50) {
			if(tEnd - tStart > 3000)
				Logger.error(this, "checkFilters took "+(tEnd-tStart)+"ms with unclaimedFIFOSize of "+_unclaimed.size()+" for matched: "+matched);
			else
				if(logMINOR) Logger.minor(this, "checkFilters took "+(tEnd-tStart)+"ms with unclaimedFIFOSize of "+_unclaimed.size()+" for matched: "+matched);
		}
	}
	
	/** IncomingPacketFilter should call this when a node is disconnected. */
	public void onDisconnect(PeerContext ctx) {
		Vector droppedFilters = null; // rare operation, we can waste objects for better locking
	    synchronized(_filters) {
			ListIterator i = _filters.listIterator();
			while (i.hasNext()) {
			    MessageFilter f = (MessageFilter) i.next();
			    if(f.matchesDroppedConnection(ctx)) {
			    	if(droppedFilters == null)
			    		droppedFilters = new Vector();
			    	droppedFilters.add(f);
			    	i.remove();
			    }
			}
	    }
	    if(droppedFilters != null) {
	    	for(int i=0;i<droppedFilters.size();i++) {
	    		MessageFilter mf = (MessageFilter) droppedFilters.get(i);
		        mf.onDroppedConnection(ctx);
	    	}
	    }
	}
	
	/** IncomingPacketFilter should call this when a node connects with a new boot ID */
	public void onRestart(PeerContext ctx) {
		Vector droppedFilters = null; // rare operation, we can waste objects for better locking
	    synchronized(_filters) {
			ListIterator i = _filters.listIterator();
			while (i.hasNext()) {
			    MessageFilter f = (MessageFilter) i.next();
			    if(f.matchesRestartedConnection(ctx)) {
			    	if(droppedFilters == null)
			    		droppedFilters = new Vector();
			    	droppedFilters.add(f);
			    	i.remove();
			    }
			}
	    }
	    if(droppedFilters != null) {
	    	for(int i=0;i<droppedFilters.size();i++) {
	    		MessageFilter mf = (MessageFilter) droppedFilters.get(i);
		        mf.onRestartedConnection(ctx);
	    	}
	    }
	}

	public void addAsyncFilter(MessageFilter filter, AsyncMessageFilterCallback callback) throws DisconnectedException {
		filter.setAsyncCallback(callback);
		if(filter.matched()) {
			Logger.error(this, "addAsyncFilter() on a filter which is already matched: "+filter, new Exception("error"));
			filter.clearMatched();
		}
		filter.onStartWaiting();
		if(logMINOR) Logger.minor(this, "Adding async filter "+filter+" for "+callback);
		Message ret = null;
		if(filter.anyConnectionsDropped())
			throw new DisconnectedException();
		// Check to see whether the filter matches any of the recently _unclaimed messages
		// Drop any _unclaimed messages that the filter doesn't match that are also older than MAX_UNCLAIMED_FIFO_ITEM_LIFETIME
		long now = System.currentTimeMillis();
		long messageDropTime = now - MAX_UNCLAIMED_FIFO_ITEM_LIFETIME;
		long messageLifeTime = 0;
		synchronized (_filters) {
			if(logMINOR) Logger.minor(this, "Checking _unclaimed");
			for (ListIterator i = _unclaimed.listIterator(); i.hasNext();) {
				Message m = (Message) i.next();
				if (filter.match(m)) {
					i.remove();
					ret = m;
					if(logMINOR) Logger.debug(this, "Matching from _unclaimed");
					break;
				} else if (m.localInstantiationTime < messageDropTime) {
					i.remove();
					messageLifeTime = now - m.localInstantiationTime;
					if ((m.getSource()) instanceof PeerNode) {
						Logger.normal(this, "Dropping unclaimed from "+m.getSource().getPeer()+", lived "+TimeUtil.formatTime(messageLifeTime, 2, true)+" (age)"+": "+m);
					} else {
						Logger.normal(this, "Dropping unclaimed, lived "+TimeUtil.formatTime(messageLifeTime, 2, true)+" (age)"+": "+m);
					}
				}
			}
			if (ret == null) {
				if(logMINOR) Logger.minor(this, "Not in _unclaimed");
			    // Insert filter into filter list in order of timeout
				ListIterator i = _filters.listIterator();
				while (true) {
					if (!i.hasNext()) {
						i.add(filter);
						if(logMINOR) Logger.minor(this, "Added at end");
						break;
					}
					MessageFilter mf = (MessageFilter) i.next();
					if (mf.getTimeout() > filter.getTimeout()) {
						i.previous();
						i.add(filter);
						if(logMINOR) Logger.minor(this, "Added in middle - mf timeout="+mf.getTimeout()+" - my timeout="+filter.getTimeout());
						break;
					}
				}
			}
		}
		if(ret != null) {
			filter.setMessage(ret);
			filter.onMatched();
			filter.clearMatched();
		} else {
			// Might have disconnected between check above and locking _filters.
			if(filter.anyConnectionsDropped()) {
				synchronized(_filters) {
					_filters.remove(filter);
				}
				throw new DisconnectedException();
			}
		}
	}

	/**
	 * Wait for a filter to trigger, or timeout. Blocks until either the trigger is activated, or it times
	 * out, or the peer is disconnected.
	 * @param filter The filter to wait for.
	 * @param ctr Byte counter to add bytes from the message to.
	 * @return Either a message, or null if the filter timed out.
	 * @throws DisconnectedException If the single peer being waited for disconnects.
	 */
	public Message waitFor(MessageFilter filter, ByteCounter ctr) throws DisconnectedException {
		boolean logDEBUG = Logger.shouldLog(Logger.DEBUG, this);
		if(logDEBUG) Logger.debug(this, "Waiting for "+filter);
		long startTime = System.currentTimeMillis();
		if(filter.matched()) {
			Logger.error(this, "waitFor() on a filter which is already matched: "+filter, new Exception("error"));
			filter.clearMatched();
		}
		filter.onStartWaiting();
		Message ret = null;
		if(filter.anyConnectionsDropped())
			throw new DisconnectedException();
		// Check to see whether the filter matches any of the recently _unclaimed messages
		// Drop any _unclaimed messages that the filter doesn't match that are also older than MAX_UNCLAIMED_FIFO_ITEM_LIFETIME
		long now = System.currentTimeMillis();
		long messageDropTime = now - MAX_UNCLAIMED_FIFO_ITEM_LIFETIME;
		long messageLifeTime = 0;
		synchronized (_filters) {
			if(logMINOR) Logger.minor(this, "Checking _unclaimed");
			for (ListIterator i = _unclaimed.listIterator(); i.hasNext();) {
				Message m = (Message) i.next();
				if (filter.match(m)) {
					i.remove();
					ret = m;
					if(logMINOR) Logger.debug(this, "Matching from _unclaimed");
					break;
				} else if (m.localInstantiationTime < messageDropTime) {
					i.remove();
					messageLifeTime = now - m.localInstantiationTime;
					if ((m.getSource()) instanceof PeerNode) {
						Logger.normal(this, "Dropping unclaimed from "+m.getSource().getPeer()+", lived "+TimeUtil.formatTime(messageLifeTime, 2, true)+" (age)"+": "+m);
					} else {
						Logger.normal(this, "Dropping unclaimed, lived "+TimeUtil.formatTime(messageLifeTime, 2, true)+" (age)"+": "+m);
					}
				}
			}
			if (ret == null) {
				if(logMINOR) Logger.minor(this, "Not in _unclaimed");
			    // Insert filter into filter list in order of timeout
				ListIterator i = _filters.listIterator();
				while (true) {
					if (!i.hasNext()) {
						i.add(filter);
						if(logMINOR) Logger.minor(this, "Added at end");
						break;
					}
					MessageFilter mf = (MessageFilter) i.next();
					if (mf.getTimeout() > filter.getTimeout()) {
						i.previous();
						i.add(filter);
						if(logMINOR) Logger.minor(this, "Added in middle - mf timeout="+mf.getTimeout()+" - my timeout="+filter.getTimeout());
						break;
					}
				}
			}
		}
		long tEnd = System.currentTimeMillis();
		if(tEnd - now > 50) {
			if(tEnd - now > 3000)
				Logger.error(this, "waitFor _unclaimed iteration took "+(tEnd-now)+"ms with unclaimedFIFOSize of "+_unclaimed.size()+" for ret of "+ret);
			else
				if(logMINOR) Logger.minor(this, "waitFor _unclaimed iteration took "+(tEnd-now)+"ms with unclaimedFIFOSize of "+_unclaimed.size()+" for ret of "+ret);
		}
		// Unlock to wait on filter
		// Waiting on the filter won't release the outer lock
		// So we have to release it here
		if(ret == null) {	
			if(logMINOR) Logger.minor(this, "Waiting...");
			synchronized (filter) {
				try {
					// Precaution against filter getting matched between being added to _filters and
					// here - bug discovered by Mason
				    while(!(filter.matched() || (filter.droppedConnection() != null))) {
						long wait = filter.getTimeout()-System.currentTimeMillis();
						if(wait < 0)
							break;
						filter.wait(wait);
					}
				    if(filter.droppedConnection() != null)
				        throw new DisconnectedException();
				} catch (InterruptedException e) {
				}
				ret = filter.getMessage();
			}
			if(logDEBUG) Logger.debug(this, "Returning "+ret+" from "+filter);
		}
		if(!filter.matched()) {
			// We must remove it from _filters before we return, or when it is re-added,
			// it will be in the list twice, and that will cause other filters not to be
			// dropped.
			synchronized(_filters) {
				// Fortunately, it will be close to the beginning of the filters list, having
				// just timed out. That is assuming it hasn't already been removed; in that
				// case, this will be slower.
				_filters.remove(filter);
			}
		}
			// Matched a packet, unclaimed or after wait
			filter.setMessage(ret);
			filter.onMatched();
			filter.clearMatched();

		// Probably get rid...
//		if (Dijjer.getDijjer().getDumpMessageWaitTimes() != null) {
//			Dijjer.getDijjer().getDumpMessageWaitTimes().println(filter.toString() + "\t" + filter.getInitialTimeout() + "\t"
//					+ (System.currentTimeMillis() - startTime));
//			Dijjer.getDijjer().getDumpMessageWaitTimes().flush();
//		}
		long endTime = System.currentTimeMillis();
		if(logDEBUG) Logger.debug(this, "Returning in "+(endTime-startTime)+"ms");
		if((ctr != null) && (ret != null))
			ctr.receivedBytes(ret._receivedByteCount);
		return ret;
	}

	/**
	 * Send a Message to a PeerContext.
	 * @throws NotConnectedException If we are not currently connected to the node.
	 */
	public void send(PeerContext destination, Message m, ByteCounter ctr) throws NotConnectedException {
	    if(m.getSpec().isInternalOnly()) {
	        Logger.error(this, "Trying to send internal-only message "+m+" of spec "+m.getSpec(), new Exception("debug"));
	        return;
	    }
		destination.sendAsync(m, null, 0, ctr);
	}

	public void setDispatcher(Dispatcher d) {
		_dispatcher = d;
	}

	/**
	 * @return the number of received messages that are currently unclaimed
	 */
	public int getUnclaimedFIFOSize() {
		synchronized (_filters){
			return _unclaimed.size();
		}
	}
	
	public Map getUnclaimedFIFOMessageCounts() {
		Map messageCounts = new HashMap();
		synchronized(_filters) {
			for (ListIterator i = _unclaimed.listIterator(); i.hasNext();) {
				Message m = (Message) i.next();
				String messageName = m.getSpec().getName();
				Integer messageCount = (Integer) messageCounts.get(messageName);
				if (messageCount == null) {
					messageCounts.put(messageName, new Integer(1) );
				} else {
					messageCount = new Integer(messageCount.intValue() + 1);
					messageCounts.put(messageName, messageCount );
				}
			}
		}
		return messageCounts;
	}
}
