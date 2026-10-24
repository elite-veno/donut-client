package dev.donut.client.schematic;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class HopperTest {
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

    private static final BlockPos CHEST = new BlockPos(0, 66, 0);

    @Test
    void aHopperUnderTheBlockDrawsInItsDropUnlessLocked() {
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        blocks.put(CHEST, Blocks.CHEST.defaultBlockState());
        blocks.put(CHEST.below(), Blocks.HOPPER.defaultBlockState());
        assertTrue(SchematicBuilder.hopperTakes(world(blocks), CHEST));
        blocks.put(CHEST.below(), Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.ENABLED, false));
        assertFalse(SchematicBuilder.hopperTakes(world(blocks), CHEST), "a powered hopper is locked");
        blocks.put(CHEST.below(), Blocks.STONE.defaultBlockState());
        assertFalse(SchematicBuilder.hopperTakes(world(blocks), CHEST));
    }

    @Test
    void aHopperUnderAnOpenSpotBesideDrawsInADropThatRollsThere() {
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        blocks.put(CHEST, Blocks.CHEST.defaultBlockState());
        blocks.put(CHEST.below(), Blocks.STONE.defaultBlockState());
        blocks.put(CHEST.east().below(), Blocks.HOPPER.defaultBlockState());
        assertTrue(SchematicBuilder.hopperTakes(world(blocks), CHEST), "open air over the hopper beside");
        blocks.put(CHEST.east(), Blocks.STONE.defaultBlockState());
        assertFalse(SchematicBuilder.hopperTakes(world(blocks), CHEST), "a block over the hopper beside keeps the drop out");
        blocks.put(CHEST.east(), Blocks.AIR.defaultBlockState());
        blocks.put(CHEST.east().below().below(), Blocks.HOPPER.defaultBlockState());
        blocks.put(CHEST.east().below(), Blocks.STONE.defaultBlockState());
        assertFalse(SchematicBuilder.hopperTakes(world(blocks), CHEST), "a hopper further down draws in nothing from up here");
    }
}
