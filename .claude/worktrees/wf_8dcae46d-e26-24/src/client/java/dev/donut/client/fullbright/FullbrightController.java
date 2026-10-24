package dev.donut.client.fullbright;

import dev.donut.client.config.ClientConfig;
import dev.donut.client.xray.XrayController;
import java.util.Objects;

/** Overrides only the lightmap's brightness, leaving the saved brightness option intact. */
public final class FullbrightController {
    /** The lightmap reaches full brightness everywhere well before this, without extreme values. */
    public static final double GAMMA = 16.0;

    private static volatile boolean bright;
    private static volatile boolean clearView;

    private final ClientConfig config;
    private Boolean appliedBright;
    private Boolean appliedNoFog;

    public FullbrightController(ClientConfig config) { this.config = config; }

    /** Applies the settings when they change, so another controller's state is left alone until then. */
    public void tick() {
        boolean wantBright = config.fullbright.enabled;
        boolean wantNoFog = wantBright && config.fullbright.noFog;
        if (!Objects.equals(appliedBright, wantBright)) {
            appliedBright = wantBright;
            bright = wantBright;
        }
        if (!Objects.equals(appliedNoFog, wantNoFog)) {
            appliedNoFog = wantNoFog;
            clearView = wantNoFog;
        }
    }

    /** X-ray reveals blocks deep inside unlit stone, so it brightens the world too. */
    public static double effectiveGamma(double originalGamma) {
        return bright || XrayController.enabled() ? GAMMA : originalGamma;
    }

    /** Distance, water, lava, blindness and darkness fog all come from the same buffer. */
    public static boolean noFog() { return clearView; }
}
