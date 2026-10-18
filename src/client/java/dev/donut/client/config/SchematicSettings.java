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
    /** Each block waits a random time between these two, in seconds: about as quick as a player building a row. */
    public static final double DEFAULT_MIN_DELAY = 0.25, DEFAULT_MAX_DELAY = 0.9;
    public double minDelay = DEFAULT_MIN_DELAY;
    public double maxDelay = DEFAULT_MAX_DELAY;
    /** Now and then a break: the player stands a while and looks about before building on. */
    public boolean breaks = true;
    /** About how often a break comes, in minutes: each wait is a third shorter or longer, at random. */
    public double breakEvery = 4;
    /** The longest break, in seconds: each lasts between a fifth of it and all of it. */
    public double breakLength = 25;
    /** How quickly the view turns, as a share of a player's usual pace: never a jump, only a quicker or calmer hand. */
    public double turnSpeed = 1;
    /** After a click, the longest moment before the view turns on to the next block, in seconds; each is a third to all of it. */
    public double reaction = 0.12;
    /** Ticks the crosshair rests on its spot before the click; now and then one more. */
    public int settle = 1;
    /** Of every hundred walks, about how many start with a moment of standing still. */
    public int hesitate = 12;
    /** Longer walks over open ground are sprinted; near an edge never. */
    public boolean sprint = true;
    /** Take missing blocks out of shulker boxes carried in the inventory: place one, empty what is needed, break it. */
    public boolean useShulkers = true;
    /** Within each layer, redstone components go after the plain blocks, and pistons, observers and dispensers last. */
    public boolean redstoneLast = true;
    /** Outline the schematic and the blocks left in the layer being built. */
    public boolean preview = true;
    /** Walk, jump and climb to each block on its own; off, the player walks and it places what comes in reach. */
    public boolean walk = true;
    /** Blocks it may put down to climb on or to place a block against, and takes away again; the build's own never. */
    public String helperBlocks = "dirt, cobblestone, cobbled_deepslate, netherrack, stone, andesite, diorite, granite, tuff, blackstone";

    public void sanitize() {
        file = file == null ? "" : file.strip();
        helperBlocks = helperBlocks == null ? "" : helperBlocks.strip();
        if (helperBlocks.length() > 512) helperBlocks = helperBlocks.substring(0, 512);
        // A file name only: the schematics folder is the only place the builder reads from.
        int slash = Math.max(file.lastIndexOf('/'), file.lastIndexOf('\\'));
        if (slash >= 0) file = file.substring(slash + 1);
        if (!file.isEmpty() && !file.toLowerCase(Locale.ROOT).endsWith(".litematic")) file = file + ".litematic";
        rotation = Math.floorMod(rotation, 4);
        mirror = Math.clamp(mirror, 0, 2);
        originY = Math.clamp(originY, -2048, 2048);
        originX = Math.clamp(originX, -30_000_000, 30_000_000);
        originZ = Math.clamp(originZ, -30_000_000, 30_000_000);
        minDelay = Double.isFinite(minDelay) ? Math.clamp(minDelay, MIN_DELAY, MAX_DELAY) : DEFAULT_MIN_DELAY;
        maxDelay = Double.isFinite(maxDelay) ? Math.clamp(maxDelay, MIN_DELAY, MAX_DELAY) : DEFAULT_MAX_DELAY;
        if (maxDelay < minDelay) maxDelay = minDelay;
        breakEvery = Double.isFinite(breakEvery) ? Math.clamp(breakEvery, 1, 30) : 4;
        breakLength = Double.isFinite(breakLength) ? Math.clamp(breakLength, 5, 120) : 25;
        // Slower than this, turning round on the spot outlasts the walker's patience; quicker, a turn is near a flick.
        turnSpeed = Double.isFinite(turnSpeed) ? Math.clamp(turnSpeed, 0.8, 2) : 1;
        reaction = Double.isFinite(reaction) ? Math.clamp(reaction, 0, 0.5) : 0.12;
        settle = Math.clamp(settle, 1, 6);
        hesitate = Math.clamp(hesitate, 0, 50);
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
