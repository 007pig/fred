package freenet.client.async;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.Serializable;
import java.util.Arrays;

import freenet.client.InsertContext;
import freenet.client.InsertContext.CompatibilityMode;
import freenet.support.Logger;
import freenet.support.io.StorageFormatException;

public class CompatibilityAnalyser implements Serializable {

    private static final long serialVersionUID = 1L;
    short min;
    short max;
    byte[] cryptoKey;
    boolean dontCompress;
    boolean definitive;
    
    public CompatibilityAnalyser() {
        this.min = InsertContext.CompatibilityMode.COMPAT_UNKNOWN.code;
        this.max = InsertContext.CompatibilityMode.COMPAT_UNKNOWN.code;
    }
    
    CompatibilityAnalyser(short min, short max, byte[] cryptoKey, boolean dontCompress, boolean definitive) {
        this.min = min;
        this.max = max;
        this.cryptoKey = cryptoKey;
        this.dontCompress = dontCompress;
        this.definitive = definitive;
    }
    
    public void merge(short min, short max, byte[] cryptoKey, boolean dontCompress, boolean definitive) {
        if(this.definitive) {
            Logger.warning(this, "merge() after definitive", new Exception("debug"));
            return;
        }
        assert(min != InsertContext.CompatibilityMode.COMPAT_CURRENT.ordinal());
        assert(max != InsertContext.CompatibilityMode.COMPAT_CURRENT.ordinal());
        if(definitive) this.definitive = true;
        if(!dontCompress) this.dontCompress = false;
        if(min > this.min) this.min = min;
        if(max < this.max || this.max == InsertContext.CompatibilityMode.COMPAT_UNKNOWN.ordinal()) this.max = max;
        if(this.cryptoKey == null) {
            this.cryptoKey = cryptoKey;
        } else if(cryptoKey != null && !Arrays.equals(this.cryptoKey, cryptoKey)) {
            Logger.error(this, "Two different crypto keys!");
            this.cryptoKey = null;
        }
    }

    public CompatibilityMode min() {
        return InsertContext.CompatibilityMode.byCode(min);
    }
    
    public CompatibilityMode max() {
        return InsertContext.CompatibilityMode.byCode(max);
    }

    public byte[] getCryptoKey() {
        return cryptoKey;
    }

    public boolean dontCompress() {
        return dontCompress;
    }

    public boolean definitive() {
        return definitive;
    }

    public InsertContext.CompatibilityMode[] getModes() {
        return new InsertContext.CompatibilityMode[] { min(), max() };
    }
    
    static final int VERSION = 2;

    public void writeTo(DataOutputStream dos) throws IOException {
        dos.writeInt(VERSION);
        dos.writeShort(min);
        dos.writeShort(max);
        if(cryptoKey == null) {
            dos.writeBoolean(false);
        } else {
            dos.writeBoolean(true);
            assert(cryptoKey.length == 32);
            dos.write(cryptoKey);
        }
        dos.writeBoolean(dontCompress);
        dos.writeBoolean(definitive);
    }
    
    public CompatibilityAnalyser(DataInputStream dis) throws IOException, StorageFormatException {
        int ver = dis.readInt();
        if(ver != VERSION) throw new StorageFormatException("Unknown version for CompatibilityAnalyser");
        min = dis.readShort();
        if(min < 0 || CompatibilityMode.hasCode(min))
            throw new StorageFormatException("Bad min value");
        max = dis.readShort();
        if(max < 0 || max >= InsertContext.CompatibilityMode.values().length)
            throw new StorageFormatException("Bad max value");
        if(dis.readBoolean()) {
            cryptoKey = new byte[32];
            dis.readFully(cryptoKey);
        }
        dontCompress = dis.readBoolean();
        definitive = dis.readBoolean();
    }


}
