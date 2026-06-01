package protocols.membership.full.messages;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import io.netty.buffer.ByteBuf;
import pt.unl.fct.di.novasys.babel.generic.ProtoMessage;
import pt.unl.fct.di.novasys.network.ISerializer;
import pt.unl.fct.di.novasys.network.data.Host;

/**
 * The one message the membership protocol exchanges: a periodic "here are some
 * peers I know" sample. A node sends it to a random current neighbour; the
 * receiver opens connections to any peers in the sample it doesn't already know.
 * Repeated over time, this gossip lets every node eventually learn about (and
 * connect to) every other node — a <em>full</em> membership.
 *
 * <h2>What a Babel message is</h2>
 * A {@code ProtoMessage} is something a protocol sends over a channel to a peer
 * (via {@code sendMessage}). It is identified by a numeric id ({@link #MSG_ID})
 * that the protocol registers a serializer and a handler for. When a peer's bytes
 * arrive, Babel uses the id to pick the serializer (to rebuild the object) and
 * the handler (to process it).
 *
 * <h2>Serialization</h2>
 * Every Babel message needs an {@link ISerializer}: the framework calls it to
 * turn the object into bytes on the wire and back. The rule is simply to write
 * each field and read it back <em>in the same order</em>. Here we write the
 * number of hosts, then each {@link Host} using Babel's built-in
 * {@code Host.serializer} (so we never hand-encode an address/port ourselves).
 */
public class MembershipSampleMessage extends ProtoMessage {

    // Membership owns protocol id 100; its messages start at 101.
    public static final short MSG_ID = 101;

    private final Set<Host> sample;

    public MembershipSampleMessage(Set<Host> sample) {
        super(MSG_ID);
        this.sample = sample;
    }

    public Set<Host> getSample() {
        return sample;
    }

    @Override
    public String toString() {
        return "MembershipSampleMessage{sample=" + sample + '}';
    }

    public static final ISerializer<MembershipSampleMessage> serializer = new ISerializer<>() {
        @Override
        public void serialize(MembershipSampleMessage msg, ByteBuf out) throws IOException {
            // Write the count, then each host. The deserializer must read in this
            // exact order.
            out.writeInt(msg.sample.size());
            for (Host h : msg.sample) {
                Host.serializer.serialize(h, out);
            }
        }

        @Override
        public MembershipSampleMessage deserialize(ByteBuf in) throws IOException {
            int size = in.readInt();
            Set<Host> sample = new HashSet<>(size, 1);
            for (int i = 0; i < size; i++) {
                sample.add(Host.serializer.deserialize(in));
            }
            return new MembershipSampleMessage(sample);
        }
    };
}
