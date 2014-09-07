/**
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */
package freenet.support.io;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;

import freenet.client.async.ClientContext;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
import freenet.support.api.Bucket;
import freenet.support.api.BucketFactory;

public class DelayedFreeBucket implements Bucket, Serializable, DelayedFree {

    private static final long serialVersionUID = 1L;
    // Only set on construction and on onResume() on startup. So shouldn't need locking.
	private transient PersistentFileTracker factory;
	private final Bucket bucket;
	private boolean freed;

        private static volatile boolean logMINOR;
	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback(){
			@Override
			public void shouldUpdate(){
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
			}
		});
	}
	
	@Override
	public boolean toFree() {
		return freed;
	}
	
	public DelayedFreeBucket(PersistentFileTracker factory, Bucket bucket) {
		this.factory = factory;
		this.bucket = bucket;
		if(bucket == null) throw new NullPointerException();
	}

    @Override
	public OutputStream getOutputStream() throws IOException {
		if(freed) throw new IOException("Already freed");
		return bucket.getOutputStream();
	}

	@Override
	public InputStream getInputStream() throws IOException {
		if(freed) throw new IOException("Already freed");
		return bucket.getInputStream();
	}

	@Override
	public String getName() {
		return bucket.getName();
	}

	@Override
	public long size() {
		return bucket.size();
	}

	@Override
	public boolean isReadOnly() {
		return bucket.isReadOnly();
	}

	@Override
	public void setReadOnly() {
		bucket.setReadOnly();
	}

    public Bucket getUnderlying() {
		if(freed) return null;
		return bucket;
	}
	
	@Override
	public void free() {
		synchronized(this) { // mutex on just this method; make a separate lock if necessary to lock the above
			if(freed) return;
			if(logMINOR)
				Logger.minor(this, "Freeing "+this+" underlying="+bucket, new Exception("debug"));
			this.factory.delayedFree(this);
			freed = true;
		}
	}

	@Override
	public String toString() {
		return super.toString()+":"+bucket;
	}
	
	@Override
	public Bucket createShadow() {
		return bucket.createShadow();
	}

	@Override
    public void realFree() {
		bucket.free();
	}

    @Override
    public void onResume(ClientContext context) throws ResumeFailedException {
        this.factory = context.persistentBucketFactory;
        bucket.onResume(context);
    }
    
    static final int MAGIC = 0x4e9c9a03;
    static final int VERSION = 1;

    @Override
    public void storeTo(DataOutputStream dos) throws IOException {
        dos.writeInt(MAGIC);
        dos.writeInt(VERSION);
        bucket.storeTo(dos);
    }

    protected DelayedFreeBucket(DataInputStream dis) throws StorageFormatException, IOException {
        int version = dis.readInt();
        if(version != VERSION) throw new StorageFormatException("Bad version");
        bucket = (RandomAccessBucket) BucketTools.restoreFrom(dis);
    }
    
    /** Convert to a RandomAccessBucket if it can be done quickly. Otherwise return null. 
     * @throws IOException If the bucket has already been freed. */
    public RandomAccessBucket toRandomAccessBucket() throws IOException {
        if(freed) throw new IOException("Already freed");
        if(bucket instanceof RandomAccessBucket) {
            return new DelayedFreeRandomAccessBucket(factory, (RandomAccessBucket)bucket);
            // Underlying file is already registered.
        }
        return null;
    }

}