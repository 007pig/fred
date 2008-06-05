/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import com.db4o.ObjectContainer;

import freenet.client.InsertException;
import freenet.support.SimpleFieldSet;

/**
 * ClientPutState
 * 
 * Represents a state in the insert process.
 */
public interface ClientPutState {

	/** Get the BaseClientPutter responsible for this request state. */
	public abstract BaseClientPutter getParent();

	/** Cancel the request. */
	public abstract void cancel(ObjectContainer container);

	/** Schedule the request. */
	public abstract void schedule(ObjectContainer container, ClientContext context) throws InsertException;
	
	/**
	 * Get the token, an object which is passed around with the insert and may be
	 * used by callers.
	 */
	public Object getToken();

	/** Serialize current progress to a SimpleFieldSet.
	 * Does not have to be complete! */
	public abstract SimpleFieldSet getProgressFieldset();
}
