/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Vector;

import freenet.client.ArchiveContext;
import freenet.client.FECCodec;
import freenet.client.FECJob;
import freenet.client.FailureCodeTracker;
import freenet.client.FetchContext;
import freenet.client.FetchException;
import freenet.client.Metadata;
import freenet.client.MetadataParseException;
import freenet.client.SplitfileBlock;
import freenet.client.FECCodec.StandardOnionFECCodecEncoderCallback;
import freenet.keys.CHKBlock;
import freenet.keys.CHKEncodeException;
import freenet.keys.ClientCHK;
import freenet.keys.ClientCHKBlock;
import freenet.keys.ClientKeyBlock;
import freenet.keys.NodeCHK;
import freenet.support.Logger;
import freenet.support.api.Bucket;
import freenet.support.io.BucketTools;

/**
 * A single segment within a SplitFileFetcher.
 * This in turn controls a large number of SplitFileFetcherSubSegment's, which are registered on the ClientRequestScheduler.
 */
public class SplitFileFetcherSegment implements StandardOnionFECCodecEncoderCallback {

	private static boolean logMINOR;
	final short splitfileType;
	final ClientCHK[] dataKeys;
	final ClientCHK[] checkKeys;
	final MinimalSplitfileBlock[] dataBuckets;
	final MinimalSplitfileBlock[] checkBuckets;
	final int[] dataRetries;
	final int[] checkRetries;
	final Vector subSegments;
	final int minFetched;
	final SplitFileFetcher parentFetcher;
	final ArchiveContext archiveContext;
	final FetchContext fetchContext;
	final long maxBlockLength;
	/** Has the segment finished processing? Irreversible. */
	private boolean finished;
	private boolean startedDecode;
	/** Bucket to store the data retrieved, after it has been decoded */
	private Bucket decodedData;
	/** Fetch context for block fetches */
	final FetchContext blockFetchContext;
	/** Recursion level */
	final int recursionLevel;
	private FetchException failureException;
	private int fatallyFailedBlocks;
	private int failedBlocks;
	private int fetchedBlocks;
	final FailureCodeTracker errors;
	private boolean finishing;
	
	private FECCodec codec;
	
	public SplitFileFetcherSegment(short splitfileType, ClientCHK[] splitfileDataKeys, ClientCHK[] splitfileCheckKeys, SplitFileFetcher fetcher, ArchiveContext archiveContext, FetchContext fetchContext, long maxTempLength, int recursionLevel) throws MetadataParseException, FetchException {
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		this.parentFetcher = fetcher;
		this.errors = new FailureCodeTracker(false);
		this.archiveContext = archiveContext;
		this.splitfileType = splitfileType;
		dataKeys = splitfileDataKeys;
		checkKeys = splitfileCheckKeys;
		if(splitfileType == Metadata.SPLITFILE_NONREDUNDANT) {
			minFetched = dataKeys.length;
		} else if(splitfileType == Metadata.SPLITFILE_ONION_STANDARD) {
			minFetched = dataKeys.length;
		} else throw new MetadataParseException("Unknown splitfile type"+splitfileType);
		finished = false;
		decodedData = null;
		dataBuckets = new MinimalSplitfileBlock[dataKeys.length];
		checkBuckets = new MinimalSplitfileBlock[checkKeys.length];
		for(int i=0;i<dataBuckets.length;i++) {
			dataBuckets[i] = new MinimalSplitfileBlock(i);
		}
		for(int i=0;i<checkBuckets.length;i++)
			checkBuckets[i] = new MinimalSplitfileBlock(i+dataBuckets.length);
		dataRetries = new int[dataKeys.length];
		checkRetries = new int[checkKeys.length];
		subSegments = new Vector();
		this.fetchContext = fetchContext;
		maxBlockLength = maxTempLength;
		blockFetchContext = new FetchContext(fetchContext, FetchContext.SPLITFILE_DEFAULT_BLOCK_MASK, true);
		this.recursionLevel = 0;
		if(logMINOR) Logger.minor(this, "Created "+this+" for "+parentFetcher);
		for(int i=0;i<dataKeys.length;i++)
			if(dataKeys[i] == null) throw new NullPointerException("Null: data block "+i);
		for(int i=0;i<checkKeys.length;i++)
			if(checkKeys[i] == null) throw new NullPointerException("Null: check block "+i);
	}

	public synchronized boolean isFinished() {
		return finished || parentFetcher.parent.isCancelled();
	}

	public synchronized boolean isFinishing() {
		return isFinished() || finishing;
	}
	
	/** Throw a FetchException, if we have one. Else do nothing. */
	public synchronized void throwError() throws FetchException {
		if(failureException != null)
			throw failureException;
	}
	
	/** Decoded length? */
	public long decodedLength() {
		return decodedData.size();
	}

	/** Write the decoded segment's data to an OutputStream */
	public long writeDecodedDataTo(OutputStream os, long truncateLength) throws IOException {
		long len = decodedData.size();
		if((truncateLength >= 0) && (truncateLength < len))
			len = truncateLength;
		BucketTools.copyTo(decodedData, os, Math.min(truncateLength, decodedData.size()));
		return len;
	}

	/** How many blocks have failed due to running out of retries? */
	public synchronized int failedBlocks() {
		return failedBlocks;
	}
	
	/** How many blocks have been successfully fetched? */
	public synchronized int fetchedBlocks() {
		return fetchedBlocks;
	}

	/** How many blocks failed permanently due to fatal errors? */
	public synchronized int fatallyFailedBlocks() {
		return fatallyFailedBlocks;
	}

	public void onSuccess(Bucket data, int blockNo, boolean dontNotify, SplitFileFetcherSubSegment seg, ClientKeyBlock block) {
		boolean decodeNow = false;
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		if(parentFetcher.parent instanceof ClientGetter)
			((ClientGetter)parentFetcher.parent).addKeyToBinaryBlob(block);
		// No need to unregister key, because it will be cleared in tripPendingKey().
		synchronized(this) {
			if(isFinished()) return;
			if(blockNo < dataKeys.length) {
				if(dataKeys[blockNo] == null) {
					Logger.error(this, "Block already finished: "+blockNo);
					return;
				}
				dataKeys[blockNo] = null;
				dataBuckets[blockNo].setData(data);
			} else if(blockNo < checkKeys.length + dataKeys.length) {
				blockNo -= dataKeys.length;
				if(checkKeys[blockNo] == null) {
					Logger.error(this, "Check block already finished: "+blockNo);
					return;
				}
				checkKeys[blockNo] = null;
				checkBuckets[blockNo].setData(data);
			} else
				Logger.error(this, "Unrecognized block number: "+blockNo, new Exception("error"));
			fetchedBlocks++;
			if(logMINOR) Logger.minor(this, "Fetched "+fetchedBlocks+" blocks in onSuccess("+blockNo+")");
			if(startedDecode) {
				return;
			} else {
				decodeNow = (fetchedBlocks >= minFetched);
				if(decodeNow) {
					startedDecode = true;
					finishing = true;
				}
			}
		}
		parentFetcher.parent.completedBlock(dontNotify);
		seg.possiblyRemoveFromParent();
		if(decodeNow) {
			removeSubSegments();
			decode();
		}
	}

	public void decode() {
		// Now decode
		if(logMINOR) Logger.minor(this, "Decoding "+SplitFileFetcherSegment.this);

		codec = FECCodec.getCodec(splitfileType, dataKeys.length, checkKeys.length);
		
		if(splitfileType != Metadata.SPLITFILE_NONREDUNDANT) {
			codec.addToQueue(new FECJob(codec, dataBuckets, checkBuckets, CHKBlock.DATA_LENGTH, fetchContext.bucketFactory, this, true));
			// Now have all the data blocks (not necessarily all the check blocks)
		}
	}
	
	public void onDecodedSegment() {
		try {
			if(isCollectingBinaryBlob()) {
				for(int i=0;i<dataBuckets.length;i++) {
					Bucket data = dataBuckets[i].getData();
					try {
						maybeAddToBinaryBlob(data, i, false);
					} catch (FetchException e) {
						fail(e);
						return;
					}
				}
			}
			decodedData = fetchContext.bucketFactory.makeBucket(-1);
			if(logMINOR) Logger.minor(this, "Copying data from data blocks");
			OutputStream os = decodedData.getOutputStream();
			for(int i=0;i<dataBuckets.length;i++) {
				SplitfileBlock status = dataBuckets[i];
				Bucket data = status.getData();
				BucketTools.copyTo(data, os, Long.MAX_VALUE);
			}
			if(logMINOR) Logger.minor(this, "Copied data");
			os.close();
			// Must set finished BEFORE calling parentFetcher.
			// Otherwise a race is possible that might result in it not seeing our finishing.
			finished = true;
			if(codec == null || !isCollectingBinaryBlob())
				parentFetcher.segmentFinished(SplitFileFetcherSegment.this);
		} catch (IOException e) {
			Logger.normal(this, "Caught bucket error?: "+e, e);
			finished = true;
			failureException = new FetchException(FetchException.BUCKET_ERROR);
			parentFetcher.segmentFinished(SplitFileFetcherSegment.this);
			return;
		}

		// Now heal

		/** Splitfile healing:
		 * Any block which we have tried and failed to download should be 
		 * reconstructed and reinserted.
		 */

		// Encode any check blocks we don't have
		if(codec != null) {
			codec.addToQueue(new FECJob(codec, dataBuckets, checkBuckets, 32768, fetchContext.bucketFactory, this, false));
		}
	}

	public void onEncodedSegment() {
		// Now insert *ALL* blocks on which we had at least one failure, and didn't eventually succeed
		for(int i=0;i<dataBuckets.length;i++) {
			boolean heal = false;
			Bucket data = dataBuckets[i].getData();
			if(dataRetries[i] > 0)
				heal = true;
			if(heal) {
				queueHeal(data);
			} else {
				dataBuckets[i].data.free();
				dataBuckets[i].data = null;
			}
			dataBuckets[i] = null;
			dataKeys[i] = null;
		}
		for(int i=0;i<checkBuckets.length;i++) {
			boolean heal = false;
			Bucket data = checkBuckets[i].getData();
			try {
				maybeAddToBinaryBlob(data, i, true);
			} catch (FetchException e) {
				fail(e);
				return;
			}
			if(checkRetries[i] > 0)
				heal = true;
			if(heal) {
				queueHeal(data);
			} else {
				checkBuckets[i].data.free();
			}
			checkBuckets[i] = null;
			checkKeys[i] = null;
		}
		// Defer the completion until we have generated healing blocks if we are collecting binary blobs.
		if(isCollectingBinaryBlob())
			parentFetcher.segmentFinished(SplitFileFetcherSegment.this);
	}

	boolean isCollectingBinaryBlob() {
		if(parentFetcher.parent instanceof ClientGetter) {
			ClientGetter getter = (ClientGetter) (parentFetcher.parent);
			return getter.collectingBinaryBlob();
		} else return false;
	}
	
	private void maybeAddToBinaryBlob(Bucket data, int i, boolean check) throws FetchException {
		if(parentFetcher.parent instanceof ClientGetter) {
			ClientGetter getter = (ClientGetter) (parentFetcher.parent);
			if(getter.collectingBinaryBlob()) {
				try {
					ClientCHKBlock block =
						ClientCHKBlock.encode(data, false, true, (short)-1, data.size());
					getter.addKeyToBinaryBlob(block);
				} catch (CHKEncodeException e) {
					Logger.error(this, "Failed to encode (collecting binary blob) "+(check?"check":"data")+" block "+i+": "+e, e);
					throw new FetchException(FetchException.INTERNAL_ERROR, "Failed to encode for binary blob: "+e);
				} catch (IOException e) {
					throw new FetchException(FetchException.BUCKET_ERROR, "Failed to encode for binary blob: "+e);
				}
			}
		}
	}

	private void queueHeal(Bucket data) {
		if(logMINOR) Logger.minor(this, "Queueing healing insert");
		fetchContext.healingQueue.queue(data);
	}
	
	/** This is after any retries and therefore is either out-of-retries or fatal */
	public synchronized void onFatalFailure(FetchException e, int blockNo, SplitFileFetcherSubSegment seg) {
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		if(logMINOR) Logger.minor(this, "Permanently failed block: "+blockNo+" on "+this+" : "+e, e);
		boolean allFailed;
		// Since we can't keep the key, we need to unregister for it at this point to avoid a memory leak
		NodeCHK key = getBlockNodeKey(blockNo);
		if(key != null) seg.unregisterKey(key);
		synchronized(this) {
			if(isFinishing()) return; // this failure is now irrelevant, and cleanup will occur on the decoder thread
			if(blockNo < dataKeys.length) {
				if(dataKeys[blockNo] == null) {
					Logger.error(this, "Block already finished: "+blockNo);
					return;
				}
				dataKeys[blockNo] = null;
			} else if(blockNo < checkKeys.length + dataKeys.length) {
				if(checkKeys[blockNo-dataKeys.length] == null) {
					Logger.error(this, "Check block already finished: "+blockNo);
					return;
				}
				checkKeys[blockNo-dataKeys.length] = null;
			} else
				Logger.error(this, "Unrecognized block number: "+blockNo, new Exception("error"));
			// :(
			if(e.isFatal()) {
				fatallyFailedBlocks++;
				parentFetcher.parent.fatallyFailedBlock();
			} else {
				failedBlocks++;
				parentFetcher.parent.failedBlock();
			}
			// Once it is no longer possible to have a successful fetch, fail...
			allFailed = failedBlocks + fatallyFailedBlocks > (dataKeys.length + checkKeys.length - minFetched);
		}
		if(allFailed)
			fail(new FetchException(FetchException.SPLITFILE_ERROR, errors));
		else
			seg.possiblyRemoveFromParent();
	}
	
	/** A request has failed non-fatally, so the block may be retried */
	public void onNonFatalFailure(FetchException e, int blockNo, SplitFileFetcherSubSegment seg) {
		int tries;
		int maxTries = blockFetchContext.maxNonSplitfileRetries;
		boolean failed = false;
		synchronized(this) {
			if(isFinished()) return;
			if(blockNo < dataKeys.length) {
				tries = ++dataRetries[blockNo];
				if(tries > maxTries && maxTries >= 0) failed = true; 
			} else {
				tries = ++checkRetries[blockNo-dataKeys.length];
				if(tries > maxTries && maxTries >= 0) failed = true;
			}
		}
		if(failed) {
			onFatalFailure(e, blockNo, seg);
			if(logMINOR)
				Logger.minor(this, "Not retrying block "+blockNo+" on "+this+" : tries="+tries+"/"+maxTries);
			return;
		}
		// If we are here we are going to retry
		SplitFileFetcherSubSegment sub = getSubSegment(tries);
		if(logMINOR)
			Logger.minor(this, "Retrying block "+blockNo+" on "+this+" : tries="+tries+"/"+maxTries+" : "+sub);
		sub.add(blockNo, false);
	}
	
	private SplitFileFetcherSubSegment getSubSegment(int retryCount) {
		SplitFileFetcherSubSegment sub;
		synchronized(this) {
			for(int i=0;i<subSegments.size();i++) {
				sub = (SplitFileFetcherSubSegment) subSegments.get(i);
				if(sub.retryCount == retryCount) return sub;
			}
			sub = new SplitFileFetcherSubSegment(this, retryCount);
			subSegments.add(sub);
		}
		return sub;
	}

	private void fail(FetchException e) {
		synchronized(this) {
			if(finished) return;
			finished = true;
			this.failureException = e;
			if(startedDecode) {
				Logger.error(this, "Failing with "+e+" but already started decode", e);
				return;
			}
			for(int i=0;i<dataBuckets.length;i++) {
				MinimalSplitfileBlock b = dataBuckets[i];
				if(b != null) {
					Bucket d = b.getData();
					if(d != null) d.free();
				}
				dataBuckets[i] = null;
			}
			for(int i=0;i<checkBuckets.length;i++) {
				MinimalSplitfileBlock b = checkBuckets[i];
				if(b != null) {
					Bucket d = b.getData();
					if(d != null) d.free();
				}
				checkBuckets[i] = null;
			}
		}
		removeSubSegments();
		parentFetcher.segmentFinished(this);
	}

	public void schedule() {
		try {
			SplitFileFetcherSubSegment seg = getSubSegment(0);
			for(int i=0;i<dataRetries.length+checkRetries.length;i++)
				seg.add(i, true);
			
			seg.schedule();
			if(logMINOR)
				Logger.minor(this, "scheduling "+seg+" : "+seg.blockNums);
		} catch (Throwable t) {
			Logger.error(this, "Caught "+t+" scheduling "+this, t);
			fail(new FetchException(FetchException.INTERNAL_ERROR, t));
		}
	}

	public void cancel() {
		fail(new FetchException(FetchException.CANCELLED));
	}

	public void onBlockSetFinished(ClientGetState state) {
		// Ignore; irrelevant
	}

	public void onTransition(ClientGetState oldState, ClientGetState newState) {
		// Ignore
	}

	public ClientCHK getBlockKey(int blockNum) {
		if(blockNum < 0) return null;
		else if(blockNum < dataKeys.length)
			return dataKeys[blockNum];
		else if(blockNum < dataKeys.length + checkKeys.length)
			return checkKeys[blockNum - dataKeys.length];
		else return null;
	}
	
	public NodeCHK getBlockNodeKey(int blockNum) {
		ClientCHK key = getBlockKey(blockNum);
		if(key != null) return key.getNodeCHK();
		else return null;
	}

	public synchronized void removeSeg(SplitFileFetcherSubSegment segment) {
		for(int i=0;i<subSegments.size();i++) {
			if(segment.equals(subSegments.get(i))) {
				subSegments.remove(i);
				i--;
			}
		}
	}

	private void removeSubSegments() {
		for(int i=0;i<subSegments.size();i++) {
			SplitFileFetcherSubSegment seg = (SplitFileFetcherSubSegment) subSegments.get(i);
			seg.kill();
		}
	}

}
