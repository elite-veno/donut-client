package dev.donut.client.finder;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * A chunk ChunkFinder keeps spots away from, with why: plants that grew, amethyst growth, player-built blocks, or a
 * player standing in it, such as "Kelp grew, ~25 min" or "Player blocks: shulker box".
 */
public record ChunkSign(ChunkPos chunk, int surfaceY, String reason) {
    public AABB surfaceBounds() {
        int x = chunk.getMinBlockX(), z = chunk.getMinBlockZ();
        return new AABB(x, surfaceY + 1, z, x + 16, surfaceY + 1.05, z + 16);
    }

    public double horizontalDistance(Vec3 origin) {
        double dx = chunk.getMinBlockX() + 8.0 - origin.x, dz = chunk.getMinBlockZ() + 8.0 - origin.z;
        return Math.sqrt(dx * dx + dz * dz);
    }
}
