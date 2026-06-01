package protocols.membership.common.notifications;

import java.util.HashSet;
import java.util.Set;

import pt.unl.fct.di.novasys.babel.generic.ProtoNotification;
import pt.unl.fct.di.novasys.network.data.Host;

/**
 * Emitted by the membership protocol when one or more peers stop being
 * neighbours (their connection dropped). The broadcast protocol uses this to
 * stop forwarding to them; the chat app uses it to drop them from the roster
 * even if they crashed without a graceful "leave".
 *
 * @see NeighbourUp
 */
public class NeighbourDown extends ProtoNotification {

    public static final short NOTIFICATION_ID = 102;

    private final Set<Host> neighbours;

    public NeighbourDown(Host neighbour) {
        super(NOTIFICATION_ID);
        this.neighbours = new HashSet<>();
        this.neighbours.add(neighbour);
    }

    public void addNeighbour(Host neighbour) {
        this.neighbours.add(neighbour);
    }

    /** Defensive copy — subscribers must not mutate our internal set. */
    public Set<Host> getNeighbours() {
        return new HashSet<>(this.neighbours);
    }

    public int getLength() {
        return this.neighbours.size();
    }
}
