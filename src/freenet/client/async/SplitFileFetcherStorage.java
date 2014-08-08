package freenet.client.async;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import freenet.client.ClientMetadata;
import freenet.client.FailureCodeTracker;
import freenet.client.FetchContext;
import freenet.client.FetchException;
import freenet.client.InsertContext.CompatibilityMode;
import freenet.client.Metadata;
import freenet.client.MetadataParseException;
import freenet.client.MetadataUnresolvedException;
import freenet.client.FECCodec;
import freenet.crypt.ChecksumChecker;
import freenet.crypt.ChecksumFailedException;
import freenet.crypt.RandomSource;
import freenet.keys.CHKBlock;
import freenet.keys.ClientKey;
import freenet.keys.FreenetURI;
import freenet.keys.Key;
import freenet.node.KeysFetchingLocally;
import freenet.node.SendableRequestItem;
import freenet.node.SendableRequestItemKey;
import freenet.support.Logger;
import freenet.support.MemoryLimitedJobRunner;
import freenet.support.Ticker;
import freenet.support.api.Bucket;
import freenet.support.api.BucketFactory;
import freenet.support.compress.Compressor.COMPRESSOR_TYPE;
import freenet.support.io.BucketTools;
import freenet.support.io.LockableRandomAccessThing;
import freenet.support.io.NativeThread;
import freenet.support.io.LockableRandomAccessThing.RAFLock;
import freenet.support.io.LockableRandomAccessThingFactory;

/** <p>Stores the state for a SplitFileFetcher, persisted to a LockableRandomAccessThing (i.e. a 
 * single random access file), but with most of the metadata in memory. The data, and the larger
 * metadata such as the full keys, are read from disk when needed, and persisted to disk.</p>
 * 
 * <p>On disk format goals:</p>
 * <ol><li>Maximise robustness.</li>
 * <li>Minimise seeks.</li>
 * <li>Minimise disk usage.</li>
 * <li>Be as simple as realistically possible.</li></ol>
 * 
 * <p>Overall on-disk structure:
 * BLOCK STORAGE: Decoded data, one segment at a time (the last segment's size is rounded up to a 
 * whole block). Within each segment, the number of blocks is equal to the number of data blocks 
 * (plus the number of cross-check blocks if there are cross-check blocks), but they are not 
 * necessarily actually data blocks (they may be check blocks), and they may not be in the correct 
 * order. When we FEC decode, we read in the blocks, construct the CHKs to see what keys they 
 * belong to, check that we still have enough valid keys (update the metadata if the counts were 
 * wrong), do the decode, and write the data blocks back in the correct order; the segment is 
 * finished. When all the segments are finished, we generate a stream as usual, i.e. we still need
 * to copy the file. It may be possible in future to simply truncate the file but in many cases we
 * need to decompress or filter, and there are significant issues with code complexity and seeks 
 * during FEC decodes, see bug #6063. 
 * 
 * KEY LIST: The original key list. Not changed when a block is fetched.
 * - Fixed and checksummed (each segment has a checksum).
 * 
 * SEGMENT STATUS: The status of each segment, including the status of each block, including flags 
 * and where it is in the block storage within the segment.
 * - Checksummed per segment. So it needs to be written as a whole segment. Can be regenerated from 
 * the block store and key list, which happens routinely when FEC decoding.
 * 
 * BLOOM FILTERS: Main bloom filter. Segment bloom filters.
 * 
 * ORIGINAL METADATA: For extra robustness, keep the full original metadata.
 * 
 * ORIGINAL URL: If the original key is available, keep that too.
 * 
 * BASIC SETTINGS: Type of splitfile, length of file, overall decryption key, number of blocks and 
 * check blocks per segment, etc.
 * - Fixed and checksummed. Read as a block so we can check the checksum.
 * 
 * FOOTER:
 * Length of basic settings. (So we can seek back to get them)
 * Version number.
 * Checksum.
 * Magic value.
 * 
 * OTHER NOTES:
 * 
 * CHECKSUMS: 4-byte CRC32.
 * 
 * CONCURRENCY: Callbacks into fetcher should be run off-thread, as they will usually be inside a 
 * MemoryLimitedJob.
 * 
 * LOCKING: Trivial or taken last. Hence can be called inside e.g. RGA calls to getCooldownTime 
 * etc.
 * 
 * PERSISTENCE: This whole class is transient. It is recreated on startup by the 
 * SplitFileFetcher. Many of the fields are also transient, e.g. 
 * SplitFileFetcherSegmentStorage's cooldown fields.
 * @author toad
 */
public class SplitFileFetcherStorage {

    private static volatile boolean logMINOR;
    static {
        Logger.registerClass(SplitFileFetcherStorage.class);
    }
    
    final SplitFileFetcherCallback fetcher;
    // Metadata for the fetch
    /** The underlying presumably-on-disk storage. */ 
    private final LockableRandomAccessThing raf;
    /** The segments */
    final SplitFileFetcherSegmentStorage[] segments;
    /** The cross-segments. Null if no cross-segments. */
    private final SplitFileFetcherCrossSegmentStorage[] crossSegments;
    /** If the splitfile has a common encryption algorithm, this is it. */
    final byte splitfileSingleCryptoAlgorithm;
    /** If the splitfile has a common encryption key, this is it. */
    final byte[] splitfileSingleCryptoKey;
    /** FEC codec for the splitfile, if needed. */
    public final FECCodec fecCodec;
    final Ticker ticker;
    final PersistentJobRunner jobRunner;
    final MemoryLimitedJobRunner memoryLimitedJobRunner;
    final long finalLength;
    final short splitfileType;
    /** MIME type etc. Set on construction and passed to onSuccess(). */
    final ClientMetadata clientMetadata;
    /** Decompressors. Set on construction and passed to onSuccess(). */
    final List<COMPRESSOR_TYPE> decompressors;
    /** False = Transient: We are using the RAF as scratch space, we only need to write the blocks,
     * and the keys (if we don't keep them in memory). True = Persistent: It must be possible to 
     * resume after a node restart. Ideally we'd like to be able to recover the download in its
     * entirety without needing any additional information, but at a minimum we want to be able to
     * continue it while passing in the usual external arguments (FetchContext, parent, etc). */
    final boolean persistent;
    
    private boolean finishedFetcher;
    private boolean finishedEncoding;
    private boolean cancelled;
    
    /** Errors. For now, this is not persisted (FIXME). */
    private final FailureCodeTracker errors;
    final int maxRetries;
    /** Every cooldownTries attempts, a key will enter cooldown, and won't be re-tried for a period. */
    final int cooldownTries;
    /** Cooldown lasts this long for each key. */
    final long cooldownLength;
    /** Only set if all segments are in cooldown. */
    private long overallCooldownWakeupTime;
    final CompatibilityMode finalMinCompatMode;
    
    /** Contains Bloom filters */
    final SplitFileFetcherKeyListener keyListener;
    
    final RandomSource random;
    
    // Metadata for the file i.e. stuff we need to be able to efficiently read/write it.
    /** Offset to start of the key lists in bytes */
    final long offsetKeyList;
    /** Offset to start of the segment status'es in bytes */
    final long offsetSegmentStatus;
    /** Offset to start of the general progress section */
    final long offsetGeneralProgress;
    /** Offset to start of the bloom filters in bytes */
    final long offsetMainBloomFilter;
    /** Offset to start of the per-segment bloom filters in bytes */
    final long offsetSegmentBloomFilters;
    /** Offset to start of the original metadata in bytes */
    final long offsetOriginalMetadata;
    /** Offset to start of the original details in bytes. "Original details" includes the URI to 
     * this download (if available), the original URI for the whole download (if available), 
     * whether this is the final fetch (it might be a metadata or container fetch), and data from
     * the ultimate client, e.g. the Identifier, whether it is on the Global queue, the client name
     * if it isn't etc. */
    final long offsetOriginalDetails;
    /** Offset to start of the basic settings in bytes */
    final long offsetBasicSettings;
    /** Length of all section checksums */
    final int checksumLength;
    /** Checksum implementation */
    final ChecksumChecker checksumChecker;
    private boolean hasCheckedDatastore;
    static final long HAS_CHECKED_DATASTORE_FLAG = 1;
    /** Fixed value posted at the end of the file (if plaintext!) */
    static final long END_MAGIC = 0x28b32d99416eb6efL;
    /** Current format version */
    static final int VERSION = 1;
    
    /** List of segments we need to tryStartDecode() on because their metadata was corrupted on
     * startup. */
    private List<SplitFileFetcherSegmentStorage> segmentsToTryDecode;
    
    /** Construct a new SplitFileFetcherStorage from metadata. Creates the RandomAccessThing and
     * writes the initial data to it. There is another constructor for resuming a download. 
     * @param persistent 
     * @param topCompatibilityMode 
     * @param clientMetadata2 
     * @param decompressors2
     * @param cb This is only provided so we can create appropriate events when constructing, we do
     * not store it. 
     * @throws FetchException If we failed to set up the download due to a problem with the metadata. 
     * @throws MetadataParseException 
     * @throws IOException If we were unable to create the file to store the metadata and 
     * downloaded blocks in. */
    public SplitFileFetcherStorage(Metadata metadata, SplitFileFetcherCallback fetcher, 
            List<COMPRESSOR_TYPE> decompressors, ClientMetadata clientMetadata, 
            boolean topDontCompress, short topCompatibilityMode, FetchContext origFetchContext,
            boolean realTime, KeySalter salt, FreenetURI thisKey, FreenetURI origKey, 
            boolean isFinalFetch, byte[] clientDetails, RandomSource random, 
            BucketFactory tempBucketFactory, LockableRandomAccessThingFactory rafFactory, 
            PersistentJobRunner exec, Ticker ticker, MemoryLimitedJobRunner memoryLimitedJobRunner, 
            ChecksumChecker checker, boolean persistent) 
    throws FetchException, MetadataParseException, IOException {
        this.fetcher = fetcher;
        this.jobRunner = exec;
        this.ticker = ticker;
        this.memoryLimitedJobRunner = memoryLimitedJobRunner;
        this.finalLength = metadata.dataLength();
        this.splitfileType = metadata.getSplitfileType();
        this.fecCodec = FECCodec.getInstance(splitfileType);
        this.decompressors = decompressors;
        this.random = random;
        this.errors = new FailureCodeTracker(false);
        this.checksumChecker = checker;
        this.checksumLength = checker.checksumLength();
        this.persistent = persistent;
        if(decompressors.size() > 1) {
            Logger.error(this, "Multiple decompressors: "+decompressors.size()+" - this is almost certainly a bug", new Exception("debug"));
        }
        this.clientMetadata = clientMetadata == null ? new ClientMetadata() : clientMetadata.clone(); // copy it as in SingleFileFetcher
        SplitFileSegmentKeys[] segmentKeys = metadata.getSegmentKeys();
        CompatibilityMode minCompatMode = metadata.getMinCompatMode();
        CompatibilityMode maxCompatMode = metadata.getMaxCompatMode();

        int crossCheckBlocks = metadata.getCrossCheckBlocks();
        
        maxRetries = origFetchContext.maxSplitfileBlockRetries;
        cooldownTries = origFetchContext.getCooldownRetries();
        cooldownLength = origFetchContext.getCooldownTime();
        this.splitfileSingleCryptoAlgorithm = metadata.getSplitfileCryptoAlgorithm();
        splitfileSingleCryptoKey = metadata.getSplitfileCryptoKey();
        
        // These are approximate values, the number of blocks per segment varies.
        int blocksPerSegment = metadata.getDataBlocksPerSegment();
        int checkBlocksPerSegment = metadata.getCheckBlocksPerSegment();
        
        int splitfileDataBlocks = 0;
        int splitfileCheckBlocks = 0;
        
        long storedBlocksLength = 0;
        long storedKeysLength = 0;
        long storedSegmentStatusLength = 0;

        for(SplitFileSegmentKeys keys : segmentKeys) {
            int dataBlocks = keys.getDataBlocks();
            int checkBlocks = keys.getCheckBlocks();
            splitfileDataBlocks += dataBlocks;
            splitfileCheckBlocks += checkBlocks;
            storedKeysLength +=
                SplitFileFetcherSegmentStorage.storedKeysLength(dataBlocks, checkBlocks, splitfileSingleCryptoKey != null, checksumLength);
            storedSegmentStatusLength +=
                SplitFileFetcherSegmentStorage.paddedStoredSegmentStatusLength(dataBlocks, checkBlocks, 
                        crossCheckBlocks, maxRetries != -1, checksumLength, persistent);
        }
        
        storedBlocksLength = (splitfileDataBlocks + splitfileCheckBlocks) * CHKBlock.DATA_LENGTH;
        
        int segmentCount = metadata.getSegmentCount();
        
        if(splitfileType == Metadata.SPLITFILE_NONREDUNDANT) {
            if(splitfileCheckBlocks > 0) {
                Logger.error(this, "Splitfile type is SPLITFILE_NONREDUNDANT yet "+splitfileCheckBlocks+" check blocks found!! : "+this);
                throw new FetchException(FetchException.INVALID_METADATA, "Splitfile type is non-redundant yet have "+splitfileCheckBlocks+" check blocks");
            }
        } else if(splitfileType == Metadata.SPLITFILE_ONION_STANDARD) {
            
            boolean dontCompress = decompressors.isEmpty();
            if(topCompatibilityMode != 0) {
                // If we have top compatibility mode, then we can give a definitive answer immediately, with the splitfile key, with dontcompress, etc etc.
                if(minCompatMode == CompatibilityMode.COMPAT_UNKNOWN ||
                        !(minCompatMode.ordinal() > topCompatibilityMode || maxCompatMode.ordinal() < topCompatibilityMode)) {
                    minCompatMode = maxCompatMode = CompatibilityMode.values()[topCompatibilityMode];
                    dontCompress = topDontCompress;
                } else
                    throw new FetchException(FetchException.INVALID_METADATA, "Top compatibility mode is incompatible with detected compatibility mode");
            }
            // We assume we are the bottom layer. 
            // If the top-block stats are passed in then we can safely say the report is definitive.
            fetcher.onSplitfileCompatibilityMode(minCompatMode, maxCompatMode, metadata.getCustomSplitfileKey(), dontCompress, true, topCompatibilityMode != 0);

            if((blocksPerSegment > origFetchContext.maxDataBlocksPerSegment)
                    || (checkBlocksPerSegment > origFetchContext.maxCheckBlocksPerSegment))
                throw new FetchException(FetchException.TOO_MANY_BLOCKS_PER_SEGMENT, "Too many blocks per segment: "+blocksPerSegment+" data, "+checkBlocksPerSegment+" check");
            
                
        } else throw new MetadataParseException("Unknown splitfile format: "+splitfileType);

        if(logMINOR)
            Logger.minor(this, "Algorithm: "+splitfileType+", blocks per segment: "+blocksPerSegment+
                    ", check blocks per segment: "+checkBlocksPerSegment+", segments: "+segmentCount+
                    ", data blocks: "+splitfileDataBlocks+", check blocks: "+splitfileCheckBlocks);
        segments = new SplitFileFetcherSegmentStorage[segmentCount]; // initially null on all entries
        
        long checkLength = 1L * (splitfileDataBlocks - segmentCount * crossCheckBlocks) * CHKBlock.DATA_LENGTH;
        if(checkLength > finalLength) {
            if(checkLength - finalLength > CHKBlock.DATA_LENGTH)
                throw new FetchException(FetchException.INVALID_METADATA, "Splitfile is "+checkLength+" bytes long but length is "+finalLength+" bytes");
        }
        
        byte[] localSalt = new byte[32];
        random.nextBytes(localSalt);
        
        keyListener = new SplitFileFetcherKeyListener(fetcher, this, realTime, false, 
                localSalt, splitfileDataBlocks + splitfileCheckBlocks, blocksPerSegment + 
                checkBlocksPerSegment, segmentCount);

        finalMinCompatMode = minCompatMode;
        
        this.offsetKeyList = storedBlocksLength;
        this.offsetSegmentStatus = offsetKeyList + storedKeysLength;
        
        byte[] generalProgress = appendChecksum(encodeGeneralProgress());
        
        if(persistent) {
            offsetGeneralProgress = offsetSegmentStatus + storedSegmentStatusLength;
            this.offsetMainBloomFilter = offsetGeneralProgress + generalProgress.length; 
            this.offsetSegmentBloomFilters = offsetMainBloomFilter + keyListener.paddedMainBloomFilterSize();
            this.offsetOriginalMetadata = offsetSegmentBloomFilters + 
                keyListener.totalSegmentBloomFiltersSize();
        } else {
            // Don't store anything except the blocks and the key list.
            offsetGeneralProgress = offsetMainBloomFilter = offsetSegmentBloomFilters = offsetOriginalMetadata = offsetSegmentStatus;
        }
            
        
        long dataOffset = 0;
        long segmentKeysOffset = offsetKeyList;
        long segmentStatusOffset = offsetSegmentStatus;
        
        for(int i=0;i<segments.length;i++) {
            // splitfile* will be overwritten, this is bad
            // so copy them
            SplitFileSegmentKeys keys = segmentKeys[i];
            final int dataBlocks = keys.getDataBlocks();
            final int checkBlocks = keys.getCheckBlocks();
            if((dataBlocks > origFetchContext.maxDataBlocksPerSegment)
                    || (checkBlocks > origFetchContext.maxCheckBlocksPerSegment))
                throw new FetchException(FetchException.TOO_MANY_BLOCKS_PER_SEGMENT, "Too many blocks per segment: "+blocksPerSegment+" data, "+checkBlocksPerSegment+" check");
            segments[i] = new SplitFileFetcherSegmentStorage(this, i, splitfileType, 
                    dataBlocks-crossCheckBlocks, // Cross check blocks are included in data blocks for SplitFileSegmentKeys' purposes.
                    checkBlocks, crossCheckBlocks, dataOffset, segmentKeysOffset, segmentStatusOffset,
                    maxRetries != -1, keys);
            dataOffset += (dataBlocks+checkBlocks) * CHKBlock.DATA_LENGTH;
            segmentKeysOffset += 
                SplitFileFetcherSegmentStorage.storedKeysLength(dataBlocks+crossCheckBlocks, checkBlocks, splitfileSingleCryptoKey != null, checksumLength);
            segmentStatusOffset +=
                SplitFileFetcherSegmentStorage.paddedStoredSegmentStatusLength(dataBlocks, checkBlocks, 
                        crossCheckBlocks, maxRetries != -1, checksumLength, persistent);
            for(int j=0;j<(dataBlocks+checkBlocks);j++) {
                keyListener.addKey(keys.getKey(j, null, false).getNodeKey(false), i, salt);
            }
        }
        assert(dataOffset == storedBlocksLength);
        assert(segmentKeysOffset == storedBlocksLength + storedKeysLength);
        assert(segmentStatusOffset == storedBlocksLength + storedKeysLength + storedSegmentStatusLength);
        int totalCrossCheckBlocks = segments.length * crossCheckBlocks;
        fetcher.setSplitfileBlocks(splitfileDataBlocks - totalCrossCheckBlocks, splitfileCheckBlocks + totalCrossCheckBlocks);
        
        keyListener.finishedSetup();
        
        if(crossCheckBlocks != 0) {
            // FIXME
            throw new UnsupportedOperationException();
//            Random random = new MersenneTwister(Metadata.getCrossSegmentSeed(metadata.getHashes(), metadata.getHashThisLayerOnly()));
//            // Cross segment redundancy: Allocate the blocks.
//            crossSegments = new SplitFileFetcherCrossSegmentStorage[segments.length];
//            int segLen = blocksPerSegment;
//            for(int i=0;i<crossSegments.length;i++) {
//                Logger.normal(this, "Allocating blocks (on fetch) for cross segment "+i);
//                if(segments.length - i == deductBlocksFromSegments) {
//                    segLen--;
//                }
//                SplitFileFetcherCrossSegmentStorage seg = 
//                    new SplitFileFetcherCrossSegmentStorage(i, segLen, crossCheckBlocks, parent, 
//                            this, splitfileType);
//                crossSegments[i] = seg;
//                for(int j=0;j<segLen;j++) {
//                    // Allocate random data blocks
//                    allocateCrossDataBlock(seg, random);
//                }
//                for(int j=0;j<crossCheckBlocks;j++) {
//                    // Allocate check blocks
//                    allocateCrossCheckBlock(seg, random);
//                }
//            }
        } else {
            crossSegments = null;
        }
        
        long totalLength;
        Bucket metadataTemp;
        byte[] encodedURI;
        byte[] encodedBasicSettings;
        if(persistent) {
            // Write the metadata to a temporary file to get its exact length.
            metadataTemp = tempBucketFactory.makeBucket(-1);
            OutputStream os = metadataTemp.getOutputStream();
            OutputStream cos = checksumOutputStream(os);
            BufferedOutputStream bos = new BufferedOutputStream(cos);
            try {
                metadata.writeTo(new DataOutputStream(bos));
            } catch (MetadataUnresolvedException e) {
                throw new FetchException(FetchException.INTERNAL_ERROR, "Metadata not resolved starting splitfile fetch?!: "+e, e);
            }
            bos.close();
            long metadataLength = metadataTemp.size();
            offsetOriginalDetails = offsetOriginalMetadata + metadataLength;
            
            encodedURI = encodeAndChecksumOriginalDetails(thisKey, origKey, clientDetails, isFinalFetch);
            this.offsetBasicSettings = offsetOriginalDetails + encodedURI.length;
            
            encodedBasicSettings = encodeBasicSettings();
            totalLength = 
                offsetBasicSettings + // rest of file
                encodedBasicSettings.length + // basic settings
                4 + // length of basic settings
                checksumLength + // might as well checksum the footer as well
                4 + // version
                4 + // flags
                2 + // checksum type
                8; // magic
        } else {
            totalLength = offsetSegmentStatus;
            offsetOriginalDetails = offsetBasicSettings = offsetSegmentStatus;
            metadataTemp = null;
            encodedURI = encodedBasicSettings = null;
        }
        
        // Create the actual LockableRandomAccessThing
        
        this.raf = rafFactory.makeRAF(totalLength);
        RAFLock lock = raf.lockOpen();
        try {
            for(int i=0;i<segments.length;i++) {
                SplitFileFetcherSegmentStorage segment = segments[i];
                segment.writeKeysWithChecksum(segmentKeys[i]);
            }
            if(persistent) {
                for(SplitFileFetcherSegmentStorage segment : segments)
                    segment.writeMetadata();
                raf.pwrite(offsetGeneralProgress, generalProgress, 0, generalProgress.length);
                keyListener.innerWriteMainBloomFilter(offsetMainBloomFilter);
                keyListener.initialWriteSegmentBloomFilters(offsetSegmentBloomFilters);
                BucketTools.copyTo(metadataTemp, raf, offsetOriginalMetadata, -1);
                metadataTemp.free();
                raf.pwrite(offsetOriginalDetails, encodedURI, 0, encodedURI.length);
                raf.pwrite(offsetBasicSettings, encodedBasicSettings, 0, encodedBasicSettings.length);
                
                // This bit tricky because version is included in the checksum.
                // When the RAF is encrypted, we use HMAC's and this is important.
                // FIXME is Fields.bytesToInt etc compatible with DataOutputStream.*?
                // FIXME if not, we need something that is ...
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                DataOutputStream dos = new DataOutputStream(baos);
                dos.writeInt(encodedBasicSettings.length - checksumLength);
                byte[] bufToWrite = baos.toByteArray();
                baos = new ByteArrayOutputStream();
                dos = new DataOutputStream(baos);
                dos.writeInt(0); // flags
                dos.writeShort(checksumChecker.getChecksumTypeID());
                dos.writeInt(VERSION);
                byte[] version = baos.toByteArray();
                byte[] bufToChecksum = Arrays.copyOf(bufToWrite, bufToWrite.length+version.length);
                System.arraycopy(version, 0, bufToChecksum, bufToWrite.length, version.length);
                byte[] checksum = 
                    checksumChecker.generateChecksum(bufToChecksum);
                // Pointers.
                raf.pwrite(offsetBasicSettings + encodedBasicSettings.length, bufToWrite, 0, 
                        bufToWrite.length);
                // Checksum.
                raf.pwrite(offsetBasicSettings + encodedBasicSettings.length + bufToWrite.length, 
                        checksum, 0, checksum.length);
                // Version.
                raf.pwrite(offsetBasicSettings + encodedBasicSettings.length + bufToWrite.length + 
                        checksum.length, version, 0, version.length);
                // Write magic last.
                baos = new ByteArrayOutputStream();
                dos = new DataOutputStream(baos);
                dos.writeLong(END_MAGIC);
                byte[] buf = baos.toByteArray();
                raf.pwrite(totalLength - 8, buf, 0, 8);
            }
        } finally {
            lock.unlock();
        }
        if(logMINOR) Logger.minor(this, "Fetching "+thisKey+" on "+this+" for "+fetcher);
    }
    
    /** Construct a SplitFileFetcherStorage from a stored RandomAccessThing, and appropriate local
     * settings passed in. Ideally this would work with only basic system utilities such as 
     * those on ClientContext, i.e. we'd be able to restore the splitfile download without knowing
     * anything about it.
     * @param newSalt True if the global salt has changed.
     * @param salt The global salter. Should be passed in even if the global salt hasn't changed,
     * as we may not have completed regenerating bloom filters.
     * @throws IOException If the restore failed because of a failure to read from disk. 
     * @throws StorageFormatException 
     * @throws FetchException If the request has already failed (but it wasn't processed before 
     * restarting). */
    public SplitFileFetcherStorage(LockableRandomAccessThing raf, boolean realTime,  
            SplitFileFetcherCallback callback, FetchContext origContext,
            RandomSource random, PersistentJobRunner exec,
            Ticker ticker, MemoryLimitedJobRunner memoryLimitedJobRunner, ChecksumChecker checker, boolean newSalt, KeySalter salt) throws IOException, StorageFormatException, FetchException {
        this.persistent = true;
        this.raf = raf;
        this.fetcher = callback;
        this.ticker = ticker;
        this.jobRunner = exec;
        this.memoryLimitedJobRunner = memoryLimitedJobRunner;
        this.random = random;
        this.checksumChecker = checker;
        this.checksumLength = checker.checksumLength();
        this.maxRetries = origContext.maxSplitfileBlockRetries;
        this.cooldownTries = origContext.getCooldownRetries();
        this.cooldownLength = origContext.getCooldownTime();
        this.errors = new FailureCodeTracker(false); // FIXME persist???
        // FIXME this is hideous! Rewrite the writing/parsing code here in a less ugly way. However, it works...
        long rafLength = raf.size();
        if(raf.size() < 8 /* FIXME more! */)
            throw new StorageFormatException("Too short");
        // Last 8 bytes: Magic value.
        byte[] buf = new byte[8];
        raf.pread(rafLength-8, buf, 0, 8);
        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(buf));
        if(dis.readLong() != END_MAGIC)
            throw new StorageFormatException("Wrong magic bytes");
        // 4 bytes before that: Version.
        byte[] versionBuf = new byte[4];
        raf.pread(rafLength-12, versionBuf, 0, 4);
        dis = new DataInputStream(new ByteArrayInputStream(versionBuf));
        int version = dis.readInt();
        if(version != 1)
            throw new StorageFormatException("Wrong version "+version);
        // 2 bytes: Checksum type
        byte[] checksumTypeBuf = new byte[2];
        raf.pread(rafLength-14, checksumTypeBuf, 0, 2);
        dis = new DataInputStream(new ByteArrayInputStream(checksumTypeBuf));
        int checksumType = dis.readShort();
        if(checksumType != ChecksumChecker.CHECKSUM_CRC)
            throw new StorageFormatException("Unknown checksum type "+checksumType);
        // 4 bytes: Flags. Unused at present.
        byte[] flagsBuf = new byte[4];
        raf.pread(rafLength-18, flagsBuf, 0, 4);
        dis = new DataInputStream(new ByteArrayInputStream(flagsBuf));
        int flags = dis.readInt();
        if(flags != 0)
            throw new StorageFormatException("Unknown flags: "+flags);
        // 4 bytes basic settings length and a checksum, which includes both the settings length and the version.
        buf = new byte[14];
        raf.pread(rafLength-(22+checksumLength), buf, 0, 4);
        byte[] checksum = new byte[checksumLength];
        // Check the checksum.
        raf.pread(rafLength-(18+checksumLength), checksum, 0, checksumLength);
        System.arraycopy(flagsBuf, 0, buf, 4, 4);
        System.arraycopy(checksumTypeBuf, 0, buf, 8, 2);
        System.arraycopy(versionBuf, 0, buf, 10, 4);
        if(!checksumChecker.checkChecksum(buf, 0, 14, checksum))
            throw new StorageFormatException("Checksum failed on basic settings length and version");
        dis = new DataInputStream(new ByteArrayInputStream(buf));
        int basicSettingsLength = dis.readInt();
        if(basicSettingsLength < 0 || basicSettingsLength + 12 + 4 + checksumLength > raf.size() || 
                basicSettingsLength > 4096)
            throw new StorageFormatException("Bad basic settings length");
        byte[] basicSettingsBuffer = new byte[basicSettingsLength];
        long basicSettingsOffset = rafLength-(18+4+checksumLength*2+basicSettingsLength);
        try {
            preadChecksummed(basicSettingsOffset, 
                    basicSettingsBuffer, 0, basicSettingsLength);
        } catch (ChecksumFailedException e) {
            throw new StorageFormatException("Basic settings checksum invalid");
        }
        dis = new DataInputStream(new ByteArrayInputStream(basicSettingsBuffer));
        splitfileType = dis.readShort();
        if(!Metadata.isValidSplitfileType(splitfileType))
            throw new StorageFormatException("Invalid splitfile type "+splitfileType);
        this.fecCodec = FECCodec.getInstance(splitfileType);
        splitfileSingleCryptoAlgorithm = dis.readByte();
        if(!Metadata.isValidSplitfileCryptoAlgorithm(splitfileSingleCryptoAlgorithm))
            throw new StorageFormatException("Invalid splitfile crypto algorithm "+splitfileType);
        if(dis.readBoolean()) {
            splitfileSingleCryptoKey = new byte[32];
            dis.readFully(splitfileSingleCryptoKey);
        } else {
            splitfileSingleCryptoKey = null;
        }
        finalLength = dis.readLong();
        if(finalLength < 0)
            throw new StorageFormatException("Invalid final length "+finalLength);
        try {
            clientMetadata = ClientMetadata.construct(dis);
        } catch (MetadataParseException e) {
            throw new StorageFormatException("Invalid MIME type");
        }
        int decompressorCount = dis.readInt();
        if(decompressorCount < 0)
            throw new StorageFormatException("Invalid decompressor count "+decompressorCount);
        decompressors = new ArrayList<COMPRESSOR_TYPE>(decompressorCount);
        for(int i=0;i<decompressorCount;i++) {
            short type = dis.readShort();
            COMPRESSOR_TYPE d = COMPRESSOR_TYPE.getCompressorByMetadataID(type);
            if(d == null) throw new StorageFormatException("Invalid decompressor ID "+type);
            decompressors.add(d);
        }
        offsetKeyList = dis.readLong();
        if(offsetKeyList < 0 || offsetKeyList > rafLength) 
            throw new StorageFormatException("Invalid offset (key list)");
        offsetSegmentStatus = dis.readLong();
        if(offsetSegmentStatus < 0 || offsetSegmentStatus > rafLength) 
            throw new StorageFormatException("Invalid offset (segment status)");
        offsetGeneralProgress = dis.readLong();
        if(offsetGeneralProgress < 0 || offsetGeneralProgress > rafLength) 
            throw new StorageFormatException("Invalid offset (general progress)");
        offsetMainBloomFilter = dis.readLong();
        if(offsetMainBloomFilter < 0 || offsetMainBloomFilter > rafLength) 
            throw new StorageFormatException("Invalid offset (main bloom filter)");
        offsetSegmentBloomFilters = dis.readLong();
        if(offsetSegmentBloomFilters < 0 || offsetSegmentBloomFilters > rafLength) 
            throw new StorageFormatException("Invalid offset (segment bloom filters)");
        offsetOriginalMetadata = dis.readLong();
        if(offsetOriginalMetadata < 0 || offsetOriginalMetadata > rafLength) 
            throw new StorageFormatException("Invalid offset (original metadata)");
        offsetOriginalDetails = dis.readLong();
        if(offsetOriginalDetails < 0 || offsetOriginalDetails > rafLength) 
            throw new StorageFormatException("Invalid offset (original metadata)");
        offsetBasicSettings = dis.readLong();
        if(offsetBasicSettings != basicSettingsOffset)
            throw new StorageFormatException("Invalid basic settings offset (not the same as computed)");
        int compatMode = dis.readInt();
        if(compatMode < 0 || compatMode > CompatibilityMode.values().length)
            throw new StorageFormatException("Invalid compatibility mode "+compatMode);
        finalMinCompatMode = CompatibilityMode.values()[compatMode];
        int segmentCount = dis.readInt();
        if(segmentCount < 0) throw new StorageFormatException("Invalid segment count "+segmentCount);
        this.segments = new SplitFileFetcherSegmentStorage[segmentCount];
        long dataOffset = 0;
        long segmentKeysOffset = offsetKeyList;
        long segmentStatusOffset = offsetSegmentStatus;
        for(int i=0;i<segments.length;i++) {
            segments[i] = new SplitFileFetcherSegmentStorage(this, dis, i, maxRetries != -1, dataOffset, segmentKeysOffset, segmentStatusOffset);
            int dataBlocks = segments[i].dataBlocks;
            int checkBlocks = segments[i].checkBlocks;
            int crossCheckBlocks = segments[i].crossSegmentCheckBlocks;
            dataOffset += (dataBlocks+checkBlocks) * CHKBlock.DATA_LENGTH;
            segmentKeysOffset += 
                SplitFileFetcherSegmentStorage.storedKeysLength(dataBlocks+crossCheckBlocks, checkBlocks, splitfileSingleCryptoKey != null, checksumLength);
            segmentStatusOffset +=
                SplitFileFetcherSegmentStorage.paddedStoredSegmentStatusLength(dataBlocks, checkBlocks, 
                        crossCheckBlocks, maxRetries != -1, checksumLength, true);
        }
        int crossSegments = dis.readInt();
        if(crossSegments != 0)
            throw new StorageFormatException("Cross-segment not supported yet");
        this.crossSegments = null; // FIXME cross-segment splitfile support
        this.keyListener = new SplitFileFetcherKeyListener(this, fetcher, dis, realTime, false, newSalt);
        for(SplitFileFetcherSegmentStorage segment : segments) {
            boolean needsDecode = false;
            try {
                segment.readMetadata();
                if(segment.hasFailed()) {
                    raf.close();
                    raf.free(); // Failed, so free it.
                    throw new FetchException(FetchException.SPLITFILE_ERROR, errors);
                }
            } catch (ChecksumFailedException e) {
                Logger.error(this, "Progress for segment "+segment.segNo+" on "+this+" corrupted.");
                needsDecode = true;
            }
            if(segment.needsDecode())
                needsDecode = true;
            if(needsDecode) {
                if(segmentsToTryDecode == null)
                    segmentsToTryDecode = new ArrayList<SplitFileFetcherSegmentStorage>();
                segmentsToTryDecode.add(segment);
            }
        }
        for(int i=0;i<segments.length;i++) {
            SplitFileFetcherSegmentStorage segment = segments[i];
            try {
                segment.readSegmentKeys();
            } catch (ChecksumFailedException e) {
                throw new StorageFormatException("Keys corrupted");
            }
        }
        readGeneralProgress();
    }
    
    static final int GENERAL_PROGRESS_LENGTH_BEFORE_CHECKSUM = 8;
    
    private void readGeneralProgress() throws IOException {
        try {
            byte[] buf = new byte[GENERAL_PROGRESS_LENGTH_BEFORE_CHECKSUM];
            this.preadChecksummed(offsetGeneralProgress, buf, 0, buf.length);
            ByteArrayInputStream bais = new ByteArrayInputStream(buf);
            DataInputStream dis = new DataInputStream(bais);
            long flags = dis.readLong();
            if((flags & HAS_CHECKED_DATASTORE_FLAG) != 0)
                hasCheckedDatastore = true;
        } catch (ChecksumFailedException e) {
            // Reset general progress
            this.hasCheckedDatastore = false;
        }
    }

    private byte[] encodeGeneralProgress() {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        long flags = 0;
        if(hasCheckedDatastore)
            flags |= HAS_CHECKED_DATASTORE_FLAG;
        try {
            dos.writeLong(flags);
        } catch (IOException e) {
            throw new Error(e);
        }
        byte[] ret = baos.toByteArray();
        assert(ret.length == GENERAL_PROGRESS_LENGTH_BEFORE_CHECKSUM);
        return ret;
    }

    /** Start the storage layer.
     * @return True if it should be scheduled immediately. If false, the storage layer will 
     * callback into the fetcher later.
     */
    public boolean start() {
        if(segmentsToTryDecode != null) {
            List<SplitFileFetcherSegmentStorage> brokenSegments;
            synchronized(SplitFileFetcherStorage.this) {
                brokenSegments = segmentsToTryDecode;
                segmentsToTryDecode = null;
            }
            if(brokenSegments != null) {
                for(SplitFileFetcherSegmentStorage segment : brokenSegments) {
                    segment.tryStartDecode();
                }
            }
        }
        if(keyListener.needsKeys()) {
            try {
                this.jobRunner.queue(new PersistentJob() {

                    @Override
                    public boolean run(ClientContext context) {
                        System.out.println("Regenerating filters for "+SplitFileFetcherStorage.this);
                        Logger.error(this, "Regenerating filters for "+SplitFileFetcherStorage.this);
                        KeySalter salt = fetcher.getSalter();
                        for(int i=0;i<segments.length;i++) {
                            SplitFileFetcherSegmentStorage segment = segments[i];
                            try {
                                try {
                                    SplitFileSegmentKeys keys = segment.readSegmentKeys();
                                    for(int j=0;j<keys.totalKeys();j++) {
                                        keyListener.addKey(keys.getKey(j, null, false).getNodeKey(false), i, salt);
                                    }
                                } catch (IOException e) {
                                    failOnDiskError(e);
                                    return false;
                                }
                            } catch (ChecksumFailedException e) {
                                failOnDiskError(e);
                                return false;
                            }
                        }
                        keyListener.addedAllKeys();
                        try {
                            keyListener.initialWriteSegmentBloomFilters(offsetSegmentBloomFilters);
                            keyListener.innerWriteMainBloomFilter(offsetMainBloomFilter);
                        } catch (IOException e) {
                            if(persistent)
                                failOnDiskError(e);
                        }
                        fetcher.restartedAfterDataCorruption();
                        Logger.warning(this, "Finished regenerating filters for "+SplitFileFetcherStorage.this);
                        System.out.println("Finished regenerating filters for "+SplitFileFetcherStorage.this);
                        return false;
                    }
                    
                }, NativeThread.LOW_PRIORITY+1);
            } catch (PersistenceDisabledException e) {
                // Ignore.
            }
            return false;
        }
        return true;
    }
    
    /** Thrown when the file being loaded appears not to be a stored splitfile */
    static class StorageFormatException extends Exception {

        public StorageFormatException(String message) {
            super(message);
        }
        
    }
    
    OutputStream checksumOutputStream(OutputStream os) {
        return checksumChecker.checksumWriter(os);
    }

    private byte[] encodeBasicSettings() {
        return appendChecksum(innerEncodeBasicSettings());
    }
    
    /** Encode the basic settings (number of blocks etc) to a byte array */
    private byte[] innerEncodeBasicSettings() {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        try {
            dos.writeShort(splitfileType);
            dos.writeByte(this.splitfileSingleCryptoAlgorithm);
            dos.writeBoolean(this.splitfileSingleCryptoKey != null);
            if(this.splitfileSingleCryptoKey != null) {
                assert(splitfileSingleCryptoKey.length == 32);
                dos.write(splitfileSingleCryptoKey);
            }
            dos.writeLong(this.finalLength);
            clientMetadata.writeTo(dos);
            dos.writeInt(decompressors.size()); // FIXME enforce size limits???
            for(COMPRESSOR_TYPE c : decompressors)
                dos.writeShort(c.metadataID);
            dos.writeLong(offsetKeyList);
            dos.writeLong(offsetSegmentStatus);
            dos.writeLong(offsetGeneralProgress);
            dos.writeLong(offsetMainBloomFilter);
            dos.writeLong(offsetSegmentBloomFilters);
            dos.writeLong(offsetOriginalMetadata);
            dos.writeLong(offsetOriginalDetails);
            dos.writeLong(offsetBasicSettings);
            dos.writeInt(finalMinCompatMode.ordinal());
            dos.writeInt(segments.length);
            for(SplitFileFetcherSegmentStorage segment : segments) {
                segment.writeFixedMetadata(dos);
            }
            if(this.crossSegments == null)
                dos.writeInt(0);
            else {
                // FIXME
//                dos.writeInt(crossSegments.length);
//                for(SplitFileFetcherCrossSegmentStorage segment : crossSegments) {
//                    segment.writeFixedMetadata(dos);
//                }
            }
            keyListener.writeStaticSettings(dos);
        } catch (IOException e) {
            throw new Error(e); // Impossible
        }
        return baos.toByteArray();
    }

    /** Write details needed to restart the download from scratch, and to identify whether it is
     * useful to do so. */
    private byte[] encodeAndChecksumOriginalDetails(FreenetURI thisKey, FreenetURI origKey,
            byte[] clientDetails, boolean isFinalFetch) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        dos.writeUTF(thisKey.toASCIIString());
        dos.writeUTF(origKey.toASCIIString());
        dos.writeBoolean(isFinalFetch);
        dos.writeInt(clientDetails.length);
        dos.write(clientDetails);
        dos.writeInt(maxRetries);
        dos.writeInt(cooldownTries);
        dos.writeLong(cooldownLength);
        return checksumChecker.appendChecksum(baos.toByteArray());
    }

    /** FIXME not used yet */
    private void allocateCrossDataBlock(SplitFileFetcherCrossSegmentStorage segment, Random random) {
        int x = 0;
        for(int i=0;i<10;i++) {
            x = random.nextInt(segments.length);
            SplitFileFetcherSegmentStorage seg = segments[x];
            int blockNum = seg.allocateCrossDataBlock(segment, random);
            if(blockNum >= 0) {
                segment.addDataBlock(seg, blockNum);
                return;
            }
        }
        for(int i=0;i<segments.length;i++) {
            x++;
            if(x == segments.length) x = 0;
            SplitFileFetcherSegmentStorage seg = segments[x];
            int blockNum = seg.allocateCrossDataBlock(segment, random);
            if(blockNum >= 0) {
                segment.addDataBlock(seg, blockNum);
                return;
            }
        }
        throw new IllegalStateException("Unable to allocate cross data block!");
    }

    /** FIXME not used yet */
    private void allocateCrossCheckBlock(SplitFileFetcherCrossSegmentStorage segment, Random random) {
        int x = 0;
        for(int i=0;i<10;i++) {
            x = random.nextInt(segments.length);
            SplitFileFetcherSegmentStorage seg = segments[x];
            int blockNum = seg.allocateCrossCheckBlock(segment, random);
            if(blockNum >= 0) {
                segment.addDataBlock(seg, blockNum);
                return;
            }
        }
        for(int i=0;i<segments.length;i++) {
            x++;
            if(x == segments.length) x = 0;
            SplitFileFetcherSegmentStorage seg = segments[x];
            int blockNum = seg.allocateCrossCheckBlock(segment, random);
            if(blockNum >= 0) {
                segment.addDataBlock(seg, blockNum);
                return;
            }
        }
        throw new IllegalStateException("Unable to allocate cross data block!");
    }


    
    public short getPriorityClass() {
        return fetcher.getPriorityClass();
    }

    /** A segment successfully completed. 
     * @throws PersistenceDisabledException */
    public void finishedSuccess(SplitFileFetcherSegmentStorage segment) {
        if(allSucceeded()) {
            if(logMINOR) Logger.minor(this, "finishedSuccess on "+this+" from "+segment+" for "+fetcher, new Exception("debug"));
            jobRunner.queueLowOrDrop(new PersistentJob() {

                @Override
                public boolean run(ClientContext context) {
                    fetcher.onSuccess();
                    return true;
                }
                
            });
        }
        // FIXME truncation optimisation: if the file isn't compressed and doesn't need filtering, 
        // we can just truncate it to get rid of the metadata etc.
        // If it is compressed or needs filtering, we may need to keep the metadata for now...
    }

    private boolean allSucceeded() {
        for(SplitFileFetcherSegmentStorage segment : segments) {
            if(!segment.hasSucceeded()) return false;
        }
        return true;
    }

    public StreamGenerator streamGenerator() {
        // FIXME truncation optimisation.
        return new StreamGenerator() {

            @Override
            public void writeTo(OutputStream os, ClientContext context)
                    throws IOException {
                LockableRandomAccessThing.RAFLock lock = raf.lockOpen();
                try {
                    for(SplitFileFetcherSegmentStorage segment : segments) {
                        segment.writeToInner(os);
                    }
                    os.close();
                } catch (Throwable t) {
                    Logger.error(this, "Failed to write stream: "+t, t);
                } finally {
                    lock.unlock();
                }
            }

            @Override
            public long size() {
                return finalLength;
            }
            
        };
    }

    static final long LAZY_WRITE_METADATA_DELAY = TimeUnit.MINUTES.toMillis(5);
    
    private final PersistentJob writeMetadataJob = new PersistentJob() {

        @Override
        public boolean run(ClientContext context) {
            try {
                if(hasFinished()) return false;
                RAFLock lock = raf.lockOpen();
                try {
                    for(SplitFileFetcherSegmentStorage segment : segments) {
                        segment.writeMetadata(false);
                    }
                    keyListener.maybeWriteMainBloomFilter(offsetMainBloomFilter);
                } finally {
                    lock.unlock();
                }
                return false;
            } catch (IOException e) {
                if(hasFinished()) return false;
                Logger.error(this, "Failed writing metadata for "+SplitFileFetcherStorage.this+": "+e, e);
                return false;
            }
        }
        
    };

    public void lazyWriteMetadata() {
        if(!persistent) return;
        if(LAZY_WRITE_METADATA_DELAY != 0) {
            ticker.queueTimedJob(new Runnable() {

                @Override
                public void run() {
                    jobRunner.queueLowOrDrop(writeMetadataJob);
                }
                
            }, "Write metadata for splitfile", 
                    LAZY_WRITE_METADATA_DELAY, false, true);
        } else { // Must still be off-thread, multiple segments, possible locking issues...
            jobRunner.queueLowOrDrop(writeMetadataJob);
        }
    }

    public void finishedFetcher() {
        synchronized(this) {
            if(finishedFetcher) {
                if(logMINOR) Logger.minor(this, "Already finishedFetcher");
                return;
            }
            finishedFetcher = true;
            if(!finishedEncoding) return;
        }
        jobRunner.queueLowOrDrop(new PersistentJob() {
            
            @Override
            public boolean run(ClientContext context) {
                close();
                return true;
            }
            
        });
    }
    
    private void finishedEncoding() {
        synchronized(this) {
            if(finishedEncoding) {
                if(logMINOR) Logger.minor(this, "Already finishedEncoding");
                return;
            }
            finishedEncoding = true;
            if(!finishedFetcher) return;
        }
        jobRunner.queueLowOrDrop(new PersistentJob() {
            
            @Override
            public boolean run(ClientContext context) {
                close();
                return true;
            }
            
        });
    }
    
    /** Shutdown and free resources. CONCURRENCY: Caller is responsible for making sure this is 
     * not called on a MemoryLimitedJob thread. */
    void close() {
        if(logMINOR) Logger.minor(this, "Finishing "+this+" for "+fetcher, new Exception("debug"));
        raf.close();
        raf.free();
        fetcher.onClosed();
    }
    
    void finishedEncoding(SplitFileFetcherSegmentStorage segment) {
        if(logMINOR) Logger.minor(this, "Successfully decoded "+segment+" for "+this+" for "+fetcher);
        if(!allFinished()) return;
        finishedEncoding();
    }
    
    private boolean allFinished() {
        for(SplitFileFetcherSegmentStorage segment : segments) {
            if(!segment.isFinished()) return false;
        }
        return true;
    }
    
    private boolean allDecodingOrFinished() {
        for(SplitFileFetcherSegmentStorage segment : segments) {
            if(!segment.isDecodingOrFinished()) return false;
        }
        return true;
    }

    /** Fail the request, off-thread. The callback will call cancel etc, so it won't immediately
     * shut down the storage.
     * @param e
     */
    public void fail(final FetchException e) {
        jobRunner.queueLowOrDrop(new PersistentJob() {
            
            @Override
            public boolean run(ClientContext context) {
                fetcher.fail(e);
                return true;
            }
                
        });
    }

    /** A segment ran out of retries. We have given up on that segment and therefore on the whole
     * splitfile.
     * @param segment The segment that failed.
     */
    public void failOnSegment(SplitFileFetcherSegmentStorage segment) {
        fail(new FetchException(FetchException.SPLITFILE_ERROR, errors));
    }

    public void failOnDiskError(final IOException e) {
        Logger.error(this, "Failing on disk error: "+e, e);
        jobRunner.queueLowOrDrop(new PersistentJob() {

            @Override
            public boolean run(ClientContext context) {
                fetcher.failOnDiskError(e);
                return true;
            }
            
        });
    }

    public void failOnDiskError(final ChecksumFailedException e) {
        Logger.error(this, "Failing on unrecoverable corrupt data: "+e, e);
        jobRunner.queueLowOrDrop(new PersistentJob() {

            @Override
            public boolean run(ClientContext context) {
                fetcher.failOnDiskError(e);
                return true;
            }
            
        });
    }

    public long countUnfetchedKeys() {
        long total = 0;
        for(SplitFileFetcherSegmentStorage segment : segments)
            total += segment.countUnfetchedKeys();
        return total;
    }

    public Key[] listUnfetchedKeys() {
        try {
            ArrayList<Key> keys = new ArrayList<Key>();
            for(SplitFileFetcherSegmentStorage segment : segments)
                segment.getUnfetchedKeys(keys);
            return keys.toArray(new Key[keys.size()]);
        } catch (IOException e) {
            failOnDiskError(e);
            return new Key[0];
        }
    }
    
    public long countSendableKeys() {
        long now = System.currentTimeMillis();
        long total = 0;
        for(SplitFileFetcherSegmentStorage segment : segments)
            total += segment.countSendableKeys(now, maxRetries);
        return total;
    }

    final class MyKey implements SendableRequestItem, SendableRequestItemKey {

        public MyKey(int n, int segNo, SplitFileFetcherStorage storage) {
            this.blockNumber = n;
            this.segmentNumber = segNo;
            this.get = storage;
            hashCode = initialHashCode();
        }

        final int blockNumber;
        final int segmentNumber;
        final SplitFileFetcherStorage get;
        final int hashCode;

        @Override
        public void dump() {
            // Ignore.
        }

        @Override
        public SendableRequestItemKey getKey() {
            return this;
        }
        
        @Override
        public boolean equals(Object o) {
            if(this == o) return true;
            if(!(o instanceof MyKey)) return false;
            MyKey k = (MyKey)o;
            return k.blockNumber == blockNumber && k.segmentNumber == segmentNumber && 
                k.get == get;
        }
        
        public int hashCode() {
            return hashCode;
        }

        private int initialHashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + blockNumber;
            result = prime * result + ((get == null) ? 0 : get.hashCode());
            result = prime * result + segmentNumber;
            return result;
        }

    }

    /** Choose a random key which can be fetched at the moment. Must not update any persistent data;
     * it's okay to update caches and other stuff that isn't stored to disk. If we fail etc we 
     * should do it off-thread.
     * @return The block number to be fetched, as an integer.
     */
    public MyKey chooseRandomKey(KeysFetchingLocally keys) {
        synchronized(this) {
            if(finishedFetcher) return null;
        }
        // Generally segments are fairly well balanced, so we can usually pick a random segment 
        // then a random key from it.
        int segNo = random.nextInt(segments.length);
        SplitFileFetcherSegmentStorage segment = segments[segNo];
        int ret = segment.chooseRandomKey(keys);
        if(ret != -1) return new MyKey(ret, segNo, this);
        // Looks like we are close to completion ...
        // FIXME OPT SCALABILITY This is O(n) memory and time with the number of segments. For a 
        // 4GB splitfile, there would be 512 segments. The alternative is to keep a similar 
        // structure up to date, which also requires changes to the segment code. Not urgent until
        // very big splitfiles are common.
        // FIXME OPT SCALABILITY A simpler option might be just to have one SplitFileFetcherGet per
        // segment, like the old code.
        boolean[] tried = new boolean[segments.length];
        tried[segNo] = true;
        for(int count=1; count<segments.length; count++) {
            while(tried[segNo = random.nextInt(segments.length)]);
            tried[segNo] = true;
            segment = segments[segNo];
            ret = segment.chooseRandomKey(keys);
            if(ret != -1) return new MyKey(ret, segNo, this);
        }
        return null;
    }

    /** Cancel the download, stop all FEC decodes, and call close() off-thread when done. */
    void cancel() {
        synchronized(this) {
            cancelled = true;
            finishedFetcher = true;
        }
        for(SplitFileFetcherSegmentStorage segment : segments)
            segment.cancel();
    }

    /** Local only is true and we've finished checking the datastore. If all segments are not 
     * already finished/decoding, we need to fail with DNF. If the segments fail to decode due to
     * data corruption, we will retry as usual. */
    public void finishedCheckingDatastoreOnLocalRequest() {
        if(hasFinished()) return; // Don't need to do anything.
        if(allDecodingOrFinished()) {
            // All segments are decoding.
            // If they succeed, we complete.
            // If they fail to decode, they will cause the getter to retry, and re-scan the 
            // datastore for the corrupted keys.
            return;
        }
        if(hasFinished()) return; // Don't need to do anything.
        // Some segments still need data.
        // They're not going to get it.
        jobRunner.queueLowOrDrop(new PersistentJob() {

            @Override
            public boolean run(ClientContext context) {
                fetcher.failCheckedDatastoreOnly();
                return true;
            }
            
        });
    }

    synchronized boolean hasFinished() {
        return cancelled || finishedFetcher;
    }

    public void onFailure(MyKey key, FetchException fe) {
        synchronized(this) {
            if(cancelled || finishedFetcher) return;
        }
        errors.inc(fe.getMode());
        SplitFileFetcherSegmentStorage segment = segments[key.segmentNumber];
        segment.onNonFatalFailure(key.blockNumber);
    }

    public ClientKey getKey(MyKey key) {
        try {
            return segments[key.segmentNumber].getSegmentKeys().getKey(key.blockNumber, null, false);
        } catch (IOException e) {
            this.failOnDiskError(e);
            return null;
        }
    }

    public int maxRetries() {
        return maxRetries;
    }

    public void failedBlock() {
        jobRunner.queueLowOrDrop(new PersistentJob() {

            @Override
            public boolean run(ClientContext context) {
                fetcher.onFailedBlock();
                return false;
            }
            
        });
    }

    public boolean lastBlockMightNotBePadded() {
        return (finalMinCompatMode == CompatibilityMode.COMPAT_UNKNOWN || 
                finalMinCompatMode.ordinal() < CompatibilityMode.COMPAT_1416.ordinal());
    }

    public void restartedAfterDataCorruption(boolean wasCorrupt) {
        jobRunner.queueLowOrDrop(new PersistentJob() {

            @Override
            public boolean run(ClientContext context) {
                maybeClearCooldown();
                fetcher.restartedAfterDataCorruption();
                return false;
            }
            
        });
    }

    /** Separate lock for cooldown operations, which must be serialized. Must be taken *BEFORE*
     * segment locks. */
    private final Object cooldownLock = new Object();

    /** Called when a segment goes into overall cooldown. */
    void increaseCooldown(SplitFileFetcherSegmentStorage splitFileFetcherSegmentStorage,
            final long cooldownTime) {
        // Risky locking-wise, so run as a separate job.
        jobRunner.queueLowOrDrop(new PersistentJob() {

            @Override
            public boolean run(ClientContext context) {
                long now = System.currentTimeMillis();
                synchronized(cooldownLock) {
                    if(cooldownTime < now) return false;
                    if(overallCooldownWakeupTime > now) return false; // Wait for it to wake up.
                    long wakeupTime = Long.MAX_VALUE;
                    for(SplitFileFetcherSegmentStorage segment : segments) {
                        long segmentTime = segment.getOverallCooldownTime();
                        if(segmentTime < now) return false;
                        wakeupTime = Math.min(segmentTime, wakeupTime);
                    }
                    overallCooldownWakeupTime = wakeupTime;
                }
                return false;
            }
            
        });
    }

    /** Called when a segment exits cooldown e.g. due to a request completing and becoming 
     * retryable. Must NOT be called with segment locks held. */
    public void maybeClearCooldown() {
        synchronized(cooldownLock) {
            if(overallCooldownWakeupTime == 0 || 
                    overallCooldownWakeupTime < System.currentTimeMillis()) return;
            overallCooldownWakeupTime = 0;
        }
        fetcher.clearCooldown();
    }

    public long getCooldownWakeupTime(long now) {
        synchronized(cooldownLock) {
            if(overallCooldownWakeupTime < now) overallCooldownWakeupTime = 0;
            return overallCooldownWakeupTime;
        }
    }
    
    // Operations with checksums and storage access.
    
    /** Append a CRC32 to a (short) byte[] */
    private byte[] appendChecksum(byte[] data) {
        return checksumChecker.appendChecksum(data);
    }
    
    void preadChecksummed(long fileOffset, byte[] buf, int offset, int length) throws IOException, ChecksumFailedException {
        byte[] checksumBuf = new byte[checksumLength];
        RAFLock lock = raf.lockOpen();
        try {
            raf.pread(fileOffset, buf, offset, length);
            raf.pread(fileOffset+length, checksumBuf, 0, checksumLength);
        } finally {
            lock.unlock();
        }
        if(!checksumChecker.checkChecksum(buf, offset, length, checksumBuf)) {
            Arrays.fill(buf, offset, offset+length, (byte)0);
            throw new ChecksumFailedException();
        }
    }

    /** Create an OutputStream that we can write formatted data to of a specific length. On 
     * close(), it checks that the length is as expected, computes the checksum, and writes the
     * data to the specified position in the file.
     * @param fileOffset The position in the file (raf) of the first byte.
     * @param length The length, including checksum, of the data to be written.
     * @return
     */
    OutputStream writeChecksummedTo(final long fileOffset, final int length) {
        final ByteArrayOutputStream baos = new ByteArrayOutputStream(length);
        OutputStream cos = checksumOutputStream(baos);
        return new FilterOutputStream(cos) {
            
            public void close() throws IOException {
                out.close();
                byte[] buf = baos.toByteArray();
                if(buf.length != length)
                    throw new IllegalStateException("Wrote wrong number of bytes: "+buf.length+" should be "+length);
                raf.pwrite(fileOffset, buf, 0, length);
            }
            
        };
    }

    RAFLock lockRAFOpen() throws IOException {
        return raf.lockOpen();
    }

    void writeBlock(SplitFileFetcherSegmentStorage segment, int slotNumber, byte[] data) 
    throws IOException {
        raf.pwrite(segment.blockOffset(slotNumber), data, 0, data.length);
    }

    byte[] readBlock(SplitFileFetcherSegmentStorage segment, int slotNumber) 
    throws IOException {
        long offset = segment.blockOffset(slotNumber);
        byte[] buf = new byte[CHKBlock.DATA_LENGTH];
        raf.pread(offset, buf, 0, buf.length);
        return buf;
    }

    /** Needed for resuming. */
    LockableRandomAccessThing getRAF() {
        return raf;
    }

    public synchronized void setHasCheckedStore() {
        hasCheckedDatastore = true;
        if(!persistent) return;
        this.jobRunner.queueLowOrDrop(new PersistentJob() {

            @Override
            public boolean run(ClientContext context) {
                byte[] generalProgress = appendChecksum(encodeGeneralProgress());
                try {
                    raf.pwrite(offsetGeneralProgress, generalProgress, 0, generalProgress.length);
                } catch (IOException e) {
                    failOnDiskError(e);
                }
                return false;
            }
            
        });
    }

    public synchronized boolean hasCheckedStore() {
        return hasCheckedDatastore;
    }

}