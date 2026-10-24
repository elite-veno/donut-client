package dev.donut.client.schematic;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Getting out of a pocket full of water: helper blocks into water that comes back, and blocks broken beside water. */
class PocketWaterTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static final BlockState SOURCE = Blocks.WATER.defaultBlockState();
    private static final BlockState RUNNING = Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, 2);
    private static final BlockState FALLING = Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, 8);
    private static final BlockState STONE = Blocks.STONE.defaultBlockState();
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    /** Stone up to y 64, air above, with {@code blocks} set. */
    private static BlockGetter world(Map<BlockPos, BlockState> blocks) {
        return new BlockGetter() {
            @Override
            public BlockState getBlockState(BlockPos pos) {
                BlockState state = blocks.get(pos);
                if (state != null) return state;
                return pos.getY() <= 64 ? STONE : AIR;
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
    void waterThatRunsOrFallsComesBackButALoneSourceDoesNot() {
        BlockPos cell = new BlockPos(0, 65, 0);
        assertTrue(SchematicBuilder.waterReturns(world(Map.of(cell, RUNNING)), cell), "running water comes from water left alone");
        assertTrue(SchematicBuilder.waterReturns(world(Map.of(cell, FALLING)), cell), "falling water too");
        assertFalse(SchematicBuilder.waterReturns(world(Map.of(cell, SOURCE)), cell), "a source alone is gone for good");
        assertTrue(SchematicBuilder.waterReturns(world(Map.of(cell, SOURCE, cell.above(), SOURCE)), cell), "water above falls in again");
        Map<BlockPos, BlockState> pool = new HashMap<>();
        pool.put(cell, SOURCE);
        pool.put(cell.east(), SOURCE);
        pool.put(cell.west(), SOURCE);
        assertTrue(SchematicBuilder.waterReturns(world(pool), cell), "two sources beside it, over stone, make it again");
        BlockPos high = cell.above(3);
        Map<BlockPos, BlockState> hanging = Map.of(high, SOURCE, high.east(), SOURCE, high.west(), SOURCE);
        assertFalse(SchematicBuilder.waterReturns(world(hanging), high), "over air, two sources beside it make none");
    }

    @Test
    void escapeHelpersGoOnlyIntoWaterThatIsThereAgainAfter() {
        BlockPos cell = new BlockPos(0, 65, 0);
        BlockState kelp = Blocks.KELP_PLANT.defaultBlockState();
        // Water the build has there: always, as e416b26 had it, even where a dry spot wouldn't take one.
        assertTrue(SchematicBuilder.helperIntoWater(world(Map.of(cell, SOURCE)), cell, SOURCE, false));
        // Kelp still to go in, its spot full of water falling from the water above: the kelp goes into the water that
        // comes back. Not where a dry spot wouldn't take a helper either, as for kelp already in.
        assertTrue(SchematicBuilder.helperIntoWater(world(Map.of(cell, FALLING)), cell, kelp, true));
        assertFalse(SchematicBuilder.helperIntoWater(world(Map.of(cell, FALLING)), cell, kelp, false));
        assertFalse(SchematicBuilder.helperIntoWater(world(Map.of(cell, SOURCE)), cell, kelp, true),
                "a lone source would be gone, and the kelp has nothing to go into");
        // A waterlogged slab gets its water from a bucket anyway.
        BlockState slab = Blocks.OAK_SLAB.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, true);
        assertTrue(SchematicBuilder.helperIntoWater(world(Map.of(cell, SOURCE)), cell, slab, true));
        // Outside the plan: a current through a channel comes back, a pond's last source doesn't.
        assertTrue(SchematicBuilder.helperIntoWater(world(Map.of(cell, RUNNING)), cell, null, true));
        assertFalse(SchematicBuilder.helperIntoWater(world(Map.of(cell, SOURCE)), cell, null, true));
        assertFalse(SchematicBuilder.helperIntoWater(world(Map.of(cell, Blocks.LAVA.defaultBlockState())), cell, null, true), "never lava");
    }

    @Test
    void aBlockBesideWaterMayBeBrokenOnlyWhenAskedToLetWaterIn() {
        BlockPos pos = new BlockPos(0, 65, 0);
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        blocks.put(pos, Blocks.OAK_PLANKS.defaultBlockState());
        blocks.put(pos.east(), RUNNING);
        assertFalse(SchematicBuilder.safeToBreak(world(blocks), pos, 0, false));
        assertTrue(SchematicBuilder.safeToBreak(world(blocks), pos, 0, true));
        // Kelp beside or under it holds on to what is under the kelp; on top of it, it would go.
        blocks.put(pos.west(), Blocks.KELP_PLANT.defaultBlockState());
        assertTrue(SchematicBuilder.safeToBreak(world(blocks), pos, 0, true));
        blocks.put(pos.above(), Blocks.KELP.defaultBlockState());
        assertFalse(SchematicBuilder.safeToBreak(world(blocks), pos, 0, true));
        blocks.remove(pos.above());
        blocks.put(pos.north(), Blocks.LAVA.defaultBlockState());
        assertFalse(SchematicBuilder.safeToBreak(world(blocks), pos, 0, true), "never beside lava");
        // A block with water in its own spot lets that out.
        BlockState wet = Blocks.OAK_SLAB.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, true);
        assertFalse(SchematicBuilder.safeToBreak(world(Map.of(pos, wet)), pos, 0, true));
    }

    @Test
    void onlyTheBuildsOwnSourcesMayRunIn() {
        BlockPos pos = new BlockPos(0, 65, 0);
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        blocks.put(pos.east(), SOURCE);
        BlockGetter level = world(blocks);
        assertTrue(SchematicBuilder.ownWaterBeside(level, pos, at -> at.equals(pos.east()) ? SOURCE : null));
        assertTrue(SchematicBuilder.ownWaterBeside(level, pos, at -> at.equals(pos.east()) ? Blocks.KELP.defaultBlockState() : null),
                "kelp is water the build has there");
        assertFalse(SchematicBuilder.ownWaterBeside(level, pos, at -> null), "a lake round the build is not let in");
        assertFalse(SchematicBuilder.ownWaterBeside(level, pos, at -> STONE), "nor water where the build has stone");
        blocks.put(pos.east(), RUNNING);
        assertTrue(SchematicBuilder.ownWaterBeside(world(blocks), pos, at -> null), "water that only runs past may come in");
        blocks.put(pos.east(), STONE);
        blocks.put(pos.below(), SOURCE);
        assertTrue(SchematicBuilder.ownWaterBeside(world(blocks), pos, at -> null), "water under it doesn't run up into it");
    }

    @Test
    void waterLetIntoAChannelPushesThePlayerAlongItsFloorOnly() {
        // A channel along z at x 0 with a floor of stone at y 64, walls of stone, a roof at y 67 with one block gone at z 0,
        // water held back beside it. At z 4 and on, the floor is a block lower.
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        for (int z = -8; z <= 8; z++) {
            for (int y = 65; y <= 67; y++) {
                blocks.put(new BlockPos(1, y, z), STONE);
                blocks.put(new BlockPos(-1, y, z), STONE);
            }
            blocks.put(new BlockPos(0, 67, z), STONE);
            if (z >= 4) {
                blocks.put(new BlockPos(0, 64, z), AIR);
                blocks.put(new BlockPos(1, 64, z), STONE);
                blocks.put(new BlockPos(-1, 64, z), STONE);
            }
        }
        BlockPos gone = new BlockPos(0, 67, 0);
        blocks.put(gone, AIR);
        blocks.put(gone.above(), SOURCE);
        BlockGetter level = world(blocks);
        Vec3 feet = new Vec3(0.5, 65, 2.5);
        BlockPos floor = new BlockPos(0, 64, 2);
        BlockPos lower = new BlockPos(0, 63, 4);
        assertTrue(SchematicBuilder.floods(level, gone, feet), "the water lands in the channel and runs to the player");
        // Down the step it lands on the lower floor: a place of the pocket, where the player can drop to anyway.
        Set<Long> pocket = Set.of(floor.asLong(), lower.asLong(), new BlockPos(0, 63, 5).asLong());
        assertFalse(SchematicBuilder.carriesOff(level, gone, feet, floor, key -> pocket.contains(key)));
        // Where they couldn't get to, it is no way out.
        assertTrue(SchematicBuilder.carriesOff(level, gone, feet, floor, key -> key == floor.asLong()));
        // Water that never reaches them carries them nowhere.
        assertFalse(SchematicBuilder.carriesOff(level, gone, new Vec3(0.5, 65, -20.5), new BlockPos(0, 64, -21), key -> false));
    }

    @Test
    void waterOntoANarrowTopAlwaysCarriesThePlayerOff() {
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        BlockPos gate = new BlockPos(0, 65, 0);
        blocks.put(gate, Blocks.SPRUCE_FENCE_GATE.defaultBlockState());
        blocks.put(new BlockPos(1, 65, 0), STONE);
        BlockPos at = new BlockPos(1, 66, 0);
        BlockGetter level = world(blocks);
        assertTrue(SchematicBuilder.carriesOff(level, at, new Vec3(0.5, 66.5, 0.5), gate, key -> true));
        // Standing on a full block there, the same water only pushes them along.
        blocks.put(gate, STONE);
        assertFalse(SchematicBuilder.carriesOff(world(blocks), at, new Vec3(0.5, 66, 0.5), gate, key -> true));
    }

    @Test
    void aBrokenBlockLetsItsWaterInAndDownThroughTheCellsUnderIt() {
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        BlockPos roof = new BlockPos(0, 67, 0);
        blocks.put(roof, Blocks.OAK_PLANKS.defaultBlockState());
        blocks.put(roof.east(), RUNNING);
        Overlay view = SchematicBuilder.opened(world(blocks), List.of(roof));
        assertTrue(view.getFluidState(roof).is(net.minecraft.tags.FluidTags.WATER));
        assertFalse(view.getFluidState(roof).isSource(), "water running in is no source");
        assertTrue(view.getFluidState(new BlockPos(0, 66, 0)).is(net.minecraft.tags.FluidTags.WATER), "it falls on");
        assertTrue(view.getFluidState(new BlockPos(0, 65, 0)).is(net.minecraft.tags.FluidTags.WATER));
        assertEquals(STONE, view.getBlockState(new BlockPos(0, 64, 0)), "down to the floor");
        // A dry block is only gone.
        Overlay dry = SchematicBuilder.opened(world(Map.of(roof, Blocks.OAK_PLANKS.defaultBlockState())), List.of(roof));
        assertTrue(dry.getBlockState(roof).isAir());
        assertTrue(dry.getFluidState(new BlockPos(0, 66, 0)).isEmpty());
        // Two broken, the water let in by the upper one falls through the lower one.
        blocks.remove(roof.east());
        blocks.put(roof.above(), Blocks.OAK_PLANKS.defaultBlockState());
        blocks.put(roof.above().east(), SOURCE);
        Overlay both = SchematicBuilder.opened(world(blocks), List.of(roof, roof.above()));
        assertTrue(both.getFluidState(roof.above()).is(net.minecraft.tags.FluidTags.WATER));
        assertTrue(both.getFluidState(roof).is(net.minecraft.tags.FluidTags.WATER));
        assertTrue(both.getFluidState(new BlockPos(0, 65, 0)).is(net.minecraft.tags.FluidTags.WATER));
    }
}
