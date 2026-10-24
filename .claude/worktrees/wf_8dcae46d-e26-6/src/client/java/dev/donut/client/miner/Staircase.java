package dev.donut.client.miner;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;

/**
 * Paths dug through rock: where the player's feet go, one block over at a time, rising or dropping a block per step.
 * A path winds down (or up) around a pillar until a straight run can make up the rest of the height, then leaves the
 * winding on the side facing its end and runs there, along that side's axis first.
 *
 * <p>The start is the middle of one side of the winding's ring. A normal pickaxe winds around a single block. With
 * landings every other step is level, and each level step and the climb or drop after it go the same way: an area
 * pickaxe clears a step's blocks with a level look from one block back, so it digs each pair from the landing before
 * it. Its winding goes around a 3x3 pillar, so each side holds two pairs.
 */
public final class Staircase {
    private Staircase() { }

    /** The way from the start to the middle of the winding: the start is the middle of one side of its ring. */
    public record Winding(int dx, int dz) {
        public static final Winding[] ALL = {new Winding(1, 0), new Winding(0, 1), new Winding(-1, 0), new Winding(0, -1)};
    }

    private static int radius(boolean landings) { return landings ? 2 : 1; }

    /** The columns a winding from {@code start} digs or stands on: its ring and its pillar. */
    public static List<int[]> columns(BlockPos start, boolean landings, Winding winding) {
        List<int[]> columns = new ArrayList<>();
        int r = radius(landings);
        int cx = start.getX() + r * winding.dx(), cz = start.getZ() + r * winding.dz();
        for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) columns.add(new int[] {cx + dx, cz + dz});
        return columns;
    }

    /** Feet positions winding from {@code start} to {@code targetY}, start first. */
    public static List<BlockPos> spiral(BlockPos start, int targetY, boolean landings, Winding winding) {
        return build(start, start.getX(), start.getZ(), targetY, landings, winding, false);
    }

    /** Feet positions from {@code start} to {@code endX, endZ} at {@code targetY}, start first. */
    public static List<BlockPos> approach(BlockPos start, int endX, int endZ, int targetY, boolean landings) {
        // The winding goes behind the start, away from the end, so the start is already on the side the run leaves by.
        int dx = endX - start.getX(), dz = endZ - start.getZ();
        Winding away = Math.abs(dx) >= Math.abs(dz) ? new Winding(dx > 0 ? -1 : 1, 0) : new Winding(0, dz > 0 ? -1 : 1);
        return build(start, endX, endZ, targetY, landings, away, true);
    }

    private static List<BlockPos> build(BlockPos start, int endX, int endZ, int targetY, boolean landings, Winding winding, boolean toEnd) {
        List<BlockPos> path = new ArrayList<>();
        path.add(start);
        int dir = Integer.signum(targetY - start.getY());
        boolean straight = !toEnd || endX == start.getX() && endZ == start.getZ();
        // The run leaves along the axis the end lies furthest along; with landings it makes up all the height on that
        // first leg, so no level-and-drop pair is split by its turn.
        boolean xFirst = Math.abs(endX - start.getX()) >= Math.abs(endZ - start.getZ());
        boolean vertical = !landings;
        BlockPos at = start;
        boolean wind = straight ? targetY != start.getY() : Math.abs(targetY - start.getY()) > runReach(start, endX, endZ, landings, xFirst, vertical);
        if (wind) {
            List<int[]> ring = ring(start, radius(landings), winding);
            for (int index = 1; index < 8192; index++) {
                int[] cell = ring.get(index % ring.size());
                int dy = vertical && at.getY() != targetY ? dir : 0;
                at = new BlockPos(cell[0], at.getY() + dy, cell[1]);
                path.add(at);
                if (landings) vertical = !vertical;
                if (straight) {
                    if (at.getY() == targetY) break;
                    continue;
                }
                // Out by the side facing the end, with a level step next so the run starts a pair of its own.
                boolean facing = winding.dx() != 0 ? cell[0] == start.getX() : cell[1] == start.getZ();
                if (facing && (!landings || !vertical) && Math.abs(targetY - at.getY()) <= runReach(at, endX, endZ, landings, xFirst, vertical)) break;
            }
        }
        if (!toEnd) return path;
        while (at.getX() != endX || at.getZ() != endZ) {
            int sx = Integer.signum(endX - at.getX()), sz = Integer.signum(endZ - at.getZ());
            if (xFirst ? sx != 0 : sz == 0) sz = 0;
            else sx = 0;
            int dy = at.getY() != targetY && vertical ? Integer.signum(targetY - at.getY()) : 0;
            at = at.offset(sx, dy, sz);
            path.add(at);
            if (landings) vertical = !vertical;
        }
        return path;
    }

    /**
     * The ring around a pillar {@code r} blocks the winding's way from {@code start}, clockwise seen from above,
     * starting at {@code start}, the middle of its nearest side: {@code 8r} blocks, each beside the one before.
     */
    static List<int[]> ring(BlockPos start, int r, Winding winding) {
        int cx = start.getX() + r * winding.dx(), cz = start.getZ() + r * winding.dz();
        // Clockwise with x east and z south: along the north side east, the east side south, the south side west, the west side north.
        List<int[]> loop = new ArrayList<>(8 * r);
        for (int x = -r; x < r; x++) loop.add(new int[] {cx + x, cz - r});
        for (int z = -r; z < r; z++) loop.add(new int[] {cx + r, cz + z});
        for (int x = r; x > -r; x--) loop.add(new int[] {cx + x, cz + r});
        for (int z = r; z > -r; z--) loop.add(new int[] {cx - r, cz + z});
        int first = 0;
        for (int i = 0; i < loop.size(); i++) if (loop.get(i)[0] == start.getX() && loop.get(i)[1] == start.getZ()) first = i;
        List<int[]> ring = new ArrayList<>(loop.size());
        for (int i = 0; i < loop.size(); i++) ring.add(loop.get((first + i) % loop.size()));
        return ring;
    }

    /** How much height the run from {@code at} to the end can make up, its next step vertical or not. */
    private static int runReach(BlockPos at, int endX, int endZ, boolean landings, boolean xFirst, boolean verticalNext) {
        if (!landings) return Math.abs(endX - at.getX()) + Math.abs(endZ - at.getZ());
        int steps = xFirst ? Math.abs(endX - at.getX()) : Math.abs(endZ - at.getZ());
        return verticalNext ? (steps + 1) / 2 : steps / 2;
    }

    /** The blocks a step from {@code from} to the next block over, {@code to}, needs empty, lowest first. */
    public static List<BlockPos> clears(BlockPos from, BlockPos to) {
        List<BlockPos> cells = new ArrayList<>(4);
        int top = Math.max(from.getY(), to.getY()) + 1;
        for (int y = to.getY(); y <= top; y++) cells.add(new BlockPos(to.getX(), y, to.getZ()));
        // Climbing needs the block over the head free for the jump.
        for (int y = from.getY() + 2; y <= top; y++) cells.add(new BlockPos(from.getX(), y, from.getZ()));
        return cells;
    }
}
