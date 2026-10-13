package dev.donut.client.finder;

import dev.donut.client.config.ClientConfig;
import dev.donut.client.config.FinderSettings;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;

/** Reads only blocks and block entities already sent to the client; never requests chunks. */
public final class FinderScanner {
    public static final int MAX_TARGETS = 4096;
    private final ClientConfig config;
    private final DebrisIndex debris = new DebrisIndex();
    private final ActivityIndex activity = new ActivityIndex();
    private List<FoundBlock> targets = List.of();
    private List<ActivityArea> areas = List.of();
    private double activityRange;
    private ActivityStats activityStats = ActivityStats.EMPTY;
    private ClientLevel lastLevel;
    private int ticks;

    public FinderScanner(ClientConfig config) { this.config = config; }
    public List<FoundBlock> snapshot() { return targets; }
    public long storageCount() { return targets.stream().filter(t -> t.kind().isStorage()).count(); }
    public long spawnerCount() { return targets.stream().filter(t -> t.kind().isSpawner()).count(); }
    public long debrisCount() { return targets.stream().filter(t -> t.kind().isDebris()).count(); }
    /** Chunks whose amethyst growth reaches the ActivityFinder threshold, nearest first. */
    public List<ActivityArea> activity() { return areas; }
    /** The horizontal reach ActivityFinder used for its last scan, in blocks. */
    public double activityRange() { return activityRange; }
    public ActivityStats activityStats() { return activityStats; }

    public void clear() {
        targets = List.of();
        areas = List.of();
        activityStats = ActivityStats.EMPTY;
        lastLevel = null;
        ticks = 0;
        debris.clear();
        activity.clear();
    }

    public void tick(Minecraft client) {
        if (client.level == null || client.player == null) { clear(); return; }
        if (client.level != lastLevel) {
            clear();
            lastLevel = client.level;
        }
        // The chunk indexes spread their block scans over every tick, so they run before the cadence check.
        if (config.debris.enabled) debris.tick(client.level, client.player.chunkPosition(), config.debris.range);
        else debris.clear();
        activityRange = config.activity.allLoadedChunks ? loadedRange(client) : config.activity.range;
        if (config.activity.enabled) activity.tick(client.level, client.player.chunkPosition(), activityRange);
        else activity.clear();
        if (!config.storage.enabled && !config.spawner.enabled && !config.debris.enabled && !config.activity.enabled) {
            targets = List.of();
            areas = List.of();
            activityStats = ActivityStats.EMPTY;
            ticks = 0;
            return;
        }
        if (ticks++ % 10 != 0) return;
        var origin = client.player.position();
        List<FoundBlock> found = new ArrayList<>();
        if (config.storage.enabled || config.spawner.enabled) collectBlockEntities(client, origin, found);
        if (config.debris.enabled) debris.collect(client.level, origin, config.debris.range, found);
        found.sort(Comparator.comparingDouble(target -> target.pos().distToCenterSqr(origin)));
        targets = List.copyOf(found.subList(0, Math.min(found.size(), MAX_TARGETS)));

        List<ActivityArea> grown = new ArrayList<>();
        if (config.activity.enabled) activity.collect(origin, activityRange, config.activity.minActivity, grown);
        grown.sort(Comparator.comparingDouble(area -> area.horizontalDistance(origin)));
        areas = List.copyOf(grown);
        activityStats = config.activity.enabled
                ? activity.stats(client.level.dimension() == Level.OVERWORLD, origin, activityRange) : ActivityStats.EMPTY;
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
        int centerX = client.player.chunkPosition().x;
        int centerZ = client.player.chunkPosition().z;
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
                    if (pos.distToCenterSqr(origin) <= settings.range * settings.range) {
                        found.add(new FoundBlock(pos.immutable(), kind));
                    }
                }
            }
        }
    }
}
