/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.useralerts;

import java.text.DateFormat;
import java.util.Date;

import freenet.l10n.L10n;
import freenet.node.DarknetPeerNode;
import freenet.support.HTMLNode;

// Node To Node Text Message User Alert
public class N2NTMUserAlert extends AbstractUserAlert {
	private DarknetPeerNode sourcePeerNode;
	private String sourceNodename;
	private String messageText;
	private int fileNumber;
	private long composedTime;
	private long sentTime;
	private long receivedTime;

	public N2NTMUserAlert(DarknetPeerNode sourcePeerNode, String source, String target, String message, int fileNumber, long composedTime, long  sentTime, long receivedTime) {
		super(true, null, null, null, null, UserAlert.MINOR, true, null, true, null);
		this.sourcePeerNode = sourcePeerNode;
		this.sourceNodename = source;
		this.messageText = message;
		this.fileNumber = fileNumber;
		this.composedTime = composedTime;
		this.sentTime = sentTime;
		this.receivedTime = receivedTime;
	}
	
	@Override
	public String getTitle() {
		return l10n("title", new String[] { "number", "peername", "peer" },
				new String[] { Integer.toString(fileNumber), sourcePeerNode.getName(), sourcePeerNode.getPeer().toString() });
	}
	
	@Override
	public String getText() {
		return 
			l10n("header", new String[] { "from", "composed", "sent", "received" },
					new String[] { sourceNodename, DateFormat.getInstance().format(new Date(composedTime)), 
					DateFormat.getInstance().format(new Date(sentTime)), DateFormat.getInstance().format(new Date(receivedTime)) }) + "\n" +
			messageText;
	}

	@Override
	public String getShortText() {
		return l10n("headerShort", "from", sourceNodename);
	}
	
	@Override
	public HTMLNode getHTMLText() {
		HTMLNode alertNode = new HTMLNode("div");
		alertNode.addChild("p",
				l10n("header", new String[] { "from", "composed", "sent", "received" },
						new String[] { sourceNodename, DateFormat.getInstance().format(new Date(composedTime)), 
						DateFormat.getInstance().format(new Date(sentTime)), DateFormat.getInstance().format(new Date(receivedTime)) }));
		String[] lines = messageText.split("\n");
		for (int i = 0, c = lines.length; i < c; i++) {
			alertNode.addChild("#", lines[i]);
			if(i != lines.length - 1)
				alertNode.addChild("br");
		}
		alertNode.addChild("p").addChild("a", "href", "/send_n2ntm/?peernode_hashcode=" + sourcePeerNode.hashCode(), l10n("reply"));
		return alertNode;
	}

	@Override
	public String dismissButtonText(){
		return l10n("delete");
	}
	
	private String l10n(String key) {
		return L10n.getString("N2NTMUserAlert."+key);
	}

	private String l10n(String key, String[] patterns, String[] values) {
		return L10n.getString("N2NTMUserAlert."+key, patterns, values);
	}

	private String l10n(String key, String pattern, String value) {
		return L10n.getString("N2NTMUserAlert."+key, pattern, value);
	}

	@Override
	public void onDismiss() {
		sourcePeerNode.deleteExtraPeerDataFile(fileNumber);
	}

}
