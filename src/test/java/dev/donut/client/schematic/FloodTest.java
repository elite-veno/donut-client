package dev.donut.client.schematic;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class FloodTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** Stone up to y 64, air above, with {@code blocks} set. */
    private static BlockGetter world(Map<BlockPos, BlockState> blocks) {
        return new BlockGetter() {
            @Override
            public BlockState getBlockState(BlockPos pos) {
                BlockState state = blocks.get(pos);
                if (state != null) return state;
                return pos.getY() <= 64 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
            }

            @Override
            public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }

            @Override
            public BlockEntity getBlockEntity(BlockPos pos) { return null; }

            @Override
            public int getHeight() { return 384; }

            @Override
            public int getMinY() { return -64; }
        };
    }

    @Test
    void waterPouredBesideThePlayerRunsOntoThem() {
        // On flat ground, a source two blocks away spreads over the player's feet.
        assertTrue(SchematicBuilder.floods(world(Map.of()), new BlockPos(2, 65, 0), new Vec3(0.5, 65, 0.5)));
    }

    @Test
    void waterBelowTheFeetOrBehindAWallDoesNot() {
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        // A channel dug one deep beside the player: the water stays in it, under their feet.
        for (int z = -3; z <= 3; z++) blocks.put(new BlockPos(2, 64, z), Blocks.AIR.defaultBlockState());
        for (int z = -3; z <= 3; z++) blocks.put(new BlockPos(2, 65, z), Blocks.STONE.defaultBlockState());
        blocks.put(new BlockPos(2, 65, 0), Blocks.AIR.defaultBlockState());
        assertFalse(SchematicBuilder.floods(world(blocks), new BlockPos(2, 64, 0), new Vec3(0.5, 66, 0.5)),
                "the player stands a block above the water's level");
        // Behind a wall it can't run round within a flow's length.
        Map<BlockPos, BlockState> walled = new HashMap<>();
        for (int z = -10; z <= 10; z++) walled.put(new BlockPos(1, 65, z), Blocks.STONE.defaultBlockState());
        assertFalse(SchematicBuilder.floods(world(walled), new BlockPos(3, 65, 0), new Vec3(0.5, 65, 0.5)));
    }

    @Test
    void waterOnTheSameLevelNearbyRunsToWhereThePlayerWouldStand() {
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        blocks.put(new BlockPos(3, 65, 0), Blocks.WATER.defaultBlockState());
        assertTrue(SchematicBuilder.wetSoon(world(blocks), new BlockPos(0, 65, 0), new HashMap<>()), "three blocks off, on open ground");
        assertFalse(SchematicBuilder.wetSoon(world(blocks), new BlockPos(0, 66, 0), new HashMap<>()), "standing a block higher");
        blocks.put(new BlockPos(2, 65, 0), Blocks.STONE.defaultBlockState());
        blocks.put(new BlockPos(2, 66, 0), Blocks.STONE.defaultBlockState());
        for (int z = -8; z <= 8; z++) blocks.put(new BlockPos(2, 65, z), Blocks.STONE.defaultBlockState());
        assertFalse(SchematicBuilder.wetSoon(world(blocks), new BlockPos(0, 65, 0), new HashMap<>()), "behind a wall");
    }
}
