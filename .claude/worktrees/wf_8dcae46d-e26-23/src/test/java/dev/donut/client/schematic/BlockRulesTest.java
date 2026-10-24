package dev.donut.client.schematic;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ComparatorMode;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.level.block.state.properties.RedstoneSide;
import net.minecraft.world.level.block.state.properties.SlabType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class BlockRulesTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static BlockState repeater(Direction facing, int delay) {
        return Blocks.REPEATER.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, facing)
                .setValue(BlockStateProperties.DELAY, delay);
    }

    @Test
    void secondHalvesComeWithTheirBlockAndOnlyFluidSourcesArePoured() {
        BlockState door = Blocks.OAK_DOOR.defaultBlockState();
        assertEquals(BlockRules.Kind.PLACE, BlockRules.kind(door.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER)));
        assertEquals(BlockRules.Kind.AUTO, BlockRules.kind(door.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER)));
        assertEquals(BlockRules.Kind.AUTO, BlockRules.kind(Blocks.BED.red().defaultBlockState().setValue(BlockStateProperties.BED_PART, BedPart.HEAD)));
        assertEquals(BlockRules.Kind.PLACE, BlockRules.kind(Blocks.BED.red().defaultBlockState().setValue(BlockStateProperties.BED_PART, BedPart.FOOT)));
        assertEquals(BlockRules.Kind.AUTO, BlockRules.kind(Blocks.PISTON_HEAD.defaultBlockState()));
        assertEquals(BlockRules.Kind.FLUID, BlockRules.kind(Blocks.WATER.defaultBlockState()));
        assertEquals(BlockRules.Kind.AUTO, BlockRules.kind(Blocks.WATER.defaultBlockState().setValue(BlockStateProperties.LEVEL, 3)));
        assertEquals(BlockRules.Kind.UNPLACEABLE, BlockRules.kind(Blocks.FIRE.defaultBlockState()));
        assertEquals(BlockRules.Kind.PLACE, BlockRules.kind(Blocks.REDSTONE_WIRE.defaultBlockState()), "dust is placed with redstone");
        assertEquals(BlockRules.Kind.PLACE, BlockRules.kind(Blocks.WALL_TORCH.defaultBlockState()), "a wall torch is placed with a torch");
    }

    @Test
    void placingDecidesFacingButNotWhatClicksOrNeighborsSet() {
        BlockState target = repeater(Direction.EAST, 3).setValue(BlockStateProperties.POWERED, true);
        assertTrue(BlockRules.placementMatches(repeater(Direction.EAST, 1), target));
        assertFalse(BlockRules.placementMatches(repeater(Direction.NORTH, 3), target));
        assertFalse(BlockRules.placementMatches(Blocks.COMPARATOR.defaultBlockState(), target));
        BlockState stairs = Blocks.OAK_STAIRS.defaultBlockState().setValue(BlockStateProperties.HALF, Half.TOP);
        assertFalse(BlockRules.placementMatches(Blocks.OAK_STAIRS.defaultBlockState(), stairs), "upside-down stairs");
        BlockState wire = Blocks.REDSTONE_WIRE.defaultBlockState().setValue(BlockStateProperties.NORTH_REDSTONE, RedstoneSide.UP);
        assertTrue(BlockRules.placementMatches(Blocks.REDSTONE_WIRE.defaultBlockState(), wire), "wire connections follow the neighbors");
    }

    @Test
    void aSlabStartsADoubleSlabAndRailsOnlyKeepTheirAxis() {
        BlockState slab = Blocks.STONE_SLAB.defaultBlockState();
        BlockState bottom = slab.setValue(BlockStateProperties.SLAB_TYPE, SlabType.BOTTOM), top = slab.setValue(BlockStateProperties.SLAB_TYPE, SlabType.TOP);
        BlockState full = slab.setValue(BlockStateProperties.SLAB_TYPE, SlabType.DOUBLE);
        assertTrue(BlockRules.placementMatches(bottom, full));
        assertTrue(BlockRules.placementMatches(top, full));
        assertFalse(BlockRules.placementMatches(top, bottom));
        assertEquals(1, BlockRules.missing(bottom, full));
        assertFalse(BlockRules.satisfied(bottom, full));
        assertTrue(BlockRules.satisfied(full, full));
        BlockState rail = Blocks.POWERED_RAIL.defaultBlockState();
        BlockState up = rail.setValue(BlockStateProperties.RAIL_SHAPE_STRAIGHT, RailShape.ASCENDING_NORTH);
        assertTrue(BlockRules.placementMatches(rail.setValue(BlockStateProperties.RAIL_SHAPE_STRAIGHT, RailShape.NORTH_SOUTH), up));
        assertFalse(BlockRules.placementMatches(rail.setValue(BlockStateProperties.RAIL_SHAPE_STRAIGHT, RailShape.EAST_WEST), up));
    }

    @Test
    void countsTakeMoreOfTheirItem() {
        BlockState candles = Blocks.CANDLE.defaultBlockState();
        assertEquals(2, BlockRules.missing(candles.setValue(BlockStateProperties.CANDLES, 2), candles.setValue(BlockStateProperties.CANDLES, 4)));
        assertEquals(3, BlockRules.itemsNeeded(Blocks.AIR.defaultBlockState(), candles.setValue(BlockStateProperties.CANDLES, 3)));
        assertEquals(2, BlockRules.itemsNeeded(Blocks.AIR.defaultBlockState(),
                Blocks.STONE_SLAB.defaultBlockState().setValue(BlockStateProperties.SLAB_TYPE, SlabType.DOUBLE)));
        assertEquals(1, BlockRules.itemsNeeded(Blocks.AIR.defaultBlockState(), Blocks.STONE.defaultBlockState()));
        assertEquals(6, BlockRules.itemsNeeded(Blocks.SNOW.defaultBlockState().setValue(BlockStateProperties.LAYERS, 2),
                Blocks.SNOW.defaultBlockState().setValue(BlockStateProperties.LAYERS, 8)));
    }

    @Test
    void clicksSetDelaysModesNotesAndSwitches() {
        assertEquals(2, BlockRules.adjustClicks(repeater(Direction.EAST, 1), repeater(Direction.EAST, 3)));
        assertEquals(1, BlockRules.adjustClicks(repeater(Direction.EAST, 4), repeater(Direction.EAST, 1)), "the delay wraps from 4 to 1");
        BlockState comparator = Blocks.COMPARATOR.defaultBlockState();
        assertEquals(1, BlockRules.adjustClicks(comparator, comparator.setValue(BlockStateProperties.MODE_COMPARATOR, ComparatorMode.SUBTRACT)));
        BlockState note = Blocks.NOTE_BLOCK.defaultBlockState();
        assertEquals(23, BlockRules.adjustClicks(note.setValue(BlockStateProperties.NOTE, 5), note.setValue(BlockStateProperties.NOTE, 3)));
        BlockState lever = Blocks.LEVER.defaultBlockState();
        assertEquals(1, BlockRules.adjustClicks(lever, lever.setValue(BlockStateProperties.POWERED, true)));
        BlockState door = Blocks.OAK_DOOR.defaultBlockState();
        assertEquals(1, BlockRules.adjustClicks(door, door.setValue(BlockStateProperties.OPEN, true)));
        assertEquals(0, BlockRules.adjustClicks(door, door.setValue(BlockStateProperties.OPEN, true).setValue(BlockStateProperties.POWERED, true)),
                "a powered door is held open by its signal");
        BlockState iron = Blocks.IRON_DOOR.defaultBlockState();
        assertEquals(0, BlockRules.adjustClicks(iron, iron.setValue(BlockStateProperties.OPEN, true)), "iron doors don't open by hand");
        assertEquals(0, BlockRules.adjustClicks(Blocks.IRON_TRAPDOOR.defaultBlockState(),
                Blocks.IRON_TRAPDOOR.defaultBlockState().setValue(BlockStateProperties.OPEN, true)));
        BlockState dot = Blocks.REDSTONE_WIRE.defaultBlockState();
        BlockState cross = dot.setValue(BlockStateProperties.NORTH_REDSTONE, RedstoneSide.SIDE).setValue(BlockStateProperties.EAST_REDSTONE, RedstoneSide.SIDE)
                .setValue(BlockStateProperties.SOUTH_REDSTONE, RedstoneSide.SIDE).setValue(BlockStateProperties.WEST_REDSTONE, RedstoneSide.SIDE);
        assertEquals(1, BlockRules.adjustClicks(cross, dot), "a lone wire placed as a cross is clicked into a dot");
        assertEquals(0, BlockRules.adjustClicks(cross, cross));
    }

    @Test
    void poweredAndLitAreTheGamesBusiness() {
        BlockState lamp = Blocks.REDSTONE_LAMP.defaultBlockState();
        assertTrue(BlockRules.satisfied(lamp, lamp.setValue(BlockStateProperties.LIT, true)));
        BlockState piston = Blocks.PISTON.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.UP);
        assertTrue(BlockRules.satisfied(piston, piston.setValue(BlockStateProperties.EXTENDED, true)));
        assertFalse(BlockRules.satisfied(Blocks.PISTON.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.DOWN), piston));
    }

    @Test
    void redstoneGoesAfterPlainBlocksAndWhatMovesGoesLast() {
        assertEquals(0, BlockRules.group(Blocks.STONE.defaultBlockState()));
        assertEquals(0, BlockRules.group(Blocks.GLASS.defaultBlockState()));
        assertEquals(1, BlockRules.group(Blocks.REDSTONE_WIRE.defaultBlockState()));
        assertEquals(1, BlockRules.group(Blocks.REPEATER.defaultBlockState()));
        assertEquals(1, BlockRules.group(Blocks.REDSTONE_WALL_TORCH.defaultBlockState()));
        assertEquals(1, BlockRules.group(Blocks.REDSTONE_BLOCK.defaultBlockState()));
        assertEquals(2, BlockRules.group(Blocks.PISTON.defaultBlockState()));
        assertEquals(2, BlockRules.group(Blocks.STICKY_PISTON.defaultBlockState()));
        assertEquals(2, BlockRules.group(Blocks.OBSERVER.defaultBlockState()));
        assertEquals(2, BlockRules.group(Blocks.DROPPER.defaultBlockState()));
    }

    @Test
    void blocksWithAUseAreSneakedAgainst() {
        assertTrue(BlockRules.interactive(Blocks.CHEST));
        assertTrue(BlockRules.interactive(Blocks.LEVER));
        assertTrue(BlockRules.interactive(Blocks.REPEATER));
        assertTrue(BlockRules.interactive(Blocks.CRAFTING_TABLE));
        assertTrue(BlockRules.interactive(Blocks.OAK_DOOR));
        assertFalse(BlockRules.interactive(Blocks.STONE));
        assertFalse(BlockRules.interactive(Blocks.OAK_PLANKS));
        assertFalse(BlockRules.interactive(Blocks.GLASS));
    }

    @Test
    void sourcesArePouredAndFlowingWaterComesFromThem() {
        BlockState source = Blocks.WATER.defaultBlockState();
        assertEquals(BlockRules.Kind.FLUID, BlockRules.kind(source));
        assertEquals(net.minecraft.world.item.Items.WATER_BUCKET, BlockRules.item(source));
        assertEquals(net.minecraft.world.item.Items.LAVA_BUCKET, BlockRules.item(Blocks.LAVA.defaultBlockState()));
        assertEquals(BlockRules.Kind.AUTO, BlockRules.kind(source.setValue(BlockStateProperties.LEVEL, 3)), "flowing water");
        assertEquals(BlockRules.Kind.AUTO, BlockRules.kind(source.setValue(BlockStateProperties.LEVEL, 8)), "falling water");
        assertEquals(3, BlockRules.group(source), "water goes in after its layer's blocks");
        assertEquals(4, BlockRules.group(Blocks.KELP.defaultBlockState()), "kelp waits for the water");
        assertEquals(0, BlockRules.group(Blocks.OAK_STAIRS.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, true)));
    }

    @Test
    void blocksFullOfWaterArePlacedDryAndFilledAfter() {
        BlockState chest = Blocks.CHEST.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST);
        BlockState wetChest = chest.setValue(BlockStateProperties.WATERLOGGED, true);
        assertTrue(BlockRules.soaked(wetChest));
        assertFalse(BlockRules.soaked(chest));
        assertEquals(BlockRules.Kind.PLACE, BlockRules.kind(wetChest), "placed with its own item");
        assertEquals(net.minecraft.world.item.Items.CHEST, BlockRules.item(wetChest));
        assertFalse(BlockRules.wet(wetChest), "it goes in with the solid blocks");
        assertTrue(BlockRules.placementMatches(chest, wetChest), "placing it dry starts it");
        assertTrue(BlockRules.placedFully(chest, wetChest));
        assertTrue(BlockRules.needsWater(chest, wetChest));
        assertFalse(BlockRules.satisfied(chest, wetChest), "dry, it is not done: its water feeds what flows from it");
        assertTrue(BlockRules.satisfied(wetChest, wetChest));
        assertFalse(BlockRules.needsWater(Blocks.AIR.defaultBlockState(), wetChest), "the chest itself goes in first");
        assertTrue(BlockRules.satisfied(wetChest, chest), "water in a block meant dry is left as it is");

        BlockState trapdoor = Blocks.MANGROVE_TRAPDOOR.defaultBlockState().setValue(BlockStateProperties.HALF, Half.TOP)
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH);
        BlockState wetTrapdoor = trapdoor.setValue(BlockStateProperties.WATERLOGGED, true);
        assertTrue(BlockRules.needsWater(trapdoor, wetTrapdoor));
        assertFalse(BlockRules.satisfied(trapdoor, wetTrapdoor));
        assertFalse(BlockRules.placedFully(trapdoor.setValue(BlockStateProperties.HALF, Half.BOTTOM), wetTrapdoor), "a bottom trapdoor is no top one");

        BlockState leaves = Blocks.PALE_OAK_LEAVES.defaultBlockState().setValue(BlockStateProperties.PERSISTENT, true);
        BlockState wetLeaves = leaves.setValue(BlockStateProperties.DISTANCE, 7).setValue(BlockStateProperties.WATERLOGGED, true);
        assertTrue(BlockRules.needsWater(leaves.setValue(BlockStateProperties.DISTANCE, 3), wetLeaves));
        assertFalse(BlockRules.satisfied(leaves, wetLeaves));
        assertTrue(BlockRules.satisfied(wetLeaves.setValue(BlockStateProperties.DISTANCE, 2), wetLeaves), "a leaf's distance is the game's");

        assertFalse(BlockRules.soaked(Blocks.IRON_BARS.defaultBlockState()), "dry bars stay dry");

        assertFalse(BlockRules.fillsInPlace(chest), "clicked, a chest opens");
        assertFalse(BlockRules.fillsInPlace(trapdoor), "clicked, a wooden trapdoor opens");
        assertTrue(BlockRules.fillsInPlace(Blocks.IRON_TRAPDOOR.defaultBlockState()), "no hand opens an iron one");
        assertTrue(BlockRules.fillsInPlace(leaves));
        assertTrue(BlockRules.fillsInPlace(Blocks.OAK_SLAB.defaultBlockState()));
    }

    @Test
    void onlyBlocksThatGiveThemselvesBackAreBrokenToGetOut() {
        assertTrue(BlockRules.dropsItself(Blocks.COBBLESTONE.defaultBlockState()));
        assertTrue(BlockRules.dropsItself(Blocks.SPRUCE_FENCE_GATE.defaultBlockState()));
        assertTrue(BlockRules.dropsItself(Blocks.CONCRETE.blue().defaultBlockState()));
        assertFalse(BlockRules.dropsItself(Blocks.GLASS.defaultBlockState()), "glass breaks into nothing");
        assertFalse(BlockRules.dropsItself(Blocks.STAINED_GLASS.blue().defaultBlockState()));
        assertFalse(BlockRules.dropsItself(Blocks.GLASS_PANE.defaultBlockState()));
        assertFalse(BlockRules.dropsItself(Blocks.STAINED_GLASS_PANE.red().defaultBlockState()));
        assertFalse(BlockRules.dropsItself(Blocks.BLUE_ICE.defaultBlockState()));
        assertFalse(BlockRules.dropsItself(Blocks.PACKED_ICE.defaultBlockState()));
        assertFalse(BlockRules.dropsItself(Blocks.ICE.defaultBlockState()), "ice turns to water");
        assertFalse(BlockRules.dropsItself(Blocks.OAK_LEAVES.defaultBlockState()));
        assertFalse(BlockRules.dropsItself(Blocks.STONE.defaultBlockState()), "stone gives cobblestone");
        assertFalse(BlockRules.dropsItself(Blocks.DIAMOND_ORE.defaultBlockState()));
        assertFalse(BlockRules.dropsItself(Blocks.REDSTONE_ORE.defaultBlockState()));
    }

    @Test
    void glassAndPackedIceBreakCleanButIceAndInfestedStoneDoNot() {
        assertTrue(BlockRules.breaksClean(Blocks.GLASS.defaultBlockState()), "glass is only gone");
        assertTrue(BlockRules.breaksClean(Blocks.PACKED_ICE.defaultBlockState()), "packed ice never melts");
        assertTrue(BlockRules.breaksClean(Blocks.COBBLESTONE.defaultBlockState()));
        assertFalse(BlockRules.breaksClean(Blocks.ICE.defaultBlockState()), "ice turns into water over a block");
        assertFalse(BlockRules.breaksClean(Blocks.FROSTED_ICE.defaultBlockState()));
        assertFalse(BlockRules.breaksClean(Blocks.INFESTED_STONE.defaultBlockState()), "a silverfish comes out");
    }

    @Test
    void aKelpStemIsPlacedAsKelpAndCountsAsIt() {
        BlockState stem = Blocks.KELP_PLANT.defaultBlockState(), tip = Blocks.KELP.defaultBlockState();
        assertEquals(BlockRules.Kind.PLACE, BlockRules.kind(stem));
        assertEquals(net.minecraft.world.item.Items.KELP, BlockRules.item(stem));
        assertEquals(Blocks.KELP, BlockRules.placedAs(stem).getBlock());
        assertTrue(BlockRules.placementMatches(tip, stem), "a stem starts as a tip");
        assertTrue(BlockRules.satisfied(stem, tip.setValue(BlockStateProperties.AGE_25, 7)), "a tip grown into a stem is still the plant");
        assertTrue(BlockRules.satisfied(tip.setValue(BlockStateProperties.AGE_25, 3), tip.setValue(BlockStateProperties.AGE_25, 20)), "age is the plant's own");
    }

    @Test
    void doubleChestsPairUpAndLoneChestsStayAlone() {
        BlockState chest = Blocks.CHEST.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH);
        BlockState single = chest.setValue(BlockStateProperties.CHEST_TYPE, net.minecraft.world.level.block.state.properties.ChestType.SINGLE);
        BlockState left = chest.setValue(BlockStateProperties.CHEST_TYPE, net.minecraft.world.level.block.state.properties.ChestType.LEFT);
        BlockState right = chest.setValue(BlockStateProperties.CHEST_TYPE, net.minecraft.world.level.block.state.properties.ChestType.RIGHT);
        assertTrue(BlockRules.placementMatches(single, left, false), "the first half stands alone until the second goes in");
        assertFalse(BlockRules.placementMatches(single, left, true), "the second half must join the first");
        assertTrue(BlockRules.placementMatches(left, left, true));
        assertFalse(BlockRules.placementMatches(right, left, false), "joined the wrong way");
        assertFalse(BlockRules.placementMatches(left, single, false), "a lone chest joined a neighbor");
        BlockPos pos = new BlockPos(5, 64, 5);
        assertEquals(pos.east(), BlockRules.chestPartner(pos, left), "a north-facing left half has its partner to the east");
        assertEquals(pos.west(), BlockRules.chestPartner(pos, right));
        assertNull(BlockRules.chestPartner(pos, single));
    }

    @Test
    void layersRunBottomUpWithRedstoneAfterPlainBlocks() {
        Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
        blocks.put(new BlockPos(0, 1, 0), Blocks.REDSTONE_WIRE.defaultBlockState());
        blocks.put(new BlockPos(1, 0, 0), Blocks.PISTON.defaultBlockState());
        blocks.put(new BlockPos(2, 0, 0), Blocks.REPEATER.defaultBlockState());
        blocks.put(new BlockPos(3, 0, 0), Blocks.STONE.defaultBlockState());
        blocks.put(new BlockPos(0, 0, 0), Blocks.STONE.defaultBlockState());
        blocks.put(new BlockPos(4, 0, 0), Blocks.WATER.defaultBlockState());
        blocks.put(new BlockPos(5, 0, 0), Blocks.WATER.defaultBlockState().setValue(BlockStateProperties.LEVEL, 3));
        BuildPlan plan = BuildPlan.of(blocks, true);
        assertEquals(List.of(0, 1), List.copyOf(plan.layers().keySet()));
        List<BlockPos> bottom = java.util.Arrays.stream(plan.layers().get(0)).mapToObj(plan::pos).toList();
        assertEquals(List.of(new BlockPos(3, 0, 0), new BlockPos(0, 0, 0), new BlockPos(2, 0, 0), new BlockPos(1, 0, 0),
                new BlockPos(4, 0, 0)), bottom.subList(0, 5), "stone first, then the repeater, then the piston, then the water poured");
        assertEquals(6, plan.placeable());
        assertEquals(1, plan.count(BuildPlan.Status.SKIPPED));
        BuildPlan inOrder = BuildPlan.of(blocks, false);
        assertEquals(new BlockPos(1, 0, 0), inOrder.pos(inOrder.layers().get(0)[0]), "without Redstone last the schematic's order stays");
    }

    @Test
    void placingTurnsAndMirrorsAboutTheOrigin() {
        BlockPos origin = new BlockPos(100, 64, -20);
        assertEquals(new BlockPos(100, 64, -19), Schematic.place(new BlockPos(1, 0, 0), origin, Mirror.NONE, Rotation.CLOCKWISE_90));
        assertEquals(new BlockPos(99, 65, -20), Schematic.place(new BlockPos(1, 1, 0), origin, Mirror.NONE, Rotation.CLOCKWISE_180));
        assertEquals(new BlockPos(102, 64, -23), Schematic.place(new BlockPos(2, 0, 3), origin, Mirror.LEFT_RIGHT, Rotation.NONE));
        BlockState east = repeater(Direction.EAST, 2);
        assertEquals(Direction.SOUTH, Schematic.place(east, Mirror.NONE, Rotation.CLOCKWISE_90).getValue(BlockStateProperties.HORIZONTAL_FACING));
        assertEquals(Direction.WEST, Schematic.place(east, Mirror.FRONT_BACK, Rotation.NONE).getValue(BlockStateProperties.HORIZONTAL_FACING));
        assertEquals(2, Schematic.place(east, Mirror.NONE, Rotation.COUNTERCLOCKWISE_90).getValue(BlockStateProperties.DELAY));
    }

    @Test
    void bubbleColumnsArePouredAndWaterCountsInWhatIsFullOfIt() {
        BlockState bubbles = Blocks.BUBBLE_COLUMN.defaultBlockState(), water = Blocks.WATER.defaultBlockState();
        assertEquals(BlockRules.Kind.FLUID, BlockRules.kind(bubbles), "a bubble column is water poured over soul sand");
        assertEquals(Items.WATER_BUCKET, BlockRules.item(bubbles));
        assertTrue(BlockRules.wet(bubbles));
        assertEquals(BlockRules.group(water), BlockRules.group(bubbles), "poured with the rest of the water");

        assertTrue(BlockRules.holdsFluid(water, water));
        assertTrue(BlockRules.holdsFluid(Blocks.KELP.defaultBlockState(), water), "kelp grown up into the water");
        assertTrue(BlockRules.holdsFluid(Blocks.KELP_PLANT.defaultBlockState(), water));
        assertTrue(BlockRules.holdsFluid(bubbles, water), "water over soul sand bubbles");
        assertTrue(BlockRules.holdsFluid(water, bubbles), "water where bubbles should rise: they rise once soul sand is under it");
        assertFalse(BlockRules.holdsFluid(water.setValue(BlockStateProperties.LEVEL, 3), water), "flowing water is no source");
        assertFalse(BlockRules.holdsFluid(Blocks.STONE.defaultBlockState(), water));
        assertFalse(BlockRules.holdsFluid(Blocks.LAVA.defaultBlockState(), water));
        assertFalse(BlockRules.holdsFluid(Blocks.OAK_SLAB.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, true), water),
                "a waterlogged slab is a slab");
    }

    @Test
    void glowLichenGoesOnTheFacesTheTargetHas() {
        BlockState floor = Blocks.GLOW_LICHEN.defaultBlockState().setValue(BlockStateProperties.DOWN, true);
        BlockState wall = Blocks.GLOW_LICHEN.defaultBlockState().setValue(BlockStateProperties.WEST, true);
        BlockState both = floor.setValue(BlockStateProperties.WEST, true);
        assertFalse(BlockRules.placementMatches(wall, floor), "on the wall where the target has it on the floor");
        assertTrue(BlockRules.placementMatches(floor, floor));
        assertTrue(BlockRules.placementMatches(floor, both), "one of its faces so far");
        assertEquals(1, BlockRules.missing(floor, both), "the other face still goes in");
        assertEquals(2, BlockRules.itemsNeeded(Blocks.AIR.defaultBlockState(), both));
        assertFalse(BlockRules.satisfied(floor, both));
        assertTrue(BlockRules.satisfied(both, both));
    }
}
