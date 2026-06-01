package protocols.apps.chat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * The chat's own little "application protocol" that rides <em>inside</em> the
 * broadcast's opaque {@code byte[]} payload. The broadcast layer doesn't know or
 * care what these bytes mean — that's the whole point of layering: the broadcast
 * moves bytes; the application decides what they are.
 *
 * <p>Two things travel by broadcast (i.e. to everyone):
 * <ul>
 *   <li>{@link Kind#TEXT} — a message to the global channel ({@code nick} + {@code text});</li>
 *   <li>{@link Kind#LEAVE} — "I'm leaving" on a graceful {@code /quit} ({@code nick}).</li>
 * </ul>
 * Arrival and private messages go point-to-point instead — see {@code ChatDirectMessage}.
 *
 * <p>We hand-roll a tiny encoding with {@link DataOutputStream} (1 byte kind, then
 * two length-prefixed UTF strings) so the wire format is obvious to a reader.
 */
public final class ChatPayload {

    public enum Kind { TEXT, LEAVE }

    private final Kind kind;
    private final String nick;
    private final String text; // unused for LEAVE

    public ChatPayload(Kind kind, String nick, String text) {
        this.kind = kind;
        this.nick = nick;
        this.text = text == null ? "" : text;
    }

    public Kind getKind() { return kind; }
    public String getNick() { return nick; }
    public String getText() { return text; }

    /** Serialize to the bytes carried inside a broadcast message. */
    public byte[] encode() {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(baos)) {
            out.writeByte(kind.ordinal());
            out.writeUTF(nick);
            out.writeUTF(text);
        } catch (IOException e) {
            // Writing to an in-memory buffer cannot fail in practice.
            throw new UncheckedIOException(e);
        }
        return baos.toByteArray();
    }

    /** Parse the bytes delivered by the broadcast layer back into a payload. */
    public static ChatPayload decode(byte[] bytes) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            Kind kind = Kind.values()[in.readByte()];
            String nick = in.readUTF();
            String text = in.readUTF();
            return new ChatPayload(kind, nick, text);
        } catch (IOException | ArrayIndexOutOfBoundsException e) {
            throw new UncheckedIOException(new IOException("Malformed chat payload", e));
        }
    }
}
