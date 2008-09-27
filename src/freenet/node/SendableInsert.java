/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import com.db4o.ObjectContainer;

import freenet.client.async.ClientContext;
import freenet.client.async.ClientRequestScheduler;
import freenet.support.io.NativeThread;

/**
 * Callback interface for a low level insert, which is immediately sendable. These
 * should be registered on the ClientRequestScheduler when we want to send them. It will
 * then, when it is time to send, create a thread, send the request, and call the 
 * callback below.
 */
public abstract class SendableInsert extends SendableRequest {

	public SendableInsert(boolean persistent) {
		super(persistent);
	}
	
	/** Called when we successfully insert the data */
	public abstract void onSuccess(Object keyNum, ObjectContainer container, ClientContext context);
	
	/** Called when we don't! */
	public abstract void onFailure(LowLevelPutException e, Object keyNum, ObjectContainer container, ClientContext context);

	@Override
	public void internalError(Throwable t, RequestScheduler sched, ObjectContainer container, ClientContext context, boolean persistent) {
		sched.callFailure(this, new LowLevelPutException(LowLevelPutException.INTERNAL_ERROR, t.getMessage(), t), NativeThread.MAX_PRIORITY, persistent);
	}

	public final boolean isInsert() {
		return true;
	}
	
	public ClientRequestScheduler getScheduler(ClientContext context) {
		if(isSSK())
			return context.getSskInsertScheduler();
		else
			return context.getChkInsertScheduler();
	}
	
}
