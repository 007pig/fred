package freenet.client.async;

import freenet.client.ArchiveManager.ARCHIVE_TYPE;
import java.io.IOException;
import java.net.MalformedURLException;
import java.util.HashMap;

import freenet.client.InsertBlock;
import freenet.client.InsertContext;
import freenet.client.InsertException;
import freenet.client.Metadata;
import freenet.client.MetadataUnresolvedException;
import freenet.client.events.FinishedCompressionEvent;
import freenet.client.events.StartedCompressionEvent;
import freenet.keys.BaseClientKey;
import freenet.keys.CHKBlock;
import freenet.keys.FreenetURI;
import freenet.keys.SSKBlock;
import freenet.support.Logger;
import freenet.support.OOMHandler;
import freenet.support.SimpleFieldSet;
import freenet.support.api.Bucket;
import freenet.support.compress.CompressionOutputSizeException;
import freenet.support.compress.Compressor.COMPRESSOR_TYPE;
import freenet.support.io.BucketChainBucketFactory;
import freenet.support.io.BucketTools;

/**
 * Attempt to insert a file. May include metadata.
 * 
 * This stage:
 * Attempt to compress the file. Off-thread if it will take a while.
 * Then hand it off to SimpleFileInserter.
 */
class SingleFileInserter implements ClientPutState {

	private static boolean logMINOR;
	final BaseClientPutter parent;
	final InsertBlock block;
	final InsertContext ctx;
	final boolean metadata;
	final PutCompletionCallback cb;
	final boolean getCHKOnly;
	final ARCHIVE_TYPE archiveType;
	COMPRESSOR_TYPE compressorUsed;
	/** If true, we are not the top level request, and should not
	 * update our parent to point to us as current put-stage. */
	private final boolean reportMetadataOnly;
	public final Object token;
	private final boolean freeData; // this is being set, but never read ???
	private final String targetFilename;
	private final boolean earlyEncode;

	/**
	 * @param parent
	 * @param cb
	 * @param block
	 * @param metadata
	 * @param ctx
	 * @param dontCompress
	 * @param getCHKOnly
	 * @param reportMetadataOnly If true, don't insert the metadata, just report it.
	 * @param insertAsArchiveManifest If true, insert the metadata as an archive manifest.
	 * @param freeData If true, free the data when possible.
	 * @param targetFilename 
	 * @param earlyEncode If true, try to get a URI as quickly as possible.
	 * @throws InsertException
	 */
	SingleFileInserter(BaseClientPutter parent, PutCompletionCallback cb, InsertBlock block, 
			boolean metadata, InsertContext ctx, boolean dontCompress, 
			boolean getCHKOnly, boolean reportMetadataOnly, Object token, ARCHIVE_TYPE archiveType, 
			boolean freeData, String targetFilename, boolean earlyEncode) throws InsertException {
		this.earlyEncode = earlyEncode;
		this.reportMetadataOnly = reportMetadataOnly;
		this.token = token;
		this.parent = parent;
		this.block = block;
		this.ctx = ctx;
		this.metadata = metadata;
		this.cb = cb;
		this.getCHKOnly = getCHKOnly;
		this.archiveType = archiveType;
		this.freeData = freeData;
		this.targetFilename = targetFilename;
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
	}
	
	public void start(SimpleFieldSet fs) throws InsertException {
		if(fs != null) {
			String type = fs.get("Type");
			if(type.equals("SplitHandler")) {
				// Try to reconstruct SplitHandler.
				// If we succeed, we bypass both compression and FEC encoding!
				try {
					SplitHandler sh = new SplitHandler();
					sh.start(fs, false);
					cb.onTransition(this, sh);
					sh.schedule();
					return;
				} catch (ResumeException e) {
					Logger.error(this, "Failed to restore: "+e, e);
				}
			}
		}
		// Run off thread in any case
		OffThreadCompressor otc = new OffThreadCompressor();
		ctx.executor.execute(otc, "Compressor for " + this);
	}

	// Use a mutex to serialize compression (limit memory usage/IO)
	// Of course it doesn't make any sense on multi-core systems.
	private static final Object compressorSync = new Object();
	
	private  class OffThreadCompressor implements Runnable {
		public void run() {
		    freenet.support.Logger.OSThread.logPID(this);
			try {
				tryCompress();
			} catch (InsertException e) {
				cb.onFailure(e, SingleFileInserter.this);
            } catch (OutOfMemoryError e) {
				OOMHandler.handleOOM(e);
				System.err.println("OffThreadCompressor thread above failed.");
				// Might not be heap, so try anyway
				cb.onFailure(new InsertException(InsertException.INTERNAL_ERROR, e, null), SingleFileInserter.this);
            } catch (Throwable t) {
                Logger.error(this, "Caught in OffThreadCompressor: "+t, t);
                System.err.println("Caught in OffThreadCompressor: "+t);
                t.printStackTrace();
                // Try to fail gracefully
				cb.onFailure(new InsertException(InsertException.INTERNAL_ERROR, t, null), SingleFileInserter.this);
			}
		}
	}
	
	private void tryCompress() throws InsertException {
		synchronized(compressorSync) {
		// First, determine how small it needs to be
		Bucket origData = block.getData();
		Bucket data = origData;
		int blockSize;
		int oneBlockCompressedSize;
		boolean dontCompress = ctx.dontCompress;
		
		long origSize = data.size();
		String type = block.desiredURI.getKeyType();
		if(type.equals("SSK") || type.equals("KSK") || type.equals("USK")) {
			blockSize = SSKBlock.DATA_LENGTH;
			oneBlockCompressedSize = SSKBlock.MAX_COMPRESSED_DATA_LENGTH;
		} else if(type.equals("CHK")) {
			blockSize = CHKBlock.DATA_LENGTH;
			oneBlockCompressedSize = CHKBlock.MAX_COMPRESSED_DATA_LENGTH;
		} else {
			throw new InsertException(InsertException.INVALID_URI, "Unknown key type: "+type, null);
		}
		
		COMPRESSOR_TYPE bestCodec = null;
		Bucket bestCompressedData = null;

		boolean tryCompress = (origSize > blockSize) && (!ctx.dontCompress) && (!dontCompress);
		if(tryCompress) {
			if(logMINOR) Logger.minor(this, "Attempt to compress the data");
			// Try to compress the data.
			// Try each algorithm, starting with the fastest and weakest.
			// Stop when run out of algorithms, or the compressed data fits in a single block.
				for(COMPRESSOR_TYPE comp : COMPRESSOR_TYPE.values()) {
				try {
					if(logMINOR)
						Logger.minor(this, "Attempt to compress using " + comp);
					// Only produce if we are compressing *the original data*
					if(parent == cb)
						ctx.eventProducer.produceEvent(new StartedCompressionEvent(comp));
					Bucket result;
					result = comp.compress(origData, new BucketChainBucketFactory(ctx.persistentBucketFactory, CHKBlock.DATA_LENGTH), origData.size());
					if(result.size() < oneBlockCompressedSize) {
						bestCodec = comp;
						if(bestCompressedData != null)
							bestCompressedData.free();
						bestCompressedData = result;
						continue;
					}
					if((bestCompressedData != null) && (result.size() < bestCompressedData.size())) {
						bestCompressedData.free();
						bestCompressedData = result;
						bestCodec = comp;
					} else if((bestCompressedData == null) && (result.size() < data.size())) {
						bestCompressedData = result;
						bestCodec = comp;
					} else
						result.free();

				} catch(IOException e) {
				throw new InsertException(InsertException.BUCKET_ERROR, e, null);
				} catch(CompressionOutputSizeException e) {
				// Impossible
				throw new Error(e);
			}
		}
		}
		boolean shouldFreeData = false;
		if(bestCompressedData != null) {
			long compressedSize = bestCompressedData.size();
			if(logMINOR) Logger.minor(this, "The best compression algorithm is "+bestCodec+ " we have gained "+ (100-(compressedSize*100/origSize)) +"% ! ("+origSize+'/'+compressedSize+')');
			data = bestCompressedData;
			shouldFreeData = true;
			compressorUsed = bestCodec;
		}
		
		if(parent == cb) {
			if(tryCompress)
				ctx.eventProducer.produceEvent(new FinishedCompressionEvent(bestCodec == null ? -1 : bestCodec.metadataID, origSize, data.size()));
			if(logMINOR) Logger.minor(this, "Compressed "+origSize+" to "+data.size()+" on "+this);
		}
		
		// Compressed data
		
		// Insert it...
		short codecNumber = bestCodec == null ? -1 : bestCodec.metadataID;
		long compressedDataSize = data.size();
		boolean fitsInOneBlockAsIs = bestCodec == null ? compressedDataSize < blockSize : compressedDataSize < oneBlockCompressedSize;
		boolean fitsInOneCHK = bestCodec == null ? compressedDataSize < CHKBlock.DATA_LENGTH : compressedDataSize < CHKBlock.MAX_COMPRESSED_DATA_LENGTH;

		if((fitsInOneBlockAsIs || fitsInOneCHK) && block.getData().size() > Integer.MAX_VALUE)
			throw new InsertException(InsertException.INTERNAL_ERROR, "2GB+ should not encode to one block!", null);

		boolean noMetadata = ((block.clientMetadata == null) || block.clientMetadata.isTrivial()) && targetFilename == null;
		if(noMetadata && archiveType == null) {
			if(fitsInOneBlockAsIs) {
				// Just insert it
				ClientPutState bi =
					createInserter(parent, data, codecNumber, block.desiredURI, ctx, cb, metadata, (int)block.getData().size(), -1, getCHKOnly, true, true);
				cb.onTransition(this, bi);
				bi.schedule();
				cb.onBlockSetFinished(this);
				return;
			}
		}
		if (fitsInOneCHK) {
			// Insert single block, then insert pointer to it
			if(reportMetadataOnly) {
				SingleBlockInserter dataPutter = new SingleBlockInserter(parent, data, codecNumber, FreenetURI.EMPTY_CHK_URI, ctx, cb, metadata, (int)origSize, -1, getCHKOnly, true, true, token);
				Metadata meta = makeMetadata(archiveType, bestCodec, dataPutter.getURI());
				cb.onMetadata(meta, this);
				cb.onTransition(this, dataPutter);
				dataPutter.schedule();
				cb.onBlockSetFinished(this);
			} else {
				MultiPutCompletionCallback mcb = 
					new MultiPutCompletionCallback(cb, parent, token);
				SingleBlockInserter dataPutter = new SingleBlockInserter(parent, data, codecNumber, FreenetURI.EMPTY_CHK_URI, ctx, mcb, metadata, (int)origSize, -1, getCHKOnly, true, false, token);
				Metadata meta = makeMetadata(archiveType, bestCodec, dataPutter.getURI());
				Bucket metadataBucket;
				try {
					metadataBucket = BucketTools.makeImmutableBucket(ctx.bf, meta.writeToByteArray());
				} catch (IOException e) {
					Logger.error(this, "Caught "+e, e);
					throw new InsertException(InsertException.BUCKET_ERROR, e, null);
				} catch (MetadataUnresolvedException e) {
					// Impossible, we're not inserting a manifest.
					Logger.error(this, "Caught "+e, e);
					throw new InsertException(InsertException.INTERNAL_ERROR, "Got MetadataUnresolvedException in SingleFileInserter: "+e.toString(), null);
				}
				ClientPutState metaPutter = createInserter(parent, metadataBucket, (short) -1, block.desiredURI, ctx, mcb, true, (int)origSize, -1, getCHKOnly, true, false);
				mcb.addURIGenerator(metaPutter);
				mcb.add(dataPutter);
				cb.onTransition(this, mcb);
				Logger.minor(this, ""+mcb+" : data "+dataPutter+" meta "+metaPutter);
				mcb.arm();
				dataPutter.schedule();
				if(metaPutter instanceof SingleBlockInserter)
					((SingleBlockInserter)metaPutter).encode();
				metaPutter.schedule();
				cb.onBlockSetFinished(this);
			}
			return;
		}
		// Otherwise the file is too big to fit into one block
		// We therefore must make a splitfile
		// Job of SplitHandler: when the splitinserter has the metadata,
		// insert it. Then when the splitinserter has finished, and the
		// metadata insert has finished too, tell the master callback.
		if(reportMetadataOnly) {
			SplitFileInserter sfi = new SplitFileInserter(parent, cb, data, bestCodec, origSize, block.clientMetadata, ctx, getCHKOnly, metadata, token, archiveType, shouldFreeData);
			cb.onTransition(this, sfi);
			sfi.start();
			if(earlyEncode) sfi.forceEncode();
		} else {
			SplitHandler sh = new SplitHandler();
			SplitFileInserter sfi = new SplitFileInserter(parent, sh, data, bestCodec, origSize, block.clientMetadata, ctx, getCHKOnly, metadata, token, archiveType, shouldFreeData);
			sh.sfi = sfi;
			cb.onTransition(this, sh);
			sfi.start();
			if(earlyEncode) sfi.forceEncode();
		}
		}
	}
	
	private Metadata makeMetadata(ARCHIVE_TYPE archiveType, COMPRESSOR_TYPE codec, FreenetURI uri) {
		Metadata meta = null;
		if(archiveType != null)
			meta = new Metadata(Metadata.ARCHIVE_MANIFEST, archiveType, codec, uri, block.clientMetadata);
		else  // redirect
			meta = new Metadata(Metadata.SIMPLE_REDIRECT, archiveType, codec, uri, block.clientMetadata);
		if(targetFilename != null) {
			HashMap hm = new HashMap();
			hm.put(targetFilename, meta);
			meta = Metadata.mkRedirectionManifestWithMetadata(hm);
		}
		return meta;
	}

	private ClientPutState createInserter(BaseClientPutter parent, Bucket data, short compressionCodec, FreenetURI uri, 
			InsertContext ctx, PutCompletionCallback cb, boolean isMetadata, int sourceLength, int token, boolean getCHKOnly, 
			boolean addToParent, boolean encodeCHK) throws InsertException {
		
		uri.checkInsertURI(); // will throw an exception if needed
		
		if(uri.getKeyType().equals("USK")) {
			try {
				return new USKInserter(parent, data, compressionCodec, uri, ctx, cb, isMetadata, sourceLength, token, 
					getCHKOnly, addToParent, this.token);
			} catch (MalformedURLException e) {
				throw new InsertException(InsertException.INVALID_URI, e, null);
			}
		} else {
			SingleBlockInserter sbi = 
				new SingleBlockInserter(parent, data, compressionCodec, uri, ctx, cb, isMetadata, sourceLength, token, 
						getCHKOnly, addToParent, false, this.token);
			if(encodeCHK)
				cb.onEncode(sbi.getBlock().getClientKey(), this);
			return sbi;
		}
		
	}
	
	/**
	 * When we get the metadata, start inserting it to our target key.
	 * When we have inserted both the metadata and the splitfile,
	 * call the master callback.
	 */
	class SplitHandler implements PutCompletionCallback, ClientPutState {

		ClientPutState sfi;
		ClientPutState metadataPutter;
		boolean finished;
		boolean splitInsertSuccess;
		boolean metaInsertSuccess;
		boolean splitInsertSetBlocks;
		boolean metaInsertSetBlocks;
		boolean metaInsertStarted;
		boolean metaFetchable;

		/**
		 * Create a SplitHandler from a stored progress SimpleFieldSet.
		 * @param forceMetadata If true, the insert is metadata, regardless of what the
		 * encompassing SplitFileInserter says (i.e. it's multi-level metadata).
		 * @throws ResumeException Thrown if the resume fails.
		 * @throws InsertException Thrown if some other error prevents the insert
		 * from starting.
		 */
		void start(SimpleFieldSet fs, boolean forceMetadata) throws ResumeException, InsertException {
			
			boolean meta = metadata || forceMetadata;
			
			// Don't include the booleans; wait for the callback.
			
			SimpleFieldSet sfiFS = fs.subset("SplitFileInserter");
			if(sfiFS == null)
				throw new ResumeException("No SplitFileInserter");
			ClientPutState newSFI, newMetaPutter = null;
			newSFI = new SplitFileInserter(parent, this, forceMetadata ? null : block.clientMetadata, ctx, getCHKOnly, meta, token, archiveType, sfiFS);
			if(logMINOR) Logger.minor(this, "Starting "+newSFI+" for "+this);
			fs.removeSubset("SplitFileInserter");
			SimpleFieldSet metaFS = fs.subset("MetadataPutter");
			if(metaFS != null) {
				try {
					String type = metaFS.get("Type");
					if(type.equals("SplitFileInserter")) {
						// FIXME insertAsArchiveManifest ?!?!?!
						newMetaPutter = 
							new SplitFileInserter(parent, this, null, ctx, getCHKOnly, true, token, archiveType, metaFS);
					} else if(type.equals("SplitHandler")) {
						newMetaPutter = new SplitHandler();
						((SplitHandler)newMetaPutter).start(metaFS, true);
					}
				} catch (ResumeException e) {
					newMetaPutter = null;
					Logger.error(this, "Caught "+e, e);
					// Will be reconstructed later
				}
			}
			if(logMINOR) Logger.minor(this, "Metadata putter "+metadataPutter+" for "+this);
			fs.removeSubset("MetadataPutter");
			synchronized(this) {
				sfi = newSFI;
				metadataPutter = newMetaPutter;
			}
		}

		public SplitHandler() {
			// Default constructor
		}

		public synchronized void onTransition(ClientPutState oldState, ClientPutState newState) {
			if(oldState == sfi)
				sfi = newState;
			if(oldState == metadataPutter)
				metadataPutter = newState;
		}
		
		public void onSuccess(ClientPutState state) {
			logMINOR = Logger.shouldLog(Logger.MINOR, this);
			if(logMINOR) Logger.minor(this, "onSuccess("+state+") for "+this);
			boolean lateStart = false;
			synchronized(this) {
				if(finished){
					if(freeData)
						block.free();
					return;
				}
				if(state == sfi) {
					if(logMINOR) Logger.minor(this, "Splitfile insert succeeded for "+this+" : "+state);
					splitInsertSuccess = true;
					if(!metaInsertSuccess && !metaInsertStarted) {
						lateStart = true;
					} else {
						if(logMINOR) Logger.minor(this, "Metadata already started for "+this+" : success="+metaInsertSuccess+" started="+metaInsertStarted);
					}
				} else if(state == metadataPutter) {
					if(logMINOR) Logger.minor(this, "Metadata insert succeeded for "+this+" : "+state);
					metaInsertSuccess = true;
				} else {
					Logger.error(this, "Unknown: "+state+" for "+this, new Exception("debug"));
				}
				if(splitInsertSuccess && metaInsertSuccess) {
					if(logMINOR) Logger.minor(this, "Both succeeded for "+this);
					finished = true;
				}
			}
			if(lateStart)
				startMetadata();
			else if(finished)
				cb.onSuccess(this);
		}

		public void onFailure(InsertException e, ClientPutState state) {
			synchronized(this) {
				if(finished){
					if(freeData)
						block.free();
					return;
				}
			}
			fail(e);
		}

		public void onMetadata(Metadata meta, ClientPutState state) {
			InsertException e = null;
			synchronized(this) {
				if(finished) return;
				if(reportMetadataOnly) {
					if(state != sfi) {
						Logger.error(this, "Got metadata from unknown object "+state+" when expecting to report metadata");
						return;
					}
					metaInsertSuccess = true;
				} else if(state == metadataPutter) {
					Logger.error(this, "Got metadata for metadata");
					e = new InsertException(InsertException.INTERNAL_ERROR, "Did not expect to get metadata for metadata inserter", null);
				} else if(state != sfi) {
					Logger.error(this, "Got metadata from unknown state "+state);
					e = new InsertException(InsertException.INTERNAL_ERROR, "Got metadata from unknown state", null);
				} else {
					// Already started metadata putter ? (in which case we've got the metadata twice)
					if(metadataPutter != null) return;
				}
			}
			if(reportMetadataOnly) {
				cb.onMetadata(meta, this);
				return;
			}
			if(e != null) {
				onFailure(e, state);
				return;
			}
			
			byte[] metaBytes;
			try {
				metaBytes = meta.writeToByteArray();
			} catch (MetadataUnresolvedException e1) {
				Logger.error(this, "Impossible: "+e1, e1);
				InsertException ex = new InsertException(InsertException.INTERNAL_ERROR, "MetadataUnresolvedException in SingleFileInserter.SplitHandler: "+e1, null);
				ex.initCause(e1);
				fail(ex);
				return;
			}
			
			String metaPutterTargetFilename = targetFilename;
			
			if(targetFilename != null) {
				
				if(metaBytes.length <= Short.MAX_VALUE) {
					HashMap hm = new HashMap();
					hm.put(targetFilename, meta);
					meta = Metadata.mkRedirectionManifestWithMetadata(hm);
					metaPutterTargetFilename = null;
					try {
						metaBytes = meta.writeToByteArray();
					} catch (MetadataUnresolvedException e1) {
						Logger.error(this, "Impossible (2): "+e1, e1);
						InsertException ex = new InsertException(InsertException.INTERNAL_ERROR, "MetadataUnresolvedException in SingleFileInserter.SplitHandler(2): "+e1, null);
						ex.initCause(e1);
						fail(ex);
						return;
					}
				}
			}
			
			Bucket metadataBucket;
			try {
				metadataBucket = BucketTools.makeImmutableBucket(ctx.bf, metaBytes);
			} catch (IOException e1) {
				InsertException ex = new InsertException(InsertException.BUCKET_ERROR, e1, null);
				fail(ex);
				return;
			}
			InsertBlock newBlock = new InsertBlock(metadataBucket, null, block.desiredURI);
			try {
				synchronized(this) {
					metadataPutter = new SingleFileInserter(parent, this, newBlock, true, ctx, false, getCHKOnly, false, token, archiveType, true, metaPutterTargetFilename, earlyEncode);
					// If EarlyEncode, then start the metadata insert ASAP, to get the key.
					// Otherwise, wait until the data is fetchable (to improve persistence).
					if(!(earlyEncode || splitInsertSuccess)) return;
				}
				if(logMINOR) Logger.minor(this, "Putting metadata on "+metadataPutter+" from "+sfi+" ("+((SplitFileInserter)sfi).getLength()+ ')');
			} catch (InsertException e1) {
				cb.onFailure(e1, this);
				return;
			}
			startMetadata();
		}

		private void fail(InsertException e) {
			if(logMINOR) Logger.minor(this, "Failing: "+e, e);
			ClientPutState oldSFI = null;
			ClientPutState oldMetadataPutter = null;
			synchronized(this) {
				if(finished){
					if(freeData)
						block.free();
					return;
				}
				finished = true;
				oldSFI = sfi;
				oldMetadataPutter = metadataPutter;
			}
			if(oldSFI != null)
				oldSFI.cancel();
			if(oldMetadataPutter != null)
				oldMetadataPutter.cancel();
			finished = true;
			cb.onFailure(e, this);
		}

		public BaseClientPutter getParent() {
			return parent;
		}

		public void onEncode(BaseClientKey key, ClientPutState state) {
			synchronized(this) {
				if(state != metadataPutter) return;
			}
			cb.onEncode(key, this);
		}

		public void cancel() {
			ClientPutState oldSFI = null;
			ClientPutState oldMetadataPutter = null;
			synchronized(this) {
				oldSFI = sfi;
				oldMetadataPutter = metadataPutter;
			}
			if(oldSFI != null)
				oldSFI.cancel();
			if(oldMetadataPutter != null)
				oldMetadataPutter.cancel();
			
			if(freeData)
				block.free();
		}

		public void onBlockSetFinished(ClientPutState state) {
			synchronized(this) {
				if(state == sfi)
					splitInsertSetBlocks = true;
				else if (state == metadataPutter)
					metaInsertSetBlocks = true;
				if(!(splitInsertSetBlocks && metaInsertSetBlocks)) 
					return;
			}
			cb.onBlockSetFinished(this);
		}

		public void schedule() throws InsertException {
			sfi.schedule();
		}

		public Object getToken() {
			return token;
		}

		public SimpleFieldSet getProgressFieldset() {
			ClientPutState curSFI;
			ClientPutState curMetadataPutter;
			synchronized(this) {
				curSFI = sfi;
				curMetadataPutter = metadataPutter;
			}
			SimpleFieldSet fs = new SimpleFieldSet(false);
			fs.putSingle("Type", "SplitHandler");
			if(curSFI != null)
				fs.put("SplitFileInserter", curSFI.getProgressFieldset());
			if(curMetadataPutter != null)
				fs.put("MetadataPutter", metadataPutter.getProgressFieldset());
			return fs;
		}

		public void onFetchable(ClientPutState state) {

			logMINOR = Logger.shouldLog(Logger.MINOR, this);

			if(logMINOR) Logger.minor(this, "onFetchable("+state+ ')');
			
			boolean meta;
			
			synchronized(this) {
				meta = (state == metadataPutter);
				if(meta) {
					if(!metaInsertStarted) {
						Logger.error(this, "Metadata insert not started yet got onFetchable for it: "+state+" on "+this);
					}
					if(logMINOR) Logger.minor(this, "Metadata fetchable"+(metaFetchable?"":" already"));
					if(metaFetchable) return;
					metaFetchable = true;
				} else {
					if(state != sfi) {
						Logger.error(this, "onFetchable for unknown state "+state);
						return;
					}
					if(logMINOR) Logger.minor(this, "Data fetchable");
					if(metaInsertStarted) return;
				}
			}
			
			if(meta)
				cb.onFetchable(this);
		}
		
		private void startMetadata() {
			try {
				ClientPutState putter;
				ClientPutState splitInserter;
				synchronized(this) {
					if(metaInsertStarted) return;
					putter = metadataPutter;
					if(putter == null) {
						if(logMINOR) Logger.minor(this, "Cannot start metadata yet: no metadataPutter");
					} else
						metaInsertStarted = true;
					splitInserter = sfi;
				}
				if(putter != null) {
					if(logMINOR) Logger.minor(this, "Starting metadata inserter: "+putter+" for "+this);
					putter.schedule();
					if(logMINOR) Logger.minor(this, "Started metadata inserter: "+putter+" for "+this);
				} else {
					// Get all the URIs ASAP so we can start to insert the metadata.
					((SplitFileInserter)splitInserter).forceEncode();
				}
			} catch (InsertException e1) {
				Logger.error(this, "Failing "+this+" : "+e1, e1);
				fail(e1);
				return;
			}
		}
		
	}

	public BaseClientPutter getParent() {
		return parent;
	}

	public void cancel() {
		if(freeData)
			block.free();
	}

	public void schedule() throws InsertException {
		start(null);
	}

	public Object getToken() {
		return token;
	}

	public SimpleFieldSet getProgressFieldset() {
		return null;
	}
}
