package dev.donut.client.finder;

import java.util.List;

/**
 * Finds the straight passages players dig: 1x2 tunnels, 1x1 vertical holes and 1-wide staircases.
 * Caves, ravines and generated structures are rarely this narrow and regular for long.
 * Anti-xray plugins only disguise buried solid blocks, so dug-out air always reaches the client.
 */
public final class TunnelDetector {
    /** Longer runs are cut here; a long tunnel simply becomes several highlights. */
    static final int MAX_RUN = 128;

    public interface Grid {
        /** Space a player moves through: air, torches, rails, ladders; not fluids. */
        boolean open(int x, int y, int z);

        /** Blocks that stop movement: the floor, ceiling and walls of a passage. */
        boolean solid(int x, int y, int z);
    }

    public enum Kind { TUNNEL, HOLE, STAIRS }

    /**
     * A run of {@code length} cells starting at x/y/z, each the previous one moved by dx/dy/dz.
     * Each cell is {@link #cellHeight()} blocks tall.
     */
    public record Segment(Kind kind, int x, int y, int z, int dx, int dy, int dz, int length) {
        public int cellHeight() { return switch (kind) { case TUNNEL -> 2; case HOLE -> 1; case STAIRS -> 3; }; }
    }

    private final Grid grid;
    private final int minLength;
    private final int minSteps;

    /** @param minLength the shortest tunnel or hole; staircases need half as many steps, at least four */
    public TunnelDetector(Grid grid, int minLength) {
        this.grid = grid;
        this.minLength = Math.max(2, minLength);
        this.minSteps = Math.max(4, minLength / 2);
    }

    /**
     * Checks the passages that start at this block. A run belongs to its first cell, so scanning every
     * block of an area reports each passage exactly once even when it continues outside the area.
     */
    public void scanCell(int x, int y, int z, boolean tunnels, boolean holes, boolean stairs, List<Segment> found) {
        if (!grid.open(x, y, z)) return;
        if (tunnels) {
            run(Kind.TUNNEL, x, y, z, 1, 0, 0, minLength, found);
            run(Kind.TUNNEL, x, y, z, 0, 0, 1, minLength, found);
        }
        if (holes) run(Kind.HOLE, x, y, z, 0, 1, 0, minLength, found);
        if (stairs) {
            run(Kind.STAIRS, x, y, z, 1, 1, 0, minSteps, found);
            run(Kind.STAIRS, x, y, z, 1, -1, 0, minSteps, found);
            run(Kind.STAIRS, x, y, z, 0, 1, 1, minSteps, found);
            run(Kind.STAIRS, x, y, z, 0, -1, 1, minSteps, found);
        }
    }

    private void run(Kind kind, int x, int y, int z, int dx, int dy, int dz, int needed, List<Segment> found) {
        if (!cell(kind, x, y, z, dx, dz) || cell(kind, x - dx, y - dy, z - dz, dx, dz)) return;
        int length = 1;
        while (length < MAX_RUN && cell(kind, x + dx * length, y + dy * length, z + dz * length, dx, dz)) length++;
        if (length >= needed) found.add(new Segment(kind, x, y, z, dx, dy, dz, length));
    }

    /** Whether one cross-section of a passage running along dx/dz starts at x/y/z. */
    boolean cell(Kind kind, int x, int y, int z, int dx, int dz) {
        return switch (kind) {
            case TUNNEL -> corridor(x, y, z, dx, dz, 2);
            case STAIRS -> corridor(x, y, z, dx, dz, 3);
            case HOLE -> grid.open(x, y, z)
                    && grid.solid(x + 1, y, z) && grid.solid(x - 1, y, z)
                    && grid.solid(x, y, z + 1) && grid.solid(x, y, z - 1);
        };
    }

    /** An open column of the given height, with a floor, a ceiling and walls on both sides. */
    private boolean corridor(int x, int y, int z, int dx, int dz, int height) {
        if (!grid.solid(x, y - 1, z) || !grid.solid(x, y + height, z)) return false;
        // The walls sit across the direction of travel.
        int sideX = dz, sideZ = dx;
        for (int level = 0; level < height; level++) {
            if (!grid.open(x, y + level, z)) return false;
            if (!grid.solid(x + sideX, y + level, z + sideZ) || !grid.solid(x - sideX, y + level, z - sideZ)) return false;
        }
        return true;
    }
}
