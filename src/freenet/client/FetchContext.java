/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client;

import java.util.Set;

import freenet.client.async.BlockSet;
import freenet.client.async.USKManager;
import freenet.client.events.ClientEventProducer;
import freenet.client.events.SimpleEventProducer;
import freenet.node.Ticker;
import freenet.support.Executor;
import freenet.support.api.BucketFactory;

/** Context for a Fetcher. Contains all the settings a Fetcher needs to know about. */
public class FetchContext implements Cloneable {

	public static final int IDENTICAL_MASK = 0;
	public static final int SPLITFILE_DEFAULT_BLOCK_MASK = 1;
	public static final int SPLITFILE_DEFAULT_MASK = 2;
	public static final int SET_RETURN_ARCHIVES = 4;
	/** Low-level client to send low-level requests to. */
	public long maxOutputLength;
	public long maxTempLength;
	public final BucketFactory bucketFactory;
	public USKManager uskManager;
	public int maxRecursionLevel;
	public int maxArchiveRestarts;
	public int maxArchiveLevels;
	public boolean dontEnterImplicitArchives;
	public int maxSplitfileThreads;
	public int maxSplitfileBlockRetries;
	public int maxNonSplitfileRetries;
	public boolean allowSplitfiles;
	public boolean followRedirects;
	public boolean localRequestOnly;
	public boolean ignoreStore;
	public final ClientEventProducer eventProducer;
	public int maxMetadataSize;
	public int maxDataBlocksPerSegment;
	public int maxCheckBlocksPerSegment;
	public boolean cacheLocalRequests;
	/** If true, and we get a ZIP manifest, and we have no meta-strings left, then
	 * return the manifest contents as data. */
	public boolean returnZIPManifests;
	public final boolean ignoreTooManyPathComponents;
	/** If set, contains a set of blocks to be consulted before checking the datastore. */
	public BlockSet blocks;
	public Set allowedMIMETypes;
	public final Ticker ticker;
	public final Executor executor;
	public final Executor[] slowSerialExecutor;
	
	public FetchContext(long curMaxLength, 
			long curMaxTempLength, int maxMetadataSize, int maxRecursionLevel, int maxArchiveRestarts, int maxArchiveLevels,
			boolean dontEnterImplicitArchives, int maxSplitfileThreads,
			int maxSplitfileBlockRetries, int maxNonSplitfileRetries,
			boolean allowSplitfiles, boolean followRedirects, boolean localRequestOnly,
			int maxDataBlocksPerSegment, int maxCheckBlocksPerSegment,
			BucketFactory bucketFactory,
			ClientEventProducer producer, boolean cacheLocalRequests, USKManager uskManager, 
			boolean ignoreTooManyPathComponents, Ticker ticker, Executor executor, 
			Executor[] slowSerialExecutor) {
		this.ticker = ticker;
		this.executor = executor;
		this.slowSerialExecutor = slowSerialExecutor;
		this.maxOutputLength = curMaxLength;
		this.uskManager = uskManager;
		this.maxTempLength = curMaxTempLength;
		this.maxMetadataSize = maxMetadataSize;
		this.bucketFactory = bucketFactory;
		this.maxRecursionLevel = maxRecursionLevel;
		this.maxArchiveRestarts = maxArchiveRestarts;
		this.maxArchiveLevels = maxArchiveLevels;
		this.dontEnterImplicitArchives = dontEnterImplicitArchives;
		this.maxSplitfileThreads = maxSplitfileThreads;
		this.maxSplitfileBlockRetries = maxSplitfileBlockRetries;
		this.maxNonSplitfileRetries = maxNonSplitfileRetries;
		this.allowSplitfiles = allowSplitfiles;
		this.followRedirects = followRedirects;
		this.localRequestOnly = localRequestOnly;
		this.eventProducer = producer;
		this.maxDataBlocksPerSegment = maxDataBlocksPerSegment;
		this.maxCheckBlocksPerSegment = maxCheckBlocksPerSegment;
		this.cacheLocalRequests = cacheLocalRequests;
		this.ignoreTooManyPathComponents = ignoreTooManyPathComponents;
	}

	public FetchContext(FetchContext ctx, int maskID, boolean keepProducer) {
		if(keepProducer)
			this.eventProducer = ctx.eventProducer;
		else
			this.eventProducer = new SimpleEventProducer();
		this.ticker = ctx.ticker;
		this.executor = ctx.executor;
		this.slowSerialExecutor = ctx.slowSerialExecutor;
		this.uskManager = ctx.uskManager;
		this.ignoreTooManyPathComponents = ctx.ignoreTooManyPathComponents;
		this.blocks = ctx.blocks;
		this.allowedMIMETypes = ctx.allowedMIMETypes;
		if(maskID == IDENTICAL_MASK) {
			this.maxOutputLength = ctx.maxOutputLength;
			this.maxMetadataSize = ctx.maxMetadataSize;
			this.maxTempLength = ctx.maxTempLength;
			this.bucketFactory = ctx.bucketFactory;
			this.maxRecursionLevel = ctx.maxRecursionLevel;
			this.maxArchiveRestarts = ctx.maxArchiveRestarts;
			this.maxArchiveLevels = ctx.maxArchiveLevels;
			this.dontEnterImplicitArchives = ctx.dontEnterImplicitArchives;
			this.maxSplitfileThreads = ctx.maxSplitfileThreads;
			this.maxSplitfileBlockRetries = ctx.maxSplitfileBlockRetries;
			this.maxNonSplitfileRetries = ctx.maxNonSplitfileRetries;
			this.allowSplitfiles = ctx.allowSplitfiles;
			this.followRedirects = ctx.followRedirects;
			this.localRequestOnly = ctx.localRequestOnly;
			this.maxDataBlocksPerSegment = ctx.maxDataBlocksPerSegment;
			this.maxCheckBlocksPerSegment = ctx.maxCheckBlocksPerSegment;
			this.cacheLocalRequests = ctx.cacheLocalRequests;
			this.returnZIPManifests = ctx.returnZIPManifests;
		} else if(maskID == SPLITFILE_DEFAULT_BLOCK_MASK) {
			this.maxOutputLength = ctx.maxOutputLength;
			this.maxMetadataSize = ctx.maxMetadataSize;
			this.maxTempLength = ctx.maxTempLength;
			this.bucketFactory = ctx.bucketFactory;
			this.maxRecursionLevel = 1;
			this.maxArchiveRestarts = 0;
			this.maxArchiveLevels = ctx.maxArchiveLevels;
			this.dontEnterImplicitArchives = true;
			this.maxSplitfileThreads = 0;
			this.maxSplitfileBlockRetries = ctx.maxSplitfileBlockRetries;
			this.maxNonSplitfileRetries = ctx.maxSplitfileBlockRetries;
			this.allowSplitfiles = false;
			this.followRedirects = false;
			this.localRequestOnly = ctx.localRequestOnly;
			this.maxDataBlocksPerSegment = 0;
			this.maxCheckBlocksPerSegment = 0;
			this.cacheLocalRequests = ctx.cacheLocalRequests;
			this.returnZIPManifests = false;
		} else if(maskID == SPLITFILE_DEFAULT_MASK) {
			this.maxOutputLength = ctx.maxOutputLength;
			this.maxTempLength = ctx.maxTempLength;
			this.maxMetadataSize = ctx.maxMetadataSize;
			this.bucketFactory = ctx.bucketFactory;
			this.maxRecursionLevel = ctx.maxRecursionLevel;
			this.maxArchiveRestarts = ctx.maxArchiveRestarts;
			this.maxArchiveLevels = ctx.maxArchiveLevels;
			this.dontEnterImplicitArchives = ctx.dontEnterImplicitArchives;
			this.maxSplitfileThreads = ctx.maxSplitfileThreads;
			this.maxSplitfileBlockRetries = ctx.maxSplitfileBlockRetries;
			this.maxNonSplitfileRetries = ctx.maxNonSplitfileRetries;
			this.allowSplitfiles = ctx.allowSplitfiles;
			this.followRedirects = ctx.followRedirects;
			this.localRequestOnly = ctx.localRequestOnly;
			this.maxDataBlocksPerSegment = ctx.maxDataBlocksPerSegment;
			this.maxCheckBlocksPerSegment = ctx.maxCheckBlocksPerSegment;
			this.cacheLocalRequests = ctx.cacheLocalRequests;
			this.returnZIPManifests = ctx.returnZIPManifests;
		} else if (maskID == SET_RETURN_ARCHIVES) {
			this.maxOutputLength = ctx.maxOutputLength;
			this.maxMetadataSize = ctx.maxMetadataSize;
			this.maxTempLength = ctx.maxTempLength;
			this.bucketFactory = ctx.bucketFactory;
			this.maxRecursionLevel = ctx.maxRecursionLevel;
			this.maxArchiveRestarts = ctx.maxArchiveRestarts;
			this.maxArchiveLevels = ctx.maxArchiveLevels;
			this.dontEnterImplicitArchives = ctx.dontEnterImplicitArchives;
			this.maxSplitfileThreads = ctx.maxSplitfileThreads;
			this.maxSplitfileBlockRetries = ctx.maxSplitfileBlockRetries;
			this.maxNonSplitfileRetries = ctx.maxNonSplitfileRetries;
			this.allowSplitfiles = ctx.allowSplitfiles;
			this.followRedirects = ctx.followRedirects;
			this.localRequestOnly = ctx.localRequestOnly;
			this.maxDataBlocksPerSegment = ctx.maxDataBlocksPerSegment;
			this.maxCheckBlocksPerSegment = ctx.maxCheckBlocksPerSegment;
			this.cacheLocalRequests = ctx.cacheLocalRequests;
			this.returnZIPManifests = true;
		}
		else throw new IllegalArgumentException();
	}

	/** Make public, but just call parent for a field for field copy */
	public Object clone() {
		try {
			return super.clone();
		} catch (CloneNotSupportedException e) {
			// Impossible
			throw new Error(e);
		}
	}
	
}
