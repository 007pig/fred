/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.LinkedList;

import com.onionnetworks.fec.FECCode;
import com.onionnetworks.util.Buffer;

import freenet.node.PrioRunnable;
import freenet.support.Executor;
import freenet.support.Logger;
import freenet.support.OOMHandler;
import freenet.support.OOMHook;
import freenet.support.api.Bucket;
import freenet.support.api.BucketFactory;
import freenet.support.io.BucketTools;
import freenet.support.io.Closer;
import freenet.support.io.NativeThread;

/**
 * FEC (forward error correction) handler.
 * I didn't keep the old freenet.client.FEC* etc as it seemed grossly overengineered with
 * a lot of code there only because of API confusion.
 * @author root
 *
 */
public abstract class FECCodec implements OOMHook {

	// REDFLAG: Optimal stripe size? Smaller => less memory usage, but more JNI overhead

	private static int STRIPE_SIZE = 4096;
	static boolean logMINOR;
	FECCode fec;
	protected final int k, n;
	private final Executor executor;

	protected FECCodec(Executor executor, int k, int n) {
		this.executor = executor;
		this.k = k;
		this.n = n;
		
		OOMHandler.addOOMHook(this);
	}
	
	/**
	 * Get a codec where we know both the number of data blocks and the number
	 * of check blocks, and the codec type. Normally for decoding.
	 */
	public static FECCodec getCodec(short splitfileType, int dataBlocks, int checkBlocks, Executor executor) {
		if(splitfileType == Metadata.SPLITFILE_NONREDUNDANT)
			return null;
		if(splitfileType == Metadata.SPLITFILE_ONION_STANDARD)
			return StandardOnionFECCodec.getInstance(dataBlocks, checkBlocks, executor);
		else
			return null;
	}

	/**
	 * Get a codec where we know only the number of data blocks and the codec
	 * type. Normally for encoding.
	 */
	public static FECCodec getCodec(short splitfileType, int dataBlocks, Executor executor) {
		if(splitfileType == Metadata.SPLITFILE_NONREDUNDANT)
			return null;
		if(splitfileType == Metadata.SPLITFILE_ONION_STANDARD) {
			/**
			 * ALCHEMY: What we do know is that redundancy by FEC is much more efficient than 
			 * redundancy by simply duplicating blocks, for obvious reasons (see e.g. Wuala). But
			 * we have to have some redundancy at the duplicating blocks level because we do use
			 * some keys directly etc: we store an insert in 3 nodes. We also cache it on 20 nodes,
			 * but generally the key will fall out of the caches within days. So long term, it's 3.
			 * Multiplied by 2 here, makes 6. Used to be 1.5 * 3 = 4.5. Wuala uses 5, but that's 
			 * all FEC.
			 */
			int checkBlocks = dataBlocks * HighLevelSimpleClientImpl.SPLITFILE_CHECK_BLOCKS_PER_SEGMENT / HighLevelSimpleClientImpl.SPLITFILE_BLOCKS_PER_SEGMENT;
			if(dataBlocks >= HighLevelSimpleClientImpl.SPLITFILE_CHECK_BLOCKS_PER_SEGMENT) 
				checkBlocks = HighLevelSimpleClientImpl.SPLITFILE_CHECK_BLOCKS_PER_SEGMENT;
			return StandardOnionFECCodec.getInstance(dataBlocks, checkBlocks, executor);
		}
		else
			return null;
	}

	/**
	 * How many check blocks?
	 */
	public abstract int countCheckBlocks();

	protected void realDecode(SplitfileBlock[] dataBlockStatus, SplitfileBlock[] checkBlockStatus, int blockLength, BucketFactory bf) throws IOException {
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		if(logMINOR)
			Logger.minor(this, "Doing decode: " + dataBlockStatus.length + " data blocks, " + checkBlockStatus.length + " check blocks, block length " + blockLength + " with " + this, new Exception("debug"));
		if(dataBlockStatus.length + checkBlockStatus.length != n)
			throw new IllegalArgumentException();
		if(dataBlockStatus.length != k)
			throw new IllegalArgumentException();
		Buffer[] packets = new Buffer[k];
		Bucket[] buckets = new Bucket[n];
		DataInputStream[] readers = new DataInputStream[n];
		OutputStream[] writers = new OutputStream[k];
		int numberToDecode = 0; // can be less than n-k

		try {

			byte[] realBuffer = new byte[k * STRIPE_SIZE];

			int[] packetIndexes = new int[k];
			for(int i = 0; i < packetIndexes.length; i++)
				packetIndexes[i] = -1;

			int idx = 0;

			for(int i = 0; i < k; i++)
				packets[i] = new Buffer(realBuffer, i * STRIPE_SIZE,
					STRIPE_SIZE);

			// Shortcut.
			// Due to the not-fetching-last-block code, we need to check here,
			// rather than relying on numberToDecode (since the last data block won't be part of numberToDecode).
			
			boolean needDecode = false;
			for(int i = 0; i < dataBlockStatus.length;i++) {
				if(dataBlockStatus[i].getData() == null)
					needDecode = true;
			}
			
			if(!needDecode) return;
			
			for(int i = 0; i < dataBlockStatus.length; i++) {
				buckets[i] = dataBlockStatus[i].getData();
				if(buckets[i] == null) {
					buckets[i] = bf.makeBucket(blockLength);
					writers[i] = buckets[i].getOutputStream();
					if(logMINOR)
						Logger.minor(this, "writers[" + i + "] != null");
					readers[i] = null;
					numberToDecode++;
				}
				else {
					long sz = buckets[i].size();
					if(sz < blockLength) {
						if(i != dataBlockStatus.length - 1)
							throw new IllegalArgumentException("All buckets except the last must be the full size but data bucket " + i + " of " + dataBlockStatus.length + " (" + dataBlockStatus[i] + ") is " + sz + " not " + blockLength);
						if(sz < blockLength) {
							// FIXME NOT FETCHING LAST BLOCK
//							buckets[i] = BucketTools.pad(buckets[i], blockLength, bf, (int) sz);
							buckets[i].free();
							buckets[i] = bf.makeBucket(blockLength);
							writers[i] = buckets[i].getOutputStream();
							if(logMINOR)
								Logger.minor(this, "writers[" + i + "] != null (NOT PADDING)");
							readers[i] = null;
							numberToDecode++;
						}
						else
							throw new IllegalArgumentException("Too big: " + sz + " bigger than " + blockLength);
					} else {
						if(logMINOR)
							Logger.minor(this, "writers[" + i + "] = null (already filled)");
						writers[i] = null;
						readers[i] = new DataInputStream(buckets[i].getInputStream());
						packetIndexes[idx++] = i;
					}
				}
			}
			for(int i = 0; i < checkBlockStatus.length; i++) {
				buckets[i + k] = checkBlockStatus[i].getData();
				if(buckets[i + k] == null)
					readers[i + k] = null;
				else {
					readers[i + k] = new DataInputStream(buckets[i + k].getInputStream());
					if(idx < k)
						packetIndexes[idx++] = i + k;
				}
			}

			if(idx < k)
				throw new IllegalArgumentException("Must have at least k packets (k=" + k + ",idx=" + idx + ')');

			if(logMINOR)
				for(int i = 0; i < packetIndexes.length; i++)
					Logger.minor(this, "[" + i + "] = " + packetIndexes[i]);

			if(numberToDecode > 0)
				// Do the (striped) decode

				for(int offset = 0; offset < blockLength; offset += STRIPE_SIZE) {
					// Read the data in first
					for(int i = 0; i < k; i++) {
						int x = packetIndexes[i];
						readers[x].readFully(realBuffer, i * STRIPE_SIZE,
							STRIPE_SIZE);
					}
					// Do the decode
					// Not shuffled
					int[] disposableIndexes = new int[packetIndexes.length];
					System.arraycopy(packetIndexes, 0, disposableIndexes, 0,
						packetIndexes.length);
					fec.decode(packets, disposableIndexes);
					// packets now contains an array of decoded blocks, in order
					// Write the data out
					for(int i = 0; i < k; i++)
						if(writers[i] != null)
							writers[i].write(realBuffer, i * STRIPE_SIZE,
								STRIPE_SIZE);
				}

		}
		finally {
			for(int i = 0; i < k; i++)
				Closer.close(writers[i]);
			for(int i = 0; i < n; i++)
				Closer.close(readers[i]);
		}
		// Set new buckets only after have a successful decode.
		// Note that the last data bucket will be overwritten padded.
		for(int i = 0; i < dataBlockStatus.length; i++) {
			Bucket data = buckets[i];
			if(data.size() != blockLength)
				throw new IllegalStateException("Block " + i + ": " + data + " : " + dataBlockStatus[i] + " length " + data.size() + " whereas blockLength="+blockLength);
			dataBlockStatus[i].setData(data);
		}
	}

	/**
	 * Do the actual encode.
	 */
	protected void realEncode(Bucket[] dataBlockStatus,
		Bucket[] checkBlockStatus, int blockLength, BucketFactory bf)
		throws IOException {
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		//		Runtime.getRuntime().gc();
//		Runtime.getRuntime().runFinalization();
//		Runtime.getRuntime().gc();
//		Runtime.getRuntime().runFinalization();
		long memUsedAtStart = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
		if(logMINOR) {
			Logger.minor(this, "Memory in use at start: " + memUsedAtStart + " max=" + Runtime.getRuntime().maxMemory());
			Logger.minor(this, "Doing encode: " + dataBlockStatus.length + " data blocks, " + checkBlockStatus.length + " check blocks, block length " + blockLength + " with " + this);
		}
		if((dataBlockStatus.length + checkBlockStatus.length != n) ||
			(dataBlockStatus.length != k))
			throw new IllegalArgumentException("Data blocks: " + dataBlockStatus.length + ", Check blocks: " + checkBlockStatus.length + ", n: " + n + ", k: " + k);
		Buffer[] dataPackets = new Buffer[k];
		Buffer[] checkPackets = new Buffer[n - k];
		Bucket[] buckets = new Bucket[n];
		DataInputStream[] readers = new DataInputStream[k];
		OutputStream[] writers = new OutputStream[n - k];

		Bucket toFree = null;
		
		try {

			int[] toEncode = new int[n - k];
			int numberToEncode = 0; // can be less than n-k

			byte[] realBuffer = new byte[n * STRIPE_SIZE];

			for(int i = 0; i < k; i++)
				dataPackets[i] = new Buffer(realBuffer, i * STRIPE_SIZE,
					STRIPE_SIZE);
			for(int i = 0; i < n - k; i++)
				checkPackets[i] = new Buffer(realBuffer, (i + k) * STRIPE_SIZE,
					STRIPE_SIZE);

			for(int i = 0; i < dataBlockStatus.length; i++) {
				buckets[i] = dataBlockStatus[i];
				long sz = buckets[i].size();
				if(sz < blockLength) {
					if(i != dataBlockStatus.length - 1)
						throw new IllegalArgumentException("All buckets except the last must be the full size");
					if(sz < blockLength) {
						buckets[i] = BucketTools.pad(buckets[i], blockLength, bf, (int) sz);
						toFree = buckets[i];
					} else
						throw new IllegalArgumentException("Too big: " + sz + " bigger than " + blockLength);
				}
				readers[i] = new DataInputStream(buckets[i].getInputStream());
			}

			for(int i = 0; i < checkBlockStatus.length; i++) {
				buckets[i + k] = checkBlockStatus[i];
				if(buckets[i + k] == null) {
					buckets[i + k] = bf.makeBucket(blockLength);
					writers[i] = buckets[i + k].getOutputStream();
					toEncode[numberToEncode++] = i + k;
				}
				else
					writers[i] = null;
			}

			//			Runtime.getRuntime().gc();
//			Runtime.getRuntime().runFinalization();
//			Runtime.getRuntime().gc();
//			Runtime.getRuntime().runFinalization();
			long memUsedBeforeEncodes = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
			if(logMINOR)
				Logger.minor(this, "Memory in use before encodes: " + memUsedBeforeEncodes);

			if(numberToEncode > 0)
				// Do the (striped) encode

				for(int offset = 0; offset < blockLength; offset += STRIPE_SIZE) {
					long memUsedBeforeRead = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
					if(logMINOR)
						Logger.minor(this, "Memory in use before read: " + memUsedBeforeRead);
					// Read the data in first
					for(int i = 0; i < k; i++)
						readers[i].readFully(realBuffer, i * STRIPE_SIZE,
							STRIPE_SIZE);
					// Do the encode
					// Not shuffled
					long startTime = System.currentTimeMillis();
					//					Runtime.getRuntime().gc();
//					Runtime.getRuntime().runFinalization();
//					Runtime.getRuntime().gc();
//					Runtime.getRuntime().runFinalization();
					long memUsedBeforeStripe = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
					if(logMINOR)
						Logger.minor(this, "Memory in use before stripe: " + memUsedBeforeStripe);
					fec.encode(dataPackets, checkPackets, toEncode);
					//					Runtime.getRuntime().gc();
//					Runtime.getRuntime().runFinalization();
//					Runtime.getRuntime().gc();
//					Runtime.getRuntime().runFinalization();
					long memUsedAfterStripe = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
					if(logMINOR)
						Logger.minor(this, "Memory in use after stripe: " + memUsedAfterStripe);
					long endTime = System.currentTimeMillis();
					if(logMINOR)
						Logger.minor(this, "Stripe encode took " + (endTime - startTime) + "ms for k=" + k + ", n=" + n + ", stripeSize=" + STRIPE_SIZE);
					// packets now contains an array of decoded blocks, in order
					// Write the data out
					for(int i = k; i < n; i++)
						if(writers[i - k] != null)
							writers[i - k].write(realBuffer, i * STRIPE_SIZE,
								STRIPE_SIZE);
				}

		}
		finally {
			for(int i = 0; i < k; i++)
				Closer.close(readers[i]);
			for(int i = 0; i < n - k; i++)
				Closer.close(writers[i]);
			if(toFree != null)
				toFree.free();
		}
		// Set new buckets only after have a successful decode.
		for(int i = 0; i < checkBlockStatus.length; i++) {
			Bucket data = buckets[i + k];
			if(data == null)
				throw new NullPointerException();
			checkBlockStatus[i] = data;
		}
	}

	/**
	 * The method used to submit {@link FECJob}s to the pool
	 * 
	 * @author Florent Daigni&egrave;re &lt;nextgens@freenetproject.org&gt;
	 * 
	 * @param FECJob
	 */
	public void addToQueue(FECJob job) {
		addToQueue(job, this, executor);
	}

	public static void addToQueue(FECJob job, FECCodec codec, Executor executor) {
		int maxThreads = getMaxRunningFECThreads();
		synchronized(_awaitingJobs) {
			_awaitingJobs.addFirst(job);
			if(runningFECThreads < maxThreads) {
				executor.execute(fecRunner, "FEC Pool(" + (fecPoolCounter++) + ")");
				runningFECThreads++;
			}
			_awaitingJobs.notifyAll();
		}
		if(logMINOR)
			Logger.minor(StandardOnionFECCodec.class, "Adding a new job to the queue (" + _awaitingJobs.size() + ").");
	}
	private static final LinkedList _awaitingJobs = new LinkedList();
	private static final FECRunner fecRunner = new FECRunner();
	private static int runningFECThreads;
	private static int fecPoolCounter;
	
	private synchronized static int getMaxRunningFECThreads() {
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
	
	private static int maxRunningFECThreads = -1;

	/**
	 * A private Thread started by {@link FECCodec}...
	 * 
	 * @author Florent Daigni&egrave;re &lt;nextgens@freenetproject.org&gt;
	 */
	private static class FECRunner implements PrioRunnable {

		public void run() {
			freenet.support.Logger.OSThread.logPID(this);
			try {
				while(true) {
					FECJob job = null;
					// Get a job
					synchronized (_awaitingJobs) {
						while (_awaitingJobs.isEmpty()) {
							_awaitingJobs.wait(Integer.MAX_VALUE);
							if (runningFECThreads > getMaxRunningFECThreads())
								return;
						}
						job = (FECJob) _awaitingJobs.removeLast();
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
						Logger.error(this, "BOH! ioe:" + e.getMessage(), e);
					}

					// Call the callback
					try {
						if (job.isADecodingJob)
							job.callback.onDecodedSegment();
						else
							job.callback.onEncodedSegment();
					} catch (Throwable e) {
						Logger.error(this, "The callback failed!" + e.getMessage(), e);
					}
				}
			} catch (Throwable t) {
				Logger.error(this, "Caught "+t+" in "+this, t);
			}
			finally {
				synchronized (_awaitingJobs) {
					runningFECThreads--;
				}
			}
		}

		public int getPriority() {
			return NativeThread.LOW_PRIORITY;
		}
	}

	public void handleLowMemory() throws Exception {
		synchronized (_awaitingJobs) {
			maxRunningFECThreads = Math.max(1, maxRunningFECThreads - 1);
			_awaitingJobs.notify(); // not notifyAll()
		}
	}

	public void handleOutOfMemory() throws Exception {
		synchronized (_awaitingJobs) {
			maxRunningFECThreads = 1;
			_awaitingJobs.notifyAll();
		}
	}

	/**
	 * An interface wich has to be implemented by FECJob submitters
	 * 
	 * @author Florent Daigni&egrave;re &lt;nextgens@freenetproject.org&gt;
	 * 
	 * WARNING: the callback is expected to release the thread !
	 */
	public interface StandardOnionFECCodecEncoderCallback {

		public void onEncodedSegment();

		public void onDecodedSegment();
	}
}
