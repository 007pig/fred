package freenet.support;

import com.db4o.ObjectContainer;

public class SectoredRandomGrabArrayWithInt extends SectoredRandomGrabArray implements IntNumberedItem {

	private final int number;

	public SectoredRandomGrabArrayWithInt(int number, boolean persistent, ObjectContainer container) {
		super(persistent, container);
		this.number = number;
	}

	public int getNumber() {
		return number;
	}
	
	public String toString() {
		return super.toString() + ":"+number;
	}

}
