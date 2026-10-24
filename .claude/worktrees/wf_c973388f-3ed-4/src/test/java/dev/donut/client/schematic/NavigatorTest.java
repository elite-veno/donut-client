package dev.donut.client.schematic;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BubbleColumnBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
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

    @Test
    void alongAGateRowTheTopsMeetAndAcrossItTheyDont() {
        // Closed gates facing north run along x as one ridge; side by side along z, a gap lies between their tops.
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        blocks.put(new BlockPos(0, 65, 0), Blocks.SPRUCE_FENCE_GATE.defaultBlockState());
        blocks.put(new BlockPos(1, 65, 0), Blocks.SPRUCE_FENCE_GATE.defaultBlockState());
        blocks.put(new BlockPos(0, 65, 1), Blocks.SPRUCE_FENCE_GATE.defaultBlockState());
        BlockGetter level = world(blocks);
        Navigator.Node start = new Navigator.Node(0, 65, 0, 66.5, Navigator.Move.START);
        assertTrue(Navigator.supportedAlong(level, start, new Navigator.Node(1, 65, 0, 66.5, Navigator.Move.WALK)));
        assertFalse(Navigator.supportedAlong(level, start, new Navigator.Node(0, 65, 1, 66.5, Navigator.Move.WALK)));
        assertFalse(Navigator.supportedAlong(level, start, new Navigator.Node(1, 64, 0, 65, Navigator.Move.DROP)), "not at one height");
    }

    private static boolean narrow(BlockState state) {
        return Navigator.narrowTop(state.getCollisionShape(world(Map.of()), BlockPos.ZERO));
    }

    // Deep water ---------------------------------------------------------------------------------------------------

    private static BlockState stone() { return Blocks.STONE.defaultBlockState(); }

    private static BlockState water() { return Blocks.WATER.defaultBlockState(); }

    private static BlockState bubbles(boolean dragging) {
        return Blocks.BUBBLE_COLUMN.defaultBlockState().setValue(BubbleColumnBlock.DRAG_DOWN, dragging);
    }

    /** Stone in the eight columns round x, z from y0 to y1: the walls of a shaft a block wide. */
    private static void walls(Map<BlockPos, BlockState> blocks, int x, int z, int y0, int y1) {
        for (int y = y0; y <= y1; y++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx != 0 || dz != 0) blocks.put(new BlockPos(x + dx, y, z + dz), stone());
                }
            }
        }
    }

    /**
     * A shaft a block wide at 0, 0 on the floor at y 64, walled with stone up to y 68, two over the top water cell, except
     * east, where the wall stops at y 67: a ledge with its top at 68, one over the top water cell and 0.11 over a still
     * surface (67.89). {@code column} fills the shaft from y 65 up.
     */
    private static Map<BlockPos, BlockState> shaft(BlockState... column) {
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        walls(blocks, 0, 0, 65, 68);
        blocks.remove(new BlockPos(1, 68, 0));
        for (int i = 0; i < column.length; i++) blocks.put(new BlockPos(0, 65 + i, 0), column[i]);
        return blocks;
    }

    /** On the floor at the bottom of the shaft, the feet in its lowest water: where a player who sank stands. */
    private static Navigator.Node shaftBottom() { return new Navigator.Node(0, 64, 0, 65, Navigator.Move.START); }

    /** Anywhere out of the shaft's column with the feet at 68 or higher. */
    private static double out(Navigator.Node node) { return (node.x() != 0 || node.z() != 0) && node.feet() >= 68 - 1e-6 ? 1 : 0; }

    @Test
    void outOfAShaftOfWaterBySwimmingUpAndClimbingOntoTheLedge() {
        // As a player who fell into a well: up the water a block at a time, jump held, and out over the edge where the wall
        // is low enough.
        Navigator.Path path = navigator(world(shaft(water(), water(), water()))).find(shaftBottom(), NavigatorTest::out);
        assertNotNull(path, "swims up the shaft and climbs out at the top");
        assertEquals(new BlockPos(1, 67, 0), path.end().floor());
        assertEquals(68, path.end().feet(), 1e-6);
        List<Navigator.Node> nodes = path.nodes();
        for (Navigator.Node node : nodes.subList(0, nodes.size() - 1)) {
            assertEquals(0, node.x(), "straight up the shaft");
            assertEquals(0, node.z(), "straight up the shaft");
        }
        for (int k = 1; k < nodes.size(); k++) assertTrue(nodes.get(k).feet() >= nodes.get(k - 1).feet() - 1e-6, "never down");
        assertEquals(2, nodes.stream().filter(node -> node.move() == Navigator.Move.SWIM).count(), "up to the top water, a block a swim");
        assertEquals(Navigator.Move.SWIM, nodes.get(nodes.size() - 2).move(), "out from afloat at the top");
        assertEquals(67, nodes.get(nodes.size() - 2).feet(), 1e-6);
    }

    @Test
    void aWayNeverEndsAfloat() {
        // Only places in the water of the shaft score: no way ends there, however near.
        Navigator.Path path = navigator(world(shaft(water(), water(), water()))).find(shaftBottom(),
                node -> node.x() == 0 && node.z() == 0 && node.feet() >= 66 ? 1 : 0);
        assertNull(path);
    }

    @Test
    void kelpInTheShaftIsSwumThrough() {
        // Kelp holds water and stops no one: its stem at the bottom and its top above with water over it, or kelp right up
        // into the top water cell.
        BlockState plant = Blocks.KELP_PLANT.defaultBlockState(), kelp = Blocks.KELP.defaultBlockState();
        for (BlockState[] column : new BlockState[][] {{plant, kelp, water()}, {plant, plant, kelp}}) {
            Navigator.Path path = navigator(world(shaft(column))).find(shaftBottom(), NavigatorTest::out);
            assertNotNull(path, "up through " + column[2]);
            assertEquals(new BlockPos(1, 67, 0), path.end().floor());
            assertEquals(68, path.end().feet(), 1e-6);
        }
    }

    @Test
    void outOfAPoolTwoDeep() {
        // Water two deep, three by three (y 65-66, surface 66.89), walled round with stone up to y 66: the rim's top at 67.
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                for (int y = 65; y <= 66; y++) blocks.put(new BlockPos(x, y, z), Math.abs(x) == 2 || Math.abs(z) == 2 ? stone() : water());
            }
        }
        Navigator.Path path = navigator(world(blocks)).find(shaftBottom(), node -> node.feet() >= 67 - 1e-6 ? 1 : 0);
        assertNotNull(path, "swims up beside the rim and climbs out");
        Navigator.Node end = path.end(), before = path.nodes().get(path.nodes().size() - 2);
        assertEquals(67, end.feet(), 1e-6);
        assertTrue(Math.abs(end.x()) == 2 || Math.abs(end.z()) == 2, "on the rim");
        assertEquals(Navigator.Move.SWIM, before.move());
        assertTrue(end.x() == before.x() || end.z() == before.z(), "out straight over the rim, not across a corner");
    }

    @Test
    void aBubbleColumnDraggingDownIsNeverSwumUpAndOneLiftingIs() {
        // Magma under the shaft: its water is a column dragging down onto the magma. No way up it, though the ledge is there.
        Map<BlockPos, BlockState> blocks = shaft(bubbles(true), bubbles(true), bubbles(true));
        blocks.put(new BlockPos(0, 64, 0), Blocks.MAGMA_BLOCK.defaultBlockState());
        assertNull(navigator(world(blocks)).find(shaftBottom(), NavigatorTest::out), "dragged down onto the magma");
        // Soul sand under it: a column lifting the player, up and out onto the ledge.
        blocks = shaft(bubbles(false), bubbles(false), bubbles(false));
        blocks.put(new BlockPos(0, 64, 0), Blocks.SOUL_SAND.defaultBlockState());
        Navigator.Path path = navigator(world(blocks)).find(new Navigator.Node(0, 64, 0, 64.875, Navigator.Move.START), NavigatorTest::out);
        assertNotNull(path);
        assertEquals(new BlockPos(1, 67, 0), path.end().floor());
    }

    @Test
    void aChannelTwoDeepIsSwumAcrossButNotOneDraggingDown() {
        // A channel a block wide along z, two deep (y 63-64, surface 64.89) in the floor at 64, stone under and beside it:
        // the floor on the far side, top 65, is one over the top water cell. There is no way round it.
        for (boolean drags : new boolean[] {false, true}) {
            Map<BlockPos, BlockState> blocks = new HashMap<>();
            for (int z = -8; z < 8; z++) {
                blocks.put(new BlockPos(0, 62, z), drags ? Blocks.MAGMA_BLOCK.defaultBlockState() : stone());
                for (int y = 63; y <= 64; y++) blocks.put(new BlockPos(0, y, z), drags ? bubbles(true) : water());
                for (int x : new int[] {-1, 1}) {
                    blocks.put(new BlockPos(x, 62, z), stone());
                    blocks.put(new BlockPos(x, 63, z), stone());
                }
            }
            Navigator.Node start = new Navigator.Node(-1, 64, 0, 65, Navigator.Move.START);
            Navigator.Path path = navigator(world(blocks)).find(start, node -> node.x() > 0 ? 1 : 0);
            if (drags) {
                assertNull(path, "into a column dragging down nobody steps");
            } else {
                assertNotNull(path, "in, up and out");
                assertEquals(65, path.end().feet(), 1e-6);
                assertTrue(path.nodes().stream().anyMatch(node -> node.move() == Navigator.Move.SWIM));
            }
        }
    }

    @Test
    void aShortTrenchIsWalkedRoundRatherThanSwum() {
        // The same channel five long: round its end on foot costs less than in, up and out again.
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        for (int z = -2; z <= 2; z++) {
            blocks.put(new BlockPos(0, 62, z), stone());
            for (int y = 63; y <= 64; y++) blocks.put(new BlockPos(0, y, z), water());
            for (int x : new int[] {-1, 1}) {
                blocks.put(new BlockPos(x, 62, z), stone());
                blocks.put(new BlockPos(x, 63, z), stone());
            }
        }
        Navigator.Node start = new Navigator.Node(-1, 64, 0, 65, Navigator.Move.START);
        Navigator.Path path = navigator(world(blocks)).find(start, node -> node.x() == 1 && node.z() == 0 && node.feet() == 65 ? 1 : 0);
        assertNotNull(path);
        assertTrue(path.nodes().stream().allMatch(node -> node.move() != Navigator.Move.SWIM && node.feet() >= 65 - 1e-6), "round, dry");
    }

    @Test
    void noWayOutOfAShaftWithNoLedgeAtTheTopOrALidOverIt() {
        // Walls two over the top water cell all round: from the top a swimmer can't get onto them.
        Map<BlockPos, BlockState> blocks = shaft(water(), water(), water());
        blocks.put(new BlockPos(1, 68, 0), stone());
        assertNull(navigator(world(blocks)).find(shaftBottom(), NavigatorTest::out));
        // The ledge there, but a lid right over the water, or one a block higher: the head stops under it before the feet
        // are out of the water, and the lift out needs the body raised 0.6 clear of water and blocks.
        for (int lid : new int[] {68, 69}) {
            Map<BlockPos, BlockState> shut = shaft(water(), water(), water());
            shut.put(new BlockPos(0, lid, 0), stone());
            assertNull(navigator(world(shut)).find(shaftBottom(), NavigatorTest::out), "lid at y " + lid);
        }
    }

    /**
     * A kelp shaft beside a row of closed gates, as in a kelp farm: five long along z at x 0, on the floor at y 64, kelp at
     * the bottom, falling water, a source on top (surface 67.89). West: a dispenser, a piston and glass, its top at 68 with
     * nothing over it. East: closed gates facing east at y 66, a ridge from x 1.375 to 1.625 with its top at 67.5, flowing
     * water over and under them, and a wall of the same gates at x 2. Glass at both ends, z -3 and 3; with {@code lid},
     * glass over x 0 to 2 at y 68 too.
     */
    private static Map<BlockPos, BlockState> kelpShaft(boolean lid) {
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        BlockState glass = Blocks.GLASS.defaultBlockState();
        BlockState gate = Blocks.SPRUCE_FENCE_GATE.defaultBlockState().setValue(FenceGateBlock.FACING, Direction.EAST);
        BlockState flowing = water().setValue(LiquidBlock.LEVEL, 1), falling = water().setValue(LiquidBlock.LEVEL, 8);
        for (int z = -2; z <= 2; z++) {
            blocks.put(new BlockPos(0, 65, z), Blocks.KELP_PLANT.defaultBlockState());
            blocks.put(new BlockPos(0, 66, z), falling);
            blocks.put(new BlockPos(0, 67, z), water());
            blocks.put(new BlockPos(-1, 65, z), Blocks.DISPENSER.defaultBlockState());
            blocks.put(new BlockPos(-1, 66, z), Blocks.PISTON.defaultBlockState());
            blocks.put(new BlockPos(-1, 67, z), glass);
            blocks.put(new BlockPos(1, 65, z), flowing);
            blocks.put(new BlockPos(1, 66, z), gate);
            blocks.put(new BlockPos(1, 67, z), flowing);
            for (int y = 65; y <= 67; y++) blocks.put(new BlockPos(2, y, z), gate);
            if (lid) for (int x = 0; x <= 2; x++) blocks.put(new BlockPos(x, 68, z), glass);
        }
        for (int x = -1; x <= 2; x++) {
            for (int y = 65; y <= 67; y++) {
                blocks.put(new BlockPos(x, y, -3), glass);
                blocks.put(new BlockPos(x, y, 3), glass);
            }
        }
        return blocks;
    }

    /** Out of the kelp shaft with the feet at 67.5 or higher: on the glass, or on a gate's top. */
    private static double outOfKelp(Navigator.Node node) {
        return !(node.x() == 0 && Math.abs(node.z()) <= 2) && node.feet() >= 67.5 - 1e-6 ? 1 : 0;
    }

    @Test
    void outOfAKelpShaftOntoTheGlassNeverOntoAGateTop() {
        BlockGetter level = world(kelpShaft(false));
        Navigator.Node start = new Navigator.Node(0, 64, -2, 65, Navigator.Move.START);
        Navigator.Path path = navigator(level).find(start, NavigatorTest::outOfKelp);
        assertNotNull(path, "up through the kelp and out onto the glass");
        assertEquals(68, path.end().feet(), 1e-6);
        assertTrue(level.getBlockState(path.end().floor()).is(Blocks.GLASS));
        assertTrue(path.end().floor().equals(new BlockPos(-1, 67, -2)) || path.end().floor().equals(new BlockPos(0, 67, -3)),
                "out beside where the player came up: west, or over the end");
        assertTrue(path.nodes().stream().noneMatch(node -> level.getBlockState(node.floor()).is(Blocks.SPRUCE_FENCE_GATE)),
                "never out onto a gate's top, a ridge the body comes up beside as often as on");
        assertNull(navigator(world(kelpShaft(true))).find(start, NavigatorTest::outOfKelp), "with the lid on, no way out");
    }

    @Test
    void afloatAgainstAGateWallThePlayerIsInTheWaterNotOnTheGateTop() {
        // Bobbing at the top of the kelp shaft, pressed against the thin wall of a gate beside it: the middle of the body is
        // over the gate's block, its top 0.3 over the feet. The player is afloat in the shaft's water, and swims out of it.
        BlockGetter level = world(kelpShaft(false));
        Vec3 at = new Vec3(1.075, 67.21, -1.5);
        Navigator.Node here = Navigator.standingAt(level, at, body(at), false);
        assertEquals(new Navigator.Node(0, 67, -2, 67, Navigator.Move.SWIM), here);
        Navigator.Path path = navigator(level).find(here, NavigatorTest::outOfKelp);
        assertNotNull(path);
        assertTrue(level.getBlockState(path.end().floor()).is(Blocks.GLASS));
    }

    @Test
    void sinkingOverTheFloorOfTheWaterThePlayerStandsOnIt() {
        BlockGetter level = world(shaft(water(), water(), water()));
        Vec3 at = new Vec3(0.5, 65.4, 0.5);
        Navigator.Node here = Navigator.standingAt(level, at, body(at), false);
        assertEquals(new BlockPos(0, 64, 0), here.floor());
        assertEquals(65, here.feet(), 1e-6);
        assertNotEquals(Navigator.Move.SWIM, here.move());
        // Higher up the water, afloat in it.
        at = new Vec3(0.5, 66.3, 0.5);
        assertEquals(new Navigator.Node(0, 66, 0, 66, Navigator.Move.SWIM), Navigator.standingAt(level, at, body(at), false));
    }

    @Test
    void jumpingAlongTheEdgeOfAPoolThePlayerComesDownOnTheEdge() {
        // Over the rim and the water beside it at once, a little above the rim's top: down on the rim, not into the water.
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                for (int y = 65; y <= 66; y++) blocks.put(new BlockPos(x, y, z), Math.abs(x) == 2 || Math.abs(z) == 2 ? stone() : water());
            }
        }
        Vec3 at = new Vec3(1.85, 67.3, 0.5);
        Navigator.Node here = Navigator.standingAt(world(blocks), at, body(at), false);
        assertEquals(new BlockPos(2, 66, 0), here.floor());
        assertEquals(67, here.feet(), 1e-6);
    }

    /** A standing player's body with the feet at {@code at}. */
    private static AABB body(Vec3 at) {
        return new AABB(at.x - 0.3, at.y, at.z - 0.3, at.x + 0.3, at.y + 1.8, at.z + 0.3);
    }
}
