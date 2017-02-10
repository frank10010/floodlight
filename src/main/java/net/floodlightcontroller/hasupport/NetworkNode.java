/**
 *    Licensed under the Apache License, Version 2.0 (the "License"); you may
 *    not use this file except in compliance with the License. You may obtain
 *    a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *    WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *    License for the specific language governing permissions and limitations
 *    under the License.
 **/

package net.floodlightcontroller.hasupport;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.python.modules.math;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * The Network Node (Connection Manager)
 * Implements the NetworkInterface, which dictates the 
 * topology of how the controllers are communicating with 
 * each other in order to perform role based functions, after
 * electing a leader. Currently, the topology is set to a mesh
 * topology, however this can be changed completely if needed, as long
 * as the functions from NetworkInterface are implemented.
 * One has to ensure that the socketDict and the connectDict HashMaps 
 * are populated and updated similar to the way the updateConnectDict()
 * maintains these objects. This method is called as soon as a state
 * change to even one of the sockets is detected.
 * 
 * Possible improvements:
 * a. Implement a better topology, i.e. the topology is now a complete mesh,
 * this class can be completely re-implemented, if you adhere to NetworkInterface,
 * and expose socketDict and connectionDict to AsyncElection in a similar manner.
 * 
 * b. Improve the existing connection manager (NetworkNode). Currently, we are using the 
 * extended request-reply pattern, mentioned in the ZGuide. We could identify
 * a good alternative and implement it.
 * 
 * @author Bhargav Srinivasan, Om Kale
 */

public class NetworkNode implements NetworkInterface, Runnable {
	
	private static Logger logger = LoggerFactory.getLogger(NetworkNode.class);
	
	private NioClient clientSock;
	
	public final String controllerID;
	public final String serverPort;
	public final String clientPort;
	
	/**
	 * The server list holds the server port IDs of all present 
	 * connections.
	 * The connectSet is a set of nodes defined in the server list 
	 * which are to be connected
	 */

	public LinkedList<String> serverList = new LinkedList<String>();
	public LinkedList<String> allServerList = new LinkedList<String>();
	public HashSet<String>    connectSet = new HashSet<String>();
	
	private final String pulse  = new String("PULSE");
	private final String ack    = new String("ACK");
	
	/**
	 * Holds the connection state/ socket object for each of the client
	 * connections.
	 */
	
	public  HashMap<String, NioClient>  socketDict                   = new HashMap<String, NioClient>();
	public  HashMap<String, netState>   connectDict                  = new HashMap<String, netState>();
	public  HashMap<String, String>     controllerIDNetStatic        = new HashMap<String, String>();
	public  HashMap<String, Integer>    netcontrollerIDStatic        = new HashMap<String, Integer>();
	public  HashMap<String, NioClient>  allsocketDict                = new HashMap<String, NioClient>();
	private HashMap<String, NioClient>  delmark                      = new HashMap<String, NioClient>();
	
	/**
	 * Standardized sleep times for socket timeouts,
	 * number of pulses to send before expiring.
	 */
	
	/** 
	 * Decide the socket timeout value based on how fast you think the leader should
	 * respond and also how far apart the actual nodes are placed. If you are trying
	 * to communicate with servers far away, then anything up to 10s would be a good value.
	 */
	
	public final Integer socketTimeout 		      = new Integer(500);
	public final Integer linger 		          = new Integer(0);
	public final Integer numberOfPulses		      = new Integer(1);
	public final Integer pollTime				  = new Integer(1);
	public Integer ticks						  = new Integer(0);
	public final Integer maxSockets				  = new Integer(5000);
	public final Integer chill				      = new Integer(5);
	
	/**
	 * Majority is a variable that holds what % of servers need to be
	 * active in order for the election to start happening. Total rounds is
	 * the number of expected failures which is set to len(serverList) beacuse
	 * we assume that any/every node can fail.
	 */
	
	public final Integer majority;
	public final Integer totalRounds;
	private String response = new String();
	
	/**
	 * Constructor needs both the backend and frontend ports and the serverList
	 * file which specifies a port number for each connected client. 
	 * @param serverPort
	 * @param clientPort
	 * @param controllerID
	 */

	public NetworkNode(String serverPort, String clientPort, String controllerID){
		/**
		 * The port variables needed in order to start the
		 * back-end and front-end of the queue device.
		 */
		this.serverPort = serverPort.toString();
		this.clientPort = clientPort.toString();
		this.controllerID = controllerID;
		preStart();
		this.totalRounds = new Integer(this.connectSet.size());
		// logger.debug("Total Rounds: "+this.totalRounds.toString());
		if(this.totalRounds >= 2){
			this.majority = new Integer((int) math.ceil(new Double(0.51 * this.connectSet.size())));
		} else {
			this.majority = new Integer(1);
		}
		// logger.debug("Other Servers: "+this.connectSet.toString()+"Majority: "+this.majority);
		
	}
	
	/**
	 * Parses server.config located in the resources folder in order
	 * to obtain the IP:ports of all the nodes that are configured to
	 * be a part of this network.
	 * 
	 */
	
	public void preStart(){
		String filename = "src/main/resources/server.config";
		
		try{
			FileReader configFile = new FileReader(filename);
			String line = null;
			BufferedReader br = new BufferedReader(configFile);
			
			Integer cidIter = new Integer(1);
			while((line = br.readLine()) != null){
				this.serverList.add(new String(line.trim()));
				this.allServerList.add(new String(line.trim()));
				this.netcontrollerIDStatic.put(new String(line.trim()), cidIter);
				this.controllerIDNetStatic.put(cidIter.toString(), new String(line.trim()) );
				cidIter += 1;
			}
			
			this.serverList.remove(this.clientPort);
			this.connectSet = new HashSet<String>(this.serverList);
			
			for (String client: this.connectSet) {
				this.allsocketDict.put(client, new NioClient(socketTimeout,linger));
			}
			
			br.close();
			configFile.close();
			
		} catch (FileNotFoundException e){
			// logger.debug("[NetworkNode] This file was not found! Please place the server config file in the right location.");	
		} catch(Exception e){
			e.printStackTrace();
		}
	}
	
    /**
     * Sends a message to a specified client IP:port, if possible.
     * 
     * @return boolean value that indicates success or failure.
     */
	
	@Override
	public Boolean send(String clientPort, String message) {
		if( message.equals(null) ) {
			return Boolean.FALSE;
		}
		
		clientSock = socketDict.get(clientPort);
		try{
			//logger.info("[NetworkNode] Sending: "+message+" sent through port: "+clientPort.toString());
			clientSock.send(message);
			return Boolean.TRUE;
			
		} catch(Exception e){
			if(clientSock.getSocketChannel() != null){
				clientSock.deleteConnection();
			}
			//logger.info("[NetworkNode] Send Failed: "+message+" not sent through port: "+clientPort.toString());
			return Boolean.FALSE;
		}
	}

	/**
	 * Receives a message from the specified IP:port, if possible.
	 * 
	 * @return String containing the received message.
	 */
	
	@Override
	public String recv(String receivingPort) {
		clientSock = socketDict.get(receivingPort);
		try{		
			response = clientSock.recv();
			response.trim();
			//logger.info("[NetworkNode] Recv on port: "+receivingPort.toString()+response);
			return response;
		} catch (Exception e){
			if(clientSock.getSocketChannel() != null){
				clientSock.deleteConnection();
			}
			//logger.info("[NetworkNode] Recv Failed on port: "+receivingPort.toString());
			return "";
		}
		
	}
	
	/**
	 * This method maintains the hashmap socketDict, which holds the socket
	 * objects for the current active connections. It tries to connect the 
	 * nodes which have been configured but not connected yet, and adds them to
	 * the socketDict if the connection is successful. This method has been 
	 * optimized to instantiate as few socket objects as possible without loss
	 * of functionality.
	 * 
	 */
	
	public void doConnect(){
		
		HashSet<String> diffSet 		= new HashSet<String>();
		HashSet<String> connectedNodes  = new HashSet<String>();
		
		for(HashMap.Entry<String, NioClient> entry: this.socketDict.entrySet()){
			connectedNodes.add(entry.getKey());
		}
		
		diffSet.addAll(this.connectSet);
		diffSet.removeAll(connectedNodes);
		
		// logger.info("[Node] New connections to look for (ConnectSet - Connected): "+diffSet.toString());
		
		
		// Try connecting to all nodes that are in the diffSet and store the 
		// successful ones in the  socketDict.
		// byte[] rep;
		String reply;
		for (String client: diffSet){
			reply="";
			clientSock = allsocketDict.get(client);
			try{
				// logger.info("[Node] Trying to connect to Client: "+client.toString()+"Client Sock: "+clientSock.toString());
				clientSock.connectClient(client);
				clientSock.send(pulse);
				// rep = clientSock.recv(0);
				// reply = new String(rep,0,rep.length);
				reply = clientSock.recv();
				
				if( reply.equals(ack) ){
					// logger.info("[Node] Client: "+client.toString()+"Client Sock: "+clientSock.toString());
					if (!socketDict.containsKey(client)){
						socketDict.put(client, clientSock);
					} else {
						// logger.info("[Node] This socket already exists, refreshing: "+client.toString());
						clientSock.deleteConnection();
						this.socketDict.remove(client);
						this.socketDict.put(client, new NioClient(socketTimeout,linger));
					}
				} else {
					// logger.info("[Node] Received bad reply: "+client.toString()+" "+reply);
					clientSock.deleteConnection();
					// logger.info("[Node] Closed Socket"+client.toString());		
				}
				
			} catch(NullPointerException ne){
				// logger.info("[Node] ConnectClients: Reply had a null value from: "+client.toString());
				//ne.printStackTrace();
			} catch (Exception e){
				if(clientSock != null){
					clientSock.deleteConnection();
					allsocketDict.put(client, new NioClient(socketTimeout,linger));
				}
				// logger.info("[Node] ConnectClients errored out: "+client.toString());
				//e.printStackTrace();
			} 
			
		}
		
		return;
		
	}
	
	/**
	 * Called by the blockUntilConnected() method in order to perform an
	 * initial connection to all the nodes and store all their socket objects in
	 * the socketDict and connectDict which are then passed to the election class.
	 * 
	 * @return Unmodifiable hashmap of connectDict <IP:port, ON/OFF>
	 */

	@Override
	public Map<String, netState> connectClients() {
		// logger.info("[Node] To Connect: "+this.connectSet.toString());
		// logger.info("[Node] Connected: "+this.socketDict.keySet().toString());
		
		doConnect();
		
		//Delete the already connected connections from the ToConnect Set.
		for(HashMap.Entry<String, NioClient> entry: this.socketDict.entrySet()){
			if(this.connectSet.contains(entry.getKey())){
				this.connectSet.remove(entry.getKey());
				// logger.info("Discarding already connected client: "+entry.getKey().toString());
			}
		}	
		updateConnectDict();
		return (Map<String, netState>) Collections.unmodifiableMap(this.connectDict);
	}
	
	/**
	 * This method is periodically called by the election class so
	 * that we can identify if any more of the configured nodes have 
	 * become active, and if so establish connections to them and store
	 * the corresponding socket objects.
	 * 
	 * @return Unmodifiable hashmap of connectDict <IP:port, ON/OFF>
	 */

	@Override
	public Map<String, netState> checkForNewConnections() {
		
		expireOldConnections();
		
		this.connectSet = new HashSet<String> (this.serverList);
		
		doConnect();
		
		updateConnectDict();
		return (Map<String, netState>) Collections.unmodifiableMap(this.connectDict);
	}
	
	/**
	 * This method is used by the election class as a failure detector,
	 * meaning it can detect if the connected nodes are still responding, 
	 * and are active. If not, it closes the corresponding socket and removes it
	 * from the socketDict & connectDict, in order to inform the election class that 
	 * the following nodes are no longer active.
	 * 
	 * @return Unmodifiable hashmap of connectDict <IP:port, ON/OFF>
	 */

	@Override
	public Map<String, netState> expireOldConnections() {
		// logger.info("Expiring old connections...");
		delmark = new HashMap <String, NioClient>();
		// byte[] rep = null;
		String reply;
		for(HashMap.Entry<String, NioClient> entry: this.socketDict.entrySet()){
			clientSock = entry.getValue();
			reply = "";
			try{
				for(int i=0; i < this.numberOfPulses; i++){
					clientSock.send(pulse);
					reply = clientSock.recv();
				}
				// reply = new String(rep,0,rep.length);
				
				if (! reply.equals(ack) ) {
					//logger.info("[Node] Closing stale connection: "+entry.getKey().toString());
					entry.getValue().deleteConnection();
					delmark.put(entry.getKey(),entry.getValue());
				}
				
			} catch(NullPointerException ne){
				//logger.debug("[Node] Expire: Reply had a null value: "+entry.getKey().toString());
				delmark.put(entry.getKey(),entry.getValue());
				//ne.printStackTrace();
			} catch (Exception e){
				//logger.debug("[Node] Expire: Exception! : "+entry.getKey().toString());
				delmark.put(entry.getKey(),entry.getValue());
				//e.printStackTrace();
			}
		}
		
		//Pop out all the expired connections from socketDict.
		try{
			for (HashMap.Entry<String, NioClient> entry: delmark.entrySet()){
				this.socketDict.remove(entry.getKey());
				if(entry.getValue() != null) {
					entry.getValue().deleteConnection();
				}
			}
		} catch (Exception e) {
			//logger.debug("[NetworkNode] Error in expireOldConnections, while deleting socket");
			e.printStackTrace();
		}
		
		//logger.info("Expired old connections.");
		
		updateConnectDict();
		return (Map<String, netState>) Collections.unmodifiableMap(this.connectDict);
	}
	
	
	/**
	 * This method is used to completely refresh the state of the connection manager,
	 * closing all sockets, both active and inactive, and spawning new socket objects
	 * for all configured nodes. This function is called by the blockUntilConnected() 
	 * method in order to refresh state every five minutes in order to avoid an excess
	 * of open files / sockets.
	 * 
	 */
	
	
	public void cleanState() {
		
		this.connectSet = new HashSet<String> (this.serverList);
		delmark = new HashMap<String, NioClient>();
		
		for (String client: this.connectSet) {
			this.allsocketDict.get(client).deleteConnection();
			this.allsocketDict.put(client, new NioClient(socketTimeout,linger));
		}
		
		for (HashMap.Entry<String, NioClient> entry: this.socketDict.entrySet()){
			try{
				//logger.info("[Node] Closing connection: "+entry.getKey().toString());
				entry.getValue().deleteConnection();
				delmark.put(entry.getKey(), entry.getValue());
				
			} catch(NullPointerException ne){
				//logger.info("[Node] BlockUntil: Reply had a null value"+entry.getKey().toString());
				delmark.put(entry.getKey(),entry.getValue());
				//ne.printStackTrace();
			} catch (Exception e){
				//logger.info("[Node] Error closing connection: "+entry.getKey().toString());
				delmark.put(entry.getKey(),entry.getValue());
				e.printStackTrace();
			}
		}
		
		for (HashMap.Entry<String, NioClient> entry: delmark.entrySet()){
			this.socketDict.remove(entry.getKey());
		}
		
		this.socketDict = new HashMap<String, NioClient>();
		
		return;
		
	}
	

	/**
	 * Will first expire all connections in the socketDict and keep spinning until,
	 * > majority % nodes from the connectSet get connected.
	 */
	
	@Override
	public ElectionState blockUntilConnected() {
		
		cleanState();
		
		while (this.socketDict.size() < this.majority){
			try {
				// logger.info("[Node] BlockUntil: Trying to connect...");
				this.connectClients();
				
				//Flush the context to avoid too many open files
				// 250 ticks = 4 min 55 seconds
				if(ticks > 250) {
					//logger.debug("[NetworkNode] Refreshing state....");
					cleanState();
					//logger.debug("[NetworkNode] Refreshed state....");
					ticks = 0;
				}
				
				ticks += 1;
				//logger.debug("[ZMQ Node] Tick {} ", new Object[] {ticks});
				TimeUnit.MILLISECONDS.sleep(pollTime);
			} catch (Exception e){
				//logger.debug("[NetworkNode] BlockUntil errored out: "+e.toString());
				e.printStackTrace();
			}
		}
		
		updateConnectDict();
		return ElectionState.ELECT;
	}

	@Override
	public void run() {
		//ScheduledExecutorService sesNode = Executors.newScheduledThreadPool(10);
		try{
			//logger.info("Server List: "+this.serverList.toString());
			//Thread qd = new Thread(qDevice,"QueueDeviceThread");
			//qd.start();
			//qd.join();
		} catch (Exception e){
			//logger.debug("[NetworkNode] Queue Device encountered an exception! "+e.toString());
			e.printStackTrace();
		}
	}
	
	@Override
	public Map<String, netState> getConnectDict(){
		return (Map<String, netState>) Collections.unmodifiableMap(this.connectDict);
	}
	
	public Map<String, Integer> getnetControllerIDStatic(){
		return (Map<String, Integer>) Collections.unmodifiableMap(this.netcontrollerIDStatic);
	}

	/**
	 * This function translates socketDict into connectDict, in order to 
	 * preserve the abstraction of the underlying network from the actual 
	 * election algorithm.
	 */
	
	@Override
	public void updateConnectDict() {
		this.connectDict     = new HashMap<String, netState>();
		
		for (String seten: this.connectSet){
			this.connectDict.put(seten, netState.OFF);
		}
		
		for (HashMap.Entry<String, NioClient> entry: this.socketDict.entrySet()){
			this.connectDict.put(entry.getKey(), netState.ON);
		}
		
		// logger.info("Connect Dict: "+this.connectDict.toString());
		//logger.info("Socket Dict: "+this.socketDict.toString());
		
		return;
		
		
	}

}
