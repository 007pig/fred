/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client;

import java.io.IOException;
import java.util.LinkedList;
import java.util.ListIterator;

import com.db4o.ObjectContainer;
import com.db4o.ObjectSet;
import com.db4o.query.Query;

import freenet.client.async.ClientContext;
import freenet.client.async.DBJob;
import freenet.client.async.DBJobRunner;
import freenet.node.PrioRunnable;
import freenet.support.Executor;
import freenet.support.Logger;
import freenet.support.OOMHandler;
import freenet.support.OOMHook;
import freenet.support.io.NativeThread;

/**
 * The FEC queue. Uses a limited number of threads (at most one per core), a non-persistent queue,
 * a persistent queue (kept in the database), and a transient cache of the persistent queue.
 * Sorted by priority and then by time added.
 * @author toad
 */
public class FECQueue implements OOMHook {
	
	private transient LinkedList[] transientQueue;
	private transient LinkedList[] persistentQueueCache;
	private transient int maxPersistentQueueCacheSize;
	private transient int priorities;
	private transient DBJobRunner databaseJobRunner;
	private transient Executor executor;
	private transient ClientContext clientContext;
	private transient int runningFECThreads;
	private transient int fecPoolCounter;

	/** Called after creating or deserializing the FECQueue. Initialises all the transient fields. */
	public void init(int priorities, int maxCacheSize, DBJobRunner dbJobRunner, Executor exec, ClientContext clientContext) {
		this.priorities = priorities;
		this.maxPersistentQueueCacheSize = maxCacheSize;
		this.databaseJobRunner = dbJobRunner;
		this.executor = exec;
		this.clientContext = clientContext;
		transientQueue = new LinkedList[priorities];
		persistentQueueCache = new LinkedList[priorities];
		for(int i=0;i<priorities;i++) {
			transientQueue[i] = new LinkedList();
			persistentQueueCache[i] = new LinkedList();
		}
		OOMHandler.addOOMHook(this);
		queueCacheFiller();
	}
	
	private void queueCacheFiller() {
		databaseJobRunner.queue(cacheFillerJob, NativeThread.NORM_PRIORITY, false);
	}

	public void addToQueue(FECJob job, FECCodec codec, ObjectContainer container) {
		boolean logMINOR = Logger.shouldLog(Logger.MINOR, this);
		int maxThreads = getMaxRunningFECThreads();
		if(job.persistent) {
			container.set(job);
		}
		synchronized(this) {
			if(!job.persistent) {
				transientQueue[job.priority].addLast(job);
			} else {
				int totalAbove = 0;
				for(int i=0;i<job.priority;i++) {
					totalAbove += persistentQueueCache[i].size();
				}
				if(totalAbove >= maxPersistentQueueCacheSize) {
					// Don't add.
					if(logMINOR)
						Logger.minor(this, "Not adding persistent job to in-RAM cache, too many above it");
				} else {
					if(totalAbove + persistentQueueCache[job.priority].size() >= maxPersistentQueueCacheSize) {
						// Still don't add, within a priority it's oldest first.
						if(logMINOR)
							Logger.minor(this, "Not adding persistent job to in-RAM cache, too many at same priority");
					} else {
						persistentQueueCache[job.priority].addLast(job);
						int total = totalAbove + persistentQueueCache[job.priority].size();
						for(int i=job.priority+1;i<priorities;i++) {
							total += persistentQueueCache[i].size();
							while(total >= maxPersistentQueueCacheSize && !persistentQueueCache[i].isEmpty()) {
								if(logMINOR)
									Logger.minor(this, "Removing low priority job from cache, total now "+total);
								persistentQueueCache[job.priority].removeLast();
								total--;
							}
						}
					}
				}
			}
			if(runningFECThreads < maxThreads) {
				executor.execute(runner, "FEC Pool "+fecPoolCounter++);
				runningFECThreads++;
			}
			notifyAll();
		}
		if(logMINOR)
			Logger.minor(StandardOnionFECCodec.class, "Adding a new job to the queue.");
	}
	
	/**
	 * Runs on each thread.
	 * @author nextgens
	 */
	private final PrioRunnable runner = new PrioRunnable() {
		public void run() {
			freenet.support.Logger.OSThread.logPID(this);
			try {
				while(true) {
					final FECJob job;
					// Get a job
					synchronized (FECQueue.this) {
						job = getFECJobBlockingNoDBAccess();
					}

					// Encode it
					try {
						if (job.isADecodingJob)
							job.codec.realDecode(job.dataBlockStatus, job.checkBlockStatus, job.blockLength,
							        job.bucketFactory);
						else {
							job.codec.realEncode(job.dataBlocks, job.checkBlocks, job.blockLength, job.bucketFactory);
							// Update SplitFileBlocks from buckets if necessary
							if ((job.dataBlockStatus != null) || (job.checkBlockStatus != null)) {
								for (int i = 0; i < job.dataBlocks.length; i++)
									job.dataBlockStatus[i].setData(job.dataBlocks[i]);
								for (int i = 0; i < job.checkBlocks.length; i++)
									job.checkBlockStatus[i].setData(job.checkBlocks[i]);
							}
						}
					} catch (IOException e) {
						Logger.error(this, "BOH! ioe:" + e.getMessage());
					}

					// Call the callback
					try {
						if(!job.persistent) {
							if (job.isADecodingJob)
								job.callback.onDecodedSegment(null, clientContext);
							else
								job.callback.onEncodedSegment(null, clientContext);
						} else {
							databaseJobRunner.queue(new DBJob() {

								public void run(ObjectContainer container, ClientContext context) {
									if(job.isADecodingJob)
										job.callback.onDecodedSegment(container, clientContext);
									else
										job.callback.onEncodedSegment(container, clientContext);
									container.delete(job);
								}
								
							}, job.priority, false);
						}
					} catch (Throwable e) {
						Logger.error(this, "The callback failed!" + e.getMessage(), e);
					}
				}
			} catch (Throwable t) {
				Logger.error(this, "Caught "+t+" in "+this, t);
			}
			finally {
				synchronized (FECQueue.this) {
					runningFECThreads--;
				}
			}
		}

		public int getPriority() {
			return NativeThread.LOW_PRIORITY;
		}

	};

	private final DBJob cacheFillerJob = new DBJob() {

		public void run(ObjectContainer container, ClientContext context) {
			// Try to avoid accessing the database while synchronized on the FECQueue.
			while(true) {
				boolean addedAny = false;
				int totalCached = 0;
				for(short i=0;i<priorities;i++) {
					int grab = 0;
					synchronized(FECQueue.this) {
						int newCached = totalCached + persistentQueueCache[i].size();
						if(newCached >= maxPersistentQueueCacheSize) return;
						grab = maxPersistentQueueCacheSize - newCached;
					}
					Query query = container.query();
					query.constrain(FECJob.class);
					query.descend("priority").constrain(new Short(i));
					query.descend("queue").constrain(FECQueue.this);
					query.descend("addedTime").orderAscending();
					ObjectSet results = query.execute();
					if(results.hasNext()) {
						for(int j=0;j<grab && results.hasNext();j++) {
							FECJob job = (FECJob) results.next();
							synchronized(FECQueue.this) {
								if(persistentQueueCache[j].contains(job)) {
									j--;
									continue;
								}
								boolean added = false;
								for(ListIterator it = persistentQueueCache[j].listIterator();it.hasNext();) {
									FECJob cmp = (FECJob) it.next();
									if(cmp.addedTime >= job.addedTime) {
										it.previous();
										it.add(job);
										added = true;
										addedAny = true;
										break;
									}
								}
								if(!added) persistentQueueCache[j].addLast(job);
							}
						}
					}
				}
				if(!addedAny) {
					return;
				} else {
					int maxRunningThreads = getMaxRunningFECThreads();
					synchronized(FECQueue.this) {
						if(runningFECThreads < maxRunningThreads) {
							int queueSize = 0;
							for(int i=0;i<priorities;i++) {
								queueSize += persistentQueueCache[i].size();
								if(queueSize + runningFECThreads > maxRunningThreads) break;
							}
							if(queueSize + runningFECThreads < maxRunningThreads)
								maxRunningThreads = queueSize + runningFECThreads;
							while(runningFECThreads < maxRunningThreads) {
								executor.execute(runner, "FEC Pool "+fecPoolCounter++);
								runningFECThreads++;
							}
						}
					}
				}
			}
		}
		
	};
	
	private int maxRunningFECThreads = -1;

	private synchronized int getMaxRunningFECThreads() {
		if (maxRunningFECThreads != -1)
			return maxRunningFECThreads;
		String osName = System.getProperty("os.name");
		if(osName.indexOf("Windows") == -1 && (osName.toLowerCase().indexOf("mac os x") > 0) || (!NativeThread.usingNativeCode())) {
			// OS/X niceness is really weak, so we don't want any more background CPU load than necessary
			// Also, on non-Windows, we need the native threads library to be working.
			maxRunningFECThreads = 1;
		} else {
			// Most other OSs will have reasonable niceness, so go by RAM.
			Runtime r = Runtime.getRuntime();
			int max = r.availableProcessors(); // FIXME this may change in a VM, poll it
			long maxMemory = r.maxMemory();
			if(maxMemory < 256*1024*1024) {
				max = 1;
			} else {
				// Measured 11MB decode 8MB encode on amd64.
				// No more than 10% of available RAM, so 110MB for each extra processor.
				// No more than 3 so that we don't reach any FileDescriptor related limit
				max = Math.min(3, Math.min(max, (int) (Math.min(Integer.MAX_VALUE, maxMemory / (128*1024*1024)))));
			}
			maxRunningFECThreads = max;
		}
		Logger.minor(FECCodec.class, "Maximum FEC threads: "+maxRunningFECThreads);
		return maxRunningFECThreads;
	}

	/**
	 * Find a FEC job to run.
	 * @return null only if there are too many FEC threads running.
	 */
	protected synchronized FECJob getFECJobBlockingNoDBAccess() {
		while(true) {
			if(runningFECThreads > getMaxRunningFECThreads())
				return null;
			for(int i=0;i<priorities;i++) {
				if(!transientQueue[i].isEmpty())
					return (FECJob) transientQueue[i].removeFirst();
				if(!persistentQueueCache[i].isEmpty())
					return (FECJob) persistentQueueCache[i].removeFirst();
			}
			queueCacheFiller();
			try {
				wait();
			} catch (InterruptedException e) {
				// Ignore
			}
		}
	}

	public synchronized void handleLowMemory() throws Exception {
		maxRunningFECThreads = Math.min(1, maxRunningFECThreads - 1);
		notify(); // not notifyAll()
	}

	public synchronized void handleOutOfMemory() throws Exception {
		maxRunningFECThreads = 1;
		notifyAll();
	}
	
}
