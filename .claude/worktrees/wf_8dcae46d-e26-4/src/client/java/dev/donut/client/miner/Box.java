package dev.donut.client.miner;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;

/** A box of whole blocks, both corners included. */
public record Box(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
    public static Box of(BlockPos a, BlockPos b) {
        return new Box(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()),
                Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));
    }

    public boolean contains(int x, int y, int z) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    public boolean contains(BlockPos pos) { return contains(pos.getX(), pos.getY(), pos.getZ()); }

    /** Whether the column at x, z runs through the box. */
    public boolean column(int x, int z) { return x >= minX && x <= maxX && z >= minZ && z <= maxZ; }

    public int sizeX() { return maxX - minX + 1; }

    public int sizeY() { return maxY - minY + 1; }

    public int sizeZ() { return maxZ - minZ + 1; }

    public long volume() { return (long) sizeX() * sizeY() * sizeZ(); }

    public Box union(Box other) {
        return new Box(Math.min(minX, other.minX), Math.min(minY, other.minY), Math.min(minZ, other.minZ),
                Math.max(maxX, other.maxX), Math.max(maxY, other.maxY), Math.max(maxZ, other.maxZ));
    }

    public AABB aabb() { return new AABB(minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1); }

    public double centerX() { return (minX + maxX + 1) / 2.0; }

    public double centerZ() { return (minZ + maxZ + 1) / 2.0; }

    @Override
    public String toString() { return minX + " " + minY + " " + minZ + " to " + maxX + " " + maxY + " " + maxZ; }
}
