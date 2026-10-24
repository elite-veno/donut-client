package dev.donut.client.config;

public final class HudSettings {
    /** A small panel with each finder's count and the nearest find's distance and direction. */
    public boolean info = true;
    /** Names and distances next to the nearest highlights in the world. */
    public boolean labels = true;
    /** How many of the nearest highlights get a label. */
    public int maxLabels = 8;

    public void sanitize() {
        maxLabels = Math.clamp(maxLabels, 1, 32);
    }
}
