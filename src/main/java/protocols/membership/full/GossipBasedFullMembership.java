package protocols.membership.full;

import java.io.IOException;
import java.net.InetAddress;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Properties;
import java.util.Random;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import protocols.membership.common.notifications.ChannelCreated;
import protocols.membership.common.notifications.NeighbourDown;
import protocols.membership.common.notifications.NeighbourUp;
import protocols.membership.full.messages.SampleMessage;
import protocols.membership.full.timers.InfoTimer;
import protocols.membership.full.timers.SampleTimer;
import pt.unl.fct.di.novasys.babel.core.DiscoverableProtocol;
import pt.unl.fct.di.novasys.babel.exceptions.HandlerRegistrationException;
import pt.unl.fct.di.novasys.babel.generic.ProtoMessage;
import pt.unl.fct.di.novasys.babel.protocols.general.notifications.ChannelAvailableNotification;
import pt.unl.fct.di.novasys.channel.tcp.TCPChannel;
import pt.unl.fct.di.novasys.channel.tcp.events.InConnectionDown;
import pt.unl.fct.di.novasys.channel.tcp.events.InConnectionUp;
import pt.unl.fct.di.novasys.channel.tcp.events.OutConnectionDown;
import pt.unl.fct.di.novasys.channel.tcp.events.OutConnectionFailed;
import pt.unl.fct.di.novasys.channel.tcp.events.OutConnectionUp;
import pt.unl.fct.di.novasys.network.data.Host;

/**
 * Gossip-based <b>full</b> membership: every node tries to stay connected to
 * every other node. It is the foundation the broadcast and chat protocols build
 * on — they never deal with connections themselves; they just listen for the
 * {@link NeighbourUp} / {@link NeighbourDown} notifications this protocol emits.
 *
 * <h2>How membership grows</h2>
 * <ol>
 *   <li>We learn about a first peer — either from auto-discovery (see below) or a
 *       configured {@code contact} — and open a TCP connection to it.</li>
 *   <li>Once connected, the peer is in our membership and we emit
 *       {@link NeighbourUp}.</li>
 *   <li>Periodically we send a random connected peer a {@link SampleMessage}
 *       containing a sample of who we know (plus ourselves). The receiver opens
 *       connections to any peers it didn't already know. Over a few rounds,
 *       everyone learns about (and connects to) everyone.</li>
 * </ol>
 *
 * <h2>Auto-discovery — why this extends {@link DiscoverableProtocol}</h2>
 * Rather than demanding a hard-coded contact, we let Babel's runtime find peers
 * for us. By extending {@link DiscoverableProtocol} and implementing its hooks
 * ({@link #readyToStart()}, {@link #needsDiscovery()}, {@link #addContact(Host)},
 * {@link #getContact()}, {@link #start()}), the runtime's multicast discovery
 * service announces our channel on the LAN and hands us a contact automatically.
 * Where multicast isn't available, the {@code contact} property is the fallback
 * (the same pattern HyParView uses):
 * <ul>
 *   <li>{@code contact=none} → I'm the first node; start immediately.</li>
 *   <li>{@code contact=host:port} → seed directly from that node.</li>
 *   <li>(absent) → wait passively for discovery to provide a contact.</li>
 * </ul>
 *
 * <h2>Threading</h2>
 * Every handler here runs on this protocol's single Babel event-loop thread, so
 * the {@code membership}/{@code pending} sets need no locking.
 */
public class GossipBasedFullMembership extends DiscoverableProtocol {

    private static final Logger logger = LogManager.getLogger(GossipBasedFullMembership.class);

    // Protocol identity (mirrors babel-example so the two read as siblings).
    public static final short PROTOCOL_ID = 100;
    public static final String PROTOCOL_NAME = "FullMembership";

    // ── Configuration keys (override in babel_config.properties or on the CLI) ──
    public static final String PAR_SAMPLE_TIME = "protocol.membership.sampletime";
    public static final String PAR_DEFAULT_SAMPLE_TIME = "2000"; // ms between gossip samples
    public static final String PAR_SAMPLE_SIZE = "protocol.membership.samplesize";
    public static final String PAR_DEFAULT_SAMPLE_SIZE = "6";     // max peers per sample
    /** Bootstrap contact: {@code none} | {@code host:port} | (absent → wait for discovery). */
    public static final String PAR_CONTACT = "contact";
    public static final String PAR_METRICS_INTERVAL = "protocol_metrics_interval";

    private final Host self;            // our own address/port
    private final String channelName;   // channel type used in the discovery announcement
    private final int channelId;        // id of the TCP channel we created
    private final Set<Host> membership; // peers we are connected to
    private final Set<Host> pending;    // peers we are trying to connect to
    private final Random rnd;

    private final int sampleTime;       // gossip period (ms)
    private final int subsetSize;       // max peers per sample
    private final int metricsInterval;  // diagnostics period (ms), <=0 disables

    // DiscoverableProtocol lifecycle flags (touched only on the event-loop thread).
    private boolean isReadyToStart = false;
    private boolean isStarted = false;

    public GossipBasedFullMembership(String channelName, Properties props, Host self)
            throws IOException, HandlerRegistrationException {
        // DiscoverableProtocol takes (name, id, myself) — the last arg lets the
        // discovery runtime know our identity.
        super(PROTOCOL_NAME, PROTOCOL_ID, self);

        this.self = self;
        this.channelName = channelName;
        this.membership = new HashSet<>();
        this.pending = new HashSet<>();
        this.rnd = new Random();

        this.sampleTime = Integer.parseInt(props.getProperty(PAR_SAMPLE_TIME, PAR_DEFAULT_SAMPLE_TIME));
        this.subsetSize = Integer.parseInt(props.getProperty(PAR_SAMPLE_SIZE, PAR_DEFAULT_SAMPLE_SIZE));
        this.metricsInterval = Integer.parseInt(props.getProperty(PAR_METRICS_INTERVAL, "-1"));

        // ── Create the TCP channel we (and, shared, the other protocols) use. ──
        Properties channelProps = new Properties();
        channelProps.setProperty(TCPChannel.ADDRESS_KEY, self.getAddress().getHostAddress());
        channelProps.setProperty(TCPChannel.PORT_KEY, Integer.toString(self.getPort()));
        channelProps.setProperty(TCPChannel.HEARTBEAT_INTERVAL_KEY, "1000");
        channelProps.setProperty(TCPChannel.HEARTBEAT_TOLERANCE_KEY, "3000");
        channelProps.setProperty(TCPChannel.CONNECT_TIMEOUT_KEY, "1000");
        this.channelId = createChannel(channelName, channelProps);
        // Make this the channel sendMessage(...) uses by default.
        setDefaultChannel(channelId);

        // Wire up serializers/handlers/timers/channel-events (the constructor is
        // where Babel protocols register everything they react to).
        registerMessageSerializer(channelId, SampleMessage.MSG_ID, SampleMessage.serializer);
        registerMessageHandler(channelId, SampleMessage.MSG_ID, this::uponSample, this::uponMsgFail);

        registerTimerHandler(SampleTimer.TIMER_ID, this::uponSampleTimer);
        registerTimerHandler(InfoTimer.TIMER_ID, this::uponInfoTimer);

        registerChannelEventHandler(channelId, OutConnectionUp.EVENT_ID, this::uponOutConnectionUp);
        registerChannelEventHandler(channelId, OutConnectionDown.EVENT_ID, this::uponOutConnectionDown);
        registerChannelEventHandler(channelId, OutConnectionFailed.EVENT_ID, this::uponOutConnectionFailed);
        registerChannelEventHandler(channelId, InConnectionUp.EVENT_ID, this::uponInConnectionUp);
        registerChannelEventHandler(channelId, InConnectionDown.EVENT_ID, this::uponInConnectionDown);
    }

    @Override
    public void init(Properties props) throws HandlerRegistrationException, IOException {
        // Decide how we bootstrap. Note we do NOT start gossiping here — that
        // happens in start(), which the discovery runtime calls once we're ready.
        String contact = props.getProperty(PAR_CONTACT);
        if (contact == null || contact.isBlank() || contact.trim().equalsIgnoreCase("none")) {
            // "none"/blank = I'm the first node (ready now). Absent = wait for discovery.
            this.isReadyToStart = (contact != null);
            logger.info("No usable contact — {}", isReadyToStart
                    ? "starting as the first node" : "waiting for multicast discovery to find a peer");
        } else {
            try {
                String[] parts = contact.trim().split(":");
                Host contactHost = new Host(InetAddress.getByName(parts[0]), Integer.parseInt(parts[1]));
                addContact(contactHost); // seed directly; marks us ready
                logger.info("Seeding from configured contact {}", contactHost);
            } catch (Exception e) {
                throw new IOException("Invalid '" + PAR_CONTACT + "' value: '" + contact + "'", e);
            }
        }
    }

    /* ─────────────────────── DiscoverableProtocol hooks ─────────────────────── */

    /** Called by the runtime once we are ready (have a seed, or are the first node). */
    @Override
    public void start() {
        if (isStarted) return;
        // Tell the discovery runtime our channel is live (so it can announce us).
        triggerNotification(new ChannelAvailableNotification(PROTOCOL_ID, PROTOCOL_NAME, channelId, channelName, self));
        // Tell the broadcast/chat protocols which channel to share.
        triggerNotification(new ChannelCreated(channelId));
        // Begin gossiping our membership sample.
        setupPeriodicTimer(new SampleTimer(), sampleTime, sampleTime);
        if (metricsInterval > 0) {
            setupPeriodicTimer(new InfoTimer(), metricsInterval, metricsInterval);
        }
        isStarted = true;
        logger.info("Membership started (channel {})", channelId);
    }

    @Override
    public boolean readyToStart() {
        return isReadyToStart;
    }

    @Override
    public boolean needsDiscovery() {
        return !isReadyToStart;
    }

    /** The runtime (or our own contact bootstrap) calls this with a peer to join through. */
    @Override
    public void addContact(Host host) {
        if (!host.equals(self) && !membership.contains(host) && !pending.contains(host)) {
            pending.add(host);
            openConnection(host); // asynchronous — result arrives as a channel event
        }
        isReadyToStart = true;
    }

    /** A peer the runtime can hand to others as a contact (so they can seed off us). */
    @Override
    public Host getContact() {
        Host random = getRandom(membership);
        return random != null ? random : self;
    }

    /* ─────────────────────────────── Messages ──────────────────────────────── */

    private void uponSample(SampleMessage msg, Host from, short sourceProto, int channelId) {
        logger.debug("Received {} from {}", msg, from);
        // Connect to every peer in the sample we don't already know about.
        for (Host h : msg.getSample()) {
            if (!h.equals(self) && !membership.contains(h) && !pending.contains(h)) {
                pending.add(h);
                openConnection(h);
            }
        }
    }

    private void uponMsgFail(ProtoMessage msg, Host host, short destProto, Throwable throwable, int channelId) {
        logger.error("Message {} to {} failed: {}", msg, host, throwable);
    }

    /* ──────────────────────────────── Timers ───────────────────────────────── */

    private void uponSampleTimer(SampleTimer timer, long timerId) {
        if (membership.isEmpty()) return;
        Host target = getRandom(membership);
        Set<Host> sample = getRandomSubsetExcluding(membership, subsetSize, target);
        sample.add(self); // always include ourselves so the target learns about us
        sendMessage(new SampleMessage(sample), target);
        logger.debug("Gossiped a {}-host sample to {}", sample.size(), target);
    }

    private void uponInfoTimer(InfoTimer timer, long timerId) {
        logger.info("Membership ({}): {} | pending: {}", membership.size(), membership, pending);
    }

    /* ───────────────────────────── Channel events ──────────────────────────── */

    private void uponOutConnectionUp(OutConnectionUp event, int channelId) {
        Host peer = event.getNode();
        pending.remove(peer);
        if (membership.add(peer)) {
            logger.info("Neighbour up: {}", peer);
            triggerNotification(new NeighbourUp(peer));
        }
    }

    private void uponOutConnectionDown(OutConnectionDown event, int channelId) {
        Host peer = event.getNode();
        if (membership.remove(peer)) {
            logger.info("Neighbour down: {} ({})", peer, event.getCause());
            triggerNotification(new NeighbourDown(peer));
        }
    }

    private void uponOutConnectionFailed(OutConnectionFailed<ProtoMessage> event, int channelId) {
        // Connection never established — just forget it (a future sample may retry).
        logger.debug("Connection to {} failed: {}", event.getNode(), event.getCause());
        pending.remove(event.getNode());
    }

    private void uponInConnectionUp(InConnectionUp event, int channelId) {
        // Someone dialled us. We do nothing here; if we want them as a neighbour
        // we open our own outgoing connection (kept simple, like babel-example).
        logger.trace("In-connection from {} up", event.getNode());
    }

    private void uponInConnectionDown(InConnectionDown event, int channelId) {
        logger.trace("In-connection from {} down ({})", event.getNode(), event.getCause());
    }

    /* ───────────────────────────────── Utils ───────────────────────────────── */

    private Host getRandom(Set<Host> hosts) {
        if (hosts.isEmpty()) return null;
        int idx = rnd.nextInt(hosts.size());
        int i = 0;
        for (Host h : hosts) {
            if (i++ == idx) return h;
        }
        return null;
    }

    private static Set<Host> getRandomSubsetExcluding(Set<Host> hosts, int sampleSize, Host exclude) {
        List<Host> list = new LinkedList<>(hosts);
        list.remove(exclude);
        Collections.shuffle(list);
        return new HashSet<>(list.subList(0, Math.min(sampleSize, list.size())));
    }
}
