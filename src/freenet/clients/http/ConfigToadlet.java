/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.http;

import java.io.IOException;
import java.net.URI;
import java.util.Arrays;

import freenet.client.HighLevelSimpleClient;
import freenet.config.BooleanOption;
import freenet.config.Config;
import freenet.config.EnumerableOptionCallback;
import freenet.config.InvalidConfigValueException;
import freenet.config.Option;
import freenet.config.SubConfig;
import freenet.config.WrapperConfig;
import freenet.l10n.L10n;
import freenet.node.Node;
import freenet.node.NodeClientCore;
import freenet.support.HTMLNode;
import freenet.support.Logger;
import freenet.support.MultiValueTable;
import freenet.support.api.BooleanCallback;
import freenet.support.api.HTTPRequest;


// FIXME: add logging, comments
public class ConfigToadlet extends Toadlet {
	// If a setting has to be more than a meg, something is seriously wrong!
	private static final int MAX_PARAM_VALUE_SIZE = 1024*1024;
	private static final int MODE_SIMPLE = 1;
	private static final int MODE_ADVANCED = 2;
	private final Config config;
	private final NodeClientCore core;
	private final Node node;
	
	ConfigToadlet(HighLevelSimpleClient client, Config conf, Node node, NodeClientCore core) {
		super(client);
		config=conf;
		this.core = core;
		this.node = node;
	}

	public void handlePost(URI uri, HTTPRequest request, ToadletContext ctx) throws ToadletContextClosedException, IOException {
		StringBuffer errbuf = new StringBuffer();
		SubConfig[] sc = config.getConfigs();
		
		String pass = request.getPartAsString("formPassword", 32);
		if((pass == null) || !pass.equals(core.formPassword)) {
			MultiValueTable headers = new MultiValueTable();
			headers.put("Location", "/config/");
			ctx.sendReplyHeaders(302, "Found", headers, null, 0);
			return;
		}
		
		if(!ctx.isAllowedFullAccess()) {
			super.sendErrorPage(ctx, 403, L10n.getString("Toadlet.unauthorizedTitle"), L10n.getString("Toadlet.unauthorized"));
			return;
		}
		
		boolean logMINOR = Logger.shouldLog(Logger.MINOR, this);
		
		for(int i=0; i<sc.length ; i++){
			Option[] o = sc[i].getOptions();
			String prefix = sc[i].getPrefix();
			String configName;
			
			for(int j=0; j<o.length; j++){
				configName=o[j].getName();
				if(logMINOR) Logger.minor(this, "Setting "+prefix+ '.' +configName);
				
				// we ignore unreconized parameters 
				if(request.isPartSet(prefix+ '.' +configName)) {
					String value = request.getPartAsString(prefix+ '.' +configName, MAX_PARAM_VALUE_SIZE);
					if(!(o[j].getValueString().equals(value))){
						if(logMINOR) Logger.minor(this, "Setting "+prefix+ '.' +configName+" to "+value);
						try{
							o[j].setValue(value);
						} catch (InvalidConfigValueException e) {
							errbuf.append(o[j].getName()).append(' ').append(e.getMessage()).append('\n');
						} catch (Exception e){
                            errbuf.append(o[j].getName()).append(' ').append(e).append('\n');
							Logger.error(this, "Caught "+e, e);
						}
					}
				}
			}
			
			// Wrapper params
			String wrapperConfigName = "wrapper.java.maxmemory";
			if(request.isPartSet(wrapperConfigName)) {
				String value = request.getPartAsString(wrapperConfigName, MAX_PARAM_VALUE_SIZE);
				if(!WrapperConfig.getWrapperProperty(wrapperConfigName).equals(value)) {
					if(logMINOR) Logger.minor(this, "Setting "+wrapperConfigName+" to "+value);
					WrapperConfig.setWrapperProperty(wrapperConfigName, value);
				}
			}
		}
		core.storeConfig();
		
		HTMLNode pageNode = ctx.getPageMaker().getPageNode(l10n("appliedTitle"), ctx);
		HTMLNode contentNode = ctx.getPageMaker().getContentNode(pageNode);
		
		if (errbuf.length() == 0) {
			HTMLNode infobox = contentNode.addChild(ctx.getPageMaker().getInfobox("infobox-success", l10n("appliedTitle")));
			ctx.getPageMaker().getContentNode(infobox).addChild("#", l10n("appliedSuccess"));
		} else {
			HTMLNode infobox = contentNode.addChild(ctx.getPageMaker().getInfobox("infobox-error", l10n("appliedFailureTitle")));
			HTMLNode content = ctx.getPageMaker().getContentNode(infobox).addChild("div", "class", "infobox-content");
			content.addChild("#", l10n("appliedFailureExceptions"));
			content.addChild("br");
			content.addChild("#", errbuf.toString());
		}
		
		HTMLNode infobox = contentNode.addChild(ctx.getPageMaker().getInfobox("infobox-normal", l10n("possibilitiesTitle")));
		HTMLNode content = ctx.getPageMaker().getContentNode(infobox);
		content.addChild("a", new String[]{"href", "title"}, new String[]{".", l10n("shortTitle")}, l10n("returnToNodeConfig"));
		content.addChild("br");
		addHomepageLink(content);

		writeHTMLReply(ctx, 200, "OK", pageNode.generate());
		
	}
	
	private static final String l10n(String string) {
		return L10n.getString("ConfigToadlet." + string);
	}

	public void handleGet(URI uri, HTTPRequest req, ToadletContext ctx) throws ToadletContextClosedException, IOException {
		
		if(!ctx.isAllowedFullAccess()) {
			super.sendErrorPage(ctx, 403, L10n.getString("Toadlet.unauthorizedTitle"), L10n.getString("Toadlet.unauthorized"));
			return;
		}
		
		SubConfig[] sc = config.getConfigs();
		Arrays.sort(sc);
		
		HTMLNode pageNode = ctx.getPageMaker().getPageNode(L10n.getString("ConfigToadlet.fullTitle", new String[] { "name" }, new String[] { node.getMyName() }), ctx);
		HTMLNode contentNode = ctx.getPageMaker().getContentNode(pageNode);
		
		contentNode.addChild(core.alerts.createSummary());
		
		// Mode can be changed by a link, not just by the default
		
		int mode = core.isAdvancedModeEnabled() ? MODE_ADVANCED : MODE_SIMPLE;
		
		if(req.isParameterSet("mode")) {
			mode = req.getIntParam("mode", mode);
		}
		
		// FIXME style this properly
		HTMLNode table = contentNode.addChild("table", "border", "1");
		HTMLNode row = table.addChild("tr");
		HTMLNode cell = row.addChild("td");
		
		if(mode != MODE_SIMPLE)
			cell.addChild("a", new String[] { "href", "title" }, new String[] { "/config/?mode=1", l10n("modeSimpleTooltip") }, l10n("modeSimple"));
		else
			cell.addChild("b", "title", l10n("modeSimpleTooltip"), l10n("modeSimple"));
		cell = row.addChild("td");
		if(mode != MODE_ADVANCED)
			cell.addChild("a", new String[] { "href", "title" }, new String[] { "/config/?mode=2", l10n("modeAdvancedTooltip") }, l10n("modeAdvanced"));
		else
			cell.addChild("b", "title", l10n("modeAdvancedTooltip"), l10n("modeAdvanced"));
		
		if(mode >= MODE_ADVANCED){
			HTMLNode navigationBar = ctx.getPageMaker().getInfobox("navbar", l10n("configNavTitle"));
			HTMLNode navigationContent = ctx.getPageMaker().getContentNode(navigationBar).addChild("ul");
			navigationContent.addChild("a", "href", TranslationToadlet.TOADLET_URL, l10n("contributeTranslation"));
			HTMLNode navigationTable = navigationContent.addChild("table", "class", "config_navigation");
			HTMLNode navigationTableRow = navigationTable.addChild("tr");
			HTMLNode nextTableCell = navigationTableRow;
			
			for(int i=0; i<sc.length;i++){
				nextTableCell.addChild("td", "class", "config_navigation").addChild("li").addChild("a", "href", '#' +sc[i].getPrefix(), l10n(sc[i].getPrefix()));
			}
			contentNode.addChild(navigationBar);
		}

		HTMLNode infobox = contentNode.addChild("div", "class", "infobox infobox-normal");
		infobox.addChild("div", "class", "infobox-header", l10n("title"));
		HTMLNode configNode = infobox.addChild("div", "class", "infobox-content");
		HTMLNode formNode = ctx.addFormChild(configNode, ".", "configForm");
		
		if(WrapperConfig.canChangeProperties()) {
			formNode.addChild("div", "class", "configprefix", l10n("wrapper"));
			HTMLNode list = formNode.addChild("ul", "class", "config");
			HTMLNode item = list.addChild("li");
			String configName = "wrapper.java.maxmemory";
			// FIXME how to get the real default???
			String defaultValue = "128";
			String curValue = WrapperConfig.getWrapperProperty(configName);
			item.addChild("span", new String[]{ "class", "title", "style" },
					new String[]{ "configshortdesc", L10n.getString("ConfigToadlet.defaultIs", new String[] { "default" }, new String[] { defaultValue }), 
					"cursor: help;" }).addChild(L10n.getHTMLNode("WrapperConfig."+configName+".short"));
			item.addChild("span", "class", "config").addChild("input", new String[] { "type", "class", "name", "value" }, new String[] { "text", "config", configName, curValue });
			item.addChild("span", "class", "configlongdesc").addChild(L10n.getHTMLNode("WrapperConfig."+configName+".long"));
		}
		
		for(int i=0; i<sc.length;i++){
			short displayedConfigElements = 0;
			
			Option[] o = sc[i].getOptions();
			HTMLNode configGroupUlNode = new HTMLNode("ul", "class", "config");
			
			for(int j=0; j<o.length; j++){
				if(! (mode == MODE_SIMPLE && o[j].isExpert())){
					displayedConfigElements++;
					String configName = o[j].getName();
					
					HTMLNode configItemNode = configGroupUlNode.addChild("li");
					configItemNode.addChild("span", new String[]{ "class", "title", "style" },
							new String[]{ "configshortdesc", L10n.getString("ConfigToadlet.defaultIs", new String[] { "default" }, new String[] { o[j].getDefault() }) + (mode >= MODE_ADVANCED ? " ["+sc[i].getPrefix() + '.' + o[j].getName() + ']' : ""), 
							"cursor: help;" }).addChild(L10n.getHTMLNode(o[j].getShortDesc()));
					HTMLNode configItemValueNode = configItemNode.addChild("span", "class", "config");
					if(o[j].getValueString() == null){
						Logger.error(this, sc[i].getPrefix() + configName + "has returned null from config!);");
						continue; 
					}
					
					if(o[j].getCallback() instanceof EnumerableOptionCallback)
						configItemValueNode.addChild(addComboBox((EnumerableOptionCallback)o[j].getCallback(), sc[i], configName));
					else if(o[j].getCallback() instanceof BooleanCallback)
						configItemValueNode.addChild(addBooleanComboBox(((BooleanOption)o[j]).getValue(), sc[i], configName));
					else
						configItemValueNode.addChild("input", new String[] { "type", "class", "alt", "name", "value" }, new String[] { "text", "config", o[j].getShortDesc(), sc[i].getPrefix() + '.' + configName, o[j].getValueString() });

					configItemNode.addChild("span", "class", "configlongdesc").addChild(L10n.getHTMLNode(o[j].getLongDesc()));
				}
			}
			
			if(displayedConfigElements>0) {
				formNode.addChild("div", "class", "configprefix", l10n(sc[i].getPrefix()));
				formNode.addChild("a", "id", sc[i].getPrefix());
				formNode.addChild(configGroupUlNode);
			}
		}
		
		formNode.addChild("input", new String[] { "type", "value" }, new String[] { "submit", l10n("apply")});
		formNode.addChild("input", new String[] { "type", "value" }, new String[] { "reset",  l10n("reset")});
		
		this.writeHTMLReply(ctx, 200, "OK", pageNode.generate());
	}
	
	public String supportedMethods() {
		return "GET, POST";
	}
	
	private HTMLNode addComboBox(EnumerableOptionCallback o, SubConfig sc, String name) {
		HTMLNode result = new HTMLNode("select", "name", sc.getPrefix() + '.' + name);
		String[] possibleValues = o.getPossibleValues();
		for(int i=0; i<possibleValues.length; i++) {
			if(possibleValues[i].equals(o.get()))
				result.addChild("option", new String[] { "value", "selected" }, new String[] { possibleValues[i], "selected" }, possibleValues[i]);
			else
				result.addChild("option", "value", possibleValues[i], possibleValues[i]);
		}
		
		return result;
	}
	
	private HTMLNode addBooleanComboBox(boolean value, SubConfig sc, String name) {
		HTMLNode result = new HTMLNode("select", "name", sc.getPrefix() + '.' + name);
		
		if(value) {
			result.addChild("option", new String[] { "value", "selected" }, new String[] {
					"true", "selected" }, l10n("true"));
			result.addChild("option", "value", "false", l10n("false"));
		} else {
			result.addChild("option", "value", "true", l10n("true"));
			result.addChild("option", new String[] { "value", "selected" }, new String[] {
					"false", "selected" }, l10n("false"));
		}
		
		return result;
	}
}
