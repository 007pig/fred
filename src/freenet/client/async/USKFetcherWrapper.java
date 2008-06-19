/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import com.db4o.ObjectContainer;

import freenet.client.FetchException;
import freenet.client.FetchResult;
import freenet.keys.FreenetURI;
import freenet.keys.USK;
import freenet.node.RequestClient;

/**
 * Wrapper for a backgrounded USKFetcher.
 */
public class USKFetcherWrapper extends BaseClientGetter {

	final USK usk;
	
	public USKFetcherWrapper(USK usk, short prio, RequestClient client) {
		super(prio, client);
		this.usk = usk;
	}

	public FreenetURI getURI() {
		return usk.getURI();
	}

	public boolean isFinished() {
		return false;
	}

	public void notifyClients(ObjectContainer container, ClientContext context) {
		// Do nothing
	}

	public void onSuccess(FetchResult result, ClientGetState state, ObjectContainer container, ClientContext context) {
		// Ignore; we don't do anything with it because we are running in the background.
	}

	public void onFailure(FetchException e, ClientGetState state, ObjectContainer container, ClientContext context) {
		// Ignore
	}

	public void onBlockSetFinished(ClientGetState state, ObjectContainer container, ClientContext context) {
		// Ignore
	}

	public void onTransition(ClientGetState oldState, ClientGetState newState, ObjectContainer container) {
		// Ignore
	}

	public String toString() {
		return super.toString()+ ':' +usk;
	}

	public void onExpectedMIME(String mime, ObjectContainer container) {
		// Ignore
	}

	public void onExpectedSize(long size, ObjectContainer container) {
		// Ignore
	}

	public void onFinalizedMetadata(ObjectContainer container) {
		// Ignore
	}
}
