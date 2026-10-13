package dev.donut.client.finder;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * A chunk with amethyst growth. {@code minY}/{@code maxY} span its budding amethyst;
 * {@code surfaceY} is the chunk's highest motion-blocking block when it was scanned.
 *
 * @param score growth past worldgen from 0 to 1, or 0 when the amethyst around the chunk could be worldgen luck
 * @param budding budding amethyst counted here, {@code faces} their open or budded faces, {@code stages} the bud sizes
 * @param chance how likely freshly generated amethyst is to show as much growth as this chunk and the
 *               amethyst chunks around it; NaN until the chunk is judged against its neighbors
 */
public record ActivityArea(ChunkPos chunk, int minY, int maxY, int surfaceY, double score, int budding,
                           int faces, int stages, double chance) {
    /** The same chunk judged: its score and the chance its neighborhood's growth is worldgen luck. */
    ActivityArea judged(double score, double chance) {
        return new ActivityArea(chunk, minY, maxY, surfaceY, score, budding, faces, stages, chance);
    }

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
