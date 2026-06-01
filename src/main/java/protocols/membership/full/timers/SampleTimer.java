package protocols.membership.full.timers;

import pt.unl.fct.di.novasys.babel.generic.ProtoTimer;

/**
 * Fires periodically to make the membership protocol gossip a sample of its
 * neighbours (see {@code GossipBasedFullMembership.uponSampleTimer}). Set up with
 * {@code setupPeriodicTimer(...)}.
 *
 * <p>This timer carries no data, so {@link #clone()} returns {@code this} rather
 * than allocating a fresh object on every fire — the Babel convention for
 * stateless timers.
 */
public class SampleTimer extends ProtoTimer {

    public static final short TIMER_ID = 101;

    public SampleTimer() {
        super(TIMER_ID);
    }

    @Override
    public ProtoTimer clone() {
        return this;
    }
}
