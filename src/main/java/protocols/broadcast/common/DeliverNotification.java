package protocols.broadcast.common;

import java.util.UUID;

import pt.unl.fct.di.novasys.babel.generic.ProtoNotification;
import pt.unl.fct.di.novasys.network.data.Host;

/**
 * The broadcast layer's "output": emitted exactly once per unique message, to
 * hand a delivered payload up to the application. The chat app subscribes to this
 * to learn about incoming global messages and join/leave announcements.
 *
 * <p>Note it fires for messages we sent ourselves too — so the sender also
 * "delivers" its own broadcast, which keeps the application logic uniform (you
 * handle your own and others' messages the same way).
 */
public class DeliverNotification extends ProtoNotification {

    public static final short NOTIFICATION_ID = 201;

    private final UUID msgId;
    private final Host sender;
    private final byte[] msg;

    public DeliverNotification(UUID msgId, Host sender, byte[] msg) {
        super(NOTIFICATION_ID);
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
