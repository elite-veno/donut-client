package dev.donut.client.schematic;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.ObserverBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The cheap part of the wait for a place to stand under a block placed looking up (da-53). */
class LookUpStandTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static final BlockPos FLOOR = new BlockPos(0, 65, 0), CELL = FLOOR.above(), OBSERVER = new BlockPos(0, 68, 1);

    /** Made after the bootstrap: a block made as the class loads, before it, breaks Blocks for every test after. */
    private static BlockState upObserver() {
        return Blocks.OBSERVER.defaultBlockState().setValue(ObserverBlock.FACING, Direction.UP);
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

    /** ikea_v4's corridor in small: bars on a hopper, an up-facing observer two up and one over. */
    private static Map<BlockPos, BlockState> corridor() {
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        blocks.put(CELL, Blocks.IRON_BARS.defaultBlockState());
        blocks.put(OBSERVER, upObserver());
        blocks.put(OBSERVER.below(), Blocks.GLASS.defaultBlockState());
        return blocks;
    }

    private static Map<BlockPos, BlockState> hopper() { return Map.of(FLOOR, Blocks.HOPPER.defaultBlockState()); }

    @Test
    void barsUnderAnUpFacerTwoUpAreCandidates() {
        BuildPlan plan = BuildPlan.of(corridor(), true);
        assertEquals(List.of(plan.indexOf(OBSERVER)), SchematicBuilder.lookUpCandidates(plan, world(hopper()), CELL));
    }

    @Test
    void aBlockThePlanPutsOverTheCellKeepsItOut() {
        Map<BlockPos, BlockState> blocks = corridor();
        blocks.put(CELL.above(), Blocks.GLASS.defaultBlockState());
        BuildPlan plan = BuildPlan.of(blocks, true);
        assertEquals(List.of(), SchematicBuilder.lookUpCandidates(plan, world(hopper()), CELL));
    }

    @Test
    void noFloorYetOrAnUpFacerInAlreadyIsNoCandidate() {
        BuildPlan plan = BuildPlan.of(corridor(), true);
        assertEquals(List.of(), SchematicBuilder.lookUpCandidates(plan, world(Map.of()), CELL.above(4)), "nothing under the cell");
        Map<BlockPos, BlockState> in = new HashMap<>(hopper());
        in.put(OBSERVER, upObserver());
        assertEquals(List.of(), SchematicBuilder.lookUpCandidates(plan, world(in), CELL), "the observer is in");
    }

    @Test
    void levelBesideIsNoCandidate() {
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        blocks.put(CELL, Blocks.IRON_BARS.defaultBlockState());
        blocks.put(new BlockPos(0, 69, 1), upObserver());
        BuildPlan plan = BuildPlan.of(blocks, true);
        assertEquals(List.of(), SchematicBuilder.lookUpCandidates(plan, world(hopper()), CELL), "three up");
    }

    @Test
    void aSourcesRunIsTheRunningWaterJoinedToIt() {
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        blocks.put(CELL, Blocks.WATER.defaultBlockState());
        for (int x = 1; x <= 3; x++) blocks.put(CELL.east(x), Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, x));
        // Running water of its own, not joined to the source, and a block between.
        blocks.put(CELL.east(4), Blocks.GLASS.defaultBlockState());
        blocks.put(CELL.east(5), Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, 2));
        BuildPlan plan = BuildPlan.of(blocks, true);
        List<BlockPos> run = SchematicBuilder.waterRun(plan, plan.indexOf(CELL));
        assertEquals(List.of(CELL, CELL.east(1), CELL.east(2), CELL.east(3)), run);
    }
}
