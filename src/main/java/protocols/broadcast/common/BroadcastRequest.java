package protocols.broadcast.common;

import java.util.UUID;

import pt.unl.fct.di.novasys.babel.generic.ProtoRequest;
import pt.unl.fct.di.novasys.network.data.Host;

/**
 * Sent by an application protocol (here, the chat) to ask the broadcast protocol
 * to disseminate a payload to everyone. This is the broadcast layer's public
 * "input" — the chat builds one of these and calls {@code sendRequest(req,
 * FloodBroadcast.PROTOCOL_ID)}; it never talks to the network itself.
 *
 * <p>The payload is an opaque {@code byte[]}: the broadcast doesn't care what's
 * in it. The chat app frames its own little message format (a type byte + nick +
 * text) inside this — a clean example of layering an application protocol on top
 * of a generic transport.
 */
public class BroadcastRequest extends ProtoRequest {

    // Broadcast owns protocol id 200; requests/replies share a pool — 202 here
    // (201 is taken by DeliverNotification, in the notification pool).
    public static final short REQUEST_ID = 202;

    private final UUID msgId;   // unique id so duplicates can be detected
    private final Host sender;  // the original sender (for display / dedup)
    private final byte[] msg;   // opaque application payload

    public BroadcastRequest(UUID msgId, Host sender, byte[] msg) {
        super(REQUEST_ID);
        this.msgId = msgId;
        this.sender = sender;
        this.msg = msg;
    }

    public UUID getMsgId() {
        return msgId;
    }

    public Host getSender() {
        return sender;
    }

    public byte[] getMsg() {
        return msg;
    }
}
