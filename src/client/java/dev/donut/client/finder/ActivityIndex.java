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
import net.minecraft.world.phys.Vec3;

/** Scores each loaded chunk's amethyst by how far its buds grew past worldgen. */
final class ActivityIndex {
    private static final Predicate<BlockState> BUDDING = state -> state.is(Blocks.BUDDING_AMETHYST);
    private static final Direction[] DIRECTIONS = Direction.values();

    private final ChunkIndex<ActivityArea> chunks = new ChunkIndex<>(ActivityIndex::scan);

    void clear() { chunks.clear(); }

    void tick(ClientLevel level, ChunkPos center, double range) { chunks.tick(level, center, range); }

    void collect(Vec3 origin, double range, double minScore, List<ActivityArea> found) {
        chunks.forEach(area -> {
            if (area.score() >= minScore && area.horizontalDistance(origin) <= range) found.add(area);
        });
    }

    private static ActivityArea scan(LevelChunk chunk) {
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
                        for (Direction direction : DIRECTIONS) {
                            BlockPos next = pos.relative(direction);
                            // An unloaded neighbor reads as air, which would count as an empty face.
                            if (!level.hasChunkAt(next)) continue;
                            BlockState neighbor = level.getBlockState(next);
                            int stage = stage(neighbor);
                            // A bud belongs to the block it grows away from.
                            if (stage > 0 && neighbor.getValue(AmethystClusterBlock.FACING) == direction) {
                                ownFaces++;
                                ownStages += stage;
                            } else if (BuddingAmethystBlock.canClusterGrowAtState(neighbor)) {
                                ownFaces++;
                            }
                        }
                        if (ownFaces == 0) continue;
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
        return new ActivityArea(chunk.getPos(), minY, maxY, AmethystGrowth.score(budding, faces, stages), budding);
    }

    private static int stage(BlockState state) {
        if (state.is(Blocks.SMALL_AMETHYST_BUD)) return 1;
        if (state.is(Blocks.MEDIUM_AMETHYST_BUD)) return 2;
        if (state.is(Blocks.LARGE_AMETHYST_BUD)) return 3;
        if (state.is(Blocks.AMETHYST_CLUSTER)) return 4;
        return 0;
    }
}
