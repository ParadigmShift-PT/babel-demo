import java.net.InetAddress;
import java.util.Properties;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import protocols.apps.chat.ChatApp;
import protocols.broadcast.flood.FloodBroadcast;
import protocols.membership.full.GossipBasedFullMembership;
import pt.unl.fct.di.novasys.babel.core.Babel;
import pt.unl.fct.di.novasys.channel.tcp.TCPChannel;
import pt.unl.fct.di.novasys.network.data.Host;
import utils.InterfaceToIp;

/**
 * Entry point for the babel-demo peer-to-peer chat.
 *
 * <p>The demo composes three Babel protocols and lets the runtime wire them
 * together through asynchronous events:
 * <ol>
 *   <li>{@link GossipBasedFullMembership} — keeps us connected to the other nodes
 *       and finds them automatically (it's a {@code DiscoverableProtocol});</li>
 *   <li>{@link FloodBroadcast} — disseminates messages to all neighbours;</li>
 *   <li>{@link ChatApp} — the interactive mIRC-style chat on top.</li>
 * </ol>
 *
 * <p>Launch: {@code java -jar babel-demo.jar nick=<name> [babel.port=<port>]
 * [interface=<nic>] [contact=<host>:<port>]}
 */
public class Main {

    // Point log4j at our bundled configuration. Logs go to a FILE (not the
    // console) so they never corrupt the JLine chat UI on stdout — see log4j2.xml.
    static {
        System.setProperty("log4j.configurationFile", "log4j2.xml");
    }

    // Default Babel configuration file (overridable with the "-config" launch arg).
    private static final String DEFAULT_CONF = "babel_config.properties";

    // ── Configuration parameters read by the launcher ───────────────────────────
    // Convention: a PAR_* key constant per parameter (plus a PAR_DEFAULT_* where one
    // applies); the Javadoc on each is the user-facing description. Parameters
    // consumed by the protocols are declared on those protocols' classes instead
    // (e.g. GossipBasedFullMembership.PAR_CONTACT, InterfaceToIp.PAR_INTERFACE).

    /**
     * Your chat nickname — how you appear to everyone else. No default: if it is
     * omitted, the launcher prompts for one interactively at startup (when run on
     * a terminal). It must be supplied as {@code nick=<name>} only when there is
     * no interactive console (e.g. piped input).
     */
    public static final String PAR_NICK = "nick";

    /**
     * TCP port this node binds and listens on. Run several nodes on one machine by
     * giving each a distinct port; across machines the same port is fine.
     */
    public static final String PAR_BABEL_PORT = "babel.port";
    /** Default for {@link #PAR_BABEL_PORT}. */
    public static final String PAR_DEFAULT_BABEL_PORT = "6000";

    // The bind address key (`babel.address`) is owned by InterfaceToIp.PAR_ADDRESS:
    // normally left unset and derived from `babel.interface`, or set explicitly to
    // override. Here we only hold its default (loopback — fine for several nodes on
    // one machine).
    /** Default for {@link InterfaceToIp#PAR_ADDRESS}: loopback. */
    public static final String PAR_DEFAULT_BABEL_ADDRESS = "127.0.0.1";

    public static void main(String[] args) throws Exception {

        // Give each node its own log file (so two nodes on one machine don't write
        // to the same file). This must happen BEFORE any logger is created, since
        // log4j reads the filename when it first initialises.
        String port = argValue(args, PAR_BABEL_PORT, PAR_DEFAULT_BABEL_PORT);
        System.setProperty("babeldemo.logfile", "babel-demo-" + port + ".log");

        Logger logger = LogManager.getLogger(Main.class);

        // The (singleton) Babel runtime.
        Babel babel = Babel.getInstance();

        // Merge the configuration file with any key=value launch arguments.
        Properties props = Babel.loadConfig(args, DEFAULT_CONF);

        // The nickname is how you appear in the chat. If it wasn't passed as
        // nick=<name>, ask for it interactively (when there's a terminal to ask on).
        String nick = props.getProperty(PAR_NICK);
        if (nick == null || nick.isBlank()) {
            nick = promptForNick();
        }
        if (nick == null || nick.isBlank()) {
            // No nickname, and no interactive console to prompt on (e.g. piped
            // input). Show usage and exit.
            System.err.println("babel-demo — a peer-to-peer chat built on Babel\n");
            System.err.println("Usage: java -jar babel-demo.jar [nick=<name>] [options]\n");
            System.err.println("  nick=<name>                  your chat nickname (prompted for if omitted)");
            System.err.println("  babel.port=<port>            TCP port for this node (use a distinct");
            System.err.println("                               port per instance on the same machine)");
            System.err.println("  babel.interface=<nic>        network interface to bind/announce on");
            System.err.println("                               (e.g. en0 / eth0); needed for LAN discovery");
            System.err.println("  membership.contact=<h>:<p>   seed peer if multicast discovery is");
            System.err.println("                               unavailable; 'none' = I am the first node");
            System.exit(1);
        }

        // If babel.interface was given, resolve it to a concrete IPv4 address
        // (stored under babel.address) so discovery announces something reachable
        // rather than localhost.
        InterfaceToIp.addInterfaceIp(props);

        // Work out our own address/port. A Host is an (address, port) pair that
        // Babel uses everywhere; it has value equality and a built-in serializer.
        String bindAddress = props.getProperty(InterfaceToIp.PAR_ADDRESS, PAR_DEFAULT_BABEL_ADDRESS);
        int bindPort = Integer.parseInt(props.getProperty(PAR_BABEL_PORT, PAR_DEFAULT_BABEL_PORT));
        Host myself = new Host(InetAddress.getByName(bindAddress), bindPort);

        logger.info("babel-demo starting — nick='{}', host={}", nick, myself);

        // Build the three protocols.
        GossipBasedFullMembership membership = new GossipBasedFullMembership(TCPChannel.NAME, props, myself);
        FloodBroadcast broadcast = new FloodBroadcast(props, myself);
        ChatApp chat = new ChatApp(props, myself, nick, FloodBroadcast.PROTOCOL_ID);

        // Register every protocol, then init each (the chat's init starts the
        // console), then start the runtime. Babel automatically drives multicast
        // discovery for the DiscoverableProtocol membership and calls its start()
        // once it has a contact (or is the first node).
        babel.registerProtocol(membership);
        babel.registerProtocol(broadcast);
        babel.registerProtocol(chat);
        membership.init(props);
        broadcast.init(props);
        chat.init(props);

        babel.start();

        // No more stdout from here — the chat console owns the terminal now.
        logger.info("babel-demo up as '{}' ({})", nick, myself);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> logger.info("babel-demo shutting down")));
    }

    /**
     * Ask the operator for a nickname on the terminal, re-prompting until they
     * enter a non-blank, space-free name. Returns {@code null} if there is no
     * interactive console (piped/redirected input) or the input stream ends — the
     * caller then falls back to printing usage.
     */
    private static String promptForNick() {
        java.io.Console console = System.console();
        if (console == null) {
            return null; // not interactive — can't prompt
        }
        while (true) {
            String line = console.readLine("Choose a nickname: ");
            if (line == null) {
                return null; // end of input (Ctrl-D)
            }
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.contains(" ")) {
                console.printf("Nicknames can't contain spaces — try again.%n");
                continue;
            }
            return line;
        }
    }

    /** Tiny helper: find {@code key=value} in the launch args, else return a default. */
    private static String argValue(String[] args, String key, String def) {
        String prefix = key + "=";
        for (String a : args) {
            if (a.startsWith(prefix)) {
                return a.substring(prefix.length());
            }
        }
        return def;
    }
}
