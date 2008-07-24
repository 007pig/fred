/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import java.util.ArrayList;

import com.db4o.ObjectContainer;
import com.db4o.ObjectSet;
import com.db4o.query.Candidate;
import com.db4o.query.Evaluation;
import com.db4o.query.Query;

import freenet.keys.Key;
import freenet.node.SendableGet;
import freenet.support.HexUtil;
import freenet.support.Logger;

/**
 * Persistable implementation of CooldownQueue. Much simpler than RequestCooldownQueue,
 * and would use much more memory if it wasn't for the database!
 * 
 * Creator must call setContainer() and setCooldownTime() before use, after pulling it 
 * out of the database.
 * @author toad
 */
public class PersistentCooldownQueue implements CooldownQueue {
	
	private long cooldownTime;

	void setCooldownTime(long time) {
		cooldownTime = time;
	}

	public long add(Key key, SendableGet client, ObjectContainer container) {
		assert(cooldownTime != 0);
		long removeTime = System.currentTimeMillis() + cooldownTime;
		PersistentCooldownQueueItem persistentCooldownQueueItem = new PersistentCooldownQueueItem(client, key, removeTime, this);
		container.set(persistentCooldownQueueItem);
		return removeTime;
	}

	public boolean removeKey(final Key key, final SendableGet client, final long time, ObjectContainer container) {
		boolean found = false;
		final String keyAsBytes = HexUtil.bytesToHex(key.getFullKey());
		Query query = container.query();
		query.constrain(PersistentCooldownQueueItem.class);
		query.descend("keyAsBytes").constrain(keyAsBytes);
		// The result from parent will be huge, and client may be huge too.
		// Don't bother with a join, just check in the evaluation.
//		query.descend("client").constrain(client);
//		query.descend("parent").constrain(this);
		Evaluation eval = new Evaluation() {

			public void evaluate(Candidate candidate) {
				PersistentCooldownQueueItem item = (PersistentCooldownQueueItem) candidate.getObject();
				if(item.client != client) {
					candidate.include(false);
					return;
				}
				if(item.parent != PersistentCooldownQueue.this) {
					candidate.include(false);
					return;
				}
				Key k = item.key;
				candidate.objectContainer().activate(k, 5);
				if(k.equals(key))
					candidate.include(true);
				else {
					candidate.include(false);
					candidate.objectContainer().deactivate(k, 5);
				}
			}
			
		};
		query.constrain(eval);
		ObjectSet results = query.execute();

		while(results.hasNext()) {
			found = true;
			PersistentCooldownQueueItem i = (PersistentCooldownQueueItem) results.next();
			container.delete(i);
		}
		return found;
	}

	public Key[] removeKeyBefore(final long now, ObjectContainer container, int maxCount) {
		// Will be called repeatedly until no more keys are returned, so it doesn't
		// matter very much if they're not in order.
		
		// This query returns bogus results (cooldown items with times in the future).
//		ObjectSet results = container.query(new Predicate() {
//			public boolean match(PersistentCooldownQueueItem persistentCooldownQueueItem) {
//				if(persistentCooldownQueueItem.time >= now) return false;
//				if(persistentCooldownQueueItem.parent != PersistentCooldownQueue.this) return false;
//				return true;
//			}
//		});
		// Lets re-code it in SODA.
		long tStart = System.currentTimeMillis();
		Query query = container.query();
		query.constrain(PersistentCooldownQueueItem.class);
		query.descend("time").constrain(new Long(now)).smaller()
			.and(query.descend("parent").constrain(this).identity());
		ObjectSet results = query.execute();
		if(results.hasNext()) {
			long tEnd = System.currentTimeMillis();
			if(tEnd - tStart > 1000)
				Logger.error(this, "Query took "+(tEnd-tStart));
			else
				if(Logger.shouldLog(Logger.MINOR, this))
					Logger.minor(this, "Query took "+(tEnd-tStart));
			ArrayList v = new ArrayList(Math.min(maxCount, results.size()));
			while(results.hasNext() && v.size() < maxCount) {
				PersistentCooldownQueueItem i = (PersistentCooldownQueueItem) results.next();
				if(i.time >= now) {
					Logger.error(this, "removeKeyBefore(): time >= now: diff="+(now-i.time));
					continue;
				}
				if(i.parent != this) {
					Logger.error(this, "parent="+i.parent+" but should be "+this);
					continue;
				}
				container.delete(i);
				v.add(i.key);
			}
			return (Key[]) v.toArray(new Key[v.size()]);
		} else {
			long tEnd = System.currentTimeMillis();
			if(tEnd - tStart > 1000)
				Logger.error(this, "Query took "+(tEnd-tStart));
			else
				if(Logger.shouldLog(Logger.MINOR, this))
					Logger.minor(this, "Query took "+(tEnd-tStart));
			return null;
		}
	}

}
