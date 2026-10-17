package dev.donut.client.miner;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.donut.client.config.MinerSettings;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

final class MinerPartsTest {
    @Test
    void roomHasSpaceAroundTheSchematicAndKeepsItsFloor() {
        Box schematic = new Box(0, 10, 0, 14, 23, 24);
        Room room = Room.of(schematic, 3, 2, Direction.SOUTH, 6, 4);
        assertEquals(new Box(-3, 10, -3, 17, 25, 27), room.main);
        assertEquals(new Box(-3, 10, 28, 17, 13, 33), room.storage);
        assertEquals(10, room.floorY);
        assertTrue(room.contains(0, 10, 30));
        assertFalse(room.contains(0, 14, 30), "the storage room is lower");
        assertTrue(room.belowFloor(new BlockPos(5, 9, 5)));
        assertTrue(room.belowFloor(new BlockPos(18, 9, 5)), "right beside the room counts too");
        assertFalse(room.belowFloor(new BlockPos(20, 9, 5)));
        assertNull(Room.of(schematic, 3, 2, null, 6, 4).storage);
    }

    @Test
    void bandsAreMinedStandingOnTheOneBelow() {
        Room room = Room.of(new Box(0, 0, 0, 4, 9, 4), 0, 0, null, 0, 0);
        List<int[]> four = room.tiers(4);
        assertEquals(3, four.size());
        assertArrayEquals(new int[] {0, 3}, four.get(0));
        assertArrayEquals(new int[] {8, 9}, four.get(2));
        // A top band one block tall can't be stood in: it joins the one below.
        List<int[]> three = Room.of(new Box(0, 0, 0, 4, 9, 4), 0, 0, null, 0, 0).tiers(3);
        assertArrayEquals(new int[] {6, 9}, three.getLast());
        // A room is never lower than a player.
        assertEquals(1, Room.of(new Box(0, 5, 0, 3, 5, 3), 0, 0, null, 0, 0).topY - 5);
    }

    @Test
    void staircasesStepOneBlockAtATimeAndEndWhereTheyShould() {
        for (boolean landings : new boolean[] {false, true}) {
            for (BlockPos start : List.of(new BlockPos(30, 70, 4), new BlockPos(-25, 70, 40), new BlockPos(3, 70, -9), new BlockPos(0, 70, 0))) {
                List<BlockPos> path = Staircase.approach(start, 5, 5, -40, landings);
                assertEquals(start, path.getFirst());
                assertEquals(new BlockPos(5, -40, 5), path.getLast(), "from " + start + (landings ? " with landings" : ""));
                Set<BlockPos> seen = new HashSet<>();
                for (int i = 1; i < path.size(); i++) {
                    BlockPos a = path.get(i - 1), b = path.get(i);
                    assertEquals(1, Math.abs(a.getX() - b.getX()) + Math.abs(a.getZ() - b.getZ()), "a step over one block at " + b);
                    assertTrue(Math.abs(a.getY() - b.getY()) <= 1);
                    assertTrue(seen.add(b), "the staircase comes back to " + b);
                    // No step digs into the floor of another.
                    for (BlockPos cell : Staircase.clears(a, b)) {
                        for (BlockPos step : path) assertFalse(step.below().equals(cell), "the step at " + b + " digs out the floor of " + step);
                    }
                }
                if (landings) {
                    // Each climb or drop follows a level step the same way.
                    for (int i = 2; i < path.size(); i++) {
                        BlockPos a = path.get(i - 2), b = path.get(i - 1), c = path.get(i);
                        if (c.getY() == b.getY()) continue;
                        assertEquals(a.getY(), b.getY(), "two climbs in a row at " + c);
                        assertEquals(b.getX() - a.getX(), c.getX() - b.getX(), "a drop that turns at " + b);
                        assertEquals(b.getZ() - a.getZ(), c.getZ() - b.getZ(), "a drop that turns at " + b);
                    }
                }
            }
        }
    }

    @Test
    void windingsStayInTheirColumns() {
        BlockPos start = new BlockPos(0, 20, 0);
        for (boolean landings : new boolean[] {false, true}) {
            for (Staircase.Winding winding : Staircase.Winding.ALL) {
                Set<Long> columns = new HashSet<>();
                for (int[] c : Staircase.columns(start, landings, winding)) columns.add(BlockPos.asLong(c[0], 0, c[1]));
                List<BlockPos> up = Staircase.spiral(start, 31, landings, winding);
                assertEquals(31, up.getLast().getY());
                for (BlockPos step : up) assertTrue(columns.contains(BlockPos.asLong(step.getX(), 0, step.getZ())), step + " is outside its winding");
            }
        }
    }

    @Test
    void areaSwingsBreakASquareFlatAgainstTheFace() {
        BlockPos at = new BlockPos(5, 5, 5);
        List<BlockPos> up = ToolShape.area(1).cells(at, Direction.UP);
        assertEquals(9, up.size());
        assertEquals(at, up.getFirst());
        assertTrue(up.stream().allMatch(p -> p.getY() == 5));
        List<BlockPos> north = ToolShape.area(1).cells(at, Direction.NORTH);
        assertTrue(north.stream().allMatch(p -> p.getZ() == 5));
        assertTrue(north.contains(at.offset(1, 1, 0)) && north.contains(at.offset(-1, -1, 0)));
        assertEquals(List.of(at), ToolShape.SINGLE.cells(at, Direction.EAST));
        assertEquals(25, ToolShape.area(2).cells(at, Direction.WEST).size());
    }

    @Test
    void looksAgreeWithFacesAcrossFortyFiveDegrees() {
        assertTrue(ToolShape.agrees(Direction.UP, 0, 70));
        assertFalse(ToolShape.agrees(Direction.UP, 0, 40));
        assertTrue(ToolShape.agrees(Direction.DOWN, 0, -60));
        // Looking north (yaw 180) at a south face, level.
        assertTrue(ToolShape.agrees(Direction.SOUTH, 180, 10));
        assertFalse(ToolShape.agrees(Direction.SOUTH, 180, 50), "too steep for a side");
        assertFalse(ToolShape.agrees(Direction.SOUTH, 90, 0), "looking west past it");
        assertFalse(ToolShape.agrees(Direction.SOUTH, 0, 0), "looking away");
        assertTrue(ToolShape.agrees(Direction.EAST, 90, 0));
    }

    @Test
    void settingsStayInRange() {
        MinerSettings s = new MinerSettings();
        s.floorY = -500;
        s.sideRoom = 99;
        s.minDelay = 3;
        s.maxDelay = 1;
        s.turnSpeed = Double.NaN;
        s.pauseMin = 9;
        s.pauseMax = 2;
        s.storageSide = 9;
        s.sanitize();
        assertEquals(MinerSettings.MIN_FLOOR_Y, s.floorY);
        assertEquals(12, s.sideRoom);
        assertEquals(3, s.maxDelay);
        assertEquals(24, s.turnSpeed);
        assertEquals(9, s.pauseMax);
        assertEquals(5, s.storageSide);
        s.setMaxDelay(0.5);
        assertEquals(0.5, s.minDelay);
    }
}
