package dev.donut.client.config;

import java.util.Locale;

public final class SchematicSettings {
    /** The fastest and slowest allowed delay between two blocks, in seconds. */
    public static final double MIN_DELAY = 0.05, MAX_DELAY = 10;

    /** The builder always starts off when joining a new session. */
    public transient boolean enabled = false;
    /** The .litematic file, by name, in the game folder's schematics folder. */
    public String file = "";
    /** Where the schematic's origin goes; unset until the player picks a spot. */
    public boolean originSet = false;
    public int originX, originY, originZ;
    /** Quarter turns clockwise, seen from above. */
    public int rotation = 0;
    /** 0 none, 1 left to right (flips north and south), 2 front to back (flips east and west). */
    public int mirror = 0;
    /** Each block waits a random time between these two, in seconds. */
    public double minDelay = 0.65;
    public double maxDelay = 2.3;
    /** Take missing blocks out of shulker boxes carried in the inventory: place one, empty what is needed, break it. */
    public boolean useShulkers = true;
    /** Only click faces the player can see, looking straight at the spot clicked. Slower: some blocks need you on their other side. */
    public boolean strictLooks = false;
    /** Within each layer, redstone components go after the plain blocks, and pistons, observers and dispensers last. */
    public boolean redstoneLast = true;
    /** Outline the schematic and the blocks left in the layer being built. */
    public boolean preview = true;

    public void sanitize() {
        file = file == null ? "" : file.strip();
        // A file name only: the schematics folder is the only place the builder reads from.
        int slash = Math.max(file.lastIndexOf('/'), file.lastIndexOf('\\'));
        if (slash >= 0) file = file.substring(slash + 1);
        if (!file.isEmpty() && !file.toLowerCase(Locale.ROOT).endsWith(".litematic")) file = file + ".litematic";
        rotation = Math.floorMod(rotation, 4);
        mirror = Math.clamp(mirror, 0, 2);
        originY = Math.clamp(originY, -2048, 2048);
        originX = Math.clamp(originX, -30_000_000, 30_000_000);
        originZ = Math.clamp(originZ, -30_000_000, 30_000_000);
        minDelay = Double.isFinite(minDelay) ? Math.clamp(minDelay, MIN_DELAY, MAX_DELAY) : 0.65;
        maxDelay = Double.isFinite(maxDelay) ? Math.clamp(maxDelay, MIN_DELAY, MAX_DELAY) : 2.3;
        if (maxDelay < minDelay) maxDelay = minDelay;
    }

    /** Sets the shortest delay; a longest delay below it moves up with it. */
    public void setMinDelay(double seconds) {
        minDelay = Math.clamp(seconds, MIN_DELAY, MAX_DELAY);
        if (maxDelay < minDelay) maxDelay = minDelay;
    }

    /** Sets the longest delay; a shortest delay above it moves down with it. */
    public void setMaxDelay(double seconds) {
        maxDelay = Math.clamp(seconds, MIN_DELAY, MAX_DELAY);
        if (minDelay > maxDelay) minDelay = maxDelay;
    }
}
