import java.net.InetAddress;
import java.net.NetworkInterface;
import java.security.InvalidParameterException;
import java.util.Properties;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import protocols.apps.chat.ChatApp;
import protocols.broadcast.flood.FloodBroadcast;
import protocols.membership.full.GossipBasedFullMembership;
import pt.unl.fct.di.novasys.babel.core.Babel;
import pt.unl.fct.di.novasys.babel.core.protocols.discovery.MulticastDiscoveryProtocol;
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
 * [babel.interface=<nic>] [babel.address=<ip>] [membership.contact=<host>:<port>]}
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
    // (e.g. GossipBasedFullMembership.PAR_CONTACT). The process-wide bind keys
    // (babel.address / babel.interface / babel.port) are owned by babel-core
    // (Babel.PAR_DEFAULT_ADDRESS / *_INTERFACE / *_PORT) — we reference those rather
    // than re-declaring the literals. Address/interface resolution lives in
    // InterfaceToIp.

    /**
     * Your chat nickname — how you appear to everyone else. No default: if it is
     * omitted, the launcher prompts for one interactively at startup (when run on
     * a terminal). It must be supplied as {@code nick=<name>} only when there is
     * no interactive console (e.g. piped input).
     */
    public static final String PAR_NICK = "nick";

    /**
     * Default value for the TCP port ({@link Babel#PAR_DEFAULT_PORT}, i.e.
     * {@code babel.port}) this node binds and listens on. Run several nodes on one
     * machine by giving each a distinct port; across machines the same port is
     * fine. babel-core owns the key but defines no default value, so the demo
     * supplies one here.
     */
    public static final String PAR_DEFAULT_BABEL_PORT = "6000";

    // The bind address (`babel.address`, Babel.PAR_DEFAULT_ADDRESS) has no static
    // default: InterfaceToIp.resolveBindAddress derives a reachable IP (from an
    // explicit babel.address, else babel.interface, else auto-detection) and never
    // falls back to loopback silently — see that class.

    public static void main(String[] args) throws Exception {

        // Give each node its own log file (so two nodes on one machine don't write
        // to the same file). This must happen BEFORE any logger is created, since
        // log4j reads the filename when it first initialises.
        String port = argValue(args, Babel.PAR_DEFAULT_PORT, PAR_DEFAULT_BABEL_PORT);
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
            System.err.println("                               (e.g. en0 / eth0); auto-detected if omitted");
            System.err.println("  babel.address=<ip>           bind address override (e.g. 127.0.0.1 to run");
            System.err.println("                               several nodes on one disconnected machine)");
            System.err.println("  membership.contact=<h>:<p>   seed peer if multicast discovery is");
            System.err.println("                               unavailable; 'none' = I am the first node");
            System.exit(1);
        }

        // Resolve a reachable bind address into babel.address: an explicit
        // babel.address wins, else resolve babel.interface, else auto-detect the
        // first non-loopback interface. We never default to loopback silently —
        // discovery and peers need a reachable address. If nothing qualifies, tell
        // the operator to pass babel.interface or babel.address and exit cleanly.
        String addressSource;
        try {
            addressSource = InterfaceToIp.resolveBindAddress(props);
        } catch (InvalidParameterException e) {
            System.err.println("babel-demo — cannot determine a bind address.\n");
            System.err.println(e.getMessage());
            System.exit(1);
            return; // unreachable; keeps the compiler happy about addressSource
        }

        // Work out our own address/port. A Host is an (address, port) pair that
        // Babel uses everywhere; it has value equality and a built-in serializer.
        String bindAddress = props.getProperty(Babel.PAR_DEFAULT_ADDRESS);
        int bindPort = Integer.parseInt(props.getProperty(Babel.PAR_DEFAULT_PORT, PAR_DEFAULT_BABEL_PORT));
        Host myself = new Host(InetAddress.getByName(bindAddress), bindPort);

        logger.info("babel-demo starting — nick='{}', host={}", nick, myself);
        // Full interface inventory goes to the log file — handy when the auto-detected
        // interface turns out to be the wrong one.
        logger.info(InterfaceToIp.describeInterfaces());

        // Print a one-screen summary (to stdout, before the chat console takes over)
        // so it's obvious which interface/IP we bound and how we'll find peers.
        printStartupBanner(props, nick, myself, addressSource);

        // Build the three protocols.
        GossipBasedFullMembership membership = new GossipBasedFullMembership(TCPChannel.NAME, props, myself);
        FloodBroadcast broadcast = new FloodBroadcast(props, myself);
        ChatApp chat = new ChatApp(props, myself, nick, FloodBroadcast.PROTOCOL_ID);

        // Register every protocol, then init each (the chat's init starts the
        // console), then start the runtime. Because babel.discovery is set (see
        // babel_config.properties), Babel loads the multicast discovery protocol and
        // wires it to our DiscoverableProtocol membership. When membership.contact is
        // absent (needsDiscovery()==true) the node actively probes the LAN and Babel
        // calls its start() once a peer is found. membership.contact=none makes it a
        // first node: it does NOT probe, but it still replies to others' probes (so
        // joiners can discover it) — i.e. start at least one node without 'none'.
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
     * Print a concise startup summary to stdout (before the chat console takes
     * over): the nickname, the address/port we bound and how it was chosen, the
     * discovery configuration, and how we'll bootstrap. This makes it obvious — at
     * a glance — which interface/IP a node is using and whether auto-discovery is
     * actually engaged (the common "why don't they find each other?" confusion is a
     * node in {@code membership.contact=none} mode, which opts out of discovery).
     */
    private static void printStartupBanner(Properties props, String nick, Host myself, String addressSource) {
        // Resolve the actual interface the bound address sits on, so the operator can
        // see — and sanity-check — exactly which NIC this process is using. (A wrong
        // auto-selection is the usual reason two nodes never find each other.)
        String iface;
        try {
            NetworkInterface nif = NetworkInterface.getByInetAddress(myself.getAddress());
            iface = (nif != null) ? nif.getName() : "?";
        } catch (Exception e) {
            iface = "?";
        }

        StringBuilder b = new StringBuilder(System.lineSeparator());
        b.append("  babel-demo — node '").append(nick).append('\'').append(System.lineSeparator());
        b.append("  network     : ").append(iface).append("  →  ").append(myself.getAddress().getHostAddress())
                .append("   (").append(addressSource).append(')').append(System.lineSeparator());
        b.append("  listen      : ").append(myself.getAddress().getHostAddress())
                .append(':').append(myself.getPort()).append("  (TCP chat)").append(System.lineSeparator());

        if (props.getProperty(Babel.PAR_DISCOVERY_PROTOCOL) != null) {
            String group = props.getProperty(MulticastDiscoveryProtocol.PAR_DISCOVERY_MULTICAST_ADDRESS,
                    MulticastDiscoveryProtocol.MULTICAST_ADDRESS);
            String mport = props.getProperty(MulticastDiscoveryProtocol.PAR_DISCOVERY_MULTICAST_PORT,
                    Integer.toString(MulticastDiscoveryProtocol.DEFAULT_MULTICAST_PORT));
            String uport = props.getProperty(MulticastDiscoveryProtocol.PAR_DISCOVERY_UNICAST_PORT,
                    Integer.toString(MulticastDiscoveryProtocol.DEFAULT_UNICAST_PORT));
            b.append("  discovery   : multicast ").append(group).append(':').append(mport)
                    .append("  ·  unicast :").append(uport).append(System.lineSeparator());
        } else {
            b.append("  discovery   : disabled (no '").append(Babel.PAR_DISCOVERY_PROTOCOL).append("')")
                    .append(System.lineSeparator());
        }

        String contact = props.getProperty(GossipBasedFullMembership.PAR_CONTACT);
        String bootstrap;
        if (contact == null || contact.isBlank()) {
            bootstrap = "auto-discovery — I announce + probe the LAN and connect to whoever answers";
        } else if (contact.trim().equalsIgnoreCase("none")) {
            bootstrap = "first node (membership.contact=none) — I don't probe, but I reply to others' probes";
        } else {
            bootstrap = "seed from contact " + contact.trim();
        }
        b.append("  bootstrap   : ").append(bootstrap).append(System.lineSeparator());
        System.out.println(b);
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
