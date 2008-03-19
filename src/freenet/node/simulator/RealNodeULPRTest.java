/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.simulator;

import java.io.File;
import java.io.IOException;

import freenet.crypt.DummyRandomSource;
import freenet.io.comm.PeerParseException;
import freenet.io.comm.ReferenceSignatureVerificationException;
import freenet.keys.CHKEncodeException;
import freenet.keys.ClientCHKBlock;
import freenet.keys.ClientKSK;
import freenet.keys.ClientKey;
import freenet.keys.ClientKeyBlock;
import freenet.keys.FreenetURI;
import freenet.keys.InsertableClientSSK;
import freenet.keys.SSKEncodeException;
import freenet.keys.SSKVerifyException;
import freenet.node.FSParseException;
import freenet.node.LowLevelGetException;
import freenet.node.Node;
import freenet.node.NodeInitException;
import freenet.node.NodeStarter;
import freenet.store.KeyCollisionException;
import freenet.support.Executor;
import freenet.support.HexUtil;
import freenet.support.Logger;
import freenet.support.PooledExecutor;
import freenet.support.SimpleFieldSet;
import freenet.support.LoggerHook.InvalidThresholdException;
import freenet.support.io.ArrayBucket;
import freenet.support.io.FileUtil;

/**
 * Create a key block with random key and contents.
 * Create a bunch of nodes. Connect them.
 * Request the key from each node.
 * Then insert it to one of them.
 * Expected results: fast propagation via ULPRs of the data to every node.
 * 
 * Note that this relies as much on per-node failure tables causing the request
 * to go to every node as it does on ULPRs to propagate the data to every node
 * that it's been requested by.
 * 
 * This should be transformed into a Heavy Unit Test.
 * @author toad
 */
public class RealNodeULPRTest extends RealNodeTest {
	
	// Exit codes
	static final int EXIT_BASE = NodeInitException.EXIT_NODE_UPPER_LIMIT;
	static final int EXIT_KEY_EXISTS = EXIT_BASE + 1;
	static final int EXIT_UNKNOWN_ERROR_CHECKING_KEY_NOT_EXIST = EXIT_BASE + 2;
	static final int EXIT_TEST_FAILED = EXIT_BASE + 4;
	
    static final int NUMBER_OF_NODES = 10;
    // We don't explicitly subscribe, so each node must be routed through.
    // However, per-node failure tables should ensure the node doesn't make the same mistake twice so visits every node.
    static final short MAX_HTL = 10;
    //static final int NUMBER_OF_NODES = 50;
    //static final short MAX_HTL = 10;
    static final int NUMBER_OF_TESTS = 100;
    static final boolean ENABLE_SWAPPING = true;
    static final boolean ENABLE_ULPRS = true; // This is the point of the test, but it's probably a good idea to be able to do a comparison if we want to
    static final boolean ENABLE_PER_NODE_FAILURE_TABLES = true;
    
    public static void main(String[] args) throws FSParseException, PeerParseException, CHKEncodeException, InvalidThresholdException, NodeInitException, ReferenceSignatureVerificationException, KeyCollisionException, SSKEncodeException, IOException, InterruptedException, SSKVerifyException {
    	freenet.node.RequestHandler.SEND_OLD_FORMAT_SSK = false;
        System.err.println("ULPR test");
        System.err.println();
    	String testName = "realNodeULPRTest";
        File wd = new File(testName);
        if(!FileUtil.removeAll(wd)) {
        	System.err.println("Mass delete failed, test may not be accurate.");
        	System.exit(EXIT_CANNOT_DELETE_OLD_DATA);
        }
        wd.mkdir();
        
        DummyRandomSource random = new DummyRandomSource();
        
        //NOTE: globalTestInit returns in ignored random source
        //NodeStarter.globalTestInit(testName, false, Logger.ERROR, "freenet.node.Location:normal,freenet.node.simulator.RealNodeRoutingTest:normal,freenet.node.NodeDispatcher:NORMAL" /*,freenet.node.FailureTable:MINOR,freenet.node.Node:MINOR,freenet.node.Request:MINOR,freenet.io.comm.MessageCore:MINOR" "freenet.store:minor,freenet.node.LocationManager:debug,freenet.node.FNPPacketManager:normal,freenet.io.comm.MessageCore:debug"*/);
        NodeStarter.globalTestInit(testName, false, Logger.ERROR, "freenet.node.Location:normal,freenet.node.simulator.RealNodeRoutingTest:normal,freenet.node.NodeDispatcher:NORMAL,freenet.node.FailureTable:MINOR,freenet.node.Node:MINOR,freenet.node.Request:MINOR,freenet.io.comm.MessageCore:MINOR,freenet.node.PeerNode:MINOR,freenet.io.xfer.PacketThrottle:MINOR,freenet.node.PeerManager:MINOR");
        Node[] nodes = new Node[NUMBER_OF_NODES];
        Logger.normal(RealNodeRoutingTest.class, "Creating nodes...");
        Executor executor = new PooledExecutor();
        for(int i=0;i<NUMBER_OF_NODES;i++) {
            nodes[i] = 
            	NodeStarter.createTestNode(5000+i, testName, false, true, true, MAX_HTL, 20 /* 5% */, random, executor, 500*NUMBER_OF_NODES, 1024*1024, true, ENABLE_SWAPPING, false, ENABLE_ULPRS, ENABLE_PER_NODE_FAILURE_TABLES, true, true, 0);
            Logger.normal(RealNodeRoutingTest.class, "Created node "+i);
        }
        SimpleFieldSet refs[] = new SimpleFieldSet[NUMBER_OF_NODES];
        for(int i=0;i<NUMBER_OF_NODES;i++)
            refs[i] = nodes[i].exportDarknetPublicFieldSet();
        Logger.normal(RealNodeRoutingTest.class, "Created "+NUMBER_OF_NODES+" nodes");
        // Now link them up
        // Connect the set
        for(int i=0;i<NUMBER_OF_NODES;i++) {
            int next = (i+1) % NUMBER_OF_NODES;
            int prev = (i+NUMBER_OF_NODES-1)%NUMBER_OF_NODES;
            nodes[i].connect(nodes[next]);
            nodes[i].connect(nodes[prev]);
        }
        Logger.normal(RealNodeRoutingTest.class, "Connected nodes");
        // Now add some random links
        for(int i=0;i<NUMBER_OF_NODES*5;i++) {
            if(i % NUMBER_OF_NODES == 0)
                Logger.normal(RealNodeRoutingTest.class, ""+i);
            int length = (int)Math.pow(NUMBER_OF_NODES, random.nextDouble());
            int nodeA = random.nextInt(NUMBER_OF_NODES);
            int nodeB = (nodeA+length)%NUMBER_OF_NODES;
            //System.out.println(""+nodeA+" -> "+nodeB);
            Node a = nodes[nodeA];
            Node b = nodes[nodeB];
            a.connect(b);
            b.connect(a);
        }
        
        Logger.normal(RealNodeRoutingTest.class, "Added random links");
        
        for(int i=0;i<NUMBER_OF_NODES;i++)
            nodes[i].start(false);
        
        int successfulTests = 0;
        
        long totalPropagationTime = 0;
        
        for(int totalCount=0;totalCount<NUMBER_OF_TESTS;totalCount++) {
        
        boolean isSSK = (totalCount & 0x1) == 1;
        
        // Now create a key.
        
        byte[] buf = new byte[32];
        random.nextBytes(buf);
        String keyName = HexUtil.bytesToHex(buf);
        
        FreenetURI testKey;
        ClientKey insertKey;
        ClientKey fetchKey;
        ClientKeyBlock block;
        
        if(isSSK) {
        	testKey = new FreenetURI("KSK", keyName);
        	
        	insertKey = InsertableClientSSK.create(testKey);
        	fetchKey = ClientKSK.create(testKey);
        	
        	block = ((InsertableClientSSK)insertKey).encode(new ArrayBucket(buf), false, false, (short)-1, buf.length, random);
        } else {
        	block = ClientCHKBlock.encode(buf, false, false, (short)-1, buf.length);
        	insertKey = fetchKey = block.getClientKey();
        	testKey = insertKey.getURI();
        }
        
        System.err.println();
        System.err.println("Created random test key "+testKey+" = "+fetchKey.getNodeKey());
        System.err.println();
        Logger.error(RealNodeULPRTest.class, "Starting ULPR test #"+successfulTests+": "+testKey+" = "+fetchKey+" = "+fetchKey.getNodeKey());
        
        waitForAllConnected(nodes);
        
        // Fetch the key from each node.
        
        for(int i=0;i<nodes.length;i++) {
        	System.out.println("Searching from node "+i);
        	try {
        		nodes[i].clientCore.realGetKey(fetchKey, false, true, false);
        		System.err.println("TEST FAILED: KEY ALREADY PRESENT!!!"); // impossible!
        		System.exit(EXIT_KEY_EXISTS);
        	} catch (LowLevelGetException e) {
        		switch(e.code) {
        		case LowLevelGetException.DATA_NOT_FOUND:
        		case LowLevelGetException.ROUTE_NOT_FOUND:
        			// Expected
        			System.err.println("Node "+i+" : key not found (expected behaviour)");
        			continue;
        		default:
        			System.err.println("Node "+i+" : UNEXPECTED ERROR: "+e.toString());
        			System.exit(EXIT_UNKNOWN_ERROR_CHECKING_KEY_NOT_EXIST);
        		}
        	}
        }
        
        // Now we should have a good web of subscriptions set up.
        
        // Store the key to ONE node.
        
		long tStart = System.currentTimeMillis();
		nodes[nodes.length-1].store(block, false);
		
		int x = -1;
		while(true) {
			x++;
			Thread.sleep(1000);
			int count = 0;
			for(int i=0;i<nodes.length;i++) {
				if(nodes[i].fetch(fetchKey.getNodeKey(), true) != null)
					count++;
			}
			System.err.println("T="+x+" : "+count+'/'+nodes.length+" have the data on test "+successfulTests+".");
			Logger.normal(RealNodeULPRTest.class, "T="+x+" : "+count+'/'+nodes.length+" have the data on test "+successfulTests+".");
			if(x > 300) {
				System.err.println();
				System.err.println("TEST FAILED");
				System.exit(EXIT_TEST_FAILED);
			}
			if(count == nodes.length) {
				successfulTests++;
				long tEnd = System.currentTimeMillis();
				long propagationTime = tEnd-tStart;
				System.err.println("SUCCESSFUL TEST # "+successfulTests+" in "+propagationTime+"ms!!!");
				totalPropagationTime += propagationTime;
		        System.err.println("Average propagation time: "+(totalPropagationTime / successfulTests)+"ms");
				System.err.println();
				break;
			}
			if(x % nodes.length == 0) {
				System.err.print("Nodes that don't have the data: ");
				for(int i=0;i<nodes.length;i++)
					if(nodes[i].fetch(fetchKey.getNodeKey(), true) == null) {
						System.err.print(i+" ");
					}
				System.err.println();
			}
		}
		
        }
        System.err.println("Overall average propagation time: "+(totalPropagationTime / successfulTests)+"ms");
        System.exit(0);
    }
    

}
