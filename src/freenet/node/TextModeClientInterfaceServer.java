/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.Hashtable;

import freenet.client.HighLevelSimpleClient;
import freenet.config.Config;
import freenet.config.InvalidConfigValueException;
import freenet.config.SubConfig;
import freenet.crypt.RandomSource;
import freenet.crypt.SSL;
import freenet.io.NetworkInterface;
import freenet.io.SSLNetworkInterface;
import freenet.support.Logger;
import freenet.support.api.BooleanCallback;
import freenet.support.api.IntCallback;
import freenet.support.api.StringCallback;

public class TextModeClientInterfaceServer implements Runnable {

    final RandomSource r;
    final Node n;
    final NodeClientCore core;
//    final HighLevelSimpleClient client;
    final Hashtable streams;
    final File downloadsDir;
    int port;
    String bindTo;
    String allowedHosts;
    boolean isEnabled;
    private static boolean ssl = false;
    final NetworkInterface networkInterface;

    TextModeClientInterfaceServer(Node node, NodeClientCore core, int port, String bindTo, String allowedHosts) throws IOException {
    	this.n = node;
    	this.core = n.clientCore;
        this.r = n.random;
        streams = new Hashtable();
        this.downloadsDir = core.downloadDir;
        this.port=port;
        this.bindTo=bindTo;
        this.allowedHosts = allowedHosts;
        this.isEnabled=true;
        if(ssl) {
        	networkInterface = SSLNetworkInterface.create(port, bindTo, allowedHosts, n.executor, true);
        } else {
        	networkInterface = NetworkInterface.create(port, bindTo, allowedHosts, n.executor, true);
        }
    }
    
    void start() {
		Logger.normal(core, "TMCI started on "+networkInterface.getAllowedHosts()+ ':' +port);
		System.out.println("TMCI started on "+networkInterface.getAllowedHosts()+ ':' +port);
		
		n.executor.execute(this, "Text mode client interface");
    }
    
	public static TextModeClientInterfaceServer maybeCreate(Node node, NodeClientCore core, Config config) throws IOException {
		
		TextModeClientInterfaceServer server = null;
		
		SubConfig TMCIConfig = new SubConfig("console", config);
		
		TMCIConfig.register("enabled", false, 1, true, true /* FIXME only because can't be changed on the fly */, "TextModeClientInterfaceServer.enabled", "TextModeClientInterfaceServer.enabledLong", new TMCIEnabledCallback(core));
		TMCIConfig.register("ssl", false, 1, true, true , "TextModeClientInterfaceServer.ssl", "TextModeClientInterfaceServer.sslLong", new TMCISSLCallback());
		TMCIConfig.register("bindTo", NetworkInterface.DEFAULT_BIND_TO, 2, true, false, "TextModeClientInterfaceServer.bindTo", "TextModeClientInterfaceServer.bindToLong", new TMCIBindtoCallback(core));
		TMCIConfig.register("allowedHosts", NetworkInterface.DEFAULT_BIND_TO, 2, true, false, "TextModeClientInterfaceServer.allowedHosts", "TextModeClientInterfaceServer.allowedHostsLong", new TMCIAllowedHostsCallback(core));
		TMCIConfig.register("port", 2323, 1, true, false, "TextModeClientInterfaceServer.telnetPortNumber", "TextModeClientInterfaceServer.telnetPortNumberLong", new TCMIPortNumberCallback(core));
		TMCIConfig.register("directEnabled", false, 1, true, false, "TextModeClientInterfaceServer.enableInputOutput", "TextModeClientInterfaceServer.enableInputOutputLong", new TMCIDirectEnabledCallback(core));
		
		boolean TMCIEnabled = TMCIConfig.getBoolean("enabled");
		int port =  TMCIConfig.getInt("port");
		String bind_ip = TMCIConfig.getString("bindTo");
		String allowedHosts = TMCIConfig.getString("allowedHosts");
		boolean direct = TMCIConfig.getBoolean("directEnabled");
		if(SSL.available()) {
			ssl = TMCIConfig.getBoolean("ssl");
		}

		if(TMCIEnabled)
			server = new TextModeClientInterfaceServer(node, core, port, bind_ip, allowedHosts);
		
		if(direct) {
	        HighLevelSimpleClient client = core.makeClient(RequestStarter.INTERACTIVE_PRIORITY_CLASS, true);
			TextModeClientInterface directTMCI =
				new TextModeClientInterface(node, client, core.downloadDir, System.in, System.out);
			Thread t = new Thread(directTMCI, "Direct text mode interface");
			t.setDaemon(true);
			t.start();
			core.setDirectTMCI(directTMCI);
		}
		
		TMCIConfig.finishedInitialization();
		
		return server; // caller must call start()
	}

    
    static class TMCIEnabledCallback implements BooleanCallback {
    	
    	final NodeClientCore core;
    	
    	TMCIEnabledCallback(NodeClientCore core) {
    		this.core = core;
    	}
    	
    	public boolean get() {
    		return core.getTextModeClientInterface() != null;
    	}
    	
    	public void set(boolean val) throws InvalidConfigValueException {
    		if(val == get()) return;
    		// FIXME implement - see bug #122
    		throw new InvalidConfigValueException("Cannot be updated on the fly");
    	}
    }

    static class TMCISSLCallback implements BooleanCallback {
    	
    	public boolean get() {
    		return ssl;
    	}
    	
    	public void set(boolean val) throws InvalidConfigValueException {
    		if(val == get()) return;
			if(!SSL.available()) {
				throw new InvalidConfigValueException("Enable SSL support before use ssl with TMCI");
			}
    		ssl = val;
    		throw new InvalidConfigValueException("Cannot change SSL on the fly, please restart freenet");
    	}
    }

    static class TMCIDirectEnabledCallback implements BooleanCallback {
    	
    	final NodeClientCore core;
    	
    	TMCIDirectEnabledCallback(NodeClientCore core) {
    		this.core = core;
    	}
    	
    	public boolean get() {
    		return core.getDirectTMCI() != null;
    	}
    	
    	public void set(boolean val) throws InvalidConfigValueException {
    		if(val == get()) return;
    		// FIXME implement - see bug #122
    		throw new InvalidConfigValueException("Cannot be updated on the fly");
    	}
    }
    
    static class TMCIBindtoCallback implements StringCallback {
    	
    	final NodeClientCore core;
    	
    	TMCIBindtoCallback(NodeClientCore core) {
    		this.core = core;
    	}
    	
    	public String get() {
    		if(core.getTextModeClientInterface() != null)
    			return core.getTextModeClientInterface().bindTo;
    		else
    			return NetworkInterface.DEFAULT_BIND_TO;
    	}
    	
    	public void set(String val) throws InvalidConfigValueException {
    		if(val.equals(get())) return;
		try {
			core.getTextModeClientInterface().networkInterface.setBindTo(val, false);
			core.getTextModeClientInterface().bindTo = val;
		} catch (IOException e) {
			throw new InvalidConfigValueException("could not change bind to!");
		}
    	}
    }
    
    static class TMCIAllowedHostsCallback implements StringCallback {

    	private final NodeClientCore core;
    	
    	public TMCIAllowedHostsCallback(NodeClientCore core) {
    		this.core = core;
    	}
    	
		public String get() {
			if (core.getTextModeClientInterface() != null) {
				return core.getTextModeClientInterface().allowedHosts;
			}
			return NetworkInterface.DEFAULT_BIND_TO;
		}

		public void set(String val) {
			if (!val.equals(get())) {
				core.getTextModeClientInterface().networkInterface.setAllowedHosts(val);
				core.getTextModeClientInterface().allowedHosts = val;
			}
		}
    	
    }

    static class TCMIPortNumberCallback implements IntCallback{
    	
    	final NodeClientCore core;
    	
    	TCMIPortNumberCallback(NodeClientCore core) {
    		this.core = core;
    	}
    	
    	public int get() {
    		if(core.getTextModeClientInterface()!=null)
    			return core.getTextModeClientInterface().port;
    		else
    			return 2323;
    	}
    	
    	// TODO: implement it
    	public void set(int val) throws InvalidConfigValueException {
    		if(val == get()) return;
    		core.getTextModeClientInterface().setPort(val);
    	}
    }

    /**
     * Read commands, run them
     */
    public void run() {
	    freenet.support.Logger.OSThread.logPID(this);
    	while(true) {
    		int curPort = port;
    		String tempBindTo = this.bindTo;
    		try {
    			networkInterface.setSoTimeout(1000);
    		} catch (SocketException e1) {
    			Logger.error(this, "Could not set timeout: "+e1, e1);
    			System.err.println("Could not start TMCI: "+e1);
    			e1.printStackTrace();
    			return;
    		}
    		while(isEnabled) {
    			// Maybe something has changed?
				if(port != curPort) break;
				if(!(this.bindTo.equals(tempBindTo))) break;
    			try {
    				Socket s = networkInterface.accept();
    				InputStream in = s.getInputStream();
    				OutputStream out = s.getOutputStream();
    				
    				TextModeClientInterface tmci = 
					new TextModeClientInterface(this, in, out);
    				
    				n.executor.execute(tmci, "Text mode client interface handler for "+s.getPort());
    				
    			} catch (SocketTimeoutException e) {
    				// Ignore and try again
    			} catch (SocketException e){
    				Logger.error(this, "Socket error : "+e, e);
    			} catch (IOException e) {
    				Logger.error(this, "TMCI failed to accept socket: "+e, e);
    			}
    		}
    		try{
    			networkInterface.close();
    		}catch (IOException e){
    			Logger.error(this, "Error shuting down TMCI", e);
    		}
    	}
    }

	public void setPort(int val) {
		port = val;
	}
	
}
