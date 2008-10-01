/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.Set;
import freenet.keys.BaseClientKey;
import freenet.keys.ClientCHK;
import freenet.keys.FreenetURI;
import freenet.client.ArchiveManager.ARCHIVE_TYPE;
import freenet.support.Fields;
import freenet.support.Logger;
import freenet.support.api.Bucket;
import freenet.support.api.BucketFactory;
import freenet.support.compress.Compressor.COMPRESSOR_TYPE;
import freenet.support.io.BucketTools;


/** Metadata parser/writer class. */
public class Metadata implements Cloneable {

	static final long FREENET_METADATA_MAGIC = 0xf053b2842d91482bL;
	static final int MAX_SPLITFILE_PARAMS_LENGTH = 32768;
	/** Soft limit, to avoid memory DoS */
	static final int MAX_SPLITFILE_BLOCKS = 1000*1000;
	
	// URI at which this Metadata has been/will be inserted.
	FreenetURI resolvedURI;
	
	// Actual parsed data
	
	// document type
	byte documentType;
	public static final byte SIMPLE_REDIRECT = 0;
	static final byte MULTI_LEVEL_METADATA = 1;
	static final byte SIMPLE_MANIFEST = 2;
	public static final byte ARCHIVE_MANIFEST = 3;
	public static final byte ARCHIVE_INTERNAL_REDIRECT = 4;
	
	// 2 bytes of flags
	/** Is a splitfile */
	boolean splitfile;
	/** Is a DBR */
	boolean dbr;
	/** No MIME type; on by default as not all doctypes have MIME */
	boolean noMIME = true;
	/** Compressed MIME type */
	boolean compressedMIME;
	/** Has extra client-metadata */
	boolean extraMetadata;
	/** Keys stored in full (otherwise assumed to be CHKs) */
	boolean fullKeys;
	static final short FLAGS_SPLITFILE = 1;
	static final short FLAGS_DBR = 2;
	static final short FLAGS_NO_MIME = 4;
	static final short FLAGS_COMPRESSED_MIME = 8;
	static final short FLAGS_EXTRA_METADATA = 16;
	static final short FLAGS_FULL_KEYS = 32;
//	static final short FLAGS_SPLIT_USE_LENGTHS = 64; FIXME not supported, reassign to something else if we need a new flag
	static final short FLAGS_COMPRESSED = 128;
	
	/** Container archive type 
	 * @see ARCHIVE_TYPE
	 */
	short archiveType;
	
	/** Compressed splitfile codec 
	 * @see COMPRESSOR_TYPE
	 */
	short compressionCodec = -1;
	
	/** The length of the splitfile */
	long dataLength;
	/** The decompressed length of the compressed data */
	long decompressedLength;
	
	/** The MIME type, as a string */
	String mimeType;
	
	/** The compressed MIME type - lookup index for the MIME types table.
	 * Must be between 0 and 32767.
	 */
	short compressedMIMEValue;
	boolean hasCompressedMIMEParams;
	short compressedMIMEParams;
	
	/** The simple redirect key */
	FreenetURI simpleRedirectKey;
	
	short splitfileAlgorithm;
	static public final short SPLITFILE_NONREDUNDANT = 0;
	static public final short SPLITFILE_ONION_STANDARD = 1;
	
	/** Splitfile parameters */
	byte[] splitfileParams;
	int splitfileBlocks;
	int splitfileCheckBlocks;
	ClientCHK[] splitfileDataKeys;
	ClientCHK[] splitfileCheckKeys;
	
	// Manifests
	/** Manifest entries by name */
	HashMap manifestEntries;
	
	/** ZIP internal redirect: name of file in ZIP */
	String nameInArchive;

	ClientMetadata clientMetadata;

	@Override
	public Object clone() {
		try {
			return super.clone();
		} catch (CloneNotSupportedException e) {
			throw new Error("Yes it is!");
		}
	}
	
	/** Parse a block of bytes into a Metadata structure.
	 * Constructor method because of need to catch impossible exceptions.
	 * @throws MetadataParseException If the metadata is invalid.
	 */
	public static Metadata construct(byte[] data) throws MetadataParseException {
		try {
			return new Metadata(data);
		} catch (IOException e) {
			MetadataParseException e1 = new MetadataParseException("Caught "+e);
			e1.initCause(e);
			throw e1;
		}
	}
	
	/**
	 * Parse a bucket of data into a Metadata structure.
	 * @throws MetadataParseException If the parsing failed because of invalid metadata.
	 * @throws IOException If we could not read the metadata from the bucket.
	 */
	public static Metadata construct(Bucket data) throws MetadataParseException, IOException {
		InputStream is = data.getInputStream();
		BufferedInputStream bis = new BufferedInputStream(is, 4096);
		Metadata m;
		try {
			DataInputStream dis = new DataInputStream(bis);
			m = new Metadata(dis, data.size());
		} finally {
			is.close();
		}
		return m;
	}
	
	/** Parse some metadata from a byte[]. 
	 * @throws IOException If the data is incomplete, or something wierd happens. 
	 * @throws MetadataParseException */
	private Metadata(byte[] data) throws IOException, MetadataParseException {
		this(new DataInputStream(new ByteArrayInputStream(data)), data.length);
	}

	/** Parse some metadata from a DataInputStream
	 * @throws IOException If an I/O error occurs, or the data is incomplete. */
	public Metadata(DataInputStream dis, long length) throws IOException, MetadataParseException {
		long magic = dis.readLong();
		if(magic != FREENET_METADATA_MAGIC)
			throw new MetadataParseException("Invalid magic "+magic);
		short version = dis.readShort();
		if(version != 0)
			throw new MetadataParseException("Unsupported version "+version);
		documentType = dis.readByte();
		if((documentType < 0) || (documentType > 5))
			throw new MetadataParseException("Unsupported document type: "+documentType);
		boolean logMINOR = Logger.shouldLog(Logger.MINOR, this);
		if(logMINOR) Logger.minor(this, "Document type: "+documentType);
		
		boolean compressed = false;
		if((documentType == SIMPLE_REDIRECT) || (documentType == MULTI_LEVEL_METADATA)
				|| (documentType == ARCHIVE_MANIFEST) || (documentType == ARCHIVE_INTERNAL_REDIRECT)) {
			short flags = dis.readShort();
			splitfile = (flags & FLAGS_SPLITFILE) == FLAGS_SPLITFILE;
			dbr = (flags & FLAGS_DBR) == FLAGS_DBR;
			noMIME = (flags & FLAGS_NO_MIME) == FLAGS_NO_MIME;
			compressedMIME = (flags & FLAGS_COMPRESSED_MIME) == FLAGS_COMPRESSED_MIME;
			extraMetadata = (flags & FLAGS_EXTRA_METADATA) == FLAGS_EXTRA_METADATA;
			fullKeys = (flags & FLAGS_FULL_KEYS) == FLAGS_FULL_KEYS;
			compressed = (flags & FLAGS_COMPRESSED) == FLAGS_COMPRESSED;
		}
		
		if(documentType == ARCHIVE_MANIFEST) {
			if(logMINOR) Logger.minor(this, "Archive manifest");
			archiveType = dis.readShort();
			if(!ARCHIVE_TYPE.isValidMetadataID(archiveType))
				throw new MetadataParseException("Unrecognized archive type "+archiveType);
		}

		if(splitfile) {
			if(logMINOR) Logger.minor(this, "Splitfile");
			dataLength = dis.readLong();
			if(dataLength < -1)
				throw new MetadataParseException("Invalid real content length "+dataLength);
			
			if(dataLength == -1) {
				if(splitfile)
					throw new MetadataParseException("Splitfile must have a real-length");
			}
		}
		
		if(compressed) {
			compressionCodec = dis.readShort();
			if(!COMPRESSOR_TYPE.isValidMetadataID(compressionCodec))
				throw new MetadataParseException("Unrecognized splitfile compression codec "+compressionCodec);
			
			decompressedLength = dis.readLong();
		}
		
		if(noMIME) {
			mimeType = null;
			if(logMINOR) Logger.minor(this, "noMIME enabled");
		} else {
			if(compressedMIME) {
				if(logMINOR) Logger.minor(this, "Compressed MIME");
				short x = dis.readShort();
				compressedMIMEValue = (short) (x & 32767); // chop off last bit
				hasCompressedMIMEParams = (compressedMIMEValue & 32768) == 32768;
				if(hasCompressedMIMEParams) {
					compressedMIMEParams = dis.readShort();
					if(compressedMIMEParams != 0) {
						throw new MetadataParseException("Unrecognized MIME params ID (not yet implemented)");
					}
				}
				mimeType = DefaultMIMETypes.byNumber(x);
			} else {
				// Read an actual raw MIME type
				byte l = dis.readByte();
				int len = l & 0xff; // positive
				byte[] toRead = new byte[len];
				dis.readFully(toRead);
				// Use UTF-8 for everything, for simplicity
				mimeType = new String(toRead, "UTF-8");
				if(logMINOR) Logger.minor(this, "Raw MIME");
			}
			if(logMINOR) Logger.minor(this, "MIME = "+mimeType);
		}
		
		if(dbr) {
			throw new MetadataParseException("Do not support DBRs pending decision on putting them in the key!");
		}
		
		if(extraMetadata) {
			int numberOfExtraFields = (dis.readShort()) & 0xffff;
			for(int i=0;i<numberOfExtraFields;i++) {
				short type = dis.readShort();
				int len = (dis.readByte() & 0xff);
				byte[] buf = new byte[len];
				dis.readFully(buf);
				Logger.normal(this, "Ignoring type "+type+" extra-client-metadata field of "+len+" bytes");
			}
			extraMetadata = false; // can't parse, can't write
		}
		
		clientMetadata = new ClientMetadata(mimeType);
		
		if((!splitfile) && ((documentType == SIMPLE_REDIRECT) || (documentType == ARCHIVE_MANIFEST))) {
			simpleRedirectKey = readKey(dis);
		} else if(splitfile) {
			splitfileAlgorithm = dis.readShort();
			if(!((splitfileAlgorithm == SPLITFILE_NONREDUNDANT) ||
					(splitfileAlgorithm == SPLITFILE_ONION_STANDARD)))
				throw new MetadataParseException("Unknown splitfile algorithm "+splitfileAlgorithm);
			
			if(splitfileAlgorithm == SPLITFILE_NONREDUNDANT)
				throw new MetadataParseException("Non-redundant splitfile invalid");
			
			int paramsLength = dis.readInt();
			if(paramsLength > MAX_SPLITFILE_PARAMS_LENGTH)
				throw new MetadataParseException("Too many bytes of splitfile parameters: "+paramsLength);
			
			if(paramsLength > 0) {
				splitfileParams = new byte[paramsLength];
				dis.readFully(splitfileParams);
			} else if(paramsLength < 0) {
				throw new MetadataParseException("Invalid splitfile params length: "+paramsLength);
			}
			
			splitfileBlocks = dis.readInt(); // 64TB file size limit :)
			if(splitfileBlocks < 0)
				throw new MetadataParseException("Invalid number of blocks: "+splitfileBlocks);
			if(splitfileBlocks > MAX_SPLITFILE_BLOCKS)
				throw new MetadataParseException("Too many splitfile blocks (soft limit to prevent memory DoS): "+splitfileBlocks);
			splitfileCheckBlocks = dis.readInt();
			if(splitfileCheckBlocks < 0)
				throw new MetadataParseException("Invalid number of check blocks: "+splitfileCheckBlocks);
			if(splitfileCheckBlocks > MAX_SPLITFILE_BLOCKS)
				throw new MetadataParseException("Too many splitfile check-blocks (soft limit to prevent memory DoS): "+splitfileCheckBlocks);
			
			splitfileDataKeys = new ClientCHK[splitfileBlocks];
			splitfileCheckKeys = new ClientCHK[splitfileCheckBlocks];
			for(int i=0;i<splitfileDataKeys.length;i++)
				if((splitfileDataKeys[i] = readCHK(dis)) == null)
					throw new MetadataParseException("Null data key "+i);
			for(int i=0;i<splitfileCheckKeys.length;i++)
				if((splitfileCheckKeys[i] = readCHK(dis)) == null)
					throw new MetadataParseException("Null check key: "+i);
		}
		
		if(documentType == SIMPLE_MANIFEST) {
			int manifestEntryCount = dis.readInt();
			if(manifestEntryCount < 0)
				throw new MetadataParseException("Invalid manifest entry count: "+manifestEntryCount);
			
			manifestEntries = new HashMap();
			
			// Parse the sub-Manifest.
			
			Logger.minor(this, "Simple manifest, "+manifestEntryCount+" entries");
			
			for(int i=0;i<manifestEntryCount;i++) {
				short nameLength = dis.readShort();
				byte[] buf = new byte[nameLength];
				dis.readFully(buf);
				String name = new String(buf, "UTF-8").intern();
				if(logMINOR) Logger.minor(this, "Entry "+i+" name "+name);
				short len = dis.readShort();
				if(len < 0)
					throw new MetadataParseException("Invalid manifest entry size: "+len);
				if(len > length)
					throw new MetadataParseException("Impossibly long manifest entry: "+len+" - metadata size "+length);
				byte[] data = new byte[len];
				dis.readFully(data);
				try {
					Metadata m = Metadata.construct(data);
					manifestEntries.put(name, m);
				} catch (Throwable t) {
					Logger.error(this, "Could not parse sub-manifest: "+t, t);
				}
			}
			if(logMINOR) Logger.minor(this, "End of manifest"); // Make it easy to search for it!
		}
		
		if(documentType == ARCHIVE_INTERNAL_REDIRECT) {
			int len = dis.readShort();
			if(logMINOR) Logger.minor(this, "Reading zip internal redirect length "+len);
			byte[] buf = new byte[len];
			dis.readFully(buf);
			nameInArchive = new String(buf, "UTF-8");
			if(logMINOR) Logger.minor(this, "Archive internal redirect: "+nameInArchive+" ("+len+ ')');
		}
	}
	
	/**
	 * Create an empty Metadata object 
	 */
	private Metadata() {
		// Should be followed by addRedirectionManifest
	}
	
	/**
	 * Create a Metadata object and add data for redirection to it.
	 * 
	 * @param dir A map of names (string) to either files (same string) or
	 * directories (more HashMap's)
	 * @throws MalformedURLException One of the URI:s were malformed
	 */
	private void addRedirectionManifest(HashMap dir) throws MalformedURLException {
		// Simple manifest - contains actual redirects.
		// Not zip manifest, which is basically a redirect.
		documentType = SIMPLE_MANIFEST;
		noMIME = true;
		//mimeType = null;
		//clientMetadata = new ClientMetadata(null);
		manifestEntries = new HashMap();
		int count = 0;
		for (Iterator i = dir.entrySet().iterator(); i.hasNext();) {
			Map.Entry entry = (Map.Entry) i.next();
			String key = ((String) entry.getKey()).intern();
			count++;
			Object o = entry.getValue();
			Metadata target;
			if(o instanceof String) {
				// External redirect
				FreenetURI uri = new FreenetURI((String)o);
				target = new Metadata(SIMPLE_REDIRECT, uri, null);
			} else if(o instanceof HashMap) {
				target = new Metadata();
				target.addRedirectionManifest((HashMap)o);
			} else throw new IllegalArgumentException("Not String nor HashMap: "+o);
			manifestEntries.put(key, target);
		}
	}
	
	/**
	 * Create a Metadata object and add data for redirection to it.
	 * 
	 * @param dir A map of names (string) to either files (same string) or
	 * directories (more HashMap's)
	 * @throws MalformedURLException One of the URI:s were malformed
	 */
	public static Metadata mkRedirectionManifest(HashMap dir) throws MalformedURLException {
		Metadata ret = new Metadata();
		ret.addRedirectionManifest(dir);
		return ret;
	}

	/**
	 * Create a Metadata object and add manifest entries from the given map.
	 * The map can contain either string -> Metadata, or string -> map, the latter
	 * indicating subdirs. 
	 */
	public static Metadata mkRedirectionManifestWithMetadata(HashMap dir) {
		Metadata ret = new Metadata();
		ret.addRedirectionManifestWithMetadata(dir);
		return ret;
	}
	
	private void addRedirectionManifestWithMetadata(HashMap dir) {
		// Simple manifest - contains actual redirects.
		// Not zip manifest, which is basically a redirect.
		documentType = SIMPLE_MANIFEST;
		noMIME = true;
		//mimeType = null;
		//clientMetadata = new ClientMetadata(null);
		manifestEntries = new HashMap();
		int count = 0;
		for(Iterator i = dir.keySet().iterator();i.hasNext();) {
			String key = ((String) i.next()).intern();
			if(key.indexOf('/') != -1)
				throw new IllegalArgumentException("Slashes in simple redirect manifest filenames! (slashes denote sub-manifests): "+key);
			count++;
			Object o = dir.get(key);
			if(o instanceof Metadata) {
				Metadata data = (Metadata) dir.get(key);
				if(data == null)
					throw new NullPointerException();
				manifestEntries.put(key, data);
			} else if(o instanceof HashMap) {
				HashMap hm = (HashMap)o;
				Metadata subMap = mkRedirectionManifestWithMetadata(hm);
				manifestEntries.put(key, subMap);
			}
		}
	}

	/**
	 * Create a Metadata object for an archive which does not have its own
	 * metadata.
	 * @param dir A map of names (string) to either files (same string) or
	 * directories (more HashMap's)
	 */
	Metadata(HashMap dir, String prefix) {
		// Simple manifest - contains actual redirects.
		// Not zip manifest, which is basically a redirect.
		documentType = SIMPLE_MANIFEST;
		noMIME = true;
		mimeType = null;
		clientMetadata = new ClientMetadata(null);
		manifestEntries = new HashMap();
		int count = 0;
		for(Iterator i = dir.keySet().iterator();i.hasNext();) {
			String key = ((String) i.next()).intern();
			count++;
			Object o = dir.get(key);
			Metadata target;
			if(o instanceof String) {
				// Zip internal redirect
				target = new Metadata(ARCHIVE_INTERNAL_REDIRECT, prefix+key, new ClientMetadata(DefaultMIMETypes.guessMIMEType(key, false)));
			} else if(o instanceof HashMap) {
				target = new Metadata((HashMap)o, prefix+key+"/");
			} else throw new IllegalArgumentException("Not String nor HashMap: "+o);
			manifestEntries.put(key, target);
		}
	}

	/**
	 * Create a really simple Metadata object.
	 * @param docType The document type. Must be something that takes a single argument.
	 * At the moment this means ZIP_INTERNAL_REDIRECT.
	 * @param arg The argument; in the case of ZIP_INTERNAL_REDIRECT, the filename in
	 * the archive to read from.
	 */
	public Metadata(byte docType, String arg, ClientMetadata cm) {
		if(docType == ARCHIVE_INTERNAL_REDIRECT) {
			documentType = docType;
			// Determine MIME type
			this.clientMetadata = cm;
			if(cm != null)
				this.setMIMEType(cm.getMIMEType());
			nameInArchive = arg;
		} else
			throw new IllegalArgumentException();
	}

	/**
	 * Create another kind of simple Metadata object (a redirect or similar object).
	 * @param docType The document type.
	 * @param uri The URI pointed to.
	 * @param cm The client metadata, if any.
	 */
	public Metadata(byte docType, FreenetURI uri, ClientMetadata cm) {
		if((docType == SIMPLE_REDIRECT) || (docType == ARCHIVE_MANIFEST)) {
			documentType = docType;
			archiveType = ARCHIVE_TYPE.getDefault().metadataID;
			clientMetadata = cm;
			if((cm != null) && !cm.isTrivial()) {
				setMIMEType(cm.getMIMEType());
			} else {
				setMIMEType(DefaultMIMETypes.DEFAULT_MIME_TYPE);
				noMIME = true;
			}
			simpleRedirectKey = uri;
			if(!(uri.getKeyType().equals("CHK") && !uri.hasMetaStrings()))
				fullKeys = true;
		} else
			throw new IllegalArgumentException();
	}

	public Metadata(short algo, ClientCHK[] dataURIs, ClientCHK[] checkURIs, int segmentSize, int checkSegmentSize, 
			ClientMetadata cm, long dataLength, short compressionAlgo, long decompressedLength, boolean isMetadata, boolean insertAsArchiveManifest) {
		if(isMetadata)
			documentType = MULTI_LEVEL_METADATA;
		else {
			if(insertAsArchiveManifest)
				documentType = ARCHIVE_MANIFEST;
			else documentType = SIMPLE_REDIRECT;
		}
		splitfile = true;
		splitfileAlgorithm = algo;
		this.dataLength = dataLength;
		this.compressionCodec = compressionAlgo;
		splitfileBlocks = dataURIs.length;
		splitfileCheckBlocks = checkURIs.length;
		splitfileDataKeys = dataURIs;
		splitfileCheckKeys = checkURIs;
		clientMetadata = cm;
		this.decompressedLength = decompressedLength;
		if(cm != null)
			setMIMEType(cm.getMIMEType());
		else
			setMIMEType(DefaultMIMETypes.DEFAULT_MIME_TYPE);
		splitfileParams = Fields.intsToBytes(new int[] { segmentSize, checkSegmentSize } );
	}

	/**
	 * Set the MIME type to a string. Compresses it if possible for transit.
	 */
	private void setMIMEType(String type) {
		noMIME = false;
		short s = DefaultMIMETypes.byName(type);
		if(s >= 0) {
			compressedMIME = true;
			compressedMIMEValue = s;
		} else {
			compressedMIME = false;
		}
		mimeType = type;
	}

	/**
	 * Write the data to a byte array.
	 * @throws MetadataUnresolvedException 
	 */
	public byte[] writeToByteArray() throws MetadataUnresolvedException {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		DataOutputStream dos = new DataOutputStream(baos);
		try {
			writeTo(dos);
		} catch (IOException e) {
			throw new Error("Could not write to byte array: "+e, e);
		}
		return baos.toByteArray();
	}

	private ClientCHK readCHK(DataInputStream dis) throws IOException, MetadataParseException {
		if(fullKeys) {
			throw new MetadataParseException("fullKeys not supported on a splitfile");
		}
		return ClientCHK.readRawBinaryKey(dis);
	}
	
	/**
	 * Read a key using the current settings.
	 * @throws IOException 
	 * @throws MalformedURLException If the key could not be read due to an error in parsing the key.
	 * REDFLAG: May want to recover from these in future, hence the short length.
	 */
	private FreenetURI readKey(DataInputStream dis) throws IOException {
		// Read URL
		if(fullKeys) {
			return FreenetURI.readFullBinaryKeyWithLength(dis);
		} else {
			return ClientCHK.readRawBinaryKey(dis).getURI();
		}
	}

	/**
	 * Write a key using the current settings.
	 * @throws IOException 
	 * @throws MalformedURLException If an error in the key itself prevented it from being written.
	 */
	private void writeKey(DataOutputStream dos, FreenetURI freenetURI) throws IOException {
		if(fullKeys) {
			freenetURI.writeFullBinaryKeyWithLength(dos);
		} else {
			String[] meta = freenetURI.getAllMetaStrings();
			if((meta != null) && (meta.length > 0))
				throw new MalformedURLException("Not a plain CHK");
			BaseClientKey key = BaseClientKey.getBaseKey(freenetURI);
			if(key instanceof ClientCHK) {
				((ClientCHK)key).writeRawBinaryKey(dos);
			} else throw new IllegalArgumentException("Full keys must be enabled to write non-CHKs");
		}
	}
	
	private void writeCHK(DataOutputStream dos, ClientCHK chk) throws IOException {
		if(fullKeys) {
			throw new UnsupportedOperationException("Full keys not supported on splitfiles");
		} else {
			chk.writeRawBinaryKey(dos);
		}
	}
	
	/** Is a manifest? */
	public boolean isSimpleManifest() {
		return documentType == SIMPLE_MANIFEST;
	}

	/**
	 * Get the sub-document in a manifest file with the given name.
	 * @throws MetadataParseException 
	 */
	public Metadata getDocument(String name) {
		return (Metadata) manifestEntries.get(name);
	}

	/**
	 * The default document is the one which has an empty name.
	 * @throws MetadataParseException 
	 */
	public Metadata getDefaultDocument() throws MetadataParseException {
		return getDocument("");
	}
	
	/**
     * Get all documents in the manifest (ignores default doc).
     * @throws MetadataParseException
     */
    public HashMap<String, Metadata> getDocuments() {
    	HashMap<String, Metadata> docs = new HashMap<String, Metadata>();
        Set s = manifestEntries.keySet();
        Iterator i = s.iterator();
        while (i.hasNext()) {
        	String st = (String) i.next();
        	if (st.length()>1)
        		docs.put(st, (Metadata) manifestEntries.get(st));
        }
        return docs;
    }

	/**
	 * Does the metadata point to a single URI?
	 */
	public boolean isSingleFileRedirect() {
		return (((!splitfile) &&
				(documentType == SIMPLE_REDIRECT)) || (documentType == MULTI_LEVEL_METADATA) ||
				(documentType == ARCHIVE_MANIFEST));
	}

	/**
	 * Return the single target of this URI.
	 */
	public FreenetURI getSingleTarget() {
		return simpleRedirectKey;
	}

	/**
	 * Is this a ZIP manifest?
	 */
	public boolean isArchiveManifest() {
		return documentType == ARCHIVE_MANIFEST;
	}

	/**
	 * Is this a ZIP internal redirect?
	 * @return
	 */
	public boolean isArchiveInternalRedirect() {
		return documentType == ARCHIVE_INTERNAL_REDIRECT;
	}

	/**
	 * Return the name of the document referred to in the archive,
	 * if this is a zip internal redirect.
	 */
	public String getZIPInternalName() {
		return nameInArchive;
	}

	/**
	 * Return the client metadata (MIME type etc).
	 */
	public ClientMetadata getClientMetadata() {
		return clientMetadata;
	}

	/** Is this a splitfile manifest? */
	public boolean isSplitfile() {
		return splitfile;
	}

	/** Is this a simple splitfile? */
	public boolean isSimpleSplitfile() {
		return splitfile && (documentType == SIMPLE_REDIRECT);
	}

	/** Is multi-level/indirect metadata? */
	public boolean isMultiLevelMetadata() {
		return documentType == MULTI_LEVEL_METADATA;
	}

	/** What kind of archive is it? */
	public short getArchiveType() {
		return archiveType;
	}

	/** Change the document type to a simple redirect. Used by the archive code
	 * to fetch split ZIP manifests.
	 */
	public void setSimpleRedirect() {
		documentType = SIMPLE_REDIRECT;
	}

	/** Write the metadata as binary. 
	 * @throws IOException If an I/O error occurred while writing the data. 
	 * @throws MetadataUnresolvedException */
	public void writeTo(DataOutputStream dos) throws IOException, MetadataUnresolvedException {
		dos.writeLong(FREENET_METADATA_MAGIC);
		dos.writeShort(0); // version
		dos.writeByte(documentType);
		if((documentType == SIMPLE_REDIRECT) || (documentType == MULTI_LEVEL_METADATA)
				|| (documentType == ARCHIVE_MANIFEST) || (documentType == ARCHIVE_INTERNAL_REDIRECT)) {
			short flags = 0;
			if(splitfile) flags |= FLAGS_SPLITFILE;
			if(dbr) flags |= FLAGS_DBR;
			if(noMIME) flags |= FLAGS_NO_MIME;
			if(compressedMIME) flags |= FLAGS_COMPRESSED_MIME;
			if(extraMetadata) flags |= FLAGS_EXTRA_METADATA;
			if(fullKeys) flags |= FLAGS_FULL_KEYS;
			if(compressionCodec >= 0) flags |= FLAGS_COMPRESSED;
			dos.writeShort(flags);
		}
		
		if(documentType == ARCHIVE_MANIFEST) {
			dos.writeShort(archiveType);
		}
		
		if(splitfile) {
			dos.writeLong(dataLength);
		}
		
		if(compressionCodec >= 0) {
			dos.writeShort(compressionCodec);
			dos.writeLong(decompressedLength);
		}
		
		if(!noMIME) {
			if(compressedMIME) {
				int x = compressedMIMEValue;
				if(hasCompressedMIMEParams) x |= 32768;
				dos.writeShort((short)x);
				if(hasCompressedMIMEParams)
					dos.writeShort(compressedMIMEParams);
			} else {
				byte[] data = mimeType.getBytes("UTF-8");
				if(data.length > 255) throw new Error("MIME type too long: "+data.length+" bytes: "+mimeType);
				dos.writeByte((byte)data.length);
				dos.write(data);
			}
		}
		
		if(dbr)
			throw new UnsupportedOperationException("No DBR support yet");
		
		if(extraMetadata)
			throw new UnsupportedOperationException("No extra metadata support yet");
		
		if((!splitfile) && ((documentType == SIMPLE_REDIRECT) || (documentType == ARCHIVE_MANIFEST))) {
			writeKey(dos, simpleRedirectKey);
		} else if(splitfile) {
			dos.writeShort(splitfileAlgorithm);
			if(splitfileParams != null) {
				dos.writeInt(splitfileParams.length);
				dos.write(splitfileParams);
			} else
				dos.writeInt(0);
			
			dos.writeInt(splitfileBlocks);
			dos.writeInt(splitfileCheckBlocks);
			for(int i=0;i<splitfileBlocks;i++)
				writeCHK(dos, splitfileDataKeys[i]);
			for(int i=0;i<splitfileCheckBlocks;i++)
				writeCHK(dos, splitfileCheckKeys[i]);
		}
		
		if(documentType == SIMPLE_MANIFEST) {
			dos.writeInt(manifestEntries.size());
			boolean kill = false;
			LinkedList unresolvedMetadata = null;
			for(Iterator i=manifestEntries.keySet().iterator();i.hasNext();) {
				String name = (String) i.next();
				byte[] nameData = name.getBytes("UTF-8");
				if(nameData.length > Short.MAX_VALUE) throw new IllegalArgumentException("Manifest name too long");
				dos.writeShort(nameData.length);
				dos.write(nameData);
				Metadata meta = (Metadata) manifestEntries.get(name);
				try {
					byte[] data = meta.writeToByteArray();
					if(data.length > Short.MAX_VALUE) {
						FreenetURI uri = meta.resolvedURI;
						if(uri != null) {
							meta = new Metadata(SIMPLE_REDIRECT, uri, null);
							data = meta.writeToByteArray();
						} else {
							kill = true;
							if(unresolvedMetadata == null)
								unresolvedMetadata = new LinkedList();
							unresolvedMetadata.addLast(meta);
						}
					}
					dos.writeShort(data.length);
					dos.write(data);
				} catch (MetadataUnresolvedException e) {
					Metadata[] m = e.mustResolve;
					if(unresolvedMetadata == null)
						unresolvedMetadata = new LinkedList();
					for(int j=0;j<m.length;j++)
						unresolvedMetadata.addFirst(m[j]);
					kill = true;
				}
			}
			if(kill) {
				Metadata[] meta = 
					(Metadata[]) unresolvedMetadata.toArray(new Metadata[unresolvedMetadata.size()]);
				throw new MetadataUnresolvedException(meta, "Manifest data too long and not resolved");
			}
		}
		
		if(documentType == ARCHIVE_INTERNAL_REDIRECT) {
			byte[] data = nameInArchive.getBytes("UTF-8");
			if(data.length > Short.MAX_VALUE) throw new IllegalArgumentException("Zip internal redirect name too long");
			dos.writeShort(data.length);
			dos.write(data);
		}
	}

	/**
	 * Get the splitfile type.
	 */
	public short getSplitfileType() {
		return splitfileAlgorithm;
	}

	public ClientCHK[] getSplitfileDataKeys() {
		return splitfileDataKeys;
	}
	
	public ClientCHK[] getSplitfileCheckKeys() {
		return splitfileCheckKeys;
	}

	public boolean isCompressed() {
		return compressionCodec >= 0;
	}

	public short getCompressionCodec() {
		return compressionCodec;
	}

	public long dataLength() {
		return dataLength;
	}

	public byte[] splitfileParams() {
		return splitfileParams;
	}

	public long uncompressedDataLength() {
		return this.decompressedLength;
	}
	
	public FreenetURI getResolvedURI() {
		return resolvedURI;
	}
	
	public void resolve(FreenetURI uri) {
		this.resolvedURI = uri;
	}

	public Bucket toBucket(BucketFactory bf) throws MetadataUnresolvedException, IOException {
		byte[] buf = writeToByteArray();
		return BucketTools.makeImmutableBucket(bf, buf);
	}

	public boolean isResolved() {
		return resolvedURI != null;
	}

	public void setArchiveManifest() {
		archiveType = ARCHIVE_TYPE.getArchiveType(clientMetadata.getMIMEType()).metadataID;
		clientMetadata.clear();
		documentType = ARCHIVE_MANIFEST;
	}

	public String getMIMEType() {
		if(clientMetadata == null) return null;
		return clientMetadata.getMIMEType();
	}
}
