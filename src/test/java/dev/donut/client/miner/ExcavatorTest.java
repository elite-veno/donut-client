package dev.donut.client.miner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/**
 * Digs whole rooms in a block grid with a player that carries out every action exactly, checking each one is something
 * a player could do: the block in reach and in view, an area swing's look agreeing with its face, every walk over
 * solid ground. Nothing under the player, under the room or under a step may break, and no block next to water or lava.
 */
final class ExcavatorTest {
    private static final double REACH = 4.5;
    private static final int GROUND = 40;
    /** The schematic's box: 7 wide, 6 tall, 9 long, its bottom at y 20. */
    private static final Box SCHEMATIC = new Box(0, 20, 0, 6, 25, 8);

    private static Room room() { return Room.of(SCHEMATIC, 2, 1, Direction.EAST, 4, 3); }

    @Test
    void digsTheRoomWithAPickaxeFromTheSurface() {
        Sim sim = new Sim(new GridSite(GROUND), room(), new BlockPos(20, GROUND + 1, 4), null, true, 1);
        sim.run();
        sim.assertDug();
    }

    @Test
    void digsTheRoomWithAnAreaPickaxeOnly() {
        Sim sim = new Sim(new GridSite(GROUND), room(), new BlockPos(20, GROUND + 1, 4), ToolShape.area(1), false, 2);
        sim.run();
        sim.assertDug();
        assertTrue(sim.areaSwings > 0);
    }

    @Test
    void digsTheRoomWithBothPickaxes() {
        for (int seed = 3; seed < 6; seed++) {
            Sim sim = new Sim(new GridSite(GROUND), room(), new BlockPos(-14, GROUND + 1, 30), ToolShape.area(1), true, seed);
            sim.run();
            sim.assertDug();
        }
    }

    @Test
    void climbsFromTheRoomsFloorWhenStartedInsideIt() {
        GridSite site = new GridSite(GROUND);
        BlockPos start = new BlockPos(3, 20, 4);
        site.set(start, GridSite.Block.AIR);
        site.set(start.above(), GridSite.Block.AIR);
        Sim sim = new Sim(site, room(), start, null, true, 7);
        sim.run();
        sim.assertDug();
        Sim area = new Sim(siteWithPocket(start), room(), start, ToolShape.area(1), false, 8);
        area.run();
        area.assertDug();
    }

    private static GridSite siteWithPocket(BlockPos start) {
        GridSite site = new GridSite(GROUND);
        site.set(start, GridSite.Block.AIR);
        site.set(start.above(), GridSite.Block.AIR);
        return site;
    }

    @Test
    void leavesBlocksNextToLavaAndWater() {
        for (ToolShape area : new ToolShape[] {null, ToolShape.area(1)}) {
            GridSite site = new GridSite(GROUND);
            Room room = room();
            BlockPos lava = new BlockPos(room.main.minX() - 1, 21, 4), water = new BlockPos(3, room.topY + 1, room.main.maxZ() - 1);
            site.set(lava, GridSite.Block.LAVA);
            site.set(water, GridSite.Block.WATER);
            Sim sim = new Sim(site, room, new BlockPos(20, GROUND + 1, 4), area, area == null, 11);
            sim.run();
            assertTrue(sim.dig.wet().contains(lava.east()), "the block holding the lava back stays");
            assertTrue(sim.dig.wet().contains(water.below()), "the block holding the water up stays");
            sim.assertDug();
        }
    }

    @Test
    void gravelInTheCeilingNeverFallsOnThePlayer() {
        GridSite site = new GridSite(GROUND);
        Room room = room();
        site.fill(new Box(room.main.minX(), room.topY + 1, room.main.minZ(), room.main.maxX(), room.topY + 3, room.main.maxZ()), GridSite.Block.GRAVEL);
        Sim sim = new Sim(site, room, new BlockPos(20, GROUND + 1, 4), null, true, 12);
        sim.run();
        sim.assertDug();
    }

    @Test
    void fillsAGapUnderTheStaircase() {
        GridSite site = new GridSite(GROUND);
        // A cave the staircase runs into, with no floor under its first steps.
        site.fill(new Box(13, 27, 2, 18, 33, 6), GridSite.Block.AIR);
        Sim sim = new Sim(site, room(), new BlockPos(20, GROUND + 1, 4), null, true, 13);
        sim.run();
        sim.assertDug();
    }

    @Test
    void containersAreLeftWhole() {
        GridSite site = new GridSite(GROUND);
        BlockPos chest = new BlockPos(3, 22, 4);
        site.set(chest, GridSite.Block.CHEST);
        Sim sim = new Sim(site, room(), new BlockPos(20, GROUND + 1, 4), ToolShape.area(1), true, 14);
        sim.run();
        assertEquals(GridSite.Block.CHEST, site.get(chest));
    }

    @Test
    void digsRoomsOfAllSizesFromAllSides() {
        Random random = new Random(20261017);
        for (int run = 0; run < 40; run++) {
            int sx = 1 + random.nextInt(16), sy = 1 + random.nextInt(15), sz = 1 + random.nextInt(26);
            Box schematic = new Box(0, 10, 0, sx - 1, 10 + sy - 1, sz - 1);
            Direction storage = random.nextInt(3) == 0 ? null : Direction.Plane.HORIZONTAL.getRandomDirection(net.minecraft.util.RandomSource.create(run));
            Room room = Room.of(schematic, random.nextInt(5), random.nextInt(4), storage, 2 + random.nextInt(7), 2 + random.nextInt(4));
            double angle = random.nextDouble() * Math.PI * 2, distance = 8 + random.nextDouble() * 40;
            BlockPos start = new BlockPos((int) (room.main.centerX() + Math.cos(angle) * distance), GROUND + 1,
                    (int) (room.main.centerZ() + Math.sin(angle) * distance));
            int tools = random.nextInt(3);
            ToolShape area = tools == 1 ? null : ToolShape.area(1);
            boolean single = tools != 2;
            String what = "run " + run + ": room " + room.main + (storage == null ? "" : " storage " + storage) + ", from " + start.toShortString()
                    + (area == null ? ", pickaxe" : single ? ", both pickaxes" : ", area pickaxe");
            try {
                Sim sim = new Sim(new GridSite(GROUND), room, start, area, single, run);
                sim.run();
                sim.assertDug();
            } catch (AssertionError e) {
                throw new AssertionError(what + ": " + e.getMessage(), e);
            }
        }
    }

    @Test
    void copesWithCavesAndGravel() {
        Random random = new Random(424242);
        List<String> failures = new ArrayList<>();
        int runs = 60;
        for (int run = 0; run < runs; run++) {
            int sx = 3 + random.nextInt(13), sy = 2 + random.nextInt(12), sz = 3 + random.nextInt(20);
            Box schematic = new Box(0, 10, 0, sx - 1, 10 + sy - 1, sz - 1);
            Room room = Room.of(schematic, 1 + random.nextInt(4), random.nextInt(3), random.nextBoolean() ? Direction.SOUTH : null, 5, 3);
            GridSite site = new GridSite(GROUND);
            for (int i = 0; i < 4; i++) {
                int cx = room.bounds.minX() - 12 + random.nextInt(room.bounds.sizeX() + 24);
                int cy = room.floorY - 2 + random.nextInt(GROUND - room.floorY);
                int cz = room.bounds.minZ() - 12 + random.nextInt(room.bounds.sizeZ() + 24);
                GridSite.Block fill = random.nextInt(3) == 0 ? GridSite.Block.GRAVEL : GridSite.Block.AIR;
                site.fill(new Box(cx, cy, cz, cx + 1 + random.nextInt(5), cy + 1 + random.nextInt(4), cz + 1 + random.nextInt(5)), fill);
            }
            double angle = random.nextDouble() * Math.PI * 2, distance = 8 + random.nextDouble() * 30;
            BlockPos start = new BlockPos((int) (room.main.centerX() + Math.cos(angle) * distance), GROUND + 1,
                    (int) (room.main.centerZ() + Math.sin(angle) * distance));
            site.set(start, GridSite.Block.AIR);
            site.set(start.above(), GridSite.Block.AIR);
            site.set(start.below(), GridSite.Block.STONE);
            int tools = random.nextInt(3);
            ToolShape area = tools == 1 ? null : ToolShape.area(1);
            boolean single = tools != 2;
            Set<BlockPos> holes = new HashSet<>();
            Box b = room.bounds;
            for (int x = b.minX(); x <= b.maxX(); x++) for (int z = b.minZ(); z <= b.maxZ(); z++) {
                BlockPos floor = new BlockPos(x, room.floorY - 1, z);
                if (!site.is(floor, Site.SOLID)) holes.add(floor);
            }
            String what = "run " + run + ": room " + room.main + ", from " + start.toShortString()
                    + (area == null ? ", pickaxe" : single ? ", both pickaxes" : ", area pickaxe");
            Sim sim = new Sim(site, room, start, area, single, run);
            sim.holes.addAll(holes);
            try {
                sim.run();
                sim.assertDug();
            } catch (AssertionError e) {
                String message = String.valueOf(e.getMessage());
                // Giving up with a reason is allowed in a rough world; breaking a rule is not.
                if (!message.startsWith("failed at") && !message.contains("blocks left in the room")) throw new AssertionError(what + ": " + message, e);
                failures.add(what + ": " + message);
            }
        }
        System.out.println("Gave up on " + failures.size() + " of " + runs + " rough worlds");
        failures.forEach(f -> System.out.println("  " + f));
        assertTrue(failures.size() <= runs / 10, failures.size() + " of " + runs + " gave up: " + String.join("; ", failures));
    }

    @Test
    void picksItselfUpAfterFallingIntoHiddenCaves() {
        for (ToolShape area : new ToolShape[] {null, ToolShape.area(1)}) {
            GridSite site = new GridSite(GROUND);
            Room room = room();
            BlockPos start = new BlockPos(24, GROUND + 1, 4);
            Sim sim = new Sim(site, room, start, area, area == null, 21);
            // A cave right under a step of the way in, and another under the top band's floor; their floors give way when
            // stood on, dropping the player two or three blocks.
            BlockPos step = sim.dig.approach().get(sim.dig.approach().size() / 2);
            site.fill(new Box(step.getX() - 1, step.getY() - 4, step.getZ() - 1, step.getX() + 1, step.getY() - 2, step.getZ() + 1), GridSite.Block.AIR);
            sim.traps.add(step.below());
            int top = sim.dig.tiers().getLast()[0];
            site.fill(new Box(1, top - 3, 3, 5, top - 2, 8), GridSite.Block.AIR);
            for (int x = 2; x <= 4; x++) for (int z = 5; z <= 6; z++) sim.traps.add(new BlockPos(x, top - 1, z));
            sim.run();
            sim.assertDug();
            assertTrue(sim.falls >= 1, "the floor never gave way");
        }
    }

    @Test
    void climbsBackWhenTheFloorGivesWayIntoACave() {
        for (ToolShape area : new ToolShape[] {null, ToolShape.area(1)}) {
            GridSite site = new GridSite(GROUND);
            Room room = room();
            Sim sim = new Sim(site, room, new BlockPos(24, GROUND + 1, 4), area, area == null, 23);
            // A cave under the middle of the floor, with gravel for a floor that holds until stood on.
            site.fill(new Box(1, room.floorY - 4, 3, 5, room.floorY - 2, 6), GridSite.Block.AIR);
            for (int x = 2; x <= 4; x++) for (int z = 4; z <= 5; z++) sim.traps.add(new BlockPos(x, room.floorY - 1, z));
            sim.run();
            sim.assertDug();
            assertTrue(sim.falls >= 1, "the floor never gave way");
        }
    }

    @Test
    void startsOnHillyGroundAndDigsThroughGravel() {
        Random random = new Random(77);
        for (int run = 0; run < 12; run++) {
            long seed = random.nextLong();
            // Rolling hills a few blocks up and down, with gravel in pockets everywhere.
            GridSite site = new GridSite((x, z) -> GROUND + (int) Math.round(3 * Math.sin(x * 0.31 + seed % 7) + 2 * Math.cos(z * 0.23 + seed % 5)));
            Random pockets = new Random(seed);
            for (int i = 0; i < 25; i++) {
                int x = -15 + pockets.nextInt(50), y = 12 + pockets.nextInt(GROUND - 10), z = -15 + pockets.nextInt(45);
                site.fill(new Box(x, y, z, x + pockets.nextInt(3), y + pockets.nextInt(3), z + pockets.nextInt(3)), GridSite.Block.GRAVEL);
            }
            int sx = 18 + pockets.nextInt(12), sz = -10 + pockets.nextInt(30);
            BlockPos start = new BlockPos(sx, site.ground(sx, sz) + 1, sz);
            site.set(start.below(), GridSite.Block.STONE);
            ToolShape area = run % 3 == 0 ? null : ToolShape.area(1);
            Sim sim = new Sim(site, room(), start, area, run % 3 != 2, run);
            try {
                sim.run();
                sim.assertDug();
            } catch (AssertionError e) {
                throw new AssertionError("run " + run + " from " + start.toShortString() + ": " + e.getMessage(), e);
            }
        }
    }

    @Test
    void digsBigFlatAndTallRooms() {
        // Builds as players bring them, not only small test boxes: a wide farm, a long flat base, a tall narrow tower.
        Box[] schematics = {new Box(0, -20, 0, 30, -9, 30), new Box(0, -10, 0, 47, -5, 19), new Box(0, -30, 0, 8, 17, 8)};
        for (Box schematic : schematics) {
            for (ToolShape area : new ToolShape[] {null, ToolShape.area(1)}) {
                Room room = Room.of(schematic, 3, 2, Direction.SOUTH, 6, 4);
                BlockPos start = new BlockPos(room.main.maxX() + 20, GROUND + 1, (int) room.main.centerZ());
                Sim sim = new Sim(new GridSite(GROUND), room, start, area, area == null, schematic.sizeX());
                sim.limit = (int) (room.volume() * 2) + 40_000;
                String what = "room " + room.main + (area == null ? ", pickaxe" : ", area pickaxe");
                try {
                    sim.run();
                    sim.assertDug();
                } catch (AssertionError e) {
                    throw new AssertionError(what + ": " + e.getMessage(), e);
                }
                System.out.printf("%s: %d actions, planner %.2f ms a step on average, %.1f ms at most%n", what, sim.actions,
                        sim.planned / 1e6 / Math.max(1, sim.actions), sim.slowest / 1e6);
            }
        }
    }

    /** A player carrying out the excavator's actions. */
    private static final class Sim {
        final GridSite site;
        final Room room;
        final Excavator dig;
        final ToolShape area;
        final boolean single;
        final Random random;
        final List<BlockPos> approach;
        final Set<BlockPos> walked = new HashSet<>();
        /** Floor blocks that were already missing, such as a cave under the room. */
        final Set<BlockPos> holes = new HashSet<>();
        /** Blocks that give way when stood on, dropping the player into what is under them. */
        final Set<BlockPos> traps = new HashSet<>();
        int falls, towers;
        final java.util.ArrayDeque<String> recent = new java.util.ArrayDeque<>();
        /** Every staircase planned: one climbing back up from a cave under the room comes through its floor. */
        final Set<BlockPos> stairs = new HashSet<>();
        BlockPos feet;
        int areaSwings, actions, limit = 40_000;
        /** Nanoseconds the excavator spent choosing, in all and at most for one action. */
        long planned, slowest;

        Sim(GridSite site, Room room, BlockPos start, ToolShape area, boolean single, long seed) {
            this.site = site;
            this.room = room;
            this.area = area;
            this.single = single;
            this.random = new Random(seed);
            this.feet = start;
            this.dig = new Excavator(room, start, area != null, site);
            this.approach = new ArrayList<>(dig.approach());
            walked.add(start);
        }

        Swings.Reach reach() {
            Vec3 eye = new Vec3(feet.getX() + 0.5, feet.getY() + 1.62, feet.getZ() + 0.5);
            AABB body = new AABB(feet.getX() + 0.2, feet.getY(), feet.getZ() + 0.2, feet.getX() + 0.8, feet.getY() + 1.8, feet.getZ() + 0.8);
            return new Swings.Reach(eye, body, feet.getY(), REACH, 0.6, true, random);
        }

        void run() {
            for (; actions < limit; actions++) {
                long before = System.nanoTime();
                Excavator.Action action = dig.next(site, reach(), new Excavator.Tools(area, single));
                long took = System.nanoTime() - before;
                planned += took;
                slowest = Math.max(slowest, took);
                recent.add(feet.toShortString() + " " + dig.phase() + ": " + action);
                List<BlockPos> path = dig.path();
                for (int i = 1; i < path.size(); i++) stairs.addAll(Staircase.clears(path.get(i - 1), path.get(i)));
                if (recent.size() > 8) recent.removeFirst();
                switch (action) {
                    case Excavator.Dig d -> swing(d.swing());
                    case Excavator.Walk w -> walk(w.cells());
                    case Excavator.Fill f -> fill(f.pos());
                    case Excavator.Tower t -> tower(t.feet());
                    case Excavator.Wait w -> fail("waited at " + feet + ": " + w.why());
                    case Excavator.Done d -> {
                        return;
                    }
                    case Excavator.Fail f -> fail("failed at " + feet.toShortString() + " in " + dig.phase() + ": " + f.why() + "; last: "
                            + String.join(" | ", recent));
                }
            }
            fail("not done after " + actions + " actions, at " + feet + " in " + dig.phase() + "; last: " + String.join(" | ", recent));
        }

        void swing(Swings.Swing swing) {
            Vec3 eye = reach().eye();
            assertTrue(site.is(swing.target(), Site.DIG), "swung at a block that isn't there: " + swing.target());
            assertTrue(eye.distanceTo(swing.aim()) <= REACH, "out of reach: " + swing);
            assertTrue(site.sees(eye, swing.aim(), swing.target(), swing.face()), "out of view: " + swing);
            float[] look = Swings.look(eye, swing.aim());
            assertEquals(look[0], swing.yaw(), 0.01);
            assertEquals(look[1], swing.pitch(), 0.01);
            if (swing.area()) {
                assertTrue(area != null, "an area swing without an area pickaxe");
                assertTrue(ToolShape.agrees(swing.face(), swing.yaw(), swing.pitch()), "the look disagrees with the face: " + swing);
                areaSwings++;
            } else {
                assertTrue(single, "a single swing without a normal pickaxe");
            }
            List<BlockPos> cells = (swing.area() ? area : ToolShape.SINGLE).cells(swing.target(), swing.face());
            for (BlockPos cell : cells) {
                if (!site.is(cell, Site.DIG)) continue;
                // Digging down, or a lone block stood on to dig it from on top: only ever a one-block drop onto solid ground.
                if (cell.equals(feet.below())) assertTrue(site.is(feet.below(2), Site.SOLID), "broke the block under the player at " + cell);
                List<BlockPos> approach = dig.approach();
                List<BlockPos> current = dig.path();
                boolean step = approach.contains(cell) || approach.contains(cell.below());
                for (int k = 1; k < current.size() && !step; k++) step = Staircase.clears(current.get(k - 1), current.get(k)).contains(cell);
                // Digging into the ground where it starts gives up the steps walked on top.
                boolean drop = swing.target().equals(feet.below());
                assertFalse(!drop && walked.contains(cell.above()) && approach.contains(cell.above()) && !room.contains(cell),
                        "broke a step of the staircase behind the player at " + cell);
                assertFalse(cell.getY() < room.floorY && room.column(cell.getX(), cell.getZ()) && !step, "broke the room's floor at " + cell);
                for (Direction side : Direction.values()) {
                    assertFalse(site.is(cell.relative(side), Site.FLUID), "let water or lava in at " + cell);
                }
                site.set(cell, GridSite.Block.AIR);
            }
            settleGravel(cells);
            dig.broke(swing.target());
            int fell = 0;
            while (!site.is(feet.below(), Site.SOLID)) {
                feet = feet.below();
                fell++;
            }
            assertTrue(fell <= 1, "fell " + fell + " blocks at " + feet);
        }

        /** Gravel over air falls until it lands; on the player it would hurt. Only gravel over a broken block can fall. */
        void settleGravel(List<BlockPos> broken) {
            int top = room.bounds.maxY() + 4;
            for (BlockPos cell : broken) {
                boolean moved = true;
                while (moved) {
                    moved = false;
                    for (int y = cell.getY() + 1; y <= top; y++) {
                        BlockPos pos = new BlockPos(cell.getX(), y, cell.getZ());
                        if (site.get(pos) != GridSite.Block.GRAVEL || site.get(pos.below()) != GridSite.Block.AIR) continue;
                        assertFalse(pos.below().equals(feet.above()) || pos.below().equals(feet.above(2)), "gravel fell on the player at " + pos);
                        site.set(pos, GridSite.Block.AIR);
                        site.set(pos.below(), GridSite.Block.GRAVEL);
                        moved = true;
                    }
                }
            }
        }

        void walk(List<BlockPos> cells) {
            assertFalse(cells.isEmpty(), "an empty walk");
            for (BlockPos cell : cells) {
                int across = Math.abs(cell.getX() - feet.getX()) + Math.abs(cell.getZ() - feet.getZ());
                assertEquals(1, across, "walked " + across + " blocks over at once, from " + feet + " to " + cell);
                assertTrue(Math.abs(cell.getY() - feet.getY()) <= 1, "a step of more than a block, from " + feet + " to " + cell);
                assertTrue(Excavator.standable(site, cell), "walked to a place with no room or no floor: " + cell);
                if (cell.getY() > feet.getY()) assertTrue(site.is(feet.above(2), Site.CLEAR), "jumped into a ceiling at " + feet);
                if (cell.getY() < feet.getY()) assertTrue(site.is(cell.above(2), Site.CLEAR), "walked off a step into rock at " + cell);
                feet = cell;
                walked.add(cell);
                if (traps.remove(feet.below())) {
                    site.set(feet.below(), GridSite.Block.AIR);
                    holes.add(feet.below());
                    int drop = 0;
                    while (!site.is(feet.below(), Site.SOLID)) {
                        feet = feet.below();
                        drop++;
                    }
                    assertTrue(drop <= 4, "a trap dropped the player " + drop + " blocks");
                    falls++;
                    return;
                }
            }
        }

        /** Jumps and puts a block where the feet were. */
        void tower(BlockPos at) {
            assertEquals(feet, at, "towered from somewhere else");
            assertTrue(site.is(feet.above(2), Site.CLEAR), "towered into a ceiling at " + feet);
            assertEquals(GridSite.Block.AIR, site.get(feet));
            site.set(feet, GridSite.Block.STONE);
            feet = feet.above();
            towers++;
        }

        void fill(BlockPos pos) {
            assertEquals(GridSite.Block.AIR, site.get(pos), "filled a spot that isn't empty: " + pos);
            assertTrue(Vec3.atCenterOf(pos).distanceTo(reach().eye()) <= REACH + 1, "filled out of reach: " + pos);
            site.set(pos, GridSite.Block.STONE);
        }

        /** What is around a block, for a failure message. */
        String around(BlockPos pos) {
            StringBuilder b = new StringBuilder(" (");
            for (Direction d : Direction.values()) b.append(d.getName()).append('=').append(site.get(pos.relative(d))).append(' ');
            return b.append(")").toString();
        }

        /** Every block of the room is gone but those it had to leave, the floor and the staircase are whole. */
        void assertDug() {
            Box b = room.bounds;
            List<BlockPos> left = new ArrayList<>();
            for (int x = b.minX(); x <= b.maxX(); x++) {
                for (int z = b.minZ(); z <= b.maxZ(); z++) {
                    if (!room.column(x, z)) continue;
                    for (int y = b.minY(); y <= b.maxY(); y++) {
                        BlockPos pos = new BlockPos(x, y, z);
                        if (!room.contains(pos) || !site.is(pos, Site.DIG)) continue;
                        if (!dig.wet().contains(pos) && !dig.skipped().contains(pos)) left.add(pos);
                    }
                    BlockPos floor = new BlockPos(x, room.floorY - 1, z);
                    assertTrue(holes.contains(floor) || stairs.contains(floor) || site.is(floor, Site.SOLID), "the floor is gone at " + floor);
                }
            }
            // Out of reach is only ever beside a block holding back water or lava.
            left.removeIf(pos -> dig.unreached().contains(pos) && dig.wet().stream().anyMatch(w -> w.distManhattan(pos) <= 3));
            assertTrue(left.isEmpty(), left.size() + " blocks left in the room, such as " + (left.isEmpty() ? "" : left.getFirst() + around(left.getFirst()))
                    + "; ended at " + feet.toShortString() + ", last: " + String.join(" | ", recent));
            for (BlockPos step : dig.approach()) {
                if (room.contains(step)) continue;
                assertTrue(Excavator.standable(site, step), "the staircase is broken at " + step);
            }
            assertEquals(room.floorY, feet.getY(), "ended away from the floor");
        }
    }
}
