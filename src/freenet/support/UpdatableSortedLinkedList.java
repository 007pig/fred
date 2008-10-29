package freenet.support;

import java.util.Enumeration;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * @author amphibian
 * 
 * Sorted LinkedList. Keeps track of the maximum and minimum,
 * and provides an update() function to move an item when its
 * value has changed. Allows duplicates.
 */
public class UpdatableSortedLinkedList implements Iterable {
	boolean debug = false;
	protected boolean killed = false;
	private static boolean logMINOR;
	
    public UpdatableSortedLinkedList() {
        list = new DoublyLinkedListImpl();
        logMINOR = Logger.shouldLog(Logger.MINOR, this);
    }
    
    private final DoublyLinkedList list;
    
    public synchronized void add(UpdatableSortedLinkedListItem i) throws UpdatableSortedLinkedListKilledException {
        logMINOR = Logger.shouldLog(Logger.MINOR, this);
    	if(killed) throw new UpdatableSortedLinkedListKilledException();
        if(logMINOR) Logger.minor(this, "Add("+i+") on "+this);
        if(list.isEmpty()) {
            list.push(i);
            checkList();
            return;
        }
        if(i.compareTo(list.tail()) >= 0) {
            list.push(i);
            checkList();
            return;
        }
        if(i.compareTo(list.head()) <= 0) {
            list.unshift(i);
            checkList();
            return;
        }
        // Search the list for a good place to put it
        Enumeration e = list.elements();
        UpdatableSortedLinkedListItem prev = null;
        while(e.hasMoreElements()) {
            UpdatableSortedLinkedListItem cur = 
                (UpdatableSortedLinkedListItem) e.nextElement();
            if((prev != null) && (cur.compareTo(i) >= 0) && (prev.compareTo(i) <= 0)) {
                list.insertNext(prev, i);
                checkList();
                return;
            }
            if(logMINOR) Logger.minor(this, "Not matching "+cur+ ' ' +prev);
            prev = cur;
        }
        throw new IllegalStateException("impossible");
    }

    private int ctr;
    
    protected synchronized void checkList() {
    	// If get errors, make this happen all the time.
    	ctr++;
    	if(ctr % 256 != 0 && !debug) return;
    	int statedLength = list.size();
    	int realLength = 0;
    	for(Enumeration e = list.elements();e.hasMoreElements();) {
    		UpdatableSortedLinkedListItem i = (UpdatableSortedLinkedListItem) e.nextElement();
    		// Sanity check for infinite looping 
    		if(realLength > 100*1000)
    			Logger.normal(this, "["+realLength+"] = "+i+" (prev="+i.getPrev()+ ')');
    		realLength++;
    	}
    	if(statedLength != realLength) {
    		String err = "statedLength = "+statedLength+" but realLength = "+realLength+" on "+this;
    		Logger.error(this, "Illegal ERROR: "+err, new Exception("error"));
    		throw new IllegalStateException(err);
    	} else {
    		//Logger.minor(this, "checkList() successful: realLength = statedLength = "+realLength+" on "+this);
    	}
	}

	public synchronized UpdatableSortedLinkedListItem remove(UpdatableSortedLinkedListItem i) throws UpdatableSortedLinkedListKilledException {
        logMINOR = Logger.shouldLog(Logger.MINOR, this);
    	if(killed) throw new UpdatableSortedLinkedListKilledException();
    	if(logMINOR) Logger.minor(this, "Remove("+i+") on "+this);
        checkList();
        UpdatableSortedLinkedListItem item = 
        	(UpdatableSortedLinkedListItem) list.remove(i);
        if(logMINOR) Logger.minor(this, "Returning "+item);
        checkList();
        return item;
    }
    
	public synchronized void addOrUpdate(UpdatableSortedLinkedListItem item) throws UpdatableSortedLinkedListKilledException {
		if(item.getParent() == list)
			update(item);
		else if(item.getParent() == null)
			add(item);
		else throw new IllegalStateException("Item "+item+" should be on our list: "+list+" or null, but is "+item.getParent());
	}
	
    public synchronized void update(UpdatableSortedLinkedListItem i) throws UpdatableSortedLinkedListKilledException {
    	logMINOR = Logger.shouldLog(Logger.MINOR, this);
    	if(killed) throw new UpdatableSortedLinkedListKilledException();
    	if(logMINOR) Logger.minor(this, "Update("+i+") on "+this);
        checkList();
        if(i.compareTo(list.tail()) > 0) {
            list.remove(i);
            list.push(i);
            checkList();
            return;
        }
        if(i.compareTo(list.head()) < 0) {
            list.remove(i);
            list.unshift(i);
            checkList();
            return;
        }
        if((list.head() == list.tail()) && (i != list.head())) {
            Logger.error(this, "Only 1 element: "+list.head()+" and updating "+i+" on "+this, new Exception("error"));
            add(i);
            checkList();
            return;
        }
        // Forwards or backwards?
        UpdatableSortedLinkedListItem next = (UpdatableSortedLinkedListItem) list.next(i);
        UpdatableSortedLinkedListItem prev = (UpdatableSortedLinkedListItem) list.prev(i);
        if((next == null) && (prev == null)) {
            return;
        }
        if((next != null) && (prev != null) && (next.compareTo(i) >= 0) && (prev.compareTo(i) <= 0)) 
            return; // already exactly where it should be
        if((next == null) && (prev != null)) {
            if(prev.compareTo(i) <= 0) return; // already in right place, at end
        }
        if((next != null) && (prev == null)) {
            if(next.compareTo(i) >= 0) return; // already where it should be
        }
        if((next != null) && (i.compareTo(next) > 0)) {
            // i > next - move forwards
            while(true) {
                prev = next;
                next = (UpdatableSortedLinkedListItem) list.next(next);
                if(next == null) {
                    throw new NullPointerException("impossible - we checked");
                }
                if((i.compareTo(next) < 0) && (i.compareTo(prev) > 0)) {
                    list.remove(i);
                    list.insertNext(prev, i);
                    checkList();
                    return;
                }
            }
        } else if((prev != null) && (i.compareTo(prev) < 0)) {
            // i < next - move backwards
            while(true) {
                next = prev;
                prev = (UpdatableSortedLinkedListItem) list.prev(prev);
                if(next == null) {
                    throw new NullPointerException("impossible - we checked");
                }
                if((i.compareTo(next) < 0) && (i.compareTo(prev) > 0)) {
                    list.remove(i);
                    list.insertNext(prev, i);
                    checkList();
                    return;
                }
            }
        }
        Logger.error(this, "Could not update "+i, new Exception("error"));
        if(logMINOR) dump();
        remove(i);
        add(i);
        checkList();
    }

    /**
     * Dump the current status of the list to the log.
     * @throws UpdatableSortedLinkedListKilledException 
     */
    private synchronized void dump() throws UpdatableSortedLinkedListKilledException {
    	if(killed) throw new UpdatableSortedLinkedListKilledException();
        for(Enumeration e=list.elements();e.hasMoreElements();) {
            UpdatableSortedLinkedListItem item = (UpdatableSortedLinkedListItem) e.nextElement();
            if(logMINOR) Logger.minor(this, item.toString());
        }
    }

    /**
     * @return The number of items in the list.
     */
    public synchronized int size() {
        return list.size();
    }

    /**
     * @return an array, in order, of the elements in the list
     * @throws UpdatableSortedLinkedListKilledException 
     */
    public synchronized UpdatableSortedLinkedListItem[] toArray() throws UpdatableSortedLinkedListKilledException {
    	if(killed) throw new UpdatableSortedLinkedListKilledException();
        int size = list.size();
        if(size < 0)
        	throw new IllegalStateException("list.size() = "+size+" for "+this);
        	
        UpdatableSortedLinkedListItem[] output = 
            new UpdatableSortedLinkedListItem[size];
        int i=0;
        for(Enumeration e = list.elements();e.hasMoreElements();) {
            output[i++] = (UpdatableSortedLinkedListItem)e.nextElement();
            //Logger.minor(this, "["+(i-1)+"] = "+output[i-1]);
        }
        return output;
    }

    /**
     * @return Is the list empty?
     */
    public synchronized boolean isEmpty() {
        return list.isEmpty();
    }

    /**
     * @return The item on the list with the lowest value.
     */
    public synchronized UpdatableSortedLinkedListItem getLowest() {
        return (UpdatableSortedLinkedListItem) list.head();
    }
    
    public synchronized void clear() {
        list.clear();
    }
    
    public synchronized void kill() {
    	clear();
    	killed = true;
    }

	public synchronized UpdatableSortedLinkedListItem removeLowest() throws UpdatableSortedLinkedListKilledException {
		if(isEmpty()) return null;
		UpdatableSortedLinkedListItem i = getLowest();
		remove(i);
		return i;
	}

	public synchronized void moveTo(UpdatableSortedLinkedList dest) throws UpdatableSortedLinkedListKilledException {
		Enumeration e = list.elements();
		while(e.hasMoreElements()) {
			UpdatableSortedLinkedListItem item = (UpdatableSortedLinkedListItem) e.nextElement();
			remove(item);
			dest.add(item);
		}
	}

	public synchronized Iterator iterator() {
		return list.iterator();
	}
}
