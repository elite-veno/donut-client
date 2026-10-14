package dev.donut.client.finder;

import java.util.List;

/**
 * Finds the straight passages players dig: 1x2 tunnels, 1x1 vertical holes and 1-wide staircases.
 * Caves, ravines and generated structures are rarely this narrow and regular for long.
 * Anti-xray plugins only disguise buried solid blocks, so dug-out air always reaches the client.
 */
public final class TunnelDetector {
    /** How far a run is followed outside the scanned area to measure its whole length. */
    static final int MAX_RUN = 128;
    /** A piece inside the scanned area is followed this far; a vertical hole can span the whole world height. */
    private static final int MAX_PIECE = 1024;

    public interface Grid {
        /** Space a player moves through: air, torches, rails, ladders; not fluids. */
        boolean open(int x, int y, int z);

        /** Blocks that stop movement: the floor, ceiling and walls of a passage. */
        boolean solid(int x, int y, int z);

        /** Whether this column belongs to the area being scanned, such as one chunk. */
        default boolean inside(int x, int z) { return true; }
    }

    public enum Kind { TUNNEL, HOLE, STAIRS }

    /**
     * A run of {@code length} cells starting at x/y/z, each the previous one moved by dx/dy/dz, and the piece of a
     * passage {@code total} cells long that lies in the scanned area. Each cell is {@link #cellHeight()} blocks tall.
     */
    public record Segment(Kind kind, int x, int y, int z, int dx, int dy, int dz, int length, int total) {
        public Segment(Kind kind, int x, int y, int z, int dx, int dy, int dz, int length) {
            this(kind, x, y, z, dx, dy, dz, length, length);
        }

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
     * Checks the passages whose piece in the scanned area starts at this block. Each area reports its own piece, so
     * scanning every block of an area reports each passage once per area it crosses, measured over its whole length.
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
        if (!member(kind, x, y, z, dx, dy, dz)) return;
        boolean continues = member(kind, x - dx, y - dy, z - dz, dx, dy, dz);
        if (continues && grid.inside(x - dx, z - dz)) return;
        int length = 1;
        while (length < MAX_PIECE && grid.inside(x + dx * length, z + dz * length)
                && member(kind, x + dx * length, y + dy * length, z + dz * length, dx, dy, dz)) length++;
        int before = 0;
        while (continues && before < MAX_RUN && member(kind, x - dx * (before + 1), y - dy * (before + 1), z - dz * (before + 1), dx, dy, dz)) {
            before++;
        }
        int after = 0, end = length;
        while (!grid.inside(x + dx * end, z + dz * end) && after < MAX_RUN
                && member(kind, x + dx * (end + after), y + dy * (end + after), z + dz * (end + after), dx, dy, dz)) after++;
        int total = before + length + after;
        if (total >= needed) found.add(new Segment(kind, x, y, z, dx, dy, dz, length, total));
    }

    /**
     * A cell of the passage, or a single gap in one of its walls between two cells: a branch mine's side tunnels open
     * off its main corridor every few blocks. A cave rarely keeps a floor, a ceiling and walls this long.
     */
    private boolean member(Kind kind, int x, int y, int z, int dx, int dy, int dz) {
        if (cell(kind, x, y, z, dx, dz)) return true;
        return kind != Kind.HOLE && gap(kind, x, y, z, dx, dz)
                && cell(kind, x - dx, y - dy, z - dz, dx, dz) && cell(kind, x + dx, y + dy, z + dz, dx, dz);
    }

    /** Whether one cross-section of a passage running along dx/dz starts at x/y/z. */
    boolean cell(Kind kind, int x, int y, int z, int dx, int dz) {
        return switch (kind) {
            case TUNNEL -> corridor(x, y, z, dx, dz, 2, false);
            case STAIRS -> corridor(x, y, z, dx, dz, 3, false);
            case HOLE -> grid.open(x, y, z)
                    && grid.solid(x + 1, y, z) && grid.solid(x - 1, y, z)
                    && grid.solid(x, y, z + 1) && grid.solid(x, y, z - 1);
        };
    }

    private boolean gap(Kind kind, int x, int y, int z, int dx, int dz) {
        return corridor(x, y, z, dx, dz, kind == Kind.TUNNEL ? 2 : 3, true);
    }

    /**
     * An open column of the given height, with a floor, a ceiling and walls on both sides; with {@code oneSideOpen},
     * one of the two walls is open all the way up instead.
     */
    private boolean corridor(int x, int y, int z, int dx, int dz, int height, boolean oneSideOpen) {
        if (!grid.solid(x, y - 1, z) || !grid.solid(x, y + height, z)) return false;
        // The walls sit across the direction of travel.
        int sideX = dz, sideZ = dx;
        boolean leftWall = true, rightWall = true;
        for (int level = 0; level < height; level++) {
            if (!grid.open(x, y + level, z)) return false;
            leftWall &= grid.solid(x + sideX, y + level, z + sideZ);
            rightWall &= grid.solid(x - sideX, y + level, z - sideZ);
        }
        if (!oneSideOpen) return leftWall && rightWall;
        if (leftWall == rightWall) return false;
        // The open side is a side passage, not open ground: it has its own floor and ceiling.
        int openX = leftWall ? x - sideX : x + sideX, openZ = leftWall ? z - sideZ : z + sideZ;
        if (!grid.solid(openX, y - 1, openZ) || !grid.solid(openX, y + height, openZ)) return false;
        for (int level = 0; level < height; level++) if (!grid.open(openX, y + level, openZ)) return false;
        return true;
    }
}
