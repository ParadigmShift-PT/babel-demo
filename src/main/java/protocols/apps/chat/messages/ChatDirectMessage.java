package protocols.apps.chat.messages;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import io.netty.buffer.ByteBuf;
import pt.unl.fct.di.novasys.babel.generic.ProtoMessage;
import pt.unl.fct.di.novasys.network.ISerializer;

/**
 * A chat message sent <b>directly</b> to one peer (not broadcast). The chat app
 * sends these over the membership's shared channel via {@code sendMessage(msg,
 * targetHost)}. Two kinds:
 * <ul>
 *   <li>{@link Kind#HELLO} — the presence handshake: "hi, I'm &lt;nick&gt;",
 *       sent to a peer when we become neighbours so each side learns the other's
 *       nickname (a {@code Host} alone isn't human-friendly);</li>
 *   <li>{@link Kind#PRIVATE} — a private message typed with {@code /msg}.</li>
 * </ul>
 *
 * <p>Direct messaging works to any member because membership is <em>full</em> —
 * every participant is a directly-connected neighbour.
 */
public class ChatDirectMessage extends ProtoMessage {

    // Chat app owns protocol id 300; its messages start at 301.
    public static final short MSG_ID = 301;

    public enum Kind { HELLO, PRIVATE }

    private final Kind kind;
    private final String fromNick; // the sender's nickname
    private final String text;     // empty for HELLO

    public ChatDirectMessage(Kind kind, String fromNick, String text) {
        super(MSG_ID);
        this.kind = kind;
        this.fromNick = fromNick;
        this.text = text == null ? "" : text;
    }

    public Kind getKind() { return kind; }
    public String getFromNick() { return fromNick; }
    public String getText() { return text; }

    // Netty's ByteBuf has no writeUTF, so we length-prefix UTF-8 bytes ourselves.
    private static void writeString(String s, ByteBuf out) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        out.writeInt(b.length);
        out.writeBytes(b);
    }

    private static String readString(ByteBuf in) {
        int n = in.readInt();
        byte[] b = new byte[n];
        in.readBytes(b);
        return new String(b, StandardCharsets.UTF_8);
    }

    public static final ISerializer<ChatDirectMessage> serializer = new ISerializer<>() {
        @Override
        public void serialize(ChatDirectMessage m, ByteBuf out) throws IOException {
            out.writeByte(m.kind.ordinal());
            writeString(m.fromNick, out);
            writeString(m.text, out);
        }

        @Override
        public ChatDirectMessage deserialize(ByteBuf in) throws IOException {
            Kind kind = Kind.values()[in.readByte()];
            String fromNick = readString(in);
            String text = readString(in);
            return new ChatDirectMessage(kind, fromNick, text);
        }
    };
}
