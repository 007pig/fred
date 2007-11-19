/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.http;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;

import java.io.File;

import org.tanukisoftware.wrapper.WrapperManager;

import freenet.client.ClientMetadata;
import freenet.client.HighLevelSimpleClient;
import freenet.client.InsertBlock;
import freenet.client.InsertException;
import freenet.clients.http.filter.GenericReadFilterCallback;
import freenet.clients.http.bookmark.BookmarkItems;
import freenet.clients.http.bookmark.BookmarkCategory;
import freenet.clients.http.bookmark.BookmarkCategories;
import freenet.clients.http.bookmark.BookmarkManager;
import freenet.keys.FreenetURI;
import freenet.l10n.L10n;
import freenet.node.Node;
import freenet.node.NodeClientCore;
import freenet.node.NodeStarter;
import freenet.node.Version;
import freenet.node.useralerts.UserAlert;
import freenet.support.HTMLNode;
import freenet.support.Logger;
import freenet.support.MultiValueTable;
import freenet.support.api.Bucket;
import freenet.support.api.HTTPRequest;
import freenet.support.io.FileUtil;


import freenet.frost.message.*;




public class WelcomeToadlet extends Toadlet {
	private static final int MAX_URL_LENGTH = 1024 * 1024;
	final NodeClientCore core;
	final Node node;
	final BookmarkManager bookmarkManager;
	
	WelcomeToadlet(HighLevelSimpleClient client, NodeClientCore core, Node node) {
		super(client);
		this.node = node;
		this.core = core;
		this.bookmarkManager = core.bookmarkManager;
		try {
			manageBookmarksURI = new URI("/welcome/?managebookmarks");
		} catch (URISyntaxException e) {
			throw new Error(e);
		}
	}

	void redirectToRoot(ToadletContext ctx) throws ToadletContextClosedException, IOException {
		MultiValueTable headers = new MultiValueTable();
		headers.put("Location", "/");
		ctx.sendReplyHeaders(302, "Found", headers, null, 0);
		return;
	}

	URI manageBookmarksURI;
	
	
	private void addCategoryToList(BookmarkCategory cat, HTMLNode list)
	{
		BookmarkItems items = cat.getItems();
		for(int i = 0; i < items.size(); i++) {
			HTMLNode li = list.addChild("li", "class","item");
			HTMLNode a = li.addChild("a", "href", '/' + items.get(i).getKey());
                        HTMLNode img = a.addChild("img", new String[] { "src", "height", "width", "alt"},
                                new String[] { '/' + items.get(i).getKey() + "/activelink.png", "36px", "108px", "activelink"});
                        img.addChild("#", "  " + items.get(i).getName());
		}

		BookmarkCategories cats = cat.getSubCategories();
		for (int i = 0; i < cats.size(); i++) {			
			list.addChild("li", "class", "cat", cats.get(i).getName());
			addCategoryToList(cats.get(i), list.addChild("li").addChild("ul"));
		}
	}
	
	public void handlePost(URI uri, HTTPRequest request, ToadletContext ctx) throws ToadletContextClosedException, IOException {
		
		if(!ctx.isAllowedFullAccess()) {
			super.sendErrorPage(ctx, 403, "Unauthorized", L10n.getString("Toadlet.unauthorized"));
			return;
		}
		
		String passwd = request.getPartAsString("formPassword", 32);
		boolean noPassword = (passwd == null) || !passwd.equals(core.formPassword);
		if(noPassword) {
			if(Logger.shouldLog(Logger.MINOR, this)) Logger.minor(this, "No password ("+passwd+" should be "+core.formPassword+ ')');
		}
		
		if(request.getPartAsString("updateconfirm", 32).length() > 0){
			if(noPassword) {
				redirectToRoot(ctx);
				return;
			}
			// false for no navigation bars, because that would be very silly
			HTMLNode pageNode = ctx.getPageMaker().getPageNode(l10n("updatingTitle"), ctx);
			HTMLNode contentNode = ctx.getPageMaker().getContentNode(pageNode);
			HTMLNode infobox = contentNode.addChild(ctx.getPageMaker().getInfobox("infobox-information", l10n("updatingTitle")));
			HTMLNode content = ctx.getPageMaker().getContentNode(infobox);
			content.addChild("p").addChild("#", l10n("updating"));
			content.addChild("p").addChild("#", l10n("thanks"));
			writeHTMLReply(ctx, 200, "OK", pageNode.generate());
			Logger.normal(this, "Node is updating/restarting");
			node.getNodeUpdater().arm();
		}else if (request.getPartAsString(GenericReadFilterCallback.magicHTTPEscapeString, MAX_URL_LENGTH).length()>0){
			if(noPassword) {
				redirectToRoot(ctx);
				return;
			}
			MultiValueTable headers = new MultiValueTable();
			String url = null;
			if((request.getPartAsString("Go", 32).length() > 0))
				url = request.getPartAsString(GenericReadFilterCallback.magicHTTPEscapeString, MAX_URL_LENGTH);
			headers.put("Location", url==null ? "/" : url);
			ctx.sendReplyHeaders(302, "Found", headers, null, 0);
		}else if (request.getPartAsString("update", 32).length() > 0) {
			HTMLNode pageNode = ctx.getPageMaker().getPageNode(l10n("nodeUpdateConfirmTitle"), ctx);
			HTMLNode contentNode = ctx.getPageMaker().getContentNode(pageNode);
			HTMLNode infobox = contentNode.addChild(ctx.getPageMaker().getInfobox("infobox-query", l10n("nodeUpdateConfirmTitle")));
			HTMLNode content = ctx.getPageMaker().getContentNode(infobox);
			content.addChild("p").addChild("#", l10n("nodeUpdateConfirm"));
			HTMLNode updateForm = ctx.addFormChild(content, "/", "updateConfirmForm");
			updateForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "cancel", L10n.getString("Toadlet.cancel")});
			updateForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "updateconfirm", l10n("update") });
			writeHTMLReply(ctx, 200, "OK", pageNode.generate());
		}else if(request.isPartSet("getThreadDump")) {
			if(noPassword) {
				redirectToRoot(ctx);
				return;
			}
			HTMLNode pageNode = ctx.getPageMaker().getPageNode(l10n("threadDumpTitle"), ctx);
			HTMLNode contentNode = ctx.getPageMaker().getContentNode(pageNode);
			if(node.isUsingWrapper()){
				HTMLNode infobox = contentNode.addChild(ctx.getPageMaker().getInfobox(l10n("threadDumpSubTitle")));
				ctx.getPageMaker().getContentNode(infobox).addChild("#", l10n("threadDumpWithFilename", "filename", WrapperManager.getProperties().getProperty("wrapper.logfile")));
				System.out.println("Thread Dump:");
				WrapperManager.requestThreadDump();
			}else{
				HTMLNode infobox = contentNode.addChild(ctx.getPageMaker().getInfobox("infobox-error",l10n("threadDumpSubTitle")));
				ctx.getPageMaker().getContentNode(infobox).addChild("#", l10n("threadDumpNotUsingWrapper"));
			}
			this.writeHTMLReply(ctx, 200, "OK", pageNode.generate());
		}else if(request.isPartSet("getJEStatsDump")) {
			if(noPassword) {
				redirectToRoot(ctx);
				return;
			}
			HTMLNode pageNode = ctx.getPageMaker().getPageNode(l10n("databaseStatsTitle"), ctx);
			HTMLNode contentNode = ctx.getPageMaker().getContentNode(pageNode);
			HTMLNode infobox = contentNode.addChild(ctx.getPageMaker().getInfobox(l10n("databaseStatsSubTitle")));

			System.out.println(">>>>>>>>>>>>>>>>>>>>>>> START DATABASE STATS <<<<<<<<<<<<<<<<<<<<<<<");
			node.JEStatsDump();
			System.out.println(">>>>>>>>>>>>>>>>>>>>>>>  END DATABASE STATS  <<<<<<<<<<<<<<<<<<<<<<<");

			ctx.getPageMaker().getContentNode(infobox).addChild("#", l10n("writtenDatabaseStats"));
			this.writeHTMLReply(ctx, 200, "OK", pageNode.generate());
		}else if(request.isPartSet("disable")){
			if(noPassword) {
				redirectToRoot(ctx);
				return;
			}
			UserAlert[] alerts=core.alerts.getAlerts();
			for(int i=0;i<alerts.length;i++){
				if(request.getIntPart("disable",-1)==alerts[i].hashCode()){
					UserAlert alert = alerts[i];
					// Won't be dismissed if it's not allowed anyway
					if(alert.userCanDismiss() && alert.shouldUnregisterOnDismiss()) {
						alert.onDismiss();
						Logger.normal(this,"Unregistering the userAlert "+alert.hashCode());
						core.alerts.unregister(alert);
					} else {
						Logger.normal(this,"Disabling the userAlert "+alert.hashCode());
						alert.isValid(false);
					}
				}
			}
			writePermanentRedirect(ctx, l10n("disabledAlert"), "/");
			return;
		} else if(request.isPartSet("boardname")&&(request.isPartSet("filename")||request.isPartSet("message"))) {
			// Inserting into a frost board FIN
			// boardname
			// filename
			// boardprivatekey (not needed)
			// boardpublickey (not needed) (and maybe dump it all the way)
			// innitialindex
			// sender
			// subject
			String boardName = request.getPartAsString("boardname",FrostBoard.MAX_NAME_LENGTH);
			String boardPrivateKey = request.getPartAsString("boardprivatekey",78);
			String boardPublicKey = request.getPartAsString("boardpublickey",78);
			String sender = request.getPartAsString("sender",64);
			String subject = request.getPartAsString("subject",128);
			String message = request.getPartAsString("message",1024);
			if(message.length() == 0) // back compatibility; should use message
				message = request.getPartAsString("filename", 1024);
			
			int initialIndex = 0;
			if(request.isPartSet("initialindex")) {
				try {
					initialIndex = Integer.parseInt(request.getPartAsString("initialindex",10));
				} catch(NumberFormatException e) {
					initialIndex = 0;
				}
			} else if(request.isPartSet("innitialindex")) {
				try {
					initialIndex = Integer.parseInt(request.getPartAsString("innitialindex",10));
				} catch(NumberFormatException e) {
					initialIndex = 0;
				}
			}
			
			if(noPassword) {
				HTMLNode pageNode = ctx.getPageMaker().getPageNode(l10n("finTitle"), ctx);
				HTMLNode contentNode = ctx.getPageMaker().getContentNode(pageNode);
				HTMLNode infobox = contentNode.addChild(ctx.getPageMaker().getInfobox("infobox-query", l10n("finTitle")));
				HTMLNode content = ctx.getPageMaker().getContentNode(infobox);
				content.addChild("p").addChild("#", l10n("confirmFIN"));
				HTMLNode postForm = ctx.addFormChild(content.addChild("p"), "/", "finConfirmForm"); 
				HTMLNode table = postForm.addChild("table", "align", "center");
				
				finInputRow(table, "boardname", l10n("targetBoardHeader"), boardName);
				finInputRow(table, "boardprivatekey", l10n("privateKeyHeader"), boardPrivateKey);
				finInputRow(table, "boardpublickey", l10n("publicKeyHeader"), boardPublicKey);
				finInputRow(table, "initialindex", l10n("startIndexHeader"), Integer.toString(initialIndex));
				finInputRow(table, "sender", l10n("fromHeader"), sender);
				finInputRow(table, "subject", l10n("subjectHeader"), subject);
				finInputBoxRow(table, "message", l10n("messageHeader"), message);
				
				postForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "cancel", L10n.getString("Toadlet.cancel") });
				postForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "finconfirm", l10n("post") });
				writeHTMLReply(ctx, 200, "OK", pageNode.generate());
				return;
			}
			
			if(!request.isPartSet("finconfirm")) {
				redirectToRoot(ctx);
				return;
			}
			
			FrostBoard board = null;
			if(boardPrivateKey.length()>0 && boardPublicKey.length()>0) { // keyed board
				board = new FrostBoard(boardName, boardPrivateKey, boardPublicKey);
			} else { // unkeyed or public board
				board = new FrostBoard(boardName);
			}
			FrostMessage fin = new FrostMessage("news", board, sender, subject, message);
			
			HTMLNode pageNode = ctx.getPageMaker().getPageNode(l10n("finInsertedTitle"), ctx);
			HTMLNode contentNode = ctx.getPageMaker().getContentNode(pageNode);
			HTMLNode content;
			try {
				FreenetURI finalKey = fin.insertMessage(this.getClientImpl(), initialIndex);
				HTMLNode infobox = contentNode.addChild(ctx.getPageMaker().getInfobox("infobox-success", l10n("insertSucceededTitle")));
				content = ctx.getPageMaker().getContentNode(infobox);
				content.addChild("#", l10n("finInsertSuccessWithKey", "key", finalKey.toString()));
			} catch (InsertException e) {
				HTMLNode infobox = ctx.getPageMaker().getInfobox("infobox-error", l10n("insertFailedTitle"));
				content = ctx.getPageMaker().getContentNode(infobox);
				content.addChild("#", l10n("insertFailedWithMessage", "message", e.getMessage()));
				content.addChild("br");
				if (e.uri != null) {
					content.addChild("#", l10n("uriWouldHaveBeen", "uri", e.uri.toString()));
				}
				int mode = e.getMode();
				if((mode == InsertException.FATAL_ERRORS_IN_BLOCKS) || (mode == InsertException.TOO_MANY_RETRIES_IN_BLOCKS)) {
					content.addChild("br"); /* TODO */
					content.addChild("#", l10n("splitfileErrorLabel"));
					content.addChild("pre", e.errorCodes.toVerboseString());
				}
			}
			content.addChild("br");
			addHomepageLink(content);
			
			writeHTMLReply(ctx, 200, "OK", pageNode.generate());
			request.freeParts();
		}else if(request.isPartSet("key")&&request.isPartSet("filename")){
			if(noPassword) {
				redirectToRoot(ctx);
				return;
			}

			FreenetURI key = new FreenetURI(request.getPartAsString("key",128));
			String type = request.getPartAsString("content-type",128);
			if(type==null) type = "text/plain";
			ClientMetadata contentType = new ClientMetadata(type);
			
			Bucket bucket = request.getPart("filename");
			
			HTMLNode pageNode = ctx.getPageMaker().getPageNode(l10n("insertedTitle"), ctx);
			HTMLNode contentNode = ctx.getPageMaker().getContentNode(pageNode);
			HTMLNode content;
			String filenameHint = null;
			if(key.getKeyType().equals("CHK")) {
				String[] metas = key.getAllMetaStrings();
				if(metas != null && metas.length > 1) {
					filenameHint = metas[0];
				}
			}
			InsertBlock block = new InsertBlock(bucket, contentType, key);
			try {
				key = this.insert(block, filenameHint, false);
				HTMLNode infobox = contentNode.addChild(ctx.getPageMaker().getInfobox("infobox-success", l10n("insertSucceededTitle")));
				content = ctx.getPageMaker().getContentNode(infobox);
				String u = key.toString();
				L10n.addL10nSubstitution(content, "WelcomeToadlet.keyInsertedSuccessfullyWithKeyAndName",
						new String[] { "link", "/link", "name" },
						new String[] { "<a href=\"/"+u+"\">", "</a>", u });
			} catch (InsertException e) {
				HTMLNode infobox = contentNode.addChild(ctx.getPageMaker().getInfobox("infobox-error", l10n("insertFailedTitle")));
				content = ctx.getPageMaker().getContentNode(infobox);
				content.addChild("#", l10n("insertFailedWithMessage", "message", e.getMessage()));
				content.addChild("br");
				if (e.uri != null) {
					content.addChild("#", l10n("uriWouldHaveBeen", "uri", e.uri.toString()));
				}
				int mode = e.getMode();
				if((mode == InsertException.FATAL_ERRORS_IN_BLOCKS) || (mode == InsertException.TOO_MANY_RETRIES_IN_BLOCKS)) {
					content.addChild("br"); /* TODO */
					content.addChild("#", l10n("splitfileErrorLabel"));
					content.addChild("pre", e.errorCodes.toVerboseString());
				}
			}
			
			content.addChild("br");
			addHomepageLink(content);
			
			writeHTMLReply(ctx, 200, "OK", pageNode.generate());
			request.freeParts();
			bucket.free();
		}else if (request.isPartSet("shutdownconfirm")) {
			if(noPassword) {
				redirectToRoot(ctx);
				return;
			}
			MultiValueTable headers = new MultiValueTable();
			headers.put("Location", "/?terminated&formPassword=" + core.formPassword);
			ctx.sendReplyHeaders(302, "Found", headers, null, 0);
			node.ps.queueTimedJob(new Runnable(){
				public void run() {
					node.exit("Shutdown from fproxy");					
				}
			}, 1);
			return;
		}else if(request.isPartSet("restartconfirm")){
			if(noPassword) {
				redirectToRoot(ctx);
				return;
			}

			MultiValueTable headers = new MultiValueTable();
			headers.put("Location", "/?restarted&formPassword=" + core.formPassword);
			ctx.sendReplyHeaders(302, "Found", headers, null, 0);
			node.ps.queueTimedJob(new Runnable(){
				public void run() {
					node.getNodeStarter().restart();					
				}
			}, 1);
			return;
		}else {
			redirectToRoot(ctx);
		}
	}
	
	private void finInputBoxRow(HTMLNode table, String name, String label, String message) {
		HTMLNode row = table.addChild("tr");
		HTMLNode cell = row.addChild("td");
		// FIXME this should be in the CSS, not the generated code
		HTMLNode right = cell.addChild("div", "align", "right");
		HTMLNode bold = right.addChild("b");
		HTMLNode font = bold.addChild("font", "size", "-1");
		font.addChild("#", label);
		cell = row.addChild("td");
		cell.addChild("textarea", new String[] { "name", "rows", "cols" },
				new String[] { name, "12", "80" }).addChild("#", message);
	}

	private void finInputRow(HTMLNode table, String name, String label, String message) {
		HTMLNode row = table.addChild("tr");
		HTMLNode cell = row.addChild("td");
		// FIXME this should be in the CSS, not the generated code
		HTMLNode right = cell.addChild("div", "align", "right");
		HTMLNode bold = right.addChild("b");
		HTMLNode font = bold.addChild("font", "size", "-1");
		font.addChild("#", label);
		cell = row.addChild("td");
		cell.addChild("input", new String[] { "type", "name", "size", "value" }, 
				new String[] { "text", name, "30", message });
	}

	public void handleGet(URI uri, HTTPRequest request, ToadletContext ctx) throws ToadletContextClosedException, IOException {
		boolean advancedModeOutputEnabled = core.getToadletContainer().isAdvancedModeEnabled();
	
		if(ctx.isAllowedFullAccess()) {

			if(request.isParameterSet("latestlog")) {
				final File logs = new File(node.config.get("logger").getString("dirname") + File.separator + "freenet-latest.log");
				
				this.writeHTMLReply(ctx, 200, "OK", FileUtil.readUTF(logs));
				return;
			} else if (request.isParameterSet("terminated")) {
				if((!request.isParameterSet("formPassword")) || !request.getParam("formPassword").equals(core.formPassword)) {
					redirectToRoot(ctx);
					return;
				}
				// Tell the user that the node is shutting down
				HTMLNode pageNode = ctx.getPageMaker().getPageNode("Node Shutdown", false, ctx);
				HTMLNode contentNode = ctx.getPageMaker().getContentNode(pageNode);
				HTMLNode infobox = contentNode.addChild(ctx.getPageMaker().getInfobox("infobox-information", l10n("shutdownDone")));
				HTMLNode infoboxContent = ctx.getPageMaker().getContentNode(infobox);
				infoboxContent.addChild("#", l10n("thanks"));
				this.writeHTMLReply(ctx, 200, "OK", pageNode.generate());
				return;
			} else if (request.isParameterSet("restarted")) {
				if((!request.isParameterSet("formPassword")) || !request.getParam("formPassword").equals(core.formPassword)) {
					redirectToRoot(ctx);
					return;
				}
				// Tell the user that the node is restarting
				HTMLNode pageNode = ctx.getPageMaker().getPageNode("Node Restart", false, ctx);
				HTMLNode contentNode = ctx.getPageMaker().getContentNode(pageNode);
				HTMLNode infobox = contentNode.addChild(ctx.getPageMaker().getInfobox("infobox-information", l10n("restartingTitle")));
				HTMLNode infoboxContent = ctx.getPageMaker().getContentNode(infobox);
				infoboxContent.addChild("#", l10n("restarting"));
				writeHTMLReply(ctx, 200, "OK", pageNode.generate());
				Logger.normal(this, "Node is restarting");
				return;
			} else if (request.getParam("newbookmark").length() > 0) {
				HTMLNode pageNode = ctx.getPageMaker().getPageNode(l10n("confirmAddBookmarkTitle"), ctx);
				HTMLNode contentNode = ctx.getPageMaker().getContentNode(pageNode);
				HTMLNode infobox = contentNode.addChild(ctx.getPageMaker().getInfobox(l10n("confirmAddBookmarkSubTitle")));
				HTMLNode addForm = ctx.addFormChild(ctx.getPageMaker().getContentNode(infobox), "/bookmarkEditor/", "editBookmarkForm");
				addForm.addChild("#", l10n("confirmAddBookmarkWithKey", "key", request.getParam("newbookmark")));
				addForm.addChild("br");
				String key  = request.getParam("newbookmark");
				if(key.startsWith("freenet:"))
					key = key.substring(8);
				addForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "key", key});
				addForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "text", "name", request.getParam("desc") });
				addForm.addChild("input", new String[] {"type", "name", "value"}, new String[] {"hidden", "bookmark", "/"});
				addForm.addChild("input", new String[] {"type", "name", "value"}, new String[] {"hidden", "action", "addItem"});
				addForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "addbookmark", L10n.getString("BookmarkEditorToadlet.addBookmark") });
				this.writeHTMLReply(ctx, 200, "OK", pageNode.generate());
				return;
			} else if (request.getParam(GenericReadFilterCallback.magicHTTPEscapeString).length() > 0) {
				HTMLNode pageNode = ctx.getPageMaker().getPageNode( l10n("confirmExternalLinkTitle"), ctx);
				HTMLNode contentNode = ctx.getPageMaker().getContentNode(pageNode);
				HTMLNode warnbox = contentNode.addChild(ctx.getPageMaker().getInfobox("infobox-warning", l10n("confirmExternalLinkSubTitle")));
				HTMLNode externalLinkForm = ctx.addFormChild(ctx.getPageMaker().getContentNode(warnbox), "/", "confirmExternalLinkForm");

				final String target = request.getParam(GenericReadFilterCallback.magicHTTPEscapeString);
				externalLinkForm.addChild("#", l10n("confirmExternalLinkWithURL", "url", target));
				externalLinkForm.addChild("br");
				externalLinkForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", GenericReadFilterCallback.magicHTTPEscapeString, target });
				externalLinkForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "cancel", L10n.getString("Toadlet.cancel") });
				externalLinkForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "Go", l10n("goToExternalLink") });
				this.writeHTMLReply(ctx, 200, "OK", pageNode.generate());
				return;
			} else if (request.isParameterSet("exit")) {
				HTMLNode pageNode = ctx.getPageMaker().getPageNode(l10n("shutdownConfirmTitle"), ctx);
				HTMLNode contentNode = ctx.getPageMaker().getContentNode(pageNode);
				HTMLNode infobox = contentNode.addChild(ctx.getPageMaker().getInfobox("infobox-query", l10n("shutdownConfirmTitle")));
				HTMLNode content = ctx.getPageMaker().getContentNode(infobox);
				content.addChild("p").addChild("#", l10n("shutdownConfirm"));
				HTMLNode shutdownForm = ctx.addFormChild(content.addChild("p"), "/", "confirmShutdownForm");
				shutdownForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "cancel", L10n.getString("Toadlet.cancel") });
				shutdownForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "shutdownconfirm", l10n("shutdown") });
				writeHTMLReply(ctx, 200, "OK", pageNode.generate());
				return;
			}else if (request.isParameterSet("restart")) {
				HTMLNode pageNode = ctx.getPageMaker().getPageNode(l10n("restartConfirmTitle"), ctx);
				HTMLNode contentNode = ctx.getPageMaker().getContentNode(pageNode);
				HTMLNode infobox = contentNode.addChild(ctx.getPageMaker().getInfobox("infobox-query", l10n("restartConfirmTitle")));
				HTMLNode content = ctx.getPageMaker().getContentNode(infobox);
				content.addChild("p").addChild("#", l10n("restartConfirm"));
				HTMLNode restartForm = ctx.addFormChild(content.addChild("p"), "/", "confirmRestartForm");
				restartForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "cancel", L10n.getString("Toadlet.cancel") });
				restartForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "restartconfirm", l10n("restart") });
				writeHTMLReply(ctx, 200, "OK", pageNode.generate());
				return;
			}
		}

		HTMLNode pageNode = ctx.getPageMaker().getPageNode(l10n("homepageFullTitleWithName", "name", node.getMyName()), ctx);
		HTMLNode contentNode = ctx.getPageMaker().getContentNode(pageNode);

		if(node.isTestnetEnabled()) {
			HTMLNode testnetBox = contentNode.addChild(ctx.getPageMaker().getInfobox("infobox-alert", l10n("testnetWarningTitle")));
			HTMLNode testnetContent = ctx.getPageMaker().getContentNode(testnetBox);
			testnetContent.addChild("#", l10n("testnetWarning"));
		}

		String useragent = (String)ctx.getHeaders().get("user-agent");

		if (useragent != null) {
			useragent = useragent.toLowerCase();
			if ((useragent.indexOf("msie") > -1) && (useragent.indexOf("opera") == -1)) {
				HTMLNode browserWarningBox = contentNode.addChild(ctx.getPageMaker().getInfobox("infobox-alert", l10n("ieWarningTitle")));
				HTMLNode browserWarningContent = ctx.getPageMaker().getContentNode(browserWarningBox);
				browserWarningContent.addChild("#", l10n("ieWarning"));
			}
		}

		// Alerts
		if(ctx.isAllowedFullAccess())
			contentNode.addChild(core.alerts.createAlerts());

		// Bookmarks
		HTMLNode bookmarkBox = contentNode.addChild("div", "class", "infobox infobox-normal");
		HTMLNode bookmarkBoxHeader = bookmarkBox.addChild("div", "class", "infobox-header");
		bookmarkBoxHeader.addChild("#", L10n.getString("BookmarkEditorToadlet.myBookmarksTitle"));
		if(ctx.isAllowedFullAccess()){
			bookmarkBoxHeader.addChild("#", " [");
			bookmarkBoxHeader.addChild("span", "id", "bookmarkedit").addChild("a", new String[] { "href", "class" }, new String[] { "/bookmarkEditor/", "interfacelink" }, L10n.getString("BookmarkEditorToadlet.edit"));
			bookmarkBoxHeader.addChild("#", "]");
		}

		HTMLNode bookmarkBoxContent = bookmarkBox.addChild("div", "class", "infobox-content");
		HTMLNode bookmarksList = bookmarkBoxContent.addChild("ul", "id", "bookmarks");
		addCategoryToList(bookmarkManager.getMainCategory(), bookmarksList);

		// Fetch-a-key box
		HTMLNode fetchKeyBox = contentNode.addChild(ctx.getPageMaker().getInfobox("infobox-normal", l10n("fetchKeyLabel")));
		HTMLNode fetchKeyContent = ctx.getPageMaker().getContentNode(fetchKeyBox);
		fetchKeyContent.addAttribute("id", "keyfetchbox");
		HTMLNode fetchKeyForm = fetchKeyContent.addChild("form", new String[] { "action", "method" }, new String[] { "/", "get" }).addChild("div");
		fetchKeyForm.addChild("#", l10n("keyRequestLabel")+' ');
		fetchKeyForm.addChild("input", new String[] { "type", "size", "name" }, new String[] { "text", "80", "key" });
		fetchKeyForm.addChild("input", new String[] { "type", "value" }, new String[] { "submit", l10n("fetch") });

		// Version info and Quit Form
		HTMLNode versionBox = contentNode.addChild(ctx.getPageMaker().getInfobox("infobox-information", l10n("versionHeader")));
		HTMLNode versionContent = ctx.getPageMaker().getContentNode(versionBox);
		versionContent.addChild("#", 
				L10n.getString("WelcomeToadlet.version", new String[] { "fullVersion", "build", "rev" }, 
						new String[] { Version.nodeVersion, Integer.toString(Version.buildNumber()), Version.cvsRevision }));
		versionContent.addChild("br");
		if(NodeStarter.extBuildNumber < NodeStarter.RECOMMENDED_EXT_BUILD_NUMBER) {
			versionContent.addChild("#",
					L10n.getString("WelcomeToadlet.extVersionWithRecommended", new String[] { "build", "recbuild", "rev" },
							new String[] { Integer.toString(NodeStarter.extBuildNumber), Integer.toString(NodeStarter.RECOMMENDED_EXT_BUILD_NUMBER), NodeStarter.extRevisionNumber }));
		} else {
			versionContent.addChild("#",
					L10n.getString("WelcomeToadlet.extVersion", new String[] { "build", "rev" },
							new String[] { Integer.toString(NodeStarter.extBuildNumber), NodeStarter.extRevisionNumber }));
		}
		versionContent.addChild("br");
		if(ctx.isAllowedFullAccess()){
			HTMLNode shutdownForm = versionContent.addChild("form", new String[] { "action", "method" }, new String[] { ".", "get" }).addChild("div");
			shutdownForm.addChild("input", new String[] { "type", "name" }, new String[] { "hidden", "exit" });
			shutdownForm.addChild("input", new String[] { "type", "value" }, new String[] { "submit", l10n("shutdownNode") });
			if(node.isUsingWrapper()){
				HTMLNode restartForm = versionContent.addChild("form", new String[] { "action", "method" }, new String[] { ".", "get" }).addChild("div");
				restartForm.addChild("input", new String[] { "type", "name" }, new String[] { "hidden", "restart" });
				restartForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "restart2", l10n("restartNode") });
			}
		}

		// Activity
		HTMLNode activityBox = contentNode.addChild(ctx.getPageMaker().getInfobox("infobox-information", l10n("activityTitle")));
		HTMLNode activityContent = ctx.getPageMaker().getContentNode(activityBox);
		HTMLNode activityList = activityContent.addChild("ul", "id", "activity");
		activityList.addChild("li", l10n("insertCount", "total", Integer.toString(node.getNumInsertSenders())));
		activityList.addChild("li", l10n("requestCount", "total", Integer.toString(node.getNumRequestSenders())));
		activityList.addChild("li", l10n("transferringRequestCount", "total", Integer.toString(node.getNumTransferringRequestSenders())));
		if (advancedModeOutputEnabled) {
			activityList.addChild("li", l10n("arkFetchCount", "total", Integer.toString(node.getNumARKFetchers())));
		}

		this.writeHTMLReply(ctx, 200, "OK", pageNode.generate());
	}
	
	public String supportedMethods() {
		return "GET, POST";
	}
	
	private String l10n(String key) {
		return L10n.getString("WelcomeToadlet."+key);
	}
	
	private String l10n(String key, String pattern, String value) {
		return L10n.getString("WelcomeToadlet."+key, new String[] { pattern }, new String[] { value });
	}
}
