package protocols.broadcast.flood;

import java.io.IOException;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import protocols.broadcast.common.BroadcastRequest;
import protocols.broadcast.common.DeliverNotification;
import protocols.broadcast.flood.messages.BroadcastMessage;
import protocols.membership.common.notifications.ChannelCreated;
import protocols.membership.common.notifications.NeighbourDown;
import protocols.membership.common.notifications.NeighbourUp;
import pt.unl.fct.di.novasys.babel.core.GenericProtocol;
import pt.unl.fct.di.novasys.babel.exceptions.HandlerRegistrationException;
import pt.unl.fct.di.novasys.babel.generic.ProtoMessage;
import pt.unl.fct.di.novasys.network.data.Host;

/**
 * Flood broadcast — the simplest reliable dissemination: when you receive a
 * message for the first time, deliver it locally and forward it to every
 * neighbour except the one you got it from. Duplicates are dropped by remembering
 * message ids. Given a connected membership, every node receives every message.
 *
 * <h2>How it cooperates with the other protocols</h2>
 * <ul>
 *   <li>It owns <b>no</b> channel of its own. When the membership announces its
 *       channel ({@link ChannelCreated}), this protocol attaches to it with
 *       {@code registerSharedChannel} and registers its message handler there —
 *       so it reuses the membership's connections rather than opening new ones.</li>
 *   <li>Its set of forwarding targets is exactly the membership's neighbours,
 *       kept in sync via {@link NeighbourUp} / {@link NeighbourDown}. This is what
 *       makes the broadcast "neighbour-aware".</li>
 *   <li>The application asks it to send via {@link BroadcastRequest} and receives
 *       deliveries via {@link DeliverNotification}.</li>
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

        // The application's entry point into this protocol.
        registerRequestHandler(BroadcastRequest.REQUEST_ID, this::uponBroadcastRequest);

        // We learn the channel and our neighbour set from the membership protocol.
        subscribeNotification(ChannelCreated.NOTIFICATION_ID, this::uponChannelCreated);
        subscribeNotification(NeighbourUp.NOTIFICATION_ID, this::uponNeighbourUp);
        subscribeNotification(NeighbourDown.NOTIFICATION_ID, this::uponNeighbourDown);
    }

    @Override
    public void init(Properties props) {
        // Nothing to do at init — we react to events from the membership and app.
    }

    /* ─────────── Attach to the membership's shared channel ─────────── */

    private void uponChannelCreated(ChannelCreated notification, short sourceProto) {
        int cId = notification.getChannelId();
        registerSharedChannel(cId); // lets us send/receive on the membership's channel
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
        // Wrap the app payload, tagging which protocol should receive the delivery.
        BroadcastMessage msg = new BroadcastMessage(request.getMsgId(), request.getSender(), sourceProto, request.getMsg());
        // Reuse the receive path: treat our own message exactly like an incoming one
        // (from "myself"), so it gets delivered locally and forwarded once.
        uponBroadcastMessage(msg, myself, getProtoId(), -1);
    }

    /* ─────────────────────────── Messages ─────────────────────────── */

    private void uponBroadcastMessage(BroadcastMessage msg, Host from, short sourceProto, int channelId) {
        // received.add returns false if we'd already seen this id → drop the dup.
        if (received.add(msg.getMid())) {
            // 1) Deliver to the application (the protocol named in toDeliver).
            triggerNotification(new DeliverNotification(msg.getMid(), msg.getSender(), msg.getContent()));
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

    private void uponNeighbourUp(NeighbourUp notification, short sourceProto) {
        neighbours.addAll(notification.getNeighbours());
    }

    private void uponNeighbourDown(NeighbourDown notification, short sourceProto) {
        notification.getNeighbours().forEach(neighbours::remove);
    }
}
