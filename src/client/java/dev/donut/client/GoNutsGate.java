package dev.donut.client;

import dev.doughbay.fabric.GoNutsApi;

/**
 * GoNuts, bundled inside Donut Client, trades through the auction house's chest menus. While a session works, Donut's
 * own automation must not send commands or touch containers: an /rtp or a click would land in the middle of one of
 * its steps, and its purchase messages are not Donut's.
 */
public final class GoNutsGate {
    private GoNutsGate() { }

    /** True while a GoNuts session is at work. */
    public static boolean busy() {
        try {
            return GoNutsApi.busy();
        } catch (LinkageError | RuntimeException e) {
            // GoNuts missing or not started yet never blocks Donut.
            return false;
        }
    }
}
