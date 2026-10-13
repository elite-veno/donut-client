package dev.donut.client.finder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.Vec3;

/** Looks for dug passages in loaded chunks; see {@link TunnelDetector}. */
final class TunnelIndex {
    private static final Predicate<BlockState> NOT_SOLID = state -> !solid(state);

    private final ChunkIndex<List<TunnelDetector.Segment>> chunks = new ChunkIndex<>(this::scan, 3, 200);
    private int minLength = 10;
    private boolean tunnels = true, holes = true, stairs = true;
    /** Reused between scans; scans only run on the client thread. */
    private long[] openBits = new long[0], solidBits = new long[0];

    void clear() { chunks.clear(); }

    /** Changing what counts as a passage rescans every chunk. */
    void configure(int minLength, boolean tunnels, boolean holes, boolean stairs) {
        if (minLength == this.minLength && tunnels == this.tunnels && holes == this.holes && stairs == this.stairs) return;
        this.minLength = minLength;
        this.tunnels = tunnels;
        this.holes = holes;
        this.stairs = stairs;
        chunks.clear();
    }

    void tick(ClientLevel level, ChunkPos center, double range) { chunks.tick(level, center, range); }

    /** A passage can start in a neighboring chunk and run through this one. */
    void markDirty(BlockPos pos) {
        int x = SectionPos.blockToSectionCoord(pos.getX()), z = SectionPos.blockToSectionCoord(pos.getZ());
        chunks.markDirty(x, z);
        chunks.markDirty(x - 1, z);
        chunks.markDirty(x + 1, z);
        chunks.markDirty(x, z - 1);
        chunks.markDirty(x, z + 1);
    }

    void markDirty(ChunkPos pos) { chunks.markDirty(pos.x, pos.z); }

    void collect(Vec3 origin, double range, List<TunnelDetector.Segment> found) {
        double rangeSqr = range * range;
        chunks.forEach(segments -> {
            for (TunnelDetector.Segment segment : segments) {
                if (distanceSqr(segment, origin) <= rangeSqr) found.add(segment);
            }
        });
    }

    /** From the origin to the nearest cell of the segment. */
    static double distanceSqr(TunnelDetector.Segment segment, Vec3 origin) {
        double best = Double.MAX_VALUE;
        // Long passages are sampled every few cells; close enough for range checks and sorting.
        int step = Math.max(1, segment.length() / 16);
        for (int i = 0; i < segment.length(); i += step) {
            double dx = segment.x() + segment.dx() * i + 0.5 - origin.x;
            double dy = segment.y() + segment.dy() * i + segment.cellHeight() / 2.0 - origin.y;
            double dz = segment.z() + segment.dz() * i + 0.5 - origin.z;
            best = Math.min(best, dx * dx + dy * dy + dz * dz);
        }
        return best;
    }

    private static boolean open(BlockState state) { return !state.blocksMotion() && state.getFluidState().isEmpty(); }

    private static boolean solid(BlockState state) { return state.blocksMotion(); }

    private List<TunnelDetector.Segment> scan(LevelChunk chunk) {
        ChunkGrid grid = new ChunkGrid(chunk);
        TunnelDetector detector = new TunnelDetector(grid, minLength);
        List<TunnelDetector.Segment> found = new ArrayList<>();
        int minX = chunk.getPos().getMinBlockX(), minZ = chunk.getPos().getMinBlockZ();
        for (int y = grid.minY; y < grid.maxY; y++) {
            // Every cell of a passage needs a wall inside its own 16x16 layer, which an all-air section lacks.
            if (grid.airSections[(y - grid.minY) >> 4]) continue;
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    if (grid.localOpen(x, y, z)) detector.scanCell(minX + x, y, minZ + z, tunnels, holes, stairs, found);
                }
            }
        }
        return found.isEmpty() ? null : List.copyOf(found);
    }

    /** The chunk's blocks as two bit sets, with ordinary level reads for neighboring chunks. */
    private final class ChunkGrid implements TunnelDetector.Grid {
        private final Level level;
        private final int chunkX, chunkZ, minY, maxY;
        private final boolean[] airSections;
        private final BlockPos.MutableBlockPos outside = new BlockPos.MutableBlockPos();

        ChunkGrid(LevelChunk chunk) {
            level = chunk.getLevel();
            chunkX = chunk.getPos().x;
            chunkZ = chunk.getPos().z;
            minY = chunk.getMinY();
            maxY = chunk.getMaxY() + 1;
            int words = ((maxY - minY) * 256 + 63) / 64;
            if (openBits.length < words) {
                openBits = new long[words];
                solidBits = new long[words];
            } else {
                Arrays.fill(openBits, 0, words, 0L);
                Arrays.fill(solidBits, 0, words, 0L);
            }
            LevelChunkSection[] sections = chunk.getSections();
            airSections = new boolean[sections.length];
            for (int index = 0; index < sections.length; index++) {
                LevelChunkSection section = sections[index];
                airSections[index] = section.hasOnlyAir();
                int base = (SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(index)) - minY) * 256;
                if (section.hasOnlyAir()) {
                    setRange(openBits, base, base + 4096);
                } else if (!section.maybeHas(NOT_SOLID)) {
                    setRange(solidBits, base, base + 4096);
                } else {
                    for (int i = 0; i < 4096; i++) {
                        BlockState state = section.getBlockState(i & 15, i >> 8, (i >> 4) & 15);
                        if (TunnelIndex.solid(state)) set(solidBits, base + i);
                        else if (TunnelIndex.open(state)) set(openBits, base + i);
                    }
                }
            }
        }

        boolean localOpen(int x, int y, int z) { return get(openBits, index(x, y, z)); }

        @Override public boolean open(int x, int y, int z) { return read(x, y, z, true); }

        @Override public boolean solid(int x, int y, int z) { return read(x, y, z, false); }

        private boolean read(int x, int y, int z, boolean open) {
            if (y < minY || y >= maxY) return false;
            if (SectionPos.blockToSectionCoord(x) == chunkX && SectionPos.blockToSectionCoord(z) == chunkZ) {
                return get(open ? openBits : solidBits, index(x & 15, y, z & 15));
            }
            outside.set(x, y, z);
            // Unknown blocks are neither, so a passage can't be confirmed through an unloaded chunk.
            if (!level.hasChunkAt(outside)) return false;
            BlockState state = level.getBlockState(outside);
            return open ? TunnelIndex.open(state) : TunnelIndex.solid(state);
        }

        /** Index layout matches a section's own: x fastest, then z, then y. */
        private int index(int x, int y, int z) { return ((y - minY) << 8) | (z << 4) | x; }
    }

    private static void set(long[] bits, int index) { bits[index >> 6] |= 1L << index; }

    private static boolean get(long[] bits, int index) { return (bits[index >> 6] & (1L << index)) != 0; }

    private static void setRange(long[] bits, int from, int to) {
        // Sections are 4096 bits and start on word boundaries.
        Arrays.fill(bits, from >> 6, to >> 6, -1L);
    }
}
