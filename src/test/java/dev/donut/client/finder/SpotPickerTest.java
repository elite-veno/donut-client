package dev.donut.client.finder;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SpotPickerTest {
    /** A square of read chunks from 0 to size - 1, each with this many fresh sugar canes, and signs where given. */
    private static List<SpotPicker.Cell> square(int size, int canes, int[]... signs) {
        List<SpotPicker.Cell> cells = new ArrayList<>();
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                boolean sign = false;
                for (int[] at : signs) sign |= at[0] == x && at[1] == z;
                GrowthClocks clocks = new GrowthClocks();
                for (int i = 0; i < canes; i++) clocks.add(GrowthClocks.Kind.CANE, false, 0);
                cells.add(new SpotPicker.Cell(x, z, sign, false, true, clocks));
            }
        }
        return cells;
    }

    @Test
    void theBestSpotIsDeepestInsideWhatWasRead() {
        List<SpotPicker.Spot> spots = SpotPicker.pick(square(21, 1), 4, 10, 0, 0, 16);
        SpotPicker.Spot best = spots.getFirst();
        assertEquals(10, best.x());
        assertEquals(10, best.z());
        // Ten rings of read chunks, then the unknown beyond.
        assertEquals(10, best.clear());
        assertEquals(81, best.area().clocks(), "the 9 by 9 clearance square's canes");
    }

    @Test
    void spotsKeepTheirClearanceFromSigns() {
        int clearance = 4;
        List<SpotPicker.Spot> spots = SpotPicker.pick(square(21, 1, new int[] {10, 10}), clearance, 10, 10, 10, 16);
        assertFalse(spots.isEmpty());
        for (SpotPicker.Spot spot : spots) {
            int distance = Math.max(Math.abs(spot.x() - 10), Math.abs(spot.z() - 10));
            assertTrue(distance > clearance, "spot " + spot.x() + "," + spot.z() + " is " + distance + " from the sign");
            assertTrue(spot.clear() >= clearance);
        }
    }

    @Test
    void spotsAreAClearanceApart() {
        List<SpotPicker.Spot> spots = SpotPicker.pick(square(40, 1), 3, 10, 0, 0, 16);
        assertTrue(spots.size() > 1);
        for (int i = 0; i < spots.size(); i++) {
            for (int j = i + 1; j < spots.size(); j++) {
                SpotPicker.Spot a = spots.get(i), b = spots.get(j);
                assertTrue(Math.max(Math.abs(a.x() - b.x()), Math.abs(a.z() - b.z())) >= 3);
            }
        }
    }

    @Test
    void groundWithoutPlantsVouchesForNothing() {
        List<SpotPicker.Cell> bare = square(21, 0);
        assertTrue(SpotPicker.pick(bare, 4, 60, 0, 0, 16).isEmpty());
        assertTrue(SpotPicker.roomy(bare, 4, false) > 0, "clear, just unproven");
    }

    @Test
    void noRoomWhereSignsAreEverywhereOrTheReadAreaIsSmall() {
        int[][] grid = new int[49][];
        for (int i = 0; i < 49; i++) grid[i] = new int[] {(i % 7) * 3, (i / 7) * 3};
        assertEquals(0, SpotPicker.roomy(square(21, 1, grid), 4, false));
        assertEquals(0, SpotPicker.roomy(square(8, 1), 4, false), "8 chunks across has no chunk 4 rings from its edge");
        assertTrue(SpotPicker.pick(List.of(), 4, 10, 0, 0, 16).isEmpty());
    }

    @Test
    void cavesRuleOutTheChunkButNotItsNeighbors() {
        List<SpotPicker.Cell> cells = new ArrayList<>();
        for (SpotPicker.Cell cell : square(21, 1)) {
            boolean hollow = cell.x() == 10 && cell.z() == 10;
            cells.add(new SpotPicker.Cell(cell.x(), cell.z(), false, false, !hollow, cell.clocks()));
        }
        List<SpotPicker.Spot> spots = SpotPicker.pick(cells, 4, 10, 10, 10, 16);
        assertFalse(spots.isEmpty());
        assertTrue(spots.stream().noneMatch(spot -> spot.x() == 10 && spot.z() == 10), "the hollow chunk itself is no spot");
        // A cave under one chunk keeps no clearance: the best spot is right beside it.
        assertEquals(9, spots.getFirst().clear());
        assertEquals(SpotPicker.roomy(cells, 4, false) - 1, SpotPicker.roomy(cells, 4, true));
    }

    @Test
    void structuresKeepSpotsOutOfThemAndTheirNeighborsOnly() {
        List<SpotPicker.Cell> cells = new ArrayList<>();
        for (SpotPicker.Cell cell : square(21, 1)) {
            boolean structure = cell.x() == 10 && cell.z() == 10;
            cells.add(new SpotPicker.Cell(cell.x(), cell.z(), false, structure, true, cell.clocks()));
        }
        List<SpotPicker.Spot> spots = SpotPicker.pick(cells, 4, 10, 10, 10, 16);
        for (SpotPicker.Spot spot : spots) {
            assertTrue(Math.max(Math.abs(spot.x() - 10), Math.abs(spot.z() - 10)) >= 2, spot.x() + "," + spot.z() + " touches the structure");
        }
        // The structure keeps no clearance: a spot two chunks off still has the whole read area around it.
        assertTrue(spots.stream().anyMatch(spot -> Math.max(Math.abs(spot.x() - 10), Math.abs(spot.z() - 10)) == 2 && spot.clear() >= 6));
        assertEquals(SpotPicker.roomy(square(21, 1), 4, false) - 9, SpotPicker.roomy(cells, 4, false));
    }

    @Test
    void equalSpotsGoNearestFirst() {
        // Two separate clean squares, the same size, with a wall of signs between them.
        List<SpotPicker.Cell> cells = new ArrayList<>();
        for (int x = 0; x < 31; x++) {
            for (int z = 0; z < 9; z++) {
                GrowthClocks clocks = new GrowthClocks();
                clocks.add(GrowthClocks.Kind.CANE, false, 0);
                cells.add(new SpotPicker.Cell(x, z, x >= 9 && x <= 21, false, true, clocks));
            }
        }
        List<SpotPicker.Spot> spots = SpotPicker.pick(cells, 2, 10, 30, 4, 16);
        // Each square's middle is the only chunk four rings clear; the one beside the origin comes first.
        assertEquals(26, spots.get(0).x());
        assertEquals(4, spots.get(0).z());
        assertEquals(4, spots.get(1).x());
        assertEquals(4, spots.get(1).z());
        assertEquals(4, spots.get(1).clear());
    }
}
