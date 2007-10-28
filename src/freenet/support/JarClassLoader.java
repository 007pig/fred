/*
 * freenet - JarClassLoader.java Copyright © 2007 David Roden
 * 
 * This program is free software; you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation; either version 2 of the License, or (at your option) any later
 * version.
 * 
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 * 
 * You should have received a copy of the GNU General Public License along with
 * this program; if not, write to the Free Software Foundation, Inc., 59 Temple
 * Place - Suite 330, Boston, MA 02111-1307, USA.
 */

package freenet.support;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import freenet.support.io.StreamCopier;

/**
 * Class loader that loads classes from a JAR file. The JAR file gets copied
 * to a temporary location; requests for classes and resources from this class
 * loader are then satisfied from this local copy.
 * 
 * @author <a href="mailto:dr@ina-germany.de">David Roden</a>
 * @version $Id$
 */
public class JarClassLoader extends ClassLoader {

	/** The temporary jar file. */
	private JarFile tempJarFile;

	/**
	 * Constructs a new jar class loader that loads classes from the jar file
	 * with the given name in the local file system.
	 * 
	 * @param fileName
	 *            The name of the jar file
	 * @throws IOException
	 *             if an I/O error occurs
	 */
	public JarClassLoader(String fileName) throws IOException {
		this(new File(fileName));
	}

	/**
	 * Constructs a new jar class loader that loads classes from the specified
	 * URL.
	 * 
	 * @param fileUrl
	 *            The URL to load the jar file from
	 * @param length
	 *            The length of the jar file if known, <code>-1</code>
	 *            otherwise
	 * @throws IOException
	 *             if an I/O error occurs
	 */
	public JarClassLoader(URL fileUrl, long length) throws IOException {
		copyFileToTemp(fileUrl.openStream(), length);
	}

	/**
	 * Constructs a new jar class loader that loads classes from the specified
	 * file.
	 * 
	 * @param file
	 *            The file to load classes from
	 * @throws IOException
	 *             if an I/O error occurs
	 */
	public JarClassLoader(File file) throws IOException {
		tempJarFile = new JarFile(file);
	}

	/**
	 * Copies the contents of the input stream (which are supposed to be the
	 * contents of a jar file) to a temporary location.
	 * 
	 * @param inputStream
	 *            The input stream to read from
	 * @param length
	 *            The length of the stream if known, <code>-1</code> if the
	 *            length is not known
	 * @throws IOException
	 *             if an I/O error occurs
	 */
	private void copyFileToTemp(InputStream inputStream, long length) throws IOException {
		File tempFile = File.createTempFile("jar-", ".tmp");
		FileOutputStream fileOutputStream = new FileOutputStream(tempFile);
		StreamCopier.copy(inputStream, fileOutputStream, length);
		fileOutputStream.close();
		tempFile.deleteOnExit();
		tempJarFile = new JarFile(tempFile);
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * This method searches the temporary copy of the jar file for an entry
	 * that is specified by the given class name.
	 * 
	 * @see java.lang.ClassLoader#findClass(java.lang.String)
	 */
	protected Class findClass(String name) throws ClassNotFoundException {
		try {
			String pathName = transformName(name);
			JarEntry jarEntry = tempJarFile.getJarEntry(pathName);
			if (jarEntry != null) {
				long size = jarEntry.getSize();
				InputStream jarEntryInputStream = tempJarFile.getInputStream(jarEntry);
				ByteArrayOutputStream classBytesOutputStream = new ByteArrayOutputStream((int) size);
				StreamCopier.copy(jarEntryInputStream, classBytesOutputStream, size);
				classBytesOutputStream.close();
				byte[] classBytes = classBytesOutputStream.toByteArray();
				Class clazz = defineClass(name, classBytes, 0, classBytes.length);
				return clazz;
			}
			return null;
		} catch (IOException e) {
			throw new ClassNotFoundException(e.getMessage(), e);
		}
	}

	/**
	 * {@inheritDoc}
	 * 
	 * @see java.lang.ClassLoader#findResource(java.lang.String)
	 */
	protected URL findResource(String name) {
		try {
			return new URL("jar:" + new File(tempJarFile.getName()).toURL() + "!" + name);
		} catch (MalformedURLException e) {
		}
		return null;
	}

	/**
	 * Transforms the class name into a file name that can be used to locate
	 * an entry in the jar file.
	 * 
	 * @param name
	 *            The name of the class
	 * @return The path name of the entry in the jar file
	 */
	private String transformName(String name) {
		return name.replace('.', '/') + ".class";
	}

}
