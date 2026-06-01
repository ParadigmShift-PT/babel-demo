package protocols.broadcast.flood;

import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import protocols.broadcast.flood.messages.BroadcastMessage;
import pt.unl.fct.di.novasys.babel.core.GenericProtocol;
import pt.unl.fct.di.novasys.babel.exceptions.HandlerRegistrationException;
import pt.unl.fct.di.novasys.babel.generic.ProtoMessage;
import pt.unl.fct.di.novasys.babel.protocols.dissemination.requests.BroadcastRequest;
import pt.unl.fct.di.novasys.babel.protocols.general.notifications.ChannelAvailableNotification;
import pt.unl.fct.di.novasys.babel.protocols.membership.notifications.NeighborDown;
import pt.unl.fct.di.novasys.babel.protocols.membership.notifications.NeighborUp;
import pt.unl.fct.di.novasys.network.data.Host;

/**
 * Flood broadcast — the simplest reliable dissemination: when you receive a
 * message for the first time, deliver it locally and forward it to every
 * neighbour except the one you got it from. Duplicates are dropped by remembering
 * message ids. Given a connected membership, every node receives every message.
 *
 * <h2>Shared abstractions</h2>
 * Its public surface is the {@code babel-protocols-common} dissemination API, not
 * bespoke types: the application asks it to send via a
 * {@link BroadcastRequest} and receives deliveries via a {@link BroadcastDelivery}
 * notification — the same contract the real ParadigmShift gossip protocols use.
 * Only its on-the-wire {@link BroadcastMessage} is protocol-specific.
 *
 * <h2>How it cooperates with the other protocols</h2>
 * <ul>
 *   <li>It owns <b>no</b> channel of its own. It attaches to the membership's
 *       channel on the membership's {@link ChannelAvailableNotification} via
 *       {@code registerSharedChannel}, reusing those connections.</li>
 *   <li>Its forwarding targets are exactly the membership's neighbours, kept in
 *       sync via the common {@link NeighborUp} / {@link NeighborDown}.</li>
 * </ul>
 */
public class FloodBroadcast extends GenericProtocol {

    private static final Logger logger = LogManager.getLogger(FloodBroadcast.class);

    public static final short PROTOCOL_ID = 200;
    public static final String PROTOCOL_NAME = "FloodBroadcast";

    private final Host myself;
    private final Set<Host> neighbours;  // kept in sync with the membership
    private final Set<UUID> received;     // message ids already seen (dedup)
    private boolean channelReady;         // true once the membership shares its channel

    public FloodBroadcast(Properties props, Host myself) throws HandlerRegistrationException {
        super(PROTOCOL_NAME, PROTOCOL_ID);
        this.myself = myself;
        this.neighbours = new HashSet<>();
        this.received = new HashSet<>();
        this.channelReady = false;

        // Two ways protocols talk to each other in-process (no network):
        //  • a REQUEST is a directed ask from one protocol to another — here the
        //    app asks us to broadcast. We register a handler for its id.
        registerRequestHandler(BroadcastRequest.REQUEST_ID, this::uponBroadcastRequest);
        //  • a NOTIFICATION is published once and delivered to every subscriber.
        //    We subscribe to the membership's channel announcement and its
        //    neighbour up/down events.
        subscribeNotification(ChannelAvailableNotification.NOTIFICATION_ID, this::uponChannelAvailable);
        subscribeNotification(NeighborUp.NOTIFICATION_ID, this::uponNeighborUp);
        subscribeNotification(NeighborDown.NOTIFICATION_ID, this::uponNeighborDown);
    }

    @Override
    public void init(Properties props) {
        // Nothing to do at init — we react to events from the membership and app.
    }

    /* ─────────── Attach to the membership's shared channel ─────────── */

    private void uponChannelAvailable(ChannelAvailableNotification notification, short sourceProto) {
        int cId = notification.getChannelID();
        // registerSharedChannel lets a protocol use a channel another protocol
        // created — so we send/receive over the membership's connections instead
        // of opening our own. After this we register our own message types on it.
        registerSharedChannel(cId);
        registerMessageSerializer(cId, BroadcastMessage.MSG_ID, BroadcastMessage.serializer);
        try {
            registerMessageHandler(cId, BroadcastMessage.MSG_ID, this::uponBroadcastMessage, this::uponMsgFail);
        } catch (HandlerRegistrationException e) {
            logger.error("Failed to register broadcast message handler", e);
            System.exit(1);
        }
        channelReady = true;
        logger.info("Broadcast ready on shared channel {}", cId);
    }

    /* ─────────────────────────── Requests ─────────────────────────── */

    private void uponBroadcastRequest(BroadcastRequest request, short sourceProto) {
        if (!channelReady) {
            // We haven't joined yet (no channel). A real system would buffer this.
            logger.warn("Dropping broadcast request — not connected yet");
            return;
        }
        // Wrap the request's payload + origin + issue time into our wire message.
        // The message assigns its own unique MID on construction (the canonical
        // "id defined at the broadcast protocol level" — see BroadcastMessage).
        BroadcastMessage msg = new BroadcastMessage(
                request.getOriginalSender(), request.getTimestamp(), request.getPayload());
        // Reuse the receive path: treat our own message exactly like an incoming
        // one (from "myself"), so it gets delivered locally and forwarded once.
        uponBroadcastMessage(msg, myself, getProtoId(), -1);
    }

    /* ─────────────────────────── Messages ─────────────────────────── */

    private void uponBroadcastMessage(BroadcastMessage msg, Host from, short sourceProto, int channelId) {
        // received.add returns false if we'd already seen this MID → drop the dup.
        if (received.add(msg.getMID())) {
            // 1) Deliver to the application: the message builds its own common
            //    BroadcastDelivery (the canonical IdentifiableProtoMessage hook).
            triggerNotification(msg.generateDeliveryNotification(getProtoId()));
            // 2) Forward to every neighbour except whoever just sent it to us.
            for (Host neighbour : neighbours) {
                if (!neighbour.equals(from)) {
                    sendMessage(msg, neighbour);
                }
            }
        }
    }

    private void uponMsgFail(ProtoMessage msg, Host host, short destProto, Throwable throwable, int channelId) {
        logger.error("Message {} to {} failed: {}", msg, host, throwable);
    }

    /* ─────────────────────── Membership notifications ─────────────────────── */

    private void uponNeighborUp(NeighborUp notification, short sourceProto) {
        neighbours.add(notification.getPeer());
    }

    private void uponNeighborDown(NeighborDown notification, short sourceProto) {
        neighbours.remove(notification.getPeer());
    }
}
