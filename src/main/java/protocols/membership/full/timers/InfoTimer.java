package protocols.membership.full.timers;

import pt.unl.fct.di.novasys.babel.generic.ProtoTimer;

/**
 * Optional diagnostics timer: when enabled (via {@code protocol_metrics_interval}
 * &gt; 0) the membership protocol logs its current view on each fire. Handy while
 * learning — tail {@code babel-demo.log} to watch the membership converge.
 *
 * <p>Stateless, so {@link #clone()} returns {@code this}.
 */
public class InfoTimer extends ProtoTimer {

    public static final short TIMER_ID = 102;

    public InfoTimer() {
        super(TIMER_ID);
    }

    @Override
    public ProtoTimer clone() {
        return this;
    }
}
