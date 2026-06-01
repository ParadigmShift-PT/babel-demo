package protocols.broadcast.flood.messages;

import java.io.IOException;
import java.sql.Timestamp;
import java.util.UUID;

import io.netty.buffer.ByteBuf;
import pt.unl.fct.di.novasys.babel.protocols.dissemination.messages.IdentifiableProtoMessage;
import pt.unl.fct.di.novasys.babel.protocols.dissemination.notifications.BroadcastDelivery;
import pt.unl.fct.di.novasys.network.ISerializer;
import pt.unl.fct.di.novasys.network.data.Host;

/**
 * The flood's own wire message. It extends {@link IdentifiableProtoMessage}, the
 * canonical ParadigmShift base for broadcast messages: a {@link UUID} message-id
 * (the "MID") is generated when the broadcast protocol creates the message,
 * carried unchanged across every hop, and used to deduplicate. The broadcast
 * protocol dedups on {@link #getMID()} and calls
 * {@link #generateDeliveryNotification(short)} to build the application-facing
 * {@link BroadcastDelivery} — so the message id lives on the message, not in the
 * protocol's send path. (Same pattern as the production {@code GossipMessage}.)
 *
 * <p>Besides the inherited MID it carries the original sender and the issue
 * timestamp (so every receiver reconstructs an identical delivery) and the
 * opaque application payload.
 */
public class BroadcastMessage extends IdentifiableProtoMessage {

    // Broadcast owns protocol id 200; its messages start at 201.
    public static final short MSG_ID = 201;

    private final Host sender;
    private final Timestamp timestamp;
    private final byte[] content;

    /** A fresh broadcast initiated locally — {@link IdentifiableProtoMessage} assigns a new MID. */
    public BroadcastMessage(Host sender, Timestamp timestamp, byte[] content) {
        super(MSG_ID);
        this.sender = sender;
        this.timestamp = timestamp;
        this.content = content;
    }

    /** Deserializer target — preserves the MID across hops. */
    private BroadcastMessage(UUID mid, Host sender, long timestamp, byte[] content) {
        super(MSG_ID, mid);
        this.sender = sender;
        this.timestamp = new Timestamp(timestamp);
        this.content = content;
    }

    @Override
    public BroadcastDelivery generateDeliveryNotification(short sourceProtoID) {
        return new BroadcastDelivery(sender, content.clone(), new Timestamp(timestamp.getTime()));
    }

    public Host getSender() {
        return sender;
    }

    public Timestamp getTimestamp() {
        return timestamp;
    }

    public byte[] getContent() {
        return content;
    }

    @Override
    public String toString() {
        return "BroadcastMessage{mid=" + getMID() + '}';
    }

    public static final ISerializer<BroadcastMessage> serializer = new ISerializer<>() {
        @Override
        public void serialize(BroadcastMessage m, ByteBuf out) throws IOException {
            // The MID first (two longs), then sender, timestamp and payload.
            out.writeLong(m.getMID().getMostSignificantBits());
            out.writeLong(m.getMID().getLeastSignificantBits());
            Host.serializer.serialize(m.sender, out);
            out.writeLong(m.timestamp.getTime());
            out.writeInt(m.content.length);
            if (m.content.length > 0) {
                out.writeBytes(m.content);
            }
        }

        @Override
        public BroadcastMessage deserialize(ByteBuf in) throws IOException {
            UUID mid = new UUID(in.readLong(), in.readLong());
            Host sender = Host.serializer.deserialize(in);
            long timestamp = in.readLong();
            int size = in.readInt();
            byte[] content = new byte[size];
            if (size > 0) {
                in.readBytes(content);
            }
            return new BroadcastMessage(mid, sender, timestamp, content);
        }
    };
}
