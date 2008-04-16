/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

import java.io.File;
import java.io.IOException;
import java.io.FileNotFoundException;
import java.net.MalformedURLException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Vector;

import freenet.client.DefaultMIMETypes;
import freenet.client.FetchException;
import freenet.client.FetchResult;
import freenet.client.InsertException;
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

	private final HashMap manifestElements;
	private SimpleManifestPutter putter;
	private final String defaultName;
	private final long totalSize;
	private final int numberOfFiles;
	private static boolean logMINOR;
	private final boolean wasDiskPut;
	
	public ClientPutDir(FCPConnectionHandler handler, ClientPutDirMessage message, 
			HashMap manifestElements, boolean wasDiskPut) throws IdentifierCollisionException, MalformedURLException {
		super(message.uri, message.identifier, message.verbosity, handler,
				message.priorityClass, message.persistenceType, message.clientToken, message.global,
				message.getCHKOnly, message.dontCompress, message.maxRetries, message.earlyEncode);
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		this.wasDiskPut = wasDiskPut;
		this.manifestElements = manifestElements;
		this.defaultName = message.defaultName;
		makePutter();
		if(persistenceType != PERSIST_CONNECTION) {
			client.register(this, false);
			FCPMessage msg = persistentTagMessage();
			client.queueClientRequestMessage(msg, 0);
			if(handler != null && (!handler.isGlobalSubscribed()))
				handler.outputHandler.queue(msg);
		}
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
	*/
	public ClientPutDir(FCPClient client, FreenetURI uri, String identifier, int verbosity, short priorityClass, short persistenceType, String clientToken, boolean getCHKOnly, boolean dontCompress, int maxRetries, File dir, String defaultName, boolean allowUnreadableFiles, boolean global, boolean earlyEncode) throws FileNotFoundException, IdentifierCollisionException, MalformedURLException {
		super(uri, identifier, verbosity , null, client, priorityClass, persistenceType, clientToken, global, getCHKOnly, dontCompress, maxRetries, earlyEncode);

		wasDiskPut = true;
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		this.manifestElements = makeDiskDirManifest(dir, "", allowUnreadableFiles);
		this.defaultName = defaultName;
		makePutter();
		if(persistenceType != PERSIST_CONNECTION) {
			client.register(this, false);
			FCPMessage msg = persistentTagMessage();
			client.queueClientRequestMessage(msg, 0);
		}
		if(putter != null) {
			numberOfFiles = putter.countFiles();
			totalSize = putter.totalSize();
		} else {
			numberOfFiles = -1;
			totalSize = -1;
		}
		if(logMINOR) Logger.minor(this, "Putting dir "+identifier+" : "+priorityClass);
	}

	private HashMap makeDiskDirManifest(File dir, String prefix, boolean allowUnreadableFiles) throws FileNotFoundException {

		HashMap map = new HashMap();
		File[] files = dir.listFiles();
		
		if(files == null)
			throw new IllegalArgumentException("No such directory");

		for(int i=0; i < files.length; i++) {

			File f = files[i];
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
		try {
			p = new SimpleManifestPutter(this, client.core.requestStarters.chkPutScheduler, client.core.requestStarters.sskPutScheduler,
					manifestElements, priorityClass, uri, defaultName, ctx, getCHKOnly, client.lowLevelClient, earlyEncode);
		} catch (InsertException e) {
			onFailure(e, null);
			p = null;
		}
		putter = p;
	}



	public ClientPutDir(SimpleFieldSet fs, FCPClient client) throws PersistenceParseException, IOException {
		super(fs, client);
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
		Vector v = new Vector();
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
						data = SerializableToFieldSetBucketUtil.create(fs.subset("ReturnBucket"), ctx.random, client.server.core.persistentTempBucketFactory);
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
		try {
			if(!finished)
				p = new SimpleManifestPutter(this, client.core.requestStarters.chkPutScheduler, client.core.requestStarters.sskPutScheduler,
						manifestElements, priorityClass, uri, defaultName, ctx, getCHKOnly, client, earlyEncode);
		} catch (InsertException e) {
			onFailure(e, null);
			p = null;
		}
		putter = p;
		numberOfFiles = fileCount;
		totalSize = size;
		if(persistenceType != PERSIST_CONNECTION) {
			FCPMessage msg = persistentTagMessage();
			client.queueClientRequestMessage(msg, 0);
		}
	}

	public void start() {
		if(finished) return;
		if(started) return;
		try {
			if(putter != null)
				putter.start();
			started = true;
			if(logMINOR) Logger.minor(this, "Started "+putter);
			if(persistenceType != PERSIST_CONNECTION && !finished) {
				FCPMessage msg = persistentTagMessage();
				client.queueClientRequestMessage(msg, 0);
			}
		} catch (InsertException e) {
			started = true;
			onFailure(e, null);
		}
	}
	
	public void onLostConnection() {
		if(persistenceType == PERSIST_CONNECTION)
			cancel();
		// otherwise ignore
	}
	
	protected void freeData() {
		freeData(manifestElements);
	}
	
	private void freeData(HashMap manifestElements) {
		Iterator i = manifestElements.values().iterator();
		while(i.hasNext()) {
			Object o = i.next();
			if(o instanceof HashMap)
				freeData((HashMap)o);
			else {
				ManifestElement e = (ManifestElement) o;
				e.freeData();
			}
		}
	}

	protected ClientRequester getClientRequest() {
		return putter;
	}

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

	protected FCPMessage persistentTagMessage() {
		return new PersistentPutDir(identifier, publicURI, verbosity, priorityClass,
				persistenceType, global, defaultName, manifestElements, clientToken, started, ctx.maxInsertRetries, wasDiskPut);
	}

	protected String getTypeName() {
		return "PUTDIR";
	}

	public boolean hasSucceeded() {
		return succeeded;
	}

	public FreenetURI getFinalURI() {
		return generatedURI;
	}

	public boolean isDirect() {
		// TODO Auto-generated method stub
		return false;
	}

	public int getNumberOfFiles() {
		return numberOfFiles;
	}

	public long getTotalDataSize() {
		return totalSize;
	}

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

	public boolean restart() {
		if(!canRestart()) return false;
		setVarsRestart();
		makePutter();
		start();
		return true;
	}

	public void onFailure(FetchException e, ClientGetter state) {}

	public void onSuccess(FetchResult result, ClientGetter state) {}
}
