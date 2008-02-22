/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import java.io.IOException;
import java.lang.ref.SoftReference;
import java.net.MalformedURLException;

import freenet.client.FailureCodeTracker;
import freenet.client.InsertContext;
import freenet.client.InsertException;
import freenet.keys.CHKEncodeException;
import freenet.keys.ClientCHKBlock;
import freenet.keys.ClientKeyBlock;
import freenet.keys.FreenetURI;
import freenet.keys.InsertableClientSSK;
import freenet.keys.SSKEncodeException;
import freenet.node.LowLevelPutException;
import freenet.node.NodeClientCore;
import freenet.node.RequestScheduler;
import freenet.node.SendableInsert;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;
import freenet.support.api.Bucket;

/**
 * Insert *ONE KEY*.
 */
public class SingleBlockInserter extends SendableInsert implements ClientPutState {

	private static boolean logMINOR;
	final Bucket sourceData;
	final short compressionCodec;
	final FreenetURI uri; // uses essentially no RAM in the common case of a CHK because we use FreenetURI.EMPTY_CHK_URI
	FreenetURI resultingURI;
	final PutCompletionCallback cb;
	final BaseClientPutter parent;
	final InsertContext ctx;
	private int retries;
	private final FailureCodeTracker errors;
	private boolean finished;
	private final boolean dontSendEncoded;
	private SoftReference refToClientKeyBlock;
	final int token; // for e.g. splitfiles
	private final Object tokenObject;
	final boolean isMetadata;
	final boolean getCHKOnly;
	final int sourceLength;
	private int consecutiveRNFs;
	
	public SingleBlockInserter(BaseClientPutter parent, Bucket data, short compressionCodec, FreenetURI uri, InsertContext ctx, PutCompletionCallback cb, boolean isMetadata, int sourceLength, int token, boolean getCHKOnly, boolean addToParent, boolean dontSendEncoded, Object tokenObject) {
		this.consecutiveRNFs = 0;
		this.tokenObject = tokenObject;
		this.token = token;
		this.parent = parent;
		this.dontSendEncoded = dontSendEncoded;
		this.retries = 0;
		this.finished = false;
		this.ctx = ctx;
		errors = new FailureCodeTracker(true);
		this.cb = cb;
		this.uri = uri;
		this.compressionCodec = compressionCodec;
		this.sourceData = data;
		if(sourceData == null) throw new NullPointerException();
		this.isMetadata = isMetadata;
		this.sourceLength = sourceLength;
		this.getCHKOnly = getCHKOnly;
		if(addToParent) {
			parent.addBlock();
			parent.addMustSucceedBlocks(1);
			parent.notifyClients();
		}
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
	}

	protected ClientKeyBlock innerEncode() throws InsertException {
		String uriType = uri.getKeyType().toUpperCase();
		if(uriType.equals("CHK")) {
			try {
				return ClientCHKBlock.encode(sourceData, isMetadata, compressionCodec == -1, compressionCodec, sourceLength);
			} catch (CHKEncodeException e) {
				Logger.error(this, "Caught "+e, e);
				throw new InsertException(InsertException.INTERNAL_ERROR, e, null);
			} catch (IOException e) {
				Logger.error(this, "Caught "+e+" encoding data "+sourceData, e);
				throw new InsertException(InsertException.BUCKET_ERROR, e, null);
			}
		} else if(uriType.equals("SSK") || uriType.equals("KSK")) {
			try {
				InsertableClientSSK ik = InsertableClientSSK.create(uri);
				return ik.encode(sourceData, isMetadata, compressionCodec == -1, compressionCodec, sourceLength, ctx.random);
			} catch (MalformedURLException e) {
				throw new InsertException(InsertException.INVALID_URI, e, null);
			} catch (SSKEncodeException e) {
				Logger.error(this, "Caught "+e, e);
				throw new InsertException(InsertException.INTERNAL_ERROR, e, null);
			} catch (IOException e) {
				Logger.error(this, "Caught "+e, e);
				throw new InsertException(InsertException.BUCKET_ERROR, e, null);
			}
		} else {
			throw new InsertException(InsertException.INVALID_URI, "Unknown keytype "+uriType, null);
		}
	}

	protected ClientKeyBlock encode() throws InsertException {
		ClientKeyBlock block;
		boolean shouldSend;
		synchronized(this) {
			if(refToClientKeyBlock != null) {
				block = (ClientKeyBlock) refToClientKeyBlock.get();
				if(block != null) return block;
			}
			block = innerEncode();
			refToClientKeyBlock = 
				new SoftReference(block);
			shouldSend = (resultingURI == null);
			resultingURI = block.getClientKey().getURI();
		}
		if(shouldSend && !dontSendEncoded)
			cb.onEncode(block.getClientKey(), this);
		return block;
	}
	
	public boolean isInsert() {
		return true;
	}

	public short getPriorityClass() {
		return parent.getPriorityClass();
	}

	public int getRetryCount() {
		return retries;
	}

	public void onFailure(LowLevelPutException e, Object keyNum) {
		if(parent.isCancelled()) {
			fail(new InsertException(InsertException.CANCELLED));
			return;
		}
		
		switch(e.code) {
		case LowLevelPutException.COLLISION:
			fail(new InsertException(InsertException.COLLISION));
			break;
		case LowLevelPutException.INTERNAL_ERROR:
			errors.inc(InsertException.INTERNAL_ERROR);
			break;
		case LowLevelPutException.REJECTED_OVERLOAD:
			errors.inc(InsertException.REJECTED_OVERLOAD);
			break;
		case LowLevelPutException.ROUTE_NOT_FOUND:
			errors.inc(InsertException.ROUTE_NOT_FOUND);
			break;
		case LowLevelPutException.ROUTE_REALLY_NOT_FOUND:
			errors.inc(InsertException.ROUTE_REALLY_NOT_FOUND);
			break;
		default:
			Logger.error(this, "Unknown LowLevelPutException code: "+e.code);
			errors.inc(InsertException.INTERNAL_ERROR);
		}
		if(e.code == LowLevelPutException.ROUTE_NOT_FOUND || e.code == LowLevelPutException.ROUTE_REALLY_NOT_FOUND) {
			consecutiveRNFs++;
			if(logMINOR) Logger.minor(this, "Consecutive RNFs: "+consecutiveRNFs+" / "+ctx.consecutiveRNFsCountAsSuccess);
			if(consecutiveRNFs == ctx.consecutiveRNFsCountAsSuccess) {
				if(logMINOR) Logger.minor(this, "Consecutive RNFs: "+consecutiveRNFs+" - counting as success");
				onSuccess(keyNum);
				return;
			}
		} else
			consecutiveRNFs = 0;
		if(logMINOR) Logger.minor(this, "Failed: "+e);
		retries++;
		if((retries > ctx.maxInsertRetries) && (ctx.maxInsertRetries != -1)) {
			fail(InsertException.construct(errors));
			return;
		}
		getScheduler().register(this);
	}

	private void fail(InsertException e) {
		fail(e, false);
	}
	
	private void fail(InsertException e, boolean forceFatal) {
		synchronized(this) {
			if(finished) return;
			finished = true;
		}
		if(e.isFatal() || forceFatal)
			parent.fatallyFailedBlock();
		else
			parent.failedBlock();
		cb.onFailure(e, this);
	}

	public ClientKeyBlock getBlock() {
		try {
			synchronized (this) {
				if(finished) return null;
			}
			return encode();				
		} catch (InsertException e) {
			cb.onFailure(e, this);
			return null;
		} catch (Throwable t) {
			Logger.error(this, "Caught "+t, t);
			cb.onFailure(new InsertException(InsertException.INTERNAL_ERROR, t, null), this);
			return null;
		}
	}

	public void schedule() throws InsertException {
		synchronized(this) {
			if(finished) return;
		}
		if(getCHKOnly) {
			ClientKeyBlock block = encode();
			cb.onEncode(block.getClientKey(), this);
			parent.completedBlock(false);
			cb.onSuccess(this);
			finished = true;
		} else {
			getScheduler().register(this);
		}
	}

	private ClientRequestScheduler getScheduler() {
		String uriType = uri.getKeyType().toUpperCase();
		if(uriType.equals("CHK"))
			return parent.chkScheduler;
		else if(uriType.equals("SSK") || uriType.equals("KSK"))
			return parent.sskScheduler;
		else throw new IllegalArgumentException();
	}

	public FreenetURI getURI() {
		synchronized(this) {
			if(resultingURI != null)
				return resultingURI;
		}
		getBlock();
		synchronized(this) {
			// FIXME not really necessary? resultingURI is never dropped, only set.
			return resultingURI;
		}
	}

	public synchronized FreenetURI getURINoEncode() {
		return resultingURI;
	}

	public void onSuccess(Object keyNum) {
		if(logMINOR) Logger.minor(this, "Succeeded ("+this+"): "+token);
		if(parent.isCancelled()) {
			fail(new InsertException(InsertException.CANCELLED));
			return;
		}
		synchronized(this) {
			finished = true;
		}
		parent.completedBlock(false);
		cb.onSuccess(this);
	}

	public BaseClientPutter getParent() {
		return parent;
	}

	public void cancel() {
		synchronized(this) {
			if(finished) return;
			finished = true;
		}
		super.unregister();
		cb.onFailure(new InsertException(InsertException.CANCELLED), this);
	}

	public synchronized boolean isCancelled() {
		return finished;
	}

	public boolean send(NodeClientCore core, RequestScheduler sched, Object keyNum) {
		// Ignore keyNum, key, since we're only sending one block.
		try {
			if(logMINOR) Logger.minor(this, "Starting request: "+this);
			ClientKeyBlock b = getBlock();
			if(b != null)
				core.realPut(b, ctx.cacheLocalRequests);
			else {
				synchronized(this) {
					if(finished) {
						Logger.error(this, "Trying to run send "+this+" when already finished", new Exception("error"));
						return false;
					}
				}
				if(parent.isCancelled())
					fail(new InsertException(InsertException.CANCELLED));
				else
					fail(new InsertException(InsertException.BUCKET_ERROR, "Empty block", null));
				return false;
			}
		} catch (LowLevelPutException e) {
			onFailure(e, keyNum);
			if(logMINOR) Logger.minor(this, "Request failed: "+this+" for "+e);
			return true;
		}
		if(logMINOR) Logger.minor(this, "Request succeeded: "+this);
		onSuccess(keyNum);
		return true;
	}

	public Object getClient() {
		return parent.getClient();
	}

	public ClientRequester getClientRequest() {
		return parent;
	}

	public Object getToken() {
		return tokenObject;
	}

	public SimpleFieldSet getProgressFieldset() {
		return null;
	}

	/** Attempt to encode the block, if necessary */
	public void tryEncode() {
		try {
			encode();
		} catch (InsertException e) {
			fail(e);
		} catch (Throwable t) {
			Logger.error(this, "Caught "+t, t);
			// Don't requeue on BackgroundBlockEncoder.
			// Not necessary to do so (we'll ask again when we need it), and it'll probably just break again.
		}
	}

	public boolean canRemove() {
		return true;
	}

	public synchronized Object[] sendableKeys() {
		if(finished)
			return new Object[] {};
		else
			return new Object[] { new Integer(0) };
	}

	public synchronized Object[] allKeys() {
		return sendableKeys();
	}

	public synchronized Object chooseKey() {
		if(finished) return null;
		else return new Integer(0);
	}

}
