/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import freenet.client.FetchException;
import freenet.client.FetchResult;
import freenet.keys.FreenetURI;
import freenet.keys.USK;

/**
 * Wrapper for a backgrounded USKFetcher.
 */
public class USKFetcherWrapper extends BaseClientGetter {

	final USK usk;
	
	public USKFetcherWrapper(USK usk, short prio, ClientRequestScheduler chkScheduler, ClientRequestScheduler sskScheduler, Object client) {
		super(prio, chkScheduler, sskScheduler, client);
		this.usk = usk;
	}

	@Override
	public FreenetURI getURI() {
		return usk.getURI();
	}

	@Override
	public boolean isFinished() {
		return false;
	}

	@Override
	public void notifyClients() {
		// Do nothing
	}

	public void onSuccess(FetchResult result, ClientGetState state) {
		// Ignore; we don't do anything with it because we are running in the background.
	}

	public void onFailure(FetchException e, ClientGetState state) {
		// Ignore
	}

	public void onBlockSetFinished(ClientGetState state) {
		// Ignore
	}

	@Override
	public void onTransition(ClientGetState oldState, ClientGetState newState) {
		// Ignore
	}

	@Override
	public String toString() {
		return super.toString()+ ':' +usk;
	}

	public void onExpectedMIME(String mime) {
		// Ignore
	}

	public void onExpectedSize(long size) {
		// Ignore
	}

	public void onFinalizedMetadata() {
		// Ignore
	}
}
