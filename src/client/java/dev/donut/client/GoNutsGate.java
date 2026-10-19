package dev.donut.client;

import dev.doughbay.fabric.GoNutsApi;

/**
 * GoNuts, bundled inside Ven Client, trades and buys a build's materials through the auction house's chest menus. While
 * it works, Ven's own automation must not send commands or touch containers: an /rtp, a click or a build step would land
 * in the middle of one of its steps.
 */
public final class GoNutsGate {
    private GoNutsGate() { }

    /** True while GoNuts is at work: a trading session, a step of its driver, or a materials quote or purchase. */
    public static boolean busy() {
        try {
            return GoNutsApi.busy();
        } catch (LinkageError | RuntimeException e) {
            // GoNuts missing or not started yet never blocks Ven.
            return false;
        }
    }
}
