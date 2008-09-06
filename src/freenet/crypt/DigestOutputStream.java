/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.crypt;

import java.io.*;

public class DigestOutputStream extends FilterOutputStream {
    protected Digest ctx;

    public DigestOutputStream(Digest d, OutputStream out) {
	super(out);
	this.ctx=d;
    }

    @Override
	public void write(byte[] b) throws IOException {
	write(b, 0, b.length);
    }

    @Override
	public void write(byte[] b, int offset, int len) throws IOException {
	ctx.update(b, offset, len);
	out.write(b, offset, len);
    }

    @Override
	public void write(int b) throws IOException {
	ctx.update((byte)b);
	out.write(b);
    }

    public Digest getDigest() {
	return ctx;
    }
}
	
