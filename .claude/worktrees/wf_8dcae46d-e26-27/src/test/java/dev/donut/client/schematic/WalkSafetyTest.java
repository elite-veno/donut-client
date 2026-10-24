package dev.donut.client.schematic;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

/** Where walks other than planWalk's may end, and which helper blocks may be broken from where the player stands. */
class WalkSafetyTest {
    private static final AABB AREA = new AABB(-8, 60, -8, 8, 72, 8);

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** A floor of smooth stone at y 64, with {@code blocks} set and air everywhere else, under it too. */
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

    @Test
    void aWetNarrowTopOverADropIsNoWalksEnd() {
        // A closed gate with the floor gone on one side: a drop beside it.
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        blocks.put(new BlockPos(0, 65, 0), Blocks.SPRUCE_FENCE_GATE.defaultBlockState());
        blocks.put(new BlockPos(1, 64, 0), Blocks.AIR.defaultBlockState());
        Navigator.Node gate = new Navigator.Node(0, 65, 0, 66.5, Navigator.Move.START);
        SchematicBuilder.WalkEnds dry = new SchematicBuilder.WalkEnds(world(blocks), List.of());
        assertFalse(dry.wet(gate));
        assertFalse(dry.unsafe(gate), "dry, it is walked onto crouched");
        assertEquals(1, dry.guard(node -> 1).score(gate));
        // Water spread over the top: never a walk's end.
        blocks.put(new BlockPos(0, 66, 0), Blocks.WATER.defaultBlockState());
        SchematicBuilder.WalkEnds wet = new SchematicBuilder.WalkEnds(world(blocks), List.of());
        assertTrue(wet.unsafe(gate));
        assertEquals(0, wet.guard(node -> 1).score(gate));
    }

    @Test
    void waterOnAFloorWithNoDropBesideIsOnlyALastResort() {
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        blocks.put(new BlockPos(1, 65, 0), Blocks.WATER.defaultBlockState());
        BlockGetter level = world(blocks);
        SchematicBuilder.WalkEnds ends = new SchematicBuilder.WalkEnds(level, List.of());
        Navigator.Node inWater = new Navigator.Node(1, 64, 0, 65, Navigator.Move.WALK);
        assertTrue(ends.wet(inWater));
        assertFalse(ends.unsafe(inWater), "wet, but nothing to be carried down into");
        // A walk to where a block is in reach from either: the dry place, a few steps further, is taken.
        Navigator.Node start = new Navigator.Node(0, 64, 0, 65, Navigator.Move.START);
        Navigator.Goal goal = node -> node.z() == 0 && (node.x() == 1 || node.x() == 4) ? 1 : 0;
        Navigator.Path path = ends.dryFirst(new Navigator(level, AREA, cell -> false, key -> false)).find(start, ends.guard(goal));
        assertNotNull(path);
        assertEquals(4, path.end().x());
        // Only the wet one in reach: it is taken after all, as a drop the build needs back may lie in a stream.
        Navigator.Path wetOnly = ends.dryFirst(new Navigator(level, AREA, cell -> false, key -> false))
                .find(start, ends.guard(node -> node.z() == 0 && node.x() == 1 ? 1 : 0));
        assertNotNull(wetOnly);
        assertEquals(1, wetOnly.end().x());
    }

    @Test
    void aHelperHeldByItsRimAloneIsNotBrokenUnderThePlayer() {
        // A helper block on the floor, a deep gap beside it: the player leans out over the gap, crouched at the end of a
        // bridging step whose block didn't go in, held up only by the helper's rim.
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        BlockPos helper = new BlockPos(0, 65, 0);
        blocks.put(helper, Blocks.DIRT.defaultBlockState());
        blocks.put(new BlockPos(1, 64, 0), Blocks.AIR.defaultBlockState());
        AABB leaning = body(1.2, 66, 0.5);
        assertFalse(SchematicBuilder.keepsFooting(world(blocks), leaning, true, helper), "the middle is over the gap");
        assertTrue(SchematicBuilder.keepsFooting(world(blocks), leaning, false, helper), "in the air nothing holds them anyway");
        // A slab under the middle, half a block down: with the rim gone the player comes down onto it.
        blocks.put(new BlockPos(1, 64, 0), Blocks.STONE.defaultBlockState());
        blocks.put(new BlockPos(1, 65, 0), Blocks.STONE_SLAB.defaultBlockState());
        assertTrue(SchematicBuilder.keepsFooting(world(blocks), leaning, true, helper));
        // A block of its own under the middle: the helper only touches the rim of the body.
        blocks.put(new BlockPos(1, 65, 0), Blocks.STONE.defaultBlockState());
        assertTrue(SchematicBuilder.keepsFooting(world(blocks), leaning, true, helper));
    }

    @Test
    void theBlockUnderTheMiddleGoesOnlyWithGroundUnderIt() {
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        BlockPos top = new BlockPos(0, 65, 0);
        blocks.put(top, Blocks.DIRT.defaultBlockState());
        AABB onTop = body(0.5, 66, 0.5);
        assertTrue(SchematicBuilder.keepsFooting(world(blocks), onTop, true, top), "down a pillar onto the floor");
        blocks.put(new BlockPos(0, 64, 0), Blocks.AIR.defaultBlockState());
        assertFalse(SchematicBuilder.keepsFooting(world(blocks), onTop, true, top), "a bridge block over a gap");
        // Somewhere else, not under the body: no matter.
        assertTrue(SchematicBuilder.keepsFooting(world(blocks), body(3.5, 65, 3.5), true, top));
    }

    /** A standing player's bounding box with the feet's middle at x, y, z. */
    private static AABB body(double x, double y, double z) {
        return new AABB(x - 0.3, y, z - 0.3, x + 0.3, y + 1.8, z + 0.3);
    }
}
