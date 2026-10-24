package dev.donut.client.finder;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * A chunk with signs of players: {@code minY}/{@code maxY} span its evidence, {@code score} weighs it, and
 * {@code reasons} names the strongest, such as "shulker box, 3 signs, beacon".
 */
public record BaseArea(ChunkPos chunk, int minY, int maxY, int score, String reasons) {
    /** The chunk around its evidence. */
    public AABB bounds() {
        int x = chunk.getMinBlockX(), z = chunk.getMinBlockZ();
        return new AABB(x, minY, z, x + 16, maxY + 1, z + 16);
    }

    public Vec3 center() {
        return new Vec3(chunk.getMinBlockX() + 8.0, (minY + maxY + 1) / 2.0, chunk.getMinBlockZ() + 8.0);
    }

    public double horizontalDistance(Vec3 origin) {
        double dx = chunk.getMinBlockX() + 8.0 - origin.x, dz = chunk.getMinBlockZ() + 8.0 - origin.z;
        return Math.sqrt(dx * dx + dz * dz);
    }
}
