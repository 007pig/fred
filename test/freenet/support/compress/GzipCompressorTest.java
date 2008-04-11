/*
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA.
 */

package freenet.support.compress;

import java.io.IOException;
import java.io.InputStream;

import junit.framework.TestCase;
import freenet.support.api.Bucket;
import freenet.support.api.BucketFactory;
import freenet.support.io.ArrayBucket;
import freenet.support.io.ArrayBucketFactory;

/**
 * Test case for {@link freenet.support.compress.GzipCompressor} class.
 * 
 * @author stuart martin &lt;wavey@freenetproject.org&gt;
 */
public class GzipCompressorTest extends TestCase {

	private static final String UNCOMPRESSED_DATA_1 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaabbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
			+ "aksjdhaskjsdhaskjdhaksjdhkajsdhkasdhkqhdioqahdkashdkashdnkashdnaskdhnkasjhdnkasjhdnkasjhdnkasjhdnkasjhdnkashdnkasjhdnkasjhdnkasjhndkasjhdna"
			+ "djjjjjjjjjjjjjjj3j12j312j312j312j31j23hj123niah1ia3h1iu2b321uiab31ugb312gba38gab23igb12i3ag1b2ig3bi1g3bi1gba3iu12ba3iug1bi3ug1b2i3gab1i2ua3";

	private static final byte[] COMPRESSED_DATA_1 = { 31, -117, 8, 0, 0, 0, 0, 0, 0, 0, -99, -117, 81, 10, -60, 48, 8, 68, -49, -92, -13, -77,
			-41, 25, 9, 36, 26, -24, 82, 66, -18, 95, -37, -12, -89, -80, 44, -53, 14, -8, 70, 71, 37, -1, -108, -3, 36, -10, 17, -91, 113, -12,
			24, -53, -110, 87, -44, 121, 38, -99, 39, -10, 86, -4, -67, -77, -107, 28, 111, 108, -117, -7, 81, -38, -39, -57, -118, -66, -39,
			-25, -43, 86, -18, -119, 37, -98, 66, -120, 6, 30, 21, -118, -106, 41, 54, 103, 19, 39, 18, 83, 13, 42, -45, 105, -112, 89, 19, 90,
			-115, 120, 85, -102, -62, -85, -119, 58, 88, -59, -44, 43, -52, 101, 33, 15, 124, -118, 94, -106, 59, -57, -68, 46, -112, 79, -30,
			58, -119, 3, -88, -111, 58, 68, 117, 1, 0, 0 };

	/**
	 * test GZIP compressor's identity and functionality
	 */
	public void testGzipCompressor() {
		GzipCompressor gzipCompressor = (GzipCompressor) Compressor.GZIP;
		Compressor compressorZero = Compressor.getCompressionAlgorithmByMetadataID((short) 0);

		// check GZIP is the first compressor
		assertEquals(gzipCompressor, compressorZero);

		// test gzip compression
		byte[] compressedData = doCompress(UNCOMPRESSED_DATA_1.getBytes());

		// test gzip uncompression
		doUncompress(compressedData);
	}

	private void doUncompress(byte[] compressedData) {

		Bucket inBucket = new ArrayBucket(compressedData);
		BucketFactory factory = new ArrayBucketFactory();
		Bucket outBucket = null;

		try {
			outBucket = Compressor.GZIP.decompress(inBucket, factory, 32768, 32768 * 2, null);
		} catch (IOException e) {
			fail("unexpected exception thrown : " + e.getMessage());
		} catch (CompressionOutputSizeException e) {
			fail("unexpected exception thrown : " + e.getMessage());
		}

		InputStream in = null;

		try {
			in = outBucket.getInputStream();
		} catch (IOException e1) {
			fail("unexpected exception thrown : " + e1.getMessage());
		}
		long size = outBucket.size();
		byte[] outBuf = new byte[(int) size];

		try {
			in.read(outBuf);
		} catch (IOException e) {
			fail("unexpected exception thrown : " + e.getMessage());
		}

		// is the (round-tripped) uncompressed string the same as the original?
		String uncompressedString = new String(outBuf);
		assertEquals(uncompressedString, UNCOMPRESSED_DATA_1);
	}

	private byte[] doCompress(byte[] uncompressedData) {
		Bucket inBucket = new ArrayBucket(uncompressedData);
		BucketFactory factory = new ArrayBucketFactory();
		Bucket outBucket = null;

		try {
			outBucket = Compressor.GZIP.compress(inBucket, factory, 32768);
		} catch (IOException e) {
			fail("unexpected exception thrown : " + e.getMessage());
		} catch (CompressionOutputSizeException e) {
			fail("unexpected exception thrown : " + e.getMessage());
		}

		InputStream in = null;
		try {
			in = outBucket.getInputStream();
		} catch (IOException e1) {
			fail("unexpected exception thrown : " + e1.getMessage());
		}
		long size = outBucket.size();
		byte[] outBuf = new byte[(int) size];

		try {
			in.read(outBuf);
		} catch (IOException e) {
			fail("unexpected exception thrown : " + e.getMessage());
		}

		// output size same as expected?
		assertEquals(outBuf.length, COMPRESSED_DATA_1.length);

		// check each byte is exactly as expected
		for (int i = 0; i < outBuf.length; i++) {
			assertEquals(COMPRESSED_DATA_1[i], outBuf[i]);
		}

		return outBuf;
	}
}
