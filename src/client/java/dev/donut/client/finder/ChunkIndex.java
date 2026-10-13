package dev.donut.client.finder;

import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Caches one scan result per loaded chunk for searches that must read block data directly.
 * Scans are spread over ticks, nearest chunks first. Chunks marked dirty by block updates are
 * rescanned on the next tick, so changes show at once instead of waiting for the periodic refresh.
 */
final class ChunkIndex<T> {
    private static final Logger LOGGER = LoggerFactory.getLogger("Donut/Finder");
    /** Dirty chunks rescanned per tick; a large explosion or section update can dirty many at once. */
    private static final int DIRTY_PER_TICK = 24;

    private final Function<LevelChunk, T> scanner;
    private final int chunksPerTick;
    /** Indexed chunks are also rescanned periodically, as a safety net for missed updates. */
    private final int refreshTicks;
    private final Map<Long, Scan<T>> scans = new HashMap<>();
    private final LongLinkedOpenHashSet dirty = new LongLinkedOpenHashSet();
    private int[] offsets = new int[0];
    private double offsetsRange = -1;
    /** Where the periodic refresh resumes, so far chunks get their turn even when there are many. */
    private int refreshCursor;
    private long tick;
    private boolean failed;

    /** The scanner may return null for chunks without a result. */
    ChunkIndex(Function<LevelChunk, T> scanner, int chunksPerTick) { this(scanner, chunksPerTick, 40); }

    ChunkIndex(Function<LevelChunk, T> scanner, int chunksPerTick, int refreshTicks) {
        this.scanner = scanner;
        this.chunksPerTick = chunksPerTick;
        this.refreshTicks = refreshTicks;
    }

    void clear() {
        scans.clear();
        dirty.clear();
        tick = 0;
    }

    /** Rescan this chunk on the next tick, if it is loaded and within range. */
    void markDirty(int chunkX, int chunkZ) {
        if (dirty.size() < 4096) dirty.add(ChunkPos.pack(chunkX, chunkZ));
    }

    void tick(ClientLevel level, ChunkPos center, double range) {
        tick++;
        if (range != offsetsRange) {
            offsets = offsets(range);
            offsetsRange = range;
            refreshCursor = 0;
        }
        rescanDirty(level, center, range);
        // Newly loaded chunks take priority over refreshing chunks that are already indexed.
        int budget = scan(level, center, chunksPerTick, false);
        if (budget > 0) scan(level, center, budget, true);
        if (tick % 20 == 0) prune(level, center, range);
    }

    /** Indexed chunks, with or without a result. */
    int size() { return scans.size(); }

    /** This chunk's result, or null when it has none or isn't indexed. */
    T get(int chunkX, int chunkZ) {
        Scan<T> scan = scans.get(ChunkPos.pack(chunkX, chunkZ));
        return scan == null ? null : scan.result;
    }

    void forEach(Consumer<T> action) {
        for (Scan<T> scan : scans.values()) {
            if (scan.result != null) action.accept(scan.result);
        }
    }

    private void rescanDirty(ClientLevel level, ChunkPos center, double range) {
        int budget = DIRTY_PER_TICK;
        LongIterator iterator = dirty.iterator();
        while (iterator.hasNext() && budget > 0) {
            long key = iterator.nextLong();
            iterator.remove();
            int x = ChunkPos.getX(key), z = ChunkPos.getZ(key);
            if (!withinRange(x - center.x(), z - center.z(), range)) continue;
            LevelChunk chunk = level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
            if (chunk == null) continue;
            scans.put(key, new Scan<>(chunk, tick, scanOf(chunk)));
            budget--;
        }
    }

    private int scan(ClientLevel level, ChunkPos center, int budget, boolean refresh) {
        int count = offsets.length / 2;
        // New chunks go nearest first; the refresh carries on from where the last tick stopped.
        int start = refresh ? refreshCursor : 0;
        for (int step = 0; step < count && budget > 0; step++) {
            int i = (start + step) % count * 2;
            if (refresh) refreshCursor = (i / 2 + 1) % count;
            int x = center.x() + offsets[i], z = center.z() + offsets[i + 1];
            LevelChunk chunk = level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
            if (chunk == null) continue;
            long key = ChunkPos.pack(x, z);
            Scan<T> previous = scans.get(key);
            // A reloaded chunk is a new object, even when it reuses the same position.
            boolean indexed = previous != null && previous.chunk == chunk;
            if (refresh ? !indexed || tick - previous.tick < refreshTicks : indexed) continue;
            scans.put(key, new Scan<>(chunk, tick, scanOf(chunk)));
            budget--;
        }
        return budget;
    }

    /** A scanner bug must not crash the client from the tick handler; the chunk is kept without a result. */
    private T scanOf(LevelChunk chunk) {
        try {
            return scanner.apply(chunk);
        } catch (RuntimeException e) {
            if (!failed) {
                failed = true;
                LOGGER.error("Chunk scan failed at {}; chunks that fail are kept without a result", chunk.getPos(), e);
            }
            return null;
        }
    }

    private void prune(ClientLevel level, ChunkPos center, double range) {
        scans.entrySet().removeIf(entry -> {
            int x = ChunkPos.getX(entry.getKey()), z = ChunkPos.getZ(entry.getKey());
            return !withinRange(x - center.x(), z - center.z(), range)
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
