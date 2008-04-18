/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.useralerts;

import freenet.l10n.L10n;
import freenet.support.HTMLNode;

public class BuildOldAgeUserAlert extends AbstractUserAlert {
	public int lastGoodVersion = 0;
	
	public BuildOldAgeUserAlert() {
		super(false, null, null, null, null, UserAlert.ERROR, true, L10n.getString("UserAlert.hide"), false, null);
	}
	
	public String getTitle() {
		return l10n("tooOldTitle");
	}
	
	private String l10n(String key) {
		return L10n.getString("BuildOldAgeUserAlert."+key);
	}

	private String l10n(String key, String pattern, String value) {
		return L10n.getString("BuildOldAgeUserAlert."+key, pattern, value);
	}

	public String getText() {
	  if(lastGoodVersion == 0)
		  throw new IllegalArgumentException("Not valid");
		String s = l10n("tooOld", "lastgood", Integer.toString(lastGoodVersion));
		return s;
	}

	public HTMLNode getHTMLText() {
		return new HTMLNode("div", getText());
	}

	public boolean isValid() {
		if (lastGoodVersion == 0)
			return false;
		return super.isValid();
	}
	
	public String getShortText() {
		return l10n("tooOldShort", "lastgood", Integer.toString(lastGoodVersion));
	}

}
