package dev.donut.client.miner;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/** A world of whole blocks for tests: stone below a height, air above, and whatever a test puts in. */
final class GridSite implements Site {
    enum Block { AIR, STONE, GRAVEL, BEDROCK, CHEST, WATER, LAVA }

    private final Map<BlockPos, Block> blocks = new HashMap<>();
    private final java.util.function.IntBinaryOperator ground;

    /** Stone at and below {@code ground}, air above it. */
    GridSite(int ground) { this((x, z) -> ground); }

    /** Stone up to a height that may change from column to column: hills and dips. */
    GridSite(java.util.function.IntBinaryOperator ground) { this.ground = ground; }

    int ground(int x, int z) { return ground.applyAsInt(x, z); }

    Block get(BlockPos pos) {
        Block block = blocks.get(pos);
        return block != null ? block : pos.getY() <= ground.applyAsInt(pos.getX(), pos.getZ()) ? Block.STONE : Block.AIR;
    }

    void set(BlockPos pos, Block block) { blocks.put(pos.immutable(), block); }

    void fill(Box box, Block block) {
        for (int x = box.minX(); x <= box.maxX(); x++)
            for (int y = box.minY(); y <= box.maxY(); y++)
                for (int z = box.minZ(); z <= box.maxZ(); z++) set(new BlockPos(x, y, z), block);
    }

    @Override
    public int flags(int x, int y, int z) {
        return switch (get(new BlockPos(x, y, z))) {
            case AIR -> CLEAR;
            case STONE -> SOLID | DIG;
            case GRAVEL -> SOLID | DIG | FALLS;
            case BEDROCK, CHEST -> SOLID | HARD;
            case WATER -> FLUID;
            case LAVA -> FLUID | LAVA;
        };
    }

    /** Blocks a look stops at: everything with an outline, so not air, water or lava. */
    private boolean stops(BlockPos pos) {
        Block block = get(pos);
        return block != Block.AIR && block != Block.WATER && block != Block.LAVA;
    }

    @Override
    public boolean sees(Vec3 eye, Vec3 point, BlockPos pos, Direction face) {
        Vec3 dir = point.subtract(eye);
        Vec3 end = point.add(dir.normalize().scale(0.05));
        Hit hit = clip(eye, end);
        return hit != null && hit.pos.equals(pos) && hit.face == face;
    }

    record Hit(BlockPos pos, Direction face) { }

    /** The first block with an outline along the segment, and the face it was entered by; null when there is none. */
    Hit clip(Vec3 from, Vec3 to) {
        Vec3 d = to.subtract(from);
        int x = (int) Math.floor(from.x), y = (int) Math.floor(from.y), z = (int) Math.floor(from.z);
        int sx = (int) Math.signum(d.x), sy = (int) Math.signum(d.y), sz = (int) Math.signum(d.z);
        double tx = next(from.x, d.x), ty = next(from.y, d.y), tz = next(from.z, d.z);
        double dx = sx == 0 ? Double.MAX_VALUE : Math.abs(1 / d.x), dy = sy == 0 ? Double.MAX_VALUE : Math.abs(1 / d.y),
                dz = sz == 0 ? Double.MAX_VALUE : Math.abs(1 / d.z);
        if (stops(new BlockPos(x, y, z))) return null;
        while (true) {
            Direction face;
            if (tx <= ty && tx <= tz) {
                if (tx > 1) return null;
                x += sx;
                tx += dx;
                face = sx > 0 ? Direction.WEST : Direction.EAST;
            } else if (ty <= tz) {
                if (ty > 1) return null;
                y += sy;
                ty += dy;
                face = sy > 0 ? Direction.DOWN : Direction.UP;
            } else {
                if (tz > 1) return null;
                z += sz;
                tz += dz;
                face = sz > 0 ? Direction.NORTH : Direction.SOUTH;
            }
            BlockPos at = new BlockPos(x, y, z);
            if (stops(at)) return new Hit(at, face);
        }
    }

    /** The fraction of the segment at which it first crosses a block boundary along one axis. */
    private static double next(double start, double delta) {
        if (delta == 0) return Double.MAX_VALUE;
        return delta > 0 ? (Math.floor(start) + 1 - start) / delta : (start - Math.floor(start)) / -delta;
    }
}
