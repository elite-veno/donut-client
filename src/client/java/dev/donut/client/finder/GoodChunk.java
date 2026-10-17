package dev.donut.client.finder;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * A base spot ChunkFinder picked: a chunk with no sign of players in it or the chunks around it.
 *
 * @param surfaceY the highest motion-blocking block in the chunk's middle column, as the server first sent it
 * @param confidence the chance that a player spending ten minutes near the spot would have left growth there
 * @param clearance rings of loaded chunks around the spot that show no sign of players; the edge of the loaded area
 *                  ends it as a sign would, since nothing is known past it
 * @param freshClocks plants within the clearance square that would show a visit by growing
 * @param unseenMinutes how long a player could have stayed nearby with one chance in twenty of leaving no growth
 */
public record GoodChunk(ChunkPos chunk, int surfaceY, double confidence, int clearance, int freshClocks, double unseenMinutes) {
    public Vec3 center() { return new Vec3(chunk.getMinBlockX() + 8.0, surfaceY + 1, chunk.getMinBlockZ() + 8.0); }

    /** The surface block in the chunk's middle column. */
    public BlockPos surfaceCenter() { return new BlockPos(chunk.getMinBlockX() + 8, surfaceY, chunk.getMinBlockZ() + 8); }

    /** A thin square resting on the surface, so it shows from above. */
    public AABB surfaceBounds() {
        int x = chunk.getMinBlockX(), z = chunk.getMinBlockZ();
        return new AABB(x, surfaceY + 1, z, x + 16, surfaceY + 1.05, z + 16);
    }

    public double horizontalDistance(Vec3 origin) {
        double dx = chunk.getMinBlockX() + 8.0 - origin.x, dz = chunk.getMinBlockZ() + 8.0 - origin.z;
        return Math.sqrt(dx * dx + dz * dz);
    }
}
