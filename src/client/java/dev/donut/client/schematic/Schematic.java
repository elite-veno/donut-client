package dev.donut.client.schematic;

import com.mojang.datafixers.DataFixer;
import com.mojang.serialization.Dynamic;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.Identifier;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/**
 * A schematic's blocks as this game's block states, at positions relative to its origin. Air is left out: the builder
 * only places blocks.
 */
public final class Schematic {
    public final String name;
    public final String author;
    /** Relative positions, as {@link BlockPos#asLong}, and the state each one holds, in the same order. */
    private final long[] positions;
    private final BlockState[] states;
    /** Block names this game doesn't know, with how many blocks each had; those blocks are left out. */
    public final Map<String, Integer> unknown;
    public final int regions;
    /** The relative box all regions span, air included. */
    public final BlockPos min, max;

    private Schematic(String name, String author, long[] positions, BlockState[] states, Map<String, Integer> unknown,
                      int regions, BlockPos min, BlockPos max) {
        this.name = name;
        this.author = author;
        this.positions = positions;
        this.states = states;
        this.unknown = unknown;
        this.regions = regions;
        this.min = min;
        this.max = max;
    }

    public int size() { return states.length; }

    public BlockPos pos(int index) { return BlockPos.of(positions[index]); }

    public BlockState state(int index) { return states[index]; }

    /** Each palette entry read as this game's block state, after updating it from the version the file was saved in. */
    public static Schematic of(LitematicFile file, DataFixer fixer) {
        int current = SharedConstants.getCurrentVersion().dataVersion().version();
        return of(file, entry -> {
            CompoundTag tag = entry;
            if (fixer != null && file.dataVersion > 0 && file.dataVersion < current) {
                tag = (CompoundTag) fixer.update(References.BLOCK_STATE, new Dynamic<>(NbtOps.INSTANCE, tag), file.dataVersion, current).getValue();
            }
            return tag;
        });
    }

    /** As {@link #of(LitematicFile, DataFixer)}, with {@code update} bringing each palette entry up to date. */
    static Schematic of(LitematicFile file, java.util.function.UnaryOperator<CompoundTag> update) {
        List<Long> positions = new ArrayList<>();
        List<BlockState> states = new ArrayList<>();
        // Where each position went in: regions may overlap, and a block of a region read later takes the place of one
        // before, so that one position has one block, not two the build can never both make.
        Map<Long, Integer> at = new HashMap<>();
        Map<String, Integer> unknown = new LinkedHashMap<>();
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (LitematicFile.Region region : file.regions) {
            if (region.volume() == 0) continue;
            minX = Math.min(minX, region.minX());
            minY = Math.min(minY, region.minY());
            minZ = Math.min(minZ, region.minZ());
            maxX = Math.max(maxX, region.minX() + region.sizeX() - 1);
            maxY = Math.max(maxY, region.minY() + region.sizeY() - 1);
            maxZ = Math.max(maxZ, region.minZ() + region.sizeZ() - 1);
            BlockState[] palette = new BlockState[region.palette().size()];
            String[] unknownName = new String[palette.length];
            for (int i = 0; i < palette.length; i++) {
                CompoundTag entry = update.apply(region.palette().get(i));
                String name = entry.getStringOr("Name", "");
                Identifier id = Identifier.tryParse(name);
                if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) {
                    palette[i] = null;
                    unknownName[i] = name.isEmpty() ? "(no name)" : name;
                } else {
                    palette[i] = NbtUtils.readBlockState(BuiltInRegistries.BLOCK, entry);
                }
            }
            long index = 0;
            for (int y = 0; y < region.sizeY(); y++) {
                for (int z = 0; z < region.sizeZ(); z++) {
                    for (int x = 0; x < region.sizeX(); x++, index++) {
                        int entry = region.paletteIndex(index);
                        BlockState state = palette[entry];
                        if (state == null) {
                            unknown.merge(unknownName[entry], 1, Integer::sum);
                            continue;
                        }
                        if (state.isAir()) continue;
                        long pos = BlockPos.asLong(region.minX() + x, region.minY() + y, region.minZ() + z);
                        Integer before = at.putIfAbsent(pos, positions.size());
                        if (before != null) {
                            states.set(before, state);
                            continue;
                        }
                        positions.add(pos);
                        states.add(state);
                    }
                }
            }
        }
        long[] packed = new long[positions.size()];
        for (int i = 0; i < packed.length; i++) packed[i] = positions.get(i);
        BlockPos min = minX == Integer.MAX_VALUE ? BlockPos.ZERO : new BlockPos(minX, minY, minZ);
        BlockPos max = minX == Integer.MAX_VALUE ? BlockPos.ZERO : new BlockPos(maxX, maxY, maxZ);
        return new Schematic(file.name, file.author, packed, states.toArray(BlockState[]::new), Map.copyOf(unknown),
                file.regions.size(), min, max);
    }

    /**
     * Where a block ends up and what it becomes when the schematic is placed at {@code origin}, mirrored and then
     * rotated about its origin, as Litematica places it.
     */
    public static BlockPos place(BlockPos relative, BlockPos origin, Mirror mirror, Rotation rotation) {
        return StructureTemplate.transform(relative, mirror, rotation, BlockPos.ZERO).offset(origin);
    }

    public static BlockState place(BlockState state, Mirror mirror, Rotation rotation) {
        return state.mirror(mirror).rotate(rotation);
    }
}
