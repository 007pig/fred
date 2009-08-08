package freenet.clients.http;

import java.io.IOException;
import java.net.URI;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.NumberFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

import freenet.client.HighLevelSimpleClient;
import freenet.clients.http.updateableelements.AlertElement;
import freenet.clients.http.updateableelements.BaseUpdateableElement;
import freenet.clients.http.updateableelements.UpdaterConstants;
import freenet.config.SubConfig;
import freenet.l10n.L10n;
import freenet.node.Location;
import freenet.node.Node;
import freenet.node.NodeClientCore;
import freenet.node.NodeStarter;
import freenet.node.NodeStats;
import freenet.node.PeerManager;
import freenet.node.PeerNodeStatus;
import freenet.node.RequestStarterGroup;
import freenet.node.Version;
import freenet.support.HTMLNode;
import freenet.support.SizeUtil;
import freenet.support.TimeUtil;
import freenet.support.api.HTTPRequest;

public class StatisticsToadlet extends Toadlet {

	static final NumberFormat thousendPoint = NumberFormat.getInstance();
	
	private static class STMessageCount {
		public String messageName;
		public int messageCount;

		STMessageCount( String messageName, int messageCount ) {
			this.messageName = messageName;
			this.messageCount = messageCount;
		}
	}

	private final Node node;
	private final NodeClientCore core;
	private final NodeStats stats;
	private final PeerManager peers;
	private final DecimalFormat fix1p1 = new DecimalFormat("0.0");
	private final DecimalFormat fix1p2 = new DecimalFormat("0.00");
	private final DecimalFormat fix1p4 = new DecimalFormat("0.0000");
	private final DecimalFormat fix1p6sci = new DecimalFormat("0.######E0");
	private final DecimalFormat fix3p1pct = new DecimalFormat("##0.0%");
	private final DecimalFormat fix3p1US = new DecimalFormat("##0.0", new DecimalFormatSymbols(Locale.US));
	private final DecimalFormat fix3pctUS = new DecimalFormat("##0%", new DecimalFormatSymbols(Locale.US));
	private final DecimalFormat fix6p6 = new DecimalFormat("#####0.0#####");

	protected StatisticsToadlet(Node n, NodeClientCore core, HighLevelSimpleClient client) {
		super(client);
		this.node = n;
		this.core = core;
		stats = node.nodeStats;
		peers = node.peers;
	}

	@Override
	public String supportedMethods() {
		return "GET";
	}

	/**
	 * Counts the peers in <code>peerNodes</code> that have the specified
	 * status.
	 * @param peerNodeStatuses The peer nodes' statuses
	 * @param status The status to count
	 * @return The number of peers that have the specified status.
	 */
	private static int getPeerStatusCount(PeerNodeStatus[] peerNodeStatuses, int status) {
		int count = 0;
		for (int peerIndex = 0, peerCount = peerNodeStatuses.length; peerIndex < peerCount; peerIndex++) {
			if(!peerNodeStatuses[peerIndex].recordStatus())
				continue;
			if (peerNodeStatuses[peerIndex].getStatusValue() == status) {
				count++;
			}
		}
		return count;
	}
	
	private static int getCountSeedServers(PeerNodeStatus[] peerNodeStatuses) {
		int count = 0;
		for(int peerIndex = 0; peerIndex < peerNodeStatuses.length; peerIndex++) {
			if(peerNodeStatuses[peerIndex].isSeedServer()) count++;
		}
		return count;
	}

	private static int getCountSeedClients(PeerNodeStatus[] peerNodeStatuses) {
		int count = 0;
		for(int peerIndex = 0; peerIndex < peerNodeStatuses.length; peerIndex++) {
			if(peerNodeStatuses[peerIndex].isSeedClient()) count++;
		}
		return count;
	}
	
	private static PeerNodeStatus[] getPeerNodeStatuses(PeerManager peers){
		/* gather connection statistics */
		PeerNodeStatus[] peerNodeStatuses = peers.getPeerNodeStatuses(true);
		Arrays.sort(peerNodeStatuses, new Comparator<PeerNodeStatus>() {
			public int compare(PeerNodeStatus firstNode, PeerNodeStatus secondNode) {
				int statusDifference = firstNode.getStatusValue() - secondNode.getStatusValue();
				if (statusDifference != 0) {
					return statusDifference;
				}
				return 0;
			}
		});
		return peerNodeStatuses;
	}

	@Override
	public void handleGet(URI uri, HTTPRequest request, ToadletContext ctx) throws ToadletContextClosedException, IOException, RedirectException {

		if(!ctx.isAllowedFullAccess()) {
			super.sendErrorPage(ctx, 403, L10n.getString("Toadlet.unauthorizedTitle"), L10n.getString("Toadlet.unauthorized"));
			return;
		}
		final SubConfig nodeConfig = node.config.get("node");

		PeerNodeStatus[] peerNodeStatuses=getPeerNodeStatuses(peers);

		int numberOfConnected = getPeerStatusCount(peerNodeStatuses, PeerManager.PEER_NODE_STATUS_CONNECTED);
		int numberOfRoutingBackedOff = getPeerStatusCount(peerNodeStatuses, PeerManager.PEER_NODE_STATUS_ROUTING_BACKED_OFF);

		final int mode = ctx.getPageMaker().parseMode(request, container);
		PageNode page = ctx.getPageMaker().getPageNode(l10n("fullTitle", new String[] { "name" }, new String[] { node.getMyName() }), ctx);
		HTMLNode pageNode = page.outer;
		HTMLNode contentNode = page.content;

		// FIXME! We need some nice images
		final long now = System.currentTimeMillis();
		double myLocation = node.getLocation();
		final long nodeUptimeSeconds = (now - node.startupTime) / 1000;

		if(ctx.isAllowedFullAccess())
			contentNode.addChild(new AlertElement(ctx));
		ctx.getPageMaker().drawModeSelectionArray(core, container, contentNode, mode);

		double swaps = node.getSwaps();
		double noSwaps = node.getNoSwaps();

		HTMLNode overviewTable = contentNode.addChild("table", "class", "column");
		HTMLNode overviewTableRow = overviewTable.addChild("tr");
		HTMLNode nextTableCell = overviewTableRow.addChild("td", "class", "first");

		// node version information box
		HTMLNode versionInfobox = nextTableCell.addChild("div", "class", "infobox");
		
		drawNodeVersionBox(versionInfobox);
		
		// jvm stats box
		HTMLNode jvmStatsInfobox = nextTableCell.addChild("div", "class", "infobox");
		
		drawJVMStatsBox(jvmStatsInfobox,ctx);
		
		// Statistic gathering box
		HTMLNode statGatheringContent = ctx.getPageMaker().getInfobox("#", l10n("statisticGatheringTitle"), nextTableCell);
		// Generate a Thread-Dump
		if(node.isUsingWrapper()){
			HTMLNode threadDumpForm = ctx.addFormChild(statGatheringContent, "/", "threadDumpForm");
			threadDumpForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "getThreadDump", l10n("threadDumpButton")});
		}
		// Get logs
		HTMLNode logsList = statGatheringContent.addChild("ul");
		if(nodeConfig.config.get("logger").getBoolean("enabled"))
			logsList.addChild("li").addChild("a", new String[]{ "href", "target"}, new String[]{ "/?latestlog", "_blank"}, l10n("getLogs"));
		logsList.addChild("li").addChild("a", "href", TranslationToadlet.TOADLET_URL+"?getOverrideTranlationFile").addChild("#", L10n.getString("TranslationToadlet.downloadTranslationsFile"));
		
		if(mode >= PageMaker.MODE_ADVANCED) {
			// store size box
			HTMLNode storeSizeInfobox = nextTableCell.addChild("div", "class", "infobox");
			
			drawStoreSizeBox(storeSizeInfobox, myLocation, nodeUptimeSeconds,ctx);
			
			if(numberOfConnected + numberOfRoutingBackedOff > 0) {
				// Load balancing box
				// Include overall window, and RTTs for each

				HTMLNode loadStatsInfobox = nextTableCell.addChild("div", "class", "infobox");
				drawLoadLimitingBox(loadStatsInfobox, ctx);
				
				// Psuccess box
				HTMLNode successRateBox = nextTableCell.addChild("div", "class", "infobox");
				drawSuccessRatesBox(successRateBox, ctx);
				
				HTMLNode timeDetailBox = nextTableCell.addChild("div", "class", "infobox");
				drawDetailedTimingsBox(timeDetailBox, ctx);
			}
		}

		if(mode >= PageMaker.MODE_ADVANCED || numberOfConnected + numberOfRoutingBackedOff > 0) {			

			// Activity box
			nextTableCell = overviewTableRow.addChild("td", "class", "last");
			HTMLNode activityInfobox = nextTableCell.addChild("div", "class", "infobox");
			
			drawActivityBox(activityInfobox, mode >= PageMaker.MODE_ADVANCED,node,false,ctx);

			/* node status overview box */
			if(mode >= PageMaker.MODE_ADVANCED) {
				HTMLNode overviewInfobox = nextTableCell.addChild("div", "class", "infobox");
				drawOverviewBox(overviewInfobox, nodeUptimeSeconds, now, swaps, noSwaps,ctx);
			}

			// Peer statistics box
			HTMLNode peerStatsInfobox = nextTableCell.addChild("div", "class", "infobox");
			
			drawPeerStatsBox(peerStatsInfobox, mode >= PageMaker.MODE_ADVANCED, peers,ctx);

			// Bandwidth box
			HTMLNode bandwidthInfobox = nextTableCell.addChild("div", "class", "infobox");
			
			drawBandwidthBox(bandwidthInfobox, nodeUptimeSeconds, mode >= PageMaker.MODE_ADVANCED,node,ctx);
		}

		if(mode >= PageMaker.MODE_ADVANCED) {

			// Peer routing backoff reason box
			HTMLNode backoffReasonInfobox = nextTableCell.addChild("div", "class", "infobox");
			drawPeerBackoffReasonsBox(backoffReasonInfobox,peers, ctx);

			//Swap statistics box
			HTMLNode locationSwapInfobox = nextTableCell.addChild("div", "class", "infobox");
			drawSwapStatsBox(locationSwapInfobox, myLocation, nodeUptimeSeconds, swaps, noSwaps,ctx);

			// unclaimedFIFOMessageCounts box
			HTMLNode unclaimedFIFOMessageCountsInfobox = nextTableCell.addChild("div", "class", "infobox");
			drawUnclaimedFIFOMessageCountsBox(unclaimedFIFOMessageCountsInfobox,ctx);

						
			HTMLNode threadsPriorityInfobox = nextTableCell.addChild("div", "class", "infobox");
			drawThreadPriorityStatsBox(threadsPriorityInfobox,ctx);
			
			nextTableCell = overviewTableRow.addChild("td");

			// thread usage box
			HTMLNode threadUsageInfobox = nextTableCell.addChild("div", "class", "infobox");
			drawThreadUsageBox(threadUsageInfobox,ctx);
			
			// rejection reasons box
			drawRejectReasonsBox(nextTableCell, false,ctx);
			drawRejectReasonsBox(nextTableCell, true,ctx);
			
			// database thread jobs box
			
			HTMLNode databaseJobsInfobox = nextTableCell.addChild("div", "class", "infobox");
			drawDatabaseJobsBox(databaseJobsInfobox,ctx);
			
			// peer distribution box
			overviewTableRow = overviewTable.addChild("tr");
			nextTableCell = overviewTableRow.addChild("td", "class", "first");
			drawPeerLocationDistributionBox(nextTableCell, ctx);

			nextTableCell = overviewTableRow.addChild("td");

			// node distribution box
			drawNodeLocationDistributionBox(nextTableCell, ctx);
			
			
			overviewTableRow = overviewTable.addChild("tr");
			nextTableCell = overviewTableRow.addChild("td", "class", "first");
			// specialisation box
			
			drawIncomingRequestDistributionBox(nextTableCell, ctx);
			
			nextTableCell = overviewTableRow.addChild("td");
			
			drawOutgoingRequestDistributionBox(nextTableCell, ctx);
			
		}

		this.writeHTMLReply(ctx, 200, "OK", pageNode.generate());
	}
	
	private void drawPeerLocationDistributionBox(HTMLNode parent,final ToadletContext ctx){
		parent.addChild(new StatisticsUpdateableElement(ctx) {
			
			@Override
			public void updateState(boolean initial) {
				children.clear();
				
				HTMLNode peerCircleInfobox = addChild("div", "class", "infobox");
				peerCircleInfobox.addChild("div", "class", "infobox-header", "Peer\u00a0Location\u00a0Distribution (w/pReject)");
				HTMLNode peerCircleTable = peerCircleInfobox.addChild("div", "class", "infobox-content").addChild("table");
				addPeerCircle(peerCircleTable, getPeerNodeStatuses(peers), node.getLocation());
			}
		});
	}
	
	private void drawNodeLocationDistributionBox(HTMLNode parent,final ToadletContext ctx){
		parent.addChild(new StatisticsUpdateableElement(ctx) {
			
			@Override
			public void updateState(boolean initial) {
				children.clear();
				
				HTMLNode nodeCircleInfobox = addChild("div", "class", "infobox");
				nodeCircleInfobox.addChild("div", "class", "infobox-header", "Node\u00a0Location\u00a0Distribution (w/Swap\u00a0Age)");
				HTMLNode nodeCircleTable = nodeCircleInfobox.addChild("div", "class", "infobox-content").addChild("table");
				addNodeCircle(nodeCircleTable, node.getLocation());
			}
		});
	}
	
	private void drawIncomingRequestDistributionBox(HTMLNode parent,final ToadletContext ctx){
		parent.addChild(new StatisticsUpdateableElement(ctx) {
			
			@Override
			public void updateState(boolean initial) {
				children.clear();
				
				int[] incomingRequestCountArray = new int[1];
				int incomingRequestsCount = incomingRequestCountArray[0];
				int[] incomingRequestLocation = stats.getIncomingRequestLocation(incomingRequestCountArray);
				HTMLNode nodeSpecialisationInfobox = addChild("div", "class", "infobox");
				
				if(incomingRequestsCount > 0) {
					nodeSpecialisationInfobox.addChild("div", "class", "infobox-header", "Incoming\u00a0Request\u00a0Distribution");
					HTMLNode nodeSpecialisationTable = nodeSpecialisationInfobox.addChild("div", "class", "infobox-content").addChild("table");
					addSpecialisation(nodeSpecialisationTable, node.getLocation(), incomingRequestsCount, incomingRequestLocation);
				}
			}
		});
	}
	
	private void drawOutgoingRequestDistributionBox(HTMLNode parent,final ToadletContext ctx){
		parent.addChild(new StatisticsUpdateableElement(ctx) {
			
			@Override
			public void updateState(boolean initial) {
				children.clear();
				
				int[] outgoingLocalRequestCountArray = new int[1];
				int[] outgoingLocalRequestLocation = stats.getOutgoingLocalRequestLocation(outgoingLocalRequestCountArray);
				int outgoingLocalRequestsCount = outgoingLocalRequestCountArray[0];
				int[] outgoingRequestCountArray = new int[1];
				int[] outgoingRequestLocation = stats.getOutgoingRequestLocation(outgoingRequestCountArray);
				int outgoingRequestsCount = outgoingRequestCountArray[0];
				
				if(outgoingLocalRequestsCount > 0 && outgoingRequestsCount > 0) {
					HTMLNode nodeSpecialisationInfobox = addChild("div", "class", "infobox");
					nodeSpecialisationInfobox.addChild("div", "class", "infobox-header", "Outgoing\u00a0Request\u00a0Distribution");
					HTMLNode nodeSpecialisationTable = nodeSpecialisationInfobox.addChild("div", "class", "infobox-content").addChild("table");
					addCombinedSpecialisation(nodeSpecialisationTable, node.getLocation(), outgoingLocalRequestsCount, outgoingLocalRequestLocation, outgoingRequestsCount, outgoingRequestLocation);
				}
			}
		});
	}
	
	private void drawLoadLimitingBox(HTMLNode parent,final ToadletContext ctx){
		parent.addChild(new StatisticsUpdateableElement(ctx) {
			
			@Override
			public void updateState(boolean initial) {
				children.clear();
				
				RequestStarterGroup starters = core.requestStarters;
				double window = starters.getWindow();
				double realWindow = starters.getRealWindow();
				
				addChild("div", "class", "infobox-header", "Load limiting");
				HTMLNode loadStatsContent = addChild("div", "class", "infobox-content");
				HTMLNode loadStatsList = loadStatsContent.addChild("ul");
				loadStatsList.addChild("li", "Global window: "+window);
				loadStatsList.addChild("li", "Real global window: "+realWindow);
				loadStatsList.addChild("li", starters.statsPageLine(false, false));
				loadStatsList.addChild("li", starters.statsPageLine(true, false));
				loadStatsList.addChild("li", starters.statsPageLine(false, true));
				loadStatsList.addChild("li", starters.statsPageLine(true, true));
				loadStatsList.addChild("li", starters.diagnosticThrottlesLine(false));
				loadStatsList.addChild("li", starters.diagnosticThrottlesLine(true));
			}
		});
	}
	
	private void drawSuccessRatesBox(HTMLNode parent, final ToadletContext ctx){
		parent.addChild(new StatisticsUpdateableElement(ctx) {
			
			@Override
			public void updateState(boolean initial) {
				children.clear();
				
				addChild("div", "class", "infobox-header", "Success rates");
				HTMLNode successRateContent = addChild("div", "class", "infobox-content");
				stats.fillSuccessRateBox(successRateContent);
			}
		});
	}
	
	private void drawDetailedTimingsBox(HTMLNode parent,final ToadletContext ctx){
		parent.addChild(new StatisticsUpdateableElement(ctx) {
			
			@Override
			public void updateState(boolean initial) {
				children.clear();
				
				addChild("div", "class", "infobox-header", "Detailed timings (local CHK fetches)");
				HTMLNode timingsContent = addChild("div", "class", "infobox-content");
				stats.fillDetailedTimingsBox(timingsContent);
			}
		});
	}
	
	public static void drawPeerBackoffReasonsBox(HTMLNode parent,final PeerManager peers,final ToadletContext ctx){
		parent.addChild(new StatisticsUpdateableElement(ctx) {
			
			@Override
			public void updateState(boolean initial) {
				children.clear();
				
				addChild("div", "class", "infobox-header", "Peer backoff reasons");
				HTMLNode backoffReasonContent = addChild("div", "class", "infobox-content");
				String [] routingBackoffReasons = peers.getPeerNodeRoutingBackoffReasons();
				if(routingBackoffReasons.length == 0) {
					backoffReasonContent.addChild("#", "Good, your node is not backed off from any peers!");
				} else {
					HTMLNode reasonList = backoffReasonContent.addChild("ul");
					for(int i=0;i<routingBackoffReasons.length;i++) {
						int reasonCount = peers.getPeerNodeRoutingBackoffReasonSize(routingBackoffReasons[i]);
						if(reasonCount > 0) {
							reasonList.addChild("li", routingBackoffReasons[i] + '\u00a0' + reasonCount);
						}
					}
				}
			}
		});
	}

	private void drawThreadUsageBox(HTMLNode parent,final ToadletContext ctx){
		parent.addChild(new StatisticsUpdateableElement(ctx) {
			
			@Override
			public void updateState(boolean initial) {
				children.clear();
				
				addChild("div", "class", "infobox-header", "Thread usage");
				HTMLNode threadUsageContent = addChild("div", "class", "infobox-content");
				HTMLNode threadUsageList = threadUsageContent.addChild("ul");
				getThreadNames(threadUsageList);
			}
		});
	}
	
	private void drawRejectReasonsBox(HTMLNode nextTableCell, final boolean local,final ToadletContext ctx) {
		nextTableCell.addChild(new StatisticsUpdateableElement(ctx) {
			
			@Override
			public void updateState(boolean initial) {
				children.clear();
				
				HTMLNode rejectReasonsTable = new HTMLNode("table");
				NodeStats stats = node.nodeStats;
				boolean success = local ? stats.getLocalRejectReasonsTable(rejectReasonsTable) :
					stats.getRejectReasonsTable(rejectReasonsTable);
				if(!success)
					return;
				HTMLNode rejectReasonsInfobox = addChild("div", "class", "infobox");
				rejectReasonsInfobox.addChild("div", "class", "infobox-header", (local ? "Local " : "")+"Preemptive Rejection Reasons");
				rejectReasonsInfobox.addChild("div", "class", "infobox-content").addChild(rejectReasonsTable);
			}
		});
	}

	private void drawNodeVersionBox(HTMLNode versionInfobox) {
		
		versionInfobox.addChild("div", "class", "infobox-header", l10n("versionTitle"));
		HTMLNode versionInfoboxContent = versionInfobox.addChild("div", "class", "infobox-content");
		HTMLNode versionInfoboxList = versionInfoboxContent.addChild("ul");
		versionInfoboxList.addChild("li", L10n.getString("WelcomeToadlet.version", new String[] { "fullVersion", "build", "rev" },
				new String[] { Version.publicVersion, Integer.toString(Version.buildNumber()), Version.cvsRevision }));
		if(NodeStarter.extBuildNumber < NodeStarter.RECOMMENDED_EXT_BUILD_NUMBER)
			versionInfoboxList.addChild("li", L10n.getString("WelcomeToadlet.extVersionWithRecommended", 
					new String[] { "build", "recbuild", "rev" }, 
					new String[] { Integer.toString(NodeStarter.extBuildNumber), Integer.toString(NodeStarter.RECOMMENDED_EXT_BUILD_NUMBER), NodeStarter.extRevisionNumber }));
		else
			versionInfoboxList.addChild("li", L10n.getString("WelcomeToadlet.extVersion", new String[] { "build", "rev" },
					new String[] { Integer.toString(NodeStarter.extBuildNumber), NodeStarter.extRevisionNumber }));
		
	}

	private void drawJVMStatsBox(HTMLNode jvmStatsInfobox,final ToadletContext ctx) {
		
		jvmStatsInfobox.addChild(new StatisticsUpdateableElement(ctx) {
			@Override
			public void updateState(boolean initial) {
				
				children.clear();
				
				addChild("div", "class", "infobox-header", l10n("jvmInfoTitle"));
				HTMLNode jvmStatsInfoboxContent = addChild("div", "class", "infobox-content");
				HTMLNode jvmStatsList = jvmStatsInfoboxContent.addChild("ul");

				Runtime rt = Runtime.getRuntime();
				long freeMemory = rt.freeMemory();
				long totalMemory = rt.totalMemory();
				long maxMemory = rt.maxMemory();

				long usedJavaMem = totalMemory - freeMemory;
				long allocatedJavaMem = totalMemory;
				long maxJavaMem = maxMemory;
				int availableCpus = rt.availableProcessors();

				int threadCount = stats.getActiveThreadCount();

				jvmStatsList.addChild("li", l10n("usedMemory", "memory", SizeUtil.formatSize(usedJavaMem, true)));
				jvmStatsList.addChild("li", l10n("allocMemory", "memory", SizeUtil.formatSize(allocatedJavaMem, true)));
				jvmStatsList.addChild("li", l10n("maxMemory", "memory", SizeUtil.formatSize(maxJavaMem, true)));
				jvmStatsList.addChild("li", l10n("threads", new String[] { "running", "max" },
						new String[] { thousendPoint.format(threadCount), Integer.toString(stats.getThreadLimit()) }));
				jvmStatsList.addChild("li", l10n("cpus", "count", Integer.toString(availableCpus)));
				jvmStatsList.addChild("li", l10n("javaVersion", "version", System.getProperty("java.version")));
				jvmStatsList.addChild("li", l10n("jvmVendor", "vendor", System.getProperty("java.vendor")));
				jvmStatsList.addChild("li", l10n("jvmVersion", "version", System.getProperty("java.vm.version")));
				jvmStatsList.addChild("li", l10n("osName", "name", System.getProperty("os.name")));
				jvmStatsList.addChild("li", l10n("osVersion", "version", System.getProperty("os.version")));
				jvmStatsList.addChild("li", l10n("osArch", "arch", System.getProperty("os.arch")));
			}
		});

		
	}
	
	private void drawThreadPriorityStatsBox(HTMLNode node,final ToadletContext ctx) {
		
		node.addChild(new StatisticsUpdateableElement(ctx) {
			
			@Override
			public void updateState(boolean initial) {
				children.clear();
				
				addChild("div", "class", "infobox-header", l10n("threadsByPriority"));
				HTMLNode threadsInfoboxContent = addChild("div", "class", "infobox-content");
				int[] activeThreadsByPriority = stats.getActiveThreadsByPriority();
				int[] waitingThreadsByPriority = stats.getWaitingThreadsByPriority();
				
				HTMLNode threadsByPriorityTable = threadsInfoboxContent.addChild("table", "border", "0");
				HTMLNode row = threadsByPriorityTable.addChild("tr");

				row.addChild("th", l10n("priority"));
				row.addChild("th", l10n("running"));
				row.addChild("th", l10n("waiting"));
				
				for(int i=0; i<activeThreadsByPriority.length; i++) {
					row = threadsByPriorityTable.addChild("tr");
					row.addChild("td", String.valueOf(i+1));
					row.addChild("td", String.valueOf(activeThreadsByPriority[i]));
					row.addChild("td", String.valueOf(waitingThreadsByPriority[i]));
				}
			}
		});
		
	}

	private void drawDatabaseJobsBox(HTMLNode node,final ToadletContext ctx) {
		
		node.addChild(new StatisticsUpdateableElement(ctx) {
			
			@Override
			public void updateState(boolean initial) {
				children.clear();
				
				addChild("div", "class", "infobox-header", l10n("databaseJobsByPriority"));
				HTMLNode threadsInfoboxContent = addChild("div", "class", "infobox-content");
				int[] jobsByPriority = core.clientDatabaseExecutor.queuedJobs();
				
				HTMLNode threadsByPriorityTable = threadsInfoboxContent.addChild("table", "border", "0");
				HTMLNode row = threadsByPriorityTable.addChild("tr");

				row.addChild("th", l10n("priority"));
				row.addChild("th", l10n("waiting"));
				
				for(int i=0; i<jobsByPriority.length; i++) {
					row = threadsByPriorityTable.addChild("tr");
					row.addChild("td", String.valueOf(i));
					row.addChild("td", String.valueOf(jobsByPriority[i]));
				}
			}
		});
	}
	
	private void drawStoreSizeBox(HTMLNode storeSizeInfobox, final double loc, final long nodeUptimeSeconds,final ToadletContext ctx) {
		
		storeSizeInfobox.addChild(new StatisticsUpdateableElement(ctx) {
			
			@Override
			public void updateState(boolean initial) {
				children.clear();
				
				addChild("div", "class", "infobox-header", "Datastore");
				HTMLNode storeSizeInfoboxContent = addChild("div", "class", "infobox-content");
				HTMLNode storeSizeTable = storeSizeInfoboxContent.addChild("table", "border", "0");
				HTMLNode row=storeSizeTable.addChild("tr");

				//FIXME - Non-breaking space? "Stat-name"?
				row.addChild("th", "");
				row.addChild("th", "Store");
				row.addChild("th", "Cache");
				
				final long fix32kb = 32 * 1024;

				long cachedKeys = node.getChkDatacache().keyCount();
				long cachedSize = cachedKeys * fix32kb;
				long storeKeys = node.getChkDatastore().keyCount();
				long storeSize = storeKeys * fix32kb;
				long maxCachedKeys = node.getChkDatacache().getMaxKeys();
				long maxStoreKeys = node.getChkDatastore().getMaxKeys();
				long cacheHits = node.getChkDatacache().hits();
				long cacheMisses = node.getChkDatacache().misses();
				long cacheAccesses = cacheHits + cacheMisses;
				long storeHits = node.getChkDatastore().hits();
				long storeMisses = node.getChkDatastore().misses();
				long storeAccesses = storeHits + storeMisses;
				long cacheWrites=node.getChkDatacache().writes();
				long storeWrites=node.getChkDatastore().writes();
				long cacheFalsePos = node.getChkDatacache().getBloomFalsePositive();
				long storeFalsePos = node.getChkDatastore().getBloomFalsePositive();

				// REDFLAG Don't show database version because it's not possible to get it accurately.
				// (It's a public static constant, so it will use the version from compile time of freenet.jar)
				row=storeSizeTable.addChild("tr");
				row.addChild("td", "Keys");
				row.addChild("td", thousendPoint.format(storeKeys));
				row.addChild("td", thousendPoint.format(cachedKeys));
				
				row=storeSizeTable.addChild("tr");
				row.addChild("td", "Capacity");
				row.addChild("td", thousendPoint.format(maxStoreKeys));
				row.addChild("td", thousendPoint.format(maxCachedKeys));
				
				row=storeSizeTable.addChild("tr");
				row.addChild("td", "Data Size");
				row.addChild("td", SizeUtil.formatSize(storeSize, true));
				row.addChild("td", SizeUtil.formatSize(cachedSize, true));
				
				row=storeSizeTable.addChild("tr");
				row.addChild("td", "Utilization");
				row.addChild("td", fix3p1pct.format(1.0*storeKeys/maxStoreKeys));
				row.addChild("td", fix3p1pct.format(1.0*cachedKeys/maxCachedKeys));
				
				row=storeSizeTable.addChild("tr");
				row.addChild("td", "Read-Requests");
				row.addChild("td", thousendPoint.format(storeAccesses));
				row.addChild("td", thousendPoint.format(cacheAccesses));
				
				row=storeSizeTable.addChild("tr");
				row.addChild("td", "Successful Reads");
				if (storeAccesses > 0)
					row.addChild("td", thousendPoint.format(storeHits));
				else
					row.addChild("td", "0");
				if (cacheAccesses > 0)
					row.addChild("td", thousendPoint.format(cacheHits));
				else
					row.addChild("td", "0");
				
				row=storeSizeTable.addChild("tr");
				row.addChild("td", "Success Rate");
				if (storeAccesses > 0)
					row.addChild("td", fix1p4.format(100.0 * storeHits / storeAccesses) + "%");
				else
					row.addChild("td", "N/A");
				if (cacheAccesses > 0)
					row.addChild("td", fix1p4.format(100.0 * cacheHits / cacheAccesses) + "%");
				else
					row.addChild("td", "N/A");
				
				row=storeSizeTable.addChild("tr");
				row.addChild("td", "Writes");
				row.addChild("td", thousendPoint.format(storeWrites));
				row.addChild("td", thousendPoint.format(cacheWrites));
						
				/* Overall utilization is not preserved in the new table layout :(
				storeSizeList.addChild("li", 
						"Overall size:\u00a0" + thousendPoint.format(overallKeys) + 
						"\u00a0/\u00a0" + thousendPoint.format(maxOverallKeys) +
						"\u00a0(" + SizeUtil.formatSize(overallSize, true) + 
						"\u00a0/\u00a0" + SizeUtil.formatSize(maxOverallSize, true) + 
						")\u00a0(" + ((overallKeys*100)/maxOverallKeys) + "%)");
				 */
				
				row=storeSizeTable.addChild("tr");
				row.addChild("td", "Access Rate");
				row.addChild("td", fix1p2.format(1.0*storeAccesses/nodeUptimeSeconds)+" /sec");
				row.addChild("td", fix1p2.format(1.0*cacheAccesses/nodeUptimeSeconds)+" /sec");
				
				row=storeSizeTable.addChild("tr");
				row.addChild("td", "Write Rate");
				row.addChild("td", fix1p2.format(1.0*storeWrites/nodeUptimeSeconds)+" /sec");
				row.addChild("td", fix1p2.format(1.0*cacheWrites/nodeUptimeSeconds)+" /sec");
				
				if (storeFalsePos != -1 || cacheFalsePos != -1) {
					row = storeSizeTable.addChild("tr");
					row.addChild("td", "False Pos.");
					row.addChild("td", thousendPoint.format(storeFalsePos));
					row.addChild("td", thousendPoint.format(cacheFalsePos));
				}
				
				// location-based stats
				double nodeLoc=0.0;
				
				double avgCacheLocation=node.nodeStats.avgCacheLocation.currentValue();
				double avgStoreLocation=node.nodeStats.avgStoreLocation.currentValue();
				double avgCacheSuccess=node.nodeStats.avgCacheSuccess.currentValue();
				double avgStoreSuccess=node.nodeStats.avgStoreSuccess.currentValue();
				double furthestCacheSuccess=node.nodeStats.furthestCacheSuccess;
				double furthestStoreSuccess=node.nodeStats.furthestStoreSuccess;
				double storeDist=Location.distance(nodeLoc, avgStoreLocation);
				double cacheDist=Location.distance(nodeLoc, avgCacheLocation);
				
				row=storeSizeTable.addChild("tr");
				row.addChild("td", "Avg. Location");
				row.addChild("td", fix1p4.format(avgStoreLocation));
				row.addChild("td", fix1p4.format(avgCacheLocation));
				
				row=storeSizeTable.addChild("tr");
				row.addChild("td", "Avg. Success Loc.");
				row.addChild("td", fix1p4.format(avgStoreSuccess));
				row.addChild("td", fix1p4.format(avgCacheSuccess));
				
				row=storeSizeTable.addChild("tr");
				row.addChild("td", "Furthest Success");
				row.addChild("td", fix1p4.format(furthestStoreSuccess));
				row.addChild("td", fix1p4.format(furthestCacheSuccess));
				
				row = storeSizeTable.addChild("tr");
				row.addChild("td", "Avg. Distance");
				row.addChild("td", fix1p4.format(storeDist));
				row.addChild("td", fix1p4.format(cacheDist));

				long cacheLocationReports = node.nodeStats.avgCacheLocation.countReports();
				long storeLocationReports = node.nodeStats.avgStoreLocation.countReports();

				double storePercent = 1.0 * storeLocationReports / storeKeys;
				double cachePercent = 1.0 * cacheLocationReports / cachedKeys;

				//Cap the reported value at 100%, as the decaying average does not account beyond that anyway.
				if(storePercent > 1.0)
					storePercent = 1.0;
				if(cachePercent > 1.0)
					cachePercent = 1.0;

				row = storeSizeTable.addChild("tr");
				row.addChild("td", "Distance Stats");
				row.addChild("td", fix3p1pct.format(storePercent));
				row.addChild("td", fix3p1pct.format(cachePercent));
				
				node.drawClientCacheBox(this);
				node.drawSlashdotCacheBox(this);
			}
		});
		
	}

	private void drawUnclaimedFIFOMessageCountsBox(HTMLNode unclaimedFIFOMessageCountsInfobox,final ToadletContext ctx) {
		
		unclaimedFIFOMessageCountsInfobox.addChild(new StatisticsUpdateableElement(ctx) {
			
			@Override
			public void updateState(boolean initial) {
				children.clear();
				
				addChild("div", "class", "infobox-header", "unclaimedFIFO Message Counts");
				HTMLNode unclaimedFIFOMessageCountsInfoboxContent = addChild("div", "class", "infobox-content");
				HTMLNode unclaimedFIFOMessageCountsList = unclaimedFIFOMessageCountsInfoboxContent.addChild("ul");
				Map<String, Integer> unclaimedFIFOMessageCountsMap = node.getUSM().getUnclaimedFIFOMessageCounts();
				STMessageCount[] unclaimedFIFOMessageCountsArray = new STMessageCount[unclaimedFIFOMessageCountsMap.size()];
				int i = 0;
				int totalCount = 0;
				for (Map.Entry<String, Integer> e : unclaimedFIFOMessageCountsMap.entrySet()) {
					String messageName = e.getKey();
					int messageCount = e.getValue();
					totalCount = totalCount + messageCount;
					unclaimedFIFOMessageCountsArray[i++] = new STMessageCount( messageName, messageCount );
				}
				Arrays.sort(unclaimedFIFOMessageCountsArray, new Comparator<STMessageCount>() {
					public int compare(STMessageCount firstCount, STMessageCount secondCount) {
						return secondCount.messageCount - firstCount.messageCount;  // sort in descending order
					}
				});
				for (int countsArrayIndex = 0, countsArrayCount = unclaimedFIFOMessageCountsArray.length; countsArrayIndex < countsArrayCount; countsArrayIndex++) {
					STMessageCount messageCountItem = unclaimedFIFOMessageCountsArray[countsArrayIndex];
					int thisMessageCount = messageCountItem.messageCount;
					double thisMessagePercentOfTotal = ((double) thisMessageCount) / ((double) totalCount);
					unclaimedFIFOMessageCountsList.addChild("li", "" + messageCountItem.messageName + ":\u00a0" + thisMessageCount + "\u00a0(" + fix3p1pct.format(thisMessagePercentOfTotal) + ')');
				}
				unclaimedFIFOMessageCountsList.addChild("li", "Unclaimed Messages Considered:\u00a0" + totalCount);

			}
		});	
	}

	private void drawSwapStatsBox(HTMLNode locationSwapInfobox, final double location, final long nodeUptimeSeconds, final double swaps, final double noSwaps,final ToadletContext ctx) {
		
		locationSwapInfobox.addChild(new StatisticsUpdateableElement(ctx) {
			
			@Override
			public void updateState(boolean initial) {
				children.clear();
				
				addChild("div", "class", "infobox-header", "Location swaps");
				int startedSwaps = node.getStartedSwaps();
				int swapsRejectedAlreadyLocked = node.getSwapsRejectedAlreadyLocked();
				int swapsRejectedNowhereToGo = node.getSwapsRejectedNowhereToGo();
				int swapsRejectedRateLimit = node.getSwapsRejectedRateLimit();
				int swapsRejectedRecognizedID = node.getSwapsRejectedRecognizedID();
				double locChangeSession = node.getLocationChangeSession();
				int averageSwapTime = node.getAverageOutgoingSwapTime();
				int sendSwapInterval = node.getSendSwapInterval();

				HTMLNode locationSwapInfoboxContent = addChild("div", "class", "infobox-content");
				HTMLNode locationSwapList = locationSwapInfoboxContent.addChild("ul");
				locationSwapList.addChild("li", "location:\u00a0" + location);
				if (swaps > 0.0) {
					locationSwapList.addChild("li", "locChangeSession:\u00a0" + fix1p6sci.format(locChangeSession));
					locationSwapList.addChild("li", "locChangePerSwap:\u00a0" + fix1p6sci.format(locChangeSession/swaps));
				}
				if ((swaps > 0.0) && (nodeUptimeSeconds >= 60)) {
					locationSwapList.addChild("li", "locChangePerMinute:\u00a0" + fix1p6sci.format(locChangeSession/(nodeUptimeSeconds/60.0)));
				}
				if ((swaps > 0.0) && (nodeUptimeSeconds >= 60)) {
					locationSwapList.addChild("li", "swapsPerMinute:\u00a0" + fix1p6sci.format(swaps/(nodeUptimeSeconds/60.0)));
				}
				if ((noSwaps > 0.0) && (nodeUptimeSeconds >= 60)) {
					locationSwapList.addChild("li", "noSwapsPerMinute:\u00a0" + fix1p6sci.format(noSwaps/(nodeUptimeSeconds/60.0)));
				}
				if ((swaps > 0.0) && (noSwaps > 0.0)) {
					locationSwapList.addChild("li", "swapsPerNoSwaps:\u00a0" + fix1p6sci.format(swaps/noSwaps));
				}
				if (swaps > 0.0) {
					locationSwapList.addChild("li", "swaps:\u00a0" + (int)swaps);
				}
				if (noSwaps > 0.0) {
					locationSwapList.addChild("li", "noSwaps:\u00a0" + (int)noSwaps);
				}
				if (startedSwaps > 0) {
					locationSwapList.addChild("li", "startedSwaps:\u00a0" + startedSwaps);
				}
				if (swapsRejectedAlreadyLocked > 0) {
					locationSwapList.addChild("li", "swapsRejectedAlreadyLocked:\u00a0" + swapsRejectedAlreadyLocked);
				}
				if (swapsRejectedNowhereToGo > 0) {
					locationSwapList.addChild("li", "swapsRejectedNowhereToGo:\u00a0" + swapsRejectedNowhereToGo);
				}
				if (swapsRejectedRateLimit > 0) {
					locationSwapList.addChild("li", "swapsRejectedRateLimit:\u00a0" + swapsRejectedRateLimit);
				}
				if (swapsRejectedRecognizedID > 0) {
					locationSwapList.addChild("li", "swapsRejectedRecognizedID:\u00a0" + swapsRejectedRecognizedID);
				}
				locationSwapList.addChild("li", "averageSwapTime:\u00a0" + TimeUtil.formatTime(averageSwapTime, 2, true));
				locationSwapList.addChild("li", "sendSwapInterval:\u00a0" + TimeUtil.formatTime(sendSwapInterval, 2, true));
			}
		});
		
	}

	protected static void drawPeerStatsBox(HTMLNode peerStatsInfobox, final boolean advancedModeEnabled,final PeerManager peers,final ToadletContext ctx) {
		
		peerStatsInfobox.addChild(new BaseUpdateableElement("div",ctx) {
			
			PeerManager.PeerStatusChangeListener listener=new PeerManager.PeerStatusChangeListener() {
				
				public void onPeerStatusChange() {
					((SimpleToadletServer)ctx.getContainer()).pushDataManager.updateElement(getUpdaterId(null));
				}
			};
			
			{
				init();
				peers.addPeerStatusChangeListener(listener);
			}
			
			@Override
			public void updateState(boolean initial) {
				children.clear();
				
				PeerNodeStatus[] peerNodeStatuses=getPeerNodeStatuses(peers);

				int numberOfConnected = getPeerStatusCount(peerNodeStatuses, PeerManager.PEER_NODE_STATUS_CONNECTED);
				int numberOfRoutingBackedOff = getPeerStatusCount(peerNodeStatuses, PeerManager.PEER_NODE_STATUS_ROUTING_BACKED_OFF);
				int numberOfTooNew = getPeerStatusCount(peerNodeStatuses, PeerManager.PEER_NODE_STATUS_TOO_NEW);
				int numberOfTooOld = getPeerStatusCount(peerNodeStatuses, PeerManager.PEER_NODE_STATUS_TOO_OLD);
				int numberOfDisconnected = getPeerStatusCount(peerNodeStatuses, PeerManager.PEER_NODE_STATUS_DISCONNECTED);
				int numberOfNeverConnected = getPeerStatusCount(peerNodeStatuses, PeerManager.PEER_NODE_STATUS_NEVER_CONNECTED);
				int numberOfDisabled = getPeerStatusCount(peerNodeStatuses, PeerManager.PEER_NODE_STATUS_DISABLED);
				int numberOfBursting = getPeerStatusCount(peerNodeStatuses, PeerManager.PEER_NODE_STATUS_BURSTING);
				int numberOfListening = getPeerStatusCount(peerNodeStatuses, PeerManager.PEER_NODE_STATUS_LISTENING);
				int numberOfListenOnly = getPeerStatusCount(peerNodeStatuses, PeerManager.PEER_NODE_STATUS_LISTEN_ONLY);
				int numberOfSeedServers = getCountSeedServers(peerNodeStatuses);
				int numberOfSeedClients = getCountSeedClients(peerNodeStatuses);
				int numberOfRoutingDisabled = getPeerStatusCount(peerNodeStatuses, PeerManager.PEER_NODE_STATUS_ROUTING_DISABLED);
				int numberOfClockProblem = getPeerStatusCount(peerNodeStatuses, PeerManager.PEER_NODE_STATUS_CLOCK_PROBLEM);
				int numberOfConnError = getPeerStatusCount(peerNodeStatuses, PeerManager.PEER_NODE_STATUS_CONN_ERROR);
				int numberOfDisconnecting = PeerNodeStatus.getPeerStatusCount(peerNodeStatuses, PeerManager.PEER_NODE_STATUS_DISCONNECTING);
				
				addChild("div", "class", "infobox-header", l10n("peerStatsTitle"));
				HTMLNode peerStatsContent = addChild("div", "class", "infobox-content");
				HTMLNode peerStatsList = peerStatsContent.addChild("ul");
				if (numberOfConnected > 0) {
					HTMLNode peerStatsConnectedListItem = peerStatsList.addChild("li").addChild("span");
					peerStatsConnectedListItem.addChild("span", new String[] { "class", "title", "style" }, 
							new String[] { "peer_connected", l10nDark("connected"), "border-bottom: 1px dotted; cursor: help;" }, l10nDark("connectedShort"));
					peerStatsConnectedListItem.addChild("span", ":\u00a0" + numberOfConnected);
				}
				if (numberOfRoutingBackedOff > 0) {
					HTMLNode peerStatsRoutingBackedOffListItem = peerStatsList.addChild("li").addChild("span");
					peerStatsRoutingBackedOffListItem.addChild("span", new String[] { "class", "title", "style" }, 
							new String[] { "peer_backed_off", l10nDark(advancedModeEnabled ? "backedOff" : "busy"), 
							"border-bottom: 1px dotted; cursor: help;" }, l10nDark((advancedModeEnabled ? "backedOff" : "busy")+"Short"));
					peerStatsRoutingBackedOffListItem.addChild("span", ":\u00a0" + numberOfRoutingBackedOff);
				}
				if (numberOfTooNew > 0) {
					HTMLNode peerStatsTooNewListItem = peerStatsList.addChild("li").addChild("span");
					peerStatsTooNewListItem.addChild("span", new String[] { "class", "title", "style" }, 
							new String[] { "peer_too_new", l10nDark("tooNew"), "border-bottom: 1px dotted; cursor: help;" }, l10nDark("tooNewShort"));
					peerStatsTooNewListItem.addChild("span", ":\u00a0" + numberOfTooNew);
				}
				if (numberOfTooOld > 0) {
					HTMLNode peerStatsTooOldListItem = peerStatsList.addChild("li").addChild("span");
					peerStatsTooOldListItem.addChild("span", new String[] { "class", "title", "style" }, 
							new String[] { "peer_too_old", l10nDark("tooOld"), "border-bottom: 1px dotted; cursor: help;" }, l10nDark("tooOldShort"));
					peerStatsTooOldListItem.addChild("span", ":\u00a0" + numberOfTooOld);
				}
				if (numberOfDisconnected > 0) {
					HTMLNode peerStatsDisconnectedListItem = peerStatsList.addChild("li").addChild("span");
					peerStatsDisconnectedListItem.addChild("span", new String[] { "class", "title", "style" }, 
							new String[] { "peer_disconnected", l10nDark("notConnected"), "border-bottom: 1px dotted; cursor: help;" }, l10nDark("notConnectedShort"));
					peerStatsDisconnectedListItem.addChild("span", ":\u00a0" + numberOfDisconnected);
				}
				if (numberOfNeverConnected > 0) {
					HTMLNode peerStatsNeverConnectedListItem = peerStatsList.addChild("li").addChild("span");
					peerStatsNeverConnectedListItem.addChild("span", new String[] { "class", "title", "style" },
							new String[] { "peer_never_connected", l10nDark("neverConnected"), "border-bottom: 1px dotted; cursor: help;" }, l10nDark("neverConnectedShort"));
					peerStatsNeverConnectedListItem.addChild("span", ":\u00a0" + numberOfNeverConnected);
				}
				if (numberOfDisabled > 0) {
					HTMLNode peerStatsDisabledListItem = peerStatsList.addChild("li").addChild("span");
					peerStatsDisabledListItem.addChild("span", new String[] { "class", "title", "style" }, 
							new String[] { "peer_disabled", l10nDark("disabled"), "border-bottom: 1px dotted; cursor: help;" }, l10nDark("disabledShort"));
					peerStatsDisabledListItem.addChild("span", ":\u00a0" + numberOfDisabled);
				}
				if (numberOfBursting > 0) {
					HTMLNode peerStatsBurstingListItem = peerStatsList.addChild("li").addChild("span");
					peerStatsBurstingListItem.addChild("span", new String[] { "class", "title", "style" }, 
							new String[] { "peer_bursting", l10nDark("bursting"), "border-bottom: 1px dotted; cursor: help;" }, l10nDark("burstingShort"));
					peerStatsBurstingListItem.addChild("span", ":\u00a0" + numberOfBursting);
				}
				if (numberOfListening > 0) {
					HTMLNode peerStatsListeningListItem = peerStatsList.addChild("li").addChild("span");
					peerStatsListeningListItem.addChild("span", new String[] { "class", "title", "style" }, 
							new String[] { "peer_listening", l10nDark("listening"), "border-bottom: 1px dotted; cursor: help;" }, l10nDark("listeningShort"));
					peerStatsListeningListItem.addChild("span", ":\u00a0" + numberOfListening);
				}
				if (numberOfListenOnly > 0) {
					HTMLNode peerStatsListenOnlyListItem = peerStatsList.addChild("li").addChild("span");
					peerStatsListenOnlyListItem.addChild("span", new String[] { "class", "title", "style" }, 
							new String[] { "peer_listen_only", l10nDark("listenOnly"), "border-bottom: 1px dotted; cursor: help;" }, l10nDark("listenOnlyShort"));
					peerStatsListenOnlyListItem.addChild("span", ":\u00a0" + numberOfListenOnly);
				}
				if (numberOfClockProblem > 0) {
					HTMLNode peerStatsRoutingDisabledListItem = peerStatsList.addChild("li").addChild("span");
					peerStatsRoutingDisabledListItem.addChild("span", new String[] { "class", "title", "style" }, new String[] { "peer_clock_problem", l10nDark("clockProblem"), "border-bottom: 1px dotted; cursor: help;" }, l10nDark("clockProblemShort"));
					peerStatsRoutingDisabledListItem.addChild("span", ":\u00a0" + numberOfClockProblem);
				}
				if (numberOfConnError > 0) {
					HTMLNode peerStatsRoutingDisabledListItem = peerStatsList.addChild("li").addChild("span");
					peerStatsRoutingDisabledListItem.addChild("span", new String[] { "class", "title", "style" }, new String[] { "peer_routing_disabled", l10nDark("connError"), "border-bottom: 1px dotted; cursor: help;" }, l10nDark("connErrorShort"));
					peerStatsRoutingDisabledListItem.addChild("span", ":\u00a0" + numberOfClockProblem);
				}
				if (numberOfDisconnecting > 0) {
					HTMLNode peerStatsListenOnlyListItem = peerStatsList.addChild("li").addChild("span");
					peerStatsListenOnlyListItem.addChild("span", new String[] { "class", "title", "style" }, new String[] { "peer_disconnecting", l10nDark("disconnecting"), "border-bottom: 1px dotted; cursor: help;" }, l10nDark("disconnectingShort"));
					peerStatsListenOnlyListItem.addChild("span", ":\u00a0" + numberOfDisconnecting);
				}
				if (numberOfSeedServers > 0) {
					HTMLNode peerStatsSeedServersListItem = peerStatsList.addChild("li").addChild("span");
					peerStatsSeedServersListItem.addChild("span", new String[] { "class", "title", "style" },
							new String[] { "peer_listening" /* FIXME */, l10nDark("seedServers"), "border-bottom: 1px dotted; cursor: help;" }, l10nDark("seedServersShort"));
					peerStatsSeedServersListItem.addChild("span", ":\u00a0" + numberOfSeedServers);
				}
				if (numberOfSeedClients > 0) {
					HTMLNode peerStatsSeedClientsListItem = peerStatsList.addChild("li").addChild("span");
					peerStatsSeedClientsListItem.addChild("span", new String[] { "class", "title", "style" },
							new String[] { "peer_listening" /* FIXME */, l10nDark("seedClients"), "border-bottom: 1px dotted; cursor: help;" }, l10nDark("seedClientsShort"));
					peerStatsSeedClientsListItem.addChild("span", ":\u00a0" + numberOfSeedClients);
				}
				if (numberOfRoutingDisabled > 0) {
					HTMLNode peerStatsRoutingDisabledListItem = peerStatsList.addChild("li").addChild("span");
					peerStatsRoutingDisabledListItem.addChild("span", new String[] { "class", "title", "style" }, new String[] { "peer_routing_disabled", l10nDark("routingDisabled"), "border-bottom: 1px dotted; cursor: help;" }, l10nDark("routingDisabledShort"));
					peerStatsRoutingDisabledListItem.addChild("span", ":\u00a0" + numberOfRoutingDisabled);
				}
			}
			
			@Override
			public String getUpdaterType() {
				return UpdaterConstants.REPLACER_UPDATER;
			}
			
			@Override
			public String getUpdaterId(String requestId) {
				return "Statistics_Page_Peers";
			}
			
			@Override
			public void dispose() {
				peers.removePeerStatusChangeListener(listener);
			}
		});
	}

	private static String l10n(String key) {
		return L10n.getString("StatisticsToadlet."+key);
	}
	
	private static String l10nDark(String key) {
		return L10n.getString("DarknetConnectionsToadlet."+key);
	}

	private static String l10n(String key, String pattern, String value) {
		return L10n.getString("StatisticsToadlet."+key, new String[] { pattern }, new String[] { value });
	}
	
	private static String l10n(String key, String[] patterns, String[] values) {
		return L10n.getString("StatisticsToadlet."+key, patterns, values);
	}
	
	public static void drawActivityBox(HTMLNode activityInfobox, final boolean advancedModeEnabled,final Node node,final boolean showArkFetchers,final ToadletContext ctx) {
		
		activityInfobox.addChild(new StatisticsUpdateableElement(ctx) {
			
			@Override
			public void updateState(boolean initial) {
				children.clear();
				
				addChild("div", "class", "infobox-header", l10nDark("activityTitle"));
				HTMLNode activityInfoboxContent = addChild("div", "class", "infobox-content");
				
				HTMLNode activityList = drawActivity(activityInfoboxContent, node);
				int numARKFetchers = node.getNumARKFetchers();
				if (activityList!=null && showArkFetchers && numARKFetchers > 0) {
					activityList.addChild("li", "ARK\u00a0Fetch\u00a0Requests:\u00a0" + numARKFetchers);
				}

				if (advancedModeEnabled && activityList != null) {
					if (numARKFetchers > 0)
						activityList.addChild("li", "ARK\u00a0Fetch\u00a0Requests:\u00a0" + numARKFetchers);
					activityList.addChild("li", "FetcherByUSKSize:\u00a0" + node.clientCore.uskManager.getFetcherByUSKSize());
					activityList.addChild("li", "BackgroundFetcherByUSKSize:\u00a0" + node.clientCore.uskManager.getBackgroundFetcherByUSKSize());
					activityList.addChild("li", "temporaryBackgroundFetchersLRUSize:\u00a0" + node.clientCore.uskManager.getTemporaryBackgroundFetchersLRU());
				}
			}
		});
	}
	
	private static void drawBandwidth(HTMLNode activityList, Node node, long nodeUptimeSeconds, boolean isAdvancedModeEnabled) {
		long[] total = node.collector.getTotalIO();
		if(total[0] == 0 || total[1] == 0)
			return;
		long total_output_rate = (total[0]) / nodeUptimeSeconds;
		long total_input_rate = (total[1]) / nodeUptimeSeconds;
		long totalPayload = node.getTotalPayloadSent();
		long total_payload_rate = totalPayload / nodeUptimeSeconds;
		int percent = (int) (100 * totalPayload / total[0]);
		long[] rate = node.nodeStats.getNodeIOStats();
		long delta = (rate[5] - rate[2]) / 1000;
		if(delta > 0) {
			long output_rate = (rate[3] - rate[0]) / delta;
			long input_rate = (rate[4] - rate[1]) / delta;
			SubConfig nodeConfig = node.config.get("node");
			int outputBandwidthLimit = nodeConfig.getInt("outputBandwidthLimit");
			int inputBandwidthLimit = nodeConfig.getInt("inputBandwidthLimit");
			if(inputBandwidthLimit == -1) {
				inputBandwidthLimit = outputBandwidthLimit * 4;
			}
			activityList.addChild("li", l10n("inputRate", new String[] { "rate", "max" }, new String[] { SizeUtil.formatSize(input_rate, true), SizeUtil.formatSize(inputBandwidthLimit, true) }));
			activityList.addChild("li", l10n("outputRate", new String[] { "rate", "max" }, new String[] { SizeUtil.formatSize(output_rate, true), SizeUtil.formatSize(outputBandwidthLimit, true) }));
		}
		activityList.addChild("li", l10n("totalInput", new String[] { "total", "rate" }, new String[] { SizeUtil.formatSize(total[1], true), SizeUtil.formatSize(total_input_rate, true) }));
		activityList.addChild("li", l10n("totalOutput", new String[] { "total", "rate" }, new String[] { SizeUtil.formatSize(total[0], true), SizeUtil.formatSize(total_output_rate, true) } ));
		activityList.addChild("li", l10n("payloadOutput", new String[] { "total", "rate", "percent" }, new String[] { SizeUtil.formatSize(totalPayload, true), SizeUtil.formatSize(total_payload_rate, true), Integer.toString(percent) } ));
		if(isAdvancedModeEnabled) {
			long totalBytesSentCHKRequests = node.nodeStats.getCHKRequestTotalBytesSent();
			long totalBytesSentSSKRequests = node.nodeStats.getSSKRequestTotalBytesSent();
			long totalBytesSentCHKInserts = node.nodeStats.getCHKInsertTotalBytesSent();
			long totalBytesSentSSKInserts = node.nodeStats.getSSKInsertTotalBytesSent();
			long totalBytesSentOfferedKeys = node.nodeStats.getOfferedKeysTotalBytesSent();
			long totalBytesSendOffers = node.nodeStats.getOffersSentBytesSent();
			long totalBytesSentSwapOutput = node.nodeStats.getSwappingTotalBytesSent();
			long totalBytesSentAuth = node.nodeStats.getTotalAuthBytesSent();
			long totalBytesSentAckOnly = node.nodeStats.getNotificationOnlyPacketsSentBytes();
			long totalBytesSentResends = node.nodeStats.getResendBytesSent();
			long totalBytesSentUOM = node.nodeStats.getUOMBytesSent();
			long totalBytesSentAnnounce = node.nodeStats.getAnnounceBytesSent();
			long totalBytesSentRoutingStatus = node.nodeStats.getRoutingStatusBytes();
			long totalBytesSentNetworkColoring = node.nodeStats.getNetworkColoringSentBytes();
			long totalBytesSentPing = node.nodeStats.getPingSentBytes();
			long totalBytesSentProbeRequest = node.nodeStats.getProbeRequestSentBytes();
			long totalBytesSentRouted = node.nodeStats.getRoutedMessageSentBytes();
			long totalBytesSentDisconn = node.nodeStats.getDisconnBytesSent();
			long totalBytesSentInitial = node.nodeStats.getInitialMessagesBytesSent();
			long totalBytesSentChangedIP = node.nodeStats.getChangedIPBytesSent();
			long totalBytesSentNodeToNode = node.nodeStats.getNodeToNodeBytesSent();
			long totalBytesSentRemaining = total[0] - 
				(totalPayload + totalBytesSentCHKRequests + totalBytesSentSSKRequests +
				totalBytesSentCHKInserts + totalBytesSentSSKInserts +
				totalBytesSentOfferedKeys + totalBytesSendOffers + totalBytesSentSwapOutput + 
				totalBytesSentAuth + totalBytesSentAckOnly + totalBytesSentResends +
				totalBytesSentUOM + totalBytesSentAnnounce + 
				totalBytesSentRoutingStatus + totalBytesSentNetworkColoring + totalBytesSentPing +
				totalBytesSentProbeRequest + totalBytesSentRouted + totalBytesSentDisconn + 
				totalBytesSentInitial + totalBytesSentChangedIP + totalBytesSentNodeToNode);
			activityList.addChild("li", l10n("requestOutput", new String[] { "chk", "ssk" }, new String[] { SizeUtil.formatSize(totalBytesSentCHKRequests, true), SizeUtil.formatSize(totalBytesSentSSKRequests, true) }));
			activityList.addChild("li", l10n("insertOutput", new String[] { "chk", "ssk" }, new String[] { SizeUtil.formatSize(totalBytesSentCHKInserts, true), SizeUtil.formatSize(totalBytesSentSSKInserts, true) }));
			activityList.addChild("li", l10n("offeredKeyOutput", new String[] { "total", "offered" }, new String[] { SizeUtil.formatSize(totalBytesSentOfferedKeys, true), SizeUtil.formatSize(totalBytesSendOffers, true) }));
			activityList.addChild("li", l10n("swapOutput", "total", SizeUtil.formatSize(totalBytesSentSwapOutput, true)));
			activityList.addChild("li", l10n("authBytes", "total", SizeUtil.formatSize(totalBytesSentAuth, true)));
			activityList.addChild("li", l10n("ackOnlyBytes", "total", SizeUtil.formatSize(totalBytesSentAckOnly, true)));
			activityList.addChild("li", l10n("resendBytes", "total", SizeUtil.formatSize(totalBytesSentResends, true)));
			activityList.addChild("li", l10n("uomBytes", "total",  SizeUtil.formatSize(totalBytesSentUOM, true)));
			activityList.addChild("li", l10n("announceBytes", "total", SizeUtil.formatSize(totalBytesSentAnnounce, true)));
			activityList.addChild("li", l10n("adminBytes", new String[] { "routingStatus", "disconn", "initial", "changedIP" }, new String[] { SizeUtil.formatSize(totalBytesSentRoutingStatus, true), SizeUtil.formatSize(totalBytesSentDisconn, true), SizeUtil.formatSize(totalBytesSentInitial, true), SizeUtil.formatSize(totalBytesSentChangedIP, true) }));
			activityList.addChild("li", l10n("debuggingBytes", new String[] { "netColoring", "ping", "probe", "routed" }, new String[] { SizeUtil.formatSize(totalBytesSentNetworkColoring, true), SizeUtil.formatSize(totalBytesSentPing, true), SizeUtil.formatSize(totalBytesSentProbeRequest, true), SizeUtil.formatSize(totalBytesSentRouted, true) } ));
			activityList.addChild("li", l10n("nodeToNodeBytes", "total", SizeUtil.formatSize(totalBytesSentNodeToNode, true)));
			activityList.addChild("li", l10n("unaccountedBytes", new String[] { "total", "percent" },
					new String[] { SizeUtil.formatSize(totalBytesSentRemaining, true), Integer.toString((int)(totalBytesSentRemaining*100 / total[0])) }));
			double sentOverheadPerSecond = node.nodeStats.getSentOverheadPerSecond();
			activityList.addChild("li", l10n("totalOverhead", new String[] { "rate", "percent" }, 
					new String[] { SizeUtil.formatSize((long)sentOverheadPerSecond), Integer.toString((int)((100 * sentOverheadPerSecond) / total_output_rate)) }));
		}
	}

	private static HTMLNode drawActivity(HTMLNode activityInfoboxContent, Node node) {
		int numInserts = node.getNumInsertSenders();
		int numLocalCHKInserts = node.getNumLocalCHKInserts();
		int numRemoteCHKInserts = node.getNumRemoteCHKInserts();
		int numLocalSSKInserts = node.getNumLocalSSKInserts();
		int numRemoteSSKInserts = node.getNumRemoteSSKInserts();
		int numRequests = node.getNumRequestSenders();
		int numLocalCHKRequests = node.getNumLocalCHKRequests();
		int numRemoteCHKRequests = node.getNumRemoteCHKRequests();
		int numLocalSSKRequests = node.getNumLocalSSKRequests();
		int numRemoteSSKRequests = node.getNumRemoteSSKRequests();
		int numTransferringRequests = node.getNumTransferringRequestSenders();
		int numTransferringRequestHandlers = node.getNumTransferringRequestHandlers();
		int numCHKOfferReplys = node.getNumCHKOfferReplies();
		int numSSKOfferReplys = node.getNumSSKOfferReplies();
		int numCHKRequests = numLocalCHKRequests + numRemoteCHKRequests;
		int numSSKRequests = numLocalSSKRequests + numRemoteSSKRequests;
		int numCHKInserts = numLocalCHKInserts + numRemoteCHKInserts;
		int numSSKInserts = numLocalSSKInserts + numRemoteSSKInserts;
		int numIncomingTurtles = node.getNumIncomingTurtles();
		if ((numInserts == 0) && (numRequests == 0) && (numTransferringRequests == 0) &&
				(numCHKRequests == 0) && (numSSKRequests == 0) &&
				(numCHKInserts == 0) && (numSSKInserts == 0) &&
				(numTransferringRequestHandlers == 0) && 
				(numCHKOfferReplys == 0) && (numSSKOfferReplys == 0)) {
			activityInfoboxContent.addChild("#", l10n("noRequests"));
			return null;
		} else {
			HTMLNode activityList = activityInfoboxContent.addChild("ul");
			if (numInserts > 0 || numCHKInserts > 0 || numSSKInserts > 0) {
				activityList.addChild("li", L10n.getString("StatisticsToadlet.activityInserts", 
						new String[] { "totalSenders", "CHKhandlers", "SSKhandlers", "local" } , 
						new String[] { Integer.toString(numInserts), Integer.toString(numCHKInserts), Integer.toString(numSSKInserts), Integer.toString(numLocalCHKInserts + numLocalSSKInserts)}));
			}
			if (numRequests > 0 || numCHKRequests > 0 || numSSKRequests > 0) {
				activityList.addChild("li", L10n.getString("StatisticsToadlet.activityRequests", 
						new String[] { "totalSenders", "CHKhandlers", "SSKhandlers", "local" } , 
						new String[] { Integer.toString(numRequests), Integer.toString(numCHKRequests), Integer.toString(numSSKRequests), Integer.toString(numLocalCHKRequests + numLocalSSKRequests)}));
			}
			if (numTransferringRequests > 0 || numTransferringRequestHandlers > 0 || numIncomingTurtles > 0) {
				activityList.addChild("li", L10n.getString("StatisticsToadlet.transferringRequests", 
						new String[] { "senders", "receivers", "turtles" }, new String[] { Integer.toString(numTransferringRequests), Integer.toString(numTransferringRequestHandlers), Integer.toString(numIncomingTurtles)}));
			}
			if (numCHKOfferReplys > 0 || numSSKOfferReplys > 0) {
				activityList.addChild("li", L10n.getString("StatisticsToadlet.offerReplys", 
						new String[] { "chk", "ssk" }, new String[] { Integer.toString(numCHKOfferReplys), Integer.toString(numSSKOfferReplys) }));
			}
			return activityList;
		}
	}

	private void drawOverviewBox(HTMLNode overviewInfobox, final long nodeUptimeSeconds, final long now, final double swaps, final double noSwaps,final ToadletContext ctx) {
		overviewInfobox.addChild(new StatisticsUpdateableElement(ctx) {
			
			@Override
			public void updateState(boolean initial) {
				children.clear();
				
				addChild("div", "class", "infobox-header", "Node status overview");
				HTMLNode overviewInfoboxContent = addChild("div", "class", "infobox-content");
				HTMLNode overviewList = overviewInfoboxContent.addChild("ul");
				/* node status values */
				int bwlimitDelayTime = (int) stats.getBwlimitDelayTime();
				int nodeAveragePingTime = (int) stats.getNodeAveragePingTime();
				double numberOfRemotePeerLocationsSeenInSwaps = node.getNumberOfRemotePeerLocationsSeenInSwaps();

				// Darknet
				int darknetSizeEstimateSession = stats.getDarknetSizeEstimate(-1);
				int darknetSizeEstimate24h = 0;
				int darknetSizeEstimate48h = 0;
				if(nodeUptimeSeconds > (24*60*60)) {  // 24 hours
					darknetSizeEstimate24h = stats.getDarknetSizeEstimate(now - (24*60*60*1000));  // 48 hours
				}
				if(nodeUptimeSeconds > (48*60*60)) {  // 48 hours
					darknetSizeEstimate48h = stats.getDarknetSizeEstimate(now - (48*60*60*1000));  // 48 hours
				}
				// Opennet
				int opennetSizeEstimateSession = stats.getOpennetSizeEstimate(-1);
				int opennetSizeEstimate24h = 0;
				int opennetSizeEstimate48h = 0;
				if (nodeUptimeSeconds > (24 * 60 * 60)) { // 24 hours
					opennetSizeEstimate24h = stats.getOpennetSizeEstimate(now - (24 * 60 * 60 * 1000)); // 48 hours
				}
				if (nodeUptimeSeconds > (48 * 60 * 60)) { // 48 hours
					opennetSizeEstimate48h = stats.getOpennetSizeEstimate(now - (48 * 60 * 60 * 1000)); // 48 hours
				}
				
				double routingMissDistance =  stats.routingMissDistance.currentValue();
				double backedOffPercent =  stats.backedOffPercent.currentValue();
				String nodeUptimeString = TimeUtil.formatTime(nodeUptimeSeconds * 1000);  // *1000 to convert to milliseconds
				overviewList.addChild("li", "bwlimitDelayTime:\u00a0" + bwlimitDelayTime + "ms");
				overviewList.addChild("li", "nodeAveragePingTime:\u00a0" + nodeAveragePingTime + "ms");
				overviewList.addChild("li", "darknetSizeEstimateSession:\u00a0" + darknetSizeEstimateSession + "\u00a0nodes");
				if(nodeUptimeSeconds > (24*60*60)) {  // 24 hours
					overviewList.addChild("li", "darknetSizeEstimate24h:\u00a0" + darknetSizeEstimate24h + "\u00a0nodes");
				}
				if(nodeUptimeSeconds > (48*60*60)) {  // 48 hours
					overviewList.addChild("li", "darknetSizeEstimate48h:\u00a0" + darknetSizeEstimate48h + "\u00a0nodes");
				}
				overviewList.addChild("li", "opennetSizeEstimateSession:\u00a0" + opennetSizeEstimateSession + "\u00a0nodes");
				if (nodeUptimeSeconds > (24 * 60 * 60)) { // 24 hours
					overviewList.addChild("li", "opennetSizeEstimate24h:\u00a0" + opennetSizeEstimate24h + "\u00a0nodes");
				}
				if (nodeUptimeSeconds > (48 * 60 * 60)) { // 48 hours
					overviewList.addChild("li", "opennetSizeEstimate48h:\u00a0" + opennetSizeEstimate48h + "\u00a0nodes");
				}
				if ((numberOfRemotePeerLocationsSeenInSwaps > 0.0) && ((swaps > 0.0) || (noSwaps > 0.0))) {
					overviewList.addChild("li", "avrConnPeersPerNode:\u00a0" + fix6p6.format(numberOfRemotePeerLocationsSeenInSwaps/(swaps+noSwaps)) + "\u00a0peers");
				}
				overviewList.addChild("li", "nodeUptime:\u00a0" + nodeUptimeString);
				overviewList.addChild("li", "routingMissDistance:\u00a0" + fix1p4.format(routingMissDistance));
				overviewList.addChild("li", "backedOffPercent:\u00a0" + fix3p1pct.format(backedOffPercent));
				overviewList.addChild("li", "pInstantReject:\u00a0" + fix3p1pct.format(stats.pRejectIncomingInstantly()));
				overviewList.addChild("li", "unclaimedFIFOSize:\u00a0" + node.getUnclaimedFIFOSize());
				overviewList.addChild("li", "RAMBucketPoolSize:\u00a0" + SizeUtil.formatSize(core.tempBucketFactory.getRamUsed())+ " / "+ SizeUtil.formatSize(core.tempBucketFactory.getMaxRamUsed()));
				overviewList.addChild("li", "uptimeAverage:\u00a0" + fix3p1pct.format(node.uptime.getUptime()));
			}
		});
				
	}

	public static void drawBandwidthBox(HTMLNode bandwidthInfobox, final long nodeUptimeSeconds, final boolean isAdvancedModeEnabled,final Node node,final ToadletContext ctx) {
		
		bandwidthInfobox.addChild(new StatisticsUpdateableElement(ctx) {
			
			@Override
			public void updateState(boolean initial) {
				children.clear();
				
				addChild("div", "class", "infobox-header", l10n("bandwidthTitle"));
				HTMLNode bandwidthInfoboxContent = addChild("div", "class", "infobox-content");
				HTMLNode bandwidthList = bandwidthInfoboxContent.addChild("ul");
				drawBandwidth(bandwidthList, node, nodeUptimeSeconds, isAdvancedModeEnabled);
			}
		});
	}

	// FIXME this should probably be moved to nodestats so it can be used by FCP??? would have to make ThreadBunch public :<
	private void getThreadNames(HTMLNode threadUsageList) {
		int count = 0;
		Thread[] threads;
		while(true) {
			count = Math.max(stats.rootThreadGroup.activeCount(), count);
			threads = new Thread[count*2+50];
			stats.rootThreadGroup.enumerate(threads);
			if(threads[threads.length-1] == null) break;
		}
		LinkedHashMap<String, ThreadBunch> map = new LinkedHashMap<String, ThreadBunch>();
		int totalCount = 0;
		for(int i=0;i<threads.length;i++) {
			if(threads[i] == null) break;
			String name = threads[i].getName();
			if(name.indexOf(" for ") != -1)
				name = name.substring(0, name.indexOf(" for "));
			if(name.indexOf("@") != -1)
				name = name.substring(0, name.indexOf("@"));
			if (name.indexOf("(") != -1)
				name = name.substring(0, name.indexOf("("));
			ThreadBunch bunch = map.get(name);
			if(bunch != null) {
				bunch.count++;
			} else {
				map.put(name, new ThreadBunch(name, 1));
			}
			totalCount++;
		}
		ThreadBunch[] bunches = map.values().toArray(new ThreadBunch[map.size()]);
		Arrays.sort(bunches, new Comparator<ThreadBunch>() {
			public int compare(ThreadBunch b0, ThreadBunch b1) {
				if(b0.count > b1.count) return -1;
				if(b0.count < b1.count) return 1;
				return b0.name.compareTo(b1.name);
			}

		});
		double thisThreadPercentOfTotal;
		for(int i=0; i<bunches.length; i++) {
			thisThreadPercentOfTotal = ((double) bunches[i].count) / ((double) totalCount);
			threadUsageList.addChild("li", "" + bunches[i].name + ":\u00a0" + Integer.toString(bunches[i].count) + "\u00a0(" + fix3p1pct.format(thisThreadPercentOfTotal) + ')');
		}
	}

	private static class ThreadBunch {
		public ThreadBunch(String name2, int i) {
			this.name = name2;
			this.count = i;
		}
		String name;
		int count;
	}

	private final static int PEER_CIRCLE_RADIUS = 100;
	private final static int PEER_CIRCLE_INNER_RADIUS = 60;
	private final static int PEER_CIRCLE_ADDITIONAL_FREE_SPACE = 10;
	private final static long MAX_CIRCLE_AGE_THRESHOLD = 24l*60*60*1000;   // 24 hours
	private final static int HISTOGRAM_LENGTH = 10;

	private void addNodeCircle (HTMLNode circleTable, double myLocation) {
		int[] histogram = new int[HISTOGRAM_LENGTH];
		for (int i = 0; i < HISTOGRAM_LENGTH; i++) {
			histogram[i] = 0;
		}
		HTMLNode nodeCircleTableRow = circleTable.addChild("tr");
		HTMLNode nodeHistogramLegendTableRow = circleTable.addChild("tr");
		HTMLNode nodeHistogramGraphTableRow = circleTable.addChild("tr");
		HTMLNode nodeCircleTableCell = nodeCircleTableRow.addChild("td", new String[] { "class", "colspan" }, new String[] {"first", "10"});
		HTMLNode nodeHistogramLegendCell;
		HTMLNode nodeHistogramGraphCell;
		HTMLNode nodeCircleInfoboxContent = nodeCircleTableCell.addChild("div", new String[] { "style", "class" }, new String[] {"position: relative; height: " + ((PEER_CIRCLE_RADIUS + PEER_CIRCLE_ADDITIONAL_FREE_SPACE) * 2) + "px; width: " + ((PEER_CIRCLE_RADIUS + PEER_CIRCLE_ADDITIONAL_FREE_SPACE) * 2) + "px", "peercircle" });
		nodeCircleInfoboxContent.addChild("span", new String[] { "style", "class" }, new String[] { generatePeerCircleStyleString(0, false, 1.0),	 "mark" }, "|");
		nodeCircleInfoboxContent.addChild("span", new String[] { "style", "class" }, new String[] { generatePeerCircleStyleString(0.125, false, 1.0), "mark" }, "+");
		nodeCircleInfoboxContent.addChild("span", new String[] { "style", "class" }, new String[] { generatePeerCircleStyleString(0.25, false, 1.0),  "mark" }, "--");
		nodeCircleInfoboxContent.addChild("span", new String[] { "style", "class" }, new String[] { generatePeerCircleStyleString(0.375, false, 1.0), "mark" }, "+");
		nodeCircleInfoboxContent.addChild("span", new String[] { "style", "class" }, new String[] { generatePeerCircleStyleString(0.5, false, 1.0),   "mark" }, "|");
		nodeCircleInfoboxContent.addChild("span", new String[] { "style", "class" }, new String[] { generatePeerCircleStyleString(0.625, false, 1.0), "mark" }, "+");
		nodeCircleInfoboxContent.addChild("span", new String[] { "style", "class" }, new String[] { generatePeerCircleStyleString(0.75, false, 1.0),  "mark" }, "--");
		nodeCircleInfoboxContent.addChild("span", new String[] { "style", "class" }, new String[] { generatePeerCircleStyleString(0.875, false, 1.0), "mark" }, "+");
		nodeCircleInfoboxContent.addChild("span", new String[] { "style", "class" }, new String[] { generatePeerCircleStyleString(0.875, false, 1.0), "mark" }, "+");
		nodeCircleInfoboxContent.addChild("span", new String[] { "style", "class" }, new String[] { "position: absolute; top: " + PEER_CIRCLE_RADIUS + "px; left: " + (PEER_CIRCLE_RADIUS + PEER_CIRCLE_ADDITIONAL_FREE_SPACE) + "px", "mark" }, "+");
		final Object[] knownLocsCopy = stats.getKnownLocations(-1);
		final Double[] locations = (Double[])knownLocsCopy[0];
		final Long[] timestamps = (Long[])knownLocsCopy[1];
		Double location;
		Long locationTime;
		double strength = 1.0;
		long now = System.currentTimeMillis();
		long age = 1;
		int histogramIndex;
		int nodeCount = 0;
		for(int i=0; i<locations.length; i++){
			nodeCount += 1;
			location = locations[i];
			locationTime = timestamps[i];
			age = now - locationTime.longValue();
			if( age > MAX_CIRCLE_AGE_THRESHOLD ) {
				age = MAX_CIRCLE_AGE_THRESHOLD;
			}
			strength = 1 - ((double) age / MAX_CIRCLE_AGE_THRESHOLD );
			histogramIndex = (int) (Math.floor(location.doubleValue() * HISTOGRAM_LENGTH));
			histogram[histogramIndex]++;
			
			nodeCircleInfoboxContent.addChild("span", new String[] { "style", "class" }, new String[] { generatePeerCircleStyleString(location.doubleValue(), false, strength), "connected" }, "x");
		}
		nodeCircleInfoboxContent.addChild("span", new String[] { "style", "class" }, new String[] { generatePeerCircleStyleString(myLocation, true, 1.0), "me" }, "x");
		//
		double histogramPercent;
		for (int i = 0; i < HISTOGRAM_LENGTH; i++) {
			nodeHistogramLegendCell = nodeHistogramLegendTableRow.addChild("td");
			nodeHistogramGraphCell = nodeHistogramGraphTableRow.addChild("td", "style", "height: 100px;");
			nodeHistogramLegendCell.addChild("div", "class", "histogramLabel").addChild("#", fix1p1.format(((double) i) / HISTOGRAM_LENGTH ));
			histogramPercent = ((double) histogram[ i ] ) / nodeCount;
			
			// Don't use HTMLNode here to speed things up
			nodeHistogramGraphCell.addChild("%", "<div class=\"histogramConnected\" style=\"height: " + fix3pctUS.format(histogramPercent) + "; width: 100%;\">\u00a0</div>");
		}
	}
	
	private void addSpecialisation(HTMLNode table, double peerLocation, int incomingRequestsCount, int[] incomingRequestLocation) {
		HTMLNode nodeHistogramLegendTableRow = table.addChild("tr");
		HTMLNode nodeHistogramGraphTableRow = table.addChild("tr");
		int myIndex = (int)(peerLocation * incomingRequestLocation.length);
		for (int i = 0; i<incomingRequestLocation.length; i++) {
			HTMLNode nodeHistogramLegendCell = nodeHistogramLegendTableRow.addChild("td");
			HTMLNode nodeHistogramGraphCell = nodeHistogramGraphTableRow.addChild("td", "style", "height: 100px;");
			HTMLNode nodeHistogramGraphCell2 = nodeHistogramLegendCell.addChild("div", "class", "histogramLabel");
			if(i == myIndex)
				 nodeHistogramGraphCell2 = nodeHistogramGraphCell2.addChild("span", "class", "me");
			nodeHistogramGraphCell2.addChild("#", fix1p1.format(((double) i) / incomingRequestLocation.length ));
			nodeHistogramGraphCell.addChild("div", new String[] { "class", "style" }, new String[] { "histogramConnected", "height: " + fix3pctUS.format(((double)incomingRequestLocation[i]) / incomingRequestsCount) + "; width: 100%;" }, "\u00a0");
		}
	}
	
	private void addCombinedSpecialisation(HTMLNode table, double peerLocation, int locallyOriginatingRequestsCount, int[] locallyOriginatingRequests, int remotelyOriginatingRequestsCount, int[] remotelyOriginatingRequests) {
		assert(locallyOriginatingRequests.length == remotelyOriginatingRequests.length);
		HTMLNode nodeHistogramLegendTableRow = table.addChild("tr");
		HTMLNode nodeHistogramGraphTableRow = table.addChild("tr");
		int myIndex = (int)(peerLocation * locallyOriginatingRequests.length);
		for (int i = 0; i<locallyOriginatingRequests.length; i++) {
			HTMLNode nodeHistogramLegendCell = nodeHistogramLegendTableRow.addChild("td");
			HTMLNode nodeHistogramGraphCell = nodeHistogramGraphTableRow.addChild("td", "style", "height: 100px;");
			HTMLNode nodeHistogramGraphCell2 = nodeHistogramLegendCell.addChild("div", "class", "histogramLabel");
			if(i == myIndex)
				 nodeHistogramGraphCell2 = nodeHistogramGraphCell2.addChild("span", "class", "me");
			nodeHistogramGraphCell2.addChild("#", fix1p1.format(((double) i) / locallyOriginatingRequests.length ));
			nodeHistogramGraphCell.addChild("div",
				new String[] { "class", "style" },
				new String[] { "histogramConnected", "height: " +
					fix3pctUS.format(((double)locallyOriginatingRequests[i]) / locallyOriginatingRequestsCount) +
					"; width: 100%;" },
				"\u00a0");
			nodeHistogramGraphCell.addChild("div",
				new String[] { "class", "style" },
				new String[] { "histogramDisconnected", "height: " +
					fix3pctUS.format(((double)remotelyOriginatingRequests[i]) / remotelyOriginatingRequestsCount) +
					"; width: 100%;" },
				"\u00a0");
		}
	}

	private void addPeerCircle (HTMLNode circleTable, PeerNodeStatus[] peerNodeStatuses, double myLocation) {
		int[] histogramConnected = new int[HISTOGRAM_LENGTH];
		int[] histogramDisconnected = new int[HISTOGRAM_LENGTH];
		for (int i = 0; i < HISTOGRAM_LENGTH; i++) {
			histogramConnected[i] = 0;
			histogramDisconnected[i] = 0;
		}
		HTMLNode peerCircleTableRow = circleTable.addChild("tr");
		HTMLNode peerHistogramLegendTableRow = circleTable.addChild("tr");
		HTMLNode peerHistogramGraphTableRow = circleTable.addChild("tr");
		HTMLNode peerCircleTableCell = peerCircleTableRow.addChild("td", new String[] { "class", "colspan" }, new String[] {"first", "10"});
		HTMLNode peerHistogramLegendCell;
		HTMLNode peerHistogramGraphCell;
		HTMLNode peerCircleInfoboxContent = peerCircleTableCell.addChild("div", new String[] { "style", "class" }, new String[] {"position: relative; height: " + ((PEER_CIRCLE_RADIUS + PEER_CIRCLE_ADDITIONAL_FREE_SPACE) * 2) + "px; width: " + ((PEER_CIRCLE_RADIUS + PEER_CIRCLE_ADDITIONAL_FREE_SPACE) * 2) + "px", "peercircle" });
		peerCircleInfoboxContent.addChild("span", new String[] { "style", "class" }, new String[] { generatePeerCircleStyleString(0, false, 1.0),	 "mark" }, "|");
		peerCircleInfoboxContent.addChild("span", new String[] { "style", "class" }, new String[] { generatePeerCircleStyleString(0.125, false, 1.0), "mark" }, "+");
		peerCircleInfoboxContent.addChild("span", new String[] { "style", "class" }, new String[] { generatePeerCircleStyleString(0.25, false, 1.0),  "mark" }, "--");
		peerCircleInfoboxContent.addChild("span", new String[] { "style", "class" }, new String[] { generatePeerCircleStyleString(0.375, false, 1.0), "mark" }, "+");
		peerCircleInfoboxContent.addChild("span", new String[] { "style", "class" }, new String[] { generatePeerCircleStyleString(0.5, false, 1.0),   "mark" }, "|");
		peerCircleInfoboxContent.addChild("span", new String[] { "style", "class" }, new String[] { generatePeerCircleStyleString(0.625, false, 1.0), "mark" }, "+");
		peerCircleInfoboxContent.addChild("span", new String[] { "style", "class" }, new String[] { generatePeerCircleStyleString(0.75, false, 1.0),  "mark" }, "--");
		peerCircleInfoboxContent.addChild("span", new String[] { "style", "class" }, new String[] { "position: absolute; top: " + PEER_CIRCLE_RADIUS + "px; left: " + (PEER_CIRCLE_RADIUS + PEER_CIRCLE_ADDITIONAL_FREE_SPACE) + "px", "mark" }, "+");

		PeerNodeStatus peerNodeStatus;
		double peerLocation;
		double peerDistance;
		int histogramIndex;
		int peerCount = peerNodeStatuses.length;
		int newPeerCount = 0;
		for (int peerIndex = 0; peerIndex < peerCount; peerIndex++) {
			peerNodeStatus = peerNodeStatuses[peerIndex];
			peerLocation = peerNodeStatus.getLocation();
			if(!peerNodeStatus.isSearchable()) continue;
			if(peerLocation < 0.0 || peerLocation > 1.0) continue;
			newPeerCount++;
			peerDistance = Location.distance( myLocation, peerLocation );
			histogramIndex = (int) (Math.floor(peerDistance * HISTOGRAM_LENGTH * 2));
			if (peerNodeStatus.isConnected()) {
				histogramConnected[histogramIndex]++;
			} else {
				histogramDisconnected[histogramIndex]++;
			}
			peerCircleInfoboxContent.addChild("span", new String[] { "style", "class" }, new String[] { generatePeerCircleStyleString(peerLocation, false, (1.0 - peerNodeStatus.getPReject())), ((peerNodeStatus.isConnected())?"connected":"disconnected") }, ((peerNodeStatus.isOpennet())?"o":"x"));
		}
		peerCircleInfoboxContent.addChild("span", new String[] { "style", "class" }, new String[] { generatePeerCircleStyleString(myLocation, true, 1.0), "me" }, "x");
		//
		double histogramPercent;
		for (int i = 0; i < HISTOGRAM_LENGTH; i++) {
			peerHistogramLegendCell = peerHistogramLegendTableRow.addChild("td");
			peerHistogramGraphCell = peerHistogramGraphTableRow.addChild("td", "style", "height: 100px;");
			peerHistogramLegendCell.addChild("div", "class", "histogramLabel").addChild("#", fix1p2.format(((double) i) / ( HISTOGRAM_LENGTH * 2 )));
			//
			histogramPercent = ((double) histogramConnected[ i ] ) / newPeerCount;
			peerHistogramGraphCell.addChild("div", new String[] { "class", "style" }, new String[] { "histogramConnected", "height: " + fix3pctUS.format(histogramPercent) + "; width: 100%;" }, "\u00a0");
			//
			histogramPercent = ((double) histogramDisconnected[ i ] ) / newPeerCount;
			peerHistogramGraphCell.addChild("div", new String[] { "class", "style" }, new String[] { "histogramDisconnected", "height: " + fix3pctUS.format(histogramPercent) + "; width: 100%;" }, "\u00a0");
		}
	}

	private String generatePeerCircleStyleString (double peerLocation, boolean offsetMe, double strength) {
		peerLocation *= Math.PI * 2;
		//
		int offset = 0;
		if( offsetMe ) {
			// Make our own peer stand out from the crowd better so we can see it easier
			offset = -10;
		} else {
			offset = (int) (PEER_CIRCLE_INNER_RADIUS * (1.0 - strength));
		}
		double x = PEER_CIRCLE_ADDITIONAL_FREE_SPACE + PEER_CIRCLE_RADIUS + Math.sin(peerLocation) * (PEER_CIRCLE_RADIUS - offset);
		double y = PEER_CIRCLE_RADIUS - Math.cos(peerLocation) * (PEER_CIRCLE_RADIUS - offset);  // no PEER_CIRCLE_ADDITIONAL_FREE_SPACE for y-disposition
		//
		return "position: absolute; top: " + fix3p1US.format(y) + "px; left: " + fix3p1US.format(x) + "px";
	}

	@Override
	public String path() {
		return "/stats/";
	}
	
	/** This class is the base for most of the pushed elements in this page. These are updated at intervals*/
	private static abstract class StatisticsUpdateableElement extends BaseUpdateableElement{

		/** The element needs a unique id*/
		private String updaterId=String.valueOf(new Random().nextInt());
		
		/** The current ToadletContext*/
		private ToadletContext ctx;
		
		private StatisticsUpdateableElement(ToadletContext ctx){
			super("div",ctx);
			this.ctx=ctx;
			init();
			((SimpleToadletServer)ctx.getContainer()).intervalPushManager.registerUpdateableElement(this);
		}
		
		@Override
		public void dispose() {
			((SimpleToadletServer)ctx.getContainer()).intervalPushManager.deregisterUpdateableElement(this);
		}

		@Override
		public String getUpdaterId(String requestId) {
			return updaterId;
		}

		@Override
		public String getUpdaterType() {
			return UpdaterConstants.REPLACER_UPDATER;
		}
		
	}
	
}
