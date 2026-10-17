package dev.donut.client.finder;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * A chunk ChunkFinder keeps spots away from, with why, such as "Kelp grew, ~25 min", "Built: crafting table" or
 * "Structure: rail, 20 oak planks".
 *
 * @param structure part of a generated structure, which only keeps spots out of it and the chunks right around it;
 *                  every other sign keeps them a whole clearance away
 */
public record ChunkSign(ChunkPos chunk, int surfaceY, String reason, boolean structure) {
    public AABB surfaceBounds() {
        int x = chunk.getMinBlockX(), z = chunk.getMinBlockZ();
        return new AABB(x, surfaceY + 1, z, x + 16, surfaceY + 1.05, z + 16);
    }

    public double horizontalDistance(Vec3 origin) {
        double dx = chunk.getMinBlockX() + 8.0 - origin.x, dz = chunk.getMinBlockZ() + 8.0 - origin.z;
        return Math.sqrt(dx * dx + dz * dz);
    }
}
