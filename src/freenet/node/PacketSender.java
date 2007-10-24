/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import java.util.LinkedList;
import java.util.TreeMap;
import java.util.Vector;

import org.tanukisoftware.wrapper.WrapperManager;

import freenet.io.comm.DMT;
import freenet.io.comm.Message;
import freenet.io.comm.NotConnectedException;
import freenet.io.comm.UdpSocketHandler;
import freenet.support.FileLoggerHook;
import freenet.support.Logger;
import freenet.support.OOMHandler;
import freenet.support.WouldBlockException;

/**
 * @author amphibian
 * 
 * Thread that sends a packet whenever:
 * - A packet needs to be resent immediately
 * - Acknowledgements or resend requests need to be sent urgently.
 */
public class PacketSender implements Runnable, Ticker {

	private static boolean logMINOR;
	private static boolean logDEBUG;
	
	/** Maximum time we will queue a message for in millseconds */
	static final int MAX_COALESCING_DELAY = 200;
	
	/** If opennet is enabled, and there are fewer than this many connections,
	 * we MAY attempt to contact old opennet peers (opennet peers we have 
	 * dropped from the routing table but kept around in case we can't connect). */
	static final int MIN_CONNECTIONS_TRY_OLD_OPENNET_PEERS = 5;
	
	/** We send connect attempts to old-opennet-peers no more than once every
	 * this many milliseconds. */
	static final int MIN_OLD_OPENNET_CONNECT_DELAY_NO_CONNS = 10*1000;
	
	/** We send connect attempts to old-opennet-peers no more than once every
	 * this many milliseconds. */
	static final int MIN_OLD_OPENNET_CONNECT_DELAY = 60*1000;
	
    final LinkedList resendPackets;
    /** ~= Ticker :) */
    private final TreeMap timedJobsByTime;
    final Thread myThread;
    final Node node;
    NodeStats stats;
    long lastClearedOldSwapChains;
    long lastReportedNoPackets;
    long lastReceivedPacketFromAnyNode;
    /** For watchdog. 32-bit to avoid locking. */
    volatile int lastTimeInSeconds;
    private long timeLastSentOldOpennetConnectAttempt;
    
    private Vector rpiTemp;
    private int[] rpiIntTemp;
    
    PacketSender(Node node) {
        resendPackets = new LinkedList();
        timedJobsByTime = new TreeMap();
        this.node = node;
        myThread = new Thread(this, "PacketSender thread for "+node.getDarknetPortNumber());
        myThread.setDaemon(true);
        myThread.setPriority(Thread.MAX_PRIORITY);
        logMINOR = Logger.shouldLog(Logger.MINOR, this);
        logDEBUG = Logger.shouldLog(Logger.DEBUG, this);
        rpiTemp = new Vector();
        rpiIntTemp = new int[64];
    }

    
    /**
     * The main purpose of this thread is to detect the lost-lock deadlocks that happen occasionally
     * on Sun VMs with NPTL enabled, and restart the node.
     * 
     * Consequently it MUST NOT LOCK ANYTHING. That further means it must not use the Logger, and even
     * System.err/System.out if they have been redirected.
     * @author root
     *
     */
    private class Watchdog implements Runnable {
    	
    	public void run() {
		    freenet.support.Logger.OSThread.logPID(this);
    		// Do not lock anything, or we may be caught up with a lost-lock deadlock.
    		while(true) {
    			try {
					Thread.sleep(5000);
				} catch (InterruptedException e) {
					// Ignore
				}
				long now = System.currentTimeMillis();
				long recordedTime = ((long)lastTimeInSeconds) * 1000;
				long diff = now - recordedTime;
				if((diff > 3*60*1000) && node.isHasStarted()) {
					FileLoggerHook flh = Node.logConfigHandler.getFileLoggerHook();
					boolean redirected = flh != null && !flh.hasRedirectedStdOutErrNoLock();
					if(!redirected)
						System.err.println("Restarting node: PacketSender froze for 3 minutes! ("+diff+ ')');
					
					try {
						if(node.isUsingWrapper()){
							WrapperManager.requestThreadDump();
							WrapperManager.restart();
						}else{
							if(!redirected)
								System.err.println("Exiting on deadlock, but not running in the wrapper! Please restart the node manually.");
							
							// No wrapper : we don't want to let it harm the network!
							node.exit("PacketSender deadlock");
						}
					} catch (Throwable t) {
						if(!Node.logConfigHandler.getFileLoggerHook().hasRedirectedStdOutErrNoLock()) {
							System.err.println("Error : can't restart the node : consider installing the wrapper. PLEASE REPORT THAT ERROR TO devl@freenetproject.org");
							t.printStackTrace();
						}
						node.exit("PacketSender deadlock and error");
					}
					
				}
    			
    		}
    	}
    }
    
    void start(NodeStats stats) {
    	this.stats = stats;
        Logger.normal(this, "Starting PacketSender");
        System.out.println("Starting PacketSender");
    	long now = System.currentTimeMillis();
    	long transition = Version.transitionTime;
    	if(now < transition) {
    		queueTimedJob(new Runnable() {
    			public void run() {
				    freenet.support.Logger.OSThread.logPID(this);
    				PeerNode[] nodes = node.peers.myPeers;
    				for(int i=0;i<nodes.length;i++) {
    					PeerNode pn = nodes[i];
    					pn.updateShouldDisconnectNow();
    				}
    			}
    		}, transition - now);
    	}
        lastTimeInSeconds = (int) (now / 1000);
        if(!node.disableHangCheckers) {
        	// Necessary because of sun JVM bugs when NPTL is enabled. Write once, debug everywhere!
        	Thread t1 = new Thread(new Watchdog(), "PacketSender watchdog");
        	t1.setDaemon(true);
        	t1.start();
        }
    	myThread.start();
    }
    
    public void run() {
	    freenet.support.Logger.OSThread.logPID(this);
        while(true) {
            lastReceivedPacketFromAnyNode = lastReportedNoPackets;
            try {
            	logMINOR = Logger.shouldLog(Logger.MINOR, this);
                realRun();
            } catch (OutOfMemoryError e) {
				OOMHandler.handleOOM(e);
				System.err.println("Will retry above failed operation...");
            } catch (Throwable t) {
                Logger.error(this, "Caught in PacketSender: "+t, t);
                System.err.println("Caught in PacketSender: "+t);
                t.printStackTrace();
            }
        }
    }

    private void realRun() {
        long now = System.currentTimeMillis();
        lastTimeInSeconds = (int) (now / 1000);
        PeerManager pm = node.peers;
        PeerNode[] nodes = pm.myPeers;
        // Run the time sensitive status updater separately
        for(int i = 0; i < nodes.length; i++) {
            PeerNode pn = nodes[i];
            // Only routing backed off nodes should need status updating since everything else
            // should get updated immediately when it's changed
            if(pn.getPeerNodeStatus() == PeerManager.PEER_NODE_STATUS_ROUTING_BACKED_OFF) {
                pn.setPeerNodeStatus(now);
            }
        }
        pm.maybeLogPeerNodeStatusSummary(now);
        pm.maybeUpdateOldestNeverConnectedPeerAge(now);
        stats.maybeUpdatePeerManagerUserAlertStats(now);
        stats.maybeUpdateNodeIOStats(now);
        pm.maybeUpdatePeerNodeRoutableConnectionStats(now);
        long nextActionTime = Long.MAX_VALUE;
        long oldTempNow = now;
        for(int i=0;i<nodes.length;i++) {
            PeerNode pn = nodes[i];
            lastReceivedPacketFromAnyNode =
                Math.max(pn.lastReceivedPacketTime(), lastReceivedPacketFromAnyNode);
            pn.maybeOnConnect();
            if(pn.isConnected()) {
            	// Is the node dead?
            	if(now - pn.lastReceivedPacketTime() > pn.maxTimeBetweenReceivedPackets()) {
            		Logger.normal(this, "Disconnecting from "+pn+" - haven't received packets recently");
            		pn.disconnected();
            		continue;
            	} else if(pn.isRoutable() && pn.noLongerRoutable()) {
            		// we don't disconnect but we mark it incompatible
            		pn.invalidate();
            		pn.setPeerNodeStatus(now);
            		Logger.normal(this, "shouldDisconnectNow has returned true : marking the peer as incompatible");
            		continue;
            	}
                
                boolean mustSend = false;
                
                // Any urgent notifications to send?
                long urgentTime = pn.getNextUrgentTime();
                // Should spam the logs, unless there is a deadlock
                if(urgentTime < Long.MAX_VALUE && logMINOR)
                	Logger.minor(this, "Next urgent time: "+urgentTime+" for "+pn.getPeer());
                if(urgentTime <= now) {
                	mustSend = true;
                } else {
                    nextActionTime = Math.min(nextActionTime, urgentTime);
                }
                
                // Any packets to resend?
                for(int j=0;j<2;j++) {
                    KeyTracker kt;
                    if(j == 0) kt = pn.getCurrentKeyTracker();
                    else if(j == 1) kt = pn.getPreviousKeyTracker();
                    else break; // impossible
                    if(kt == null) continue;
                    int[] tmp = kt.grabResendPackets(rpiTemp, rpiIntTemp);
                    if(tmp == null) continue;
                    rpiIntTemp = tmp;
                    for(int k=0;k<rpiTemp.size();k++) {
                        ResendPacketItem item = (ResendPacketItem) rpiTemp.get(k);
                        if(item == null) continue;
                        try {
                            if(logMINOR) Logger.minor(this, "Resending "+item.packetNumber+" to "+item.kt);
                            pn.getOutgoingMangler().resend(item);
                            mustSend = false;
                        } catch (KeyChangedException e) {
                            Logger.error(this, "Caught "+e+" resending packets to "+kt);
                            pn.requeueResendItems(rpiTemp);
                            break;
                        } catch (NotConnectedException e) {
                            Logger.normal(this, "Caught "+e+" resending packets to "+kt);
                            pn.requeueResendItems(rpiTemp);
                            break;
                        } catch (PacketSequenceException e) {
                        	Logger.error(this, "Caught "+e+" - disconnecting", e);
                        	pn.forceDisconnect();
						} catch (WouldBlockException e) {
							Logger.error(this, "Impossible: "+e, e);
						}
                    }
                    
                }

                // Any messages to send?
                MessageItem[] messages = null;
                messages = pn.grabQueuedMessageItems();
                if((messages != null) && (messages.length > 0)) {
                	long l = Long.MAX_VALUE;
                	// FIXME better alternate-transports support
                	int sz = FNPPacketMangler.HEADERS_LENGTH_ONE_MESSAGE;
                	sz += UdpSocketHandler.UDP_HEADERS_LENGTH;
                	for(int j=0;j<messages.length;j++) {
                		if(l > messages[j].submitted) l = messages[j].submitted;
                		sz += 2 + /* FIXME only 2? */ messages[j].getData(pn).length;
                	}
                	if((l + MAX_COALESCING_DELAY > now) && (sz < 1024 /* sensible size */)) {
                		// Don't send immediately
                		if(nextActionTime > (l+MAX_COALESCING_DELAY))
                			nextActionTime = l+MAX_COALESCING_DELAY;
                		pn.requeueMessageItems(messages, 0, messages.length, true, "TrafficCoalescing");
                	} else {
                		for(int j=0;j<messages.length;j++) {
                			if(logMINOR) Logger.minor(this, "PS Sending: "+(messages[j].msg == null ? "(not a Message)" : messages[j].msg.getSpec().getName()));
                		}
                		// Send packets, right now, blocking, including any active notifications
                		pn.getOutgoingMangler().processOutgoingOrRequeue(messages, pn, true, false);
                		continue;
                	}
                }
                
                if(mustSend) {
                    // Send them
                    try {
						pn.sendAnyUrgentNotifications();
					} catch (PacketSequenceException e) {
                    	Logger.error(this, "Caught "+e+" - while sending urgent notifications : disconnecting", e);
                    	pn.forceDisconnect();
					}
                }
                
                // Need to send a keepalive packet?
                if(now - pn.lastSentPacketTime() > Node.KEEPALIVE_INTERVAL) {
                	if(logMINOR) Logger.minor(this, "Sending keepalive");
                   	// Force packet to have a sequence number.
                   	Message m = DMT.createFNPVoid();
                   	pn.addToLocalNodeSentMessagesToStatistic(m);
                   	pn.getOutgoingMangler().processOutgoingOrRequeue(new MessageItem[] { new MessageItem(m, null, 0, null) }, pn, true, true);
                }
            } else {
                // Not connected
                // Send handshake if necessary
                long beforeHandshakeTime = System.currentTimeMillis();
                if(pn.shouldSendHandshake())
                    pn.getOutgoingMangler().sendHandshake(pn);
                if(pn.noContactDetails())
                	pn.startARKFetcher();
                long afterHandshakeTime = System.currentTimeMillis();
                if((afterHandshakeTime - beforeHandshakeTime) > (2*1000))
                    Logger.error(this, "afterHandshakeTime is more than 2 seconds past beforeHandshakeTime ("+(afterHandshakeTime - beforeHandshakeTime)+") in PacketSender working with "+pn.userToString());
            }
    		long tempNow = System.currentTimeMillis();
    		if((tempNow - oldTempNow) > (5*1000))
    			Logger.error(this, "tempNow is more than 5 seconds past oldTempNow ("+(tempNow - oldTempNow)+") in PacketSender working with "+pn.userToString());
    		oldTempNow = tempNow;
    	}
        
        // Consider sending connect requests to our opennet old-peers.
        // No point if they are NATed, of course... but we don't know whether they are.
        OpennetManager om = node.getOpennet();
        if(om != null) {
        	int connCount = node.peers.quickCountConnectedPeers();
        	int minDelay = connCount == 0 ? MIN_OLD_OPENNET_CONNECT_DELAY_NO_CONNS : MIN_OLD_OPENNET_CONNECT_DELAY;
        	if(logDEBUG)
        		Logger.debug(this, "Conns "+connCount+" minDelay "+minDelay+" old opennet peers "+om.countOldOpennetPeers()+" last sent "+(now - timeLastSentOldOpennetConnectAttempt)+" startup "+(now - node.startupTime));
        	if(now - timeLastSentOldOpennetConnectAttempt > minDelay &&
        			connCount <= MIN_CONNECTIONS_TRY_OLD_OPENNET_PEERS &&
        			om.countOldOpennetPeers() > 0 &&
        			now - node.startupTime > OpennetManager.DROP_STARTUP_DELAY) {
            	PeerNode pn = om.randomOldOpennetNode();
            	if(pn != null) {
            		if(logMINOR)
            			Logger.minor(this, "Sending old-opennet connect attempt to "+pn);
            		pn.getOutgoingMangler().sendHandshake(pn);
            		timeLastSentOldOpennetConnectAttempt = now;
            		if(pn.noContactDetails() && node.getPeerNodes().length > 0 && connCount > 0 && node.random.nextBoolean())
            			pn.startARKFetcher();
            	}
        	}
        }
    	
        if(now - lastClearedOldSwapChains > 10000) {
            node.lm.clearOldSwapChains();
            lastClearedOldSwapChains = now;
        }
        
        long oldNow = System.currentTimeMillis();

        // Send may have taken some time
        now = System.currentTimeMillis();
        lastTimeInSeconds = (int) (now / 1000);
        
        if((now - oldNow) > (10*1000))
            Logger.error(this, "now is more than 10 seconds past oldNow ("+(now - oldNow)+") in PacketSender");
        
        Vector jobsToRun = null;
        
        synchronized(timedJobsByTime) {
        	while(!timedJobsByTime.isEmpty()) {
       			Long tRun = (Long) timedJobsByTime.firstKey();
       			if(tRun.longValue() <= now) {
       				if(jobsToRun == null) jobsToRun = new Vector();
       				Object o = timedJobsByTime.remove(tRun);
       				if(o instanceof Runnable[]) {
       					Runnable[] r = (Runnable[]) o;
       					for(int i=0;i<r.length;i++)
       						jobsToRun.add(r[i]);
       				} else {
       					Runnable r = (Runnable) o;
       					jobsToRun.add(r);
       				}
       			} else {
       				// FIXME how accurately do we want ticker jobs to be scheduled?
       				// FIXME can they wait the odd 200ms?
       				break;
       			}
        	}
        }

        if(jobsToRun != null) {
        	for(int i=0;i<jobsToRun.size();i++) {
        		Runnable r = (Runnable) jobsToRun.get(i);
        		if(logMINOR) Logger.minor(this, "Running "+r);
        		if(r instanceof FastRunnable) {
        			// Run in-line
        			try {
        				r.run();
        			} catch (Throwable t) {
        				Logger.error(this, "Caught "+t+" running "+r, t);
        			}
        		} else {
        			try {
        				node.executor.execute(r, "Scheduled job: "+r);
					} catch (OutOfMemoryError e) {
						OOMHandler.handleOOM(e);
						System.err.println("Will retry above failed operation...");
						queueTimedJob(r, 200);
					} catch (Throwable t) {
						Logger.error(this, "Caught in PacketSender: "+t, t);
						System.err.println("Caught in PacketSender: "+t);
						t.printStackTrace();
					}
        		}
        	}
        }
        
        long sleepTime = nextActionTime - now;
        // MAX_COALESCING_DELAYms maximum sleep time - same as the maximum coalescing delay
        sleepTime = Math.min(sleepTime, MAX_COALESCING_DELAY);
        
        if(now - node.startupTime > 60*1000*5) {
            if(now - lastReceivedPacketFromAnyNode > Node.ALARM_TIME) {
                Logger.error(this, "Have not received any packets from any node in last "+Node.ALARM_TIME/1000+" seconds");
                lastReportedNoPackets = now;
            }
        }
        
        if(sleepTime > 0) {
        	// Update logging only when have time to do so
            logMINOR = Logger.shouldLog(Logger.MINOR, this);
            logDEBUG = Logger.shouldLog(Logger.DEBUG, this);
            try {
                synchronized(this) {
                	if(logMINOR) Logger.minor(this, "Sleeping for "+sleepTime);
                    wait(sleepTime);
                }
            } catch (InterruptedException e) {
                // Ignore, just wake up. Probably we got interrupt()ed
                // because a new packet came in.
            }
        }
	}

    /** Wake up, and send any queued packets. */
	void wakeUp() {
        // Wake up if needed
        synchronized(this) {
            notifyAll();
        }
    }

	public void queueTimedJob(Runnable job, long offset) {
		if(offset <= 0) {
			node.executor.execute(job, "Scheduled job: "+job);
			return;
		}
		long now = System.currentTimeMillis();
		Long l = new Long(offset + now);
		synchronized(timedJobsByTime) {
			Object o = timedJobsByTime.get(l);
			if(o == null) {
				timedJobsByTime.put(l, job);
			} else if(o instanceof Runnable) {
				timedJobsByTime.put(l, new Runnable[] { (Runnable)o, job });
			} else if(o instanceof Runnable[]) {
				Runnable[] r = (Runnable[]) o;
				Runnable[] jobs = new Runnable[r.length+1];
				System.arraycopy(r, 0, jobs, 0, r.length);
				jobs[jobs.length-1] = job;
				timedJobsByTime.put(l, jobs);
			}
		}
	}
}
