package dev.donut.client.schematic;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class NavigatorTest {
    private static final AABB AREA = new AABB(-8, 60, -8, 8, 72, 8);

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** A floor of smooth stone at y 64 under {@link #AREA}, with {@code blocks} on it and air everywhere else. */
    private static BlockGetter world(Map<BlockPos, BlockState> blocks) {
        return new BlockGetter() {
            @Override
            public BlockState getBlockState(BlockPos pos) {
                BlockState state = blocks.get(pos);
                if (state != null) return state;
                return pos.getY() == 64 ? Blocks.SMOOTH_STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
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

    private static Navigator navigator(BlockGetter level) {
        return new Navigator(level, AREA, cell -> false, key -> false);
    }

    @Test
    void steppingIntoAHopperOverItsRimTakesAJump() {
        // From a comparator, the hopper's bowl is barely higher, but its rim is most of a block up.
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        blocks.put(new BlockPos(0, 65, 0), Blocks.COMPARATOR.defaultBlockState());
        blocks.put(new BlockPos(1, 65, 0), Blocks.HOPPER.defaultBlockState());
        Navigator.Node start = new Navigator.Node(0, 65, 0, 65.125, Navigator.Move.START);
        Navigator.Path path = navigator(world(blocks)).find(start, node -> node.x() == 1 && node.z() == 0 ? 1 : 0);
        assertNotNull(path);
        assertEquals(2, path.nodes().size());
        assertEquals(65.6875, path.end().feet(), 1e-6);
        assertEquals(Navigator.Move.JUMP, path.end().move());
    }

    @Test
    void walkingOnFlatGroundIsAWalk() {
        Navigator.Node start = new Navigator.Node(0, 64, 0, 65, Navigator.Move.START);
        Navigator.Path path = navigator(world(Map.of())).find(start, node -> node.x() == 1 && node.z() == 0 ? 1 : 0);
        assertNotNull(path);
        assertEquals(Navigator.Move.WALK, path.end().move());
    }

    @Test
    void aStepThatFailedIsWalkedAround() {
        BlockGetter level = world(Map.of());
        Navigator.Node start = new Navigator.Node(0, 64, 0, 65, Navigator.Move.START);
        Navigator.Goal goal = node -> node.x() == 2 && node.z() == 0 ? 1 : 0;
        Navigator.Path straight = navigator(level).find(start, goal);
        assertNotNull(straight);
        Navigator.Node first = straight.nodes().get(1);
        Navigator.Path around = navigator(level).avoidSteps(Set.of(Navigator.stepKey(start, first))).find(start, goal);
        assertNotNull(around);
        assertNotEquals(first, around.nodes().get(1));
        assertEquals(2, around.end().x());
    }
}
