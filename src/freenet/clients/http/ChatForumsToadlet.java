package freenet.clients.http;

import java.io.IOException;
import java.net.URI;

import freenet.client.HighLevelSimpleClient;
import freenet.l10n.L10n;
import freenet.node.useralerts.UserAlertManager;
import freenet.pluginmanager.PluginManager;
import freenet.support.HTMLNode;
import freenet.support.api.HTTPRequest;

public class ChatForumsToadlet extends Toadlet implements LinkEnabledCallback {

	private final UserAlertManager alerts;
	private final PluginManager plugins;
	
	protected ChatForumsToadlet(HighLevelSimpleClient client, UserAlertManager alerts, PluginManager plugins) {
		super(client);
		this.alerts = alerts;
		this.plugins = plugins;
	}

	public void handleMethodGET(URI uri, HTTPRequest req, ToadletContext ctx) throws ToadletContextClosedException, IOException {
		PageNode page = ctx.getPageMaker().getPageNode(l10n("title"), ctx);
		HTMLNode pageNode = page.outer;
		HTMLNode contentNode = page.content;
		
		contentNode.addChild(alerts.createSummary());
		
		HTMLNode contentBox = ctx.getPageMaker().getInfobox("infobox-information", l10n("title"), contentNode, "chat-list", true);
		
		contentBox.addChild("p", l10n("content1"));
		HTMLNode ul = contentBox.addChild("ul");
		HTMLNode li = ul.addChild("li");
		L10n.addL10nSubstitution(li, "ChatForumsToadlet.frost", new String[] { "frost-freenet", "frost-web", "frost-help", "/link" }, 
				new String[] { "<a href=\"/freenet:USK@QRZAI1nSm~dAY2hTdzVWXmEhkaI~dso0OadnppBR7kE,wq5rHGBI7kpChBe4yRmgBChIGDug7Xa5SG9vYGXdxR0,AQACAAE/frost/9/\">", "<a href=\"/?_CHECKED_HTTP_=http://jtcfrost.sourceforge.net/\">", "<a href=\"/SSK@ugb~uuscsidMI-Ze8laZe~o3BUIb3S50i25RIwDH99M,9T20t3xoG-dQfMO94LGOl9AxRTkaz~TykFY-voqaTQI,AQACAAE/FAFS-49/files/frost.htm\">", "</a>" });
		li = ul.addChild("li");
		L10n.addL10nSubstitution(li, "ChatForumsToadlet.fms", new String[] { "fms", "fms-help", "/link" }, new String[] { "<a href=\"/USK@0npnMrqZNKRCRoGojZV93UNHCMN-6UU3rRSAmP6jNLE,~BG-edFtdCC1cSH4O3BWdeIYa8Sw5DfyrSV-TKdO5ec,AQACAAE/fms/101/\">", "<a href=\"/SSK@ugb~uuscsidMI-Ze8laZe~o3BUIb3S50i25RIwDH99M,9T20t3xoG-dQfMO94LGOl9AxRTkaz~TykFY-voqaTQI,AQACAAE/FAFS-49/files/fms.htm\">", "</a>" });
		contentBox.addChild("p", l10n("content2"));
		
		this.writeHTMLReply(ctx, 200, "OK", pageNode.generate());
	}

	private static final String l10n(String string) {
		return L10n.getString("ChatForumsToadlet." + string);
	}

	@Override
	public String path() {
		return "/chat/";
	}

	public boolean isEnabled(ToadletContext ctx) {
		return !plugins.isPluginLoaded("plugins.Freetalk.Freetalk");
	}

	
}
