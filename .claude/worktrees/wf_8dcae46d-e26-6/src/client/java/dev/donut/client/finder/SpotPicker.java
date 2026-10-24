package dev.donut.client.finder;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * ChunkFinder's choice of base spots, kept free of game classes. A spot is a chunk whose surrounding square of
 * {@code clearance} chunks was all read and shows no sign of players, with no structure in it or right next to it, few
 * enough caves under it, and plants around it that would have shown a visit of {@code maxUnseen} minutes. Spots deepest inside clear ground come first, then those with the most telling plants,
 * then the nearest; each keeps a clearance's distance from better ones, so they name different places.
 */
final class SpotPicker {
    /**
     * One read chunk: whether it shows players, whether it is part of a structure, whether it is solid enough
     * underground to dig a base into, and its plants when it shows no players.
     */
    record Cell(int x, int z, boolean sign, boolean structure, boolean minable, GrowthClocks clocks) { }

    /**
     * @param clear rings of read chunks without signs around the spot
     * @param area the ungrown plants within the clearance square
     */
    record Spot(int x, int z, int clear, GrowthClocks area, double unseenMinutes) { }

    private SpotPicker() { }

    static List<Spot> pick(List<Cell> cells, int clearance, double maxUnseen, int originX, int originZ, int max) {
        if (cells.isEmpty() || max <= 0) return List.of();
        Cell[] grid = grid(cells);
        int width = width(cells), height = grid.length / width;
        int[] distance = distances(grid, width, height);
        boolean[] nearStructure = nearStructure(grid, width, height);
        Kinds prefix = new Kinds(grid, width, height);
        List<Spot> candidates = new ArrayList<>();
        for (int i = 0; i < grid.length; i++) {
            Cell cell = grid[i];
            if (cell == null || cell.sign || !cell.minable || nearStructure[i]) continue;
            int clear = distance[i] - 1;
            if (clear < clearance) continue;
            GrowthClocks area = prefix.sum(i % width, i / width, clearance);
            double unseen = area.unseenMinutes();
            if (unseen <= maxUnseen) candidates.add(new Spot(cell.x, cell.z, clear, area, unseen));
        }
        candidates.sort(Comparator.comparingInt((Spot spot) -> -spot.clear)
                .thenComparingDouble(Spot::unseenMinutes)
                .thenComparingLong(spot -> (long) (spot.x - originX) * (spot.x - originX) + (long) (spot.z - originZ) * (spot.z - originZ)));
        List<Spot> picked = new ArrayList<>();
        int spacing = Math.max(2, clearance);
        for (Spot spot : candidates) {
            boolean apart = true;
            for (Spot other : picked) {
                if (Math.max(Math.abs(spot.x - other.x), Math.abs(spot.z - other.z)) < spacing) { apart = false; break; }
            }
            if (!apart) continue;
            picked.add(spot);
            if (picked.size() == max) break;
        }
        return List.copyOf(picked);
    }

    /**
     * Chunks with their whole clearance read and free of signs, before plants are weighed, and of those only the
     * minable ones if asked; for explaining an empty result.
     */
    static int roomy(List<Cell> cells, int clearance, boolean minableOnly) {
        if (cells.isEmpty()) return 0;
        Cell[] grid = grid(cells);
        int width = width(cells);
        int[] distance = distances(grid, width, grid.length / width);
        boolean[] nearStructure = nearStructure(grid, width, grid.length / width);
        int count = 0;
        for (int i = 0; i < grid.length; i++) {
            Cell cell = grid[i];
            if (cell != null && !cell.sign && !nearStructure[i] && (cell.minable || !minableOnly) && distance[i] - 1 >= clearance) count++;
        }
        return count;
    }

    /** The cells by position, with a ring of unread chunks around them so the edge of what was read counts as unknown. */
    private static Cell[] grid(List<Cell> cells) {
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (Cell cell : cells) {
            minX = Math.min(minX, cell.x);
            minZ = Math.min(minZ, cell.z);
            maxZ = Math.max(maxZ, cell.z);
        }
        int width = width(cells);
        Cell[] grid = new Cell[width * (maxZ - minZ + 3)];
        for (Cell cell : cells) grid[(cell.z - minZ + 1) * width + cell.x - minX + 1] = cell;
        return grid;
    }

    private static int width(List<Cell> cells) {
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        for (Cell cell : cells) {
            minX = Math.min(minX, cell.x);
            maxX = Math.max(maxX, cell.x);
        }
        return maxX - minX + 3;
    }

    /** Cells in a structure or touching one, corners too: a base dug there would break into it. */
    private static boolean[] nearStructure(Cell[] grid, int width, int height) {
        boolean[] near = new boolean[grid.length];
        for (int i = 0; i < grid.length; i++) {
            if (grid[i] == null || !grid[i].structure) continue;
            int x = i % width, z = i / width;
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    int nx = x + dx, nz = z + dz;
                    if (nx >= 0 && nz >= 0 && nx < width && nz < height) near[nz * width + nx] = true;
                }
            }
        }
        return near;
    }

    /** Chessboard distance from each cell to the nearest unread or signed one, by a breadth-first search from all of them. */
    private static int[] distances(Cell[] grid, int width, int height) {
        int[] distance = new int[grid.length];
        int[] queue = new int[grid.length];
        int head = 0, tail = 0;
        for (int i = 0; i < grid.length; i++) {
            if (grid[i] == null || grid[i].sign) queue[tail++] = i;
            else distance[i] = Integer.MAX_VALUE;
        }
        while (head < tail) {
            int i = queue[head++], x = i % width, z = i / width;
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    int nx = x + dx, nz = z + dz;
                    if (nx < 0 || nz < 0 || nx >= width || nz >= height) continue;
                    int n = nz * width + nx;
                    if (distance[n] != Integer.MAX_VALUE) continue;
                    distance[n] = distance[i] + 1;
                    queue[tail++] = n;
                }
            }
        }
        return distance;
    }

    /** Running sums of ungrown plants per kind, so any square's count takes four lookups. */
    private static final class Kinds {
        private static final GrowthClocks.Kind[] KINDS = GrowthClocks.Kind.values();
        private final int[][] sums;
        private final int width, height;

        Kinds(Cell[] grid, int width, int height) {
            this.width = width;
            this.height = height;
            sums = new int[KINDS.length][(width + 1) * (height + 1)];
            for (int z = 0; z < height; z++) {
                for (int x = 0; x < width; x++) {
                    Cell cell = grid[z * width + x];
                    for (GrowthClocks.Kind kind : KINDS) {
                        int own = cell == null || cell.sign ? 0 : cell.clocks.clocks(kind) - cell.clocks.grown(kind);
                        int[] s = sums[kind.ordinal()];
                        s[(z + 1) * (width + 1) + x + 1] = own + s[z * (width + 1) + x + 1] + s[(z + 1) * (width + 1) + x]
                                - s[z * (width + 1) + x];
                    }
                }
            }
        }

        GrowthClocks sum(int x, int z, int radius) {
            int x0 = Math.max(0, x - radius), z0 = Math.max(0, z - radius);
            int x1 = Math.min(width, x + radius + 1), z1 = Math.min(height, z + radius + 1);
            GrowthClocks area = new GrowthClocks();
            for (GrowthClocks.Kind kind : KINDS) {
                int[] s = sums[kind.ordinal()];
                int count = s[z1 * (width + 1) + x1] - s[z0 * (width + 1) + x1] - s[z1 * (width + 1) + x0] + s[z0 * (width + 1) + x0];
                if (count > 0) area.addUngrown(kind, count);
            }
            return area;
        }
    }
}
