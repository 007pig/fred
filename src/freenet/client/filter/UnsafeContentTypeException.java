/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.filter;

import java.io.IOException;

import freenet.support.HTMLNode;

/**
 * Thrown by the filter when it cannot guarantee the safety of the data, because it is an unknown type,
 * because it cannot be filtered, or because we do not know how to filter it.
 * 
 * Base class for UnknownContentTypeException and KnownUnsafeContentTypeException.
 */
public abstract class UnsafeContentTypeException extends IOException {
	private static final long serialVersionUID = 1L;

	/**
	 * Get the contents of the error page.
	 */
	public abstract String getExplanation();
	
	/**
	 * Gets the contents of the error page as HTML.
	 */
	public abstract HTMLNode getHTMLExplanation();
	
	/**
	 * Get the title of the error page.
	 */
	public abstract String getHTMLEncodedTitle();
	
	/**
	 * Get the raw title of the error page. (May be unsafe for HTML).
	 */
	public abstract String getRawTitle();
	
}
