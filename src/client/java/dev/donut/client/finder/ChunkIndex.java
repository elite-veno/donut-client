package dev.donut.client.finder;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * Caches one scan result per loaded chunk for searches that must read block data directly.
 * Scans are spread over ticks, nearest chunks first.
 */
final class ChunkIndex<T> {
    /** Indexed chunks are rescanned so block updates appear without reloading. */
    private static final int REFRESH_TICKS = 40;

    private final Function<LevelChunk, T> scanner;
    private final int chunksPerTick;
    private final Map<Long, Scan<T>> scans = new HashMap<>();
    private int[] offsets = new int[0];
    private double offsetsRange = -1;
    private long tick;

    /** The scanner may return null for chunks without a result. */
    ChunkIndex(Function<LevelChunk, T> scanner, int chunksPerTick) {
        this.scanner = scanner;
        this.chunksPerTick = chunksPerTick;
    }

    void clear() {
        scans.clear();
        tick = 0;
    }

    void tick(ClientLevel level, ChunkPos center, double range) {
        tick++;
        if (range != offsetsRange) {
            offsets = offsets(range);
            offsetsRange = range;
        }
        // Newly loaded chunks take priority over refreshing chunks that are already indexed.
        int budget = scan(level, center, chunksPerTick, false);
        if (budget > 0) scan(level, center, budget, true);
        if (tick % 20 == 0) prune(level, center, range);
    }

    /** Indexed chunks, with or without a result. */
    int size() { return scans.size(); }

    void forEach(Consumer<T> action) {
        for (Scan<T> scan : scans.values()) {
            if (scan.result != null) action.accept(scan.result);
        }
    }

    private int scan(ClientLevel level, ChunkPos center, int budget, boolean refresh) {
        for (int i = 0; i < offsets.length && budget > 0; i += 2) {
            int x = center.x + offsets[i], z = center.z + offsets[i + 1];
            LevelChunk chunk = level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
            if (chunk == null) continue;
            long key = ChunkPos.asLong(x, z);
            Scan<T> previous = scans.get(key);
            // A reloaded chunk is a new object, even when it reuses the same position.
            boolean indexed = previous != null && previous.chunk == chunk;
            if (refresh ? !indexed || tick - previous.tick < REFRESH_TICKS : indexed) continue;
            scans.put(key, new Scan<>(chunk, tick, scanner.apply(chunk)));
            budget--;
        }
        return budget;
    }

    private void prune(ClientLevel level, ChunkPos center, double range) {
        scans.entrySet().removeIf(entry -> {
            int x = ChunkPos.getX(entry.getKey()), z = ChunkPos.getZ(entry.getKey());
            return !withinRange(x - center.x, z - center.z, range)
                    || level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false) != entry.getValue().chunk;
        });
    }

    /** Chunk offsets within range, nearest first, packed as dx/dz pairs. */
    private static int[] offsets(double range) {
        int radius = (int) Math.ceil(range / 16.0);
        List<int[]> within = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (withinRange(dx, dz, range)) within.add(new int[] {dx, dz});
            }
        }
        within.sort(Comparator.comparingInt(offset -> offset[0] * offset[0] + offset[1] * offset[1]));
        int[] packed = new int[within.size() * 2];
        for (int i = 0; i < within.size(); i++) {
            packed[i * 2] = within.get(i)[0];
            packed[i * 2 + 1] = within.get(i)[1];
        }
        return packed;
    }

    /** The player may stand anywhere in the center chunk, so measure to the nearest possible edge. */
    private static boolean withinRange(int dx, int dz, double range) {
        double x = Math.max(Math.abs(dx) - 1, 0) * 16.0, z = Math.max(Math.abs(dz) - 1, 0) * 16.0;
        return x * x + z * z <= range * range;
    }

    private record Scan<T>(LevelChunk chunk, long tick, T result) { }
}
