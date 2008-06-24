/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import java.io.IOException;
import java.net.MalformedURLException;
import java.util.LinkedList;

import com.db4o.ObjectContainer;

import freenet.client.ArchiveContext;
import freenet.client.ArchiveExtractCallback;
import freenet.client.ArchiveFailureException;
import freenet.client.ArchiveHandler;
import freenet.client.ArchiveManager;
import freenet.client.ArchiveRestartException;
import freenet.client.ClientMetadata;
import freenet.client.FetchContext;
import freenet.client.FetchException;
import freenet.client.FetchResult;
import freenet.client.Metadata;
import freenet.client.MetadataParseException;
import freenet.keys.BaseClientKey;
import freenet.keys.ClientCHK;
import freenet.keys.ClientKey;
import freenet.keys.ClientKeyBlock;
import freenet.keys.ClientSSK;
import freenet.keys.FreenetURI;
import freenet.keys.USK;
import freenet.node.RequestScheduler;
import freenet.support.Logger;
import freenet.support.api.Bucket;
import freenet.support.compress.CompressionOutputSizeException;
import freenet.support.compress.Compressor;
import freenet.support.io.BucketTools;

public class SingleFileFetcher extends SimpleSingleFileFetcher {

	private static boolean logMINOR;
	/** Original URI */
	final FreenetURI uri;
	/** Meta-strings. (Path elements that aren't part of a key type) */
	final LinkedList metaStrings;
	/** Number of metaStrings which were added by redirects etc. They are added to the start, so this is decremented
	 * when we consume one. */
	private int addedMetaStrings;
	final ClientMetadata clientMetadata;
	private Metadata metadata;
	private Metadata archiveMetadata;
	final ArchiveContext actx;
	/** Archive handler. We can only have one archive handler at a time. */
	private ArchiveHandler ah;
	private int recursionLevel;
	/** The URI of the currently-being-processed data, for archives etc. */
	private FreenetURI thisKey;
	private final LinkedList decompressors;
	private final boolean dontTellClientGet;
	private final Bucket returnBucket;
	/** If true, success/failure is immediately reported to the client, and therefore we can check TOO_MANY_PATH_COMPONENTS. */
	private final boolean isFinal;
	private RequestScheduler sched;

	/** Create a new SingleFileFetcher and register self.
	 * Called when following a redirect, or direct from ClientGet.
	 * FIXME: Many times where this is called internally we might be better off using a copy constructor? 
	 */
	public SingleFileFetcher(ClientRequester parent, GetCompletionCallback cb, ClientMetadata metadata,
			ClientKey key, LinkedList metaStrings, FreenetURI origURI, int addedMetaStrings, FetchContext ctx,
			ArchiveContext actx, ArchiveHandler ah, Metadata archiveMetadata, int maxRetries, int recursionLevel,
			boolean dontTellClientGet, long l, boolean isEssential,
			Bucket returnBucket, boolean isFinal, ObjectContainer container, ClientContext context) throws FetchException {
		super(key, maxRetries, ctx, parent, cb, isEssential, false, l, container, context);
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		if(logMINOR) Logger.minor(this, "Creating SingleFileFetcher for "+key+" from "+origURI+" meta="+metaStrings.toString(), new Exception("debug"));
		this.isFinal = isFinal;
		this.cancelled = false;
		this.returnBucket = returnBucket;
		this.dontTellClientGet = dontTellClientGet;
		this.ah = ah;
		this.archiveMetadata = archiveMetadata;
		//this.uri = uri;
		//this.key = ClientKey.getBaseKey(uri);
		//metaStrings = uri.listMetaStrings();
		this.metaStrings = metaStrings;
		this.addedMetaStrings = addedMetaStrings;
		this.clientMetadata = metadata;
		thisKey = key.getURI();
		this.uri = origURI;
		this.actx = actx;
		this.recursionLevel = recursionLevel + 1;
		if(recursionLevel > ctx.maxRecursionLevel)
			throw new FetchException(FetchException.TOO_MUCH_RECURSION, "Too much recursion: "+recursionLevel+" > "+ctx.maxRecursionLevel);
		this.decompressors = new LinkedList();
	}

	/** Copy constructor, modifies a few given fields, don't call schedule().
	 * Used for things like slave fetchers for MultiLevelMetadata, therefore does not remember returnBucket,
	 * metaStrings etc. */
	public SingleFileFetcher(SingleFileFetcher fetcher, Metadata newMeta, GetCompletionCallback callback, FetchContext ctx2, ObjectContainer container, ClientContext context) throws FetchException {
		// Don't add a block, we have already fetched the data, we are just handling the metadata in a different fetcher.
		super(fetcher.key, fetcher.maxRetries, ctx2, fetcher.parent, callback, false, true, fetcher.token, container, context);
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		if(logMINOR) Logger.minor(this, "Creating SingleFileFetcher for "+fetcher.key+" meta="+fetcher.metaStrings.toString(), new Exception("debug"));
		this.returnBucket = null;
		// We expect significant further processing in the parent
		this.isFinal = false;
		this.dontTellClientGet = fetcher.dontTellClientGet;
		this.actx = fetcher.actx;
		this.ah = fetcher.ah;
		this.archiveMetadata = fetcher.archiveMetadata;
		this.clientMetadata = (ClientMetadata) fetcher.clientMetadata.clone();
		this.metadata = newMeta;
		this.metaStrings = new LinkedList();
		this.addedMetaStrings = 0;
		this.recursionLevel = fetcher.recursionLevel + 1;
		if(recursionLevel > ctx.maxRecursionLevel)
			throw new FetchException(FetchException.TOO_MUCH_RECURSION);
		this.thisKey = fetcher.thisKey;
		this.decompressors = fetcher.decompressors;
		this.uri = fetcher.uri;
	}

	// Process the completed data. May result in us going to a
	// splitfile, or another SingleFileFetcher, etc.
	public void onSuccess(ClientKeyBlock block, boolean fromStore, Object token, RequestScheduler sched, ObjectContainer container, ClientContext context) {
		this.sched = sched;
		if(persistent) {
			container.activate(parent, 1);
			container.activate(ctx, 1);
		}
		if(parent instanceof ClientGetter)
			((ClientGetter)parent).addKeyToBinaryBlob(block, container, context);
		parent.completedBlock(fromStore, container, context);
		// Extract data
		
		if(block == null) {
			Logger.error(this, "block is null! fromStore="+fromStore+", token="+token, new Exception("error"));
			return;
		}
		Bucket data = extract(block, sched, container, context);
		if(data == null) {
			if(logMINOR)
				Logger.minor(this, "No data");
			// Already failed: if extract returns null it will call onFailure first.
			return;
		}
		if(logMINOR)
			Logger.minor(this, "Block "+(block.isMetadata() ? "is metadata" : "is not metadata")+" on "+this);
		if(!block.isMetadata()) {
			onSuccess(new FetchResult(clientMetadata, data), sched, container, context);
		} else {
			if(!ctx.followRedirects) {
				onFailure(new FetchException(FetchException.INVALID_METADATA, "Told me not to follow redirects (splitfile block??)"), false, sched, container, context);
				return;
			}
			if(parent.isCancelled()) {
				onFailure(new FetchException(FetchException.CANCELLED), false, sched, container, context);
				return;
			}
			if(data.size() > ctx.maxMetadataSize) {
				onFailure(new FetchException(FetchException.TOO_BIG_METADATA), false, sched, container, context);
				return;
			}
			// Parse metadata
			try {
				metadata = Metadata.construct(data);
			} catch (MetadataParseException e) {
				onFailure(new FetchException(e), false, sched, container, context);
				return;
			} catch (IOException e) {
				// Bucket error?
				onFailure(new FetchException(FetchException.BUCKET_ERROR, e), false, sched, container, context);
				return;
			}
			wrapHandleMetadata(false, container, context);
		}
	}

	protected void onSuccess(FetchResult result, RequestScheduler sched, ObjectContainer container, ClientContext context) {
		this.sched = sched;
		unregister(false, container);
		if(parent.isCancelled()) {
			if(logMINOR)
				Logger.minor(this, "Parent is cancelled");
			result.asBucket().free();
			onFailure(new FetchException(FetchException.CANCELLED), false, sched, container, context);
			return;
		}
		if(persistent) {
			container.activate(decompressors, 1);
			container.activate(parent, 1);
			container.activate(ctx, 1);
			container.activate(rcb, 1);
		}
		if(!decompressors.isEmpty()) {
			Bucket data = result.asBucket();
			while(!decompressors.isEmpty()) {
				Compressor c = (Compressor) decompressors.removeLast();
				try {
					long maxLen = Math.max(ctx.maxTempLength, ctx.maxOutputLength);
					data = c.decompress(data, context.getBucketFactory(parent.persistent()), maxLen, maxLen * 4, decompressors.isEmpty() ? returnBucket : null);
				} catch (IOException e) {
					onFailure(new FetchException(FetchException.BUCKET_ERROR, e), false, sched, container, context);
					return;
				} catch (CompressionOutputSizeException e) {
					onFailure(new FetchException(FetchException.TOO_BIG, e.estimatedSize, (rcb == parent), result.getMimeType()), false, sched, container, context);
					return;
				}
			}
			result = new FetchResult(result, data);
		}
		if((!ctx.ignoreTooManyPathComponents) && (!metaStrings.isEmpty()) && isFinal) {
			// Some meta-strings left
			if(addedMetaStrings > 0) {
				// Should this be an error?
				// It would be useful to be able to fetch the data ...
				// On the other hand such inserts could cause unpredictable results?
				// Would be useful to make a redirect to the key we actually fetched.
				rcb.onFailure(new FetchException(FetchException.INVALID_METADATA, "Invalid metadata: too many path components in redirects", thisKey), this, container, context);
			} else {
				// TOO_MANY_PATH_COMPONENTS
				// report to user
				if(logMINOR) {
					Logger.minor(this, "Too many path components: for "+uri+" meta="+metaStrings.toString());
				}
				FreenetURI tryURI = uri;
				tryURI = tryURI.dropLastMetaStrings(metaStrings.size());
				rcb.onFailure(new FetchException(FetchException.TOO_MANY_PATH_COMPONENTS, result.size(), (rcb == parent), result.getMimeType(), tryURI), this, container, context);
			}
			result.asBucket().free();
			return;
		} else if(result.size() > ctx.maxOutputLength) {
			rcb.onFailure(new FetchException(FetchException.TOO_BIG, result.size(), (rcb == parent), result.getMimeType()), this, container, context);
			result.asBucket().free();
		} else {
			rcb.onSuccess(result, this, container, context);
		}
	}

	/**
	 * Handle the current metadata. I.e. do something with it: transition to a splitfile, look up a manifest, etc.
	 * LOCKING: Synchronized as it changes so many variables; if we want to write the structure to disk, we don't
	 * want this running at the same time.
	 * @throws FetchException
	 * @throws MetadataParseException
	 * @throws ArchiveFailureException
	 * @throws ArchiveRestartException
	 */
	private synchronized void handleMetadata(final ObjectContainer container, final ClientContext context) throws FetchException, MetadataParseException, ArchiveFailureException, ArchiveRestartException {
		if(persistent)
			container.activate(this, 2);
		while(true) {
			if(metadata.isSimpleManifest()) {
				if(logMINOR) Logger.minor(this, "Is simple manifest");
				String name;
				if(metaStrings.isEmpty())
					throw new FetchException(FetchException.NOT_ENOUGH_PATH_COMPONENTS, -1, false, null, uri.addMetaStrings(new String[] { "" }));
				else name = removeMetaString();
				// Since metadata is a document, we just replace metadata here
				if(logMINOR) Logger.minor(this, "Next meta-string: "+name);
				if(name == null) {
					metadata = metadata.getDefaultDocument();
					if(metadata == null)
						throw new FetchException(FetchException.NOT_ENOUGH_PATH_COMPONENTS, -1, false, null, uri.addMetaStrings(new String[] { "" }));
				} else {
					metadata = metadata.getDocument(name);
					thisKey = thisKey.pushMetaString(name);
					if(metadata == null)
						throw new FetchException(FetchException.NOT_IN_ARCHIVE);
				}
				continue; // loop
			} else if(metadata.isArchiveManifest()) {
				if(logMINOR) Logger.minor(this, "Is archive manifest");
				if(metaStrings.isEmpty() && ctx.returnZIPManifests) {
					// Just return the archive, whole.
					metadata.setSimpleRedirect();
					continue;
				}
				// First we need the archive metadata.
				// Then parse it. Then we may need to fetch something from inside the archive.
				// It's more efficient to keep the existing ah if we can, and it is vital in
				// the case of binary blobs.
				if(ah == null || !ah.getKey().equals(thisKey))
					ah = context.archiveManager.makeHandler(thisKey, metadata.getArchiveType(),
							(parent instanceof ClientGetter ? ((ClientGetter)parent).collectingBinaryBlob() : false));
				archiveMetadata = metadata;
				// ah is set. This means we are currently handling an archive.
				Bucket metadataBucket;
				metadataBucket = ah.getMetadata(actx, null, recursionLevel+1, true, context.archiveManager);
				if(metadataBucket != null) {
					try {
						metadata = Metadata.construct(metadataBucket);
					} catch (IOException e) {
						// Bucket error?
						throw new FetchException(FetchException.BUCKET_ERROR, e);
					}
				} else {
					fetchArchive(false, archiveMetadata, ArchiveManager.METADATA_NAME, new ArchiveExtractCallback() {
						public void gotBucket(Bucket data, ObjectContainer container, ClientContext context) {
							try {
								metadata = Metadata.construct(data);
								wrapHandleMetadata(true, container, context);
							} catch (MetadataParseException e) {
								// Invalid metadata
								onFailure(new FetchException(FetchException.INVALID_METADATA, e), false, sched, container, context);
								return;
							} catch (IOException e) {
								// Bucket error?
								onFailure(new FetchException(FetchException.BUCKET_ERROR, e), false, sched, container, context);
								return;
							}
						}
						public void notInArchive(ObjectContainer container, ClientContext context) {
							onFailure(new FetchException(FetchException.INTERNAL_ERROR, "No metadata in container! Cannot happen as ArchiveManager should synthesise some!"), false, sched, container, context);
						}
						public void onFailed(ArchiveRestartException e, ObjectContainer container, ClientContext context) {
							SingleFileFetcher.this.onFailure(new FetchException(e), false, sched, container, context);
						}
						public void onFailed(ArchiveFailureException e, ObjectContainer container, ClientContext context) {
							SingleFileFetcher.this.onFailure(new FetchException(e), false, sched, container, context);
						}
					}, container, context); // will result in this function being called again
					return;
				}
				continue;
			} else if(metadata.isArchiveInternalRedirect()) {
				if(logMINOR) Logger.minor(this, "Is archive-internal redirect");
				clientMetadata.mergeNoOverwrite(metadata.getClientMetadata());
				String mime = clientMetadata.getMIMEType();
				if(mime != null) rcb.onExpectedMIME(mime, container);
				if(metaStrings.isEmpty() && isFinal && clientMetadata.getMIMETypeNoParams() != null && ctx.allowedMIMETypes != null &&
						!ctx.allowedMIMETypes.contains(clientMetadata.getMIMETypeNoParams())) {
					throw new FetchException(FetchException.WRONG_MIME_TYPE, -1, false, clientMetadata.getMIMEType());
				}
				// Fetch it from the archive
				if(ah == null)
					throw new FetchException(FetchException.UNKNOWN_METADATA, "Archive redirect not in an archive manifest");
				String filename = metadata.getZIPInternalName();
				if(logMINOR) Logger.minor(this, "Fetching "+filename);
				Bucket dataBucket = ah.get(filename, actx, null, recursionLevel+1, true, context.archiveManager);
				if(dataBucket != null) {
					if(logMINOR) Logger.minor(this, "Returning data");
					final Bucket out;
					try {
						// Data will not be freed until client is finished with it.
						if(returnBucket != null) {
							out = returnBucket;
							BucketTools.copy(dataBucket, out);
							dataBucket.free();
						} else {
							out = dataBucket;
						}
					} catch (IOException e) {
						throw new FetchException(FetchException.BUCKET_ERROR);
					}
					// Return the data
					onSuccess(new FetchResult(clientMetadata, out), sched, container, context);
					
					return;
				} else {
					if(logMINOR) Logger.minor(this, "Fetching archive (thisKey="+thisKey+ ')');
					// Metadata cannot contain pointers to files which don't exist.
					// We enforce this in ArchiveHandler.
					// Therefore, the archive needs to be fetched.
					fetchArchive(true, archiveMetadata, filename, new ArchiveExtractCallback() {
						public void gotBucket(Bucket data, ObjectContainer container, ClientContext context) {
							if(logMINOR) Logger.minor(this, "Returning data");
							Bucket out;
							try {
								// Data will not be freed until client is finished with it.
								if(returnBucket != null) {
									out = returnBucket;
									BucketTools.copy(data, out);
									data.free();
								} else {
									out = data;
								}
							} catch (IOException e) {
								onFailure(new FetchException(FetchException.BUCKET_ERROR), false, sched, container, context);
								return;
							}
							// Return the data
							onSuccess(new FetchResult(clientMetadata, out), sched, container, context);
						}
						public void notInArchive(ObjectContainer container, ClientContext context) {
							onFailure(new FetchException(FetchException.NOT_IN_ARCHIVE), false, sched, container, context);
						}
						public void onFailed(ArchiveRestartException e, ObjectContainer container, ClientContext context) {
							SingleFileFetcher.this.onFailure(new FetchException(e), false, sched, container, context);
						}
						public void onFailed(ArchiveFailureException e, ObjectContainer container, ClientContext context) {
							SingleFileFetcher.this.onFailure(new FetchException(e), false, sched, container, context);
						}
					}, container, context);
					// Will call back into this function when it has been fetched.
					return;
				}
			} else if(metadata.isMultiLevelMetadata()) {
				if(logMINOR) Logger.minor(this, "Is multi-level metadata");
				// Fetch on a second SingleFileFetcher, like with archives.
				metadata.setSimpleRedirect();
				final SingleFileFetcher f = new SingleFileFetcher(this, metadata, new MultiLevelMetadataCallback(), ctx, container, context);
				// Clear our own metadata so it can be garbage collected, it will be replaced by whatever is fetched.
				this.metadata = null;
				f.wrapHandleMetadata(true, container, context);
				return;
			} else if(metadata.isSingleFileRedirect()) {
				if(logMINOR) Logger.minor(this, "Is single-file redirect");
				clientMetadata.mergeNoOverwrite(metadata.getClientMetadata()); // even splitfiles can have mime types!
				String mime = clientMetadata.getMIMEType();
				if(mime != null) rcb.onExpectedMIME(mime, container);

				String mimeType = clientMetadata.getMIMETypeNoParams();
				if(mimeType != null && ArchiveManager.isUsableArchiveType(mimeType) && metaStrings.size() > 0) {
					// Looks like an implicit archive, handle as such
					metadata.setArchiveManifest();
					// Pick up MIME type from inside archive
					clientMetadata.clear();
					if(logMINOR) Logger.minor(this, "Handling implicit container... (redirect)");
					continue;
				}
				
				if(metaStrings.isEmpty() && isFinal && mimeType != null && ctx.allowedMIMETypes != null && 
						!ctx.allowedMIMETypes.contains(mimeType)) {
					throw new FetchException(FetchException.WRONG_MIME_TYPE, -1, false, clientMetadata.getMIMEType());
				}
				
				// Simple redirect
				// Just create a new SingleFileFetcher
				// Which will then fetch the target URI, and call the rcd.success
				// Hopefully!
				FreenetURI newURI = metadata.getSingleTarget();
				if(logMINOR) Logger.minor(this, "Redirecting to "+newURI);
				ClientKey redirectedKey;
				try {
					BaseClientKey k = BaseClientKey.getBaseKey(newURI);
					if(k instanceof ClientKey)
						redirectedKey = (ClientKey) k;
					else
						// FIXME do we want to allow redirects to USKs?
						// Without redirects to USKs, all SSK and CHKs are static.
						// This may be a desirable property.
						throw new FetchException(FetchException.UNKNOWN_METADATA, "Redirect to a USK");
				} catch (MalformedURLException e) {
					throw new FetchException(FetchException.INVALID_URI, e);
				}
				LinkedList newMetaStrings = newURI.listMetaStrings();
				
				// Move any new meta strings to beginning of our list of remaining meta strings
				while(!newMetaStrings.isEmpty()) {
					Object o = newMetaStrings.removeLast();
					metaStrings.addFirst(o);
					addedMetaStrings++;
				}

				final SingleFileFetcher f = new SingleFileFetcher(parent, rcb, clientMetadata, redirectedKey, metaStrings, this.uri, addedMetaStrings, ctx, actx, ah, archiveMetadata, maxRetries, recursionLevel, false, token, true, returnBucket, isFinal, container, context);
				if((redirectedKey instanceof ClientCHK) && !((ClientCHK)redirectedKey).isMetadata())
					rcb.onBlockSetFinished(this, container, context);
				if(metadata.isCompressed()) {
					Compressor codec = Compressor.getCompressionAlgorithmByMetadataID(metadata.getCompressionCodec());
					f.addDecompressor(codec);
				}
				parent.onTransition(this, f, container);
				f.schedule(container, context);
				// All done! No longer our problem!
				return;
			} else if(metadata.isSplitfile()) {
				if(logMINOR) Logger.minor(this, "Fetching splitfile");
				
				clientMetadata.mergeNoOverwrite(metadata.getClientMetadata()); // even splitfiles can have mime types!
				String mime = clientMetadata.getMIMEType();
				if(mime != null) rcb.onExpectedMIME(mime, container);
				
				String mimeType = clientMetadata.getMIMETypeNoParams();
				if(mimeType != null && ArchiveManager.isUsableArchiveType(mimeType) && metaStrings.size() > 0) {
					// Looks like an implicit archive, handle as such
					metadata.setArchiveManifest();
					// Pick up MIME type from inside archive
					clientMetadata.clear();
					if(logMINOR) Logger.minor(this, "Handling implicit container... (splitfile)");
					continue;
				}
				
				if(metaStrings.isEmpty() && isFinal && mimeType != null && ctx.allowedMIMETypes != null &&
						!ctx.allowedMIMETypes.contains(mimeType)) {
					throw new FetchException(FetchException.WRONG_MIME_TYPE, metadata.uncompressedDataLength(), false, clientMetadata.getMIMEType());
				}
				
				// Splitfile (possibly compressed)
				
				if(metadata.isCompressed()) {
					Compressor codec = Compressor.getCompressionAlgorithmByMetadataID(metadata.getCompressionCodec());
					addDecompressor(codec);
				}
				
				if(isFinal && !ctx.ignoreTooManyPathComponents) {
					if(!metaStrings.isEmpty()) {
						// Some meta-strings left
						if(addedMetaStrings > 0) {
							// Should this be an error?
							// It would be useful to be able to fetch the data ...
							// On the other hand such inserts could cause unpredictable results?
							// Would be useful to make a redirect to the key we actually fetched.
							rcb.onFailure(new FetchException(FetchException.INVALID_METADATA, "Invalid metadata: too many path components in redirects", thisKey), this, container, context);
						} else {
							// TOO_MANY_PATH_COMPONENTS
							// report to user
							FreenetURI tryURI = uri;
							tryURI = tryURI.dropLastMetaStrings(metaStrings.size());
							rcb.onFailure(new FetchException(FetchException.TOO_MANY_PATH_COMPONENTS, metadata.uncompressedDataLength(), (rcb == parent), clientMetadata.getMIMEType(), tryURI), this, container, context);
						}
						return;
					}
				} else
					if(logMINOR) Logger.minor(this, "Not finished: rcb="+rcb+" for "+this); 
				
				long len = metadata.dataLength();
				if(metadata.uncompressedDataLength() > len)
					len = metadata.uncompressedDataLength();
				
				if((len > ctx.maxOutputLength) ||
						(len > ctx.maxTempLength)) {
					
					throw new FetchException(FetchException.TOO_BIG, len, isFinal && decompressors.size() <= (metadata.isCompressed() ? 1 : 0), clientMetadata.getMIMEType());
				}
				
				SplitFileFetcher sf = new SplitFileFetcher(metadata, rcb, parent, ctx, 
						decompressors, clientMetadata, actx, recursionLevel, returnBucket, token, container);
				parent.onTransition(this, sf, container);
				sf.schedule(container, context);
				rcb.onBlockSetFinished(this, container, context);
				// Clear our own metadata, we won't need it any more.
				// For multi-level metadata etc see above.
				metadata = null; 
				
				// SplitFile will now run.
				// Then it will return data to rcd.
				// We are now out of the loop. Yay!
				return;
			} else {
				Logger.error(this, "Don't know what to do with metadata: "+metadata);
				throw new FetchException(FetchException.UNKNOWN_METADATA);
			}
		}
	}

	private String removeMetaString() {
		String name = (String) metaStrings.removeFirst();
		if(addedMetaStrings > 0) addedMetaStrings--;
		return name;
	}

	private void addDecompressor(Compressor codec) {
		decompressors.addLast(codec);
	}

	private void fetchArchive(boolean forData, Metadata meta, String element, ArchiveExtractCallback callback, final ObjectContainer container, ClientContext context) throws FetchException, MetadataParseException, ArchiveFailureException, ArchiveRestartException {
		if(logMINOR) Logger.minor(this, "fetchArchive()");
		// Fetch the archive
		// How?
		// Spawn a separate SingleFileFetcher,
		// which fetches the archive, then calls
		// our Callback, which unpacks the archive, then
		// reschedules us.
		Metadata newMeta = (Metadata) meta.clone();
		newMeta.setSimpleRedirect();
		final SingleFileFetcher f;
		f = new SingleFileFetcher(this, newMeta, new ArchiveFetcherCallback(forData, element, callback), new FetchContext(ctx, FetchContext.SET_RETURN_ARCHIVES, true), container, context);
		if(logMINOR) Logger.minor(this, "fetchArchive(): "+f);
		// Fetch the archive. The archive fetcher callback will unpack it, and either call the element 
		// callback, or just go back around handleMetadata() on this, which will see that the data is now
		// available.
		f.wrapHandleMetadata(true, container, context);
	}

	/**
	 * Call handleMetadata(), and deal with any resulting exceptions
	 */
	private void wrapHandleMetadata(final boolean notFinalizedSize, ObjectContainer container, final ClientContext context) {
		if(!persistent)
			innerWrapHandleMetadata(notFinalizedSize, container, context);
		else {
			if(!context.jobRunner.onDatabaseThread())
				context.jobRunner.queue(new DBJob() {
					public void run(ObjectContainer container, ClientContext context) {
						innerWrapHandleMetadata(notFinalizedSize, container, context);
					}
				}, parent.getPriorityClass(), false);
			else
				innerWrapHandleMetadata(notFinalizedSize, container, context);
		}
	}
	
	protected void innerWrapHandleMetadata(boolean notFinalizedSize, ObjectContainer container, ClientContext context) {
		try {
			handleMetadata(container, context);
		} catch (MetadataParseException e) {
			onFailure(new FetchException(e), false, sched, container, context);
		} catch (FetchException e) {
			if(notFinalizedSize)
				e.setNotFinalizedSize();
			onFailure(e, false, sched, container, context);
		} catch (ArchiveFailureException e) {
			onFailure(new FetchException(e), false, sched, container, context);
		} catch (ArchiveRestartException e) {
			onFailure(new FetchException(e), false, sched, container, context);
		}
	}

	class ArchiveFetcherCallback implements GetCompletionCallback {

		private final boolean wasFetchingFinalData;
		private final String element;
		private final ArchiveExtractCallback callback;
		
		ArchiveFetcherCallback(boolean wasFetchingFinalData, String element, ArchiveExtractCallback cb) {
			this.wasFetchingFinalData = wasFetchingFinalData;
			this.element = element;
			this.callback = cb;
		}
		
		public void onSuccess(FetchResult result, ClientGetState state, ObjectContainer container, ClientContext context) {
			if(!persistent) {
				// Run directly - we are running on some thread somewhere, don't worry about it.
				innerSuccess(result, container, context);
			} else {
				// We are running on the database thread.
				// Add a tag, unpack on a separate thread, copy the data to a persistent bucket, then schedule on the database thread,
				// remove the tag, and call the callback.
				container.activate(SingleFileFetcher.this, 1);
				container.activate(ah, 1);
				ah.extractPersistentOffThread(result.asBucket(), actx, element, callback, container, context);
			}
		}

		private void innerSuccess(FetchResult result, ObjectContainer container, ClientContext context) {
			container.activate(SingleFileFetcher.this, 1);
			container.activate(ah, 1);
			try {
				ah.extractToCache(result.asBucket(), actx, element, callback, context.archiveManager, container, context);
			} catch (ArchiveFailureException e) {
				SingleFileFetcher.this.onFailure(new FetchException(e), false, sched, container, context);
				return;
			} catch (ArchiveRestartException e) {
				SingleFileFetcher.this.onFailure(new FetchException(e), false, sched, container, context);
				return;
			}
			if(callback != null) return;
			wrapHandleMetadata(true, container, context);
		}

		public void onFailure(FetchException e, ClientGetState state, ObjectContainer container, ClientContext context) {
			container.activate(SingleFileFetcher.this, 1);
			// Force fatal as the fetcher is presumed to have made a reasonable effort.
			SingleFileFetcher.this.onFailure(e, true, sched, container, context);
		}

		public void onBlockSetFinished(ClientGetState state, ObjectContainer container, ClientContext context) {
			if(persistent)
				container.activate(rcb, 1);
			if(wasFetchingFinalData) {
				rcb.onBlockSetFinished(SingleFileFetcher.this, container, context);
			}
		}

		public void onTransition(ClientGetState oldState, ClientGetState newState, ObjectContainer container) {
			// Ignore
		}

		public void onExpectedMIME(String mime, ObjectContainer container) {
			// Ignore
		}

		public void onExpectedSize(long size, ObjectContainer container) {
			if(persistent)
				container.activate(rcb, 1);
			rcb.onExpectedSize(size, container);
		}

		public void onFinalizedMetadata(ObjectContainer container) {
			// Ignore
		}
		
	}

	class MultiLevelMetadataCallback implements GetCompletionCallback {
		
		public void onSuccess(FetchResult result, ClientGetState state, ObjectContainer container, ClientContext context) {
			if(persistent)
				container.activate(SingleFileFetcher.this, 1);
			try {
				metadata = Metadata.construct(result.asBucket());
			} catch (MetadataParseException e) {
				SingleFileFetcher.this.onFailure(new FetchException(FetchException.INVALID_METADATA, e), false, sched, container, context);
				return;
			} catch (IOException e) {
				// Bucket error?
				SingleFileFetcher.this.onFailure(new FetchException(FetchException.BUCKET_ERROR, e), false, sched, container, context);
				return;
			}
			wrapHandleMetadata(true, container, context);
		}
		
		public void onFailure(FetchException e, ClientGetState state, ObjectContainer container, ClientContext context) {
			if(persistent)
				container.activate(SingleFileFetcher.this, 1);
			// Pass it on; fetcher is assumed to have retried as appropriate already, so this is fatal.
			SingleFileFetcher.this.onFailure(e, true, sched, container, context);
		}

		public void onBlockSetFinished(ClientGetState state, ObjectContainer container, ClientContext context) {
			// Ignore as we are fetching metadata here
		}

		public void onTransition(ClientGetState oldState, ClientGetState newState, ObjectContainer container) {
			// Ignore
		}

		public void onExpectedMIME(String mime, ObjectContainer container) {
			// Ignore
		}

		public void onExpectedSize(long size, ObjectContainer container) {
			if(persistent) {
				container.activate(SingleFileFetcher.this, 1);
				container.activate(rcb, 1);
			}
			rcb.onExpectedSize(size, container);
		}

		public void onFinalizedMetadata(ObjectContainer container) {
			// Ignore
		}
		
	}
	
	public boolean ignoreStore() {
		return ctx.ignoreStore;
	}

	/**
	 * Create a fetcher for a key.
	 */
	public static ClientGetState create(ClientRequester requester, GetCompletionCallback cb, 
			ClientMetadata clientMetadata, FreenetURI uri, FetchContext ctx, ArchiveContext actx, 
			int maxRetries, int recursionLevel, boolean dontTellClientGet, long l, boolean isEssential, 
			Bucket returnBucket, boolean isFinal, ObjectContainer container, ClientContext context) throws MalformedURLException, FetchException {
		BaseClientKey key = BaseClientKey.getBaseKey(uri);
		if((clientMetadata == null || clientMetadata.isTrivial()) && (!uri.hasMetaStrings()) &&
				ctx.allowSplitfiles == false && ctx.followRedirects == false && 
				returnBucket == null && key instanceof ClientKey)
			return new SimpleSingleFileFetcher((ClientKey)key, maxRetries, ctx, requester, cb, isEssential, false, l, container, context);
		if(key instanceof ClientKey)
			return new SingleFileFetcher(requester, cb, clientMetadata, (ClientKey)key, uri.listMetaStrings(), uri, 0, ctx, actx, null, null, maxRetries, recursionLevel, dontTellClientGet, l, isEssential, returnBucket, isFinal, container, context);
		else {
			return uskCreate(requester, cb, clientMetadata, (USK)key, uri.listMetaStrings(), ctx, actx, maxRetries, recursionLevel, dontTellClientGet, l, isEssential, returnBucket, isFinal, container, context);
		}
	}

	private static ClientGetState uskCreate(ClientRequester requester, GetCompletionCallback cb, ClientMetadata clientMetadata, USK usk, LinkedList metaStrings, FetchContext ctx, ArchiveContext actx, int maxRetries, int recursionLevel, boolean dontTellClientGet, long l, boolean isEssential, Bucket returnBucket, boolean isFinal, ObjectContainer container, ClientContext context) throws FetchException {
		if(usk.suggestedEdition >= 0) {
			// Return the latest known version but at least suggestedEdition.
			long edition = context.uskManager.lookup(usk);
			if(edition <= usk.suggestedEdition) {
				// Background fetch - start background fetch first so can pick up updates in the datastore during registration.
				context.uskManager.startTemporaryBackgroundFetcher(usk, context);
				edition = context.uskManager.lookup(usk);
				if(edition > usk.suggestedEdition) {
					if(logMINOR) Logger.minor(SingleFileFetcher.class, "Redirecting to edition "+edition);
					cb.onFailure(new FetchException(FetchException.PERMANENT_REDIRECT, usk.copy(edition).getURI().addMetaStrings(metaStrings)), null, container, context);
					return null;
				} else {
					// Transition to SingleFileFetcher
					GetCompletionCallback myCB =
						new USKProxyCompletionCallback(usk, cb);
					// Want to update the latest known good iff the fetch succeeds.
					SingleFileFetcher sf = 
						new SingleFileFetcher(requester, myCB, clientMetadata, usk.getSSK(), metaStrings, 
								usk.getURI().addMetaStrings(metaStrings), 0, ctx, actx, null, null, maxRetries, recursionLevel, 
								dontTellClientGet, l, isEssential, returnBucket, isFinal, container, context);
					return sf;
				}
			} else {
				cb.onFailure(new FetchException(FetchException.PERMANENT_REDIRECT, usk.copy(edition).getURI().addMetaStrings(metaStrings)), null, container, context);
				return null;
			}
		} else {
			// Do a thorough, blocking search
			USKFetcherTag tag = 
				context.uskManager.getFetcher(usk.copy(-usk.suggestedEdition), ctx, false, requester.persistent(),
						new MyUSKFetcherCallback(requester, cb, clientMetadata, usk, metaStrings, ctx, actx, maxRetries, recursionLevel, dontTellClientGet, l, returnBucket, requester.persistent()), container, context);
			if(isEssential)
				requester.addMustSucceedBlocks(1, container);
			return tag;
		}
	}

	public static class MyUSKFetcherCallback implements USKFetcherCallback {

		final ClientRequester parent;
		final GetCompletionCallback cb;
		final ClientMetadata clientMetadata;
		final USK usk;
		final LinkedList metaStrings;
		final FetchContext ctx;
		final ArchiveContext actx;
		final int maxRetries;
		final int recursionLevel;
		final boolean dontTellClientGet;
		final long token;
		final Bucket returnBucket;
		final boolean persistent;
		
		public MyUSKFetcherCallback(ClientRequester requester, GetCompletionCallback cb, ClientMetadata clientMetadata, USK usk, LinkedList metaStrings, FetchContext ctx, ArchiveContext actx, int maxRetries, int recursionLevel, boolean dontTellClientGet, long l, Bucket returnBucket, boolean persistent) {
			this.parent = requester;
			this.cb = cb;
			this.clientMetadata = clientMetadata;
			this.usk = usk;
			this.metaStrings = metaStrings;
			this.ctx = ctx;
			this.actx = actx;
			this.maxRetries = maxRetries;
			this.recursionLevel = recursionLevel;
			this.dontTellClientGet = dontTellClientGet;
			this.token = l;
			this.returnBucket = returnBucket;
			this.persistent = persistent;
		}

		public void onFoundEdition(long l, USK newUSK, ObjectContainer container, ClientContext context, boolean metadata, short codec, byte[] data) {
			if(persistent)
				container.activate(this, 2);
			ClientSSK key = usk.getSSK(l);
			try {
				if(l == usk.suggestedEdition) {
					SingleFileFetcher sf = new SingleFileFetcher(parent, cb, clientMetadata, key, metaStrings, key.getURI().addMetaStrings(metaStrings),
							0, ctx, actx, null, null, maxRetries, recursionLevel+1, dontTellClientGet, token, false, returnBucket, true, container, context);
					sf.schedule(container, context);
				} else {
					cb.onFailure(new FetchException(FetchException.PERMANENT_REDIRECT, newUSK.getURI().addMetaStrings(metaStrings)), null, container, context);
				}
			} catch (FetchException e) {
				cb.onFailure(e, null, container, context);
			}
		}

		public void onFailure(ObjectContainer container, ClientContext context) {
			if(persistent)
				container.activate(this, 2);
			cb.onFailure(new FetchException(FetchException.DATA_NOT_FOUND, "No USK found"), null, container, context);
		}

		public void onCancelled(ObjectContainer container, ClientContext context) {
			if(persistent)
				container.activate(this, 2);
			cb.onFailure(new FetchException(FetchException.CANCELLED, (String)null), null, container, context);
		}

		public short getPollingPriorityNormal() {
			return parent.getPriorityClass();
		}

		public short getPollingPriorityProgress() {
			return parent.getPriorityClass();
		}

	}

}
