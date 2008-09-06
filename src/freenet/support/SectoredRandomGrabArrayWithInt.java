package freenet.support;

import freenet.crypt.RandomSource;

public class SectoredRandomGrabArrayWithInt extends SectoredRandomGrabArray implements IntNumberedItem {

	private final int number;

	public SectoredRandomGrabArrayWithInt(RandomSource rand, int number) {
		super(rand);
		this.number = number;
	}

	public int getNumber() {
		return number;
	}
	
	@Override
	public String toString() {
		return super.toString() + ":"+number;
	}

}
