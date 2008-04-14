package freenet.clients.http;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import freenet.client.HighLevelSimpleClient;
import freenet.l10n.L10n;
import freenet.node.Node;
import freenet.node.NodeClientCore;
import freenet.node.useralerts.UserAlert;
import freenet.pluginmanager.AccessDeniedPluginHTTPException;
import freenet.pluginmanager.DownloadPluginHTTPException;
import freenet.pluginmanager.NotFoundPluginHTTPException;
import freenet.pluginmanager.PluginHTTPException;
import freenet.pluginmanager.PluginInfoWrapper;
import freenet.pluginmanager.PluginManager;
import freenet.pluginmanager.RedirectPluginHTTPException;
import freenet.pluginmanager.PluginManager.PluginProgress;
import freenet.support.HTMLNode;
import freenet.support.Logger;
import freenet.support.MultiValueTable;
import freenet.support.TimeUtil;
import freenet.support.api.HTTPRequest;

public class PproxyToadlet extends Toadlet {
	private static final int MAX_PLUGIN_NAME_LENGTH = 1024;
	/** Maximum time to wait for a threaded plugin to exit */
	private static final int MAX_THREADED_UNLOAD_WAIT_TIME = 60*1000;
	private final Node node;
	private final NodeClientCore core;

	public PproxyToadlet(HighLevelSimpleClient client, Node node, NodeClientCore core) {
		super(client);
		this.node = node;
		this.core = core;
	}

	public String supportedMethods() {
		return "GET, POST";
	}

	public void handlePost(URI uri, HTTPRequest request, ToadletContext ctx)
	throws ToadletContextClosedException, IOException {

		MultiValueTable headers = new MultiValueTable();

		String pass = request.getPartAsString("formPassword", 32);
		if((pass == null) || !pass.equals(core.formPassword)) {
			headers.put("Location", "/plugins/");
			ctx.sendReplyHeaders(302, "Found", headers, null, 0);
			return;
		}

		if(!ctx.isAllowedFullAccess()) {
			super.sendErrorPage(ctx, 403, l10n("unauthorizedTitle"), l10n("unauthorized"));
			return;
		}

		String path=request.getPath();

		// remove leading / and plugins/ from path
		if(path.startsWith("/")) path = path.substring(1);
		if(path.startsWith("plugins/")) path = path.substring("plugins/".length());

		if(Logger.shouldLog(Logger.MINOR, this)) Logger.minor(this, "Pproxy received POST on "+path);

		PluginManager pm = node.pluginManager;

		if(path.length()>0)
		{
			try
			{
				String plugin = null;
				// split path into plugin class name and 'daa' path for plugin
				int to = path.indexOf("/");
				if(to == -1)
				{
					plugin = path;
				}
				else
				{
					plugin = path.substring(0, to);
				}

				writeHTMLReply(ctx, 200, "OK", pm.handleHTTPPost(plugin, request));
			}
			catch (RedirectPluginHTTPException e) {
				writeTemporaryRedirect(ctx, e.message, e.newLocation);
			}
			catch (NotFoundPluginHTTPException e) {
				sendErrorPage(ctx, NotFoundPluginHTTPException.code, e.message, e.location);
			}
			catch (AccessDeniedPluginHTTPException e) {
				sendErrorPage(ctx, AccessDeniedPluginHTTPException.code, e.message, e.location);
			}
			catch (DownloadPluginHTTPException e) {
				// FIXME: maybe it ought to be defined like sendErrorPage : in toadlets

				MultiValueTable head = new MultiValueTable();
				head.put("Content-Disposition", "attachment; filename=\"" + e.filename + '"');
				ctx.sendReplyHeaders(DownloadPluginHTTPException.CODE, "Found", head, e.mimeType, e.data.length);
				ctx.writeData(e.data);
			}
			catch(PluginHTTPException e)
			{
				sendErrorPage(ctx, PluginHTTPException.code, e.message, e.location);
			}
			catch(Throwable t)
			{
				writeInternalError(t, ctx);
			}
		}
		else
		{
			PageMaker pageMaker = ctx.getPageMaker();

			if (request.isPartSet("submit-official") || request.isPartSet("submit-other")) {
				String pluginName = null;
				if (request.isPartSet("submit-official")) {
					pluginName = request.getPartAsString("plugin-name", 40);
				} else {
					pluginName = request.getPartAsString("plugin-url", 200);
				}
				pm.startPlugin(pluginName, true);
				headers.put("Location", ".");
				ctx.sendReplyHeaders(302, "Found", headers, null, 0);
				return;
			}
			if (request.isPartSet("dismiss-user-alert")) {
				int userAlertHashCode = request.getIntPart("disable", -1);
				core.alerts.dismissAlert(userAlertHashCode);
				headers.put("Location", ".");
				ctx.sendReplyHeaders(302, "Found", headers, null, 0);
				return;
			}
			if (request.isPartSet("cancel")){
				headers.put("Location", "/plugins/");
				ctx.sendReplyHeaders(302, "Found", headers, null, 0);
				return;
			}
			if (request.getPartAsString("unloadconfirm", MAX_PLUGIN_NAME_LENGTH).length() > 0) {
				String pluginThreadName = request.getPartAsString("unloadconfirm", MAX_PLUGIN_NAME_LENGTH);
				String pluginSpecification = getPluginSpecification(pm, pluginThreadName);
				pm.killPlugin(pluginThreadName, MAX_THREADED_UNLOAD_WAIT_TIME);
				if (request.isPartSet("purge")) {
					pm.removeCachedCopy(pluginSpecification);
				}
				HTMLNode pageNode = pageMaker.getPageNode(l10n("plugins"), ctx);
				HTMLNode contentNode = pageMaker.getContentNode(pageNode);
				HTMLNode infobox = contentNode.addChild("div", "class", "infobox infobox-success");
				infobox.addChild("div", "class", "infobox-header", l10n("pluginUnloaded"));
				HTMLNode infoboxContent = infobox.addChild("div", "class", "infobox-content");
				infoboxContent.addChild("#", l10n("pluginUnloadedWithName", "name", pluginThreadName));
				infoboxContent.addChild("br");
				infoboxContent.addChild("a", "href", "/plugins/", l10n("returnToPluginPage"));
				writeHTMLReply(ctx, 200, "OK", pageNode.generate());
				return;
			}if (request.getPartAsString("unload", MAX_PLUGIN_NAME_LENGTH).length() > 0) {
				HTMLNode pageNode = pageMaker.getPageNode(l10n("plugins"), ctx);
				HTMLNode contentNode = pageMaker.getContentNode(pageNode);
				HTMLNode infobox = contentNode.addChild("div", "class", "infobox infobox-query");
				infobox.addChild("div", "class", "infobox-header", l10n("unloadPluginTitle"));
				HTMLNode infoboxContent = infobox.addChild("div", "class", "infobox-content");
				infoboxContent.addChild("#", l10n("unloadPluginWithName", "name", request.getPartAsString("unload", MAX_PLUGIN_NAME_LENGTH)));
				HTMLNode unloadForm = 
					ctx.addFormChild(infoboxContent, "/plugins/", "unloadPluginConfirmForm");
				unloadForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "unloadconfirm", request.getPartAsString("unload", MAX_PLUGIN_NAME_LENGTH) });
				HTMLNode tempNode = unloadForm.addChild("p");
				tempNode.addChild("input", new String[] { "type", "name" }, new String[] { "checkbox", "purge" });
				tempNode.addChild("#", l10n("unloadPurge"));
				tempNode = unloadForm.addChild("p");
				tempNode.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "confirm", l10n("unload") });
				tempNode.addChild("#", " ");
				tempNode.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "cancel", L10n.getString("Toadlet.cancel") });
				writeHTMLReply(ctx, 200, "OK", pageNode.generate());
				return;
			} else if (request.getPartAsString("reload", MAX_PLUGIN_NAME_LENGTH).length() > 0) {
				HTMLNode pageNode = pageMaker.getPageNode(l10n("plugins"), ctx);
				HTMLNode contentNode = pageMaker.getContentNode(pageNode);
				HTMLNode reloadBox = contentNode.addChild(pageMaker.getInfobox("infobox infobox-query", l10n("reloadPluginTitle")));
				HTMLNode reloadContent = pageMaker.getContentNode(reloadBox);
				reloadContent.addChild("p", l10n("reloadExplanation"));
				reloadContent.addChild("p", l10n("reloadWarning"));
				HTMLNode reloadForm = ctx.addFormChild(reloadContent, "/plugins/", "reloadPluginConfirmForm");
				reloadForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "reloadconfirm", request.getPartAsString("reload", MAX_PLUGIN_NAME_LENGTH) });
				HTMLNode tempNode = reloadForm.addChild("p");
				tempNode.addChild("input", new String[] { "type", "name" }, new String[] { "checkbox", "purge" });
				tempNode.addChild("#", l10n("reloadPurgeWarning"));
				tempNode = reloadForm.addChild("p");
				tempNode.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "confirm", l10n("reload") });
				tempNode.addChild("#", " ");
				tempNode.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "cancel", L10n.getString("Toadlet.cancel") });
				
				writeHTMLReply(ctx, 200, "OK", pageNode.generate());
				return;
			}else if (request.getPartAsString("reloadconfirm", MAX_PLUGIN_NAME_LENGTH).length() > 0) {
				boolean purge = request.isPartSet("purge");
				String pluginThreadName = request.getPartAsString("reloadconfirm", MAX_PLUGIN_NAME_LENGTH);
				String fn = getPluginSpecification(pm, pluginThreadName);

				if (fn == null) {
					sendErrorPage(ctx, 404, l10n("pluginNotFoundReloadTitle"), 
							L10n.getString("PluginToadlet.pluginNotFoundReload"));
				} else {
					pm.killPlugin(pluginThreadName, MAX_THREADED_UNLOAD_WAIT_TIME);
					if (purge) {
						pm.removeCachedCopy(fn);
					}
					pm.startPlugin(fn, true);

					headers.put("Location", ".");
					ctx.sendReplyHeaders(302, "Found", headers, null, 0);
				}
				return;
			}else {
				// Ignore
				headers.put("Location", ".");
				ctx.sendReplyHeaders(302, "Found", headers, null, 0);
			}

		}

	}

	/**
	 * Searches all plugins for the plugin with the given thread name and
	 * returns the plugin specification used to load the plugin.
	 * 
	 * @param pluginManager
	 *            The plugin manager
	 * @param pluginThreadName
	 *            The thread name of the plugin
	 * @return The plugin specification of the plugin, or <code>null</code> if
	 *         no plugin was found
	 */
	private String getPluginSpecification(PluginManager pluginManager, String pluginThreadName) {
		Iterator it = pluginManager.getPlugins().iterator();
		while (it.hasNext()) {
			PluginInfoWrapper pi = (PluginInfoWrapper) it.next();
			if (pi.getThreadName().equals(pluginThreadName)) {
				return pi.getFilename();
			}
		}
		return null;
	}

	private String l10n(String key, String pattern, String value) {
		return L10n.getString("PproxyToadlet."+key, new String[] { pattern }, new String[] { value });
	}

	private String l10n(String key) {
		return L10n.getString("PproxyToadlet."+key);
	}

	public void handleGet(URI uri, HTTPRequest request, ToadletContext ctx)
	throws ToadletContextClosedException, IOException {

		//String basepath = "/plugins/";
		String path = request.getPath();

		// remove leading / and plugins/ from path
		if(path.startsWith("/")) path = path.substring(1);
		if(path.startsWith("plugins/")) path = path.substring("plugins/".length());

		PluginManager pm = node.pluginManager;

		if(Logger.shouldLog(Logger.MINOR, this))
			Logger.minor(this, "Pproxy fetching "+path);
		try {
			if (path.equals("")) {
				if (!ctx.isAllowedFullAccess()) {
					super.sendErrorPage(ctx, 403, "Unauthorized", L10n.getString("Toadlet.unauthorized"));
					return;
				}

				Iterator/* <PluginProgress> */loadingPlugins = pm.getStartingPlugins().iterator();

				HTMLNode pageNode = ctx.getPageMaker().getPageNode(l10n("pluginsWithNodeName", "name", core.getMyName()), ctx);
				if (loadingPlugins.hasNext()) {
					/* okay, add a refresh. */
					HTMLNode headNode = ctx.getPageMaker().getHeadNode(pageNode);
					headNode.addChild("meta", new String[] { "http-equiv", "content" }, new String[] { "refresh", "10; url=" });
				}
				HTMLNode contentNode = ctx.getPageMaker().getContentNode(pageNode);

				contentNode.addChild(core.alerts.createSummary());

				UserAlert[] userAlerts = core.alerts.getAlerts();
				for (int index = 0, count = userAlerts.length; index < count; index++) {
					UserAlert userAlert = userAlerts[index];
					if (userAlert.isValid() && (userAlert.getUserIdentifier() == PluginManager.class)) {
						contentNode.addChild(core.alerts.renderAlert(userAlert));
					}
				}
				
				/* find which plugins have already been loaded. */
				List/*<String>*/ availablePlugins = findAvailablePlugins();
				Iterator/*<PluginInfoWrapper>*/ loadedPlugins = pm.getPlugins().iterator();
				while (loadedPlugins.hasNext()) {
					PluginInfoWrapper pluginInfoWrapper = (PluginInfoWrapper) loadedPlugins.next();
					String pluginName = pluginInfoWrapper.getPluginClassName();
					String shortPluginName = pluginName.substring(pluginName.lastIndexOf('.') + 1);
					availablePlugins.remove(shortPluginName);
				}
				while (loadingPlugins.hasNext()) {
					PluginProgress pluginProgress = (PluginProgress) loadingPlugins.next();
					String pluginName = pluginProgress.getName();
					availablePlugins.remove(pluginName);
				}

				showStartingPlugins(pm, contentNode);
				showPluginList(ctx, pm, contentNode);
				showOfficialPluginLoader(ctx, contentNode, availablePlugins);
				showUnofficialPluginLoader(ctx, contentNode);

				writeHTMLReply(ctx, 200, "OK", pageNode.generate());
			} else {
				// split path into plugin class name and 'data' path for plugin
				int to = path.indexOf("/");
				String plugin;
				if (to == -1) {
					plugin = path;
				} else {
					plugin = path.substring(0, to);
				}

				// Plugin may need to know where it was accessed from, so it can e.g. produce relative URLs.
				//writeReply(ctx, 200, "text/html", "OK", mkPage("plugin", pm.handleHTTPGet(plugin, data)));
				writeHTMLReply(ctx, 200, "OK", pm.handleHTTPGet(plugin, request));				
			}

			//FetchResult result = fetch(key);
			//writeReply(ctx, 200, result.getMimeType(), "OK", result.asBucket());
		} catch (RedirectPluginHTTPException e) {
			writeTemporaryRedirect(ctx, e.message, e.newLocation);
		} catch (NotFoundPluginHTTPException e) {
			sendErrorPage(ctx, NotFoundPluginHTTPException.code, e.message, e.location);
		} catch (AccessDeniedPluginHTTPException e) {
			sendErrorPage(ctx, AccessDeniedPluginHTTPException.code, e.message, e.location);
		} catch (DownloadPluginHTTPException e) {
			// FIXME: maybe it ought to be defined like sendErrorPage : in toadlets

			MultiValueTable head = new MultiValueTable();
			head.put("Content-Disposition", "attachment; filename=\"" + e.filename + '"');
			ctx.sendReplyHeaders(DownloadPluginHTTPException.CODE, "Found", head, e.mimeType, e.data.length);
			ctx.writeData(e.data);
		} catch(PluginHTTPException e) {
			sendErrorPage(ctx, PluginHTTPException.code, e.message, e.location);
		} catch (Throwable t) {
			ctx.forceDisconnect();
			writeInternalError(t, ctx);
		}
	}

	/**
	 * Returns a list of the names of all available official plugins. Right now
	 * this list is hardcoded but in future we could retrieve this list from emu
	 * or from freenet itself.
	 * 
	 * @return A list of all available plugin names
	 */
	private List/* <String> */findAvailablePlugins() {
		List/* <String> */availablePlugins = new ArrayList/* <String> */();
		availablePlugins.add("Echo");
		availablePlugins.add("Freemail");
		availablePlugins.add("HelloWorld");
		availablePlugins.add("HelloFCP");
		availablePlugins.add("JSTUN");
		availablePlugins.add("KeyExplorer");
		availablePlugins.add("MDNSDiscovery");
		availablePlugins.add("SNMP");
		availablePlugins.add("TestGallery");
		availablePlugins.add("ThawIndexBrowser");
		availablePlugins.add("UPnP");
		availablePlugins.add("XMLLibrarian");
		availablePlugins.add("XMLSpider");
		return availablePlugins;
	}

	/**
	 * Shows a list of all currently loading plugins.
	 * 
	 * @param pluginManager
	 *            The plugin manager
	 * @param contentNode
	 *            The node to add content to
	 */
	private void showStartingPlugins(PluginManager pluginManager, HTMLNode contentNode) {
		Set/*<PluginProgress>*/ startingPlugins = pluginManager.getStartingPlugins();
		if (!startingPlugins.isEmpty()) {
			HTMLNode startingPluginsBox = contentNode.addChild("div", "class", "infobox infobox-normal");
			startingPluginsBox.addChild("div", "class", "infobox-header", l10n("startingPluginsTitle"));
			HTMLNode startingPluginsContent = startingPluginsBox.addChild("div", "class", "infobox-content");
			HTMLNode startingPluginsTable = startingPluginsContent.addChild("table");
			HTMLNode startingPluginsHeader = startingPluginsTable.addChild("tr");
			startingPluginsHeader.addChild("th", l10n("startingPluginName"));
			startingPluginsHeader.addChild("th", l10n("startingPluginStatus"));
			startingPluginsHeader.addChild("th", l10n("startingPluginTime"));
			Iterator/*<PluginProgress>*/ startingPluginsIterator = startingPlugins.iterator();
			while (startingPluginsIterator.hasNext()) {
				PluginProgress pluginProgress = (PluginProgress) startingPluginsIterator.next();
				HTMLNode startingPluginsRow = startingPluginsTable.addChild("tr");
				startingPluginsRow.addChild("td", pluginProgress.getName());
				startingPluginsRow.addChild("td", l10n("startingPluginStatus." + pluginProgress.getProgress().toString()));
				startingPluginsRow.addChild("td", "aligh", "right", TimeUtil.formatTime(pluginProgress.getTime()));
			}
		}
	}

	private void showPluginList(ToadletContext ctx, PluginManager pm, HTMLNode contentNode) throws ToadletContextClosedException, IOException {
		HTMLNode infobox = contentNode.addChild("div", "class", "infobox infobox-normal");
		infobox.addChild("div", "class", "infobox-header", L10n.getString("PluginToadlet.pluginListTitle"));
		HTMLNode infoboxContent = infobox.addChild("div", "class", "infobox-content");
		if (pm.getPlugins().isEmpty()) {
			infoboxContent.addChild("div", l10n("noPlugins"));
		} else {
			HTMLNode pluginTable = infoboxContent.addChild("table", "class", "plugins");
			HTMLNode headerRow = pluginTable.addChild("tr");
			headerRow.addChild("th", l10n("classNameTitle"));
			headerRow.addChild("th", l10n("versionTitle"));
			headerRow.addChild("th", l10n("internalIDTitle"));
			headerRow.addChild("th", l10n("startedAtTitle"));
			headerRow.addChild("th");
			headerRow.addChild("th");
			headerRow.addChild("th");
			Iterator it = pm.getPlugins().iterator();
			while (it.hasNext()) {
				PluginInfoWrapper pi = (PluginInfoWrapper) it.next();
				HTMLNode pluginRow = pluginTable.addChild("tr");
				pluginRow.addChild("td", pi.getPluginClassName());
				pluginRow.addChild("td", pi.getPluginVersion());
				pluginRow.addChild("td", pi.getThreadName());
				pluginRow.addChild("td", new Date(pi.getStarted()).toString());
				if (pi.isStopping()) {
					pluginRow.addChild("td", l10n("pluginStopping"));
					/* add two empty cells. */
					pluginRow.addChild("td");
					pluginRow.addChild("td");
				} else {
					if (pi.isPproxyPlugin()) {
						HTMLNode visitForm = pluginRow.addChild("td").addChild("form", new String[] { "method", "action", "target" }, new String[] { "get", pi.getPluginClassName(), "_new" });
						visitForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "formPassword", core.formPassword });
						visitForm.addChild("input", new String[] { "type", "value" }, new String[] { "submit", L10n.getString("PluginToadlet.visit") });
					}
					HTMLNode unloadForm = ctx.addFormChild(pluginRow.addChild("td"), ".", "unloadPluginForm");
					unloadForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "unload", pi.getThreadName() });
					unloadForm.addChild("input", new String[] { "type", "value" }, new String[] { "submit", l10n("unload") });
					HTMLNode reloadForm = ctx.addFormChild(pluginRow.addChild("td"), ".", "reloadPluginForm");
					reloadForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "reload", pi.getThreadName() });
					reloadForm.addChild("input", new String[] { "type", "value" }, new String[] { "submit", l10n("reload") });
				}
			}
		}
	}
	
	private void showOfficialPluginLoader(ToadletContext toadletContext, HTMLNode contentNode, List/*<String>*/ availablePlugins) {
		/* box for "official" plugins. */
		HTMLNode addOfficialPluginBox = contentNode.addChild("div", "class", "infobox infobox-normal");
		addOfficialPluginBox.addChild("div", "class", "infobox-header", l10n("loadOfficialPlugin"));
		HTMLNode addOfficialPluginContent = addOfficialPluginBox.addChild("div", "class", "infobox-content");
		HTMLNode addOfficialForm = toadletContext.addFormChild(addOfficialPluginContent, ".", "addOfficialPluginForm");
		addOfficialForm.addChild("div", l10n("loadOfficialPluginText"));
		// FIXME CSS-ize this
		addOfficialForm.addChild("p").addChild("b").addChild("font", new String[] { "color" }, new String[] { "red" }, l10n("loadOfficialPluginWarning"));
		addOfficialForm.addChild("#", (l10n("loadOfficialPluginLabel") + ": "));
		HTMLNode selectNode = addOfficialForm.addChild("select", "name", "plugin-name");
		Iterator/*<String>*/ availablePluginIterator = availablePlugins.iterator();
		while (availablePluginIterator.hasNext()) {
			String pluginName = (String) availablePluginIterator.next();
			selectNode.addChild("option", "value", pluginName, pluginName);
		}
		addOfficialForm.addChild("#", " ");
		addOfficialForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "submit-official", l10n("Load") });
	}
	
	private void showUnofficialPluginLoader(ToadletContext toadletContext, HTMLNode contentNode) {
		/* box for unofficial plugins. */
		HTMLNode addOtherPluginBox = contentNode.addChild("div", "class", "infobox infobox-normal");
		addOtherPluginBox.addChild("div", "class", "infobox-header", l10n("loadOtherPlugin"));
		HTMLNode addOtherPluginContent = addOtherPluginBox.addChild("div", "class", "infobox-content");
		HTMLNode addOtherForm = toadletContext.addFormChild(addOtherPluginContent, ".", "addOtherPluginForm");
		addOtherForm.addChild("div", l10n("loadOtherPluginText"));
		addOtherForm.addChild("#", (l10n("loadOtherURLLabel") + ": "));
		addOtherForm.addChild("input", new String[] { "type", "name", "size" }, new String[] { "text", "plugin-url", "80" });
		addOtherForm.addChild("#", " ");
		addOtherForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "submit-other", l10n("Load") });
	}

}
