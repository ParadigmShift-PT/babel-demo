package protocols.membership.common.notifications;

import java.util.HashSet;
import java.util.Set;

import pt.unl.fct.di.novasys.babel.generic.ProtoNotification;
import pt.unl.fct.di.novasys.network.data.Host;

/**
 * Emitted by the membership protocol when one or more peers become neighbours
 * (i.e. a connection to them is established and they enter our membership).
 *
 * <p>This is the key decoupling point in the demo: the membership protocol knows
 * <em>nothing</em> about broadcast or chat — it just announces "here is a new
 * neighbour", and any protocol that cares (the broadcast, the chat roster) can
 * subscribe to this notification. That is how Babel keeps protocols composable.
 *
 * <p>It carries a <em>set</em> (not a single host) so a protocol can also report
 * several neighbours at once if it ever needs to; the membership here reports one
 * at a time.
 */
public class NeighbourUp extends ProtoNotification {

    // Membership owns protocol id 100; its notifications start at 101.
    public static final short NOTIFICATION_ID = 101;

    private final Set<Host> neighbours;

    public NeighbourUp(Host neighbour) {
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
