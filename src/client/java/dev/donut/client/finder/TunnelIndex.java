package dev.donut.client.finder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
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

    /** Chunks are scanned for every kind at the shortest length, so changing these filters shows at once. */
    void configure(int minLength, boolean tunnels, boolean holes, boolean stairs) {
        this.minLength = minLength;
        this.tunnels = tunnels;
        this.holes = holes;
        this.stairs = stairs;
    }

    void tick(ClientLevel level, ChunkPos center, double range) { chunks.tick(level, center, range); }

    /** Whether this chunk's passages have been read. */
    boolean indexed(int chunkX, int chunkZ) { return chunks.indexed(chunkX, chunkZ); }

    /** A passage can start in a neighboring chunk and run through this one. */
    void markDirty(BlockPos pos) {
        int x = SectionPos.blockToSectionCoord(pos.getX()), z = SectionPos.blockToSectionCoord(pos.getZ());
        chunks.markDirty(x, z);
        chunks.markDirty(x - 1, z);
        chunks.markDirty(x + 1, z);
        chunks.markDirty(x, z - 1);
        chunks.markDirty(x, z + 1);
    }

    /** A chunk that loads completes the passages its neighbors' edges cut short. */
    void markDirty(ChunkPos pos) {
        chunks.markDirty(pos.x(), pos.z());
        chunks.markDirty(pos.x() - 1, pos.z());
        chunks.markDirty(pos.x() + 1, pos.z());
        chunks.markDirty(pos.x(), pos.z() - 1);
        chunks.markDirty(pos.x(), pos.z() + 1);
    }

    /** Only a block that turns open or solid can change a passage; growing crops and redstone don't. */
    static boolean matters(BlockState previous, BlockState current) {
        return solid(previous) != solid(current) || open(previous) != open(current);
    }

    void collect(Vec3 origin, double range, List<TunnelDetector.Segment> found) {
        double rangeSqr = range * range;
        int minSteps = Math.max(4, minLength / 2);
        chunks.forEach(segments -> {
            for (TunnelDetector.Segment segment : segments) {
                boolean wanted = switch (segment.kind()) {
                    case TUNNEL -> tunnels && segment.total() >= minLength;
                    case HOLE -> holes && segment.total() >= minLength;
                    case STAIRS -> stairs && segment.total() >= minSteps;
                };
                if (wanted && distanceSqr(segment, origin) <= rangeSqr) found.add(segment);
            }
        });
    }

    /** The point of the segment's center line closest to the origin. */
    static Vec3 nearestPoint(TunnelDetector.Segment segment, Vec3 origin) {
        double x = segment.x() + 0.5, y = segment.y() + segment.cellHeight() / 2.0, z = segment.z() + 0.5;
        int dx = segment.dx(), dy = segment.dy(), dz = segment.dz();
        double along = ((origin.x - x) * dx + (origin.y - y) * dy + (origin.z - z) * dz) / (dx * dx + dy * dy + dz * dz);
        along = Math.clamp(along, 0, segment.length() - 1);
        return new Vec3(x + dx * along, y + dy * along, z + dz * along);
    }

    static double distanceSqr(TunnelDetector.Segment segment, Vec3 origin) { return nearestPoint(segment, origin).distanceToSqr(origin); }

    /** Signs, pressure plates and the like count as walls for blocksMotion but have nothing to collide with. */
    private static boolean solid(BlockState state) {
        return state.blocksMotion() && !state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty();
    }

    private static boolean open(BlockState state) { return !solid(state) && state.getFluidState().isEmpty(); }

    private List<TunnelDetector.Segment> scan(LevelChunk chunk) {
        ChunkGrid grid = new ChunkGrid(chunk);
        // Every kind at the shortest length; collect() applies the settings.
        TunnelDetector detector = new TunnelDetector(grid, 4);
        List<TunnelDetector.Segment> found = new ArrayList<>();
        int minX = chunk.getPos().getMinBlockX(), minZ = chunk.getPos().getMinBlockZ();
        for (int y = grid.minY; y < grid.maxY; y++) {
            // Every cell of a passage needs a wall inside its own 16x16 layer, which an all-air section lacks.
            if (grid.airSections[(y - grid.minY) >> 4]) continue;
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    if (grid.localOpen(x, y, z)) detector.scanCell(minX + x, y, minZ + z, true, true, true, found);
                }
            }
        }
        found.removeIf(segment -> segment.kind() == TunnelDetector.Kind.HOLE && igloo(chunk.getLevel(), segment));
        return found.isEmpty() ? null : List.copyOf(found);
    }

    /** An igloo's basement ladder shaft is a 1x1 hole walled with stone bricks, up to 30 deep. */
    private static boolean igloo(Level level, TunnelDetector.Segment hole) {
        BlockPos middle = new BlockPos(hole.x(), hole.y() + hole.length() / 2, hole.z());
        return level.getBlockState(middle.north()).is(Blocks.STONE_BRICKS) && level.getBlockState(middle.south()).is(Blocks.STONE_BRICKS)
                && level.getBlockState(middle.east()).is(Blocks.STONE_BRICKS) && level.getBlockState(middle.west()).is(Blocks.STONE_BRICKS);
    }

    /** The chunk's blocks as two bit sets, with ordinary level reads for neighboring chunks. */
    private final class ChunkGrid implements TunnelDetector.Grid {
        private final Level level;
        private final int chunkX, chunkZ, minY, maxY;
        private final boolean[] airSections;
        private final BlockPos.MutableBlockPos outside = new BlockPos.MutableBlockPos();

        ChunkGrid(LevelChunk chunk) {
            level = chunk.getLevel();
            chunkX = chunk.getPos().x();
            chunkZ = chunk.getPos().z();
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

        @Override public boolean inside(int x, int z) {
            return SectionPos.blockToSectionCoord(x) == chunkX && SectionPos.blockToSectionCoord(z) == chunkZ;
        }

        @Override public boolean open(int x, int y, int z) { return read(x, y, z, true); }

        @Override public boolean solid(int x, int y, int z) { return read(x, y, z, false); }

        private boolean read(int x, int y, int z, boolean open) {
            if (y < minY || y >= maxY) return false;
            if (SectionPos.blockToSectionCoord(x) == chunkX && SectionPos.blockToSectionCoord(z) == chunkZ) {
                return get(open ? openBits : solidBits, index(x & 15, y, z & 15));
            }
            outside.set(x, y, z);
            // Unknown blocks are neither, so a passage can't be confirmed through an unloaded chunk.
            if (!LoadedChunks.has(level, outside)) return false;
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
