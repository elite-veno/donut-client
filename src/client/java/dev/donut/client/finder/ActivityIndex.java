package dev.donut.client.finder;

import java.util.List;
import java.util.function.Predicate;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AmethystClusterBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BuddingAmethystBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * Scores each loaded chunk's amethyst by how far its buds grew past worldgen. Whether growth is real is
 * decided over the chunk and the amethyst chunks around it, since a geode usually spans a few chunks and a
 * small piece of one is easily lucky; how far it grew is the chunk's own.
 */
final class ActivityIndex {
    private static final Predicate<BlockState> BUDDING = state -> state.is(Blocks.BUDDING_AMETHYST);
    private static final Direction[] DIRECTIONS = Direction.values();

    /** Block changes and chunk loads mark chunks for a rescan, so the periodic refresh is only a safety net. */
    private final ChunkIndex<ActivityArea> chunks = new ChunkIndex<>(ActivityIndex::scan, 16, 200);

    void clear() { chunks.clear(); }

    void tick(ClientLevel level, ChunkPos center, double range) { chunks.tick(level, center, range); }

    /**
     * Rescans a chunk on the next tick: its amethyst changed, or a neighbor whose blocks its edge faces
     * read has just loaded.
     */
    void markDirty(int chunkX, int chunkZ) { chunks.markDirty(chunkX, chunkZ); }

    void collect(Vec3 origin, double range, double minScore, List<ActivityArea> found) {
        chunks.forEach(raw -> {
            if (raw.horizontalDistance(origin) > range) return;
            ActivityArea area = judge(raw);
            if (area.score() >= minScore) found.add(area);
        });
    }

    /** Chunks beyond the range, such as the area a teleport just left, are not counted. */
    ActivityStats stats(boolean overworld, Vec3 origin, double range) {
        int[] amethyst = new int[2];
        ActivityArea[] best = new ActivityArea[2];
        chunks.forEach(raw -> {
            if (raw.horizontalDistance(origin) > range) return;
            amethyst[0]++;
            amethyst[1] += raw.budding();
            ActivityArea area = judge(raw);
            if (better(area, best[0], origin)) best[0] = area;
            if (area.chance() <= ActivityStats.CERTAIN_CHANCE && better(area, best[1], origin)) best[1] = area;
        });
        return new ActivityStats(overworld, chunks.size(), amethyst[0], amethyst[1], best[0], best[1]);
    }

    /** Of equal scores, the nearest is the one worth going to. */
    private static boolean better(ActivityArea area, ActivityArea than, Vec3 origin) {
        return than == null || area.score() > than.score()
                || area.score() == than.score() && area.horizontalDistance(origin) < than.horizontalDistance(origin);
    }

    /**
     * The chunk's growth, counted only when it and the amethyst chunks around it carry more than worldgen
     * would give them. A lucky handful of buds in one chunk is outweighed by its geode's other chunks, and a
     * grown geode split thinly over chunks is still found.
     */
    private ActivityArea judge(ActivityArea raw) {
        List<ActivityArea> around = new java.util.ArrayList<>(9);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                ActivityArea near = chunks.get(raw.chunk().x() + dx, raw.chunk().z() + dz);
                if (near != null) around.add(near);
            }
        }
        return judge(raw, around);
    }

    /** {@link #judge(ActivityArea)} over a given neighborhood, which includes the chunk itself. */
    static ActivityArea judge(ActivityArea raw, List<ActivityArea> around) {
        int budding = 0, stages = 0;
        for (ActivityArea near : around) {
            budding += near.budding();
            stages += near.stages();
        }
        double chance = AmethystGrowth.chance(budding, stages);
        boolean real = budding >= AmethystGrowth.MIN_BUDDING && chance <= AmethystGrowth.FALSE_ALARM;
        return raw.judged(real ? AmethystGrowth.growth(raw.budding(), raw.faces(), raw.stages()) : 0, chance);
    }

    static ActivityArea scan(LevelChunk chunk) {
        Level level = chunk.getLevel();
        LevelChunkSection[] sections = chunk.getSections();
        int minX = chunk.getPos().getMinBlockX(), minZ = chunk.getPos().getMinBlockZ();
        int budding = 0, faces = 0, stages = 0;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        for (int index = 0; index < sections.length; index++) {
            LevelChunkSection section = sections[index];
            if (section.hasOnlyAir() || !section.maybeHas(BUDDING)) continue;
            int sectionY = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(index));
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        if (!BUDDING.test(section.getBlockState(x, y, z))) continue;
                        BlockPos pos = new BlockPos(minX + x, sectionY + y, minZ + z);
                        int ownFaces = 0, ownStages = 0;
                        boolean couldBud = false;
                        for (Direction direction : DIRECTIONS) {
                            BlockPos next = pos.relative(direction);
                            // An unloaded neighbor reads as air, which would count as an empty face.
                            if (!LoadedChunks.has(level, next)) continue;
                            BlockState neighbor = level.getBlockState(next);
                            int stage = stage(neighbor);
                            // A bud belongs to the block it grows away from.
                            if (stage > 0 && neighbor.getValue(AmethystClusterBlock.FACING) == direction) {
                                ownFaces++;
                                ownStages += stage;
                                couldBud = true;
                            } else if (BuddingAmethystBlock.canClusterGrowAtState(neighbor)) {
                                ownFaces++;
                                couldBud = true;
                            } else if (!geodeShell(neighbor)) {
                                // Not something a geode is made of, so placed or flowed in later: the face
                                // may have been open when worldgen handed out buds. Counting the block keeps
                                // a geode whose empty faces got filled from looking all budded.
                                couldBud = true;
                            }
                        }
                        if (!couldBud) continue;
                        budding++;
                        faces += ownFaces;
                        stages += ownStages;
                        minY = Math.min(minY, pos.getY());
                        maxY = Math.max(maxY, pos.getY());
                    }
                }
            }
        }
        if (budding == 0) return null;
        return new ActivityArea(chunk.getPos(), minY, maxY, surface(chunk), 0, budding, faces, stages, Double.NaN);
    }

    /**
     * What a geode's walls are made of; anything else next to budding amethyst came later. A neighbor's bud
     * is not wall: its cell was open when worldgen handed out buds, whoever's bud took it.
     */
    private static boolean geodeShell(BlockState state) {
        return state.is(Blocks.AMETHYST_BLOCK) || state.is(Blocks.BUDDING_AMETHYST)
                || state.is(Blocks.CALCITE) || state.is(Blocks.SMOOTH_BASALT);
    }

    /** The chunk's highest motion-blocking block; the server sends this heightmap with every chunk. */
    private static int surface(LevelChunk chunk) {
        int top = chunk.getMinY();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) top = Math.max(top, chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z));
        }
        return top;
    }

    private static int stage(BlockState state) {
        if (state.is(Blocks.SMALL_AMETHYST_BUD)) return 1;
        if (state.is(Blocks.MEDIUM_AMETHYST_BUD)) return 2;
        if (state.is(Blocks.LARGE_AMETHYST_BUD)) return 3;
        if (state.is(Blocks.AMETHYST_CLUSTER)) return 4;
        return 0;
    }
}
