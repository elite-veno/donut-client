package dev.donut.client.schematic;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Taking back the helper blocks walks leave in cells the schematic leaves empty, before the build closes them in. */
class HelperReclaimTest {
    private static final AABB AREA = new AABB(-8, 58, -8, 8, 72, 8);
    private static final double REACH = 4.25;
    /** Read once the game is bootstrapped: blocks can't be had before. */
    private static BlockState stone, dirt, air;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        stone = Blocks.STONE.defaultBlockState();
        dirt = Blocks.DIRT.defaultBlockState();
        air = Blocks.AIR.defaultBlockState();
    }

    /** Stone up to y 64, air above, with {@code blocks} set. */
    private static BlockGetter world(Map<BlockPos, BlockState> blocks) {
        return new BlockGetter() {
            @Override
            public BlockState getBlockState(BlockPos pos) {
                BlockState state = blocks.get(pos);
                if (state != null) return state;
                return pos.getY() <= 64 ? stone : air;
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

    /** A search on foot, as the one that tells whether a helper block is still in view: no climbing, no bridges. */
    private static Navigator onFoot(BlockGetter level) {
        return new Navigator(level, AREA, cell -> false, key -> false).limit(3000);
    }

    /** Whether a straight look from a place to stand at the middle of the block at pos meets that block first, in reach. */
    private static Predicate<Navigator.Node> sees(BlockGetter level, BlockPos pos) {
        return node -> {
            Vec3 eye = node.eye();
            if (new AABB(pos).distanceToSqr(eye) > REACH * REACH) return false;
            BlockHitResult hit = level.clip(new ClipContext(eye, Vec3.atCenterOf(pos), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE,
                    CollisionContext.empty()));
            return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos);
        };
    }

    private static boolean closes(BlockGetter now, BlockPos placed, BlockPos helper, Navigator.Node start) {
        BlockGetter after = new Overlay(now, Map.of(placed.asLong(), stone));
        return SchematicBuilder.wouldCloseIn(onFoot(now), start, sees(now, helper), onFoot(after), start, sees(after, helper), helper);
    }

    /** A pit a block deep beside where the player stands, with a helper block at its bottom, as a bridge over a channel leaves. */
    private static Map<BlockPos, BlockState> pit(BlockPos helper) {
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        blocks.put(helper.above(), air);
        blocks.put(helper, dirt);
        return blocks;
    }

    @Test
    void aLidOverThePitClosesTheHelperBlockIn() {
        BlockPos helper = new BlockPos(0, 63, 0);
        BlockGetter level = world(pit(helper));
        Navigator.Node start = new Navigator.Node(2, 64, 0, 65, Navigator.Move.START);
        assertTrue(closes(level, helper.above(), helper, start), "seen from beside the pit now, from nowhere with the lid on");
    }

    @Test
    void aBlockElsewhereClosesNothingIn() {
        BlockPos helper = new BlockPos(0, 63, 0);
        BlockGetter level = world(pit(helper));
        Navigator.Node start = new Navigator.Node(2, 64, 0, 65, Navigator.Move.START);
        assertFalse(closes(level, new BlockPos(3, 65, 2), helper, start));
        assertFalse(closes(level, new BlockPos(1, 65, 0), helper, start), "beside the pit, it is still seen from the other sides");
    }

    @Test
    void aHelperBlockOutOfViewAlreadyIsNotThisBlocksDoing() {
        // At the bottom of a shaft two deep: only from in the shaft, on top of its own column, does a look reach it.
        BlockPos helper = new BlockPos(0, 62, 0);
        Map<BlockPos, BlockState> blocks = pit(helper);
        blocks.put(new BlockPos(0, 64, 0), air);
        BlockGetter level = world(blocks);
        Navigator.Node start = new Navigator.Node(2, 64, 0, 65, Navigator.Move.START);
        assertFalse(closes(level, new BlockPos(0, 64, 0), helper, start));
    }

    @Test
    void standingOnItsOwnColumnNeverCounts() {
        BlockPos helper = new BlockPos(0, 63, 0);
        assertTrue(SchematicBuilder.onColumn(new Navigator.Node(0, 63, 0, 64, Navigator.Move.START), helper), "on it");
        assertTrue(SchematicBuilder.onColumn(new Navigator.Node(0, 65, 0, 66, Navigator.Move.START), helper), "on blocks stacked on it");
        assertFalse(SchematicBuilder.onColumn(new Navigator.Node(1, 63, 0, 64, Navigator.Move.START), helper), "beside it");
        assertFalse(SchematicBuilder.onColumn(new Navigator.Node(0, 60, 0, 61, Navigator.Move.START), helper), "under it");
    }

    @Test
    void onlyWhereBlocksStillToGoInStandOverItCanTheBuildCloseItIn() {
        Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
        BlockPos roof = new BlockPos(1, 3, 0);
        blocks.put(new BlockPos(0, 0, 0), stone);
        blocks.put(new BlockPos(6, 0, 0), stone);
        blocks.put(roof, stone);
        blocks.put(new BlockPos(6, 3, 0), stone);
        BuildPlan plan = BuildPlan.of(blocks, false);
        BlockPos under = new BlockPos(0, 1, 0);
        assertTrue(SchematicBuilder.closedInLater(plan, under), "a roof block still to go in stands over the cell beside it");
        assertFalse(SchematicBuilder.closedInLater(plan, new BlockPos(3, 1, 0)), "nothing still to go in over it");
        assertFalse(SchematicBuilder.closedInLater(plan, new BlockPos(0, 1, 5)), "off the build's sides");
        plan.update(plan.indexOf(roof), stone);
        assertFalse(SchematicBuilder.closedInLater(plan, under), "with the roof in, nothing left to go in can close it in");
    }

    @Test
    void aWalksHelperBlockGoesOnceTheBuildIsPastItOrHelperBlocksRunShort() {
        assertFalse(SchematicBuilder.reclaimDue(false, false, false, false, 70, 71), "a layer under the one being built: walks still stand there");
        assertTrue(SchematicBuilder.reclaimDue(false, false, false, false, 69, 71), "two layers under: the build has gone on past it");
        assertTrue(SchematicBuilder.reclaimDue(false, false, false, true, 75, 71), "short of helper blocks, any");
        assertFalse(SchematicBuilder.reclaimDue(false, true, false, true, 60, 71), "taken back once and put there again, it stays");
        assertFalse(SchematicBuilder.reclaimDue(false, false, true, true, 60, 71), "the last walk stood on it");
        assertTrue(SchematicBuilder.reclaimDue(true, true, true, false, 75, 71), "one the build would close in always goes");
        assertFalse(SchematicBuilder.reclaimDue(false, false, false, false, 60, Integer.MIN_VALUE), "no layer chosen yet");
    }

    @Test
    void supportJudgedWithoutHelperBlocksIsWorkedOutAgainWithThem() {
        long blocks = 123_456_789L;
        assertEquals(blocks, SchematicBuilder.supportStamp(blocks, true), "with helper blocks, the blocks round it alone");
        assertNotEquals(blocks, SchematicBuilder.supportStamp(blocks, false));
        // A fingerprint that would come to 0, which stands for nothing worked out yet.
        assertNotEquals(0L, SchematicBuilder.supportStamp(1_785_168_781_326_730_801L, false));
    }
}
