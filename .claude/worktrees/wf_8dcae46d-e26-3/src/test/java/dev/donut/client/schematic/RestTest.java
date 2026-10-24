package dev.donut.client.schematic;

import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Where a walk comes to rest, a little off the middle of its place to stand, and which blocks are in the way there. */
class RestTest {
    /** Standing on the floor at y 64, the feet at 65. */
    private static final Navigator.Node HERE = new Navigator.Node(0, 64, 0, 65, Navigator.Move.WALK);

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** A floor of stone at y 64, air above. */
    private static BlockGetter world() {
        return new BlockGetter() {
            @Override
            public BlockState getBlockState(BlockPos pos) {
                return pos.getY() == 64 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
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

    private static boolean atRest(BlockPos pos, BlockState target) {
        return SchematicBuilder.inTheWayAtRest(world(), HERE, pos, target);
    }

    @Test
    void aHopperInTheNextCellAtBodyHeightIsInTheWayOnceTheWalkComesToRestTowardIt() {
        BlockPos beside = new BlockPos(1, 65, 0);
        BlockState hopper = Blocks.HOPPER.defaultBlockState();
        // From the very middle the body, 0.6 wide, keeps out of the next cell: the place looked fine to the walk.
        AABB middle = new AABB(0.2, 65, 0.2, 0.8, 66.8, 0.8);
        assertFalse(PlacementSolver.inTheWay(middle, world(), beside, hopper));
        assertFalse(PlacementSolver.sharesSpot(middle, world(), beside, hopper));
        // Come to rest a quarter of a block toward it, the body is in its cell, and the click waits for a step aside.
        assertTrue(atRest(beside, hopper));
        assertTrue(atRest(new BlockPos(0, 66, -1), Blocks.CHEST.defaultBlockState()), "at head height on the other side too");
        assertTrue(atRest(new BlockPos(1, 65, 1), Blocks.STONE.defaultBlockState()), "and across the corner");
    }

    @Test
    void blocksTwoCellsAwayUnderfootOrOverheadAreNot() {
        assertFalse(atRest(new BlockPos(2, 65, 0), Blocks.HOPPER.defaultBlockState()), "a cell further on");
        assertFalse(atRest(new BlockPos(1, 64, 0), Blocks.STONE.defaultBlockState()), "in the floor beside the feet");
        assertFalse(atRest(new BlockPos(1, 67, 0), Blocks.STONE.defaultBlockState()), "over the head, which ends at 66.8");
    }

    @Test
    void blocksTheBodyPassesThroughAreNever() {
        assertFalse(atRest(new BlockPos(1, 65, 0), Blocks.REDSTONE_WIRE.defaultBlockState()));
        assertFalse(atRest(new BlockPos(1, 65, 0), Blocks.TORCH.defaultBlockState()));
    }

    @Test
    void anOpenGateIsPlacedClosedAndSoInTheWay() {
        // A click puts a gate in closed, and it is opened after: as it goes in it has a closed gate's box.
        BlockState open = Blocks.OAK_FENCE_GATE.defaultBlockState().setValue(BlockStateProperties.OPEN, true);
        assertTrue(open.getCollisionShape(world(), BlockPos.ZERO).isEmpty());
        assertTrue(atRest(new BlockPos(1, 65, 0), open));
    }

    @Test
    void aPlaceHalfABlockUpReachesIntoTheCellsOverTheNextOnes() {
        // Standing on a slab the head, at 67.3, reaches into the cell over the next one's.
        Navigator.Node slab = new Navigator.Node(0, 65, 0, 65.5, Navigator.Move.WALK);
        assertTrue(SchematicBuilder.inTheWayAtRest(world(), slab, new BlockPos(1, 67, 0), Blocks.STONE.defaultBlockState()));
        assertFalse(SchematicBuilder.inTheWayAtRest(world(), slab, new BlockPos(1, 68, 0), Blocks.STONE.defaultBlockState()));
    }
}
