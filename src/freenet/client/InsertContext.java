/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client;

import java.io.Serializable;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import freenet.client.events.ClientEventProducer;
import freenet.client.events.SimpleEventProducer;
import freenet.support.compress.Compressor;

/** Context object for an insert operation, including both simple and multi-file inserts.
 * 
 * WARNING: Changing non-transient members on classes that are Serializable can result in 
 * losing uploads. 
 */
public class InsertContext implements Cloneable, Serializable {

    private static final long serialVersionUID = 1L;
    /** If true, don't try to compress the data */
	public boolean dontCompress;
	/** Splitfile algorithm. */
	public final short splitfileAlgorithm;
	/** Maximum number of retries (after the initial attempt) for each block
	 * inserted. -1 = retry forever or until it succeeds (subject to 
	 * consecutiveRNFsCountAsSuccess) or until a fatal error. */
	public int maxInsertRetries;
	/** On a very small network, any insert will RNF. Therefore we allow 
	 * some number of RNFs to equal success. */
	public int consecutiveRNFsCountAsSuccess;
	/** Maximum number of data blocks per segment for splitfiles */
	public final int splitfileSegmentDataBlocks;
	/** Maximum number of check blocks per segment for splitfiles. Will be reduced proportionally if there
	 * are fewer data blocks. */
	public final int splitfileSegmentCheckBlocks;
	/** Client events will be published to this, you can subscribe to them */
	public final ClientEventProducer eventProducer;
	/** Can this insert write to the client-cache? We don't store all requests in the client cache,
	 * in particular big stuff usually isn't written to it, to maximise its effectiveness. Plus, 
	 * local inserts are not written to the client-cache by default for privacy reasons. */
	public boolean canWriteClientCache;
	/** a string that contains the codecs to use/try
	 * if the string is null it defaults to COMPRESSOR_TYPES.Values(),
	 * so old persistent inserts are not affected after update.
	 * @see Compressor.COMPRESSOR_TYPES#getCompressorsArray(String compressordescriptor)
	 */
	public String compressorDescriptor;
	public boolean forkOnCacheable;
	/** Number of extra inserts for a single block inserted on its own. */
	public int extraInsertsSingleBlock;
	/** Number of extra inserts for a block inserted above a splitfile. */
	public int extraInsertsSplitfileHeaderBlock;
	public boolean localRequestOnly;
	/** Don't insert USK DATEHINTs (and ignore them on polling for maximum edition). */
	public boolean ignoreUSKDatehints;
	// FIXME DB4O: This should really be an enum. However, db4o has a tendency to copy enum's,
	// which wastes space (often unrecoverably), confuses programmers, creates wierd bugs and breaks == comparison.
	
	public static enum CompatibilityMode {
	    
		/** We do not know. */
		COMPAT_UNKNOWN((short)0),
		/** No compatibility issues, use the most efficient metadata possible. Used only in the 
		 * front-end: MUST NOT be stored: Code should convert this to a specific mode as early as
		 * possible, or inserts will break when a new mode is added. InsertContext does this. */
		COMPAT_CURRENT((short)1),
		// The below *are* used in Metadata compatibility mode detection. And they are comparable by ordinal().
		// This means we have to check for COMPAT_CURRENT as a special case.
		/** Exactly as before 1250: Segments of exactly 128 data, 128 check, check = data */
		COMPAT_1250_EXACT((short)2),
		/** 1250 or previous: Segments up to 128 data 128 check, check <= data. */
		COMPAT_1250((short)3),
		/** 1251/2/3: Basic even splitting, 1 extra check block if data blocks < 128, max 131 data blocks. */
		COMPAT_1251((short)4),
		/** 1255: Second stage of even splitting, a whole bunch of segments lose one block rather than the last segment losing lots of blocks. And hashes too! */
		COMPAT_1255((short)5),
		/** 1416: New CHK encryption */
		COMPAT_1416((short)6);
		
		/** Code used in metadata for this CompatibilityMode. Hence we can remove old 
		 * CompatibilityMode's, and it's also convenient. */
		public final short code;
		
		CompatibilityMode(short code) {
		    this.code = code;
		}
		
		/** cached values(). Never modify or pass this array to outside code! */
		private static final CompatibilityMode[] values = values();

		// Inserts should be converted to a specific compatibility mode as soon as possible, to avoid
		// problems when an insert is restarted on a newer build with a newer default compat mode.
		public static CompatibilityMode latest() {
			return values[values.length-1];
		}
		
		/** Must be called whenever we accept a CompatibilityMode as e.g. a config option. Converts
		 * the pseudo- */
		public CompatibilityMode intern() {
		    if(this == COMPAT_CURRENT) return latest();
		    return this;
		}
		
        private static final Map<Short, CompatibilityMode> modesByCode;
        
        static {
            HashMap<Short, CompatibilityMode> cmodes = new HashMap<Short, CompatibilityMode>();
            for(CompatibilityMode mode : CompatibilityMode.values) {
                if(cmodes.containsKey(mode.code)) throw new Error("Duplicated code!");
                cmodes.put(mode.code, mode);
            }
            modesByCode = Collections.unmodifiableMap(cmodes);
        }

	    public static CompatibilityMode byCode(short code) {
	        if(!modesByCode.containsKey(code)) throw new IllegalArgumentException();
	        return modesByCode.get(code);
	    }
	    
        public static boolean hasCode(short min) {
            return modesByCode.containsKey(min);
        }
        
	}
	
	/** Backward compatibility support for network level metadata. */
	private CompatibilityMode compatibilityMode;
	/** If true, don't insert, just generate the CHK */
    public boolean getCHKOnly;
    /** If true, try to find the final URI as quickly as possible, and insert the upper layers as 
     * soon as we can, rather than waiting for the lower layers. The default behaviour is safer,
     * because an attacker can usually only identify the datastream once he has the top block, or 
     * once you have announced the key. */
    public boolean earlyEncode;
	
	public CompatibilityMode getCompatibilityMode() {
	    return compatibilityMode;
	}
	
	public long getCompatibilityCode() {
		return compatibilityMode.ordinal();
	}

	public void setCompatibilityMode(CompatibilityMode mode) {
		this.compatibilityMode = mode.intern();
	}

	public InsertContext(
			int maxRetries, int rnfsToSuccess, int splitfileSegmentDataBlocks, int splitfileSegmentCheckBlocks,
			ClientEventProducer eventProducer, boolean canWriteClientCache, boolean forkOnCacheable, boolean localRequestOnly, String compressorDescriptor, int extraInsertsSingleBlock, int extraInsertsSplitfileHeaderBlock, CompatibilityMode compatibilityMode) {
		dontCompress = false;
		splitfileAlgorithm = Metadata.SPLITFILE_ONION_STANDARD;
		this.consecutiveRNFsCountAsSuccess = rnfsToSuccess;
		this.maxInsertRetries = maxRetries;
		this.eventProducer = eventProducer;
		this.splitfileSegmentDataBlocks = splitfileSegmentDataBlocks;
		this.splitfileSegmentCheckBlocks = splitfileSegmentCheckBlocks;
		this.canWriteClientCache = canWriteClientCache;
		this.forkOnCacheable = forkOnCacheable;
		this.compressorDescriptor = compressorDescriptor;
		this.extraInsertsSingleBlock = extraInsertsSingleBlock;
		this.extraInsertsSplitfileHeaderBlock = extraInsertsSplitfileHeaderBlock;
		this.compatibilityMode = compatibilityMode.intern();
		this.localRequestOnly = localRequestOnly;
		this.ignoreUSKDatehints = false;
	}

	public InsertContext(InsertContext ctx, SimpleEventProducer producer) {
		this.dontCompress = ctx.dontCompress;
		this.splitfileAlgorithm = ctx.splitfileAlgorithm;
		this.consecutiveRNFsCountAsSuccess = ctx.consecutiveRNFsCountAsSuccess;
		this.maxInsertRetries = ctx.maxInsertRetries;
		this.eventProducer = producer;
		this.splitfileSegmentDataBlocks = ctx.splitfileSegmentDataBlocks;
		this.splitfileSegmentCheckBlocks = ctx.splitfileSegmentCheckBlocks;
		this.compressorDescriptor = ctx.compressorDescriptor;
		this.forkOnCacheable = ctx.forkOnCacheable;
		this.extraInsertsSingleBlock = ctx.extraInsertsSingleBlock;
		this.extraInsertsSplitfileHeaderBlock = ctx.extraInsertsSplitfileHeaderBlock;
		this.compatibilityMode = ctx.compatibilityMode;
		this.localRequestOnly = ctx.localRequestOnly;
		this.ignoreUSKDatehints = ctx.ignoreUSKDatehints;
	}
	
	/** Make public, but just call parent for a field for field copy */
	@Override
	public InsertContext clone() {
		try {
			return (InsertContext) super.clone();
		} catch (CloneNotSupportedException e) {
			// Impossible
			throw new Error(e);
		}
	}
	
    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + (canWriteClientCache ? 1231 : 1237);
        result = prime * result + compatibilityMode.ordinal();
        result = prime * result
                + ((compressorDescriptor == null) ? 0 : compressorDescriptor.hashCode());
        result = prime * result + consecutiveRNFsCountAsSuccess;
        result = prime * result + (dontCompress ? 1231 : 1237);
        // eventProducer is ignored.
        result = prime * result + extraInsertsSingleBlock;
        result = prime * result + extraInsertsSplitfileHeaderBlock;
        result = prime * result + (forkOnCacheable ? 1231 : 1237);
        result = prime * result + (ignoreUSKDatehints ? 1231 : 1237);
        result = prime * result + (localRequestOnly ? 1231 : 1237);
        result = prime * result + maxInsertRetries;
        result = prime * result + splitfileAlgorithm;
        result = prime * result + splitfileSegmentCheckBlocks;
        result = prime * result + splitfileSegmentDataBlocks;
        return result;
    }

    /** Are two InsertContext's equal? Ignores the EventProducer, compares only the actual config
     * values. */
    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        InsertContext other = (InsertContext) obj;
        if (canWriteClientCache != other.canWriteClientCache)
            return false;
        if (compatibilityMode != other.compatibilityMode)
            return false;
        if (compressorDescriptor == null) {
            if (other.compressorDescriptor != null)
                return false;
        } else if (!compressorDescriptor.equals(other.compressorDescriptor))
            return false;
        if (consecutiveRNFsCountAsSuccess != other.consecutiveRNFsCountAsSuccess)
            return false;
        if (dontCompress != other.dontCompress)
            return false;
        // eventProducer is ignored, and assumed to be unique.
        if (extraInsertsSingleBlock != other.extraInsertsSingleBlock)
            return false;
        if (extraInsertsSplitfileHeaderBlock != other.extraInsertsSplitfileHeaderBlock)
            return false;
        if (forkOnCacheable != other.forkOnCacheable)
            return false;
        if (ignoreUSKDatehints != other.ignoreUSKDatehints)
            return false;
        if (localRequestOnly != other.localRequestOnly)
            return false;
        if (maxInsertRetries != other.maxInsertRetries)
            return false;
        if (splitfileAlgorithm != other.splitfileAlgorithm)
            return false;
        if (splitfileSegmentCheckBlocks != other.splitfileSegmentCheckBlocks)
            return false;
        if (splitfileSegmentDataBlocks != other.splitfileSegmentDataBlocks)
            return false;
        return true;
    }

}
