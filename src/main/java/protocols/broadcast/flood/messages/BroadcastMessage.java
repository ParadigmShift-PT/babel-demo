package protocols.broadcast.flood.messages;

import java.io.IOException;
import java.util.UUID;

import io.netty.buffer.ByteBuf;
import pt.unl.fct.di.novasys.babel.generic.ProtoMessage;
import pt.unl.fct.di.novasys.network.ISerializer;
import pt.unl.fct.di.novasys.network.data.Host;

/**
 * The wire message the flood broadcast sends peer-to-peer. It carries:
 * <ul>
 *   <li>{@code mid} — a unique id, so a node that has already seen this message
 *       can drop the duplicate (otherwise a flood loops forever);</li>
 *   <li>{@code sender} — the original author (for display);</li>
 *   <li>{@code toDeliver} — the protocol id to hand the payload to on delivery;</li>
 *   <li>{@code content} — the opaque application payload.</li>
 * </ul>
 */
public class BroadcastMessage extends ProtoMessage {

    // Broadcast owns protocol id 200; its messages start at 201.
    public static final short MSG_ID = 201;

    private final UUID mid;
    private final Host sender;
    private final short toDeliver;
    private final byte[] content;

    public BroadcastMessage(UUID mid, Host sender, short toDeliver, byte[] content) {
        super(MSG_ID);
        this.mid = mid;
        this.sender = sender;
        this.toDeliver = toDeliver;
        this.content = content;
    }

    public UUID getMid() {
        return mid;
    }

    public Host getSender() {
        return sender;
    }

    public short getToDeliver() {
        return toDeliver;
    }

    public byte[] getContent() {
        return content;
    }

    @Override
    public String toString() {
        return "BroadcastMessage{mid=" + mid + '}';
    }

    public static final ISerializer<BroadcastMessage> serializer = new ISerializer<>() {
        @Override
        public void serialize(BroadcastMessage m, ByteBuf out) throws IOException {
            // A UUID is two longs.
            out.writeLong(m.mid.getMostSignificantBits());
            out.writeLong(m.mid.getLeastSignificantBits());
            Host.serializer.serialize(m.sender, out);
            out.writeShort(m.toDeliver);
            out.writeInt(m.content.length);
            if (m.content.length > 0) {
                out.writeBytes(m.content);
            }
        }

        @Override
        public BroadcastMessage deserialize(ByteBuf in) throws IOException {
            long msb = in.readLong();
            long lsb = in.readLong();
            UUID mid = new UUID(msb, lsb);
            Host sender = Host.serializer.deserialize(in);
            short toDeliver = in.readShort();
            int size = in.readInt();
            byte[] content = new byte[size];
            if (size > 0) {
                in.readBytes(content);
            }
            return new BroadcastMessage(mid, sender, toDeliver, content);
        }
    };
}
