package dev.donut.client.finder;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * A chunk with amethyst growth. {@code minY}/{@code maxY} span its budding amethyst;
 * {@code surfaceY} is the chunk's highest motion-blocking block when it was scanned.
 */
public record ActivityArea(ChunkPos chunk, int minY, int maxY, int surfaceY, double score, int budding) {
    /** The chunk around the geode's budding amethyst. */
    public AABB bounds() {
        int x = chunk.getMinBlockX(), z = chunk.getMinBlockZ();
        return new AABB(x, minY, z, x + 16, maxY + 1, z + 16);
    }

    /** A thin square resting on the chunk's highest block, so it shows from the surface. */
    public AABB surfaceBounds() {
        int x = chunk.getMinBlockX(), z = chunk.getMinBlockZ();
        double top = Math.max(surfaceY, maxY) + 1;
        return new AABB(x, top, z, x + 16, top + 0.05, z + 16);
    }

    /** From the geode up to the surface square. */
    public AABB guideBounds() {
        int x = chunk.getMinBlockX(), z = chunk.getMinBlockZ();
        return new AABB(x, minY, z, x + 16, Math.max(surfaceY, maxY) + 1, z + 16);
    }

    public double horizontalDistance(Vec3 origin) {
        double dx = chunk.getMinBlockX() + 8.0 - origin.x, dz = chunk.getMinBlockZ() + 8.0 - origin.z;
        return Math.sqrt(dx * dx + dz * dz);
    }
}
