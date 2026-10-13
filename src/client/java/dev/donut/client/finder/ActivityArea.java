package dev.donut.client.finder;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** A chunk with amethyst growth; the box spans the chunk and the height of its budding amethyst. */
public record ActivityArea(ChunkPos chunk, int minY, int maxY, double score, int budding) {
    public AABB bounds() {
        int x = chunk.getMinBlockX(), z = chunk.getMinBlockZ();
        return new AABB(x, minY, z, x + 16, maxY + 1, z + 16);
    }

    public double horizontalDistance(Vec3 origin) {
        double dx = chunk.getMinBlockX() + 8.0 - origin.x, dz = chunk.getMinBlockZ() + 8.0 - origin.z;
        return Math.sqrt(dx * dx + dz * dz);
    }
}
