package dev.donut.client.finder;

import dev.donut.client.config.ClientConfig;
import dev.donut.client.config.FinderSettings;
import dev.donut.client.freecam.FreecamController;
import dev.donut.client.mixin.BaseSpawnerAccessor;
import it.unimi.dsi.fastutil.longs.Long2BooleanOpenHashMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import net.minecraft.ChatFormatting;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Util;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.TrialSpawnerBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.block.entity.TrialSpawnerBlockEntity;
import net.minecraft.world.level.block.entity.trialspawner.TrialSpawnerState;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.phys.Vec3;

/** Reads only blocks and block entities already sent to the client; never requests chunks. */
public final class FinderScanner implements ChunkEvents.Listener {
    /** Per finder: block entities, and debris, each keep their nearest this many, so fake debris can't crowd out chests. */
    public static final int MAX_TARGETS = 4096;
    /** A spawner's timer starts at this value and only runs while a player is within its range. */
    private static final int NATURAL_SPAWN_DELAY = 20;
    private static FinderScanner instance;

    private final ClientConfig config;
    private final DebrisIndex debris = new DebrisIndex();
    private final ActivityIndex activity = new ActivityIndex();
    private final BaseIndex baseIndex = new BaseIndex();
    private List<BaseArea> bases = List.of();
    private double baseRange;
    private final TunnelIndex tunnelIndex = new TunnelIndex();
    private final GoodChunkIndex goodIndex = new GoodChunkIndex();
    private List<GoodChunk> goodChunks = List.of();
    private List<ChunkSign> chunkSigns = List.of();
    /** The best spot last announced in chat, so each area is announced once. */
    private ChunkPos announcedSpot;
    /** The best spot of the last passes and for how many in a row, so one still shifting as chunks arrive waits. */
    private ChunkPos steadySpot;
    private int steadyPasses;
    private final RtpZones rtpWatch = new RtpZones();
    private ClientLevel rtpLevel;
    /** The singleplayer world being played, to forget landings noted in an earlier one. */
    private Object rtpWorld;
    private long rtpTicks;
    /** Where to go to be ChunkFinder's distance from every random teleport landing, or null when already there. */
    private Vec3 travelTarget;
    /** The server name RTP landings in singleplayer worlds are kept under. */
    private static final String SINGLEPLAYER = "singleplayer";
    /**
     * Whether each spawner looked activated the first time it was seen, before our own presence could run its timer.
     * Kept per dimension for the whole connection: a spawner we ran ourselves must not count once we come back.
     */
    private final Map<ResourceKey<Level>, Long2BooleanOpenHashMap> spawnerSight = new HashMap<>();
    private Long2BooleanOpenHashMap spawnerFirstSight = new Long2BooleanOpenHashMap();
    private final List<BlockPos> revealed = new ArrayList<>();
    private List<FoundBlock> targets = List.of();
    private List<ActivityArea> areas = List.of();
    private List<TunnelDetector.Segment> tunnels = List.of();
    private List<FinderSummary> summaries = List.of();
    private double activityRange;
    private ActivityStats activityStats = ActivityStats.EMPTY;
    private ClientLevel lastLevel;
    private int ticks;
    private long lastNotice;

    public FinderScanner(ClientConfig config) {
        this.config = config;
        instance = this;
        ChunkEvents.register(this);
        ClientSendMessageEvents.COMMAND.register(command -> {
            if (RtpZones.isRtp(command, config.rtp.command)) rtpWatch.commandSent(rtpTicks);
        });
    }

    /** The most recently created scanner, for screens that show live counts. */
    public static FinderScanner instance() { return instance; }

    public List<FoundBlock> snapshot() { return targets; }
    public long storageCount() { return targets.stream().filter(t -> t.kind().isStorage()).count(); }
    public long spawnerCount() { return targets.stream().filter(t -> t.kind().isSpawner()).count(); }
    /** Debris the server shows, without seed estimates. */
    public long debrisCount() { return targets.stream().filter(t -> t.kind().isDebris() && !t.flagged()).count(); }
    /** Chunks whose amethyst growth reaches the ActivityFinder threshold, nearest first. */
    public List<ActivityArea> activity() { return areas; }
    /** The horizontal reach ActivityFinder used for its last scan, in blocks. */
    public double activityRange() { return activityRange; }
    public ActivityStats activityStats() { return activityStats; }
    /** Chunks that look like someone's base, nearest first. */
    public List<BaseArea> bases() { return bases; }
    /** The horizontal reach BaseFinder used for its last scan, in blocks. */
    public double baseRange() { return baseRange; }
    public DebrisStats debrisStats() { return config.debris.enabled ? debris.stats() : DebrisStats.EMPTY; }
    /** Sections whose palette lists debris that anti-xray hid from their blocks, nearest first. */
    public List<SectionPos> debrisAreas() { return config.debris.enabled ? debris.areas() : List.of(); }
    /** Dug passages within TunnelFinder's range, nearest first. */
    public List<TunnelDetector.Segment> tunnels() { return tunnels; }
    /** ChunkFinder's base spots, best first and a clearance apart; empty when it is off or nothing qualifies. */
    public List<GoodChunk> goodChunks() { return goodChunks; }
    /** ChunkFinder's best spot, or null. */
    public GoodChunk bestGoodChunk() { return goodChunks.isEmpty() ? null : goodChunks.getFirst(); }
    /** The chunks ChunkFinder keeps spots away from, with why. */
    public List<ChunkSign> chunkSigns() { return chunkSigns; }
    /** Where to go to be ChunkFinder's RTP distance from every random teleport landing; null when already that far. */
    public Vec3 travelTarget() { return config.chunks.enabled ? travelTarget : null; }

    /** The random teleport landings on this server, in this dimension. */
    public List<RtpZones.Zone> rtpZones(Minecraft client) {
        if (client.level == null) return List.of();
        String server = serverName(client), dimension = client.level.dimension().identifier().toString();
        return config.rtpZones.stream().filter(zone -> zone.server().equals(server) && zone.dimension().equals(dimension)).toList();
    }

    /** Forgets this server's random teleport landings. */
    public void forgetRtpZones(Minecraft client) {
        String server = serverName(client);
        config.rtpZones.removeIf(zone -> zone.server().equals(server));
        travelTarget = null;
    }

    private static String serverName(Minecraft client) {
        if (client.getSingleplayerServer() != null) return SINGLEPLAYER;
        return client.getCurrentServer() != null ? client.getCurrentServer().ip : "unknown";
    }
    public ChunkStats chunkStats() { return config.chunks.enabled ? goodIndex.stats() : ChunkStats.EMPTY; }
    /** One line per enabled finder for the info HUD. */
    public List<FinderSummary> summaries() { return summaries; }

    public void clear() {
        targets = List.of();
        areas = List.of();
        tunnels = List.of();
        summaries = List.of();
        activityStats = ActivityStats.EMPTY;
        bases = List.of();
        baseIndex.clear();
        goodChunks = List.of();
        chunkSigns = List.of();
        travelTarget = null;
        goodIndex.clear();
        announcedSpot = steadySpot = null;
        lastLevel = null;
        ticks = 0;
        debris.clear();
        activity.clear();
        tunnelIndex.clear();
        revealed.clear();
        lastNotice = Long.MIN_VALUE / 2;
    }

    private void useLevel(ClientLevel level) {
        clear();
        lastLevel = level;
        spawnerFirstSight = spawnerSight.computeIfAbsent(level.dimension(), key -> new Long2BooleanOpenHashMap());
        goodIndex.useLevel(level);
    }

    private boolean anyEnabled() {
        return config.storage.enabled || config.spawner.enabled || config.debris.enabled || config.activity.enabled
                || config.tunnels.enabled || config.entities.enabled || config.bases.enabled || config.chunks.enabled;
    }

    public void tick(Minecraft client) {
        if (client.level == null || client.player == null) {
            clear();
            spawnerSight.clear();
            goodIndex.forget();
            rtpWatch.lost();
            rtpLevel = null;
            return;
        }
        followRtp(client);
        if (client.level != lastLevel) useLevel(client.level);
        // Where we stand, not the camera: the server random-ticks the chunks around the player.
        goodIndex.follow(client.level, client.player.chunkPosition());
        // In Freecam the finds follow the camera, which is where they are drawn.
        Vec3 origin = FreecamController.enabled() ? FreecamController.getInstance().position() : client.player.position();
        ChunkPos center = ChunkPos.containing(BlockPos.containing(origin));
        // The chunk indexes spread their block scans over every tick, so they run before the cadence check.
        // Hits and misses belong to the seed they were counted for.
        if (!config.debris.seed.equals(config.debris.seedCountsFor)) {
            config.debris.seedCountsFor = config.debris.seed;
            config.debris.seedHits = config.debris.seedMisses = 0;
        }
        if (config.debris.enabled) debris.tick(client.level, center, config.debris.range, debrisSeed(client));
        else debris.clear();
        activityRange = config.activity.allLoadedChunks ? loadedRange(client) : config.activity.range;
        if (config.activity.enabled) activity.tick(client.level, center, activityRange);
        else activity.clear();
        baseRange = config.bases.allLoadedChunks ? loadedRange(client) : config.bases.range;
        if (config.bases.enabled) baseIndex.tick(client.level, center, baseRange);
        else baseIndex.clear();
        if (config.chunks.enabled) goodIndex.tick(client.level, center, loadedRange(client));
        else goodIndex.clear();
        FinderSettings t = config.tunnels;
        tunnelIndex.configure((int) Math.round(t.minLength), t.tunnels, t.holes, t.staircases);
        // The index scans for every kind and length; turning one off only filters what it found.
        if (t.enabled) tunnelIndex.tick(client.level, center, t.range);
        else tunnelIndex.clear();
        announceRevealed(client);
        if (!anyEnabled()) {
            targets = List.of();
            areas = List.of();
            tunnels = List.of();
            summaries = List.of();
            activityStats = ActivityStats.EMPTY;
            goodChunks = List.of();
            chunkSigns = List.of();
            ticks = 0;
            return;
        }
        if (ticks++ % 10 != 0) return;
        List<FoundBlock> found = new ArrayList<>(), debrisFound = new ArrayList<>();
        if (config.storage.enabled || config.spawner.enabled) collectBlockEntities(client, center, origin, found);
        if (config.debris.enabled) {
            debris.collect(client.level, origin, config.debris.range, config.debris.filterFakes, config.debris.searchAreas,
                    estimatesTrusted(), debrisFound);
        }
        Comparator<FoundBlock> nearest = Comparator.comparingDouble(target -> target.pos().distToCenterSqr(origin));
        found.sort(nearest);
        debrisFound.sort(nearest);
        List<FoundBlock> all = new ArrayList<>(found.subList(0, Math.min(found.size(), MAX_TARGETS)));
        all.addAll(debrisFound.subList(0, Math.min(debrisFound.size(), MAX_TARGETS)));
        all.sort(nearest);
        targets = List.copyOf(all);

        List<ActivityArea> grown = new ArrayList<>();
        if (config.activity.enabled) activity.collect(origin, activityRange, config.activity.minActivity, grown);
        grown.sort(Comparator.comparingDouble(area -> area.horizontalDistance(origin)));
        areas = List.copyOf(grown);
        // Paper's extra worlds have their own dimension keys but the Overworld's type.
        boolean overworld = client.level.dimensionTypeRegistration().is(BuiltinDimensionTypes.OVERWORLD);
        activityStats = config.activity.enabled ? activity.stats(overworld, origin, activityRange) : ActivityStats.EMPTY;

        List<TunnelDetector.Segment> passages = new ArrayList<>();
        if (t.enabled) tunnelIndex.collect(origin, t.range, passages);
        passages.sort(Comparator.comparingDouble(segment -> TunnelIndex.distanceSqr(segment, origin)));
        tunnels = List.copyOf(passages.subList(0, Math.min(passages.size(), MAX_TARGETS)));

        List<BaseArea> suspicious = new ArrayList<>();
        if (config.bases.enabled) baseIndex.collect(client.level, origin, baseRange, config.bases.minBaseScore, suspicious);
        suspicious.sort(Comparator.comparingDouble(area -> area.horizontalDistance(origin)));
        bases = List.copyOf(suspicious);

        List<GoodChunk> spots = new ArrayList<>();
        List<ChunkSign> signs = new ArrayList<>();
        List<RtpZones.Zone> zones = rtpZones(client);
        if (config.chunks.enabled) goodIndex.collect(client.level, client.player, origin, config.chunks, zones, spots, signs);
        goodChunks = List.copyOf(spots);
        chunkSigns = List.copyOf(signs);
        var border = client.level.getWorldBorder();
        double[] target = config.chunks.enabled ? RtpZones.travelTarget(zones, client.player.getX(), client.player.getZ(),
                config.chunks.rtpDistance, new double[] {border.getMinX(), border.getMinZ(), border.getMaxX(), border.getMaxZ()}) : null;
        travelTarget = target == null ? null : new Vec3(target[0], client.player.getY(), target[1]);
        if (config.chunks.enabled) announceSpot(client);
        summaries = summarize(client, origin);
    }

    /** Estimates stop once spots the server revealed came up empty, or real debris keeps turning up elsewhere. */
    private boolean estimatesTrusted() {
        FinderSettings d = config.debris;
        return !DebrisStats.seedSpotsEmpty(d.seedHits, d.seedMisses) && !debris.stats().debrisMismatch();
    }

    /** Singleplayer knows its own seed; on a server, DebrisFinder uses the one typed in its settings. */
    private OptionalLong debrisSeed(Minecraft client) {
        if (!config.debris.seedEstimates) return OptionalLong.empty();
        var server = client.getSingleplayerServer();
        return server != null ? OptionalLong.of(server.overworld().getSeed()) : DebrisPredictor.parseSeed(config.debris.seed);
    }

    /** Reaches every corner of the client's chunk cache, which holds the render distance plus a margin. */
    private static double loadedRange(Minecraft client) {
        int radius = Math.max(2, client.options.getEffectiveRenderDistance()) + 3;
        return (radius * Math.sqrt(2) + 1) * 16;
    }

    private void collectBlockEntities(Minecraft client, ChunkPos center, Vec3 origin, List<FoundBlock> found) {
        double range = Math.max(config.storage.enabled ? config.storage.range : 0,
                config.spawner.enabled ? config.spawner.range : 0);
        int radius = (int) Math.ceil(range / 16.0);
        int centerX = center.x();
        int centerZ = center.z();
        for (int x = centerX - radius; x <= centerX + radius; x++) {
            for (int z = centerZ - radius; z <= centerZ + radius; z++) {
                var chunk = client.level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
                if (chunk == null) continue;
                for (var entity : chunk.getBlockEntities().values()) {
                    if (entity.isRemoved()) continue;
                    BlockState state = entity.getBlockState();
                    TargetKind kind = TargetKind.from(state);
                    if (kind == null) continue;
                    FinderSettings settings = kind.settings(config);
                    if (!settings.enabled || !kind.included(settings)) continue;
                    // A double chest is one find; the renderer draws both halves as one box.
                    if (state.getBlock() instanceof ChestBlock && state.getValue(ChestBlock.TYPE) == ChestType.RIGHT) continue;
                    var pos = entity.getBlockPos();
                    double distanceSqr = pos.distToCenterSqr(origin);
                    if (distanceSqr <= settings.range * settings.range) {
                        boolean activated = kind.isSpawner() && activated(entity, client.player.distanceToSqr(Vec3.atCenterOf(pos)));
                        found.add(new FoundBlock(pos.immutable(), kind, activated));
                    }
                }
            }
        }
    }

    private boolean activated(BlockEntity entity, double playerDistanceSqr) {
        long key = entity.getBlockPos().asLong();
        if (spawnerFirstSight.containsKey(key)) return spawnerFirstSight.get(key);
        boolean activated = firstSight(entity, playerDistanceSqr);
        spawnerFirstSight.put(key, activated);
        return activated;
    }

    /**
     * Worldgen spawners keep a timer of 20 ticks until a player comes within range (16 blocks). A higher value means
     * the timer was reset, which only a nearby player causes; a lower one that it ran, which our own presence can do
     * too. Spawners are normally read as their chunk arrives, before we can have run them. A trial spawner shows a
     * cleared trial, or an ominous one, in its block state.
     */
    private static boolean firstSight(BlockEntity entity, double playerDistanceSqr) {
        if (entity instanceof SpawnerBlockEntity spawner) {
            BaseSpawnerAccessor timer = (BaseSpawnerAccessor) spawner.getSpawner();
            int delay = timer.donut$getSpawnDelay();
            double range = timer.donut$getRequiredPlayerRange() + 1;
            return delay > NATURAL_SPAWN_DELAY || delay != NATURAL_SPAWN_DELAY && playerDistanceSqr > range * range;
        }
        if (entity instanceof TrialSpawnerBlockEntity) {
            BlockState state = entity.getBlockState();
            return state.getValue(TrialSpawnerBlock.STATE) == TrialSpawnerState.COOLDOWN || state.getValue(TrialSpawnerBlock.OMINOUS);
        }
        return false;
    }

    /** The timers exactly as the server sent them; no client tick has run them yet. */
    private void recordSpawners(LevelChunk chunk) {
        var player = Minecraft.getInstance().player;
        for (var entity : chunk.getBlockEntities().values()) {
            if (!(entity instanceof SpawnerBlockEntity) && !(entity instanceof TrialSpawnerBlockEntity)) continue;
            double distanceSqr = player == null ? Double.MAX_VALUE : player.distanceToSqr(Vec3.atCenterOf(entity.getBlockPos()));
            spawnerFirstSight.putIfAbsent(entity.getBlockPos().asLong(), firstSight(entity, distanceSqr));
        }
    }

    private List<FinderSummary> summarize(Minecraft client, Vec3 origin) {
        List<FinderSummary> lines = new ArrayList<>();
        if (config.storage.enabled) {
            int carts = 0;
            Vec3 nearestCart = null;
            if (config.storage.minecarts) {
                for (Entity entity : client.level.entitiesForRendering()) {
                    if (EntityKind.of(entity) != EntityKind.STORAGE_MINECART
                            || entity.distanceToSqr(origin) > config.storage.range * config.storage.range) continue;
                    carts++;
                    if (nearestCart == null || entity.distanceToSqr(origin) < nearestCart.distanceToSqr(origin)) nearestCart = entity.position();
                }
            }
            lines.add(blockSummary("Storage", TargetKind::isStorage, config.storage.color, carts, nearestCart, origin));
        }
        if (config.spawner.enabled) {
            long activated = config.spawner.activatedSpawners
                    ? targets.stream().filter(t -> t.kind().isSpawner() && t.flagged()).count() : 0;
            FinderSummary spawners = blockSummary("Spawners", TargetKind::isSpawner, config.spawner.color, 0, null, origin);
            lines.add(activated == 0 ? spawners : new FinderSummary(spawners.name(), spawners.count(), spawners.nearest(),
                    spawners.color(), activated + " activated"));
        }
        if (config.debris.enabled) {
            DebrisStats stats = debris.stats();
            // The nearest estimate is worth heading for too, but the count is debris the server shows.
            FinderSummary found = blockSummary("Debris", TargetKind::isDebris, config.debris.color, 0, null, origin);
            boolean warn = stats.bedrockMismatch() || stats.oresRandomized() || stats.seedMismatch()
                    || stats.offSeedChunks() > 0 && stats.seedChunks() == 0;
            int areaCount = config.debris.searchAreas ? stats.searchAreas() : 0;
            int hits = config.debris.seedHits, judged = hits + config.debris.seedMisses;
            boolean empty = config.debris.seedEstimates && DebrisStats.seedSpotsEmpty(hits, config.debris.seedMisses);
            warn |= empty;
            String note = stats.bedrockMismatch() ? "seed is not this Nether's" : stats.oresRandomized() ? "server randomizes debris"
                    : empty ? "seed spots came up empty (" + hits + " of " + judged + ")"
                    : stats.seedMismatch() ? "seed doesn't match" : stats.offSeedChunks() > 0 && stats.seedChunks() == 0 ? "area not from seed"
                    : stats.estimates() > 0 ? "+" + stats.estimates() + " by seed"
                    : areaCount > 0 ? areaCount + (areaCount == 1 ? " search area" : " search areas")
                    : stats.allDugOut() ? "seed spots dug out"
                    : stats.hiddenFakes() > 0 ? "anti-xray fakes hidden"
                    : stats.fakedChunks() > 0 && !config.debris.filterFakes ? "incl. anti-xray fakes"
                    : stats.likelyHidden() ? "hidden until mined" : "";
            lines.add(new FinderSummary(found.name(), (int) debrisCount(), found.nearest(), found.color(), note, warn));
        }
        if (config.tunnels.enabled) {
            Vec3 nearest = tunnels.isEmpty() ? null : TunnelIndex.nearestPoint(tunnels.getFirst(), origin);
            lines.add(new FinderSummary("Tunnels", tunnels.size(), nearest, config.tunnels.color, ""));
        }
        if (config.activity.enabled) {
            Vec3 nearest = areas.isEmpty() ? null : Vec3.atCenterOf(areas.getFirst().chunk().getMiddleBlockPosition(areas.getFirst().surfaceY()));
            ActivityStats stats = activityStats;
            String note = !stats.overworld() ? "not Overworld"
                    : stats.best() != null && stats.best().score() > 0 ? "best " + Math.round(stats.best().score() * 100) + "%"
                    : stats.best() != null ? "no growth beyond worldgen"
                    : stats.scannedChunks() > 0 ? "no geodes nearby" : "";
            lines.add(new FinderSummary("Activity", areas.size(), nearest, config.activity.color, note, !stats.overworld()));
        }
        if (config.bases.enabled) {
            Vec3 nearest = bases.isEmpty() ? null : bases.getFirst().center();
            int best = BaseIndex.best(bases);
            lines.add(new FinderSummary("Bases", bases.size(), nearest, config.bases.color, best == 0 ? "" : "best " + best));
        }
        if (config.chunks.enabled) {
            GoodChunk best = bestGoodChunk();
            ChunkStats stats = goodIndex.stats();
            boolean travel = best == null && travelTarget != null;
            String note = !stats.overworld() ? "not Overworld"
                    : best != null ? "clear " + best.clearance() + ", unseen " + GoodChunkIndex.duration(best.unseenMinutes())
                    : travel ? "leave the RTP zone: " + Math.round(Math.hypot(travelTarget.x - origin.x, travelTarget.z - origin.z)) + " m"
                    : stats.scanned() < 9 ? ""
                    : stats.roomy() == 0 ? (stats.signs() > 0 ? "signs all around" : "clearance past view")
                    : stats.solid() == 0 ? "too much cave below"
                    : stats.withClocks() == 0 ? "no plants to judge by" : "too few plants to judge by";
            Vec3 toward = best != null ? best.center() : travel ? travelTarget : null;
            lines.add(new FinderSummary("Chunks", goodChunks.size(), toward, config.chunks.color, note, !stats.overworld()));
        }
        if (config.entities.enabled) {
            int count = 0;
            Entity nearest = null, nearestPlayer = null;
            double rangeSqr = config.entities.range * config.entities.range;
            for (Entity entity : client.level.entitiesForRendering()) {
                EntityKind kind = EntityKind.of(entity);
                if (kind == null || kind == EntityKind.STORAGE_MINECART || !kind.included(config.entities)) continue;
                double distanceSqr = entity.distanceToSqr(origin);
                if (distanceSqr > rangeSqr) continue;
                count++;
                if (nearest == null || distanceSqr < nearest.distanceToSqr(origin)) nearest = entity;
                if (kind == EntityKind.PLAYER && (nearestPlayer == null || distanceSqr < nearestPlayer.distanceToSqr(origin))) {
                    nearestPlayer = entity;
                }
            }
            // A player is what the line is for; a pig in between must not hide one.
            Entity shown = nearestPlayer != null ? nearestPlayer : nearest;
            lines.add(new FinderSummary("Entities", count, shown == null ? null : shown.position(), config.entities.color,
                    shown == null ? "" : shown.getName().getString()));
        }
        return List.copyOf(lines);
    }

    private FinderSummary blockSummary(String name, java.util.function.Predicate<TargetKind> filter, int color,
                                       int extra, Vec3 extraNearest, Vec3 origin) {
        int count = extra;
        Vec3 nearest = extraNearest;
        for (FoundBlock target : targets) {
            if (!filter.test(target.kind())) continue;
            count++;
            Vec3 center = Vec3.atCenterOf(target.pos());
            if (nearest == null || center.distanceToSqr(origin) < nearest.distanceToSqr(origin)) nearest = center;
        }
        return new FinderSummary(name, count, nearest, color, "");
    }

    static Vec3 center(TunnelDetector.Segment segment) {
        int middle = segment.length() / 2;
        return new Vec3(segment.x() + segment.dx() * middle + 0.5, segment.y() + segment.dy() * middle + segment.cellHeight() / 2.0,
                segment.z() + segment.dz() * middle + 0.5);
    }

    @Override
    public void blockChanged(ClientLevel level, BlockPos pos, BlockState previous, BlockState current) {
        if (level != lastLevel) return;
        boolean debrisChanged = previous.is(Blocks.ANCIENT_DEBRIS) || current.is(Blocks.ANCIENT_DEBRIS);
        if (config.debris.enabled && debrisChanged) debris.markDirty(pos);
        if (config.debris.enabled && previous.is(Blocks.ANCIENT_DEBRIS) && !current.is(Blocks.ANCIENT_DEBRIS)) debris.debrisRemoved(pos);
        if (config.tunnels.enabled && TunnelIndex.matters(previous, current)) tunnelIndex.markDirty(pos);
        if (config.bases.enabled && (BaseEvidence.ANY.test(previous) || BaseEvidence.ANY.test(current))) {
            baseIndex.markDirty(SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ()));
        }
        // Built blocks and amethyst are read again; plants and caves keep their first reading.
        if (config.chunks.enabled && (BuiltBlocks.built(previous) || BuiltBlocks.built(current)
                || amethyst(previous) || amethyst(current))) {
            goodIndex.markDirty(SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ()));
        }
        if (config.chunks.enabled && TunnelIndex.matters(previous, current)) goodIndex.passageChanged(pos);
        if (config.activity.enabled && (amethyst(previous) || amethyst(current))) {
            // A bud on the chunk's edge can belong to budding amethyst next door.
            int chunkX = SectionPos.blockToSectionCoord(pos.getX()), chunkZ = SectionPos.blockToSectionCoord(pos.getZ());
            int x = pos.getX() & 15, z = pos.getZ() & 15;
            activity.markDirty(chunkX, chunkZ);
            if (x == 0) activity.markDirty(chunkX - 1, chunkZ);
            if (x == 15) activity.markDirty(chunkX + 1, chunkZ);
            if (z == 0) activity.markDirty(chunkX, chunkZ - 1);
            if (z == 15) activity.markDirty(chunkX, chunkZ + 1);
        }
    }

    private static boolean amethyst(BlockState state) {
        return state.is(Blocks.BUDDING_AMETHYST) || state.is(Blocks.SMALL_AMETHYST_BUD) || state.is(Blocks.MEDIUM_AMETHYST_BUD)
                || state.is(Blocks.LARGE_AMETHYST_BUD) || state.is(Blocks.AMETHYST_CLUSTER);
    }

    @Override
    public void serverBlock(ClientLevel level, BlockPos pos, BlockState state) {
        if (level != lastLevel || !config.debris.enabled) return;
        boolean isDebris = state.is(Blocks.ANCIENT_DEBRIS);
        // This runs before the update applies, so the level still shows what the client had.
        boolean alreadyShown = level.getBlockState(pos).is(Blocks.ANCIENT_DEBRIS) && !debris.inFakedChunk(pos);
        switch (debris.reveal(pos, state)) {
            case HIT -> config.debris.seedHits++;
            case MISS -> config.debris.seedMisses++;
            case NONE -> { }
        }
        // Anti-xray sends the real block once a neighbor is mined; a newly confirmed debris was just revealed. Paper
        // also resends debris that was in plain sight, which is no news.
        if (debris.confirm(pos, isDebris)) {
            debris.markDirty(pos);
            if (!alreadyShown && revealed.size() < 64) revealed.add(pos.immutable());
        }
    }

    @Override
    public void chunkLoaded(ClientLevel level, ChunkPos pos) {
        // A new world's first chunks arrive before the first tick sees it.
        if (level != lastLevel) useLevel(level);
        LevelChunk chunk = level.getChunkSource().getChunk(pos.x(), pos.z(), ChunkStatus.FULL, false);
        if (chunk != null) recordSpawners(chunk);
        // Chunk data can be resent in place, which keeps the same chunk object.
        if (config.debris.enabled) {
            debris.chunkReloaded(level, pos);
            debris.markDirty(pos);
            // Debris on the edge next to this chunk read as exposed while it was missing.
            debris.markDirty(new ChunkPos(pos.x() - 1, pos.z()));
            debris.markDirty(new ChunkPos(pos.x() + 1, pos.z()));
            debris.markDirty(new ChunkPos(pos.x(), pos.z() - 1));
            debris.markDirty(new ChunkPos(pos.x(), pos.z() + 1));
        }
        if (config.tunnels.enabled) tunnelIndex.markDirty(pos);
        if (config.bases.enabled) baseIndex.markDirty(pos.x(), pos.z());
        if (config.chunks.enabled) goodIndex.chunkLoaded(pos);
        if (config.activity.enabled) {
            // Faces on the edges next to this chunk read as missing until it loads.
            activity.markDirty(pos.x(), pos.z());
            activity.markDirty(pos.x() - 1, pos.z());
            activity.markDirty(pos.x() + 1, pos.z());
            activity.markDirty(pos.x(), pos.z() - 1);
            activity.markDirty(pos.x(), pos.z() + 1);
        }
    }

    /**
     * Notices random teleports: a long jump, or a new world, within a while of sending the rtp command. The landing is
     * kept, and with ChunkFinder on, where to go to be far enough from it is said in chat.
     */
    private void followRtp(Minecraft client) {
        rtpTicks++;
        // Singleplayer worlds share no /rtp: a landing noted in one means nothing in the next.
        Object world = client.getSingleplayerServer();
        if (world != rtpWorld) {
            rtpWorld = world;
            if (world != null) config.rtpZones.removeIf(zone -> zone.server().equals(SINGLEPLAYER));
        }
        boolean newLevel = rtpLevel != null && client.level != rtpLevel;
        rtpLevel = client.level;
        double[] landing = rtpWatch.tick(rtpTicks, client.player.getX(), client.player.getZ(), newLevel);
        if (landing == null) return;
        String server = serverName(client), dimension = client.level.dimension().identifier().toString();
        int x = (int) Math.floor(landing[0]), z = (int) Math.floor(landing[1]);
        boolean known = config.rtpZones.stream().anyMatch(zone -> zone.server().equals(server) && zone.dimension().equals(dimension)
                && Math.hypot(zone.x() - x, zone.z() - z) < 64);
        if (!known) {
            config.rtpZones.add(new RtpZones.Zone(server, dimension, x, z));
            if (config.rtpZones.size() > ClientConfig.MAX_RTP_ZONES) config.rtpZones.removeFirst();
        }
        if (!config.chunks.enabled || config.chunks.rtpDistance <= 0) return;
        var border = client.level.getWorldBorder();
        double[] target = RtpZones.travelTarget(rtpZones(client), x, z, config.chunks.rtpDistance,
                new double[] {border.getMinX(), border.getMinZ(), border.getMaxX(), border.getMaxZ()});
        long distance = Math.round(config.chunks.rtpDistance);
        client.player.sendSystemMessage(Component.literal("[Donut] RTP landing at " + x + ", " + z + " noted: good chunks are "
                + distance + "+ blocks from it" + (target == null ? "." : ". Head for " + Math.round(target[0]) + ", "
                + Math.round(target[1]) + "; the info panel points the way.")).withStyle(ChatFormatting.GREEN));
    }

    /** A chat line with the coordinates, once per area: a new best spot more than a clearance from the last one. */
    private void announceSpot(Minecraft client) {
        GoodChunk best = bestGoodChunk();
        steadyPasses = best != null && best.chunk().equals(steadySpot) ? steadyPasses + 1 : 0;
        steadySpot = best == null ? null : best.chunk();
        // Four passes are two seconds.
        if (best == null || !config.chunks.notify || steadyPasses < 4) return;
        int spacing = (int) Math.round(config.chunks.minClearance);
        if (announcedSpot != null && Math.max(Math.abs(announcedSpot.x() - best.chunk().x()),
                Math.abs(announcedSpot.z() - best.chunk().z())) <= spacing) return;
        announcedSpot = best.chunk();
        client.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_CHIME.value(), 1.2f, 0.8f));
        BlockPos center = best.surfaceCenter();
        client.player.sendSystemMessage(Component.literal("[Donut] Good chunk at " + center.getX() + ", " + center.getZ() + ": "
                + best.clearance() + " clear chunks around it; a player there for " + unseen(best.unseenMinutes())
                + " would have left growth").withStyle(ChatFormatting.GREEN));
    }

    private static String unseen(double minutes) {
        return minutes < 1 ? "under a minute" : minutes < 90 ? Math.round(minutes) + " min" : Math.round(minutes / 60) + " h";
    }

    private void announceRevealed(Minecraft client) {
        if (revealed.isEmpty()) return;
        if (!config.debris.enabled || !config.debris.notify) { revealed.clear(); return; }
        Vec3 origin = client.player.position();
        BlockPos nearest = null;
        int count = 0;
        for (BlockPos pos : revealed) {
            if (pos.distToCenterSqr(origin) > config.debris.range * config.debris.range) continue;
            count++;
            if (nearest == null || pos.distToCenterSqr(origin) < nearest.distToCenterSqr(origin)) nearest = pos;
        }
        revealed.clear();
        if (nearest == null) return;
        long now = Util.getMillis();
        // A whole vein can be revealed by one mined block; one ping is enough.
        if (now - lastNotice >= 500) {
            client.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING.value(), 1.6f, 0.9f));
            lastNotice = now;
        }
        int distance = (int) Math.round(Math.sqrt(nearest.distToCenterSqr(origin)));
        client.player.sendOverlayMessage(Component.literal((count == 1 ? "Ancient debris" : count + " ancient debris")
                + " revealed " + distance + " blocks away (" + nearest.getX() + ", " + nearest.getY() + ", " + nearest.getZ() + ")")
                .withStyle(ChatFormatting.LIGHT_PURPLE));
    }
}
