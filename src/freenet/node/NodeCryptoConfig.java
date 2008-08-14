/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import java.net.UnknownHostException;

import freenet.config.InvalidConfigValueException;
import freenet.config.SubConfig;
import freenet.io.comm.FreenetInetAddress;
import freenet.support.Logger;
import freenet.support.api.BooleanCallback;
import freenet.support.api.IntCallback;
import freenet.support.api.StringCallback;

/**
 * Tracks config parameters related to a NodeCrypto. The NodeCrypto may or may not exist. If it exists,
 * parameter changes are passed on to it, if it doesn't, they can be changed trivially.
 * 
 * Allows users to set the opennet port number while opennet is disabled, enable opennet on the fly etc.
 * @author toad
 */
public class NodeCryptoConfig {

	/** Port number. -1 = choose a random available port number at activation time. */
	private int portNumber;
	
	/** Bind address. 0.0.0.0 = all addresses. */
	private FreenetInetAddress bindTo;
	
	/** If nonzero, 1/dropProbability = probability of UdpSocketHandler dropping a packet (for debugging
	 * purposes; not static as we may need to simulate some nodes with more loss than others). */
	private int dropProbability;
	
	/** The NodeCrypto, if there is one */
	private NodeCrypto crypto;
	
	/** Whether we should prevent multiple connections to the same IP (taking into account other
	 * NodeCrypto's - this will usually be set for opennet but not for darknet). */
	private boolean oneConnectionPerAddress;
	
	/** If true, we will allow to connect to nodes via local (LAN etc) IP addresses,
	 * regardless of any per-peer setting. */
	private boolean alwaysAllowLocalAddresses;
	
	/** If true, assume we are NATed regardless of the evidence, and therefore always send
	 * aggressive handshakes (every 10-30 seconds). */
	private boolean assumeNATed;
	
	NodeCryptoConfig(SubConfig config, int sortOrder, boolean onePerIP) throws NodeInitException {
		
		config.register("listenPort", -1 /* means random */, sortOrder++, true, true, "Node.port", "Node.portLong",	new IntCallback() {
			public int get() {
				synchronized(NodeCryptoConfig.class) {
					if(crypto != null)
						portNumber = crypto.portNumber;
					return portNumber;
				}
			}
			public void set(int val) throws InvalidConfigValueException {
				
				if(portNumber < -1 || portNumber == 0 || portNumber > 65535) {
					throw new InvalidConfigValueException("Invalid port number");
				}
				
				
				synchronized(NodeCryptoConfig.class) {
					if(portNumber == val) return;
					// FIXME implement on the fly listenPort changing
					// Note that this sort of thing should be the exception rather than the rule!!!!
					if(crypto != null)
						throw new InvalidConfigValueException("Switching listenPort on the fly not yet supported");
					portNumber = val;
				}
			}
		});
		
		try{
			portNumber = config.getInt("listenPort");
		}catch (Exception e){
			// FIXME is this really necessary?
			Logger.error(this, "Caught "+e, e);
			System.err.println(e);
			e.printStackTrace();
			portNumber = -1;
		}
		
		config.register("bindTo", "0.0.0.0", sortOrder++, true, true, "Node.bindTo", "Node.bindToLong", new NodeBindtoCallback());
		
		try {
			bindTo = new FreenetInetAddress(config.getString("bindTo"), false);
			
		} catch (UnknownHostException e) {
			throw new NodeInitException(NodeInitException.EXIT_COULD_NOT_BIND_USM, "Invalid bindTo: "+config.getString("bindTo"));
		}
		
		config.register("testingDropPacketsEvery", 0, sortOrder++, true, false, "Node.dropPacketEvery", "Node.dropPacketEveryLong",
				new IntCallback() {

					public int get() {
						synchronized(NodeCryptoConfig.this) {
							return dropProbability;
						}
					}

					public void set(int val) throws InvalidConfigValueException {
						if(val < 0) throw new InvalidConfigValueException("testingDropPacketsEvery must not be negative");
						synchronized(NodeCryptoConfig.this) {
							if(val == dropProbability) return;
							dropProbability = val;
							if(crypto == null) return;
						}
						crypto.onSetDropProbability(val);
					}
			
		});
		dropProbability = config.getInt("testingDropPacketsEvery"); 
		
		config.register("oneConnectionPerIP", onePerIP, sortOrder++, true, false, "Node.oneConnectionPerIP", "Node.oneConnectionPerIPLong",
				new BooleanCallback() {

					public boolean get() {
						synchronized(NodeCryptoConfig.this) {
							return oneConnectionPerAddress;
						}
					}

					public void set(boolean val) throws InvalidConfigValueException {
						synchronized(NodeCryptoConfig.this) {
							oneConnectionPerAddress = val;
						}
					}
			
		});
		oneConnectionPerAddress = config.getBoolean("oneConnectionPerIP");
		
		config.register("alwaysAllowLocalAddresses", false, sortOrder++, true, false, "Node.alwaysAllowLocalAddresses", "Node.alwaysAllowLocalAddressesLong",
				new BooleanCallback() {

					public boolean get() {
						synchronized(NodeCryptoConfig.this) {
							return alwaysAllowLocalAddresses;
						}
					}

					public void set(boolean val) throws InvalidConfigValueException {
						synchronized(NodeCryptoConfig.this) {
							alwaysAllowLocalAddresses = val;
						}
					}
		});
		alwaysAllowLocalAddresses = config.getBoolean("alwaysAllowLocalAddresses");
		
		config.register("assumeNATed", true, sortOrder++, true, true, "Node.assumeNATed", "Node.assumeNATedLong", new BooleanCallback() {

			public boolean get() {
				return assumeNATed;
			}

			public void set(boolean val) throws InvalidConfigValueException {
				assumeNATed = val;
			}
		});
		assumeNATed = config.getBoolean("assumeNATed");
	}

	/** The number of config options i.e. the amount to increment sortOrder by */
	public static final int OPTION_COUNT = 3;
	
	synchronized void starting(NodeCrypto crypto2) {
		if(crypto != null) throw new IllegalStateException("Replacing existing NodeCrypto "+crypto+" with "+crypto2);
		crypto = crypto2;
	}
	
	synchronized void started(NodeCrypto crypto2) {
		if(crypto != null) throw new IllegalStateException("Replacing existing NodeCrypto "+crypto+" with "+crypto2);
	}
	
	synchronized void maybeStarted(NodeCrypto crypto2) {

	}
	
	synchronized void stopping(NodeCrypto crypto2) {
		crypto = null;
	}
	
	public synchronized int getPort() {
		return portNumber;
	}
	
	class NodeBindtoCallback implements StringCallback {
		
		public String get() {
			return bindTo.toString();
		}
		
		public void set(String val) throws InvalidConfigValueException {
			if(val.equals(get())) return;
			// FIXME why not? Can't we use freenet.io.NetworkInterface like everywhere else, just adapt it for UDP?
			throw new InvalidConfigValueException("Cannot be updated on the fly");
		}
	}

	public synchronized FreenetInetAddress getBindTo() {
		return bindTo;
	}

	public synchronized void setPort(int port) {
		portNumber = port;
	}

	public synchronized int getDropProbability() {
		return dropProbability;
	}

	public synchronized boolean oneConnectionPerAddress() {
		return oneConnectionPerAddress;
	}

	public synchronized boolean alwaysAllowLocalAddresses() {
		return alwaysAllowLocalAddresses;
	}

	public boolean alwaysHandshakeAggressively() {
		return assumeNATed;
	}
}
