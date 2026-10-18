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
    void cornersOverAHoleAreNotCut() {
        // A walkway of stone at y 65 bending round a hole: the diagonal over the hole's corner is never taken.
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) blocks.put(new BlockPos(x, 64, z), Blocks.AIR.defaultBlockState());
        blocks.put(new BlockPos(0, 64, 0), Blocks.STONE.defaultBlockState());
        blocks.put(new BlockPos(1, 64, 0), Blocks.STONE.defaultBlockState());
        blocks.put(new BlockPos(1, 64, -1), Blocks.STONE.defaultBlockState());
        blocks.put(new BlockPos(0, 64, -1), Blocks.AIR.defaultBlockState());
        for (int y = 60; y < 64; y++) for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) blocks.put(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
        Navigator.Node start = new Navigator.Node(0, 64, 0, 65, Navigator.Move.START);
        Navigator.Path path = navigator(world(blocks)).find(start, node -> node.x() == 1 && node.z() == -1 && node.feet() == 65 ? 1 : 0);
        assertNotNull(path);
        assertEquals(3, path.nodes().size(), "round by the side, not across the corner over the hole");
        assertEquals(1, path.nodes().get(1).x());
        assertEquals(0, path.nodes().get(1).z());
    }

    @Test
    void climbingTakesNoMoreStepsThanTheHelperBlocksCarried() {
        BlockGetter level = world(Map.of());
        Navigator.Node start = new Navigator.Node(0, 64, 0, 65, Navigator.Move.START);
        Navigator.Goal up = node -> node.feet() >= 68 ? 1 : 0;
        Navigator.Path path = new Navigator(level, AREA, cell -> true, key -> false).find(start, up);
        assertNotNull(path);
        assertEquals(3, path.nodes().stream().filter(node -> node.move() == Navigator.Move.PILLAR).count());
        assertNull(new Navigator(level, AREA, cell -> true, key -> false).pillars(2).find(start, up), "two blocks climb two steps, not three");
        assertNotNull(new Navigator(level, AREA, cell -> true, key -> false).pillars(3).find(start, up));
    }

    @Test
    void aPoorPlaceFoundFirstDoesNotEndTheSearchForAFirmOne() {
        BlockGetter level = world(Map.of());
        Navigator.Node start = new Navigator.Node(-7, 64, 0, 65, Navigator.Move.START);
        // A poor place a step away, and a far better one fourteen steps away, past where a search stops after a find.
        Navigator.Goal goal = node -> node.z() != 0 ? 0 : node.x() == -6 ? 1 : node.x() == 7 ? 10 : 0;
        long far = BlockPos.asLong(7, 64, 0);
        assertEquals(-6, navigator(level).find(start, goal).end().x(), "every place firm: the search ends soon after the first");
        assertEquals(7, navigator(level).firmWhen(key -> key == far).find(start, goal).end().x());
    }

    @Test
    void aGapABlockWideIsBridged() {
        // Two ledges two blocks over the ground, a block apart: across the gap a block goes down and is stepped on, rather
        // than down to the ground and up again by a climb of two.
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        for (int x : new int[] {0, 1, 3, 4}) {
            blocks.put(new BlockPos(x, 65, 0), Blocks.STONE.defaultBlockState());
            blocks.put(new BlockPos(x, 66, 0), Blocks.STONE.defaultBlockState());
        }
        BlockGetter level = world(blocks);
        Navigator.Node start = new Navigator.Node(1, 66, 0, 67, Navigator.Move.START);
        Navigator.Goal across = node -> node.x() == 3 && node.z() == 0 && node.feet() == 67 ? 1 : 0;
        Navigator.Path path = new Navigator(level, AREA, cell -> true, key -> false).find(start, across);
        assertNotNull(path);
        assertEquals(Navigator.Move.BRIDGE, path.nodes().get(1).move());
        assertEquals(2, path.nodes().get(1).x());
        assertEquals(67, path.nodes().get(1).feet(), 1e-6);
        assertNull(new Navigator(level, AREA, cell -> true, key -> false).pillars(0).find(start, across), "no block to bridge with");
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

    @Test
    void fencesAndClosedGatesAreNarrowTopsAndFloorsAreNot() {
        assertTrue(narrow(Blocks.SPRUCE_FENCE_GATE.defaultBlockState()), "a closed gate is a ridge a quarter of a block wide");
        assertTrue(narrow(Blocks.OAK_FENCE.defaultBlockState()));
        assertFalse(narrow(Blocks.STONE.defaultBlockState()));
        assertFalse(narrow(Blocks.HOPPER.defaultBlockState()), "a hopper's bowl floor is wide enough");
        assertFalse(narrow(Blocks.OAK_SLAB.defaultBlockState()));
    }

    @Test
    void noCornerIsCutOffANarrowTop() {
        // Standing on a closed gate, the block diagonally beside it is reached with two straight steps, not one across.
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        blocks.put(new BlockPos(0, 65, 0), Blocks.SPRUCE_FENCE_GATE.defaultBlockState());
        blocks.put(new BlockPos(1, 65, 1), Blocks.STONE.defaultBlockState());
        blocks.put(new BlockPos(1, 65, 0), Blocks.STONE.defaultBlockState());
        Navigator.Node start = new Navigator.Node(0, 65, 0, 66.5, Navigator.Move.START);
        Navigator.Path path = navigator(world(blocks)).find(start, node -> node.x() == 1 && node.z() == 1 && node.feet() == 66 ? 1 : 0);
        assertNotNull(path);
        Navigator.Node first = path.nodes().get(1);
        assertFalse(first.x() != start.x() && first.z() != start.z(), "the first step off the gate is straight");
    }

    @Test
    void noJumpLandsOnANarrowTop() {
        // A closed gate a jump up from the ground: never stood on that way.
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        blocks.put(new BlockPos(1, 65, 0), Blocks.SPRUCE_FENCE_GATE.defaultBlockState());
        Navigator.Node start = new Navigator.Node(0, 64, 0, 65, Navigator.Move.START);
        Navigator.Path path = navigator(world(blocks)).find(start, node -> node.x() == 1 && node.z() == 0 && node.y() == 65 ? 1 : 0);
        assertNull(path, "the gate's top is only reached by a jump");
    }

    private static boolean narrow(BlockState state) {
        return Navigator.narrowTop(state.getCollisionShape(world(Map.of()), BlockPos.ZERO));
    }
}
