package dev.donut.client.config;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

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
    /**
     * The schematic, origin, turn and mirror last used in each world, by {@link #worldKey}: a world joined again gets its own
     * back, and one never built in starts without an origin rather than with another world's.
     */
    public Map<String, Placement> places = new LinkedHashMap<>();
    /**
     * The world the placement above is for, saved with it: after a restart it is still known whose it is, and it is kept
     * as that world's when another is joined. Empty in a config from before placements were kept per world.
     */
    public String world = "";
    /** Worlds remembered; the oldest is forgotten first. */
    public static final int MAX_PLACES = 64;

    public static final class Placement {
        public String file = "";
        public boolean originSet;
        public int originX, originY, originZ, rotation, mirror;
    }

    /** Keeps the current placement as {@code world}'s. */
    public void remember(String world) {
        if (world == null || world.isEmpty() || file.isEmpty() && !originSet) return;
        Placement place = new Placement();
        place.file = file;
        place.originSet = originSet;
        place.originX = originX;
        place.originY = originY;
        place.originZ = originZ;
        place.rotation = rotation;
        place.mirror = mirror;
        places.remove(world);
        places.put(world, place);
        for (Iterator<String> it = places.keySet().iterator(); places.size() > MAX_PLACES && it.hasNext(); ) {
            it.next();
            it.remove();
        }
    }

    /**
     * Takes {@code world}'s placement back: true when it had one. Without one the origin is unset, as another world's
     * spot means nothing here; the schematic stays chosen.
     */
    public boolean recall(String world) {
        Placement place = world == null ? null : places.get(world);
        if (place == null) {
            originSet = false;
            return false;
        }
        file = place.file == null ? "" : place.file;
        originSet = place.originSet;
        originX = place.originX;
        originY = place.originY;
        originZ = place.originZ;
        rotation = place.rotation;
        mirror = place.mirror;
        sanitize();
        return true;
    }

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
    /** About how often, in seconds, a pause of a second or a few comes between blocks, a look about, with breaks on. */
    public double pauseEvery = 45;
    /** How quickly the view turns, as a share of a player's usual pace: never a jump, only a quicker or calmer hand. */
    public double turnSpeed = 1;
    /** After a click, the longest moment before the view turns on to the next block, in seconds; each is a third to all of it. */
    public double reaction = 0.12;
    /** Ticks the crosshair rests on its spot before the click; now and then one more. */
    public int settle = 1;
    /** On average how many ticks more than that the crosshair rests, at random: mostly none, now and then a few. */
    public double settleSpread = 0.7;
    /** After taking another item in hand, the longest moment before using it, in seconds; each is a third to all of it. */
    public double itemSwitch = 0.2;
    /** After a block mined breaks, attack stays held while the view turns to the next one, as a player clearing a row does. */
    public boolean holdAttack = true;
    /** Of every hundred longer turns to a block, about how many land a little off and correct, as a hand does. */
    public int aimCorrections = 30;
    /** How far a click may land off the middle and the thirds of a face, as a share of it: more is a looser hand. */
    public double clickSpread = 0.15;
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
        if (places == null) places = new LinkedHashMap<>();
        if (world == null) world = "";
        places.values().removeIf(place -> place == null);
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
        pauseEvery = Double.isFinite(pauseEvery) ? Math.clamp(pauseEvery, 15, 300) : 45;
        // Slower than this, turning round on the spot outlasts the walker's patience; quicker, a turn is near a flick.
        turnSpeed = Double.isFinite(turnSpeed) ? Math.clamp(turnSpeed, 0.8, 2) : 1;
        reaction = Double.isFinite(reaction) ? Math.clamp(reaction, 0, 0.5) : 0.12;
        settle = Math.clamp(settle, 1, 6);
        itemSwitch = Double.isFinite(itemSwitch) ? Math.clamp(itemSwitch, 0.05, 0.5) : 0.2;
        settleSpread = Double.isFinite(settleSpread) ? Math.clamp(settleSpread, 0, 2) : 0.7;
        clickSpread = Double.isFinite(clickSpread) ? Math.clamp(clickSpread, 0.06, 0.15) : 0.15;
        aimCorrections = Math.clamp(aimCorrections, 0, 80);
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
