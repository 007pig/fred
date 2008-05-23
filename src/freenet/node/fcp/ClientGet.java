/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;

import freenet.client.FetchContext;
import freenet.client.FetchException;
import freenet.client.FetchResult;
import freenet.client.InsertException;
import freenet.client.async.BaseClientPutter;
import freenet.client.async.BinaryBlob;
import freenet.client.async.ClientCallback;
import freenet.client.async.ClientGetter;
import freenet.client.async.ClientRequester;
import freenet.client.events.ClientEvent;
import freenet.client.events.ClientEventListener;
import freenet.client.events.SplitfileProgressEvent;
import freenet.keys.FreenetURI;
import freenet.support.Fields;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;
import freenet.support.api.Bucket;
import freenet.support.io.CannotCreateFromFieldSetException;
import freenet.support.io.FileBucket;
import freenet.support.io.FileUtil;
import freenet.support.io.NullBucket;
import freenet.support.io.SerializableToFieldSetBucketUtil;

/**
 * A simple client fetch. This can of course fetch arbitrarily large
 * files, including splitfiles, redirects, etc.
 */
public class ClientGet extends ClientRequest implements ClientCallback, ClientEventListener {

	private final FetchContext fctx;
	private final ClientGetter getter;
	private final short returnType;
	private final File targetFile;
	private final File tempFile;
	/** Bucket passed in to the ClientGetter to return data in. Null unless returntype=disk */
	private Bucket returnBucket;
	private final boolean binaryBlob;

	// Verbosity bitmasks
	private int VERBOSITY_SPLITFILE_PROGRESS = 1;

	// Stuff waiting for reconnection
	/** Did the request succeed? Valid if finished. */
	private boolean succeeded;
	/** Length of the found data */
	private long foundDataLength = -1;
	/** MIME type of the found data */
	private String foundDataMimeType;
	/** Details of request failure */
	private GetFailedMessage getFailedMessage;
	/** Succeeded but failed to return data e.g. couldn't write to file */
	private ProtocolErrorMessage postFetchProtocolErrorMessage;
	/** AllData (the actual direct-send data) - do not persist, because the bucket
	 * is not persistent. FIXME make the bucket persistent! */
	private AllDataMessage allDataPending;
	/** Last progress message. Not persistent - FIXME this will be made persistent
	 * when we have proper persistence at the ClientGetter level. */
	private SimpleProgressMessage progressPending;

	/**
	 * Create one for a global-queued request not made by FCP.
	 * @throws IdentifierCollisionException
	 * @throws NotAllowedException 
	 */
	public ClientGet(FCPClient globalClient, FreenetURI uri, boolean dsOnly, boolean ignoreDS,
			int maxSplitfileRetries, int maxNonSplitfileRetries, long maxOutputLength,
			short returnType, boolean persistRebootOnly, String identifier, int verbosity, short prioClass,
			File returnFilename, File returnTempFilename) throws IdentifierCollisionException, NotAllowedException {
		super(uri, identifier, verbosity, null, globalClient, prioClass,
				(persistRebootOnly ? ClientRequest.PERSIST_REBOOT : ClientRequest.PERSIST_FOREVER),
				null, true);

		fctx = new FetchContext(client.defaultFetchContext, FetchContext.IDENTICAL_MASK, false);
		fctx.eventProducer.addEventListener(this);
		fctx.localRequestOnly = dsOnly;
		fctx.ignoreStore = ignoreDS;
		fctx.maxNonSplitfileRetries = maxNonSplitfileRetries;
		fctx.maxSplitfileBlockRetries = maxSplitfileRetries;
		fctx.maxOutputLength = maxOutputLength;
		fctx.maxTempLength = maxOutputLength;
		Bucket ret = null;
		this.returnType = returnType;
		binaryBlob = false;
		if(returnType == ClientGetMessage.RETURN_TYPE_DISK) {
			this.targetFile = returnFilename;
			this.tempFile = returnTempFilename;
			if(!(client.core.allowDownloadTo(returnTempFilename) && client.core.allowDownloadTo(returnFilename)))
				throw new NotAllowedException();
			ret = new FileBucket(returnTempFilename, false, true, false, false, false);
		} else if(returnType == ClientGetMessage.RETURN_TYPE_NONE) {
			targetFile = null;
			tempFile = null;
			ret = new NullBucket();
		} else {
			targetFile = null;
			tempFile = null;
			try {
				if(persistenceType == PERSIST_FOREVER)
					ret = client.server.core.persistentTempBucketFactory.makeEncryptedBucket();
				else
					ret = fctx.bucketFactory.makeBucket(-1);
			} catch (IOException e) {
				Logger.error(this, "Cannot create bucket for temp storage: "+e, e);
				onFailure(new FetchException(FetchException.BUCKET_ERROR, e), null);
				getter = null;
				returnBucket = null;
				return;
			}
		}
		returnBucket = ret;
		if(persistenceType != PERSIST_CONNECTION) {
			try {
				client.register(this, false);
			} catch (IdentifierCollisionException e) {
				ret.free();
				throw e;
			}
		}
		getter = new ClientGetter(this, client.core.requestStarters.chkFetchScheduler, client.core.requestStarters.sskFetchScheduler, uri, fctx, priorityClass, client.lowLevelClient, returnBucket, null);
		if(persistenceType != PERSIST_CONNECTION) {
			FCPMessage msg = persistentTagMessage();
			client.queueClientRequestMessage(msg, 0);
		}
	}

	public ClientGet(FCPConnectionHandler handler, ClientGetMessage message) throws IdentifierCollisionException, MessageInvalidException {
		super(message.uri, message.identifier, message.verbosity, handler, message.priorityClass,
				message.persistenceType, message.clientToken, message.global);
		// Create a Fetcher directly in order to get more fine-grained control,
		// since the client may override a few context elements.
		fctx = new FetchContext(client.defaultFetchContext, FetchContext.IDENTICAL_MASK, false);
		fctx.eventProducer.addEventListener(this);
		// ignoreDS
		fctx.localRequestOnly = message.dsOnly;
		fctx.ignoreStore = message.ignoreDS;
		fctx.maxNonSplitfileRetries = message.maxRetries;
		fctx.maxSplitfileBlockRetries = message.maxRetries;
		// FIXME do something with verbosity !!
		// Has already been checked
		fctx.maxOutputLength = message.maxSize;
		fctx.maxTempLength = message.maxTempSize;

		if(message.allowedMIMETypes != null) {
			fctx.allowedMIMETypes = new HashSet();
			for(int i=0;i<message.allowedMIMETypes.length;i++) fctx.allowedMIMETypes.add(message.allowedMIMETypes[i]);
		}

		this.returnType = message.returnType;
		this.binaryBlob = message.binaryBlob;
		Bucket ret = null;
		if(returnType == ClientGetMessage.RETURN_TYPE_DISK) {
			this.targetFile = message.diskFile;
			this.tempFile = message.tempFile;
			if(!(client.core.allowDownloadTo(tempFile) && client.core.allowDownloadTo(targetFile)))
				throw new MessageInvalidException(ProtocolErrorMessage.ACCESS_DENIED, "Not allowed to download to "+tempFile+" or "+targetFile, identifier, global);
			else if(!(handler.allowDDAFrom(tempFile, true) && handler.allowDDAFrom(targetFile, true)))
				throw new MessageInvalidException(ProtocolErrorMessage.DIRECT_DISK_ACCESS_DENIED, "Not allowed to download to "+tempFile+" or "+targetFile + ". You might need to do a " + TestDDARequestMessage.NAME + " first.", identifier, global);
			ret = new FileBucket(message.tempFile, false, true, false, false, false);
		} else if(returnType == ClientGetMessage.RETURN_TYPE_NONE) {
			targetFile = null;
			tempFile = null;
			ret = new NullBucket();
		} else {
			targetFile = null;
			tempFile = null;
			try {
				if(persistenceType == PERSIST_FOREVER)
					ret = client.server.core.persistentTempBucketFactory.makeEncryptedBucket();
				else
					ret = fctx.bucketFactory.makeBucket(-1);
			} catch (IOException e) {
				Logger.error(this, "Cannot create bucket for temp storage: "+e, e);
				onFailure(new FetchException(FetchException.BUCKET_ERROR, e), null);
				getter = null;
				returnBucket = null;
				return;
			}
		}
		if(ret == null)
			Logger.error(this, "Impossible: ret = null in FCP constructor for "+this, new Exception("debug"));
		returnBucket = ret;
		if(persistenceType != PERSIST_CONNECTION) {
			try {
				client.register(this, false);
			} catch (IdentifierCollisionException e) {
				ret.free();
				throw e;
			}
		}
		getter = new ClientGetter(this, client.core.requestStarters.chkFetchScheduler, 
				client.core.requestStarters.sskFetchScheduler, uri, fctx, priorityClass, 
				client.lowLevelClient, binaryBlob ? new NullBucket() : returnBucket, 
						binaryBlob ? returnBucket : null);
		if(persistenceType != PERSIST_CONNECTION) {
			FCPMessage msg = persistentTagMessage();
			client.queueClientRequestMessage(msg, 0);
			if(handler != null && (!handler.isGlobalSubscribed()))
				handler.outputHandler.queue(msg);
		}
	}

	/**
	 * Create a ClientGet from a request serialized to a SimpleFieldSet.
	 * Can throw, and does minimal verification, as is dealing with data
	 * supposedly serialized out by the node.
	 * @throws IOException
	 */
	public ClientGet(SimpleFieldSet fs, FCPClient client2) throws IOException {
		super(fs, client2);

		returnType = ClientGetMessage.parseValidReturnType(fs.get("ReturnType"));
		String f = fs.get("Filename");
		if(f != null)
			targetFile = new File(f);
		else
			targetFile = null;
		f = fs.get("TempFilename");
		if(f != null)
			tempFile = new File(f);
		else
			tempFile = null;
		boolean ignoreDS = Fields.stringToBool(fs.get("IgnoreDS"), false);
		boolean dsOnly = Fields.stringToBool(fs.get("DSOnly"), false);
		int maxRetries = Integer.parseInt(fs.get("MaxRetries"));
		fctx = new FetchContext(client.defaultFetchContext, FetchContext.IDENTICAL_MASK, false);
		fctx.eventProducer.addEventListener(this);
		// ignoreDS
		fctx.localRequestOnly = dsOnly;
		fctx.ignoreStore = ignoreDS;
		fctx.maxNonSplitfileRetries = maxRetries;
		fctx.maxSplitfileBlockRetries = maxRetries;
		binaryBlob = Fields.stringToBool(fs.get("BinaryBlob"), false);
		succeeded = Fields.stringToBool(fs.get("Succeeded"), false);
		if(finished) {
			if(succeeded) {
				foundDataLength = Long.parseLong(fs.get("FoundDataLength"));
				foundDataMimeType = fs.get("FoundDataMimeType");
				SimpleFieldSet fs1 = fs.subset("PostFetchProtocolError");
				if(fs1 != null)
					postFetchProtocolErrorMessage = new ProtocolErrorMessage(fs1);
			} else {
				getFailedMessage = new GetFailedMessage(fs.subset("GetFailed"), false);
			}
		}
		Bucket ret = null;
		if(returnType == ClientGetMessage.RETURN_TYPE_DISK) {
			if (succeeded) {
				ret = new FileBucket(targetFile, false, true, false, false, false);
			} else {
				ret = new FileBucket(tempFile, false, true, false, false, false);
			}
		} else if(returnType == ClientGetMessage.RETURN_TYPE_NONE) {
			ret = new NullBucket();
		} else if(returnType == ClientGetMessage.RETURN_TYPE_DIRECT) {
			try {
				ret = SerializableToFieldSetBucketUtil.create(fs.subset("ReturnBucket"), fctx.random, client.server.core.persistentTempBucketFactory);
				if(ret == null) throw new CannotCreateFromFieldSetException("ret == null");
			} catch (CannotCreateFromFieldSetException e) {
				Logger.error(this, "Cannot read: "+this+" : "+e, e);
				try {
					// Create a new temp bucket
					if(persistenceType == PERSIST_FOREVER)
						ret = client.server.core.persistentTempBucketFactory.makeEncryptedBucket();
					else
						ret = fctx.bucketFactory.makeBucket(-1);
				} catch (IOException e1) {
					Logger.error(this, "Cannot create bucket for temp storage: "+e, e);
					onFailure(new FetchException(FetchException.BUCKET_ERROR, e), null);
					getter = null;
					returnBucket = null;
					return;
				}
			}
		} else {
			throw new IllegalArgumentException();
		}
		if(succeeded) {
			if(foundDataLength < ret.size()) {
				Logger.error(this, "Failing "+identifier+" because lost data");
				succeeded = false;
			}
		}
		if(ret == null)
			Logger.error(this, "Impossible: ret = null in SFS constructor for "+this, new Exception("debug"));
		returnBucket = ret;

		String[] allowed = fs.getAll("AllowedMIMETypes");
		if(allowed != null) {
			fctx.allowedMIMETypes = new HashSet();
			for(int i=0;i<allowed.length;i++) fctx.allowedMIMETypes.add(allowed[i]);
		}

		getter = new ClientGetter(this, client.core.requestStarters.chkFetchScheduler, 
				client.core.requestStarters.sskFetchScheduler, uri, 
				fctx, priorityClass, client.lowLevelClient, 
				binaryBlob ? new NullBucket() : returnBucket, 
						binaryBlob ? returnBucket : null);

		if(persistenceType != PERSIST_CONNECTION) {
			FCPMessage msg = persistentTagMessage();
			client.queueClientRequestMessage(msg, 0);
		}

		if(finished && succeeded)
				allDataPending = new AllDataMessage(returnBucket, identifier, global, startupTime, completionTime);
	}

	public void start() {
		try {
			synchronized(this) {
				if(finished) return;
			}
			getter.start();
			if(persistenceType != PERSIST_CONNECTION && !finished) {
				FCPMessage msg = persistentTagMessage();
				client.queueClientRequestMessage(msg, 0);
			}
			synchronized(this) {
				started = true;
			}
		} catch (FetchException e) {
			synchronized(this) {
				started = true;
			} // before the failure handler
			onFailure(e, null);
		} catch (Throwable t) {
			synchronized(this) {
				started = true;
			}
			onFailure(new FetchException(FetchException.INTERNAL_ERROR, t), null);
		}
	}

	public void onLostConnection() {
		if(persistenceType == PERSIST_CONNECTION)
			cancel();
		// Otherwise ignore
	}

	public void onSuccess(FetchResult result, ClientGetter state) {
		Logger.minor(this, "Succeeded: "+identifier);
		Bucket data = result.asBucket();
		if(returnBucket != data && !binaryBlob) {
			boolean failed = true;
			synchronized(this) {
				if(finished) {
					Logger.error(this, "Already finished but onSuccess() for "+this+" data = "+data, new Exception("debug"));
					data.free();
					return; // Already failed - bucket error maybe??
				}
				if(returnType == ClientGetMessage.RETURN_TYPE_DIRECT && returnBucket == null) {
					// Lost bucket for some reason e.g. bucket error (caused by IOException) on previous try??
					// Recover...
					returnBucket = data;
					failed = false;
				}
			}
			if(failed) {
				Logger.error(this, "returnBucket = "+returnBucket+" but onSuccess() data = "+data, new Exception("debug"));
				// Caller guarantees that data == returnBucket
				onFailure(new FetchException(FetchException.INTERNAL_ERROR, "Data != returnBucket"), null);
				return;
			}
		}
		boolean dontFree = false;
		// FIXME I don't think this is a problem in this case...? (Disk write while locked..)
		AllDataMessage adm = null;
		synchronized(this) {
			if(succeeded) {
				Logger.error(this, "onSuccess called twice for "+this+" ("+identifier+ ')');
				return; // We might be called twice; ignore it if so.
			}
			started = true;
			if(returnType == ClientGetMessage.RETURN_TYPE_DIRECT) {
				// Send all the data at once
				// FIXME there should be other options
				// FIXME: CompletionTime is set on finish() : we need to give it current time here
				// but it means we won't always return the same value to clients... Does it matter ?
				adm = new AllDataMessage(returnBucket, identifier, global, startupTime, System.currentTimeMillis());
				if(persistenceType == PERSIST_CONNECTION)
					adm.setFreeOnSent();
				dontFree = true;
				/* 
				 * } else if(returnType == ClientGetMessage.RETURN_TYPE_NONE) {
				// Do nothing
				 */
			} else if(returnType == ClientGetMessage.RETURN_TYPE_DISK) {
				// Write to temp file, then rename over filename
				if(!FileUtil.renameTo(tempFile, targetFile)) {
					postFetchProtocolErrorMessage = new ProtocolErrorMessage(ProtocolErrorMessage.COULD_NOT_RENAME_FILE, false, null, identifier, global);
					// Don't delete temp file, user might want it.
				}
				returnBucket = new FileBucket(targetFile, false, true, false, false, false);
			}
			progressPending = null;
			this.foundDataLength = returnBucket.size();
			if(!binaryBlob)
				this.foundDataMimeType = result.getMimeType();
			else
				this.foundDataMimeType = BinaryBlob.MIME_TYPE;
			this.succeeded = true;
			finished = true;
		}
		trySendDataFoundOrGetFailed(null);

		if(adm != null)
			trySendAllDataMessage(adm, null);
		if(!dontFree)
			data.free();
		finish();
		client.notifySuccess(this);
	}

	private void trySendDataFoundOrGetFailed(FCPConnectionOutputHandler handler) {
		FCPMessage msg;

		// Don't need to lock. succeeded is only ever set, never unset.
		// and succeeded and getFailedMessage are both atomic.
		if(succeeded) {
			msg = new DataFoundMessage(foundDataLength, foundDataMimeType, identifier, global);
		} else {
			msg = getFailedMessage;
		}

		if(handler != null)
			handler.queue(msg);
		else
			client.queueClientRequestMessage(msg, 0);
		if(postFetchProtocolErrorMessage != null) {
			if(handler != null)
				handler.queue(postFetchProtocolErrorMessage);
			else
				client.queueClientRequestMessage(postFetchProtocolErrorMessage, 0);
		}

	}

	private void trySendAllDataMessage(AllDataMessage msg, FCPConnectionOutputHandler handler) {
		if(persistenceType != ClientRequest.PERSIST_CONNECTION) {
			allDataPending = msg;
		} else {
			client.queueClientRequestMessage(msg, 0);
		}
	}

	private void trySendProgress(SimpleProgressMessage msg, FCPConnectionOutputHandler handler) {
		if(persistenceType != ClientRequest.PERSIST_CONNECTION) {
			progressPending = msg;
		}
		client.queueClientRequestMessage(msg, VERBOSITY_SPLITFILE_PROGRESS);
	}

	public void sendPendingMessages(FCPConnectionOutputHandler handler, boolean includePersistentRequest, boolean includeData, boolean onlyData) {
		if(persistenceType == ClientRequest.PERSIST_CONNECTION) {
			Logger.error(this, "WTF? persistenceType="+persistenceType, new Exception("error"));
			return;
		}
		if(!onlyData) {
			if(includePersistentRequest) {
				FCPMessage msg = persistentTagMessage();
				handler.queue(msg);
			}
			if(progressPending != null)
				handler.queue(progressPending);
			if(finished)
				trySendDataFoundOrGetFailed(handler);
		}

		if (onlyData && allDataPending  == null) {
			Logger.error(this, "No data pending !");
		}

		if(includeData && (allDataPending != null))
			handler.queue(allDataPending);
	}

	protected FCPMessage persistentTagMessage() {
		return new PersistentGet(identifier, uri, verbosity, priorityClass, returnType, persistenceType, targetFile, tempFile, clientToken, client.isGlobalQueue, started, fctx.maxNonSplitfileRetries, binaryBlob, fctx.maxOutputLength);
	}

	public void onFailure(FetchException e, ClientGetter state) {
		if(finished) return;
		synchronized(this) {
			succeeded = false;
			getFailedMessage = new GetFailedMessage(e, identifier, global);
			finished = true;
			started = true;
		}
		if(Logger.shouldLog(Logger.MINOR, this))
			Logger.minor(this, "Caught "+e, e);
		trySendDataFoundOrGetFailed(null);
		finish();
		client.notifyFailure(this);
		if(persistenceType != PERSIST_CONNECTION)
			client.server.forceStorePersistentRequests();
	}

	public void onSuccess(BaseClientPutter state) {
		// Ignore
	}

	public void onFailure(InsertException e, BaseClientPutter state) {
		// Ignore
	}

	public void onGeneratedURI(FreenetURI uri, BaseClientPutter state) {
		// Ignore
	}

	public void requestWasRemoved() {
		// if request is still running, send a GetFailed with code=cancelled
		if( !finished ) {
			synchronized(this) {
				succeeded = false;
				finished = true;
				FetchException cancelled = new FetchException(FetchException.CANCELLED);
				getFailedMessage = new GetFailedMessage(cancelled, identifier, global);
			}
			trySendDataFoundOrGetFailed(null);
		}
		// notify client that request was removed
		FCPMessage msg = new PersistentRequestRemovedMessage(getIdentifier(), global);
		client.queueClientRequestMessage(msg, 0);

		freeData();
		finish();
	}

	public void receive(ClientEvent ce) {
		// Don't need to lock, verbosity is final and finished is never unset.
		if(finished) return;
		if(!(((verbosity & VERBOSITY_SPLITFILE_PROGRESS) == VERBOSITY_SPLITFILE_PROGRESS) &&
				(ce instanceof SplitfileProgressEvent)))
			return;
		SimpleProgressMessage progress =
			new SimpleProgressMessage(identifier, global, (SplitfileProgressEvent)ce);
		trySendProgress(progress, null);
	}

	// This is distinct from the ClientGetMessage code, as later on it will be radically
	// different (it can store detailed state).
	public synchronized SimpleFieldSet getFieldSet() {
		SimpleFieldSet fs = new SimpleFieldSet(false); // we will need multi-level later...
		fs.putSingle("Type", "GET");
		fs.putSingle("URI", uri.toString(false, false));
		fs.putSingle("Identifier", identifier);
		fs.putSingle("Verbosity", Integer.toString(verbosity));
		fs.putSingle("PriorityClass", Short.toString(priorityClass));
		fs.putSingle("ReturnType", ClientGetMessage.returnTypeString(returnType));
		fs.putSingle("Persistence", persistenceTypeString(persistenceType));
		fs.putSingle("ClientName", client.name);
		if(targetFile != null)
			fs.putSingle("Filename", targetFile.getPath());
		if(tempFile != null)
			fs.putSingle("TempFilename", tempFile.getPath());
		if(clientToken != null)
			fs.putSingle("ClientToken", clientToken);
		fs.putSingle("IgnoreDS", Boolean.toString(fctx.ignoreStore));
		fs.putSingle("DSOnly", Boolean.toString(fctx.localRequestOnly));
		fs.putSingle("MaxRetries", Integer.toString(fctx.maxNonSplitfileRetries));
		fs.putSingle("Finished", Boolean.toString(finished));
		fs.putSingle("Succeeded", Boolean.toString(succeeded));
		if(fctx.allowedMIMETypes != null)
			fs.putOverwrite("AllowedMIMETypes", (String[]) fctx.allowedMIMETypes.toArray(new String[fctx.allowedMIMETypes.size()]));
		if(finished) {
			if(succeeded) {
				fs.putSingle("FoundDataLength", Long.toString(foundDataLength));
				fs.putSingle("FoundDataMimeType", foundDataMimeType);
				if(postFetchProtocolErrorMessage != null) {
					fs.put("PostFetchProtocolError", postFetchProtocolErrorMessage.getFieldSet());
				}
			} else {
				if(getFailedMessage != null) {
					fs.put("GetFailed", getFailedMessage.getFieldSet(false));
				}
			}
		}
		// Return bucket
		if(returnType == ClientGetMessage.RETURN_TYPE_DIRECT && !(succeeded == false && returnBucket == null)) {
			bucketToFS(fs, "ReturnBucket", false, returnBucket);
		}
		fs.putSingle("Global", Boolean.toString(client.isGlobalQueue));
		fs.put("BinaryBlob", binaryBlob);
		fs.put("StartupTime", startupTime);
		if(finished)
			fs.put("CompletionTime", completionTime);

		return fs;
	}

	protected ClientRequester getClientRequest() {
		return getter;
	}

	protected void freeData() {
		if(returnBucket != null)
			returnBucket.free();
	}

	public boolean hasSucceeded() {
		return succeeded;
	}

	public boolean isDirect() {
		return this.returnType == ClientGetMessage.RETURN_TYPE_DIRECT;
	}

	public boolean isToDisk() {
		return this.returnType == ClientGetMessage.RETURN_TYPE_DISK;
	}

	public FreenetURI getURI() {
		return uri;
	}

	public long getDataSize() {
		if(foundDataLength > 0)
			return foundDataLength;
		if(getter != null)
			return getter.expectedSize();
		return -1;
	}

	public String getMIMEType() {
		if(foundDataMimeType != null)
			return foundDataMimeType;
		if(getter != null)
			return getter.expectedMIME();
		return null;
	}

	public File getDestFilename() {
		return targetFile;
	}

	public double getSuccessFraction() {
		if(progressPending != null) {
			return progressPending.getFraction();
		} else
			return -1;
	}

	public double getTotalBlocks() {
		if(progressPending != null) {
			return progressPending.getTotalBlocks();
		} else
			return 1;
	}

	public double getMinBlocks() {
		if(progressPending != null) {
			return progressPending.getMinBlocks();
		} else
			return 1;
	}

	public double getFailedBlocks() {
		if(progressPending != null) {
			return progressPending.getFailedBlocks();
		} else
			return 0;
	}

	public double getFatalyFailedBlocks() {
		if(progressPending != null) {
			return progressPending.getFatalyFailedBlocks();
		} else
			return 0;
	}

	public double getFetchedBlocks() {
		if(progressPending != null) {
			return progressPending.getFetchedBlocks();
		} else
			return 0;
	}

	public String getFailureReason() {
		if(getFailedMessage == null)
			return null;
		String s = getFailedMessage.shortCodeDescription;
		if(getFailedMessage.extraDescription != null)
			s += ": "+getFailedMessage.extraDescription;
		return s;
	}


	public boolean isTotalFinalized() {
		if(finished && succeeded) return true;
		if(progressPending == null) return false;
		else return progressPending.isTotalFinalized();
	}

	/**
	 * Returns the {@link Bucket} that contains the downloaded data.
	 *
	 * @return The data in a {@link Bucket}, or <code>null</code> if this
	 *         isn&rsquo;t applicable
	 */
	public Bucket getBucket() {
		synchronized(this) {
			if(targetFile != null) {
				if(succeeded || tempFile == null)
					return new FileBucket(targetFile, false, true, false, false, false);
				else
					return new FileBucket(tempFile, false, true, false, false, false);
			} else return returnBucket;
		}
	}

	public void onFetchable(BaseClientPutter state) {
		// Ignore, we don't insert
	}

	public boolean canRestart() {
		if(!finished) {
			Logger.minor(this, "Cannot restart because not finished for "+identifier);
			return false;
		}
		if(succeeded) {
			Logger.minor(this, "Cannot restart because succeeded for "+identifier);
			return false;
		}
		return getter.canRestart();
	}

	public boolean restart() {
		if(!canRestart()) return false;
		FreenetURI redirect;
		synchronized(this) {
			finished = false;
			redirect = 
				getFailedMessage == null ? null : getFailedMessage.redirectURI;
			this.getFailedMessage = null;
			this.allDataPending = null;
			this.postFetchProtocolErrorMessage = null;
			this.progressPending = null;
			started = false;
		}
		try {
			if(getter.restart(redirect)) {
				synchronized(this) {
					if(redirect != null) this.uri = redirect;
					started = true;
				}
			}
			return true;
		} catch (FetchException e) {
			onFailure(e, null);
			return false;
		}
	}

	public synchronized boolean hasPermRedirect() {
		return getFailedMessage != null && getFailedMessage.redirectURI != null;
	}
}
