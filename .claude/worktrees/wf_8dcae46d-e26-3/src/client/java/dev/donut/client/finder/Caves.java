package dev.donut.client.finder;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * How much of a chunk's underground is open, for ChunkFinder: caves, ravines, water and lava below the ground, which a
 * player digging through can fall or flood into. Anti-xray only disguises solid blocks, so the server sends caves as
 * they are.
 */
public final class Caves {
    /** The bedrock floor's layers, which are never dug, are left out. */
    private static final int BEDROCK = 5;

    private Caves() { }

    /**
     * The share of blocks from the ground down to the bedrock floor that a player could fall or swim through, from 0
     * to 1; read in every other column, which is close enough for caves and four times faster.
     */
    public static float share(LevelChunk chunk) {
        int minX = chunk.getPos().getMinBlockX(), minZ = chunk.getPos().getMinBlockZ(), floor = chunk.getMinY() + BEDROCK;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        long open = 0, total = 0;
        for (int x = 0; x < 16; x += 2) {
            for (int z = 0; z < 16; z += 2) {
                // Down through trees, plants and lakes or the sea to the ground.
                int y = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
                while (y > floor && !ground(chunk.getBlockState(pos.set(minX + x, y, minZ + z)))) y--;
                for (y--; y > floor; y--) {
                    total++;
                    if (!chunk.getBlockState(pos.set(minX + x, y, minZ + z)).blocksMotion()) open++;
                }
            }
        }
        return total == 0 ? 0 : (float) open / total;
    }

    private static boolean ground(BlockState state) {
        return state.blocksMotion() && !state.is(BlockTags.LEAVES) && !state.is(BlockTags.LOGS);
    }
}
