/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.MalformedURLException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Vector;

import com.db4o.ObjectContainer;

import freenet.client.DefaultMIMETypes;
import freenet.client.FetchException;
import freenet.client.FetchResult;
import freenet.client.InsertException;
import freenet.client.async.ClientContext;
import freenet.client.async.ClientGetter;
import freenet.client.async.ClientRequester;
import freenet.client.async.ManifestElement;
import freenet.client.async.SimpleManifestPutter;
import freenet.keys.FreenetURI;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;
import freenet.support.api.Bucket;
import freenet.support.io.CannotCreateFromFieldSetException;
import freenet.support.io.FileBucket;
import freenet.support.io.PaddedEphemerallyEncryptedBucket;
import freenet.support.io.SerializableToFieldSetBucketUtil;

public class ClientPutDir extends ClientPutBase {

	private final HashMap<String, Object> manifestElements;
	private SimpleManifestPutter putter;
	private final String defaultName;
	private final long totalSize;
	private final int numberOfFiles;
	private static boolean logMINOR;
	private final boolean wasDiskPut;
	
	public ClientPutDir(FCPConnectionHandler handler, ClientPutDirMessage message, 
			HashMap<String, Object> manifestElements, boolean wasDiskPut, FCPServer server) throws IdentifierCollisionException, MalformedURLException {
		super(message.uri, message.identifier, message.verbosity, handler,
				message.priorityClass, message.persistenceType, message.clientToken, message.global,
				message.getCHKOnly, message.dontCompress, message.maxRetries, message.earlyEncode, server);
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		this.wasDiskPut = wasDiskPut;
		this.manifestElements = manifestElements;
		this.defaultName = message.defaultName;
		makePutter();
		if(putter != null) {
			numberOfFiles = putter.countFiles();
			totalSize = putter.totalSize();
		} else {
			numberOfFiles = -1;
			totalSize = -1;
		}
		if(logMINOR) Logger.minor(this, "Putting dir "+identifier+" : "+priorityClass);
	}

	/**
	*	Puts a disk dir
	 * @throws InsertException 
	*/
	public ClientPutDir(FCPClient client, FreenetURI uri, String identifier, int verbosity, short priorityClass, short persistenceType, String clientToken, boolean getCHKOnly, boolean dontCompress, int maxRetries, File dir, String defaultName, boolean allowUnreadableFiles, boolean global, boolean earlyEncode, FCPServer server) throws FileNotFoundException, IdentifierCollisionException, MalformedURLException {
		super(uri, identifier, verbosity , null, client, priorityClass, persistenceType, clientToken, global, getCHKOnly, dontCompress, maxRetries, earlyEncode, server);

		wasDiskPut = true;
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		this.manifestElements = makeDiskDirManifest(dir, "", allowUnreadableFiles);
		this.defaultName = defaultName;
		makePutter();
		if(putter != null) {
			numberOfFiles = putter.countFiles();
			totalSize = putter.totalSize();
		} else {
			numberOfFiles = -1;
			totalSize = -1;
		}
		if(logMINOR) Logger.minor(this, "Putting dir "+identifier+" : "+priorityClass);
	}

	void register(ObjectContainer container, boolean lazyResume, boolean noTags) throws IdentifierCollisionException {
		if(persistenceType != PERSIST_CONNECTION)
			client.register(this, false, container);
		if(persistenceType != PERSIST_CONNECTION && !noTags) {
			FCPMessage msg = persistentTagMessage(container);
			client.queueClientRequestMessage(msg, 0, container);
		}
	}
	
	private HashMap<String, Object> makeDiskDirManifest(File dir, String prefix, boolean allowUnreadableFiles) throws FileNotFoundException {

		HashMap<String, Object> map = new HashMap<String, Object>();
		File[] files = dir.listFiles();
		
		if(files == null)
			throw new IllegalArgumentException("No such directory");

		for (File f : files) {

			if (f.exists() && f.canRead()) {
				if(f.isFile()) {
					FileBucket bucket = new FileBucket(f, true, false, false, false, false);
					if(logMINOR)
						Logger.minor(this, "Add file : " + f.getAbsolutePath());
					
					map.put(f.getName(), new ManifestElement(f.getName(), prefix + f.getName(), bucket, DefaultMIMETypes.guessMIMEType(f.getName(), true), f.length()));
				} else if(f.isDirectory()) {
					if(logMINOR)
						Logger.minor(this, "Add dir : " + f.getAbsolutePath());
					
					map.put(f.getName(), makeDiskDirManifest(f, prefix + f.getName() + "/", allowUnreadableFiles));
				} else {
					if(!allowUnreadableFiles)
						throw new FileNotFoundException("Not a file and not a directory : " + f);
				}
			} else if (!allowUnreadableFiles)
				throw new FileNotFoundException("The file does not exist or is unreadable : " + f);
			
		}

		return map;
	}
	
	private void makePutter() {
		SimpleManifestPutter p;
			p = new SimpleManifestPutter(this, 
					manifestElements, priorityClass, uri, defaultName, ctx, getCHKOnly,
					lowLevelClient,
					earlyEncode);
		putter = p;
	}



	public ClientPutDir(SimpleFieldSet fs, FCPClient client, FCPServer server, ObjectContainer container) throws PersistenceParseException, IOException {
		super(fs, client, server);
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		SimpleFieldSet files = fs.subset("Files");
		defaultName = fs.get("DefaultName");
		String type = fs.get("PutDirType");
		if(type.equals("disk"))
			wasDiskPut = true;
		else
			wasDiskPut = false;
		// Flattened for disk, sort out afterwards
		int fileCount = 0;
		long size = 0;
		Vector<ManifestElement> v = new Vector<ManifestElement>();
		for(int i=0;;i++) {
			String num = Integer.toString(i);
			SimpleFieldSet subset = files.subset(num);
			if(subset == null) break;
			// Otherwise serialize
			String name = subset.get("Name");
			if(name == null)
				throw new PersistenceParseException("No Name on "+i);
			String contentTypeOverride = subset.get("Metadata.ContentType");
			String uploadFrom = subset.get("UploadFrom");
			Bucket data = null;
			if(logMINOR) Logger.minor(this, "Parsing "+i);
			if(logMINOR) Logger.minor(this, "UploadFrom="+uploadFrom);
			ManifestElement me;
			if((uploadFrom == null) || uploadFrom.equalsIgnoreCase("direct")) {
				long sz = Long.parseLong(subset.get("DataLength"));
				if(!finished) {
					try {
						data = SerializableToFieldSetBucketUtil.create(fs.subset("ReturnBucket"), server.core.random, server.core.persistentTempBucketFactory);
					} catch (CannotCreateFromFieldSetException e) {
						throw new PersistenceParseException("Could not read old bucket for "+identifier+" : "+e, e);
					}
				} else {
					data = null;
				}
				me = new ManifestElement(name, data, contentTypeOverride, sz);
				fileCount++;
			} else if(uploadFrom.equalsIgnoreCase("disk")) {
				long sz = Long.parseLong(subset.get("DataLength"));
				// Disk
				String f = subset.get("Filename");
				if(f == null)
					throw new PersistenceParseException("UploadFrom=disk but no name on "+i);
				File ff = new File(f);
				if(!(ff.exists() && ff.canRead())) {
					Logger.error(this, "File no longer exists, cancelling upload: "+ff);
					throw new IOException("File no longer exists, cancelling upload: "+ff);
				}
				data = new FileBucket(ff, true, false, false, false, false);
				me = new ManifestElement(name, data, contentTypeOverride, sz);
				fileCount++;
			} else if(uploadFrom.equalsIgnoreCase("redirect")) {
				FreenetURI targetURI = new FreenetURI(subset.get("TargetURI"));
				me = new ManifestElement(name, targetURI, contentTypeOverride);
			} else
				throw new PersistenceParseException("Don't know UploadFrom="+uploadFrom);
			v.add(me);
			if((data != null) && (data.size() > 0))
				size += data.size();
		}
		manifestElements = SimpleManifestPutter.unflatten(v);
		SimpleManifestPutter p = null;
			if(!finished)
				p = new SimpleManifestPutter(this, 
						manifestElements, priorityClass, uri, defaultName, ctx, getCHKOnly, 
						lowLevelClient,
						earlyEncode);
		putter = p;
		numberOfFiles = fileCount;
		totalSize = size;
		if(persistenceType != PERSIST_CONNECTION) {
			FCPMessage msg = persistentTagMessage(container);
			client.queueClientRequestMessage(msg, 0, container);
		}
	}

	@Override
	public void start(ObjectContainer container, ClientContext context) {
		if(finished) return;
		if(started) return;
		try {
			if(putter != null)
				putter.start(container, context);
			started = true;
			if(logMINOR) Logger.minor(this, "Started "+putter+" for "+this+" persistence="+persistenceType);
			if(persistenceType != PERSIST_CONNECTION && !finished) {
				FCPMessage msg = persistentTagMessage(container);
				client.queueClientRequestMessage(msg, 0, container);
			}
			if(persistenceType == PERSIST_FOREVER)
				container.store(this); // Update
		} catch (InsertException e) {
			started = true;
			onFailure(e, null, container);
		}
	}
	
	@Override
	public void onLostConnection(ObjectContainer container, ClientContext context) {
		if(persistenceType == PERSIST_CONNECTION)
			cancel(container, context);
		// otherwise ignore
	}
	
	@SuppressWarnings("unchecked")
	protected void freeData(ObjectContainer container) {
		freeData(manifestElements, container);
	}
	
	@SuppressWarnings("unchecked")
	private void freeData(HashMap<String, Object> manifestElements, ObjectContainer container) {
		if(persistenceType == PERSIST_FOREVER)
			container.activate(manifestElements, 1);
		Iterator i = manifestElements.values().iterator();
		while(i.hasNext()) {
			Object o = i.next();
			if(o instanceof HashMap)
				freeData((HashMap<String, Object>) o, container);
			else {
				ManifestElement e = (ManifestElement) o;
				e.freeData(container, persistenceType == PERSIST_FOREVER);
			}
		}
	}

	@Override
	protected ClientRequester getClientRequest() {
		return putter;
	}

	@Override
	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet fs = super.getFieldSet();
		// Translate manifestElements directly into a fieldset
		SimpleFieldSet files = new SimpleFieldSet(false);
		// Flatten the hierarchy, it can be reconstructed on restarting.
		// Storing it directly would be a PITA.
		ManifestElement[] elements = SimpleManifestPutter.flatten(manifestElements);
		fs.putSingle("DefaultName", defaultName);
		fs.putSingle("PutDirType", wasDiskPut ? "disk" : "complex");
		for(int i=0;i<elements.length;i++) {
			String num = Integer.toString(i);
			ManifestElement e = elements[i];
			String name = e.getName();
			String mimeOverride = e.getMimeTypeOverride();
			SimpleFieldSet subset = new SimpleFieldSet(false);
			subset.putSingle("Name", name);
			if(mimeOverride != null)
				subset.putSingle("Metadata.ContentType", mimeOverride);
			FreenetURI target = e.getTargetURI();
			if(target != null) {
				subset.putSingle("UploadFrom", "redirect");
				subset.putSingle("TargetURI", target.toString());
			} else {
				Bucket data = e.getData();
				// What to do with the bucket?
				// It is either a persistent encrypted bucket or a file bucket ...
				subset.putSingle("DataLength", Long.toString(e.getSize()));
				if(data instanceof FileBucket) {
					subset.putSingle("UploadFrom", "disk");
					subset.putSingle("Filename", ((FileBucket)data).getFile().getPath());
				} else if(finished) {
					subset.putSingle("UploadFrom", "direct");
				} else if(data instanceof PaddedEphemerallyEncryptedBucket) {
					subset.putSingle("UploadFrom", "direct");
					// the bucket is a persistent encrypted temp bucket
					bucketToFS(fs, "TempBucket", false, data);
				} else {
					throw new IllegalStateException("Don't know what to do with bucket: "+data);
				}
			}
			files.put(num, subset);
		}
		fs.put("Files", files);
		return fs;
	}

	@Override
	protected FCPMessage persistentTagMessage(ObjectContainer container) {
		if(persistenceType == PERSIST_FOREVER) {
			container.activate(publicURI, 5);
			container.activate(ctx, 1);
			container.activate(manifestElements, 5);
		}
		return new PersistentPutDir(identifier, publicURI, verbosity, priorityClass,
				persistenceType, global, defaultName, manifestElements, clientToken, started, ctx.maxInsertRetries, wasDiskPut);
	}

	@Override
	protected String getTypeName() {
		return "PUTDIR";
	}

	@Override
	public boolean hasSucceeded() {
		return succeeded;
	}

	public FreenetURI getFinalURI(ObjectContainer container) {
		if(persistenceType == PERSIST_FOREVER)
			container.activate(generatedURI, 5);
		return generatedURI;
	}

	public int getNumberOfFiles() {
		return numberOfFiles;
	}

	public long getTotalDataSize() {
		return totalSize;
	}

	@Override
	public boolean canRestart() {
		if(!finished) {
			Logger.minor(this, "Cannot restart because not finished for "+identifier);
			return false;
		}
		if(succeeded) {
			Logger.minor(this, "Cannot restart because succeeded for "+identifier);
			return false;
		}
		return true;
	}

	@Override
	public boolean restart(ObjectContainer container, ClientContext context) {
		if(!canRestart()) return false;
		setVarsRestart(container);
			makePutter();
		start(container, context);
		return true;
	}

	public void onFailure(FetchException e, ClientGetter state, ObjectContainer container) {}

	public void onSuccess(FetchResult result, ClientGetter state, ObjectContainer container) {}

	public void onRemoveEventProducer(ObjectContainer container) {
		// Do nothing, we called the removeFrom().
	}
	
	@Override
	public void requestWasRemoved(ObjectContainer container, ClientContext context) {
		if(persistenceType == PERSIST_FOREVER) {
			container.activate(putter, 1);
			putter.removeFrom(container, context);
			putter = null;
		}
		super.requestWasRemoved(container, context);
	}
}
