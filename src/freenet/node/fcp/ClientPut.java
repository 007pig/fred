/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.security.MessageDigest;
import java.util.Arrays;

import freenet.client.ClientMetadata;
import freenet.client.DefaultMIMETypes;
import freenet.client.FetchException;
import freenet.client.FetchResult;
import freenet.client.InsertException;
import freenet.client.Metadata;
import freenet.client.MetadataUnresolvedException;
import freenet.client.async.BinaryBlob;
import freenet.client.async.ClientGetter;
import freenet.client.async.ClientPutter;
import freenet.crypt.SHA256;
import freenet.keys.FreenetURI;
import freenet.support.Base64;
import freenet.support.IllegalBase64Exception;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;
import freenet.support.SimpleReadOnlyArrayBucket;
import freenet.support.api.Bucket;
import freenet.support.io.CannotCreateFromFieldSetException;
import freenet.support.io.FileBucket;
import freenet.support.io.SerializableToFieldSetBucketUtil;

public class ClientPut extends ClientPutBase {

	final ClientPutter putter;
	private final short uploadFrom;
	/** Original filename if from disk, otherwise null. Purely for PersistentPut. */
	private final File origFilename;
	/** If uploadFrom==UPLOAD_FROM_REDIRECT, this is the target of the redirect */
	private final FreenetURI targetURI;
	private final Bucket data;
	private final ClientMetadata clientMetadata;
	/** We store the size of inserted data before freeing it */
	private long finishedSize;
	/** Filename if the file has one */
	private final String targetFilename;
	private boolean logMINOR;
	/** If true, we are inserting a binary blob: No metadata, no URI is generated. */
	private final boolean binaryBlob;
	
	/**
	 * Creates a new persistent insert.
	 * 
	 * @param uri
	 *            The URI to insert data to
	 * @param identifier
	 *            The identifier of the insert
	 * @param verbosity
	 *            The verbosity bitmask
	 * @param handler
	 *            The FCP connection handler
	 * @param priorityClass
	 *            The priority for this insert
	 * @param persistenceType
	 *            The persistence type of this insert
	 * @param clientToken
	 *            The client token of this insert
	 * @param global
	 *            Whether this insert appears on the global queue
	 * @param getCHKOnly
	 *            Whether only the resulting CHK is requested
	 * @param dontCompress
	 *            Whether the file should not be compressed
	 * @param maxRetries
	 *            The maximum number of retries
	 * @param uploadFromType
	 *            Where the file is uploaded from
	 * @param origFilename
	 *            The original filename
	 * @param contentType
	 *            The content type of the data
	 * @param data
	 *            The data (may be <code>null</code> if
	 *            <code>uploadFromType</code> is UPLOAD_FROM_REDIRECT)
	 * @param redirectTarget
	 *            The URI to redirect to (if <code>uploadFromType</code> is
	 *            UPLOAD_FROM_REDIRECT)
	 * @throws IdentifierCollisionException
	 * @throws NotAllowedException 
	 * @throws FileNotFoundException 
	 * @throws MalformedURLException 
	 */
	public ClientPut(FCPClient globalClient, FreenetURI uri, String identifier, int verbosity, 
			short priorityClass, short persistenceType, String clientToken, boolean getCHKOnly,
			boolean dontCompress, int maxRetries, short uploadFromType, File origFilename, String contentType,
			Bucket data, FreenetURI redirectTarget, String targetFilename, boolean earlyEncode) throws IdentifierCollisionException, NotAllowedException, FileNotFoundException, MalformedURLException {
		super(uri, identifier, verbosity, null, globalClient, priorityClass, persistenceType, null, true, getCHKOnly, dontCompress, maxRetries, earlyEncode);
		if(uploadFromType == ClientPutMessage.UPLOAD_FROM_DISK) {
			if(!globalClient.core.allowUploadFrom(origFilename))
				throw new NotAllowedException();
			if(!(origFilename.exists() && origFilename.canRead()))
				throw new FileNotFoundException();
		}

		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		binaryBlob = false;
		this.targetFilename = targetFilename;
		this.uploadFrom = uploadFromType;
		this.origFilename = origFilename;
		// Now go through the fields one at a time
		String mimeType = contentType;
		this.clientToken = clientToken;
		if(persistenceType != PERSIST_CONNECTION)
			client.register(this, false);
		Bucket tempData = data;
		ClientMetadata cm = new ClientMetadata(mimeType, null);
		boolean isMetadata = false;
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		if(logMINOR) Logger.minor(this, "data = "+tempData+", uploadFrom = "+ClientPutMessage.uploadFromString(uploadFrom));
		if(uploadFrom == ClientPutMessage.UPLOAD_FROM_REDIRECT) {
			this.targetURI = redirectTarget;
			Metadata m = new Metadata(Metadata.SIMPLE_REDIRECT, null, null, targetURI, cm);
			byte[] d;
			try {
				d = m.writeToByteArray();
			} catch (MetadataUnresolvedException e) {
				// Impossible
				Logger.error(this, "Impossible: "+e, e);
				onFailure(new InsertException(InsertException.INTERNAL_ERROR, "Impossible: "+e+" in ClientPut", null), null);
				this.data = null;
				clientMetadata = cm;
				putter = null;
				return;
			}
			tempData = new SimpleReadOnlyArrayBucket(d);
			isMetadata = true;
		} else
			targetURI = null;

		this.data = tempData;
		this.clientMetadata = cm;

		putter = new ClientPutter(this, data, uri, cm, 
				ctx, client.core.requestStarters.chkPutScheduler, client.core.requestStarters.sskPutScheduler, priorityClass, 
				getCHKOnly, isMetadata, client.lowLevelClient, null, targetFilename, binaryBlob);
		if(persistenceType != PERSIST_CONNECTION) {
			FCPMessage msg = persistentTagMessage();
			client.queueClientRequestMessage(msg, 0);
		}
	}
	
	public ClientPut(FCPConnectionHandler handler, ClientPutMessage message) throws IdentifierCollisionException, MessageInvalidException, MalformedURLException {
		super(message.uri, message.identifier, message.verbosity, handler, 
				message.priorityClass, message.persistenceType, message.clientToken, message.global,
				message.getCHKOnly, message.dontCompress, message.maxRetries, message.earlyEncode);
		String salt = null;
		byte[] saltedHash = null;
		binaryBlob = message.binaryBlob;
		
		if(message.uploadFromType == ClientPutMessage.UPLOAD_FROM_DISK) {
			if(!handler.server.core.allowUploadFrom(message.origFilename))
				throw new MessageInvalidException(ProtocolErrorMessage.ACCESS_DENIED, "Not allowed to upload from "+message.origFilename, identifier, global);

			if(message.fileHash != null) {
				try {
					salt = handler.connectionIdentifier + '-' + message.identifier + '-';
					saltedHash = Base64.decode(message.fileHash);
				} catch (IllegalBase64Exception e) {
					throw new MessageInvalidException(ProtocolErrorMessage.INVALID_FIELD, "Can't base64 decode " + ClientPutBase.FILE_HASH, identifier, global);
				}
			} else if(!handler.allowDDAFrom(message.origFilename, false))
				throw new MessageInvalidException(ProtocolErrorMessage.DIRECT_DISK_ACCESS_DENIED, "Not allowed to upload from "+message.origFilename+". Have you done a testDDA previously ?", identifier, global);		
		}
			
		this.targetFilename = message.targetFilename;
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		this.uploadFrom = message.uploadFromType;
		this.origFilename = message.origFilename;
		// Now go through the fields one at a time
		String mimeType = message.contentType;
		if(binaryBlob) {
			if(mimeType != null && !mimeType.equals(BinaryBlob.MIME_TYPE)) {
				throw new MessageInvalidException(ProtocolErrorMessage.INVALID_FIELD, "No MIME type allowed when inserting a binary blob", identifier, global);
			}
		}
		if(mimeType == null && origFilename != null) {
			mimeType = DefaultMIMETypes.guessMIMEType(origFilename.getName(), true);
		}
		if ((mimeType == null) && (targetFilename != null)) {
			mimeType = DefaultMIMETypes.guessMIMEType(targetFilename, true);
		}
		if(mimeType == null) {
			mimeType = DefaultMIMETypes.guessMIMEType(identifier, true);
		}
		clientToken = message.clientToken;
		if(persistenceType != PERSIST_CONNECTION)
			client.register(this, false);
		Bucket tempData = message.bucket;
		ClientMetadata cm = new ClientMetadata(mimeType, null);
		boolean isMetadata = false;
		if(logMINOR) Logger.minor(this, "data = "+tempData+", uploadFrom = "+ClientPutMessage.uploadFromString(uploadFrom));
		if(uploadFrom == ClientPutMessage.UPLOAD_FROM_REDIRECT) {
			this.targetURI = message.redirectTarget;
			Metadata m = new Metadata(Metadata.SIMPLE_REDIRECT, null, null, targetURI, cm);
			byte[] d;
			try {
				d = m.writeToByteArray();
			} catch (MetadataUnresolvedException e) {
				// Impossible
				Logger.error(this, "Impossible: "+e, e);
				onFailure(new InsertException(InsertException.INTERNAL_ERROR, "Impossible: "+e+" in ClientPut", null), null);
				this.data = null;
				clientMetadata = cm;
				putter = null;
				return;
			}
			tempData = new SimpleReadOnlyArrayBucket(d);
			isMetadata = true;
		} else
			targetURI = null;
		this.data = tempData;
		this.clientMetadata = cm;
		
		// Check the hash : allow it to be null for backward compatibility and if testDDA is allowed
		if(salt != null) {
			MessageDigest md = SHA256.getMessageDigest();
			byte[] foundHash;
			try {
				try {
					md.update(salt.getBytes("UTF-8"));
				} catch (UnsupportedEncodingException e) {
					throw new Error("Impossible: JVM doesn't support UTF-8: " + e, e);
				}
				try {
					InputStream is = data.getInputStream();
					SHA256.hash(is, md);
					is.close();
				} catch (IOException e) {
					SHA256.returnMessageDigest(md);
					Logger.error(this, "Got IOE: " + e.getMessage(), e);
					throw new MessageInvalidException(ProtocolErrorMessage.COULD_NOT_READ_FILE,
					        "Unable to access file: " + e, identifier, global);
				}
				foundHash = md.digest();
			} finally {
				SHA256.returnMessageDigest(md);
			}

			if(logMINOR) Logger.minor(this, "FileHash result : we found " + Base64.encode(foundHash) + " and were given " + Base64.encode(saltedHash) + '.');

			if(!Arrays.equals(saltedHash, foundHash))
				throw new MessageInvalidException(ProtocolErrorMessage.DIRECT_DISK_ACCESS_DENIED, "The hash doesn't match! (salt used : \""+salt+"\")", identifier, global);
		}
		
		if(logMINOR) Logger.minor(this, "data = "+data+", uploadFrom = "+ClientPutMessage.uploadFromString(uploadFrom));
		putter = new ClientPutter(this, data, uri, cm, 
				ctx, client.core.requestStarters.chkPutScheduler, client.core.requestStarters.sskPutScheduler, priorityClass, 
				getCHKOnly, isMetadata, client.lowLevelClient, null, targetFilename, binaryBlob);
		if(persistenceType != PERSIST_CONNECTION) {
			FCPMessage msg = persistentTagMessage();
			client.queueClientRequestMessage(msg, 0);
			if(handler != null && (!handler.isGlobalSubscribed()))
				handler.outputHandler.queue(msg);
		}
	}
	
	/**
	 * Create from a persisted SimpleFieldSet.
	 * Not very tolerant of errors, as the input was generated
	 * by the node.
	 * @throws PersistenceParseException 
	 * @throws IOException 
	 */
	public ClientPut(SimpleFieldSet fs, FCPClient client2) throws PersistenceParseException, IOException {
		super(fs, client2);
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		String mimeType = fs.get("Metadata.ContentType");

		String from = fs.get("UploadFrom");
		
		if(from.equals("direct")) {
			uploadFrom = ClientPutMessage.UPLOAD_FROM_DIRECT;
		} else if(from.equals("disk")) {
			uploadFrom = ClientPutMessage.UPLOAD_FROM_DISK;
		} else if(from.equals("redirect")) {
			uploadFrom = ClientPutMessage.UPLOAD_FROM_REDIRECT;
		} else {
				throw new PersistenceParseException("Unknown UploadFrom: "+from);
		}
		
		ClientMetadata cm = new ClientMetadata(mimeType, null);
		
		boolean isMetadata = false;
		
		binaryBlob = fs.getBoolean("BinaryBlob", false);
		targetFilename = fs.get("TargetFilename");
		
		if(uploadFrom == ClientPutMessage.UPLOAD_FROM_DISK) {
			origFilename = new File(fs.get("Filename"));
			if(logMINOR)
				Logger.minor(this, "Uploading from disk: "+origFilename+" for "+this);
			data = new FileBucket(origFilename, true, false, false, false, false);
			targetURI = null;
		} else if(uploadFrom == ClientPutMessage.UPLOAD_FROM_DIRECT) {
			origFilename = null;
			if(logMINOR)
				Logger.minor(this, "Uploading from direct for "+this);
			if(!finished) {
				try {
					data = SerializableToFieldSetBucketUtil.create(fs.subset("TempBucket"), ctx.random, client.server.core.persistentTempBucketFactory);
				} catch (CannotCreateFromFieldSetException e) {
					throw new PersistenceParseException("Could not read old bucket for "+identifier+" : "+e, e);
				}
			} else {
				if(Logger.shouldLog(Logger.MINOR, this)) 
					Logger.minor(this, "Finished already so not reading bucket for "+this);
				data = null;
			}
			targetURI = null;
		} else if(uploadFrom == ClientPutMessage.UPLOAD_FROM_REDIRECT) {
			String target = fs.get("TargetURI");
			targetURI = new FreenetURI(target);
			if(logMINOR)
				Logger.minor(this, "Uploading from redirect for "+this+" : "+targetURI);
			Metadata m = new Metadata(Metadata.SIMPLE_REDIRECT, null, null, targetURI, cm);
			byte[] d;
			try {
				d = m.writeToByteArray();
			} catch (MetadataUnresolvedException e) {
				// Impossible
				Logger.error(this, "Impossible: "+e, e);
				onFailure(new InsertException(InsertException.INTERNAL_ERROR, "Impossible: "+e+" in ClientPut", null), null);
				this.data = null;
				clientMetadata = cm;
				origFilename = null;
				putter = null;
				return;
			}
			data = new SimpleReadOnlyArrayBucket(d);
			origFilename = null;
			isMetadata = true;
		} else {
			throw new PersistenceParseException("shouldn't happen");
		}
		if(logMINOR) Logger.minor(this, "data = "+data);
		this.clientMetadata = cm;
		SimpleFieldSet oldProgress = fs.subset("progress");
		if(finished) oldProgress = null; // Not useful any more
		putter = new ClientPutter(this, data, uri, cm, ctx, client.core.requestStarters.chkPutScheduler, 
				client.core.requestStarters.sskPutScheduler, priorityClass, getCHKOnly, isMetadata, 
				client.lowLevelClient, oldProgress, targetFilename, binaryBlob);
		if(persistenceType != PERSIST_CONNECTION) {
			FCPMessage msg = persistentTagMessage();
			client.queueClientRequestMessage(msg, 0);
		}
		
	}

	@Override
	public void start() {
		if(Logger.shouldLog(Logger.MINOR, this))
			Logger.minor(this, "Starting "+this+" : "+identifier);
		synchronized(this) {
			if(finished) return;
		}
		try {
			putter.start(earlyEncode);
			if(persistenceType != PERSIST_CONNECTION && !finished) {
				FCPMessage msg = persistentTagMessage();
				client.queueClientRequestMessage(msg, 0);
			}
			synchronized(this) {
				started = true;
			}
		} catch (InsertException e) {
			synchronized(this) {
				started = true;
			}
			onFailure(e, null);
		} catch (Throwable t) {
			synchronized(this) {
				started = true;
			}
			onFailure(new InsertException(InsertException.INTERNAL_ERROR, t, null), null);
		}
	}

	@Override
	protected void freeData() {
		if(data == null) return;
		finishedSize=data.size();
		data.free();
	}
	
	@Override
	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet fs = super.getFieldSet();
		// This is all fixed, so no need for synchronization.
		if(clientMetadata.getMIMEType() != null)
			fs.putSingle("Metadata.ContentType", clientMetadata.getMIMEType());
		fs.putSingle("UploadFrom", ClientPutMessage.uploadFromString(uploadFrom));
		if(uploadFrom == ClientPutMessage.UPLOAD_FROM_DISK) {
			fs.putSingle("Filename", origFilename.getPath());
		} else if(uploadFrom == ClientPutMessage.UPLOAD_FROM_DIRECT) {
			if(!finished) {
				// the bucket is a persistent encrypted temp bucket
				bucketToFS(fs, "TempBucket", true, data);
			}
		} else if(uploadFrom == ClientPutMessage.UPLOAD_FROM_REDIRECT) {
			fs.putSingle("TargetURI", targetURI.toString());
		}
		if(putter != null)  {
			SimpleFieldSet sfs = putter.getProgressFieldset();
			fs.put("progress", sfs);
		}
		if(targetFilename != null)
			fs.putSingle("TargetFilename", targetFilename);
		fs.putSingle("EarlyEncode", Boolean.toString(earlyEncode));
		fs.put("BinaryBlob", binaryBlob);
		
		return fs;
	}

	@Override
	protected freenet.client.async.ClientRequester getClientRequest() {
		return putter;
	}

	@Override
	protected FCPMessage persistentTagMessage() {
		return new PersistentPut(identifier, publicURI, verbosity, priorityClass, uploadFrom, targetURI, 
				persistenceType, origFilename, clientMetadata.getMIMEType(), client.isGlobalQueue,
				getDataSize(), clientToken, started, ctx.maxInsertRetries, targetFilename, binaryBlob);
	}

	@Override
	protected String getTypeName() {
		return "PUT";
	}

	@Override
	public boolean hasSucceeded() {
		return succeeded;
	}

	public FreenetURI getFinalURI() {
		return generatedURI;
	}

	public boolean isDirect() {
		return uploadFrom == ClientPutMessage.UPLOAD_FROM_DIRECT;
	}

	public File getOrigFilename() {
		if(uploadFrom != ClientPutMessage.UPLOAD_FROM_DISK)
			return null;
		return origFilename;
	}

	public long getDataSize() {
		if(data == null)
			return finishedSize;
		else
			return data.size();
	}

	public String getMIMEType() {
		return clientMetadata.getMIMEType();
	}

	@Override
	public boolean canRestart() {
		if(!finished) {
			Logger.minor(this, "Cannot restart because not finished for "+identifier);
			return false;
		}
		if(succeeded) {
			Logger.minor(this, "Cannot restart because succeeded for "+identifier);
			return false;
		}
		return putter.canRestart();
	}

	@Override
	public boolean restart() {
		if(!canRestart()) return false;
		setVarsRestart();
		try {
			if(putter.restart(earlyEncode)) {
				synchronized(this) {
					generatedURI = null;
					started = true;
				}
			}
			return true;
		} catch (InsertException e) {
			onFailure(e, null);
			return false;
		}
	}

	public void onFailure(FetchException e, ClientGetter state) {}

	public void onSuccess(FetchResult result, ClientGetter state) {}
}
