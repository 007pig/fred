package freenet.client.async;

import java.util.Vector;

import com.db4o.ObjectContainer;

import freenet.client.InsertException;
import freenet.client.Metadata;
import freenet.keys.BaseClientKey;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;

public class MultiPutCompletionCallback implements PutCompletionCallback, ClientPutState {

	// LinkedList's rather than HashSet's for memory reasons.
	// This class will not be used with large sets, so O(n) is cheaper than O(1) -
	// at least it is on memory!
	private final Vector waitingFor;
	private final Vector waitingForBlockSet;
	private final Vector waitingForFetchable;
	private final PutCompletionCallback cb;
	private ClientPutState generator;
	private final BaseClientPutter parent;
	private InsertException e;
	private boolean finished;
	private boolean started;
	public final Object token;
	
	public MultiPutCompletionCallback(PutCompletionCallback cb, BaseClientPutter parent, Object token) {
		this.cb = cb;
		waitingFor = new Vector();
		waitingForBlockSet = new Vector();
		waitingForFetchable = new Vector();
		this.parent = parent;
		this.token = token;
		finished = false;
	}

	public void onSuccess(ClientPutState state, ObjectContainer container) {
		onBlockSetFinished(state, container);
		onFetchable(state, container);
		synchronized(this) {
			if(finished) return;
			waitingFor.remove(state);
			if(!(waitingFor.isEmpty() && started))
				return;
		}
		complete(null, container);
	}

	public void onFailure(InsertException e, ClientPutState state, ObjectContainer container) {
		synchronized(this) {
			if(finished) return;
			waitingFor.remove(state);
			waitingForBlockSet.remove(state);
			waitingForFetchable.remove(state);
			if(!(waitingFor.isEmpty() && started)) {
				this.e = e;
				return;
			}
		}
		complete(e, container);
	}

	private void complete(InsertException e, ObjectContainer container) {
		synchronized(this) {
			if(finished) return;
			finished = true;
			if(e != null && this.e != null && this.e != e) {
				if(!(e.getMode() == InsertException.CANCELLED)) // Cancelled is okay, ignore it, we cancel after failure sometimes.
					Logger.error(this, "Completing with "+e+" but already set "+this.e);
			}
			if(e == null) e = this.e;
		}
		if(e != null)
			cb.onFailure(e, this, container);
		else
			cb.onSuccess(this, container);
	}

	public synchronized void addURIGenerator(ClientPutState ps) {
		add(ps);
		generator = ps;
	}
	
	public synchronized void add(ClientPutState ps) {
		if(finished) return;
		waitingFor.add(ps);
		waitingForBlockSet.add(ps);
		waitingForFetchable.add(ps);
	}

	public void arm(ObjectContainer container) {
		boolean allDone;
		boolean allGotBlocks;
		synchronized(this) {
			started = true;
			allDone = waitingFor.isEmpty();
			allGotBlocks = waitingForBlockSet.isEmpty();
		}

		if(allGotBlocks) {
			cb.onBlockSetFinished(this, container);
		}
		if(allDone) {
			complete(e, container);
		}
	}

	public BaseClientPutter getParent() {
		return parent;
	}

	public void onEncode(BaseClientKey key, ClientPutState state, ObjectContainer container) {
		synchronized(this) {
			if(state != generator) return;
		}
		cb.onEncode(key, this, container);
	}

	public void cancel(ObjectContainer container) {
		ClientPutState[] states = new ClientPutState[waitingFor.size()];
		synchronized(this) {
			states = (ClientPutState[]) waitingFor.toArray(states);
		}
		for(int i=0;i<states.length;i++)
			states[i].cancel(container);
	}

	public synchronized void onTransition(ClientPutState oldState, ClientPutState newState, ObjectContainer container) {
		if(generator == oldState)
			generator = newState;
		if(oldState == newState) return;
		for(int i=0;i<waitingFor.size();i++) {
			if(waitingFor.get(i) == oldState) waitingFor.set(i, newState);
		}
		for(int i=0;i<waitingFor.size();i++) {
			if(waitingForBlockSet.get(i) == oldState) waitingForBlockSet.set(i, newState);
		}
		for(int i=0;i<waitingFor.size();i++) {
			if(waitingForFetchable.get(i) == oldState) waitingForFetchable.set(i, newState);
		}
	}

	public synchronized void onMetadata(Metadata m, ClientPutState state, ObjectContainer container) {
		if(generator == state) {
			cb.onMetadata(m, this, container);
		} else {
			Logger.error(this, "Got metadata for "+state);
		}
	}

	public void onBlockSetFinished(ClientPutState state, ObjectContainer container) {
		synchronized(this) {
			this.waitingForBlockSet.remove(state);
			if(!started) return;
			if(!waitingForBlockSet.isEmpty()) return;
		}
		cb.onBlockSetFinished(this, container);
	}

	public void schedule(ObjectContainer container, ClientContext context) throws InsertException {
		// Do nothing
	}

	public Object getToken() {
		return token;
	}

	public SimpleFieldSet getProgressFieldset() {
		return null;
	}

	public void onFetchable(ClientPutState state, ObjectContainer container) {
		synchronized(this) {
			this.waitingForFetchable.remove(state);
			if(!started) return;
			if(!waitingForFetchable.isEmpty()) return;
		}
		cb.onFetchable(this, container);
	}

}
