package dev.donut.client.schematic;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;

/**
 * A Litematica schematic as saved on disk: gzipped NBT holding one or more regions, each with a palette of block
 * states and the index of every block's state packed into longs. Nothing here needs a running game, so it can be
 * checked on its own; {@link Schematic} turns it into block states.
 */
public final class LitematicFile {
    /** Litematica's own limit is far lower; a region claiming more is damaged rather than large. */
    static final long MAX_VOLUME = 256L * 1024 * 1024;
    /** Decompressed size allowed while reading: the largest schematics people share are well under this. */
    private static final long MAX_BYTES = 1024L * 1024 * 1024;

    public final String name;
    public final String author;
    /** The Minecraft data version it was saved with, or -1 when the file doesn't say. */
    public final int dataVersion;
    public final List<Region> regions;

    private LitematicFile(String name, String author, int dataVersion, List<Region> regions) {
        this.name = name;
        this.author = author;
        this.dataVersion = dataVersion;
        this.regions = regions;
    }

    public static LitematicFile read(Path path) throws IOException {
        return parse(NbtIo.readCompressed(path, NbtAccounter.create(MAX_BYTES)), fileName(path));
    }

    private static String fileName(Path path) {
        String name = path.getFileName().toString();
        return name.toLowerCase(java.util.Locale.ROOT).endsWith(".litematic") ? name.substring(0, name.length() - 10) : name;
    }

    /** Reads a schematic's root tag; {@code fallbackName} names it when its metadata has no name. */
    public static LitematicFile parse(CompoundTag root, String fallbackName) throws IOException {
        if (root.getCompound("Regions").isEmpty()) throw new IOException("Not a Litematica schematic: it has no regions");
        int version = root.getIntOr("Version", 0);
        // Version 1 stored blocks differently and only Litematica's first releases wrote it.
        if (version < 2) throw new IOException("Litematica schematic version " + version + " is too old; save it again with a newer Litematica");
        CompoundTag meta = root.getCompoundOrEmpty("Metadata");
        String name = meta.getStringOr("Name", "").strip();
        String author = meta.getStringOr("Author", "").strip();
        CompoundTag regionsTag = root.getCompoundOrEmpty("Regions");
        List<Region> regions = new ArrayList<>();
        for (String key : regionsTag.keySet()) {
            CompoundTag region = regionsTag.getCompound(key).orElse(null);
            if (region == null) continue;
            regions.add(region(key, region));
        }
        if (regions.isEmpty()) throw new IOException("The schematic has no regions");
        return new LitematicFile(name.isEmpty() ? fallbackName : name, author, root.getIntOr("MinecraftDataVersion", -1), List.copyOf(regions));
    }

    private static Region region(String name, CompoundTag tag) throws IOException {
        CompoundTag position = tag.getCompoundOrEmpty("Position"), size = tag.getCompoundOrEmpty("Size");
        int px = position.getIntOr("x", 0), py = position.getIntOr("y", 0), pz = position.getIntOr("z", 0);
        int sx = size.getIntOr("x", 0), sy = size.getIntOr("y", 0), sz = size.getIntOr("z", 0);
        long volume = (long) Math.abs(sx) * Math.abs(sy) * Math.abs(sz);
        if (volume == 0) return new Region(name, px, py, pz, 0, 0, 0, List.of(), new long[0]);
        if (volume > MAX_VOLUME) throw new IOException("Region " + name + " is " + volume + " blocks, more than a schematic holds");
        // A negative size grows the region from its position toward negative coordinates; blocks are stored from the
        // region's lowest corner either way.
        int minX = Math.min(px, px + sx - Integer.signum(sx));
        int minY = Math.min(py, py + sy - Integer.signum(sy));
        int minZ = Math.min(pz, pz + sz - Integer.signum(sz));
        ListTag paletteTag = tag.getListOrEmpty("BlockStatePalette");
        List<CompoundTag> palette = new ArrayList<>(paletteTag.size());
        for (int i = 0; i < paletteTag.size(); i++) palette.add(paletteTag.getCompoundOrEmpty(i));
        if (palette.isEmpty()) throw new IOException("Region " + name + " has no block palette");
        long[] states = tag.getLongArray("BlockStates").orElse(new long[0]);
        Region region = new Region(name, minX, minY, minZ, Math.abs(sx), Math.abs(sy), Math.abs(sz), List.copyOf(palette), states);
        long needed = (volume * region.bits() + 63) / 64;
        if (states.length < needed) {
            throw new IOException("Region " + name + " is cut short: " + states.length + " of " + needed + " block words");
        }
        return region;
    }

    /**
     * One region: its lowest corner relative to the schematic's origin, its size, and its blocks.
     *
     * @param states palette indexes, {@link #bits()} each, packed from the lowest bit up and running on into the next
     *               long when one fills; blocks go in x, then z, then y order
     */
    public record Region(String name, int minX, int minY, int minZ, int sizeX, int sizeY, int sizeZ,
                         List<CompoundTag> palette, long[] states) {
        /** Bits per block: enough for the palette, and never fewer than two, as Litematica writes them. */
        public int bits() { return Math.max(2, Integer.SIZE - Integer.numberOfLeadingZeros(Math.max(1, palette.size() - 1))); }

        public long volume() { return (long) sizeX * sizeY * sizeZ; }

        /** The palette index of the block at this offset from the region's lowest corner. */
        public int paletteIndex(int x, int y, int z) {
            return paletteIndex(((long) y * sizeZ + z) * sizeX + x);
        }

        int paletteIndex(long index) {
            int bits = bits();
            long mask = (1L << bits) - 1;
            long start = index * bits;
            int word = (int) (start >>> 6), offset = (int) (start & 63);
            long value = states[word] >>> offset;
            if (offset + bits > 64) value |= states[word + 1] << (64 - offset);
            int result = (int) (value & mask);
            // An index past the palette is damage; it reads as the palette's first entry, air in Litematica's files.
            return result < palette.size() ? result : 0;
        }

        /** The name the palette gives an entry, such as "minecraft:stone". */
        public String blockName(int paletteIndex) {
            Tag name = palette.get(paletteIndex).get("Name");
            return name == null ? "" : name.asString().orElse("");
        }
    }
}
