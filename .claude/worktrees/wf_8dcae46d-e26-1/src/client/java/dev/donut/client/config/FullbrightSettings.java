package dev.donut.client.config;

public final class FullbrightSettings {
    public boolean enabled = false;
    public boolean favorite = false;
    /** Also clears distance, water, lava, blindness and darkness fog. */
    public boolean noFog = true;

    public void sanitize() { }
}
