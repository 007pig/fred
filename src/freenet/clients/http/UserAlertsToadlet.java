/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.http;

import java.io.IOException;
import java.net.URI;

import freenet.client.HighLevelSimpleClient;
import freenet.clients.http.updateableelements.LongAlertElement;
import freenet.l10n.L10n;
import freenet.l10n.NodeL10n;
import freenet.node.Node;
import freenet.node.NodeClientCore;
import freenet.node.useralerts.UserAlertManager;
import freenet.support.HTMLNode;
import freenet.support.MultiValueTable;
import freenet.support.api.HTTPRequest;

/**
 * A page consisting entirely of useralerts.
 * @author toad
 */
public class UserAlertsToadlet extends Toadlet {
	
	UserAlertsToadlet(HighLevelSimpleClient client, Node node, NodeClientCore core) {
		super(client);
		this.node = node;
	}
	
	private Node node;

	public void handleMethodGET(URI uri, HTTPRequest request, ToadletContext ctx) throws ToadletContextClosedException, IOException {
		if (!ctx.isAllowedFullAccess()) {
			super.sendErrorPage(ctx, 403, NodeL10n.getBase().getString("Toadlet.unauthorizedTitle"), NodeL10n.getBase().getString("Toadlet.unauthorized"));
			return;
		}
		
		PageNode page = ctx.getPageMaker().getPageNode(l10n("titleWithName", "name", node.getMyName()), ctx);
        HTMLNode pageNode = page.outer;
        HTMLNode contentNode = page.content;
        contentNode.addChild(new LongAlertElement(ctx,false));
        
        writeHTMLReply(ctx, 200, "OK", pageNode.generate());
	}

	public void handleMethodPOST(URI uri, HTTPRequest request, ToadletContext ctx) throws ToadletContextClosedException, IOException {

		MultiValueTable<String, String> headers = new MultiValueTable<String, String>();

		String pass = request.getPartAsString("formPassword", 32);
		if((pass == null) || !pass.equals(node.clientCore.formPassword)) {
			sendErrorPage(ctx, 403, "Forbidden", "Invalid form password.");
		}
		if (request.isPartSet("dismiss-user-alert")) {
			int userAlertHashCode = request.getIntPart("disable", -1);
			node.clientCore.alerts.dismissAlert(userAlertHashCode);
		}
		headers.put("Location", ".");
		ctx.sendReplyHeaders(302, "Found", headers, null, 0);
	}


	protected String l10n(String name, String pattern, String value) {
		return NodeL10n.getBase().getString("UserAlertsToadlet."+name, pattern, value);
	}

	@Override
	public String path() {
		return "/alerts/";
	}
	
}
