package dev.donut.client.config;

public final class FinderSettings {
    public boolean enabled = false;
    public boolean favorite = false;
    public boolean throughWalls = true;
    public boolean distanceFade = true;
    /** Lines from the crosshair to every highlight, so targets behind you or far away are easy to follow. */
    public boolean tracers = false;
    public RenderMode mode = RenderMode.BOTH;
    public double range = 96;
    public double outlineOpacity = 0.9;
    public double fillOpacity = 0.16;
    public double lineWidth = 1.5;
    public boolean chests = true;
    public boolean trappedChests = true;
    public boolean enderChests = true;
    public boolean shulkers = true;
    public boolean droppers = true;
    /** StorageFinder only. */
    public boolean barrels = true;
    public boolean hoppers = true;
    public boolean dispensers = true;
    public boolean furnaces = true;
    /** StorageFinder only: chest and hopper minecarts, such as those in mineshafts. */
    public boolean minecarts = true;
    public boolean trialSpawners = true;
    /** SpawnerFinder only: color spawners whose timer shows that a player has been near them. */
    public boolean activatedSpawners = true;
    /** DebrisFinder only: hide fake debris that anti-xray plugins scatter through buried netherrack. */
    public boolean filterFakes = true;
    /** DebrisFinder only: play a sound when debris appears from a block update, such as while mining. */
    public boolean notify = true;
    /**
     * DebrisFinder only: mark the netherrack where the world seed placed debris that the server hides. Off by default:
     * DonutSMP gives its debris its own secret seed, so estimates there mark empty spots.
     */
    public boolean seedEstimates = false;
    /**
     * DebrisFinder only: estimate spots the server revealed while mining, with debris and without, counted for
     * {@link #seedCountsFor}. Once they keep coming up empty, estimates stop.
     */
    public int seedHits = 0, seedMisses = 0;
    public String seedCountsFor = "";
    /** DebrisFinder only: the server's world seed, as typed when creating a world. Singleplayer uses its own. */
    public String seed = "6608149111735331168";
    /** DebrisFinder only: outline 16-block sections whose block palette still lists debris that anti-xray hid. */
    public boolean searchAreas = true;
    /** TunnelFinder only. */
    public boolean tunnels = true;
    public boolean holes = true;
    public boolean staircases = true;
    /** TunnelFinder only: the shortest straight run, in blocks, that counts as dug by a player. */
    public double minLength = 10;
    /** EntityFinder only. */
    public boolean players = true;
    public boolean hostiles = false;
    public boolean passives = false;
    public boolean items = false;
    /** ActivityFinder only: the amethyst growth a chunk needs before it is highlighted. */
    public double minActivity = 0.5;
    /** BaseFinder only: the score a chunk needs before it is marked; one strong find such as a shulker box is 6. */
    public double minBaseScore = 6;
    /** ActivityFinder only: draw squares on the surface with a guide down to the geode. */
    public boolean surfaceMarker = true;
    /** ChunkFinder only: rings of chunks around a spot that must all be loaded and show no sign of players. */
    public double minClearance = 4;
    /** ChunkFinder only: the longest a player may have been near a spot, in minutes, without its plants showing it. */
    public double maxUnseen = 10;
    /** ChunkFinder only: growth from stays shorter than this, in minutes, such as someone flying past, is no sign. */
    public double ignoreVisitsUnder = 2;
    /** ChunkFinder only: the most of a spot's underground that may be open cave, water or lava, from 0 to 1. */
    public double maxCaves = 0.05;
    /** ChunkFinder only: also mark the chunks spots keep away from, with why. */
    public boolean showSigns = true;
    /** ActivityFinder only: scan every loaded chunk without distance fade, ignoring the range. */
    public boolean allLoadedChunks = true;
    public boolean perTypeColors = true;
    public int color = 0x4C86FF;
    public int chestColor = 0xFFC66B;
    public int shulkerColor = 0xBA8CFF;
    public int dropperColor = 0x6DDAC5;
    /** StorageFinder: furnaces, smokers and blast furnaces. */
    public int furnaceColor = 0xFF975F;
    /**
     * SpawnerFinder: spawners a player has been near. TunnelFinder: vertical holes. EntityFinder: hostile mobs.
     * DebrisFinder: seed estimates. ChunkFinder: signs of players.
     */
    public int secondaryColor = 0xFF5A5A;
    /** EntityFinder: passive mobs. TunnelFinder: staircases. DebrisFinder: search areas. ChunkFinder: structures. */
    public int tertiaryColor = 0x7EDD8E;
    /** EntityFinder: dropped items. */
    public int itemColor = 0xFFFFFF;

    public void sanitize() { sanitize(new FinderSettings()); }

    /** Invalid numbers fall back to this module's own defaults. */
    public void sanitize(FinderSettings defaults) {
        if (mode == null) mode = defaults.mode;
        if (seed == null) seed = "";
        if (seedCountsFor == null) seedCountsFor = "";
        seedHits = Math.max(0, seedHits);
        seedMisses = Math.max(0, seedMisses);
        range = clamp(range, 16, 256, defaults.range);
        outlineOpacity = clamp(outlineOpacity, 0, 1, defaults.outlineOpacity);
        fillOpacity = clamp(fillOpacity, 0, 1, defaults.fillOpacity);
        lineWidth = clamp(lineWidth, 0.5, 5, defaults.lineWidth);
        minActivity = clamp(minActivity, 0, 1, defaults.minActivity);
        minLength = clamp(minLength, 4, 32, defaults.minLength);
        minBaseScore = clamp(minBaseScore, 2, 30, defaults.minBaseScore);
        minClearance = clamp(minClearance, 1, 12, defaults.minClearance);
        maxUnseen = clamp(maxUnseen, 1, 60, defaults.maxUnseen);
        ignoreVisitsUnder = clamp(ignoreVisitsUnder, 0, 30, defaults.ignoreVisitsUnder);
        maxCaves = clamp(maxCaves, 0, 0.3, defaults.maxCaves);
        color &= 0xFFFFFF;
        chestColor &= 0xFFFFFF;
        shulkerColor &= 0xFFFFFF;
        dropperColor &= 0xFFFFFF;
        furnaceColor &= 0xFFFFFF;
        secondaryColor &= 0xFFFFFF;
        tertiaryColor &= 0xFFFFFF;
        itemColor &= 0xFFFFFF;
    }

    private static double clamp(double value, double min, double max, double fallback) {
        return Double.isFinite(value) ? Math.clamp(value, min, max) : fallback;
    }
}
