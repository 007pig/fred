/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import java.util.LinkedList;

import freenet.keys.ClientKey;

public interface RequestScheduler {

	/** Tell the scheduler that a request from a specific RandomGrabArray succeeded.
	 * Definition of "succeeded" will vary, but the point is most schedulers will run another
	 * request from the parentGrabArray in the near future on the theory that if one works,
	 * another may also work. 
	 * */
	public void succeeded(BaseSendableGet get);

	/**
	 * After a key has been requested a few times, it is added to the cooldown queue for
	 * a fixed period, since it would be pointless to rerequest it (especially given ULPRs).
	 * Note that while a key is on the cooldown queue its requestors will still be told if
	 * it is found by ULPRs or back door coalescing.
	 * @param key The key to be added.
	 * @return The time at which the key will leave the cooldown queue.
	 */
	long queueCooldown(ClientKey key, SendableGet getter);

	/**
	 * Remove keys from the cooldown queue who have now served their time and can be requested 
	 * again.
	 */
	public void moveKeysFromCooldownQueue();
	
	/** Once a key has been requested a few times, don't request it again for 30 minutes. 
	 * To do so would be pointless given ULPRs, and just waste bandwidth. */
	public static final long COOLDOWN_PERIOD = 30*60*1000;
	/** The number of times a key can be requested before triggering the cooldown period. 
	 * Note: If you don't want your requests to be subject to cooldown (e.g. in fproxy), make 
	 * your max retry count less than this (and more than -1). */
	public static final int COOLDOWN_RETRIES = 3;
	public long countTransientQueuedRequests();

	public void queueFillRequestStarterQueue();

	public LinkedList getRequestStarterQueue();

	public SendableRequest getBetterNonPersistentRequest(SendableRequest req);
	
}
