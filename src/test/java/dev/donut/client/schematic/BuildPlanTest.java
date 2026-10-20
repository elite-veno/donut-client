package dev.donut.client.schematic;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class BuildPlanTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static int[] earlierNear(BuildPlan plan, BlockPos pos) {
        int[] near = plan.earlierNear(plan.indexOf(pos)).clone();
        Arrays.sort(near);
        return near;
    }

    @Test
    void laterGroupsWaitOnlyForEarlierBlocksCloseByInTheirLayer() {
        Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
        BlockPos stone = new BlockPos(0, 0, 0), farStone = new BlockPos(6, 0, 0), wire = new BlockPos(2, 0, 0);
        BlockPos piston = new BlockPos(3, 0, 1), above = new BlockPos(3, 1, 1);
        blocks.put(stone, Blocks.STONE.defaultBlockState());
        blocks.put(farStone, Blocks.STONE.defaultBlockState());
        blocks.put(wire, Blocks.REDSTONE_WIRE.defaultBlockState());
        blocks.put(piston, Blocks.PISTON.defaultBlockState());
        blocks.put(above, Blocks.STONE.defaultBlockState());
        BuildPlan plan = BuildPlan.of(blocks, true);

        assertArrayEquals(new int[0], earlierNear(plan, stone), "plain blocks wait for nothing");
        assertArrayEquals(new int[] {plan.indexOf(stone)}, earlierNear(plan, wire), "dust two blocks from stone waits for it");
        // The piston is a block from the dust and three from the near stone: only the dust is close enough; the stone in
        // the layer above goes in with its own layer.
        assertArrayEquals(new int[] {plan.indexOf(wire)}, earlierNear(plan, piston));

        BuildPlan anyOrder = BuildPlan.of(blocks, false);
        assertEquals(0, anyOrder.earlierNear(anyOrder.indexOf(piston)).length, "without redstone last nothing waits");
    }

    @Test
    void airShutInByBlocksAndWaterIsEnclosed() {
        // A 5x4x3 box of stone with two air cells inside, one of them walled off from the rest by water only, and an
        // air cell open to the outside through a hole in the wall.
        Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
        for (int x = 0; x < 5; x++) for (int y = 0; y < 3; y++) for (int z = 0; z < 4; z++) blocks.put(new BlockPos(x, y, z), Blocks.STONE.defaultBlockState());
        BlockPos inside = new BlockPos(1, 1, 1), behindWater = new BlockPos(3, 1, 1), water = new BlockPos(2, 1, 1);
        BlockPos open = new BlockPos(1, 1, 2), hole = new BlockPos(1, 1, 3);
        blocks.remove(inside);
        blocks.remove(behindWater);
        blocks.put(water, Blocks.WATER.defaultBlockState());
        blocks.remove(open);
        blocks.remove(hole);
        blocks.put(new BlockPos(1, 1, 2), Blocks.STONE.defaultBlockState());
        BuildPlan plan = BuildPlan.of(blocks, true);

        assertTrue(plan.enclosed(inside), "air with stone all round");
        assertTrue(plan.enclosed(behindWater), "water doesn't open it");
        assertFalse(plan.enclosed(hole), "a hole in the outer wall is outside");
        assertFalse(plan.enclosed(new BlockPos(9, 9, 9)), "outside the build");
        assertFalse(plan.enclosed(new BlockPos(2, 1, 1)), "a block of the build itself");
    }

    @Test
    void waterWaitsOnlyForTheSolidBlocksOfItsOwnLayerAndThoseBelow() {
        Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
        BlockPos lowWater = new BlockPos(1, 0, 0), stone = new BlockPos(0, 1, 0), highWater = new BlockPos(1, 1, 0);
        BlockPos topKelp = new BlockPos(1, 2, 0);
        blocks.put(lowWater, Blocks.WATER.defaultBlockState());
        blocks.put(stone, Blocks.STONE.defaultBlockState());
        blocks.put(highWater, Blocks.WATER.defaultBlockState());
        blocks.put(topKelp, Blocks.KELP.defaultBlockState());
        BuildPlan plan = BuildPlan.of(blocks, true);

        assertFalse(plan.wetWaits(plan.indexOf(lowWater)), "nothing solid in its layer or below: in right away");
        assertTrue(plan.wetWaits(plan.indexOf(highWater)), "the stone in its layer, not read yet, goes in first");
        assertTrue(plan.wetWaits(plan.indexOf(topKelp)), "a solid block in a layer below holds it too");
        assertFalse(plan.wetWaits(plan.indexOf(stone)), "solid blocks never wait for this");
    }

    @Test
    void farmlandPlacedAsDirtWaitsForTheHoe() {
        Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
        BlockPos at = new BlockPos(0, 0, 0);
        blocks.put(at, Blocks.FARMLAND.defaultBlockState());
        BuildPlan plan = BuildPlan.of(blocks, true);
        int i = plan.indexOf(at);
        assertEquals(BuildPlan.Status.PENDING, plan.update(i, Blocks.DIRT.defaultBlockState()), "dirt is not farmland, nor a wrong block");
        assertEquals(BuildPlan.Status.DONE, plan.update(i, Blocks.FARMLAND.defaultBlockState().setValue(BlockStateProperties.MOISTURE, 7)));
    }

    @Test
    void aHalfOfADoubleChestStandingAloneIsNotDone() {
        BlockState left = Blocks.CHEST.defaultBlockState().setValue(net.minecraft.world.level.block.ChestBlock.TYPE,
                net.minecraft.world.level.block.state.properties.ChestType.LEFT);
        BlockPos leftAt = new BlockPos(0, 0, 0), rightAt = BlockRules.chestPartner(leftAt, left);
        BlockState right = BlockRules.chestOther(left), single = Blocks.CHEST.defaultBlockState();
        Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
        blocks.put(leftAt, left);
        blocks.put(rightAt, right);
        BuildPlan plan = BuildPlan.of(blocks, true);
        int i = plan.indexOf(leftAt);
        BlockState air = Blocks.AIR.defaultBlockState();

        assertEquals(BuildPlan.Status.PENDING, plan.update(i, single, air), "the first half waits for the other");
        assertEquals(BuildPlan.Status.MISPLACED, plan.update(i, single, single), "two lone chests never join by themselves");
        assertEquals(BuildPlan.Status.MISPLACED, plan.update(i, single, right), "nor one alone beside a half set as joined to it");
        assertEquals(BuildPlan.Status.DONE, plan.update(i, left, right));
        assertEquals(BuildPlan.Status.DONE, plan.update(plan.indexOf(rightAt), right, left));
    }

    @Test
    void aBlockFullOfWaterCountsAsInOncePlacedDryAndGetsItsWaterWithItsLayer() {
        Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
        BlockPos chestAt = new BlockPos(0, 0, 0), hopperAt = new BlockPos(1, 0, 0), waterAt = new BlockPos(2, 0, 0);
        BlockPos stoneAt = new BlockPos(4, 0, 0);
        BlockState dry = Blocks.CHEST.defaultBlockState(), wet = dry.setValue(BlockStateProperties.WATERLOGGED, true);
        BlockState hopper = Blocks.HOPPER.defaultBlockState(), stone = Blocks.STONE.defaultBlockState();
        blocks.put(chestAt, wet);
        blocks.put(hopperAt, hopper);
        blocks.put(waterAt, Blocks.WATER.defaultBlockState());
        blocks.put(stoneAt, stone);
        BuildPlan plan = BuildPlan.of(blocks, true);
        int chest = plan.indexOf(chestAt), water = plan.indexOf(waterAt);

        assertFalse(plan.soaking(chest));
        assertFalse(plan.wetWaits(chest), "the chest itself is placed as any block");
        assertEquals(Items.CHEST, plan.item(chest));

        assertEquals(BuildPlan.Status.PENDING, plan.update(chest, dry));
        assertTrue(plan.soaking(chest), "in, but dry");
        assertEquals(Items.WATER_BUCKET, plan.item(chest), "what it takes now is a bucket");
        assertTrue(plan.wetWaits(chest), "its water waits for the layer's solid blocks, as poured water does");
        assertArrayEquals(new int[] {chest}, plan.earlierNear(plan.indexOf(hopperAt)), "the hopper beside it follows it");

        assertEquals(BuildPlan.Status.DONE, plan.update(plan.indexOf(hopperAt), hopper));
        assertEquals(BuildPlan.Status.DONE, plan.update(plan.indexOf(stoneAt), stone));
        assertFalse(plan.wetWaits(chest), "the layer's solid blocks are in: now its water");
        assertFalse(plan.wetWaits(water), "a chest waiting for its water holds up no other water");

        assertEquals(BuildPlan.Status.DONE, plan.update(chest, wet));
        assertFalse(plan.soaking(chest));
        assertEquals(3, plan.count(BuildPlan.Status.DONE), "the chest is done, with the hopper and the stone");

        assertEquals(BuildPlan.Status.PENDING, plan.update(chest, Blocks.AIR.defaultBlockState()));
        assertFalse(plan.soaking(chest), "taken away, it is a block to place again");
        assertTrue(plan.wetWaits(water), "and the water waits for it again");
    }

    @Test
    void aChestFullOfWaterIsTakenAwayThenPlacedAgainIntoWaterPouredFirst() {
        Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
        BlockPos chestAt = new BlockPos(0, 0, 0), waterAt = new BlockPos(2, 0, 0), stoneAt = new BlockPos(4, 0, 0);
        BlockState dry = Blocks.CHEST.defaultBlockState(), wet = dry.setValue(BlockStateProperties.WATERLOGGED, true);
        BlockState air = Blocks.AIR.defaultBlockState(), water = Blocks.WATER.defaultBlockState();
        blocks.put(chestAt, wet);
        blocks.put(waterAt, water);
        blocks.put(stoneAt, Blocks.STONE.defaultBlockState());
        BuildPlan plan = BuildPlan.of(blocks, true);
        int chest = plan.indexOf(chestAt), pool = plan.indexOf(waterAt);
        plan.update(plan.indexOf(stoneAt), Blocks.STONE.defaultBlockState());
        plan.update(chest, dry);
        assertTrue(plan.soaking(chest));

        plan.drain(chest);
        assertEquals(BuildPlan.Status.PENDING, plan.update(chest, air));
        assertFalse(plan.soaking(chest));
        assertTrue(plan.refilling(chest), "broken to go in again into water");
        assertTrue(plan.needsPour(chest), "its spot gets the water first");
        assertEquals(Items.WATER_BUCKET, plan.item(chest));
        assertFalse(plan.wetWaits(chest));
        assertFalse(plan.wetWaits(pool), "taken away for its water, it holds up no other water");

        assertEquals(BuildPlan.Status.PENDING, plan.update(chest, water.setValue(BlockStateProperties.LEVEL, 3)));
        assertTrue(plan.needsPour(chest), "water running in is no source to place it into");
        assertEquals(BuildPlan.Status.PENDING, plan.update(chest, water));
        assertFalse(plan.needsPour(chest), "with the source in, the chest goes in");
        assertTrue(plan.refilling(chest));
        assertEquals(Items.CHEST, plan.item(chest));

        assertEquals(BuildPlan.Status.DONE, plan.update(chest, wet));
        assertFalse(plan.refilling(chest));
        assertEquals(BuildPlan.Status.PENDING, plan.update(chest, air));
        assertFalse(plan.refilling(chest), "done once, it is a block to place like any other when it goes missing");
        assertTrue(plan.wetWaits(pool));
    }

    @Test
    void aHelperInTheBuildsOwnWaterHoldsTheWaterAndLayersAboveUntilItIsGone() {
        Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
        BlockPos floor = new BlockPos(0, 0, 0), pool = new BlockPos(1, 0, 0), wall = new BlockPos(3, 1, 0), above = new BlockPos(2, 1, 0);
        BlockState stone = Blocks.STONE.defaultBlockState(), water = Blocks.WATER.defaultBlockState();
        blocks.put(floor, stone);
        blocks.put(pool, water);
        blocks.put(wall, stone);
        blocks.put(above, water);
        BuildPlan plan = BuildPlan.of(blocks, true);
        plan.update(plan.indexOf(floor), stone);
        plan.update(plan.indexOf(wall), stone);
        assertFalse(plan.wetWaits(plan.indexOf(above)), "every solid block is in");
        assertFalse(plan.holding());

        plan.hold(pool, true);
        assertTrue(plan.holding());
        assertEquals(0, plan.heldLayerY(), "held in the bottom layer");
        assertTrue(plan.wetWaits(plan.indexOf(above)), "the water above waits for the helper in the pool to go");
        plan.hold(pool, true);
        plan.hold(pool, false);
        assertFalse(plan.wetWaits(plan.indexOf(above)), "holding twice and letting go once lets go");
        assertEquals(Integer.MAX_VALUE, plan.heldLayerY());

        plan.hold(new BlockPos(1, -5, 0), true);
        assertFalse(plan.holding(), "a spot under every layer holds nothing");
        plan.hold(above, true);
        plan.hold(pool, true);
        plan.releaseHolds();
        assertFalse(plan.holding());
        assertFalse(plan.wetWaits(plan.indexOf(above)), "released, nothing waits");
    }

    @Test
    void kelpWithNoWaterInItsSpotGetsWaterPouredFirst() {
        Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
        BlockPos floorAt = new BlockPos(0, 68, 0), kelpAt = floorAt.above();
        blocks.put(floorAt, Blocks.STONE.defaultBlockState());
        blocks.put(kelpAt, Blocks.KELP_PLANT.defaultBlockState());
        BuildPlan plan = BuildPlan.of(blocks, true);
        int kelp = plan.indexOf(kelpAt);
        assertTrue(plan.plant(kelp));
        assertFalse(plan.plant(plan.indexOf(floorAt)));
        assertEquals(BuildPlan.Status.PENDING, plan.update(kelp, Blocks.AIR.defaultBlockState()));
        assertTrue(plan.needsPour(kelp), "a dry spot: water first");
        assertEquals(Items.WATER_BUCKET, plan.item(kelp));
        BlockState flowing = Blocks.WATER.defaultBlockState().setValue(net.minecraft.world.level.block.LiquidBlock.LEVEL, 3);
        plan.update(kelp, flowing);
        assertTrue(plan.needsPour(kelp), "water running past is no water to grow in");
        BlockState falling = Blocks.WATER.defaultBlockState().setValue(net.minecraft.world.level.block.LiquidBlock.LEVEL, 8);
        plan.update(kelp, falling);
        assertFalse(plan.needsPour(kelp), "water falling down the column will do");
        plan.update(kelp, Blocks.WATER.defaultBlockState());
        assertFalse(plan.needsPour(kelp));
        assertEquals(Items.KELP, plan.item(kelp), "then the kelp itself");
    }
}
