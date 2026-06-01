package protocols.membership.common.notifications;

import pt.unl.fct.di.novasys.babel.generic.ProtoNotification;

/**
 * Emitted once by the membership protocol to tell the other protocols which
 * Babel channel (TCP connection pool) it created, so they can share it.
 *
 * <p>In Babel a channel is owned by one protocol but can be <em>shared</em>: the
 * broadcast and chat protocols call {@code registerSharedChannel(channelId)} on
 * receiving this, which lets them register their own message serializers/handlers
 * on the membership's channel and send messages over the same connections —
 * rather than each opening its own redundant set of TCP connections.
 */
public class ChannelCreated extends ProtoNotification {

    public static final short NOTIFICATION_ID = 103;

    private final int channelId;

    public ChannelCreated(int channelId) {
        super(NOTIFICATION_ID);
        this.channelId = channelId;
    }

    public int getChannelId() {
        return channelId;
    }
}
