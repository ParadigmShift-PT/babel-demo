package protocols.apps.chat;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import protocols.apps.chat.messages.ChatDirectMessage;
import protocols.apps.chat.ui.Console;
import pt.unl.fct.di.novasys.babel.core.GenericProtocol;
import pt.unl.fct.di.novasys.babel.exceptions.HandlerRegistrationException;
import pt.unl.fct.di.novasys.babel.generic.ProtoMessage;
import pt.unl.fct.di.novasys.babel.protocols.dissemination.notifications.BroadcastDelivery;
import pt.unl.fct.di.novasys.babel.protocols.dissemination.requests.BroadcastRequest;
import pt.unl.fct.di.novasys.babel.protocols.general.notifications.ChannelAvailableNotification;
import pt.unl.fct.di.novasys.babel.protocols.membership.notifications.NeighborDown;
import pt.unl.fct.di.novasys.babel.protocols.membership.notifications.NeighborUp;
import pt.unl.fct.di.novasys.network.data.Host;

/**
 * The chat application — the top of the stack, and the only protocol a user sees.
 * It ties the lower layers together:
 * <ul>
 *   <li><b>Global messages</b> go out via a {@link BroadcastRequest} to the
 *       broadcast protocol, and come back (to everyone, including us) as
 *       {@link BroadcastDelivery} notifications. Both are the shared
 *       {@code babel-protocols-common} dissemination types.</li>
 *   <li><b>Private messages</b> ({@code /msg}) are sent point-to-point with
 *       {@code sendMessage} over the membership's shared channel.</li>
 *   <li><b>Presence</b> — who's in the chat — is a {@code Host → nickname} roster.
 *       When the membership reports a {@link NeighborUp}, we send that peer a
 *       direct {@link ChatDirectMessage.Kind#HELLO} carrying our nickname; the
 *       peer does the same, so both learn each other. {@link NeighborDown} (a
 *       crash) and a broadcast {@code LEAVE} (a graceful {@code /quit}) remove
 *       people again.</li>
 * </ul>
 *
 * <h2>Threading note</h2>
 * The console runs on its own thread (so it can block waiting for you to type).
 * When you enter a line, {@link #handleInput} runs on that thread and calls
 * {@code sendRequest}/{@code sendMessage} — these enqueue work onto Babel's event
 * loop, so they're safe to call from here. The roster is a
 * {@link ConcurrentHashMap} because the console thread reads it (for {@code /who}
 * and {@code /msg}) while the event loop writes it. Printing is via JLine, which
 * is itself safe to call from any thread.
 */
public class ChatApp extends GenericProtocol {

    private static final Logger logger = LogManager.getLogger(ChatApp.class);

    public static final short PROTO_ID = 300;
    public static final String PROTO_NAME = "ChatApp";

    private final Host myself;
    private final String nick;
    private final short broadcastProtoId;

    private final Console console;
    private final Map<Host, String> roster = new ConcurrentHashMap<>(); // Host → nickname
    private final List<Host> neighbours = new ArrayList<>();            // event-loop only

    private int channelId;
    private boolean channelReady = false;

    public ChatApp(Properties props, Host myself, String nick, short broadcastProtoId)
            throws HandlerRegistrationException, IOException {
        super(PROTO_NAME, PROTO_ID);
        this.myself = myself;
        this.nick = nick;
        this.broadcastProtoId = broadcastProtoId;
        this.console = new Console("[" + nick + "] ");

        // Global deliveries + membership changes + the shared channel.
        subscribeNotification(BroadcastDelivery.NOTIFICATION_ID, this::uponDeliver);
        subscribeNotification(NeighborUp.NOTIFICATION_ID, this::uponNeighborUp);
        subscribeNotification(NeighborDown.NOTIFICATION_ID, this::uponNeighborDown);
        subscribeNotification(ChannelAvailableNotification.NOTIFICATION_ID, this::uponChannelAvailable);
        // Direct-message handler is registered once we have the shared channel.
    }

    @Override
    public void init(Properties props) {
        console.printAbove("*** babel-demo — connected as " + nick + ". Type /help for commands.");
        // Start reading the user's input. handleInput runs on the console thread.
        console.start(this::handleInput, this::shutdown);
    }

    /* ─────────── Attach our direct-message handler to the shared channel ─────────── */

    private void uponChannelAvailable(ChannelAvailableNotification notification, short sourceProto) {
        this.channelId = notification.getChannelID();
        registerSharedChannel(channelId);
        registerMessageSerializer(channelId, ChatDirectMessage.MSG_ID, ChatDirectMessage.serializer);
        try {
            registerMessageHandler(channelId, ChatDirectMessage.MSG_ID, this::uponDirect, this::uponMsgFail);
        } catch (HandlerRegistrationException e) {
            logger.error("Failed to register chat direct-message handler", e);
            System.exit(1);
        }
        channelReady = true;
        // Greet any neighbours we already knew before the channel was ready.
        for (Host h : neighbours) {
            sendHello(h);
        }
    }

    /* ───────────────────────── Membership notifications ───────────────────────── */

    private void uponNeighborUp(NeighborUp notification, short sourceProto) {
        Host h = notification.getPeer();
        if (!neighbours.contains(h)) {
            neighbours.add(h);
        }
        // Presence handshake: tell the new neighbour who we are.
        if (channelReady) {
            sendHello(h);
        }
    }

    private void uponNeighborDown(NeighborDown notification, short sourceProto) {
        Host h = notification.getPeer();
        neighbours.remove(h);
        String goneNick = roster.remove(h); // null if we never knew them / already removed
        if (goneNick != null) {
            console.printAbove("*** " + goneNick + " has left the chat");
        }
    }

    /* ─────────────────────── Global deliveries (broadcast) ─────────────────────── */

    private void uponDeliver(BroadcastDelivery notification, short sourceProto) {
        ChatPayload payload = ChatPayload.decode(notification.getPayload());
        Host sender = notification.getSender();
        switch (payload.getKind()) {
            case TEXT -> console.printAbove(ts() + " <" + payload.getNick() + "> " + payload.getText());
            case LEAVE -> {
                if (!sender.equals(myself)) {
                    // Print only if we actually had them (dedups with NeighborDown).
                    if (roster.remove(sender) != null) {
                        console.printAbove("*** " + payload.getNick() + " has left the chat");
                    }
                }
            }
        }
    }

    /* ───────────────────────── Direct messages (point-to-point) ──────────────────── */

    private void uponDirect(ChatDirectMessage msg, Host from, short sourceProto, int channelId) {
        switch (msg.getKind()) {
            case HELLO -> {
                // First time we hear this peer's nick → they've joined our view.
                if (roster.put(from, msg.getFromNick()) == null) {
                    console.printAbove("*** " + msg.getFromNick() + " has joined the chat");
                }
            }
            case PRIVATE -> console.printAbove(ts() + " <- (" + msg.getFromNick() + ") " + msg.getText());
        }
    }

    private void uponMsgFail(ProtoMessage msg, Host host, short destProto, Throwable throwable, int channelId) {
        logger.error("Direct message {} to {} failed: {}", msg, host, throwable);
    }

    /* ───────────────────────────── Console input ───────────────────────────────── */

    private void handleInput(String line) {
        if (!line.startsWith("/")) {
            broadcastText(line); // our own message comes back via uponDeliver and renders once
            return;
        }
        // It's a command.
        String[] parts = line.split("\\s+", 3);
        String cmd = parts[0].toLowerCase();
        switch (cmd) {
            case "/help" -> printHelp();
            case "/who", "/names" -> {
                List<String> names = new ArrayList<>(roster.values());
                names.sort(String::compareToIgnoreCase);
                console.printAbove("*** in the chat: " + (names.isEmpty() ? "(just you)" : String.join(", ", names))
                        + " — and you (" + nick + ")");
            }
            case "/msg" -> {
                if (parts.length < 3) {
                    console.printAbove("*** usage: /msg <nick> <message>");
                    return;
                }
                String targetNick = parts[1];
                String text = parts[2];
                Host target = findHost(targetNick);
                if (target == null) {
                    console.printAbove("*** no user named '" + targetNick + "' (try /who)");
                } else {
                    sendMessage(new ChatDirectMessage(ChatDirectMessage.Kind.PRIVATE, nick, text), target);
                    console.printAbove(ts() + " -> (" + targetNick + ") " + text);
                }
            }
            case "/quit" -> {
                console.printAbove("*** leaving…");
                shutdown();
            }
            default -> console.printAbove("*** unknown command '" + cmd + "' — try /help");
        }
    }

    private void printHelp() {
        console.printAbove("*** commands:");
        console.printAbove("***   <text>               send to everyone (global channel)");
        console.printAbove("***   /msg <nick> <text>   private message to one user");
        console.printAbove("***   /who                 who's in the chat");
        console.printAbove("***   /help                this help");
        console.printAbove("***   /quit                leave the chat");
    }

    /* ──────────────────────────────── Senders ──────────────────────────────────── */
    // Two distinct Babel send primitives are used here:
    //  • sendRequest(req, protoId) — hand a request to ANOTHER protocol in this
    //    process (the broadcast). That protocol then puts it on the network.
    //  • sendMessage(msg, host)    — send a message straight to ONE peer over the
    //    shared channel (point-to-point). Used for HELLO and private /msg.

    // Global channel: encode the text and ask the broadcast protocol to flood it.
    private void broadcastText(String text) {
        byte[] payload = new ChatPayload(ChatPayload.Kind.TEXT, nick, text).encode();
        sendRequest(new BroadcastRequest(myself, payload, PROTO_ID), broadcastProtoId);
    }

    // Graceful departure: broadcast a LEAVE so everyone drops us from their roster.
    private void broadcastLeave() {
        byte[] payload = new ChatPayload(ChatPayload.Kind.LEAVE, nick, "").encode();
        sendRequest(new BroadcastRequest(myself, payload, PROTO_ID), broadcastProtoId);
    }

    // Presence handshake: send our nickname directly to one peer (point-to-point).
    private void sendHello(Host peer) {
        sendMessage(new ChatDirectMessage(ChatDirectMessage.Kind.HELLO, nick, ""), peer);
    }

    /* ──────────────────────────────── Helpers ──────────────────────────────────── */

    private Host findHost(String targetNick) {
        for (Map.Entry<Host, String> e : roster.entrySet()) {
            if (e.getValue().equalsIgnoreCase(targetNick)) {
                return e.getKey();
            }
        }
        return null;
    }

    private static String ts() {
        return "[" + new SimpleDateFormat("HH:mm").format(new Date()) + "]";
    }

    /** Announce departure, give the message a moment to flush, then exit. */
    private void shutdown() {
        try {
            broadcastLeave();
            Thread.sleep(300); // best-effort: let the LEAVE go out before we exit
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        console.stop();
        System.exit(0);
    }
}
