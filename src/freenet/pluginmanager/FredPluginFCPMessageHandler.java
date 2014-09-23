/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.pluginmanager;

import java.io.IOException;
import java.util.UUID;

import freenet.node.fcp.FCPPluginClient;
import freenet.support.SimpleFieldSet;
import freenet.support.api.Bucket;
import freenet.support.io.NativeThread;

/**
 * FCP server or client plugins which transfer FCP messages to each other using a
 * {@link FCPPluginClient} must implement this interface, or even better one of it's child
 * interfaces, to provide a function which handles the received messages.<br><br>
 * 
 * For symmetry, the child interfaces {@link ClientSideFCPMessageHandler} and
 * {@link ServerSideFCPMessageHandler} do not provide any different functions.<br>
 * They exist nevertheless to allow JavaDoc to explain differences in what the server and client are
 * allowed to do.<br>
 * You <b>must</b> follow the restrictions which are explained there.<br>
 * For clarity, you <b>should</b> implement the child interfaces instead of this interface.<br><br>
 * 
 * If you want to specify the thread priority of the message handling functions, you can
 * additionally implement the member interface {@link PrioritizedMessageHandler}.<br><br>
 * 
 * As opposed to the old {@link FredPluginFCP} and {@link FredPluginTalker} message handler
 * interfaces, and their {@link PluginReplySender} and {@link PluginTalker} message sending
 * counterparts, this new API is as symmetric as possible:<br>
 * Both the message handler and message sender is now one interface / class shared by both server
 * and client, instead of different ones for each - {@link FredPluginFCPMessageHandler} and
 * {@link FCPPluginClient}.<br>
 * With the old interface, the server could only <i>reply</i> to messages of the client, it could
 * not send a message without a previous client message.<br>
 * With this implementation, server and client are free to send messages to each others whenever
 * they like to.<br>
 * The only restriction upon this is that the opening and closing of connections is dictated by the
 * client. The server cannot connect to a client on its own.
 * 
 * @author xor (xor@freenetproject.org)
 * @see PluginRespirator#connecToOtherPlugin(String,
 *      FredPluginFCPMessageHandler.ClientSideFCPMessageHandler)
 *          PluginRespirator provides the function to connect to a FCP server plugin.
 * @see FCPPluginClient
 *          A client will be represented as class FCPPluginClient to the client and server plugin.
 *          It's JavaDoc provides an overview of the internal code paths through which plugin FCP
 *          messages flow.
 */
public interface FredPluginFCPMessageHandler {

    /**
     * Container class for both incoming and outgoing FCP messages.
     */
    public final class FCPPluginMessage {
        public static enum ClientPermissions {
            /** The client is connected by network and the owner of the node has configured
             *  restricted access for the client's IP */
            ACCESS_FCP_RESTRICTED,
            /** The client is connected by network and the owner of the node has configured full
             *  access for the client's IP */
            ACCESS_FCP_FULL,
            /** The client plugin is running within the same node as the server plugin.<br>
             *  This probably should be interpreted as {@link #ACCESS_FCP_FULL}: If the client
             *  plugin is running inside the node, it can probably do whatever it wants. We're
             *  nevertheless shipping this information to you as it is available anyway. */
            ACCESS_DIRECT
        };
        
        /**
         * The permissions of the client which sent the messages. Null for server-to-client and
         * outgoing messages.
         */
        public final ClientPermissions permissions;
        
        /**
         * The unique identifier of the message.<br>
         * Can be used by server and client to track the progress of messages.<br>
         * This especially applies to the synchronous send functions of {@link FCPPluginClient}
         * which will wait for a reply with the same identifier as the original message until they
         * return.<br><br>
         * 
         * For reply messages, this shall be the same as the identifier of the message to which this
         * is a reply.<br>
         * For non-reply message, this shall be a sufficiently random String to prevent collisions
         * with any previous message identifiers.<br><br>
         * 
         * <b>Notice:</b> Custom client implementations can chose the identifier freely when sending
         * messages, and thus violate these rules. This is highly discouraged though, as non-unique
         * identifiers make tracking messages impossible. But if a client does violate the rules and
         * thereby breaks its message tracking, thats not the server's problem, and thus should not
         * cause complexification of the server code.<br>
         * So server implementations <b>should</b> assume that the client chooses the identifier in
         * a sane manner which follows the rules.<br>
         * This class does follow the rules, and thus client and server implementations using it
         * will do so as well.
         */
        public final String identifier;
        
        /**
         * Part 1 of the actual message: Human-readable parameters. Shall be small amount of data.
         * Can be null for data-only or success-indicator messages.
         */
        public final SimpleFieldSet parameters;
        
        /**
         * Part 2 of the actual message: Non-human readable, large size bulk data. Can be null if no
         * large amount of data is to be transfered.
         */
        public final Bucket data;
        
        /**
         * For messages which are a reply to another message, this true if the operation requested
         * by the original messages succeeded.<br>
         * For non-reply messages, this is null.
         */
        public final Boolean success;

        /**
         * For reply messages with {@link #success} == false, may contain an alpha-numeric String
         * which identifies a reason for the failure in a standardized representation which software
         * can parse easily. May also be null in that case, but please try to not do that.<br>
         * For {@link #success} == null or true, this must be null.<br><br>
         * 
         * The String shall be for programming purposes and thus <b>must</b> be alpha-numeric.<br>
         * For unclassified errors, such as Exceptions which you do not expect, use "InternalError".
         */
        public final String errorCode;

        /**
         * For reply messages with {@link #errorCode} != null, may contain a String which describes
         * the problem in a human-readable, user-friendly manner. May also be null in that case, but
         * please try to not do that.<br>
         * For {@link #errorCode} == null, this must be null.<br><br>
         * 
         * You are encouraged to provide it translated to the configured language already.<br>
         * The String shall not be used for identifying problems in programming.<br>
         * There, use {@link #errorCode}.
         * For Exceptions which you do not expect, {@link Exception#toString()} will return a
         * sufficient errorMessage (containing the name of the Exception and the localized error
         * message, or non-localized if there is no translation).
         * 
         * (Notice: This may only be non-null if {@link #errorCode} is non-null instead of just
         * if {@link #success} == false to ensure that a developer-friendly error signaling is
         * implemented: errorCode is designed to be easy to parse, errorMessage is designed
         * to be human readable and thus cannot be parsed. Therefore, the errorCode field should be
         * more mandatory than this field.)
         */
        public final String errorMessage;

        /**
         * @return True if the message is merely a reply to a previous message from your side.<br>
         *         In that case, you should probably not send another reply message back to prevent
         *         infinite bouncing of "success!" replies.
         */
        public boolean isReplyMessage() {
            return success != null;
        }
        
        /**
         * See the JavaDoc of the member variables with the same name as the parameters for an
         * explanation of the parameters.
         */
        private FCPPluginMessage(ClientPermissions permissions, String identifier,
                SimpleFieldSet parameters, Bucket data, Boolean success, String errorCode,
                String errorMessage) {
            
            // See JavaDoc of member variables with the same name for reasons of the requirements
            assert(permissions != null || permissions == null);
            assert(identifier != null);
            assert(parameters != null || parameters == null);
            assert(data != null || data == null);
            assert(success != null || success == null);
            
            assert(parameters != null || data != null || success != null)
                : "Messages should not be empty";
            
            assert(errorCode == null || (success != null && success == false))
                : "errorCode should only be provided for reply messages which indicate failure.";
            
            assert(errorMessage == null || errorCode != null)
                : "errorCode should always be provided if there is an errorMessage";
            
            this.permissions = permissions;
            this.identifier = identifier;
            this.parameters = parameters;
            this.data = data;
            this.success = success;
            this.errorCode = errorCode;
            this.errorMessage = errorMessage;
        }
        
        /**
         * For being used by server or client to construct outgoing messages.<br>
         * Those can then be passed to the send functions of {@link FCPPluginClient} or returned in
         * the message handlers of {@link FredPluginFCPMessageHandler}.<br><br>
         * 
         * <b>Notice</b>: Messages constructed with this constructor are <b>not</b> reply messages.
         * <br>
         * If you are replying to a message, notably when returning a message in the message
         * handling function, use {@link #constructReplyMessage(FCPPluginMessage, SimpleFieldSet,
         * Bucket, boolean, String, String)} instead.<br><br>
         * 
         * See the JavaDoc of the member variables with the same name as the parameters for an
         * explanation of the parameters.
         */
        public static FCPPluginMessage construct(SimpleFieldSet parameters, Bucket data) {
            // Notice: While the specification of FCP formally allows the client to freely chose the
            // ID, we hereby restrict it to be a random UUID instead of allowing the client
            // (or server) to chose it. This is to prevent accidental collisions with the IDs of
            // other messages. I cannot think of any usecase of free-choice identifiers. And
            // collisions are *bad*: They can break the "ACK" mechanism of the "success" variable.
            // This would in turn break things such as the sendSynchronous() functions of
            // FCPPluginClient.
            return new FCPPluginMessage(null, UUID.randomUUID().toString(), parameters, data,
                // success, errorCode, errorMessage are null since non-reply messages must not
                // indicate errors
                null, null, null);
        }
        
        /**
         * For being used by server or client to construct outgoing messages which are a reply to an
         * original message.<br>
         * Those then can be returned from the message handler
         * {@link FredPluginFCPMessageHandler#handlePluginFCPMessage(FCPPluginClient,
         * FCPPluginMessage)}.<br><br>
         * 
         * See the JavaDoc of the member variables with the same name as the parameters for an
         * explanation of the parameters.
         * 
         * @throws IllegalStateException
         *             If the original message was a reply message already.<br>
         *             Replies often shall only indicate success / failure instead of triggering
         *             actual operations, so it could cause infinite bouncing if you reply to them
         *             again.<br>
         *             Consider the whole of this as a remote procedure call process: A non-reply
         *             message is the procedure call, a reply message is the procedure result. When
         *             receiving the result, the procedure call is finished, and thus shouldn't
         *             cause further replies to be sent.<br>
         *             <b>Notice</b>: The JavaDoc of the aforementioned message handling function
         *             explains how you can nevertheless send a reply to reply messages.
         */
        public static FCPPluginMessage constructReplyMessage(FCPPluginMessage originalMessage,
                SimpleFieldSet parameters, Bucket data, boolean success, String errorCode,
                String errorMessage) {
            
            if(originalMessage.isReplyMessage()) {
                throw new IllegalStateException("Constructing a reply message for a message which "
                    + "was a reply message already not allowed.");
            }
            
            return new FCPPluginMessage(null, originalMessage.identifier,
                parameters, data, success, errorCode, errorMessage);
        }
        
        /**
         * Only for being used by internal network code.<br><br>
         * 
         * You <b>must not</b> use this for constructing outgoing messages in server or client
         * implementations.<br>
         * There, use {@link #construct(SimpleFieldSet, Bucket)} and
         * {@link #constructReplyMessage(FCPPluginMessage, SimpleFieldSet, Bucket, boolean, String,
         * String)}.<br>
         * <br>
         * 
         * This function is typically to construct incoming messages for passing them to the message
         * handling function
         * {@link FredPluginFCPMessageHandler#handlePluginFCPMessage(FCPPluginClient,
         * FCPPluginMessage)}.<br><br>
         * 
         * See the JavaDoc of the member variables with the same name as the parameters for an
         * explanation of the parameters.<br>
         */
        public static FCPPluginMessage constructRawMessage(ClientPermissions permissions,
                String identifier, SimpleFieldSet parameters, Bucket data, Boolean success,
                String errorCode, String errorMessage) {
            
            return new FCPPluginMessage(permissions, identifier, parameters, data, success,
                errorCode, errorMessage);
        }

        @Override
        public String toString() {
            return super.toString() +
                " (permissions: " + permissions +
                "; identifier: " + identifier +
                "; parameters: " + parameters +
                "; data: " + data +
                "; success: " + success +
                "; errorCode: " + errorCode +
                "; errorMessage: " + errorMessage + ")";
        }
    }


    /**
     * Message handling function for messages received from a plugin FCP server or client.<br/><br/>
     * 
     * <b>ATTENTION</b>: Please read the different constraints for server and client side message
     * handlers at the child interfaces:<br/>
     * - {@link ServerSideFCPMessageHandler#handlePluginFCPMessage(FCPPluginClient,
     *   FCPPluginMessage)}<br/>
     * - {@link ClientSideFCPMessageHandler#handlePluginFCPMessage(FCPPluginClient,
     *   FCPPluginMessage)}<br/>
     * 
     * To stress those different constraints, you should also not implement this interface but one
     * of the child interfaces {@link ServerSideFCPMessageHandler} and
     * {@link ClientSideFCPMessageHandler}.
     */
    FCPPluginMessage handlePluginFCPMessage(FCPPluginClient client, FCPPluginMessage message);


    /**
     * Plugins which provide FCP services to clients must implement this interface.<br/>
     * The purpose of this interface is to provide a message handling function for processing
     * messages received from the clients.
     * 
     * @see FredPluginFCPMessageHandler
     *          The parent interface FredPluginFCPMessageHandler provides an overview.
     * @see ClientSideFCPMessageHandler
     *          The interface of the client plugin which handles the messages sent by the server.
     */
    public interface ServerSideFCPMessageHandler extends FredPluginFCPMessageHandler {
        /**
         * <p>Is called to handle messages from your clients.<br/>
         * <b>Must not</b> block for very long and thus must only do small amounts of processing.
         * <br/>
         * You <b>must not</b> keep a hard reference to the passed {@link FCPPluginClient} object
         * outside of the scope of this function: This would prevent client plugins from being
         * unloaded. See below for how to keep a reference to a client.</p>
         * 
         * <p>If you ...<br/>
         * - Need a long time to compute a reply.<br/>
         * - Keep a reference to the client because you want send messages to the client after having
         *   exited this function; maybe even triggered by events at your plugin, not by client
         *   messages.<br/>
         * Then you should:<br/>
         * - Obtain the UUID of the client via {@link FCPPluginClient#getID()}, store the UUID,
         *   and exit this message handling function.<br/>
         * - Compute your reply in another thread.</br>
         * - Once you're ready to send the reply, use
         *   {@link PluginRespirator#getPluginClientByID(java.util.UUID)} to obtain the client.<br/>
         * - If you keep client UUID for longer than sending a single reply, you should periodically
         *   send messages to the client to check whether it is still alive to prevent excessive
         *   growth of your client UUID database. If the client has disconnected, you will then get
         *   an {@link IOException} by {@link PluginRespirator#getPluginClientByID(UUID)} or
         *   {@link FCPPluginClient#send(FCPPluginClient.SendDirection, FCPPluginMessage)}. Then
         *   you shall remove it from your database.<br>
         *   Consider discarding client UUIDs upon IOException as the disconnection mechanism:
         *   There are no explicit disconnection functions. Clients can come and go as they please.
         *   <br>
         * </p>
         * 
         * @param client
         *            The client which sent the message.<br/><br/>
         *            You <b>must not</b> keep a hard reference to this object outside of the scope
         *            of this function: This would prevent client plugins from being unloaded. See
         *            the head of the documentation of this function for an explanation of how to
         *            store a pointer to a certain client.<br/><br/>
         * 
         *            You <b>must not</b> use its send functions for sending back the main reply.
         *            Instead, use the return value for shipping the reply.<br/>
         *            You are free to send "out of band" secondary replies using the client.<br/>
         *            This is to ensure that if the sender of the original message used a
         *            <i>synchronous</i> send function at {@link FCPPluginClient}, the send function
         *            will be able to return your reply message: This mechanism only works for
         *            returned replies, not for out of band replies. <br/><br/>
         * @param message
         *            The actual message. See the JavaDoc of its member variables for an explanation
         *            of their meaning.
         * @return Your reply message, or null if you don't want to reply.<br/><br/>
         * 
         *         You <b>must</b> construct this by using the constructor
         *         {@link FCPPluginMessage#constructReplyMessage(FCPPluginMessage, SimpleFieldSet,
         *         Bucket, boolean, String, String)} to ensure that the
         *         {@link FCPPluginMessage#identifier} gets preserved.<br/><br/>
         * 
         *         You <b>must</b> return null if the original message was a reply message as
         *         indicated by {@link FCPPluginMessage#isReplyMessage()}<br/>
         *         Replies often shall only indicate success / failure instead of triggering actual
         *         operations, so it could cause infinite bouncing if you reply to them again.<br/>
         *         If you still have to send a message to do further operations, you should create a
         *         new "dialog" by sending an "out of band" message using the passed
         *         {@link FCPPluginClient}, as explained in the description of this function.<br/>
         *         Consider the whole of this as a remote procedure call process: A non-reply
         *         message is the procedure call, a reply message is the procedure result. When
         *         receiving the result, the procedure call is finished, and shouldn't contain
         *         further replies.
         * @throws RuntimeException
         *             You <b>must not</b> throw. Please instead return a reply with
         *             {@link FCPPluginMessage#success} set to false, and optionally describe the
         *             problem via {@link FCPPluginMessage#errorCode} and
         *             {@link FCPPluginMessage#errorMessage}.
         */
        @Override
        FCPPluginMessage handlePluginFCPMessage(FCPPluginClient client, FCPPluginMessage message);
    }

    /**
     * Client plugins which connect to a FCP server plugin must implement this interface.<br/>
     * The purpose of this interface is to provide a message handling function for processing
     * messages received from the server.
     * 
     * @see FredPluginFCPMessageHandler
     *          The parent interface FredPluginFCPMessageHandler provides an overview.
     * @see ServerSideFCPMessageHandler
     *          The interface of the server plugin which handles the messages sent by the client.
     */
    public interface ClientSideFCPMessageHandler extends FredPluginFCPMessageHandler {
        /**
         * Is called to handle messages from the server after you sent a message to it using a
         * {@link FCPPluginClient}.<br/><br/>
         * 
         * <b>ATTENTION:</b> The server is free to send messages to you on its own, that is not
         * triggered by any message which you sent.<br/>
         * This can happen for as long as you keep the connection open by having a hard reference to
         * the original {@link FCPPluginClient} in memory.<br/>
         * The purpose of this mechanism is for example to allow the server to tell you about events
         * which happened at its side.<br>
         * For such messages, the {@link FCPPluginMessage#identifier} will not match any of your
         * previous messages.
         * 
         * @param client
         *            The client which you used to open the connection to the server.<br/><br/>
         * 
         *            You <b>must not</b> use its send functions for sending back the main reply.
         *            Instead, use the return value for shipping the reply.<br/>
         *            You are free to send "out of band" secondary replies using the client.<br/>
         *            This is to ensure that if the sender of the original message used a
         *            <i>synchronous</i> send function at {@link FCPPluginClient}, the send function
         *            will be able to return your reply message: This mechanism only works for
         *            returned replies, not for out of band replies. <br/><br/>
         * @param message
         *            The actual message. See the JavaDoc of its member variables for an explanation
         *            of their meaning.
         * @return Your reply message, or null if you don't want to reply.<br/><br/>
         * 
         *         You <b>must</b> construct this by using the constructor
         *         {@link FCPPluginMessage#constructReplyMessage(FCPPluginMessage, SimpleFieldSet,
         *         Bucket, boolean, String, String)} to ensure that the
         *         {@link FCPPluginMessage#identifier} gets preserved.<br/><br/>
         * 
         *         You <b>must</b> return null if the original message was a reply message as
         *         indicated by {@link FCPPluginMessage#isReplyMessage()}<br/>
         *         Replies often shall only indicate success / failure instead of triggering actual
         *         operations, so it could cause infinite bouncing if you reply to them again.<br/>
         *         If you still have to send a message to do further operations, you should create a
         *         new "dialog" by sending an "out of band" message using the passed
         *         {@link FCPPluginClient}, as explained in the description of this function.<br/>
         *         Consider the whole of this as a remote procedure call process: A non-reply
         *         message is the procedure call, a reply message is the procedure result. When
         *         receiving the result, the procedure call is finished, and shouldn't contain
         *         further replies.
         * @throws RuntimeException
         *             You <b>must not</b> throw. Please instead return a reply with
         *             {@link FCPPluginMessage#success} set to false, and optionally describe the
         *             problem via {@link FCPPluginMessage#errorCode} and
         *             {@link FCPPluginMessage#errorMessage}.
         */
        @Override
        FCPPluginMessage handlePluginFCPMessage(FCPPluginClient client, FCPPluginMessage message);
    }
    
    /**
     * Implement this if you want to specify the thread priority of threads which are used to
     * execute the message handling function
     * {@link FredPluginFCPMessageHandler#handlePluginFCPMessage(FCPPluginClient, FCPPluginMessage)}
     */
    public interface PrioritizedMessageHandler {
        /**
         * @see PrioritizedMessageHandler
         */
        public NativeThread.PriorityLevel getPriority();
    }
}
