package freenet.client;

import com.db4o.ObjectContainer;
import com.db4o.ObjectSet;
import com.db4o.query.Predicate;

import freenet.client.async.ClientContext;
import freenet.client.async.DBJob;
import freenet.keys.FreenetURI;
import freenet.support.Logger;
import freenet.support.api.Bucket;
import freenet.support.io.NativeThread;

class ArchiveHandlerImpl implements ArchiveHandler {

	private final FreenetURI key;
	private final short archiveType;
	private boolean forceRefetchArchive;
	
	ArchiveHandlerImpl(FreenetURI key, short archiveType, boolean forceRefetchArchive) {
		this.key = key;
		this.archiveType = archiveType;
		this.forceRefetchArchive = forceRefetchArchive;
	}
	
	public Bucket get(String internalName, ArchiveContext archiveContext,
			ClientMetadata dm, int recursionLevel,
			boolean dontEnterImplicitArchives, ArchiveManager manager)
			throws ArchiveFailureException, ArchiveRestartException,
			MetadataParseException, FetchException {
		
		// Do loop detection on the archive that we are about to fetch.
		archiveContext.doLoopDetection(key);
		
		if(forceRefetchArchive) return null;
		
		Bucket data;
		
		// Fetch from cache
		if(Logger.shouldLog(Logger.MINOR, this))
			Logger.minor(this, "Checking cache: "+key+ ' ' +internalName);
		if((data = manager.getCached(key, internalName)) != null) {
			return data;
		}	
		
		return null;
	}

	public Bucket getMetadata(ArchiveContext archiveContext, ClientMetadata dm,
			int recursionLevel, boolean dontEnterImplicitArchives,
			ArchiveManager manager) throws ArchiveFailureException,
			ArchiveRestartException, MetadataParseException, FetchException {
		return get(".metadata", archiveContext, dm, recursionLevel, dontEnterImplicitArchives, manager);
	}

	public void extractToCache(Bucket bucket, ArchiveContext actx,
			String element, ArchiveExtractCallback callback,
			ArchiveManager manager, ObjectContainer container, ClientContext context) throws ArchiveFailureException,
			ArchiveRestartException {
		forceRefetchArchive = false; // now we don't need to force refetch any more
		ArchiveStoreContext ctx = manager.makeContext(key, archiveType, false);
		manager.extractToCache(key, archiveType, bucket, actx, ctx, element, callback, container, context);
	}

	public short getArchiveType() {
		return archiveType;
	}

	public FreenetURI getKey() {
		return key;
	}

	/**
	 * Unpack a fetched archive on a separate thread for a persistent caller.
	 * This involves:
	 * - Add a tag to the database so that it will be restarted on a crash.
	 * - Run the actual unpack on a separate thread.
	 * - Copy the data to a persistent bucket.
	 * - Schedule a database job.
	 * - Call the callback.
	 * @param bucket
	 * @param actx
	 * @param element
	 * @param callback
	 * @param container
	 * @param context
	 */
	public void extractPersistentOffThread(Bucket bucket, ArchiveContext actx, String element, ArchiveExtractCallback callback, ObjectContainer container, final ClientContext context) {
		assert(element != null); // no callback would be called...
		final ArchiveManager manager = context.archiveManager;
		final ArchiveExtractTag tag = new ArchiveExtractTag(this, bucket, actx, element, callback, context.nodeDBHandle);
		container.set(tag);
		runPersistentOffThread(tag, context, manager);
	}

	private static void runPersistentOffThread(final ArchiveExtractTag tag, final ClientContext context, final ArchiveManager manager) {
		final ProxyCallback proxyCallback = new ProxyCallback();
		
		context.mainExecutor.execute(new Runnable() {

			public void run() {
				try {
					tag.handler.extractToCache(tag.data, tag.actx, tag.element, proxyCallback, manager, null, context);
					context.jobRunner.queue(new DBJob() {

						public void run(ObjectContainer container, ClientContext context) {
							container.delete(tag);
							if(proxyCallback.data == null)
								tag.callback.notInArchive(container, context);
							else
								tag.callback.gotBucket(proxyCallback.data, container, context);
						}
						
					}, NativeThread.NORM_PRIORITY, false);
					
				} catch (final ArchiveFailureException e) {
					
					context.jobRunner.queue(new DBJob() {

						public void run(ObjectContainer container, ClientContext context) {
							container.delete(tag);
							tag.callback.onFailed(e, container, context);
						}
						
					}, NativeThread.NORM_PRIORITY, false);
					
				} catch (final ArchiveRestartException e) {
					
					context.jobRunner.queue(new DBJob() {

						public void run(ObjectContainer container, ClientContext context) {
							container.delete(tag);
							tag.callback.onFailed(e, container, context);
						}
						
					}, NativeThread.NORM_PRIORITY, false);
					
				}
			}
			
		}, "Off-thread extract");
	}

	/** Called from ArchiveManager.init() */
	static void init(ObjectContainer container, ClientContext context, final long nodeDBHandle) {
		ObjectSet set = container.query(new Predicate() {
			public boolean match(ArchiveExtractTag tag) {
				return tag.nodeDBHandle == nodeDBHandle;
			}
		});
		while(set.hasNext()) {
			ArchiveExtractTag tag = (ArchiveExtractTag) set.next();
			runPersistentOffThread(tag, context, context.archiveManager);
		}
	}
	
	private static class ProxyCallback implements ArchiveExtractCallback {

		Bucket data;
		
		public void gotBucket(Bucket data, ObjectContainer container, ClientContext context) {
			this.data = data;
		}

		public void notInArchive(ObjectContainer container, ClientContext context) {
			this.data = null;
		}

		public void onFailed(ArchiveRestartException e, ObjectContainer container, ClientContext context) {
			// Must not be called.
			throw new UnsupportedOperationException();
		}

		public void onFailed(ArchiveFailureException e, ObjectContainer container, ClientContext context) {
			// Must not be called.
			throw new UnsupportedOperationException();
		}
		
	}
	
}

class ArchiveExtractTag {
	
	final ArchiveHandlerImpl handler;
	final Bucket data;
	final ArchiveContext actx;
	final String element;
	final ArchiveExtractCallback callback;
	final long nodeDBHandle;
	
	ArchiveExtractTag(ArchiveHandlerImpl handler, Bucket data, ArchiveContext actx, String element, ArchiveExtractCallback callback, long nodeDBHandle) {
		this.handler = handler;
		this.data = data;
		this.actx = actx;
		this.element = element;
		this.callback = callback;
		this.nodeDBHandle = nodeDBHandle;
	}
	
}