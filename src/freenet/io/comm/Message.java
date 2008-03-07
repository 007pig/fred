/*
 * Dijjer - A Peer to Peer HTTP Cache
 * Copyright (C) 2004,2005 Change.Tv, Inc
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package freenet.io.comm;

import java.io.*;
import java.lang.ref.WeakReference;
import java.util.*;

import freenet.support.Fields;
import freenet.support.Logger;
import freenet.support.Serializer;
import freenet.support.ShortBuffer;

/**
 * A Message which can be read from and written to a DatagramPacket
 *
 * @author ian
 */
public class Message {

    public static final String VERSION = "$Id: Message.java,v 1.11 2005/09/15 18:16:04 amphibian Exp $";

	private final MessageType _spec;
	private final WeakReference/*<PeerContext>*/ _sourceRef;
	private final boolean _internal;
	private final HashMap _payload = new HashMap(8, 1.0F); // REDFLAG at the moment memory is more of an issue than CPU so we use a high load factor
	private Vector _subMessages;
	public final long localInstantiationTime;
	final int _receivedByteCount;

	public static Message decodeMessageFromPacket(byte[] buf, int offset, int length, PeerContext peer, int overhead) {
		DataInputStream dis
	    = new DataInputStream(new ByteArrayInputStream(buf,
	        offset, length));
		return decodeMessage(dis, peer, length + overhead, true, false);
	}
	
	public static Message decodeMessage(DataInputStream dis, PeerContext peer, int recvByteCount, boolean mayHaveSubMessages, boolean inSubMessage) {
		boolean logMINOR = Logger.shouldLog(Logger.MINOR, Message.class);
		MessageType mspec;
        try {
            mspec = MessageType.getSpec(new Integer(dis.readInt()));
        } catch (IOException e1) {
        	if(Logger.shouldLog(Logger.DEBUG, Message.class))
        		Logger.minor(Message.class,"Failed to read message type: "+e1, e1);
            return null;
        }
        if (mspec == null) {
		    return null;
		}
		if(mspec.isInternalOnly())
		    return null; // silently discard internal-only messages
		Message m = new Message(mspec, peer, recvByteCount);
		try {
		    for (Iterator i = mspec.getOrderedFields().iterator(); i.hasNext();) {
		        String name = (String) i.next();
		        Class type = (Class) mspec.getFields().get(name);
		        if (type.equals(LinkedList.class)) { // Special handling for LinkedList to deal with element type
		            m.set(name, Serializer.readListFromDataInputStream((Class) mspec.getLinkedListTypes().get(name), dis));
		        } else {
		            m.set(name, Serializer.readFromDataInputStream(type, dis));
		        }
		    }
		    if(mayHaveSubMessages) {
		    	while(true) {
		    		DataInputStream dis2;
		    		try {
			    		int size = dis.readUnsignedShort();
			    		byte[] buf = new byte[size];
		    			dis.readFully(buf);
			    		dis2 = new DataInputStream(new ByteArrayInputStream(buf));
		    		} catch (EOFException e) {
		    			if(logMINOR) Logger.minor(Message.class, "No submessages, returning: "+m);
		    			return m;
		    		}
		    		try {
		    			Message subMessage = decodeMessage(dis2, peer, 0, false, true);
		    			if(subMessage == null) return m;
		    			if(logMINOR) Logger.minor(Message.class, "Adding submessage: "+subMessage);
		    			m.addSubMessage(subMessage);
		    		} catch (Throwable t) {
		    			Logger.error(Message.class, "Failed to read sub-message: "+t, t);
		    		}
		    	}
		    }
		} catch (EOFException e) {
			String msg = peer.getPeer()+" sent a message packet that ends prematurely while deserialising "+mspec.getName();
			if(inSubMessage)
				Logger.minor(Message.class, msg+" in sub-message", e);
			else
				Logger.error(Message.class, msg, e);
		    return null;
		} catch (IOException e) {
		    Logger.error(Message.class, "Unexpected IOException: "+e+" reading from buffer stream", e);
		    return null;
		}
		return m;
	}
	
	public Message(MessageType spec) {
		this(spec, null, 0);
	}

	private Message(MessageType spec, PeerContext source, int recvByteCount) {
		localInstantiationTime = System.currentTimeMillis();
		_spec = spec;
		if(source == null) {
			_internal = true;
			_sourceRef = null;
		} else {
			_internal = false;
			_sourceRef = source.getWeakRef();
		}
		_receivedByteCount = recvByteCount;
	}

	public boolean getBoolean(String key) {
		return ((Boolean) _payload.get(key)).booleanValue();
	}

	public byte getByte(String key) {
		return ((Byte) _payload.get(key)).byteValue();
	}

	public short getShort(String key) {
		return ((Short) _payload.get(key)).shortValue();
	}

	public int getInt(String key) {
		return ((Integer) _payload.get(key)).intValue();
	}

	public long getLong(String key) {
		return ((Long) _payload.get(key)).longValue();
	}

	public double getDouble(String key) {
	    return ((Double) _payload.get(key)).doubleValue();
	}
	
	public String getString(String key) {
		return (String)_payload.get(key);
	}

	public Object getObject(String key) {
		return _payload.get(key);
	}

	public void set(String key, boolean b) {
		set(key, Boolean.valueOf(b));
	}

	public void set(String key, byte b) {
		set(key, new Byte(b));
	}

	public void set(String key, short s) {
		set(key, new Short(s));
	}

	public void set(String key, int i) {
		set(key, new Integer(i));
	}

	public void set(String key, long l) {
		set(key, new Long(l));
	}

    public void set(String key, double d) {
        set(key, new Double(d));
    }
    
	public void set(String key, Object value) {
		if (!_spec.checkType(key, value)) {
			if (value == null) {
				throw new IncorrectTypeException("Got null for " + key);				
			}
			throw new IncorrectTypeException("Got " + value.getClass() + ", expected " + _spec.typeOf(key));
		}
		_payload.put(key, value);
	}

	public byte[] encodeToPacket(PeerContext destination) {
		return encodeToPacket(destination, true, false);
	}
	
	private byte[] encodeToPacket(PeerContext destination, boolean includeSubMessages, boolean isSubMessage) {
//		if (this.getSpec() != MessageTypes.ping && this.getSpec() != MessageTypes.pong)
//		Logger.logMinor("<<<<< Send message : " + this);

    	if(Logger.shouldLog(Logger.DEBUG, Message.class))
    		Logger.minor(this, "My spec code: "+_spec.getName().hashCode()+" for "+_spec.getName());
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		DataOutputStream dos = new DataOutputStream(baos);
		try {
			dos.writeInt(_spec.getName().hashCode());
			for (Iterator i = _spec.getOrderedFields().iterator(); i.hasNext();) {
				String name = (String) i.next();
				Serializer.writeToDataOutputStream(_payload.get(name), dos, destination);
			}
			dos.flush();
		} catch (IOException e) {
			e.printStackTrace();
			throw new IllegalStateException(e.getMessage());
		}
		
		if(_subMessages != null && includeSubMessages) {
			for(int i=0;i<_subMessages.size();i++) {
				byte[] temp = ((Message)_subMessages.get(i)).encodeToPacket(destination, false, true);
				try {
					dos.writeShort(temp.length);
					dos.write(temp);
				} catch (IOException e) {
					e.printStackTrace();
					throw new IllegalStateException(e.getMessage());
				}
			}
		}
		
		byte[] buf = baos.toByteArray();
    	if(Logger.shouldLog(Logger.DEBUG, Message.class))
    		Logger.minor(this, "Length: "+buf.length+", hash: "+Fields.hashCode(buf));
		return buf;
	}

	public String toString() {
		StringBuffer ret = new StringBuffer(1000);
		String comma = "";
        ret.append(_spec.getName()).append(" {");
		for (Iterator i = _spec.getFields().keySet().iterator(); i.hasNext();) {
			ret.append(comma);
			String name = (String) i.next();
            ret.append(name).append('=').append(_payload.get(name));
			comma = ", ";
		}
		ret.append('}');
		return ret.toString();
	}

	public PeerContext getSource() {
		return _sourceRef == null ? null : (PeerContext) _sourceRef.get();
	}

	public boolean isInternal() {
	    return _internal;
	}
	
	public MessageType getSpec() {
		return _spec;
	}

	public boolean isSet(String fieldName) {
		return _payload.containsKey(fieldName);
	}
	
	public Object getFromPayload(String fieldName) throws FieldNotSetException {
		Object r =  _payload.get(fieldName);
		if (r == null) {
			throw new FieldNotSetException(fieldName+" not set");
		}
		return r;
	}
	
	public static class FieldNotSetException extends RuntimeException {
		private static final long serialVersionUID = 1L;
		
		public FieldNotSetException(String message) {
			super(message);
		}
	}

    /**
     * Set fields for a routed-to-a-specific-node message.
     * @param nodeIdentity 
     */
    public void setRoutedToNodeFields(long uid, double targetLocation, short htl, byte[] nodeIdentity) {
        set(DMT.UID, uid);
        set(DMT.TARGET_LOCATION, targetLocation);
        set(DMT.HTL, htl);
        set(DMT.NODE_IDENTITY, new ShortBuffer(nodeIdentity));
    }

	public int receivedByteCount() {
		return _receivedByteCount;
	}
	
	public void addSubMessage(Message subMessage) {
		if(_subMessages == null) _subMessages = new Vector();
		_subMessages.add(subMessage);
	}
	
	public Message getSubMessage(MessageType t) {
		if(_subMessages == null) return null;
		for(int i=0;i<_subMessages.size();i++) {
			Message m = (Message) _subMessages.get(i);
			if(m.getSpec() == t) return m;
		}
		return null;
	}

	public Message grabSubMessage(MessageType t) {
		if(_subMessages == null) return null;
		for(int i=0;i<_subMessages.size();i++) {
			Message m = (Message) _subMessages.get(i);
			if(m.getSpec() == t) {
				_subMessages.remove(i);
				return m;
			}
		}
		return null;
	}
	
	public long age() {
		return System.currentTimeMillis() - localInstantiationTime;
	}

}