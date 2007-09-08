package freenet.pluginmanager;

import freenet.support.Logger;
import freenet.support.OOMHandler;

/**
 * Methods to handle a specific plugin (= set it up and start it)
 * 
 * @author cyberdo
 */
public class PluginHandler {

	/**
	 * Will get all needed info from the plugin, put it into the Wrapper. Then
	 * the Pluginstarter will be greated, and the plugin fedto it, starting the
	 * plugin.
	 * 
	 * the pluginInfoWrapper will then be returned
	 * 
	 * @param plug
	 */
	public static PluginInfoWrapper startPlugin(PluginManager pm, String filename, FredPlugin plug, PluginRespirator pr) {
		final PluginInfoWrapper pi = new PluginInfoWrapper(plug, filename);
		final PluginStarter ps = new PluginStarter(pr, pi);
		pi.setThread(ps);
		
		// This is an ugly trick... sorry ;o)
		// The thread still exists as an identifier, but is never started if the
		// plugin doesn't require it
		ps.setPlugin(pm, plug);
		// Run after startup
		// FIXME this is horrible, wastes a thread, need to make PluginStarter a Runnable 
		// not a Thread, and then deal with the consequences of that (removePlugin(Thread)) ...
		pm.getTicker().queueTimedJob(new Runnable() {
			public void run() {
				if (!pi.isThreadlessPlugin())
					ps.start();
				else
					ps.run();
			}
		}, 0);
		return pi;
	}
	
	private static class PluginStarter extends Thread {
		private Object plugin = null;
		private PluginRespirator pr;
		private PluginManager pm = null;
		final PluginInfoWrapper pi;
		
		public PluginStarter(PluginRespirator pr, PluginInfoWrapper pi) {
			this.pr = pr;
			this.pi = pi;
			setDaemon(true);
		}
		
		public void setPlugin(PluginManager pm, Object plugin) {
			this.plugin = plugin;
			this.pm = pm;
		}
		
		public void run() {
			int seconds = 120; // give up after 2 min
			while (plugin == null) {
				// 1s polling
				try {
					Thread.sleep(1000);
				} catch (InterruptedException e) {
				}
				if (seconds-- <= 0)
					return;
			}
			
			if (plugin instanceof FredPlugin) {
				try {
					((FredPlugin)plugin).runPlugin(pr);
				} catch (OutOfMemoryError e) {
					OOMHandler.handleOOM(e);
				} catch (Throwable t) {
					Logger.normal(this, "Caught Throwable while running plugin: "+t, t);
					System.err.println("Caught Throwable while running plugin: "+t);
					t.printStackTrace();
				}
				pm.unregisterPlugin(pi); // If not already unregistered
				if(!(plugin instanceof FredPluginThreadless))
					pm.removePlugin(pi);
			} else {
				// If not FredPlugin, then the whole thing is aborted,
				// and then this method will return, killing the thread
				return;
			}
		}
		
	}
}
