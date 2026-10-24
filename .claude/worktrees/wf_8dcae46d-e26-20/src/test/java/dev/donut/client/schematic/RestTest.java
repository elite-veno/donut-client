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

/** Where a walk comes to rest, a little off the middle of its place to stand, and when it steps back to that middle. */
class RestTest {
    /** Standing on the floor at y 64, the feet at 65. */
    private static final Navigator.Node HERE = new Navigator.Node(0, 64, 0, 65, Navigator.Move.WALK);

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** A floor of stone at y 64, with {@code blocks} set and air everywhere else. */
    private static BlockGetter world(Map<BlockPos, BlockState> blocks) {
        return new BlockGetter() {
            @Override
            public BlockState getBlockState(BlockPos pos) {
                BlockState state = blocks.get(pos);
                if (state != null) return state;
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

    @Test
    void aRestWithinTheSpreadTheLooksAllowForStaysPut() {
        BlockGetter level = world(Map.of());
        assertFalse(SchematicBuilder.stepsBackToMiddle(level, new Vec3(0.5, 65, 0.5), HERE), "at the middle");
        assertFalse(SchematicBuilder.stepsBackToMiddle(level, new Vec3(0.7, 65, 0.3), HERE), "0.2 off each way");
        assertFalse(SchematicBuilder.stepsBackToMiddle(level, new Vec3(0.74, 65, 0.74), HERE), "just inside the corner");
    }

    @Test
    void aRestThatSlidOnPastTheMiddleStepsBack() {
        BlockGetter level = world(Map.of());
        // As logged: a walk one block west came to rest at x 15.133 on the place at x 15, its body in the next block.
        assertTrue(SchematicBuilder.stepsBackToMiddle(level, new Vec3(0.133, 65, 0.513), HERE), "0.37 off along x");
        assertTrue(SchematicBuilder.stepsBackToMiddle(level, new Vec3(0.5, 65, 0.8), HERE), "0.3 off along z");
        // Held up by the rim of the place's block, the feet over the gap beside it.
        assertTrue(SchematicBuilder.stepsBackToMiddle(level, new Vec3(-0.2, 65, 0.5), HERE), "over the edge");
    }

    @Test
    void notOnANarrowTopOverADrop() {
        // A closed gate on a block over the floor: off its top the feet drop two blocks, and the player stands crouched.
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        blocks.put(new BlockPos(0, 65, 0), Blocks.STONE.defaultBlockState());
        blocks.put(new BlockPos(0, 66, 0), Blocks.SPRUCE_FENCE_GATE.defaultBlockState());
        Navigator.Node gate = new Navigator.Node(0, 66, 0, 67.5, Navigator.Move.WALK);
        assertFalse(SchematicBuilder.stepsBackToMiddle(world(blocks), new Vec3(0.85, 67.5, 0.5), gate));
    }
}
