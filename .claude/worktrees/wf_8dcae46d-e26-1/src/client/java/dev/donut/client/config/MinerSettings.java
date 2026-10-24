package dev.donut.client.config;

public final class MinerSettings {
    /** The fastest and slowest allowed wait between two blocks, in seconds. */
    public static final double MIN_DELAY = 0, MAX_DELAY = 5;
    public static final int MIN_FLOOR_Y = -60, MAX_FLOOR_Y = 300;

    /** The miner always starts off when joining a new session. */
    public transient boolean enabled = false;
    public boolean favorite = false;
    /** Wait for the chunk finder's best good chunk and dig there; off digs where the schematic's origin is, or where you stand. */
    public boolean goodChunks = true;
    /** The room's floor height under a good chunk: the schematic's lowest layer goes here, on the block below it. */
    public int floorY = -40;
    /** Blocks dug around the schematic on each side, and above it. The floor below it is never dug. */
    public int sideRoom = 3;
    public int headroom = 2;
    /** A lower room beside the schematic for storage: 0 none, 1 north, 2 east, 3 south, 4 west, 5 the side the tunnel comes in. */
    public int storageSide = 5;
    public int storageDepth = 6;
    public int storageHeight = 4;
    /** 0 the amethyst pickaxe when there is one, 1 only the amethyst pickaxe, 2 only normal tools. */
    public int pickaxe = 0;
    /** A tool with this much durability left is put away, so it never breaks. */
    public int keepDurability = 15;
    /** Put a junk block (cobblestone, dirt and the like) under a step that has nothing to stand on. */
    public boolean fillGaps = true;
    /** Start SchematicBuilder on the dug room once it is done. */
    public boolean buildAfter = false;
    public boolean stopWhenHurt = true;
    /** Pause while another player is this close, in blocks; 0 never pauses. */
    public int pauseForPlayers = 32;
    public boolean preview = true;

    // Looking human -------------------------------------------------------------------------------------------------
    /** Each block waits a random time between these two, in seconds, after the one before breaks; the game adds a quarter second. */
    public double minDelay = 0.05;
    public double maxDelay = 0.35;
    /** The fastest the camera turns, in degrees a tick; it eases in and out, and never jumps. */
    public double turnSpeed = 24;
    /** How far from a face's middle the aim may land, as a share of the face (0 always the middle). */
    public double aimSpread = 0.6;
    /** The camera drifts this many degrees at most while a block breaks, as a hand does. */
    public double handShake = 0.35;
    /** The chance, after each block, of a longer pause between these two times, in seconds. */
    public double pauseChance = 0.04;
    public double pauseMin = 1.2;
    public double pauseMax = 4.5;
    /** Pick among the few best next blocks at random instead of always the very best. */
    public boolean varyOrder = true;

    public void sanitize() {
        floorY = Math.clamp(floorY, MIN_FLOOR_Y, MAX_FLOOR_Y);
        sideRoom = Math.clamp(sideRoom, 0, 12);
        headroom = Math.clamp(headroom, 0, 8);
        storageSide = Math.clamp(storageSide, 0, 5);
        storageDepth = Math.clamp(storageDepth, 2, 24);
        storageHeight = Math.clamp(storageHeight, 2, 8);
        pickaxe = Math.clamp(pickaxe, 0, 2);
        keepDurability = Math.clamp(keepDurability, 1, 200);
        pauseForPlayers = Math.clamp(pauseForPlayers, 0, 128);
        minDelay = finite(minDelay, 0.05, MIN_DELAY, MAX_DELAY);
        maxDelay = finite(maxDelay, 0.35, MIN_DELAY, MAX_DELAY);
        if (maxDelay < minDelay) maxDelay = minDelay;
        turnSpeed = finite(turnSpeed, 24, 4, 90);
        aimSpread = finite(aimSpread, 0.6, 0, 0.9);
        handShake = finite(handShake, 0.35, 0, 2);
        pauseChance = finite(pauseChance, 0.04, 0, 0.5);
        pauseMin = finite(pauseMin, 1.2, 0.2, 30);
        pauseMax = finite(pauseMax, 4.5, 0.2, 30);
        if (pauseMax < pauseMin) pauseMax = pauseMin;
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

    public void setPauseMin(double seconds) {
        pauseMin = Math.clamp(seconds, 0.2, 30);
        if (pauseMax < pauseMin) pauseMax = pauseMin;
    }

    public void setPauseMax(double seconds) {
        pauseMax = Math.clamp(seconds, 0.2, 30);
        if (pauseMin > pauseMax) pauseMin = pauseMax;
    }

    private static double finite(double value, double fallback, double min, double max) {
        return Double.isFinite(value) ? Math.clamp(value, min, max) : fallback;
    }
}
