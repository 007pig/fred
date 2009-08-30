package freenet.clients.http;

import freenet.node.SecurityLevels;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import freenet.l10n.NodeL10n;
import freenet.node.Node;
import freenet.node.NodeClientCore;
import freenet.node.SecurityLevels.FRIENDS_THREAT_LEVEL;
import freenet.pluginmanager.FredPluginL10n;
import freenet.support.HTMLNode;
import freenet.support.Logger;
import freenet.support.api.HTTPRequest;
import freenet.support.io.FileUtil;

/** Simple class to output standard heads and tail for web interface pages. 
*/
public final class PageMaker {
	
	public enum THEME {
		BOXED("boxed", "Boxed", "", false, false),
		CLEAN("clean", "Clean", "Mr. Proper", false, false),
		CLEAN_DROPDOWN("clean-dropdown", "Clean (Dropdown menu)", "Clean theme with a dropdown menu.", false, false),
		CLEAN_STATIC("clean-static", "Clean (Static menu)", "Clean theme with a static menu.", false, false),
		GRAYANDBLUE("grayandblue", "Gray And Blue", "", false, false),
		SKY("sky", "Sky", "", false, false),
		MINIMALBLUE("minimalblue", "Minimal Blue", "A minimalistic theme in blue", false, false),
		MINIMALISTIC("minimalist", "Minimalistic", "A very minimalistic theme based on Google's designs", true, true);

		
		public static final String[] possibleValues = {
			BOXED.code,
			CLEAN.code,
			CLEAN_DROPDOWN.code,
			CLEAN_STATIC.code,
			GRAYANDBLUE.code,
			SKY.code,
			MINIMALBLUE.code,
			MINIMALISTIC.code
		};
		
		public final String code;  // the internal name
		public final String name;  // the name in "human form"
		public final String description; // description
		/**
		 * If true, the activelinks will appear on the welcome page, whether
		 * the user has enabled them or not.
		 */
		public final boolean forceActivelinks;
		/**
		 * If true, the "Fetch a key" infobox will appear above the bookmarks
		 * infobox on the welcome page.
		 */
		public final boolean fetchKeyBoxAboveBookmarks;
		
		private THEME(String code, String name, String description) {
			this(code, name, description, false, false);
		}

		private THEME(String code, String name, String description, boolean forceActivelinks, boolean fetchKeyBoxAboveBookmarks) {
			this.code = code;
			this.name = name;
			this.description = description;
			this.forceActivelinks = forceActivelinks;
			this.fetchKeyBoxAboveBookmarks = fetchKeyBoxAboveBookmarks;
		}

		public static THEME themeFromName(String cssName) {
			for(THEME t : THEME.values()) {
				if(t.code.equalsIgnoreCase(cssName) ||
				   t.name.equalsIgnoreCase(cssName))
				{
					return t;
				}
			}
			return getDefault();
		}

		public static THEME getDefault() {
			return THEME.CLEAN;
		}
	}	
	
	public static final int MODE_SIMPLE = 1;
	public static final int MODE_ADVANCED = 2;
	private THEME theme;
	private File override;
	private final Node node;
	
	private List<SubMenu> menuList = new ArrayList<SubMenu>();
	private Map<String, SubMenu> subMenus = new HashMap<String, SubMenu>();
	
	private class SubMenu {
		
		/** Name of the submenu */
		private final String navigationLinkText;
		/** Link if the user clicks on the submenu itself */
		private final String defaultNavigationLink;
		/** Tooltip */
		private final String defaultNavigationLinkTitle;
		
		private final FredPluginL10n plugin;
		
		private final List<String> navigationLinkTexts = new ArrayList<String>();
		private final List<String> navigationLinkTextsNonFull = new ArrayList<String>();
		private final Map<String, String> navigationLinkTitles = new HashMap<String, String>();
		private final Map<String, String> navigationLinks = new HashMap<String, String>();
		private final Map<String, LinkEnabledCallback>  navigationLinkCallbacks = new HashMap<String, LinkEnabledCallback>();
		
		public SubMenu(String link, String name, String title, FredPluginL10n plugin) {
			this.navigationLinkText = name;
			this.defaultNavigationLink = link;
			this.defaultNavigationLinkTitle = title;
			this.plugin = plugin;
		}

		public void addNavigationLink(String path, String name, String title, boolean fullOnly, LinkEnabledCallback cb) {
			navigationLinkTexts.add(name);
			if(!fullOnly)
				navigationLinkTextsNonFull.add(name);
			navigationLinkTitles.put(name, title);
			navigationLinks.put(name, path);
			if(cb != null)
				navigationLinkCallbacks.put(name, cb);
		}

		@Deprecated
		public void removeNavigationLink(String name) {
			navigationLinkTexts.remove(name);
			navigationLinkTextsNonFull.remove(name);
			navigationLinkTitles.remove(name);
			navigationLinks.remove(name);
		}

		@Deprecated
		public void removeAllNavigationLinks() {
			navigationLinkTexts.clear();
			navigationLinkTextsNonFull.clear();
			navigationLinkTitles.clear();
			navigationLinks.clear();
		}
	}
	
	protected PageMaker(THEME t, Node n) {
		setTheme(t);
		this.node = n;
	}
	
	void setOverride(File f) {
		this.override = f;
	}
	
	public void setTheme(THEME theme2) {
		if (theme2 == null) {
			this.theme = THEME.getDefault();
		} else {
			URL themeurl = getClass().getResource("staticfiles/themes/" + theme2.code + "/theme.css");
			if (themeurl == null)
				this.theme = THEME.getDefault();
			else
				this.theme = theme2;
		}
	}

	public void addNavigationCategory(String link, String name, String title, FredPluginL10n plugin) {
		SubMenu menu = new SubMenu(link, name, title, plugin);
		subMenus.put(name, menu);
		menuList.add(menu);
	}
	

	public void removeNavigationCategory(String name) {
		SubMenu menu = subMenus.remove(name);
		if (menu == null) {
			Logger.error(this, "can't remove navigation category, name="+name);
			return;
		}	
		menuList.remove(menu);
	}
	
	public void addNavigationLink(String menutext, String path, String name, String title, boolean fullOnly, LinkEnabledCallback cb) {
		SubMenu menu = subMenus.get(menutext);
		menu.addNavigationLink(path, name, title, fullOnly, cb);
	}
	
	/* FIXME: Implement a proper way for chosing what the menu looks like upon handleHTTPGet/Post */
	@Deprecated
	public void removeNavigationLink(String menutext, String name) {
		SubMenu menu = subMenus.get(menutext);
		menu.removeNavigationLink(name);
	}
	
	@Deprecated
	public void removeAllNavigationLinks() {
		for(SubMenu menu : subMenus.values())
			menu.removeAllNavigationLinks();
	}
	
	public HTMLNode createBackLink(ToadletContext toadletContext, String name) {
		String referer = toadletContext.getHeaders().get("referer");
		if (referer != null) {
			return new HTMLNode("a", new String[] { "href", "title" }, new String[] { referer, name }, name);
		}
		return new HTMLNode("a", new String[] { "href", "title" }, new String[] { "javascript:back()", name }, name);
	}
	
	public PageNode getPageNode(String title, ToadletContext ctx) {
		return getPageNode(title, true, ctx);
	}

	public PageNode getPageNode(String title, boolean renderNavigationLinks, ToadletContext ctx) {
		boolean fullAccess = ctx == null ? false : ctx.isAllowedFullAccess();
		HTMLNode pageNode = new HTMLNode.HTMLDoctype("html", "-//W3C//DTD XHTML 1.1//EN");
		HTMLNode htmlNode = pageNode.addChild("html", "xml:lang", NodeL10n.getBase().getSelectedLanguage().isoCode);
		HTMLNode headNode = htmlNode.addChild("head");
		headNode.addChild("meta", new String[] { "http-equiv", "content" }, new String[] { "Content-Type", "text/html; charset=utf-8" });
		headNode.addChild("title", title + " - Freenet");
		if(override == null)
			headNode.addChild("link", new String[] { "rel", "href", "type", "title" }, new String[] { "stylesheet", "/static/themes/" + theme.code + "/theme.css", "text/css", theme.code });
		else
			headNode.addChild(getOverrideContent());
		for (THEME t: THEME.values()) {
			String themeName = t.code;
			headNode.addChild("link", new String[] { "rel", "href", "type", "media", "title" }, new String[] { "alternate stylesheet", "/static/themes/" + themeName + "/theme.css", "text/css", "screen", themeName });
		}
		
		Toadlet t;
		if (ctx != null) {
			t = ctx.activeToadlet();
			t = t.showAsToadlet();
		} else
			t = null;
		String activePath = "";
		if(t != null) activePath = t.path();
		HTMLNode bodyNode = htmlNode.addChild("body");
		HTMLNode pageDiv = bodyNode.addChild("div", "id", "page");
		HTMLNode topBarDiv = pageDiv.addChild("div", "id", "topbar");

		final HTMLNode statusBarDiv = pageDiv.addChild("div", "id", "statusbar-container").addChild("div", "id", "statusbar");

		if (node != null && node.clientCore != null) {
			final HTMLNode alerts = node.clientCore.alerts.createSummary(true);
			if (alerts != null) {
				statusBarDiv.addChild(alerts).addAttribute("id", "statusbar-alerts");
				statusBarDiv.addChild("div", "class", "separator", "\u00a0");
			}
		}

		statusBarDiv.addChild("div", "id", "statusbar-language").addChild("a", "href", "/config/node#l10n", NodeL10n.getBase().getSelectedLanguage().fullName);

		if (node.clientCore != null && ctx != null) {
			statusBarDiv.addChild("div", "class", "separator", "\u00a0");
			final HTMLNode switchMode = statusBarDiv.addChild("div", "id", "statusbar-switchmode");
			if (ctx.activeToadlet().container.isAdvancedModeEnabled()) {
				switchMode.addAttribute("class", "simple");
				switchMode.addChild("a", "href", "?mode=1", NodeL10n.getBase().getString("StatusBar.switchToSimpleMode"));
			} else {
				switchMode.addAttribute("class", "advanced");
				switchMode.addChild("a", "href", "?mode=2", NodeL10n.getBase().getString("StatusBar.switchToAdvancedMode"));
			}
		}

		if (node != null && node.clientCore != null) {
			statusBarDiv.addChild("div", "class", "separator", "\u00a0");
			final HTMLNode secLevels = statusBarDiv.addChild("div", "id", "statusbar-seclevels", NodeL10n.getBase().getString("SecurityLevels.statusBarPrefix"));

			final HTMLNode network = secLevels.addChild("a", "href", "/seclevels/", SecurityLevels.localisedName(node.securityLevels.getNetworkThreatLevel()));
			network.addAttribute("title", NodeL10n.getBase().getString("SecurityLevels.networkThreatLevelShort"));
			network.addAttribute("class", node.securityLevels.getNetworkThreatLevel().toString().toLowerCase());

			final HTMLNode friends = secLevels.addChild("a", "href", "/seclevels/", SecurityLevels.localisedName(node.securityLevels.getFriendsThreatLevel()));
			friends.addAttribute("title", NodeL10n.getBase().getString("SecurityLevels.friendsThreatLevelShort"));
			friends.addAttribute("class", node.securityLevels.getFriendsThreatLevel().toString().toLowerCase());

			final HTMLNode physical = secLevels.addChild("a", "href", "/seclevels/", SecurityLevels.localisedName(node.securityLevels.getPhysicalThreatLevel()));
			physical.addAttribute("title", NodeL10n.getBase().getString("SecurityLevels.physicalThreatLevelShort"));
			physical.addAttribute("class", node.securityLevels.getPhysicalThreatLevel().toString().toLowerCase());

			statusBarDiv.addChild("div", "class", "separator", "\u00a0");

			final int connectedPeers = node.peers.countConnectedPeers();
			final HTMLNode peers = statusBarDiv.addChild("div", "id", "statusbar-peers", connectedPeers + " Peers");

			if (connectedPeers == 0) {
				peers.addAttribute("class", "no-peers");
			} else if (connectedPeers < 4) {
				peers.addAttribute("class", "very-few-peers");
			} else if (connectedPeers < 7) {
				peers.addAttribute("class", "few-peers");
			} else if (connectedPeers < 10) {
				peers.addAttribute("class", "avg-peers");
			} else {
				peers.addAttribute("class", "lots-of-peers");
			}
		}

		topBarDiv.addChild("h1", title);
		if (renderNavigationLinks) {
			SubMenu selected = null;
			HTMLNode navbarDiv = pageDiv.addChild("div", "id", "navbar");
			HTMLNode navbarUl = navbarDiv.addChild("ul", "id", "navlist");
			for (SubMenu menu : menuList) {
				HTMLNode subnavlist = new HTMLNode("ul");
				boolean isSelected = false;
				boolean nonEmpty = false;
				for (String navigationLink :  fullAccess ? menu.navigationLinkTexts : menu.navigationLinkTextsNonFull) {
					LinkEnabledCallback cb = menu.navigationLinkCallbacks.get(navigationLink);
					if(cb != null && !cb.isEnabled(ctx)) continue;
					nonEmpty = true;
					String navigationTitle = menu.navigationLinkTitles.get(navigationLink);
					String navigationPath = menu.navigationLinks.get(navigationLink);
					HTMLNode sublistItem;
					if(activePath.equals(navigationPath)) {
						sublistItem = subnavlist.addChild("li", "class", "submenuitem-selected");
						isSelected = true;
					} else {
						sublistItem = subnavlist.addChild("li");
					}
					if(menu.plugin != null) {
						if(navigationTitle != null) navigationTitle = menu.plugin.getString(navigationTitle);
						if(navigationLink != null) navigationLink = menu.plugin.getString(navigationLink);
					} else {
						if(navigationTitle != null) navigationTitle = NodeL10n.getBase().getString(navigationTitle);
						if(navigationLink != null) navigationLink = NodeL10n.getBase().getString(navigationLink);
					}
					if(navigationTitle != null)
						sublistItem.addChild("a", new String[] { "href", "title" }, new String[] { navigationPath, navigationTitle }, navigationLink);
					else
						sublistItem.addChild("a", "href", navigationPath, navigationLink);
				}
				if(nonEmpty) {
					HTMLNode listItem;
					if(isSelected) {
						selected = menu;
						subnavlist.addAttribute("class", "subnavlist-selected");
						listItem = new HTMLNode("li", "id", "navlist-selected");
					} else {
						subnavlist.addAttribute("class", "subnavlist");
						listItem = new HTMLNode("li");
					}
					String menuItemTitle = menu.defaultNavigationLinkTitle;
					String text = menu.navigationLinkText;
					if(menu.plugin == null) {
						menuItemTitle = NodeL10n.getBase().getString(menuItemTitle);
						text = NodeL10n.getBase().getString(text);
					} else {
						menuItemTitle = menu.plugin.getString(menuItemTitle);
						text = menu.plugin.getString(text);
					}
					
					listItem.addChild("a", new String[] { "href", "title" }, new String[] { menu.defaultNavigationLink, menuItemTitle }, text);
					listItem.addChild(subnavlist);
					navbarUl.addChild(listItem);
				}
					
			}
			if(selected != null) {
				HTMLNode div = new HTMLNode("div", "id", "selected-subnavbar");
				HTMLNode subnavlist = div.addChild("ul", "id", "selected-subnavbar-list");
				boolean nonEmpty = false;
				for (String navigationLink :  fullAccess ? selected.navigationLinkTexts : selected.navigationLinkTextsNonFull) {
					LinkEnabledCallback cb = selected.navigationLinkCallbacks.get(navigationLink);
					if(cb != null && !cb.isEnabled(ctx)) continue;
					nonEmpty = true;
					String navigationTitle = selected.navigationLinkTitles.get(navigationLink);
					String navigationPath = selected.navigationLinks.get(navigationLink);
					HTMLNode sublistItem;
					if(activePath.equals(navigationPath)) {
						sublistItem = subnavlist.addChild("li", "class", "submenuitem-selected");
					} else {
						sublistItem = subnavlist.addChild("li");
					}
					if(selected.plugin != null) {
						if(navigationTitle != null) navigationTitle = selected.plugin.getString(navigationTitle);
						if(navigationLink != null) navigationLink = selected.plugin.getString(navigationLink);
					} else {
						if(navigationTitle != null) navigationTitle = NodeL10n.getBase().getString(navigationTitle);
						if(navigationLink != null) navigationLink = NodeL10n.getBase().getString(navigationLink);
					}
					if(navigationTitle != null)
						sublistItem.addChild("a", new String[] { "href", "title" }, new String[] { navigationPath, navigationTitle }, navigationLink);
					else
						sublistItem.addChild("a", "href", navigationPath, navigationLink);
				}
				if(nonEmpty)
					pageDiv.addChild(div);
			}
		}
		HTMLNode contentDiv = pageDiv.addChild("div", "id", "content");
		return new PageNode(pageNode, headNode, contentDiv);
	}

	public THEME getTheme() {
		return this.theme;
	}

	public InfoboxNode getInfobox(String header) {
		return getInfobox(header, null, false);
	}

	public InfoboxNode getInfobox(HTMLNode header) {
		return getInfobox(header, null, false);
	}

	public InfoboxNode getInfobox(String category, String header) {
		return getInfobox(category, header, null, false);
	}

	public HTMLNode getInfobox(String category, String header, HTMLNode parent) {
		return getInfobox(category, header, parent, null, false);
	}

	public InfoboxNode getInfobox(String category, HTMLNode header) {
		return getInfobox(category, header, null, false);
	}

	public InfoboxNode getInfobox(String header, String title, boolean isUnique) {
		if (header == null) throw new NullPointerException();
		return getInfobox(new HTMLNode("#", header), title, isUnique);
	}
	
	public InfoboxNode getInfobox(HTMLNode header, String title, boolean isUnique) {
		if (header == null) throw new NullPointerException();
		return getInfobox(null, header, title, isUnique);
	}

	public InfoboxNode getInfobox(String category, String header, String title, boolean isUnique) {
		if (header == null) throw new NullPointerException();
		return getInfobox(category, new HTMLNode("#", header), title, isUnique);
	}

	/** Create an infobox, attach it to the given parent, and return the content node. */
	public HTMLNode getInfobox(String category, String header, HTMLNode parent, String title, boolean isUnique) {
		InfoboxNode node = getInfobox(category, header, title, isUnique);
		parent.addChild(node.outer);
		return node.content;
	}

	/**
	 * Returns an infobox with the given style and header.
	 * 
	 * @param category
	 *            The CSS styles, separated by a space (' ')
	 * @param header
	 *            The header HTML node
	 * @return The infobox
	 */
	public InfoboxNode getInfobox(String category, HTMLNode header, String title, boolean isUnique) {
		if (header == null) throw new NullPointerException();

		StringBuffer classes = new StringBuffer("infobox");
		if(category != null) {
			classes.append(" ");
			classes.append(category);
		}
		if(title != null && !isUnique) {
			classes.append(" ");
			classes.append(title);
		}

		HTMLNode infobox = new HTMLNode("div", "class", classes.toString());

		if(title != null && isUnique) {
			infobox.addAttribute("id", title);
		}

		infobox.addChild("div", "class", "infobox-header").addChild(header);
		return new InfoboxNode(infobox, infobox.addChild("div", "class", "infobox-content"));
	}
	
	private HTMLNode getOverrideContent() {
		HTMLNode result = new HTMLNode("style", "type", "text/css");
		
		try {
			result.addChild("#", FileUtil.readUTF(override));
		} catch (IOException e) {
			Logger.error(this, "Got an IOE: " + e.getMessage(), e);
		}
		
		return result;
	}
	
	/** Call this before getPageNode(), so the menus reflect the advanced mode setting. */
	protected int parseMode(HTTPRequest req, ToadletContainer container) {
		int mode = container.isAdvancedModeEnabled() ? MODE_ADVANCED : MODE_SIMPLE;
		
		if(req.isParameterSet("mode")) {
			mode = req.getIntParam("mode", mode);
			if(mode == MODE_ADVANCED)
				container.setAdvancedMode(true);
			else
				container.setAdvancedMode(false);
		}
		
		return mode;
	}
	
	private static final String l10n(String string) {
		return NodeL10n.getBase().getString("PageMaker." + string);
	}
}
