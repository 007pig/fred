/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import java.io.IOException;

import com.db4o.ObjectContainer;

import freenet.client.ClientMetadata;
import freenet.client.InsertBlock;
import freenet.client.InsertContext;
import freenet.client.InsertException;
import freenet.client.Metadata;
import freenet.client.events.SplitfileProgressEvent;
import freenet.keys.BaseClientKey;
import freenet.keys.FreenetURI;
import freenet.node.RequestClient;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;
import freenet.support.api.Bucket;

public class ClientPutter extends BaseClientPutter implements PutCompletionCallback {

	final ClientCallback client;
	final Bucket data;
	final FreenetURI targetURI;
	final ClientMetadata cm;
	final InsertContext ctx;
	final String targetFilename;
	private ClientPutState currentState;
	private boolean finished;
	private final boolean getCHKOnly;
	private final boolean isMetadata;
	private boolean startedStarting;
	private final boolean binaryBlob;
	private FreenetURI uri;
	/** SimpleFieldSet containing progress information from last startup.
	 * Will be progressively cleared during startup. */
	private SimpleFieldSet oldProgress;

	/**
	 * @param client The object to call back when we complete, or don't.
	 * @param data
	 * @param targetURI
	 * @param cm
	 * @param ctx
	 * @param scheduler
	 * @param priorityClass
	 * @param getCHKOnly
	 * @param isMetadata
	 * @param clientContext The client object for purposs of round-robin client balancing.
	 * @param stored The progress so far, stored as a SimpleFieldSet. Advisory; if there 
	 * is an error reading this in, we will restart from scratch.
	 * @param targetFilename If set, create a one-file manifest containing this filename pointing to this file.
	 */
	public ClientPutter(ClientCallback client, Bucket data, FreenetURI targetURI, ClientMetadata cm, InsertContext ctx,
			ClientRequestScheduler chkScheduler, ClientRequestScheduler sskScheduler, short priorityClass, boolean getCHKOnly, 
			boolean isMetadata, RequestClient clientContext, SimpleFieldSet stored, String targetFilename, boolean binaryBlob) {
		super(priorityClass, clientContext);
		this.cm = cm;
		this.isMetadata = isMetadata;
		this.getCHKOnly = getCHKOnly;
		this.client = client;
		this.data = data;
		this.targetURI = targetURI;
		this.ctx = ctx;
		this.finished = false;
		this.cancelled = false;
		this.oldProgress = stored;
		this.targetFilename = targetFilename;
		this.binaryBlob = binaryBlob;
	}

	public void start(boolean earlyEncode, ObjectContainer container, ClientContext context) throws InsertException {
		start(earlyEncode, false, container, context);
	}
	
	public boolean start(boolean earlyEncode, boolean restart, ObjectContainer container, ClientContext context) throws InsertException {
		if(persistent())
			container.activate(client, 1);
		if(Logger.shouldLog(Logger.MINOR, this))
			Logger.minor(this, "Starting "+this);
		try {
			this.targetURI.checkInsertURI();
			
			if(data == null)
				throw new InsertException(InsertException.BUCKET_ERROR, "No data to insert", null);
			
			boolean cancel = false;
			synchronized(this) {
				if(restart) {
					if(currentState != null && !finished) return false;
					finished = false;
				}
				if(startedStarting) return false;
				startedStarting = true;
				if(currentState != null) return false;
				cancel = this.cancelled;
				if(!cancel) {
					if(!binaryBlob)
						currentState =
							new SingleFileInserter(this, this, new InsertBlock(data, cm, targetURI), isMetadata, ctx, 
									false, getCHKOnly, false, null, false, false, targetFilename, earlyEncode);
					else
						currentState =
							new BinaryBlobInserter(data, this, null, false, priorityClass, ctx, context, container);
				}
			}
			if(cancel) {
				onFailure(new InsertException(InsertException.CANCELLED), null, container, context);
				oldProgress = null;
				return false;
			}
			synchronized(this) {
				cancel = cancelled;
			}
			if(cancel) {
				onFailure(new InsertException(InsertException.CANCELLED), null, container, context);
				oldProgress = null;
				if(persistent())
					container.set(this);
				return false;
			}
			if(Logger.shouldLog(Logger.MINOR, this))
				Logger.minor(this, "Starting insert: "+currentState);
			if(currentState instanceof SingleFileInserter)
				((SingleFileInserter)currentState).start(oldProgress, container, context);
			else
				currentState.schedule(container, context);
			synchronized(this) {
				oldProgress = null;
				cancel = cancelled;
			}
			if(persistent()) {
				container.set(this);
				// It has scheduled, we can safely deactivate it now, so it won't hang around in memory.
				container.deactivate(currentState, 1);
			}
			if(cancel) {
				onFailure(new InsertException(InsertException.CANCELLED), null, container, context);
				return false;
			}
		} catch (InsertException e) {
			Logger.error(this, "Failed to start insert: "+e, e);
			synchronized(this) {
				finished = true;
				oldProgress = null;
				currentState = null;
			}
			if(persistent())
				container.set(this);
			// notify the client that the insert could not even be started
			if (this.client!=null) {
				this.client.onFailure(e, this, container);
			}
		} catch (IOException e) {
			Logger.error(this, "Failed to start insert: "+e, e);
			synchronized(this) {
				finished = true;
				oldProgress = null;
				currentState = null;
			}
			if(persistent())
				container.set(this);
			// notify the client that the insert could not even be started
			if (this.client!=null) {
				this.client.onFailure(new InsertException(InsertException.BUCKET_ERROR, e, null), this, container);
			}
		} catch (BinaryBlobFormatException e) {
			Logger.error(this, "Failed to start insert: "+e, e);
			synchronized(this) {
				finished = true;
				oldProgress = null;
				currentState = null;
			}
			if(persistent())
				container.set(this);
			// notify the client that the insert could not even be started
			if (this.client!=null) {
				this.client.onFailure(new InsertException(InsertException.BINARY_BLOB_FORMAT_ERROR, e, null), this, container);
			}
		} 
		if(Logger.shouldLog(Logger.MINOR, this))
			Logger.minor(this, "Started "+this);
		return true;
	}

	public void onSuccess(ClientPutState state, ObjectContainer container, ClientContext context) {
		if(persistent())
			container.activate(client, 1);
		synchronized(this) {
			finished = true;
			currentState = null;
			oldProgress = null;
		}
		if(super.failedBlocks > 0 || super.fatallyFailedBlocks > 0 || super.successfulBlocks < super.totalBlocks) {
			Logger.error(this, "Failed blocks: "+failedBlocks+", Fatally failed blocks: "+fatallyFailedBlocks+
					", Successful blocks: "+successfulBlocks+", Total blocks: "+totalBlocks+" but success?! on "+this+" from "+state,
					new Exception("debug"));
		}
		if(persistent())
			container.set(this);
		client.onSuccess(this, container);
	}

	public void onFailure(InsertException e, ClientPutState state, ObjectContainer container, ClientContext context) {
		if(persistent())
			container.activate(client, 1);
		synchronized(this) {
			finished = true;
			currentState = null;
			oldProgress = null;
		}
		if(persistent())
			container.set(this);
		client.onFailure(e, this, container);
	}

	public void onMajorProgress() {
		client.onMajorProgress();
	}
	
	public void onEncode(BaseClientKey key, ClientPutState state, ObjectContainer container, ClientContext context) {
		if(persistent())
			container.activate(client, 1);
		synchronized(this) {
			this.uri = key.getURI();
			if(targetFilename != null)
				uri = uri.pushMetaString(targetFilename);
		}
		if(persistent())
			container.set(this);
		client.onGeneratedURI(uri, this, container);
	}
	
	public void cancel(ObjectContainer container, ClientContext context) {
		if(Logger.shouldLog(Logger.MINOR, this))
			Logger.minor(this, "Cancelling "+this, new Exception("debug"));
		ClientPutState oldState = null;
		synchronized(this) {
			if(cancelled) return;
			super.cancel();
			oldState = currentState;
			if(startedStarting) return;
			startedStarting = true;
		}
		if(persistent()) {
			container.set(this);
			if(oldState != null)
				container.activate(oldState, 1);
		}
		if(oldState != null) oldState.cancel(container, context);
		onFailure(new InsertException(InsertException.CANCELLED), null, container, context);
	}
	
	public synchronized boolean isFinished() {
		return finished || cancelled;
	}

	public FreenetURI getURI() {
		return uri;
	}

	public synchronized void onTransition(ClientPutState oldState, ClientPutState newState, ObjectContainer container) {
		if(newState == null) throw new NullPointerException();
		if(currentState == oldState) {
			currentState = newState;
			if(persistent())
				container.set(this);
		} else
			Logger.error(this, "onTransition: cur="+currentState+", old="+oldState+", new="+newState, new Exception("debug"));
	}

	public void onMetadata(Metadata m, ClientPutState state, ObjectContainer container, ClientContext context) {
		Logger.error(this, "Got metadata on "+this+" from "+state+" (this means the metadata won't be inserted)");
	}
	
	public void notifyClients(ObjectContainer container, ClientContext context) {
		if(persistent())
			container.activate(ctx, 2);
		ctx.eventProducer.produceEvent(new SplitfileProgressEvent(this.totalBlocks, this.successfulBlocks, this.failedBlocks, this.fatallyFailedBlocks, this.minSuccessBlocks, this.blockSetFinalized), container, context);
	}
	
	public void onBlockSetFinished(ClientPutState state, ObjectContainer container, ClientContext context) {
		if(Logger.shouldLog(Logger.MINOR, this))
			Logger.minor(this, "Set finished", new Exception("debug"));
		blockSetFinalized(container, context);
	}

	public SimpleFieldSet getProgressFieldset() {
		if(currentState == null) return null;
		return currentState.getProgressFieldset();
	}

	public void onFetchable(ClientPutState state, ObjectContainer container) {
		if(persistent())
			container.activate(client, 1);
		client.onFetchable(this, container);
	}

	public boolean canRestart() {
		if(currentState != null && !finished) {
			Logger.minor(this, "Cannot restart because not finished for "+uri);
			return false;
		}
		if(data == null) return false;
		return true;
	}

	public boolean restart(boolean earlyEncode, ObjectContainer container, ClientContext context) throws InsertException {
		return start(earlyEncode, true, container, context);
	}

	public void onTransition(ClientGetState oldState, ClientGetState newState, ObjectContainer container) {
		// Ignore, at the moment
	}

}
