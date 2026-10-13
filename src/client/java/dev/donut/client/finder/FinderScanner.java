package dev.donut.client.finder;

import dev.donut.client.config.ClientConfig;
import dev.donut.client.config.FinderSettings;
import dev.donut.client.mixin.BaseSpawnerAccessor;
import it.unimi.dsi.fastutil.longs.Long2BooleanOpenHashMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalLong;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;

/** Reads only blocks and block entities already sent to the client; never requests chunks. */
public final class FinderScanner implements ChunkEvents.Listener {
    public static final int MAX_TARGETS = 4096;
    /** A spawner's timer starts at this value and only runs while a player is within its range. */
    private static final int NATURAL_SPAWN_DELAY = 20;
    private static final double SPAWNER_PLAYER_RANGE = 17;
    private static FinderScanner instance;

    private final ClientConfig config;
    private final DebrisIndex debris = new DebrisIndex();
    private final ActivityIndex activity = new ActivityIndex();
    private final TunnelIndex tunnelIndex = new TunnelIndex();
    /** Whether each spawner looked activated the first time it was seen, before our own presence could run its timer. */
    private final Long2BooleanOpenHashMap spawnerFirstSight = new Long2BooleanOpenHashMap();
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
    public DebrisStats debrisStats() { return config.debris.enabled ? debris.stats() : DebrisStats.EMPTY; }
    /** Sections whose palette lists debris that anti-xray hid from their blocks, nearest first. */
    public List<SectionPos> debrisAreas() { return config.debris.enabled ? debris.areas() : List.of(); }
    /** Dug passages within TunnelFinder's range, nearest first. */
    public List<TunnelDetector.Segment> tunnels() { return tunnels; }
    /** One line per enabled finder for the info HUD. */
    public List<FinderSummary> summaries() { return summaries; }

    public void clear() {
        targets = List.of();
        areas = List.of();
        tunnels = List.of();
        summaries = List.of();
        activityStats = ActivityStats.EMPTY;
        lastLevel = null;
        ticks = 0;
        debris.clear();
        activity.clear();
        tunnelIndex.clear();
        spawnerFirstSight.clear();
        revealed.clear();
    }

    private boolean anyEnabled() {
        return config.storage.enabled || config.spawner.enabled || config.debris.enabled || config.activity.enabled
                || config.tunnels.enabled || config.entities.enabled;
    }

    public void tick(Minecraft client) {
        if (client.level == null || client.player == null) { clear(); return; }
        if (client.level != lastLevel) {
            clear();
            lastLevel = client.level;
        }
        ChunkPos center = client.player.chunkPosition();
        // The chunk indexes spread their block scans over every tick, so they run before the cadence check.
        if (config.debris.enabled) debris.tick(client.level, center, config.debris.range, debrisSeed(client));
        else debris.clear();
        activityRange = config.activity.allLoadedChunks ? loadedRange(client) : config.activity.range;
        if (config.activity.enabled) activity.tick(client.level, center, activityRange);
        else activity.clear();
        FinderSettings t = config.tunnels;
        tunnelIndex.configure((int) Math.round(t.minLength), t.tunnels, t.holes, t.staircases);
        if (t.enabled) tunnelIndex.tick(client.level, center, t.range);
        else tunnelIndex.clear();
        announceRevealed(client);
        if (!anyEnabled()) {
            targets = List.of();
            areas = List.of();
            tunnels = List.of();
            summaries = List.of();
            activityStats = ActivityStats.EMPTY;
            ticks = 0;
            return;
        }
        if (ticks++ % 10 != 0) return;
        var origin = client.player.position();
        List<FoundBlock> found = new ArrayList<>();
        if (config.storage.enabled || config.spawner.enabled) collectBlockEntities(client, origin, found);
        if (config.debris.enabled) debris.collect(client.level, origin, config.debris.range, config.debris.filterFakes, found);
        found.sort(Comparator.comparingDouble(target -> target.pos().distToCenterSqr(origin)));
        targets = List.copyOf(found.subList(0, Math.min(found.size(), MAX_TARGETS)));

        List<ActivityArea> grown = new ArrayList<>();
        if (config.activity.enabled) activity.collect(origin, activityRange, config.activity.minActivity, grown);
        grown.sort(Comparator.comparingDouble(area -> area.horizontalDistance(origin)));
        areas = List.copyOf(grown);
        activityStats = config.activity.enabled
                ? activity.stats(client.level.dimension() == Level.OVERWORLD, origin, activityRange) : ActivityStats.EMPTY;

        List<TunnelDetector.Segment> passages = new ArrayList<>();
        if (t.enabled) tunnelIndex.collect(origin, t.range, passages);
        passages.sort(Comparator.comparingDouble(segment -> TunnelIndex.distanceSqr(segment, origin)));
        tunnels = List.copyOf(passages.subList(0, Math.min(passages.size(), MAX_TARGETS)));
        summaries = summarize(client, origin);
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

    private void collectBlockEntities(Minecraft client, Vec3 origin, List<FoundBlock> found) {
        double range = Math.max(config.storage.enabled ? config.storage.range : 0,
                config.spawner.enabled ? config.spawner.range : 0);
        int radius = (int) Math.ceil(range / 16.0);
        int centerX = client.player.chunkPosition().x();
        int centerZ = client.player.chunkPosition().z();
        for (int x = centerX - radius; x <= centerX + radius; x++) {
            for (int z = centerZ - radius; z <= centerZ + radius; z++) {
                var chunk = client.level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
                if (chunk == null) continue;
                for (var entity : chunk.getBlockEntities().values()) {
                    if (entity.isRemoved()) continue;
                    TargetKind kind = TargetKind.from(entity.getBlockState());
                    if (kind == null) continue;
                    FinderSettings settings = kind.settings(config);
                    if (!settings.enabled || !kind.included(settings)) continue;
                    var pos = entity.getBlockPos();
                    double distanceSqr = pos.distToCenterSqr(origin);
                    if (distanceSqr <= settings.range * settings.range) {
                        boolean activated = entity instanceof SpawnerBlockEntity spawner
                                && activated(pos, spawner, distanceSqr);
                        found.add(new FoundBlock(pos.immutable(), kind, activated));
                    }
                }
            }
        }
    }

    /**
     * Worldgen spawners keep a timer of 20 ticks until a player comes within 16 blocks. Any other value
     * means someone spent time next to it, or placed it. Spawners are normally read as their chunk arrives,
     * before our own presence can run the timer. One seen only later counts when its timer was reset,
     * which only a nearby player causes, or ran down while we were too far away to cause it.
     */
    private boolean activated(BlockPos pos, SpawnerBlockEntity spawner, double distanceSqr) {
        long key = pos.asLong();
        if (spawnerFirstSight.containsKey(key)) return spawnerFirstSight.get(key);
        int delay = spawnDelay(spawner);
        boolean activated = delay > NATURAL_SPAWN_DELAY
                || delay != NATURAL_SPAWN_DELAY && distanceSqr > SPAWNER_PLAYER_RANGE * SPAWNER_PLAYER_RANGE;
        spawnerFirstSight.put(key, activated);
        return activated;
    }

    private static int spawnDelay(SpawnerBlockEntity spawner) {
        return ((BaseSpawnerAccessor) spawner.getSpawner()).donut$getSpawnDelay();
    }

    /** The timers exactly as the server sent them; no client tick has run them yet. */
    private void recordSpawners(LevelChunk chunk) {
        for (var entity : chunk.getBlockEntities().values()) {
            if (entity instanceof SpawnerBlockEntity spawner) {
                spawnerFirstSight.putIfAbsent(entity.getBlockPos().asLong(), spawnDelay(spawner) != NATURAL_SPAWN_DELAY);
            }
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
            long activated = targets.stream().filter(FoundBlock::flagged).count();
            FinderSummary spawners = blockSummary("Spawners", TargetKind::isSpawner, config.spawner.color, 0, null, origin);
            lines.add(activated == 0 ? spawners : new FinderSummary(spawners.name(), spawners.count(), spawners.nearest(),
                    spawners.color(), activated + " activated"));
        }
        if (config.debris.enabled) {
            DebrisStats stats = debris.stats();
            // The nearest estimate is worth heading for too, but the count is debris the server shows.
            FinderSummary found = blockSummary("Debris", TargetKind::isDebris, config.debris.color, 0, null, origin);
            String note = stats.bedrockMismatch() ? "seed is not this Nether's" : stats.oresRandomized() ? "server randomizes debris"
                    : stats.seedMismatch() ? "seed doesn't match" : stats.offSeedChunks() > 0 && stats.seedChunks() == 0 ? "area not from seed"
                    : stats.estimates() > 0 ? "+" + stats.estimates() + " by seed" : stats.searchAreas() > 0 ? stats.searchAreas() + " search areas"
                    : stats.seedChunks() > 0 && stats.dugOut() > 0 ? "seed spots dug out" : stats.estimates() > 0 ? "+" + stats.estimates() + " by seed"
                    : stats.fakedChunks() > 0 ? "anti-xray fakes hidden" : stats.likelyHidden() ? "hidden until mined" : "";
            lines.add(new FinderSummary(found.name(), (int) debrisCount(), found.nearest(), found.color(), note));
        }
        if (config.tunnels.enabled) {
            Vec3 nearest = tunnels.isEmpty() ? null : center(tunnels.getFirst());
            lines.add(new FinderSummary("Tunnels", tunnels.size(), nearest, config.tunnels.color, ""));
        }
        if (config.activity.enabled) {
            Vec3 nearest = areas.isEmpty() ? null : Vec3.atCenterOf(areas.getFirst().chunk().getMiddleBlockPosition(areas.getFirst().surfaceY()));
            lines.add(new FinderSummary("Activity", areas.size(), nearest, config.activity.color, ""));
        }
        if (config.entities.enabled) {
            int count = 0;
            Vec3 nearest = null;
            String nearestName = "";
            double rangeSqr = config.entities.range * config.entities.range;
            for (Entity entity : client.level.entitiesForRendering()) {
                EntityKind kind = EntityKind.of(entity);
                if (kind == null || kind == EntityKind.STORAGE_MINECART || !kind.included(config.entities)) continue;
                double distanceSqr = entity.distanceToSqr(origin);
                if (distanceSqr > rangeSqr) continue;
                count++;
                if (nearest == null || distanceSqr < nearest.distanceToSqr(origin)) {
                    nearest = entity.position();
                    nearestName = kind == EntityKind.PLAYER ? entity.getName().getString() : "";
                }
            }
            lines.add(new FinderSummary("Entities", count, nearest, config.entities.color, nearestName));
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
        if (config.tunnels.enabled) tunnelIndex.markDirty(pos);
    }

    @Override
    public void serverBlock(ClientLevel level, BlockPos pos, BlockState state) {
        if (level != lastLevel || !config.debris.enabled) return;
        boolean isDebris = state.is(Blocks.ANCIENT_DEBRIS);
        // Anti-xray sends the real block once a neighbor is mined; a newly confirmed debris was just revealed.
        if (debris.confirm(pos, isDebris)) {
            debris.markDirty(pos);
            if (revealed.size() < 64) revealed.add(pos.immutable());
        }
    }

    @Override
    public void chunkLoaded(ClientLevel level, ChunkPos pos) {
        // A new world's first chunks arrive before the first tick sees it.
        if (level != lastLevel) {
            clear();
            lastLevel = level;
        }
        LevelChunk chunk = level.getChunkSource().getChunk(pos.x(), pos.z(), ChunkStatus.FULL, false);
        if (chunk != null) recordSpawners(chunk);
        // Chunk data can be resent in place, which keeps the same chunk object.
        if (config.debris.enabled) debris.markDirty(pos);
        if (config.tunnels.enabled) tunnelIndex.markDirty(pos);
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
        long now = client.level.getGameTime();
        // A whole vein can be revealed by one mined block; one ping is enough.
        if (now - lastNotice >= 10) {
            client.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING.value(), 1.6f, 0.9f));
            lastNotice = now;
        }
        int distance = (int) Math.round(Math.sqrt(nearest.distToCenterSqr(origin)));
        client.player.sendOverlayMessage(Component.literal((count == 1 ? "Ancient debris" : count + " ancient debris")
                + " revealed " + distance + " blocks away (" + nearest.getX() + ", " + nearest.getY() + ", " + nearest.getZ() + ")")
                .withStyle(ChatFormatting.LIGHT_PURPLE));
    }
}
