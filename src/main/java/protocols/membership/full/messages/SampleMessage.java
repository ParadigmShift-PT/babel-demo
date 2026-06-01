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
 * <h2>Serialization</h2>
 * Every Babel message needs a serializer: the framework calls it to turn the
 * object into bytes on the wire and back. The pattern is always the same — write
 * each field, read it back in the same order. Here we write the number of hosts,
 * then each {@link Host} using Babel's built-in {@code Host.serializer}.
 */
public class SampleMessage extends ProtoMessage {

    // Membership owns protocol id 100; its messages start at 101.
    public static final short MSG_ID = 101;

    private final Set<Host> sample;

    public SampleMessage(Set<Host> sample) {
        super(MSG_ID);
        this.sample = sample;
    }

    public Set<Host> getSample() {
        return sample;
    }

    @Override
    public String toString() {
        return "SampleMessage{sample=" + sample + '}';
    }

    public static final ISerializer<SampleMessage> serializer = new ISerializer<>() {
        @Override
        public void serialize(SampleMessage msg, ByteBuf out) throws IOException {
            out.writeInt(msg.sample.size());
            for (Host h : msg.sample) {
                Host.serializer.serialize(h, out);
            }
        }

        @Override
        public SampleMessage deserialize(ByteBuf in) throws IOException {
            int size = in.readInt();
            Set<Host> sample = new HashSet<>(size, 1);
            for (int i = 0; i < size; i++) {
                sample.add(Host.serializer.deserialize(in));
            }
            return new SampleMessage(sample);
        }
    };
}
