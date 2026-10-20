package dev.donut.client.schematic;

import static dev.donut.client.schematic.LitematicFixture.block;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.UnaryOperator;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LitematicFileTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static int[] sequence(int count, int paletteSize) {
        int[] values = new int[count];
        for (int i = 0; i < count; i++) values[i] = (i * 7 + i / 3) % paletteSize;
        return values;
    }

    @Test
    void indexesThatRunOnIntoTheNextLongReadBack() throws IOException {
        // Five entries take three bits, which never line up with 64: every 21st block straddles two longs.
        List<CompoundTag> palette = List.of(block("minecraft:air"), block("minecraft:stone"), block("minecraft:dirt"),
                block("minecraft:glass"), block("minecraft:oak_planks"));
        int[] blocks = sequence(4 * 3 * 5, palette.size());
        CompoundTag root = LitematicFixture.schematic("packed", 4700,
                new LitematicFixture.Region("main", new int[] {0, 0, 0}, new int[] {4, 3, 5}, palette, blocks));
        LitematicFile.Region region = LitematicFile.parse(root, "x").regions.getFirst();
        assertEquals(3, region.bits());
        int index = 0;
        for (int y = 0; y < 3; y++) {
            for (int z = 0; z < 5; z++) {
                for (int x = 0; x < 4; x++) assertEquals(blocks[index++], region.paletteIndex(x, y, z), "at " + x + " " + y + " " + z);
            }
        }
    }

    @Test
    void widePalettesUseMoreBits() throws IOException {
        List<CompoundTag> palette = new java.util.ArrayList<>();
        palette.add(block("minecraft:air"));
        for (int i = 0; i < 40; i++) palette.add(block("minecraft:stone"));
        int[] blocks = sequence(9 * 9 * 9, palette.size());
        CompoundTag root = LitematicFixture.schematic("wide", 4700,
                new LitematicFixture.Region("main", new int[] {0, 0, 0}, new int[] {9, 9, 9}, palette, blocks));
        LitematicFile.Region region = LitematicFile.parse(root, "x").regions.getFirst();
        assertEquals(6, region.bits());
        for (int i = 0; i < blocks.length; i++) assertEquals(blocks[i], region.paletteIndex(i));
    }

    @Test
    void aNegativeSizeGrowsTheRegionTowardNegativeCoordinates() throws IOException {
        List<CompoundTag> palette = List.of(block("minecraft:air"), block("minecraft:stone"));
        CompoundTag root = LitematicFixture.schematic("negative", 4700,
                new LitematicFixture.Region("main", new int[] {5, 2, 5}, new int[] {-3, 2, -2}, palette, new int[12]));
        LitematicFile.Region region = LitematicFile.parse(root, "x").regions.getFirst();
        assertEquals(3, region.minX());
        assertEquals(2, region.minY());
        assertEquals(4, region.minZ());
        assertEquals(3, region.sizeX());
        assertEquals(2, region.sizeY());
        assertEquals(2, region.sizeZ());
    }

    @Test
    void blocksBecomeStatesAtTheirPlacesAndAirIsLeftOut() throws IOException {
        List<CompoundTag> palette = List.of(block("minecraft:air"), block("minecraft:stone"),
                block("minecraft:repeater", "facing", "east", "delay", "3"));
        // Two regions: a 2x1x2 floor at the origin and a repeater above, off to the side.
        CompoundTag root = LitematicFixture.schematic("two regions", 4700,
                new LitematicFixture.Region("floor", new int[] {0, 0, 0}, new int[] {2, 1, 2}, palette, new int[] {1, 0, 1, 1}),
                new LitematicFixture.Region("top", new int[] {4, 1, 0}, new int[] {1, 1, 1}, palette, new int[] {2}));
        Schematic schematic = Schematic.of(LitematicFile.parse(root, "x"), UnaryOperator.identity());
        assertEquals("two regions", schematic.name);
        assertEquals(4, schematic.size(), "three stone and one repeater; the air block is left out");
        assertEquals(new BlockPos(0, 0, 0), schematic.min);
        assertEquals(new BlockPos(4, 1, 1), schematic.max);
        java.util.Map<BlockPos, BlockState> blocks = new java.util.HashMap<>();
        for (int i = 0; i < schematic.size(); i++) blocks.put(schematic.pos(i), schematic.state(i));
        assertEquals(Blocks.STONE.defaultBlockState(), blocks.get(new BlockPos(0, 0, 0)));
        assertNull(blocks.get(new BlockPos(1, 0, 0)));
        assertEquals(Blocks.STONE.defaultBlockState(), blocks.get(new BlockPos(0, 0, 1)));
        assertEquals(Blocks.STONE.defaultBlockState(), blocks.get(new BlockPos(1, 0, 1)));
        BlockState repeater = blocks.get(new BlockPos(4, 1, 0));
        assertEquals(Direction.EAST, repeater.getValue(RepeaterBlock.FACING));
        assertEquals(3, repeater.getValue(RepeaterBlock.DELAY));
    }

    @Test
    void overlappingRegionsGiveOneBlockAPosition() throws IOException {
        List<CompoundTag> palette = List.of(block("minecraft:air"), block("minecraft:stone"), block("minecraft:dirt"));
        // Two 2x1x1 regions sharing the cell at x 1: stone in one, dirt in the other.
        CompoundTag root = LitematicFixture.schematic("overlap", 4700,
                new LitematicFixture.Region("a", new int[] {0, 0, 0}, new int[] {2, 1, 1}, palette, new int[] {1, 1}),
                new LitematicFixture.Region("b", new int[] {1, 0, 0}, new int[] {2, 1, 1}, palette, new int[] {2, 2}));
        Schematic schematic = Schematic.of(LitematicFile.parse(root, "x"), UnaryOperator.identity());
        assertEquals(3, schematic.size(), "x 0, 1 and 2, the shared cell once");
        java.util.Set<BlockPos> seen = new java.util.HashSet<>();
        for (int i = 0; i < schematic.size(); i++) assertTrue(seen.add(schematic.pos(i)), "one block at " + schematic.pos(i));
    }

    @Test
    void unknownBlocksAreCountedAndLeftOut() throws IOException {
        List<CompoundTag> palette = List.of(block("minecraft:air"), block("minecraft:stone"), block("othermod:widget"));
        CompoundTag root = LitematicFixture.schematic("modded", 4700,
                new LitematicFixture.Region("main", new int[] {0, 0, 0}, new int[] {3, 1, 1}, palette, new int[] {1, 2, 2}));
        Schematic schematic = Schematic.of(LitematicFile.parse(root, "x"), UnaryOperator.identity());
        assertEquals(1, schematic.size());
        assertEquals(2, schematic.unknown.get("othermod:widget"));
    }

    @Test
    void aCutShortRegionIsRefused() {
        List<CompoundTag> palette = List.of(block("minecraft:air"), block("minecraft:stone"));
        CompoundTag root = LitematicFixture.schematic("short", 4700,
                new LitematicFixture.Region("main", new int[] {0, 0, 0}, new int[] {16, 16, 16}, palette, new int[16 * 16 * 16]));
        root.getCompoundOrEmpty("Regions").getCompoundOrEmpty("main").put("BlockStates", new LongArrayTag(new long[3]));
        IOException e = assertThrows(IOException.class, () -> LitematicFile.parse(root, "x"));
        assertTrue(e.getMessage().contains("cut short"), e.getMessage());
    }

    @Test
    void filesWithoutRegionsAreNotSchematics() {
        assertThrows(IOException.class, () -> LitematicFile.parse(new CompoundTag(), "x"));
    }

    @Test
    void readsTheGzippedFileAndNamesItAfterTheFileWhenItsMetadataHasNoName(@TempDir Path dir) throws IOException {
        List<CompoundTag> palette = List.of(block("minecraft:air"), block("minecraft:stone"));
        CompoundTag root = LitematicFixture.schematic("", 4700,
                new LitematicFixture.Region("main", new int[] {0, 0, 0}, new int[] {1, 1, 1}, palette, new int[] {1}));
        Path file = dir.resolve("Little Hut.litematic");
        NbtIo.writeCompressed(root, file);
        LitematicFile read = LitematicFile.read(file);
        assertEquals("Little Hut", read.name);
        assertEquals(4700, read.dataVersion);
        assertTrue(Files.size(file) > 0);
    }
}
