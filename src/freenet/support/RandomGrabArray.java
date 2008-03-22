package freenet.support;

import java.util.HashSet;

import freenet.crypt.RandomSource;

/**
 * An array which supports very fast remove-and-return-a-random-element.
 */
public class RandomGrabArray {

	/** Array of items. Non-null's followed by null's. */
	private RandomGrabArrayItem[] reqs;
	/** Index of first null item. */
	private int index;
	/** Random source */
	private RandomSource rand;
	/** What do we already have? FIXME: Replace with a Bloom filter or something (to save 
	 * RAM), or rewrite the whole class as a custom hashset maybe based on the classpath 
	 * HashSet. Note that removeRandom() is *the* common operation, so MUST BE FAST.
	 */
	private HashSet contents;
	private final static int MIN_SIZE = 32;

	public RandomGrabArray(RandomSource rand) {
		this.reqs = new RandomGrabArrayItem[MIN_SIZE];
		index = 0;
		this.rand = rand;
		contents = new HashSet();
	}
	
	public void add(RandomGrabArrayItem req) {
		boolean logMINOR = Logger.shouldLog(Logger.MINOR, this);
		if(req.isCancelled()) {
			if(logMINOR) Logger.minor(this, "Is finished already: "+req);
			return;
		}
		req.setParentGrabArray(this);
		synchronized(this) {
			if(contents.contains(req)) {
				if(logMINOR) Logger.minor(this, "Already contains "+req+" : "+this+" size now "+index);
				return;
			}
			contents.add(req);
			if(index >= reqs.length) {
				RandomGrabArrayItem[] r = new RandomGrabArrayItem[reqs.length*2];
				System.arraycopy(reqs, 0, r, 0, reqs.length);
				reqs = r;
			}
			reqs[index++] = req;
			if(logMINOR) Logger.minor(this, "Added: "+req+" to "+this+" size now "+index);
		}
	}
	
	public RandomGrabArrayItem removeRandom(RandomGrabArrayItemExclusionList excluding) {
		RandomGrabArrayItem ret, oret;
		boolean logMINOR = Logger.shouldLog(Logger.MINOR, this);
		synchronized(this) {
			final int MAX_EXCLUDED = 10;
			int excluded = 0;
			while(true) {
				if(index == 0) {
					if(logMINOR) Logger.minor(this, "All null on "+this);
					return null;
				}
				int i = rand.nextInt(index);
				ret = reqs[i];
				oret = ret;
				if(ret.isCancelled()) {
					if(logMINOR) Logger.minor(this, "Not returning because cancelled: "+ret);
					ret = null;
				}
				if(ret != null && excluding.exclude(ret)) {
					excluded++;
					if(excluded > MAX_EXCLUDED) {
						Logger.error(this, "Remove random returning null because "+excluded+" excluded items", new Exception("error"));
						return null;
					}
					continue;
				}
				if(ret != null && !ret.canRemove()) {
					if(logMINOR) Logger.minor(this, "Returning (cannot remove): "+ret);
					return ret;
				}
				do {
					reqs[i] = reqs[--index];
					reqs[index] = null;
					if(oret != null)
						contents.remove(oret);
					oret = reqs[i];
					// May as well check whether that is cancelled too.
				} while (index > i && (oret == null || oret.isCancelled()));
				// Shrink array
				if((index < reqs.length / 4) && (reqs.length > MIN_SIZE)) {
					// Shrink array
					int newSize = Math.max(index * 2, MIN_SIZE);
					RandomGrabArrayItem[] r = new RandomGrabArrayItem[newSize];
					System.arraycopy(reqs, 0, r, 0, r.length);
					reqs = r;
				}
				if((ret != null) && !ret.isCancelled()) break;
			}
		}
		if(logMINOR) Logger.minor(this, "Returning "+ret);
		ret.setParentGrabArray(null);
		return ret;
	}
	
	public void remove(RandomGrabArrayItem it) {
		synchronized(this) {
			if(!contents.contains(it)) return;
			contents.remove(it);
			for(int i=0;i<index;i++) {
				if((reqs[i] == it) || reqs[i].equals(it)) {
					reqs[i] = reqs[--index];
					reqs[index] = null;
					break;
				}
			}
		}
		it.setParentGrabArray(null);
	}

	public synchronized boolean isEmpty() {
		return index == 0;
	}
}
