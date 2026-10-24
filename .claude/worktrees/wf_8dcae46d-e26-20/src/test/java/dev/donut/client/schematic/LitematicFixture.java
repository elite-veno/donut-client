package dev.donut.client.schematic;

import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;

/** Writes schematics the way Litematica does, so the reader is checked against its format rather than against itself. */
final class LitematicFixture {
    private LitematicFixture() { }

    /** A region at {@code position} with signed {@code size}; {@code blocks[i]} is the palette index of block i. */
    record Region(String name, int[] position, int[] size, List<CompoundTag> palette, int[] blocks) { }

    static CompoundTag schematic(String name, int dataVersion, Region... regions) {
        CompoundTag root = new CompoundTag();
        root.putInt("Version", 6);
        root.putInt("SubVersion", 1);
        root.putInt("MinecraftDataVersion", dataVersion);
        CompoundTag meta = new CompoundTag();
        meta.putString("Name", name);
        meta.putString("Author", "tester");
        root.put("Metadata", meta);
        CompoundTag all = new CompoundTag();
        for (Region region : regions) {
            CompoundTag tag = new CompoundTag();
            tag.put("Position", xyz(region.position));
            tag.put("Size", xyz(region.size));
            ListTag palette = new ListTag();
            palette.addAll(region.palette);
            tag.put("BlockStatePalette", palette);
            tag.put("BlockStates", new LongArrayTag(pack(region.blocks, region.palette.size())));
            all.put(region.name, tag);
        }
        root.put("Regions", all);
        return root;
    }

    static CompoundTag block(String name, String... properties) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Name", name);
        if (properties.length > 0) {
            CompoundTag props = new CompoundTag();
            for (int i = 0; i < properties.length; i += 2) props.putString(properties[i], properties[i + 1]);
            tag.put("Properties", props);
        }
        return tag;
    }

    private static CompoundTag xyz(int[] v) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("x", v[0]);
        tag.putInt("y", v[1]);
        tag.putInt("z", v[2]);
        return tag;
    }

    /** Litematica's LitematicaBitArray.setAt: entries run on into the next long when one fills. */
    static long[] pack(int[] values, int paletteSize) {
        int bits = Math.max(2, Integer.SIZE - Integer.numberOfLeadingZeros(paletteSize - 1));
        long mask = (1L << bits) - 1;
        long[] words = new long[(int) ((values.length * (long) bits + 63) / 64)];
        for (int index = 0; index < values.length; index++) {
            long start = index * (long) bits;
            int first = (int) (start >> 6), last = (int) (((index + 1L) * bits - 1) >> 6), offset = (int) (start & 63);
            long value = values[index] & mask;
            words[first] = words[first] & ~(mask << offset) | value << offset;
            if (first != last) {
                int carried = 64 - offset, rest = bits - carried;
                words[last] = words[last] >>> rest << rest | value >> carried;
            }
        }
        return words;
    }
}
