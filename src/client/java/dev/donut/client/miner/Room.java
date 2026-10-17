package dev.donut.client.miner;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * The space dug for a schematic: its box with room around it on every side and above it, and optionally a lower storage
 * room beside it. The floor under the schematic's lowest layer is never part of it, so it stays to walk on.
 */
public final class Room {
    /** Where the schematic itself goes. */
    public final Box schematic;
    /** The schematic's box with the room around it. */
    public final Box main;
    /** The storage room, or null. */
    public final Box storage;
    public final Direction storageSide;
    /** Both boxes together. */
    public final Box bounds;
    /** The lowest layer dug; the block layer under it is the floor. */
    public final int floorY, topY;

    private Room(Box schematic, Box main, Box storage, Direction storageSide) {
        this.schematic = schematic;
        this.main = main;
        this.storage = storage;
        this.storageSide = storageSide;
        this.bounds = storage == null ? main : main.union(storage);
        this.floorY = main.minY();
        this.topY = main.maxY();
    }

    /**
     * @param side        blocks of room around the schematic on each side
     * @param headroom    blocks of room above it
     * @param storageSide the side the storage room goes on, or null for none
     */
    public static Room of(Box schematic, int side, int headroom, Direction storageSide, int storageDepth, int storageHeight) {
        int top = schematic.maxY() + Math.max(0, headroom);
        // A player is two blocks tall.
        if (top < schematic.minY() + 1) top = schematic.minY() + 1;
        Box main = new Box(schematic.minX() - side, schematic.minY(), schematic.minZ() - side,
                schematic.maxX() + side, top, schematic.maxZ() + side);
        Box storage = null;
        if (storageSide != null && storageSide.getAxis().isHorizontal()) {
            int y0 = main.minY(), y1 = Math.min(main.maxY(), main.minY() + Math.max(2, storageHeight) - 1);
            int depth = Math.max(1, storageDepth);
            storage = switch (storageSide) {
                case NORTH -> new Box(main.minX(), y0, main.minZ() - depth, main.maxX(), y1, main.minZ() - 1);
                case SOUTH -> new Box(main.minX(), y0, main.maxZ() + 1, main.maxX(), y1, main.maxZ() + depth);
                case WEST -> new Box(main.minX() - depth, y0, main.minZ(), main.minX() - 1, y1, main.maxZ());
                default -> new Box(main.maxX() + 1, y0, main.minZ(), main.maxX() + depth, y1, main.maxZ());
            };
        }
        return new Room(schematic, main, storage, storage == null ? null : storageSide);
    }

    public boolean contains(int x, int y, int z) {
        return main.contains(x, y, z) || storage != null && storage.contains(x, y, z);
    }

    public boolean contains(BlockPos pos) { return contains(pos.getX(), pos.getY(), pos.getZ()); }

    /** Whether the column at x, z runs through the room. */
    public boolean column(int x, int z) { return main.column(x, z) || storage != null && storage.column(x, z); }

    /** Whether a block is part of the floor or below it, or right beside the room there: never dug. */
    public boolean belowFloor(BlockPos pos) {
        if (pos.getY() >= floorY) return false;
        int x = pos.getX(), z = pos.getZ();
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) if (column(x + dx, z + dz)) return true;
        return false;
    }

    /** Blocks in the room, all of them, air included. */
    public long volume() { return main.volume() + (storage == null ? 0 : storage.volume()); }

    /**
     * Height bands from the bottom up, each {@code {bottom, top}}, mined from the top down while standing on the band
     * below. A band on top too low to stand in joins the one under it.
     */
    public List<int[]> tiers(int height) {
        List<int[]> tiers = new ArrayList<>();
        for (int bottom = floorY; bottom <= topY; bottom += height) tiers.add(new int[] {bottom, Math.min(bottom + height - 1, topY)});
        int[] last = tiers.getLast();
        if (tiers.size() > 1 && last[1] - last[0] + 1 < 2) {
            tiers.removeLast();
            tiers.getLast()[1] = topY;
        }
        return tiers;
    }

    /** The side of {@code box} that faces {@code from}: the way it is approached. */
    public static Direction facing(Box box, BlockPos from) {
        double dx = from.getX() + 0.5 - box.centerX(), dz = from.getZ() + 0.5 - box.centerZ();
        // Measured against the box's own size, so a long box is entered from its long side when approached from there.
        double nx = dx / Math.max(1, box.sizeX()), nz = dz / Math.max(1, box.sizeZ());
        if (Math.abs(nx) >= Math.abs(nz)) return dx >= 0 ? Direction.EAST : Direction.WEST;
        return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
    }

    /** The main box's block nearest to {@code from} at height {@code y}: where a tunnel from there comes in. */
    public BlockPos entry(BlockPos from, int y) {
        return new BlockPos(Math.clamp(from.getX(), main.minX(), main.maxX()), y, Math.clamp(from.getZ(), main.minZ(), main.maxZ()));
    }
}
