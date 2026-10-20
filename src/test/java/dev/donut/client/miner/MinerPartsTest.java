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
    void noSwingUnderGravelThatWaterFollows() {
        // A wall of stone to dig into, with gravel over the block and water over the gravel, as under a riverbed: once the
        // block goes, the gravel falls and the water comes after it.
        GridSite site = new GridSite(70);
        BlockPos target = new BlockPos(1, 66, 0);
        for (int y = 65; y <= 66; y++) site.set(new BlockPos(0, y, 0), GridSite.Block.AIR);
        site.set(target.above(), GridSite.Block.GRAVEL);
        site.set(target.above(2), GridSite.Block.GRAVEL);
        site.set(target.above(3), GridSite.Block.WATER);
        net.minecraft.world.phys.Vec3 eye = new net.minecraft.world.phys.Vec3(0.5, 65 + 1.62, 0.5);
        net.minecraft.world.phys.AABB body = new net.minecraft.world.phys.AABB(0.2, 65, 0.2, 0.8, 66.8, 0.8);
        Swings.Reach at = new Swings.Reach(eye, body, 65, 4.5, 0.6, false, new java.util.Random(1));
        Set<BlockPos> blocked = new HashSet<>();
        assertNull(Swings.choose(site, at, ToolShape.SINGLE, List.of(target), cell -> false, cell -> true, blocked, 0),
                "the water would come down into the tunnel");
        assertTrue(blocked.contains(target));

        site.set(target.above(3), GridSite.Block.STONE);
        assertTrue(Swings.choose(site, at, ToolShape.SINGLE, List.of(target), cell -> false, cell -> true, new HashSet<>(), 0) != null,
                "gravel under stone only falls");
    }

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
    void aRoomGoesDownUnderDipsInTheGroundOverAnyColumn() {
        Room room = Room.of(new Box(0, -40, 0, 40, -11, 30), 3, 2, Direction.SOUTH, 6, 4);
        // The room's top is y -9: four blocks of cover need ground at y -5 or higher.
        assertEquals(0, room.sink((x, z) -> 64, 4));
        assertEquals(0, room.sink((x, z) -> -5, 4));
        assertEquals(1, room.sink((x, z) -> -6, 4));
        // A ravine over one corner of a wide room, far from the middle, counts.
        assertEquals(9, room.sink((x, z) -> x > 40 && z < 0 ? -14 : 64, 4));
        // The storage room's top is y -37: a dip over it alone is no matter until it gets that deep.
        assertEquals(0, room.sink((x, z) -> z > 33 ? -30 : 64, 4));
        assertEquals(2, room.sink((x, z) -> z > 33 ? -35 : 64, 4));
        // Columns nobody knows about, outside the chunks loaded, don't count.
        assertEquals(0, room.sink((x, z) -> x < 10 ? Room.UNKNOWN : 64, 4));
        assertEquals(0, room.sink((x, z) -> x < -3 || x > 43 || z < -3 || z > 39 ? -60 : 64, 4), "columns beside the room don't count");
    }

    @Test
    void blocksLeftAreCountedAShareOfColumnsAtATime() {
        Room room = Room.of(new Box(0, 10, 0, 20, 19, 12), 3, 2, Direction.EAST, 6, 4);
        GridSite site = new GridSite(40);
        site.fill(new Box(2, 12, 2, 9, 15, 7), GridSite.Block.AIR);
        Excavator dig = new Excavator(room, new BlockPos(40, 41, 5), false, site);
        int all = dig.left(site), columns = room.bounds.sizeX() * room.bounds.sizeZ();
        for (int pass = 0; pass < 3; pass++) {
            int ticks = 0, counted;
            while ((counted = dig.countLeft(site, Math.ceilDiv(columns, 20))) < 0) ticks++;
            assertEquals(all, counted);
            assertTrue(ticks < 20, "a pass took " + ticks + " ticks");
        }
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
        s.turnPace = Double.NaN;
        s.pauseMin = 9;
        s.pauseMax = 2;
        s.storageSide = 9;
        s.sanitize();
        assertEquals(MinerSettings.MIN_FLOOR_Y, s.floorY);
        assertEquals(12, s.sideRoom);
        assertEquals(3, s.maxDelay);
        assertEquals(1, s.turnPace);
        assertEquals(9, s.pauseMax);
        assertEquals(5, s.storageSide);
        s.setMaxDelay(0.5);
        assertEquals(0.5, s.minDelay);
    }
}
