package freenet.support;

import java.util.HashMap;

/**
 * UpdatableSortedLinkedList plus a hashtable. Each item has
 * an indexItem(), which we use to track them. This is completely
 * independant of their sort order, hence "foreign".
 * Note that this class, unlike its parent, does not permit 
 * duplicates.
 */
public class UpdatableSortedLinkedListWithForeignIndex extends UpdatableSortedLinkedList {

    final HashMap map;

    public UpdatableSortedLinkedListWithForeignIndex() {
        super();
        map = new HashMap();
    }
    
    @Override
	public synchronized void add(UpdatableSortedLinkedListItem item) throws UpdatableSortedLinkedListKilledException {
        if(!(item instanceof IndexableUpdatableSortedLinkedListItem)) {
            throw new IllegalArgumentException();
        }
    	if(killed) throw new UpdatableSortedLinkedListKilledException();
        IndexableUpdatableSortedLinkedListItem i = (IndexableUpdatableSortedLinkedListItem)item;
        if(map.get(i.indexValue()) != null) {
            // Ignore duplicate
            Logger.error(this, "Ignoring duplicate: "+i+" was already present: "+map.get(i.indexValue()));
            return;
        }
        super.add(i);
        map.put(i.indexValue(), item);
        checkList();
    }
    
    @Override
	public synchronized UpdatableSortedLinkedListItem remove(UpdatableSortedLinkedListItem item) throws UpdatableSortedLinkedListKilledException {
    	if(killed) throw new UpdatableSortedLinkedListKilledException();
        map.remove(((IndexableUpdatableSortedLinkedListItem)item).indexValue());
        return super.remove(item);
    }
    
	public synchronized IndexableUpdatableSortedLinkedListItem get(Object key) {
		return (IndexableUpdatableSortedLinkedListItem)map.get(key);
	}

    public synchronized boolean containsKey(Object key) {
        return map.containsKey(key);
    }
    
    public synchronized boolean contains(IndexableUpdatableSortedLinkedListItem item) {
    	return containsKey(item.indexValue());
    }

    /**
     * Remove an element from the list by its key.
     * @throws UpdatableSortedLinkedListKilledException 
     */
    public synchronized IndexableUpdatableSortedLinkedListItem removeByKey(Object key) throws UpdatableSortedLinkedListKilledException {
    	if(killed) throw new UpdatableSortedLinkedListKilledException();
        IndexableUpdatableSortedLinkedListItem item = 
            (IndexableUpdatableSortedLinkedListItem) map.get(key);
        if(item != null) remove(item);
        checkList();
        return item;
    }
    
    @Override
	public synchronized void clear() {
    	map.clear();
    	super.clear();
    }
}
