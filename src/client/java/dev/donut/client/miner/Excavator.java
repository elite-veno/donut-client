package dev.donut.client.miner;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/**
 * Plans the dig, one action at a time. A staircase runs from where the player stands to the room's floor, so the way
 * in and out is at floor level once the room is dug; a winding staircase inside the room climbs to its top band; then
 * the room is dug band by band from the top down, standing on the band below each, and the player digs down through
 * the block under their feet, a block at a time, to the next band.
 *
 * <p>The player's own floor, the staircase's floors and the room's floor are never broken, nor is a block that would let
 * water or lava in. It only reads the world through {@link Site}, so it plans the same in the game and in tests; the
 * caller carries each action out and asks again.
 */
public final class Excavator {
    public enum Phase { APPROACH, CLIMB, TOWER, TIER, DROP, DONE }

    public sealed interface Action permits Dig, Walk, Fill, Tower, Wait, Done, Fail { }

    public record Dig(Swings.Swing swing) implements Action { }

    /** Walk through these blocks (feet positions) in turn. */
    public record Walk(List<BlockPos> cells) implements Action { }

    /** Put a block here: the next step has nothing to stand on. */
    public record Fill(BlockPos pos) implements Action { }

    /** Jump and put a block under the feet, standing here: one block up a hole the player fell down. */
    public record Tower(BlockPos feet) implements Action { }

    public record Wait(String why) implements Action { }

    public record Done(String summary) implements Action { }

    public record Fail(String why) implements Action { }

    /** The tools at hand: an area pickaxe's shape or null, and whether there is a normal pickaxe. */
    public record Tools(ToolShape area, boolean single) {
        public boolean any() { return area != null || single; }
    }

    /** Tries at one block before it is left out: the server may keep putting it back. */
    static final int MAX_TRIES = 4;

    private final Room room;
    private final List<int[]> tiers;
    private final boolean landings;
    private Phase phase;
    private int tier;
    private List<BlockPos> path = List.of();
    private Set<BlockPos> pathCells = Set.of(), pathFloors = Set.of();
    /** Every block the staircase being dug takes away, its steps' head room included. */
    private Set<BlockPos> pathClears = Set.of();
    /** The highest path index stood on. */
    private int reached;
    /** The way in from outside, kept whole: its floors outside the room. */
    private List<BlockPos> approach;
    /** Times the way in was planned again from lower down, after digging into the ground where it started. */
    private int restarts;
    /** Times the plan started over from where the player ended up: after a fall, or water or lava ahead. */
    private int replans;
    static final int MAX_REPLANS = 24;
    /** Water and lava met on the way, which a new way in keeps clear of. */
    private final Set<BlockPos> avoid = new HashSet<>();
    /** Places a climb started from and got stuck. */
    private final Set<BlockPos> triedClimbs = new HashSet<>();
    private int restartFrom = Integer.MIN_VALUE;
    private final Set<BlockPos> tunnelFloors = new HashSet<>();
    private final Set<BlockPos> skipped = new HashSet<>(), wet = new HashSet<>(), unreached = new HashSet<>();
    private final Map<BlockPos, Integer> tries = new HashMap<>();
    /** Places stood on from which nothing could be dug, until something near them breaks. */
    private final Set<BlockPos> triedSpots = new HashSet<>();
    /** Where {@link #countLeft} is in its pass over the room's columns, and what it has counted so far. */
    private int countAt, counted;

    /** Plans the way in from {@code start}, past the water and lava {@code site} shows; null knows nothing of them. */
    public Excavator(Room room, BlockPos start, boolean area, Site site) {
        this.room = room;
        this.landings = area;
        this.tiers = room.tiers(area ? 3 : 4);
        this.tier = site == null ? tiers.size() - 1 : highestLeft(site);
        planApproach(site, start);
    }

    /**
     * The highest band with something left to dig. A room dug partly already, such as one started again after a stop,
     * is entered at the band where the digging got to, not climbed to the top through what is already gone.
     */
    private int highestLeft(Site site) {
        Box b = room.bounds;
        for (int t = tiers.size() - 1; t > 0; t--) {
            int[] band = tiers.get(t);
            for (int x = b.minX(); x <= b.maxX(); x++) {
                for (int z = b.minZ(); z <= b.maxZ(); z++) {
                    for (int y = band[0]; y <= band[1]; y++) {
                        // A block not loaded yet may still be there.
                        if (room.contains(x, y, z) && (site.flags(x, y, z) & (Site.DIG | Site.UNLOADED)) != 0) return t;
                    }
                }
            }
        }
        return 0;
    }

    private void planApproach(Site site, BlockPos start) {
        if (room.contains(start)) {
            // Already inside: wind straight up (or down) to the top band.
            approach = List.of(start);
        } else {
            // The way in from each side of the room; the one with the least water, lava and digging on it.
            Direction facing = Room.facing(room.main, start);
            List<BlockPos> best = null;
            double bestCost = Double.MAX_VALUE;
            for (Direction side : new Direction[] {facing, facing.getClockWise(), facing.getCounterClockWise(), facing.getOpposite()}) {
                List<BlockPos> way = wayIn(start, side);
                double cost = site == null ? way.size() : cost(site, way) + (side == facing ? 0 : 6);
                if (cost < bestCost) {
                    best = way;
                    bestCost = cost;
                }
            }
            approach = best;
        }
        tunnelFloors.clear();
        for (BlockPos cell : approach) if (!room.contains(cell.below()) && !room.contains(cell)) tunnelFloors.add(cell.below());
        if (approach.size() > 1) {
            setPath(approach);
            phase = Phase.APPROACH;
        } else {
            startClimb(site, start);
        }
    }

    /**
     * Feet positions from {@code start} into the room: a staircase down (or up) to the floor's height just outside the
     * room, so no step stands on a block the room later loses, then level into the main room, across a storage room on
     * that side, to a floor block in the middle of a side, far enough from the corners for the climb to wind inside.
     */
    private List<BlockPos> wayIn(BlockPos start, Direction side) {
        int r = landings ? 2 : 1;
        Box m = room.main;
        int x = side == Direction.WEST ? m.minX() : side == Direction.EAST ? m.maxX() : clampInside(start.getX(), m.minX(), m.maxX(), r);
        int z = side == Direction.NORTH ? m.minZ() : side == Direction.SOUTH ? m.maxZ() : clampInside(start.getZ(), m.minZ(), m.maxZ(), r);
        BlockPos entry = new BlockPos(x, room.floorY, z);
        BlockPos outside = entry.relative(side);
        while (room.column(outside.getX(), outside.getZ())) outside = outside.relative(side);
        // Starting over the room, or close beside it, it first walks clear of it, so the winding down is all in rock.
        Box clear = new Box(room.bounds.minX() - r - 2, room.bounds.minY(), room.bounds.minZ() - r - 2,
                room.bounds.maxX() + r + 2, room.bounds.maxY(), room.bounds.maxZ() + r + 2);
        BlockPos from = start;
        while (clear.column(from.getX(), from.getZ())) from = from.relative(side);
        List<BlockPos> path = new ArrayList<>(Staircase.approach(start, from.getX(), from.getZ(), start.getY(), landings));
        List<BlockPos> down = Staircase.approach(from, outside.getX(), outside.getZ(), room.floorY, landings);
        path.addAll(down.subList(1, down.size()));
        for (BlockPos at = path.getLast(); !at.equals(entry); ) {
            at = at.relative(side.getOpposite());
            path.add(at);
        }
        return path;
    }

    /** What a way in costs: a block to dig each, more for a gap to fill, a great deal for water or lava on or beside it. */
    private double cost(Site site, List<BlockPos> way) {
        double cost = 0;
        // A way through the room itself would lose its steps when the room is dug.
        int lastOutside = way.size() - 1;
        while (lastOutside > 0 && room.column(way.get(lastOutside).getX(), way.get(lastOutside).getZ())) lastOutside--;
        for (int i = 0; i < lastOutside; i++) if (room.column(way.get(i).getX(), way.get(i).getZ())) cost += 100_000;
        for (int i = 1; i < way.size(); i++) {
            BlockPos floor = way.get(i).below();
            int under = site.flags(floor);
            if ((under & Site.FLUID) != 0) cost += 1000;
            else if ((under & (Site.SOLID | Site.UNLOADED)) == 0) cost += 3;
            for (BlockPos cell : Staircase.clears(way.get(i - 1), way.get(i))) {
                int flags = site.flags(cell);
                if ((flags & Site.FLUID) != 0) cost += 1000;
                if ((flags & Site.DIG) != 0) {
                    cost += 1;
                    for (Direction d : Direction.values()) if (site.is(cell.relative(d), Site.FLUID)) cost += 500;
                }
                for (BlockPos bad : avoid) if (bad.distManhattan(cell) <= 3) cost += 2000;
            }
        }
        return cost;
    }

    private static int clampInside(int value, int min, int max, int margin) {
        return min + margin > max - margin ? (min + max) / 2 : Math.clamp(value, min + margin, max - margin);
    }

    private void startClimb(Site site, BlockPos from) {
        int top = tiers.get(tier)[0];
        if (from.getY() == top) {
            phase = Phase.TIER;
            path = List.of(from);
            return;
        }
        if (site != null && from.getY() < top && openAbove(site, from, top)) {
            // Down a hole, such as through a floor that gave way: back up it the way a player would, a block at a time.
            phase = Phase.TOWER;
            path = List.of(from);
            return;
        }
        setPath(Staircase.spiral(from, top, landings, windingInside(from)));
        phase = Phase.CLIMB;
    }

    /** Whether nothing stands over {@code feet} up to a player's height above {@code top}. */
    private static boolean openAbove(Site site, BlockPos feet, int top) {
        for (int y = feet.getY() + 1; y <= top + 1; y++) if (!site.is(new BlockPos(feet.getX(), y, feet.getZ()), Site.CLEAR)) return false;
        return true;
    }

    /** Up the open column, a block under the feet at a time, to the band's bottom. */
    private Action towerStep(Site site, Swings.Reach at) {
        int top = tiers.get(tier)[0];
        BlockPos feet = feet(at);
        if (feet.getY() >= top) {
            phase = Phase.TIER;
            triedSpots.clear();
            return null;
        }
        if (site.is(feet.below(), Site.CLEAR)) return new Wait("landing");
        if (!openAbove(site, feet, top)) {
            // Something fell in over the head: wind up instead.
            setPath(Staircase.spiral(feet, top, landings, windingInside(feet)));
            phase = Phase.CLIMB;
            return null;
        }
        return new Tower(feet);
    }

    /** The way to wind from {@code start} that keeps the winding inside the room, else the way toward its middle. */
    private Staircase.Winding windingInside(BlockPos start) {
        for (Staircase.Winding winding : Staircase.Winding.ALL) if (inside(start, winding)) return winding;
        double dx = room.main.centerX() - (start.getX() + 0.5), dz = room.main.centerZ() - (start.getZ() + 0.5);
        if (Math.abs(dx) >= Math.abs(dz)) return new Staircase.Winding(dx >= 0 ? 1 : -1, 0);
        return new Staircase.Winding(0, dz >= 0 ? 1 : -1);
    }

    private boolean inside(BlockPos start, Staircase.Winding winding) {
        for (int[] column : Staircase.columns(start, landings, winding)) if (!room.main.column(column[0], column[1])) return false;
        return true;
    }

    public Room room() { return room; }

    public Phase phase() { return phase; }

    public List<int[]> tiers() { return tiers; }

    /** The band being dug, counted from the top, from 1; 0 before the room's top is reached. */
    public int tierNumber() { return phase == Phase.APPROACH || phase == Phase.CLIMB || phase == Phase.TOWER ? 0 : tiers.size() - tier; }

    /** Steps of the staircase being dug that are still ahead. */
    public int stepsLeft() { return phase == Phase.APPROACH || phase == Phase.CLIMB ? Math.max(0, path.size() - 1 - reached) : 0; }

    /** The way in, from where the player started to the room's floor. */
    public List<BlockPos> approach() { return approach; }

    /** The staircase being dug now. */
    public List<BlockPos> path() { return path; }

    public Set<BlockPos> skipped() { return skipped; }

    public Set<BlockPos> wet() { return wet; }

    /** Blocks no place in the room could reach safely, once it is done: mostly beside those left for water or lava. */
    public Set<BlockPos> unreached() { return unreached; }

    /** Blocks of the room still to dig. */
    public int left(Site site) {
        int left = 0;
        Box b = room.bounds;
        for (int x = b.minX(); x <= b.maxX(); x++) {
            for (int z = b.minZ(); z <= b.maxZ(); z++) {
                if (!room.column(x, z)) continue;
                for (int y = b.minY(); y <= b.maxY(); y++) {
                    if (room.contains(x, y, z) && (site.flags(x, y, z) & Site.DIG) != 0) left++;
                }
            }
        }
        return left;
    }

    /**
     * {@link #left} a share of the room's columns at a time, so counting a big room doesn't hold up a tick: the count
     * once a pass over every column ends, else -1.
     */
    public int countLeft(Site site, int columns) {
        Box b = room.bounds;
        for (int i = 0; i < columns; i++) {
            int x = b.minX() + countAt / b.sizeZ(), z = b.minZ() + countAt % b.sizeZ();
            if (room.column(x, z)) {
                for (int y = b.minY(); y <= b.maxY(); y++) {
                    if (room.contains(x, y, z) && (site.flags(x, y, z) & Site.DIG) != 0) counted++;
                }
            }
            if (++countAt == b.sizeX() * b.sizeZ()) {
                int left = counted;
                countAt = 0;
                counted = 0;
                return left;
            }
        }
        return -1;
    }

    /** A block too slow to break with the tools at hand: it is left. */
    public void skip(BlockPos pos) { skipped.add(pos.immutable()); }

    /** A walk to this place got stuck: somewhere else is tried next. */
    public void stuck(BlockPos at) { triedSpots.add(at.immutable()); }

    /** A swing at this block didn't break it; after a few it is left out. */
    public void missed(BlockPos pos) {
        if (tries.merge(pos.immutable(), 1, Integer::sum) >= MAX_TRIES) skipped.add(pos.immutable());
    }

    /** Something broke here: places near it may have work again. */
    public void broke(BlockPos pos) {
        tries.remove(pos);
        triedSpots.removeIf(spot -> spot.distManhattan(pos) <= 8);
    }

    /** What to do next. */
    public Action next(Site site, Swings.Reach at, Tools tools) {
        if (!tools.any()) return new Fail("no pickaxe to dig with");
        for (int guard = 0; guard < 6; guard++) {
            Action action = switch (phase) {
                case APPROACH, CLIMB -> pathStep(site, at, tools);
                case TOWER -> towerStep(site, at);
                case TIER -> tierStep(site, at, tools);
                case DROP -> dropStep(site, at, tools);
                case DONE -> new Done(summary());
            };
            if (action != null) return action;
        }
        return new Wait("working out the next step");
    }

    // Staircases ---------------------------------------------------------------------------------------------------

    private void setPath(List<BlockPos> cells) {
        path = List.copyOf(cells);
        pathCells = new HashSet<>(cells);
        Set<BlockPos> floors = new HashSet<>();
        for (BlockPos cell : cells) floors.add(cell.below());
        pathFloors = floors;
        Set<BlockPos> clears = new HashSet<>(cells);
        for (int i = 1; i < cells.size(); i++) clears.addAll(Staircase.clears(cells.get(i - 1), cells.get(i)));
        pathClears = clears;
        reached = 0;
    }

    private Action pathStep(Site site, Swings.Reach at, Tools tools) {
        BlockPos feet = feet(at);
        if (restartFrom != Integer.MIN_VALUE) {
            if (site.is(feet.below(), Site.CLEAR)) return new Wait("dropping");
            boolean dropped = feet.getY() <= restartFrom;
            restartFrom = Integer.MIN_VALUE;
            if (dropped) {
                planApproach(site, feet);
                return null;
            }
        }
        int index = -1;
        // Nearest the last step reached first: a winding staircase passes over the same column again further down.
        for (int d : new int[] {0, 1, 2, 3, -1, -2}) {
            int i = reached + d;
            if (i >= 0 && i < path.size() && path.get(i).equals(feet)) { index = i; break; }
        }
        if (index < 0) {
            BlockPos expected = path.get(reached);
            if (site.is(feet.below(), Site.CLEAR)) return new Wait("falling");
            if (expected.distManhattan(feet) <= 2 && Math.abs(expected.getY() - feet.getY()) <= 1 && standable(site, expected)) {
                return new Walk(List.of(expected));
            }
            // Fell off it, or slid down with gravel: plan again from where the player is now.
            return replan(site, feet, "you left the staircase at " + str(expected));
        }
        if (index > reached) reached = index;
        if (reached >= path.size() - 1) {
            if (phase == Phase.APPROACH) {
                startClimb(site, path.getLast());
            } else {
                phase = Phase.TIER;
                triedSpots.clear();
            }
            return null;
        }
        BlockPos here = path.get(reached), next = path.get(reached + 1);
        List<BlockPos> need = new ArrayList<>(Staircase.clears(here, next));
        // A step or two behind the furthest reached, the way back up to it is walked too: sand or gravel come down into it
        // since, as when the block over its head was dug from a step back, is dug again first.
        for (int k = index; k < reached; k++) need.addAll(Staircase.clears(path.get(k), path.get(k + 1)));
        // An area pickaxe digs a level step and the climb or drop after it from the same place: see Staircase.
        if (landings && next.getY() == here.getY() && reached + 2 < path.size() && path.get(reached + 2).getY() != next.getY()) {
            need.addAll(Staircase.clears(next, path.get(reached + 2)));
        }
        List<BlockPos> dig = new ArrayList<>();
        for (BlockPos cell : need) {
            int flags = site.flags(cell);
            if ((flags & Site.CLEAR) != 0) continue;
            if ((flags & Site.UNLOADED) != 0) return new Wait("waiting for the blocks ahead to load");
            if ((flags & Site.FLUID) != 0) {
                avoid.add(cell);
                String why = ((flags & Site.LAVA) != 0 ? "lava" : "water") + " in the way at " + str(cell);
                // Climbing in the room, a new climb from the same place winds the same way, into the same water: from
                // somewhere else.
                return phase == Phase.CLIMB ? climbElsewhere(site, feet, why) : replan(site, feet, why);
            }
            if ((flags & Site.HARD) != 0) {
                // A chest, spawner or bedrock in the way: go round it.
                avoid.add(cell);
                String why = "a block it won't break is in the way at " + str(cell);
                return phase == Phase.CLIMB ? climbElsewhere(site, feet, why) : replan(site, feet, why);
            }
            if ((flags & Site.DIG) != 0) dig.add(cell);
        }
        if (!dig.isEmpty()) {
            Set<BlockPos> blocked = new HashSet<>();
            Swings.Swing swing = choose(site, at, tools, dig, this::guardedOnPath, cell -> true, blocked);
            if (swing != null) return new Dig(swing);
            if (index == reached && reached > 0) {
                // One block back along the same line gives an area pickaxe the level look it needs.
                BlockPos back = path.get(reached - 1);
                boolean inLine = here.getX() - back.getX() == next.getX() - here.getX() && here.getZ() - back.getZ() == next.getZ() - here.getZ();
                // A normal pickaxe steps back for anything, such as sand or gravel it must not dig over its own head.
                boolean useful = landings ? inLine && back.getY() == here.getY() : true;
                if (useful && standable(site, back)) return new Walk(List.of(back));
            }
            // In the open, such as on the ground where it starts, an area pickaxe can't spare the floor of the step
            // ahead; it takes it, and the gap is filled when the step comes.
            int stood = index;
            swing = choose(site, at, tools, dig, cell -> guardedBehind(cell, feet, stood), cell -> true, blocked);
            if (swing != null) return new Dig(swing);
            if (!blocked.isEmpty()) {
                avoid.addAll(blocked);
                String why = "water or lava is right behind the way at " + str(blocked.iterator().next());
                return phase == Phase.CLIMB ? climbElsewhere(site, feet, why) : replan(site, feet, why);
            }
            // On open or broken ground, such as a hillside, an area pickaxe can't always go on without taking the block
            // underfoot: it digs into the ground, drops into the pit, and plans the way in again from there, with rock
            // around.
            if (phase == Phase.APPROACH && restarts < 6 && safeDrop(site, feet, false)) {
                int level = feet.getY();
                swing = choose(site, at, tools, Set.of(feet.below()), cell -> cell.getY() < level - 1 || room.belowFloor(cell),
                        cell -> true, blocked);
                if (swing != null) {
                    restarts++;
                    restartFrom = level - 1;
                    return new Dig(swing);
                }
            }
            if (phase == Phase.CLIMB) return climbElsewhere(site, feet, "can't dig the way up at " + str(dig.getFirst()));
            return new Fail("can't dig the way at " + str(dig.getFirst()) + " from here");
        }
        BlockPos floor = next.below();
        int under = site.flags(floor);
        if ((under & Site.UNLOADED) != 0) return new Wait("waiting for the blocks ahead to load");
        if ((under & Site.SOLID) == 0) {
            if ((under & Site.FLUID) != 0) {
                avoid.add(floor);
                return replan(site, feet, "water or lava under the way at " + str(floor));
            }
            return new Fill(floor);
        }
        return new Walk(index < reached ? List.of(here, next) : List.of(next));
    }

    /**
     * Starts over from where the player is: a new way in from outside the room, keeping clear of the water and lava met so
     * far, or a new climb from inside it back up to the band being dug. Null to carry on; a failure once it has started
     * over too often.
     */
    private Action replan(Site site, BlockPos feet, String why) {
        if (++replans > MAX_REPLANS) return new Fail(why);
        // In the room, or under it after its floor gave way into a cave: climb from here; else come in again.
        if (room.contains(feet) || room.column(feet.getX(), feet.getZ()) && feet.getY() < room.floorY) startClimb(site, feet);
        else planApproach(site, feet);
        return null;
    }

    /**
     * A climb that can't go on, such as where a cave cuts through its winding: walk over this level to another place a
     * winding fits and climb from there instead.
     */
    private Action climbElsewhere(Site site, BlockPos feet, String why) {
        if (openAbove(site, feet, tiers.get(tier)[0])) {
            phase = Phase.TOWER;
            path = List.of(feet);
            return null;
        }
        triedClimbs.add(path.getFirst());
        triedClimbs.add(feet);
        if (++replans > MAX_REPLANS) return new Fail(why);
        List<BlockPos> walk = walk(site, feet, feet.getY(), node -> !triedClimbs.contains(node) && windingFits(node));
        if (walk == null) return new Fail(why);
        BlockPos from = walk.isEmpty() ? feet : walk.getLast();
        List<BlockPos> climb = new ArrayList<>();
        climb.add(feet);
        climb.addAll(walk);
        List<BlockPos> up = Staircase.spiral(from, tiers.get(tier)[0], landings, windingInside(from));
        climb.addAll(up.subList(1, up.size()));
        setPath(climb);
        phase = Phase.CLIMB;
        return null;
    }

    private boolean windingFits(BlockPos start) {
        for (Staircase.Winding winding : Staircase.Winding.ALL) if (inside(start, winding)) return true;
        return false;
    }

    /** Whether the way in hasn't gone down or up yet: the player is still at the height they started at. */
    private boolean stillOnTop() {
        for (int i = 0; i <= reached; i++) if (path.get(i).getY() != path.getFirst().getY()) return false;
        return true;
    }

    private boolean guardedOnPath(BlockPos cell) {
        if (pathFloors.contains(cell) || tunnelFloors.contains(cell)) return true;
        return room.belowFloor(cell) && !pathClears.contains(cell) && !approach.contains(cell);
    }

    /** Only the floor under the player and the floors of the steps behind them. */
    private boolean guardedBehind(BlockPos cell, BlockPos feet, int stood) {
        if (cell.equals(feet.below())) return true;
        for (int i = 0; i <= Math.min(stood, path.size() - 1); i++) if (path.get(i).below().equals(cell)) return true;
        if (phase == Phase.CLIMB) for (BlockPos step : approach) if (step.below().equals(cell)) return true;
        return room.belowFloor(cell) && !pathClears.contains(cell) && !approach.contains(cell);
    }

    // Bands --------------------------------------------------------------------------------------------------------

    private Action tierStep(Site site, Swings.Reach at, Tools tools) {
        int bottom = tiers.get(tier)[0];
        BlockPos feet = feet(at);
        if (feet.getY() > bottom && room.contains(feet.below()) && site.is(feet.below(), Site.DIG) && site.is(feet.below(2), Site.SOLID)) {
            // Up on a block of the band, a lone one or a heap of fallen gravel: dig it out from on top and drop back down.
            int level = feet.getY();
            Swings.Swing swing = choose(site, at, tools, Set.of(feet.below()), cell -> cell.getY() < Math.max(bottom, level - 1) || guardedAlways(cell),
                    cell -> room.contains(cell), wet);
            if (swing != null) return new Dig(swing);
        }
        if (feet.getY() != bottom) {
            if (site.is(feet.below(), Site.CLEAR)) return new Wait("falling");
            // Fell through a hole or off an edge, or stands on something it can't dig: climb back to the band from
            // where the player is, or find the way in again from outside the room.
            return replan(site, feet, "you left the room's band at y " + bottom);
        }
        List<BlockPos> pending = pending(site, bottom);
        Set<BlockPos> inReach = new HashSet<>();
        double limit = at.reach() + 0.9;
        for (BlockPos cell : pending) if (Vec3.atCenterOf(cell).distanceToSqr(at.eye()) <= limit * limit) inReach.add(cell);
        if (!inReach.isEmpty()) {
            Swings.Swing swing = choose(site, at, tools, inReach, cell -> cell.getY() < bottom || guardedAlways(cell),
                    cell -> room.contains(cell) && !skipped.contains(cell), wet, bottom);
            if (swing != null) return new Dig(swing);
        }
        triedSpots.add(feet);
        List<BlockPos> walk = walkToWork(site, feet, bottom, pending, at.reach());
        if (walk != null) return new Walk(walk);
        walk = ontoLoneBlock(site, feet, bottom, pending);
        if (walk != null) return new Walk(walk);
        // Done with what it can see; part of the room still unloaded may hold more.
        if (unloaded(site, bottom)) return new Wait("waiting for the rest of the room to load");
        if (tier == 0) {
            unreached.addAll(pending);
            phase = Phase.DONE;
            return null;
        }
        phase = Phase.DROP;
        triedSpots.clear();
        return null;
    }

    /**
     * The way up onto a block of the band's lowest layer with nothing over it, to dig it from on top: an area pickaxe
     * can't take one from beside it, since its look at the top would be too flat and at a side would take the floor.
     */
    private List<BlockPos> ontoLoneBlock(Site site, BlockPos feet, int bottom, List<BlockPos> pending) {
        Set<BlockPos> lone = new HashSet<>();
        for (BlockPos cell : pending) {
            if (cell.getY() == bottom && !triedSpots.contains(cell.above()) && standable(site, cell.above())) lone.add(cell);
        }
        if (lone.isEmpty()) return null;
        List<BlockPos> way = walk(site, feet, bottom, node -> {
            for (Direction side : Direction.Plane.HORIZONTAL) {
                BlockPos block = node.relative(side);
                if (lone.contains(block) && site.is(node.above(2), Site.CLEAR)) return true;
            }
            return false;
        });
        if (way == null) return null;
        BlockPos at = way.isEmpty() ? feet : way.getLast();
        for (Direction side : Direction.Plane.HORIZONTAL) {
            BlockPos block = at.relative(side);
            if (!lone.contains(block)) continue;
            triedSpots.add(block.above());
            List<BlockPos> up = new ArrayList<>(way);
            up.add(block.above());
            return up;
        }
        return null;
    }

    /** The way back out and the room's floor. */
    private boolean guardedAlways(BlockPos cell) {
        return tunnelFloors.contains(cell) || room.belowFloor(cell) && !approach.contains(cell);
    }

    /** Digs through the block underfoot, a block at a time, down to the next band's bottom. */
    private Action dropStep(Site site, Swings.Reach at, Tools tools) {
        int target = tiers.get(tier - 1)[0];
        BlockPos feet = feet(at);
        if (feet.getY() <= target) {
            // Landing lower, through a cave under the band, the band climbs back to its bottom first.
            tier--;
            phase = Phase.TIER;
            triedSpots.clear();
            return null;
        }
        BlockPos under = feet.below();
        if (site.is(under, Site.CLEAR)) return new Wait("dropping");
        if (!safeDrop(site, feet)) {
            triedSpots.add(feet);
            List<BlockPos> walk = walk(site, feet, feet.getY(), node -> !triedSpots.contains(node) && safeDrop(site, node) && roomAround(node));
            if (walk == null) walk = walk(site, feet, feet.getY(), node -> !triedSpots.contains(node) && safeDrop(site, node));
            if (walk != null) return new Walk(walk);
            return new Fail("nowhere safe to dig down from y " + feet.getY());
        }
        if (!roomAround(feet)) {
            // The square an area pickaxe takes stays inside the room.
            List<BlockPos> walk = walk(site, feet, feet.getY(), node -> safeDrop(site, node) && roomAround(node));
            if (walk != null && !walk.isEmpty()) return new Walk(walk);
        }
        int level = feet.getY();
        Swings.Swing swing = choose(site, at, tools, Set.of(under), cell -> cell.getY() < level - 1 || guardedAlways(cell),
                cell -> room.contains(cell), wet);
        if (swing != null) return new Dig(swing);
        triedSpots.add(feet);
        List<BlockPos> walk = walk(site, feet, feet.getY(), node -> !triedSpots.contains(node) && safeDrop(site, node));
        if (walk != null) return new Walk(walk);
        return new Fail("can't dig down from " + str(feet));
    }

    private boolean safeDrop(Site site, BlockPos feet) { return safeDrop(site, feet, true); }

    /** Whether digging out the block underfoot lands the player a block lower, on solid ground, away from water and lava. */
    private boolean safeDrop(Site site, BlockPos feet, boolean inRoom) {
        BlockPos under = feet.below(), landing = feet.below(2);
        if (!site.is(under, Site.DIG) || inRoom && !room.contains(under) || !site.is(landing, Site.SOLID) || site.is(landing, Site.FLUID)) return false;
        for (Direction side : Direction.values()) if (site.is(under.relative(side), Site.FLUID)) return false;
        return true;
    }

    /** Whether the blocks all round {@code feet} are the room's. */
    private boolean roomAround(BlockPos feet) {
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) if (!room.column(feet.getX() + dx, feet.getZ() + dz)) return false;
        return true;
    }

    /** Whether any block of the room in this band or above isn't loaded yet. */
    private boolean unloaded(Site site, int bottom) {
        Box b = room.bounds;
        // A chunk is loaded or not as a whole column, so one block per column tells.
        for (int x = b.minX(); x <= b.maxX(); x++) {
            for (int z = b.minZ(); z <= b.maxZ(); z++) {
                if (room.column(x, z) && (site.flags(x, Math.max(bottom, b.minY()), z) & Site.UNLOADED) != 0) return true;
            }
        }
        return false;
    }

    /** The room's blocks still to dig in this band and above. */
    private List<BlockPos> pending(Site site, int bottom) {
        List<BlockPos> pending = new ArrayList<>();
        Box b = room.bounds;
        for (int x = b.minX(); x <= b.maxX(); x++) {
            for (int z = b.minZ(); z <= b.maxZ(); z++) {
                if (!room.column(x, z)) continue;
                for (int y = Math.max(bottom, b.minY()); y <= b.maxY(); y++) {
                    if (!room.contains(x, y, z) || (site.flags(x, y, z) & Site.DIG) == 0) continue;
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!skipped.contains(pos) && !wet.contains(pos)) pending.add(pos);
                }
            }
        }
        return pending;
    }

    /** The way to the nearest place on this band, not tried yet, with a block to dig in reach; null when there is none. */
    private List<BlockPos> walkToWork(Site site, BlockPos feet, int bottom, List<BlockPos> pending, double reach) {
        List<BlockPos> open = new ArrayList<>();
        for (BlockPos cell : pending) if (exposed(site, cell)) open.add(cell);
        if (open.isEmpty()) return null;
        double limit = reach + 0.4;
        List<BlockPos> way = walk(site, feet, bottom, node -> {
            if (triedSpots.contains(node)) return false;
            Vec3 eye = new Vec3(node.getX() + 0.5, node.getY() + 1.62, node.getZ() + 0.5);
            for (BlockPos cell : open) if (Vec3.atCenterOf(cell).distanceToSqr(eye) <= limit * limit) return true;
            return false;
        });
        return way == null || way.isEmpty() ? null : way;
    }

    /**
     * The way over this level from {@code feet} to the nearest place {@code goal} accepts, without {@code feet} itself;
     * empty when {@code feet} is one, null when none can be reached.
     */
    private List<BlockPos> walk(Site site, BlockPos feet, int y, Predicate<BlockPos> goal) {
        if (goal.test(feet)) return List.of();
        Map<BlockPos, BlockPos> from = new HashMap<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        from.put(feet, feet);
        queue.add(feet);
        while (!queue.isEmpty()) {
            BlockPos node = queue.poll();
            for (Direction side : Direction.Plane.HORIZONTAL) {
                BlockPos step = node.relative(side);
                if (from.containsKey(step) || !room.contains(step) || !standable(site, step)) continue;
                from.put(step, node);
                if (goal.test(step)) {
                    List<BlockPos> way = new ArrayList<>();
                    for (BlockPos at = step; !at.equals(feet); at = from.get(at)) way.add(at);
                    return way.reversed();
                }
                queue.add(step);
            }
        }
        return null;
    }

    // Shared -------------------------------------------------------------------------------------------------------

    private Swings.Swing choose(Site site, Swings.Reach at, Tools tools, List<BlockPos> wanted, Predicate<BlockPos> guarded,
                                Predicate<BlockPos> counts, Set<BlockPos> blocked) {
        return choose(site, at, tools, new HashSet<>(wanted), guarded, counts, blocked);
    }

    private Swings.Swing choose(Site site, Swings.Reach at, Tools tools, Set<BlockPos> wanted, Predicate<BlockPos> guarded,
                                Predicate<BlockPos> counts, Set<BlockPos> blocked) {
        return choose(site, at, tools, wanted, guarded, counts, blocked, Integer.MIN_VALUE);
    }

    private Swings.Swing choose(Site site, Swings.Reach at, Tools tools, Set<BlockPos> wanted, Predicate<BlockPos> guarded,
                                Predicate<BlockPos> counts, Set<BlockPos> blocked, int bottom) {
        if (tools.area() != null) {
            Swings.Swing swing = Swings.choose(site, at, tools.area(), wanted, guarded, counts, blocked, bottom);
            if (swing != null) return swing;
        }
        return tools.single() ? Swings.choose(site, at, ToolShape.SINGLE, wanted, guarded, counts, blocked, bottom) : null;
    }

    /** Where the player's feet are: the block their body's bottom is in. */
    public static BlockPos feet(Swings.Reach at) {
        return BlockPos.containing((at.body().minX + at.body().maxX) / 2, at.feetY(), (at.body().minZ + at.body().maxZ) / 2);
    }

    /** Whether a player fits here, on something solid, out of water and lava. */
    public static boolean standable(Site site, BlockPos feet) {
        int body = site.flags(feet), head = site.flags(feet.above()), under = site.flags(feet.below());
        return (body & Site.CLEAR) != 0 && (head & Site.CLEAR) != 0 && (under & Site.SOLID) != 0 && (under & Site.FLUID) == 0;
    }

    /** Whether a face of the block is open to the air, so it can be seen and hit. */
    static boolean exposed(Site site, BlockPos pos) {
        for (Direction side : Direction.values()) if (site.is(pos.relative(side), Site.CLEAR)) return true;
        return false;
    }

    private String summary() {
        List<String> notes = new ArrayList<>();
        if (!wet.isEmpty()) notes.add(wet.size() + (wet.size() == 1 ? " block" : " blocks") + " next to water or lava");
        if (!skipped.isEmpty()) notes.add(skipped.size() + (skipped.size() == 1 ? " block" : " blocks") + " too slow to break or put back by the server");
        if (!unreached.isEmpty()) notes.add(unreached.size() + (unreached.size() == 1 ? " block" : " blocks") + " it couldn't reach safely");
        return notes.isEmpty() ? "" : "left in place: " + String.join(", ", notes);
    }

    static String str(BlockPos pos) { return pos.getX() + " " + pos.getY() + " " + pos.getZ(); }
}
