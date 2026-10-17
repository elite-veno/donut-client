package dev.donut.client.schematic;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
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
    void secondHalvesComeWithTheirBlockAndLiquidsCantBePlaced() {
        BlockState door = Blocks.OAK_DOOR.defaultBlockState();
        assertEquals(BlockRules.Kind.PLACE, BlockRules.kind(door.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER)));
        assertEquals(BlockRules.Kind.AUTO, BlockRules.kind(door.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER)));
        assertEquals(BlockRules.Kind.AUTO, BlockRules.kind(Blocks.BED.red().defaultBlockState().setValue(BlockStateProperties.BED_PART, BedPart.HEAD)));
        assertEquals(BlockRules.Kind.PLACE, BlockRules.kind(Blocks.BED.red().defaultBlockState().setValue(BlockStateProperties.BED_PART, BedPart.FOOT)));
        assertEquals(BlockRules.Kind.AUTO, BlockRules.kind(Blocks.PISTON_HEAD.defaultBlockState()));
        assertEquals(BlockRules.Kind.UNPLACEABLE, BlockRules.kind(Blocks.WATER.defaultBlockState()));
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
    void layersRunBottomUpWithRedstoneAfterPlainBlocks() {
        Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
        blocks.put(new BlockPos(0, 1, 0), Blocks.REDSTONE_WIRE.defaultBlockState());
        blocks.put(new BlockPos(1, 0, 0), Blocks.PISTON.defaultBlockState());
        blocks.put(new BlockPos(2, 0, 0), Blocks.REPEATER.defaultBlockState());
        blocks.put(new BlockPos(3, 0, 0), Blocks.STONE.defaultBlockState());
        blocks.put(new BlockPos(0, 0, 0), Blocks.STONE.defaultBlockState());
        blocks.put(new BlockPos(4, 0, 0), Blocks.WATER.defaultBlockState());
        BuildPlan plan = BuildPlan.of(blocks, true);
        assertEquals(List.of(0, 1), List.copyOf(plan.layers().keySet()));
        List<BlockPos> bottom = java.util.Arrays.stream(plan.layers().get(0)).mapToObj(plan::pos).toList();
        assertEquals(List.of(new BlockPos(3, 0, 0), new BlockPos(0, 0, 0), new BlockPos(2, 0, 0), new BlockPos(1, 0, 0)), bottom,
                "stone first, then the repeater, then the piston; water is left out");
        assertEquals(5, plan.placeable());
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
}
