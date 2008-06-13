/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import com.db4o.ObjectContainer;

import freenet.client.async.ClientContext;

/**
 * Callback interface for a low level insert, which is immediately sendable. These
 * should be registered on the ClientRequestScheduler when we want to send them. It will
 * then, when it is time to send, create a thread, send the request, and call the 
 * callback below.
 */
public abstract class SendableInsert extends SendableRequest {

	/** Called when we successfully insert the data */
	public abstract void onSuccess(Object keyNum, ObjectContainer container, ClientContext context);
	
	/** Called when we don't! */
	public abstract void onFailure(LowLevelPutException e, Object keyNum, ObjectContainer container, ClientContext context);

	public void internalError(Object keyNum, Throwable t, RequestScheduler sched, ObjectContainer container, ClientContext context) {
		onFailure(new LowLevelPutException(LowLevelPutException.INTERNAL_ERROR, t.getMessage(), t), keyNum, container, context);
	}

}
