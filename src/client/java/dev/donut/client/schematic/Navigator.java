package dev.donut.client.schematic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.LongPredicate;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Finds a way for the player to walk to a place to stand, as a player on foot gets around: walking, stepping up half
 * a block, jumping up one, dropping down at most three, and, with helper blocks in the inventory, jumping and placing
 * one underneath to climb. It never flies, wades through water rather than swimming, and keeps out of fire, lava, magma and the like.
 */
public final class Navigator {
    /** The most places to stand one search looks at. */
    static final int MAX_NODES = 40_000;
    /** How far the feet may rise in a step, in a jump, and fall without damage. */
    static final double STEP = 0.6, JUMP = 1.25, DROP = 3.2;
    /** A standing player's eye height. */
    static final double EYE = 1.62;
    private static final double BODY = 1.8;
    private static final int[][] DIRECTIONS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    public enum Move {
        START, WALK, JUMP, DROP, PILLAR, BRIDGE,
        /**
         * Out of water too deep to jump from: a helper block set on the ground beside, into the water there, and a step
         * up onto it, as a player climbs out of a pool onto a block put at its edge.
         */
        STEP,
        /**
         * Up a column of water too deep to jump from, swimming, and out onto a block beside whose top is about a block
         * over the water, pushing toward it, as a player climbs out of a pool or a farm's water channel.
         */
        SWIM;

        /** Whether the move puts down a helper block, where the node stands: a climbing step. */
        public boolean placesBlock() { return this == PILLAR || this == BRIDGE || this == STEP; }
    }

    /** A place to stand: on the block at x, y, z, with the feet at {@code feet}. */
    public record Node(int x, int y, int z, double feet, Move move) {
        long key() { return BlockPos.asLong(x, y, z); }

        public Vec3 standing() { return new Vec3(x + 0.5, feet, z + 0.5); }

        public Vec3 eye() { return new Vec3(x + 0.5, feet + EYE, z + 0.5); }

        public BlockPos floor() { return new BlockPos(x, y, z); }

        /** The block the feet are in; a pillar's next block goes here. */
        public BlockPos feetCell() { return BlockPos.containing(x + 0.5, feet + 0.01, z + 0.5); }
    }

    /** How good a place to stand is: above zero for a place worth going to. */
    public interface Goal {
        double score(Node node);
    }

    public record Path(List<Node> nodes, double score) {
        public Node end() { return nodes.getLast(); }
    }

    private final BlockGetter level;
    /** The most places to stand this search looks at. */
    private int limit = MAX_NODES;
    /** How high climbing may lift the feet: above that, a pillar gains nothing. */
    private double climbCeiling = Double.POSITIVE_INFINITY;
    /** The most climbing steps one way may take: each puts down a helper block, and the player carries so many. */
    private int pillars = Integer.MAX_VALUE;
    /** How far a drop may go down onto ground outside {@link #farDropOff}, past {@link #DROP}; see {@link #dropsOff}. */
    private double farDrop = 0;
    private AABB farDropOff;
    /** Whether a way may bridge a gap with a helper block; see {@link #noBridges}. */
    private boolean bridges = true;
    /** Which places the goal scores are a firm find; see {@link #firmWhen}. */
    private LongPredicate firm = key -> true;
    /** How much further a search goes, in cost, past the cheapest firm place found, and past the cheapest place at all. */
    private static final double FURTHER = 8, WIDER = 20;
    private final AABB area;
    /** Where a climbing block may go; false for every spot without helper blocks. */
    private final Predicate<BlockPos> pillarAllowed;
    /** Spots best not stood in, such as blocks still to be placed: they cost more, so another way is taken if there is one. */
    private final LongPredicate avoid;
    /** A search's own small leanings between ways that cost about the same; 0 for none. */
    private long leaning;
    /** Steps that failed when walked, by {@link #stepKey}: the search takes another way. */
    private Set<Long> badSteps = Set.of();
    /** Feet heights already worked out in this search, NaN where nothing can be stood on. */
    private final Map<Long, Double> standing = new HashMap<>();

    public Navigator(BlockGetter level, AABB area, Predicate<BlockPos> pillarAllowed, LongPredicate avoid) {
        this.level = level;
        this.area = area;
        this.pillarAllowed = pillarAllowed;
        this.avoid = avoid;
    }

    /** Climbs no higher than feet at {@code feet}. */
    public Navigator climbUpTo(double feet) {
        climbCeiling = feet;
        return this;
    }

    /**
     * Which places the goal scores are a firm find, by key. Past the cheapest firm place, the search goes on
     * {@link #FURTHER} for a better one; past a place that is only a poor find, it goes on {@link #WIDER}, for a firm one
     * a climb or two away. Every place is firm unless this is set.
     */
    public Navigator firmWhen(LongPredicate firm) {
        this.firm = firm;
        return this;
    }

    /** Never bridges a gap: each block a bridge puts down is one more to take away, as cleanup can't afford. */
    public Navigator noBridges() {
        bridges = false;
        return this;
    }

    /** Takes no more than {@code blocks} climbing steps along any one way: the helper blocks there are to climb with. */
    public Navigator pillars(int blocks) {
        pillars = blocks;
        return this;
    }

    /** Never takes these steps, by {@link #stepKey}: they failed when walked. */
    public Navigator avoidSteps(Set<Long> steps) {
        badSteps = steps;
        return this;
    }

    /**
     * Gives each step a small cost of its own, picked by {@code seed}: of ways that cost about the same, a different one
     * is taken each search, as a player doesn't walk the very same line every time.
     */
    public Navigator vary(long seed) {
        leaning = seed;
        return this;
    }

    /** A number between 0 and 1 picked by {@code seed}, the same for the same seed. */
    public static double unit(long seed) {
        long z = seed + 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z ^= z >>> 31;
        return (z >>> 11) * 0x1.0p-53;
    }

    /** A step from one place to stand to the next, as one number. */
    public static long stepKey(Node from, Node to) { return pairKey(from.key(), to.key()); }

    /**
     * One key for a pair: two places, or a place and a block's index. Not {@code a * 31 + b}: a place's key goes up by one a
     * block higher, so a step, or a place and a block's index, came out the same as one a block over and 31 along.
     */
    public static long pairKey(long a, long b) { return a * 0x9E3779B97F4A7C15L + b; }

    /**
     * Lets a drop go down as far as {@code feet}, past the usual {@link #DROP}, where it lands outside {@code off} seen from
     * above: off the top of a build onto the open ground round it, as a player jumps down a few blocks when there is no
     * other way, never into a gap within the build. Or onto the ground under the build's lowest layer in an aisle between
     * its parts, open on the ground to a side: v4-full8r climbed a pillar of helper blocks at 7,65..68,25 into the kelp
     * section between two hopper rows, and found no way back down for good, 40 helper blocks carried, with nothing
     * standing at the build's edge to bridge out from. A shaft down to the ground, with walls round its foot, stays shut.
     */
    public Navigator dropsOff(double feet, AABB off) {
        farDrop = feet;
        farDropOff = off;
        return this;
    }

    /** Looks at no more than {@code nodes} places to stand, for a quick search. */
    public Navigator limit(int nodes) {
        limit = nodes;
        return this;
    }

    /** Where the player stands now. */
    public static Node standingAt(BlockGetter level, Player player) {
        double feet = player.getY();
        int x = player.getBlockX(), z = player.getBlockZ();
        AABB body = player.getBoundingBox();
        for (int dy = 0; dy >= -1; dy--) {
            BlockPos cell = BlockPos.containing(player.getX(), feet - 0.05 + dy, player.getZ());
            VoxelShape shape = level.getBlockState(cell).getCollisionShape(level, cell);
            // Standing on some part of it: the rim of a hopper, or the floor of its bowl.
            for (AABB box : shape.toAabbs()) {
                AABB at = box.move(cell);
                if (Math.abs(at.maxY - feet) < 0.1 && at.minX < body.maxX && at.maxX > body.minX && at.minZ < body.maxZ && at.maxZ > body.minZ) {
                    return new Node(x, cell.getY(), z, feet, Move.START);
                }
            }
        }
        // Over an edge, the middle above a gap, held up by the rim of the block beside: crouched at the edge of a shaft,
        // say. Standing on that block, not on whatever is at the bottom of the gap.
        if (player.onGround()) {
            Node rim = null;
            double most = 0;
            for (int cx = Mth.floor(body.minX); cx <= Mth.floor(body.maxX - 1e-7); cx++) {
                for (int cz = Mth.floor(body.minZ); cz <= Mth.floor(body.maxZ - 1e-7); cz++) {
                    if (cx == x && cz == z) continue;
                    for (int dy = 0; dy >= -1; dy--) {
                        BlockPos cell = BlockPos.containing(cx + 0.5, feet - 0.05 + dy, cz + 0.5);
                        for (AABB box : level.getBlockState(cell).getCollisionShape(level, cell).toAabbs()) {
                            AABB at = box.move(cell);
                            if (Math.abs(at.maxY - feet) >= 0.1) continue;
                            double under = Math.max(0, Math.min(at.maxX, body.maxX) - Math.max(at.minX, body.minX))
                                    * Math.max(0, Math.min(at.maxZ, body.maxZ) - Math.max(at.minZ, body.minZ));
                            if (under > most) {
                                most = under;
                                rim = new Node(cx, cell.getY(), cz, feet, Move.START);
                            }
                        }
                    }
                }
            }
            if (rim != null) return rim;
        }
        // In the air: where the player comes down, on the first block below.
        for (int dy = 1; dy <= 6; dy++) {
            BlockPos cell = BlockPos.containing(player.getX(), feet - dy, player.getZ());
            VoxelShape shape = level.getBlockState(cell).getCollisionShape(level, cell);
            double top = topUnderFeet(shape);
            if (!Double.isNaN(top)) return new Node(x, cell.getY(), z, cell.getY() + top, Move.START);
        }
        BlockPos below = BlockPos.containing(player.getX(), feet - 0.05, player.getZ());
        return new Node(x, below.getY(), z, feet, Move.START);
    }

    /**
     * The cheapest way from {@code start} to the best place the goal scores: once one is found, the search goes on a
     * little further for a better one, so a few steps more can reach more blocks.
     */
    public Path find(Node start, Goal goal) {
        Map<Long, Double> cost = new HashMap<>();
        Map<Long, Node> parent = new HashMap<>();
        Map<Long, Node> nodes = new HashMap<>();
        // The climbing steps along the cheapest way to each place found, which the helper blocks carried bound.
        Map<Long, Integer> climbed = new HashMap<>();
        PriorityQueue<double[]> open = new PriorityQueue<>((a, b) -> Double.compare(a[0], b[0]));
        cost.put(start.key(), 0.0);
        nodes.put(start.key(), start);
        climbed.put(start.key(), 0);
        open.add(new double[] {0, start.x(), start.y(), start.z()});
        Node best = null;
        double bestValue = Double.NEGATIVE_INFINITY, bestCost = Double.POSITIVE_INFINITY, found = Double.POSITIVE_INFINITY;
        double foundAny = Double.POSITIVE_INFINITY;
        int expanded = 0;
        while (!open.isEmpty() && expanded < limit) {
            double[] entry = open.poll();
            long key = BlockPos.asLong((int) entry[1], (int) entry[2], (int) entry[3]);
            Double known = cost.get(key);
            if (known == null || entry[0] > known + 1e-9) continue;
            if (entry[0] > Math.min(found + FURTHER, foundAny + WIDER)) break;
            Node node = nodes.get(key);
            expanded++;
            double score = goal.score(node);
            if (score > 0) {
                foundAny = Math.min(foundAny, entry[0]);
                if (firm.test(key)) found = Math.min(found, entry[0]);
                // Each block reached is worth a few steps.
                double value = score * 3 - entry[0];
                if (value > bestValue) {
                    bestValue = value;
                    best = node;
                    bestCost = entry[0];
                }
            }
            int climbs = climbed.get(key);
            // The helper blocks the way here puts down stand in the way of the steps after it.
            for (Node next : neighbors(node, climbs == 0 ? null : placedAlong(node, parent))) {
                int used = climbs + (next.move().placesBlock() ? 1 : 0);
                if (used > pillars) continue;
                double step = entry[0] + stepCost(node, next);
                Double previous = cost.get(next.key());
                if (previous != null && previous <= step) continue;
                cost.put(next.key(), step);
                parent.put(next.key(), node);
                nodes.put(next.key(), next);
                climbed.put(next.key(), used);
                open.add(new double[] {step, next.x(), next.y(), next.z()});
            }
        }
        if (best == null) return null;
        List<Node> path = new ArrayList<>();
        for (Node at = best; at != null; at = parent.get(at.key())) path.add(at);
        Collections.reverse(path);
        return new Path(List.copyOf(path), bestCost);
    }

    private double stepCost(Node from, Node to) {
        double cost = switch (to.move()) {
            case WALK -> from.x() != to.x() && from.z() != to.z() ? 1.41 : 1;
            // A jump that comes down past the usual drop, off a build, costs as such a drop does.
            case JUMP -> 2 + Math.max(0, from.feet() - to.feet() - DROP) * 6;
            // Past the usual drop, each block more costs the player some health: only where nothing else does.
            case DROP -> 1.5 + (from.feet() - to.feet()) * 0.5 + Math.max(0, from.feet() - to.feet() - DROP) * 6;
            case PILLAR -> 6;
            case BRIDGE -> 8;
            case STEP -> 7;
            // A way out of water there is no other way out of, not a short cut: it can miss, and drop the player back in.
            case SWIM -> 30 + Math.max(0, to.feet() - from.feet());
            case START -> 0;
        };
        // Standing where a block still has to go blocks it: allowed on the way, avoided as a place to stop.
        BlockPos feet = to.feetCell();
        if (avoid.test(feet.asLong()) || avoid.test(feet.above().asLong())) cost += 4;
        // Water slows the player down: dry ground is taken where there is some. Ice carries them past where they stop.
        if (!level.getFluidState(feet).isEmpty() || !level.getFluidState(feet.above()).isEmpty()) cost += 2;
        if (level.getBlockState(to.floor()).getBlock().getFriction() > 0.7f) cost += 2;
        if (leaning != 0) cost += 0.45 * unit(to.key() ^ leaning);
        // A narrow top holds the player, but a little drift drops them off it: wider ground is taken where there is some.
        if (narrow(to.x(), to.y(), to.z())) cost += 3;
        // In water with a drop beside, water floats the player off the edge a few tenths at a time, and a jump or a drop
        // there comes down off it: v1-full7 dropped into the kelp water over the dispenser column at 9, 67, 6 and was
        // carried off its edge into the channel beside. Walked through only where no other way is, never jumped or
        // dropped into or out of, unless that is the only way at all.
        boolean wetEdge = wetOverDrop(to) || wetOverDrop(from);
        if (wetEdge) cost += to.move() == Move.WALK ? WET_EDGE : WET_EDGE_LEAP;
        return cost;
    }

    /** The blocks the way to {@code node} puts down, climbing and bridging, by position. */
    private static Set<Long> placedAlong(Node node, Map<Long, Node> parent) {
        Set<Long> placed = new HashSet<>();
        for (Node at = node; at != null; at = parent.get(at.key())) {
            if (at.move().placesBlock()) placed.add(at.floor().asLong());
        }
        return placed;
    }

    /** Whether one of the {@code placed} blocks is in the column at x, z between the heights {@code from} and {@code to}. */
    private static boolean crosses(Set<Long> placed, int x, int z, double from, double to) {
        if (placed == null) return false;
        for (int y = (int) Math.floor(from + 0.001); y <= (int) Math.floor(to - 0.001); y++) {
            if (placed.contains(BlockPos.asLong(x, y, z))) return true;
        }
        return false;
    }

    /**
     * The places one step from {@code from}. With {@code placed}, the blocks put down on the way to it so far: they are
     * there by the time the step is walked, though not yet in the level, so a step through one is no step.
     */
    private List<Node> neighbors(Node from, Set<Long> placed) {
        List<Node> out = new ArrayList<>(10);
        for (int[] d : DIRECTIONS) {
            int nx = from.x() + d[0], nz = from.z() + d[1];
            if (nx < area.minX || nx >= area.maxX || nz < area.minZ || nz >= area.maxZ) continue;
            boolean diagonal = d[0] != 0 && d[1] != 0;
            boolean far = farDrop > DROP;
            boolean offSide = far && (nx + 1 <= farDropOff.minX || nx >= farDropOff.maxX || nz + 1 <= farDropOff.minZ || nz >= farDropOff.maxZ);
            int lowest = from.y() - 4 - (far ? (int) Math.ceil(farDrop - DROP) : 0);
            for (int ny = from.y() + 2; ny >= lowest; ny--) {
                if (ny < area.minY || ny >= area.maxY) continue;
                Double feet = standable(nx, ny, nz);
                if (feet == null) continue;
                double rise = feet - from.feet();
                boolean farOff = offSide || far && feet <= farDropOff.minY + 1e-3 && openFoot(nx, ny, nz, feet);
                if (rise > JUMP || rise < -(farOff ? farDrop : DROP)) continue;
                // Into or out of a bowl, as a hopper's or a composter's, the feet get over its rim on the way, higher
                // than where they rest: a step that looks small can take a jump.
                double over = Math.max(rise, Math.max(rim(nx, ny, nz), rim(from.x(), from.y(), from.z())) - from.feet());
                if (over > JUMP) continue;
                // A drop off the build begun with a jump, out of a bowl or over a rim, falls from the top of the jump: a
                // fall that much longer than the drop, and more harm than the drop was allowed.
                if (farOff && over > STEP && rise < -DROP && JUMP - rise > farDrop) continue;
                double top = Math.max(feet, from.feet() + Math.max(over, 0)) + BODY;
                // The way across: the new spot's column is clear from its feet to the head at the higher of the two;
                // a jump needs the head room above the old spot too, and a diagonal both corners clear.
                if (!clear(nx, nz, feet, top, ny) || crosses(placed, nx, nz, feet, top)) continue;
                if (over > STEP && (!clear(from.x(), from.z(), from.feet(), from.feet() + over + BODY, from.y())
                        || crosses(placed, from.x(), from.z(), from.feet(), from.feet() + over + BODY))) continue;
                if (diagonal && (!clear(from.x() + d[0], from.z(), Math.max(feet, from.feet()), top, Integer.MIN_VALUE)
                        || !clear(from.x(), from.z() + d[1], Math.max(feet, from.feet()), top, Integer.MIN_VALUE)
                        || crosses(placed, from.x() + d[0], from.z(), Math.max(feet, from.feet()), top)
                        || crosses(placed, from.x(), from.z() + d[1], Math.max(feet, from.feet()), top))) continue;
                if (diagonal && (over > STEP || rise < -STEP)) continue;
                // Cutting a corner, the body passes over both corner cells: a walk turns as it goes and can drift out over
                // one, so each needs ground at about the height of the step, or the step goes around by the sides.
                if (diagonal && (!holds(from.x() + d[0], from.z(), ny, from.y(), from.feet(), feet)
                        || !holds(from.x(), from.z() + d[1], ny, from.y(), from.feet(), feet))) continue;
                // On a narrow top, such as a closed fence gate's, only straight steps: cutting a corner swings the body
                // off it sideways.
                if (diagonal && (narrow(nx, ny, nz) || narrow(from.x(), from.y(), from.z()))) continue;
                Move move = over > STEP ? Move.JUMP : rise < -STEP ? Move.DROP : Move.WALK;
                // Landing a jump or a drop on a narrow top takes more aim than a walk has: a few centimetres off and the
                // body comes down beside it. Narrow tops are walked onto at their own height only.
                if (move != Move.WALK && narrow(nx, ny, nz)) continue;
                // Nor stepped down onto, half a block from a bridge or a full block say: the step down carries the body on
                // past a top a few centimetres wide, as it did off a gate top at 12 73.5 30 into a channel (v4-y).
                if (narrow(nx, ny, nz) && rise < -0.05) continue;
                // Honey holds a jump down to half its height, and slime throws back up what drops on it.
                if (move == Move.JUMP && unsteady(from.floor(), Blocks.HONEY_BLOCK)) continue;
                // Coming down onto it at all, by a drop or a jump that lands lower, out of a bowl say.
                if (rise < -STEP && unsteady(new BlockPos(nx, ny, nz), Blocks.SLIME_BLOCK)) continue;
                Node next = new Node(nx, ny, nz, feet, move);
                if (!badSteps.isEmpty() && badSteps.contains(stepKey(from, next))) continue;
                out.add(next);
            }
        }
        // Climbing: jump and place a block where the feet were, then stand on it.
        BlockPos cell = from.feetCell();
        if (Math.abs(from.feet() - cell.getY()) < 0.01 && cell.getY() + 1 < area.maxY && from.feet() + 1 <= climbCeiling
                && pillarAllowed.test(cell) && !deepWater(cell)
                && clear(from.x(), from.z(), from.feet() + 1, from.feet() + 1 + BODY, Integer.MIN_VALUE)
                && !crosses(placed, from.x(), from.z(), from.feet() + 1, from.feet() + 1 + BODY)
                && seeThrough(from.x(), from.z(), cell.getY(), cell.getY() + 2)
                && !unsteady(from.floor(), Blocks.HONEY_BLOCK)) {
            Node next = new Node(cell.getX(), cell.getY(), cell.getZ(), from.feet() + 1, Move.PILLAR);
            if (badSteps.isEmpty() || !badSteps.contains(stepKey(from, next))) out.add(next);
        }
        // In water too deep to jump from, as run out of a build onto the ground under it, a jump only swims up and the
        // pillar never gets its block: a block set on the ground beside instead, into the water there, and a step up onto
        // it, the swim up carrying the player over its edge. From on top the climb goes on as on dry ground.
        // Not in a current, nor over ice: standing still to aim at the ground beside, the player drifts off, out of reach
        // of the spot (v1-full7, the kelp channel at x 6 over packed ice carried them from z 9..11 to 14, six times over).
        if (deepWater(cell) && !drifts(from)
                && Math.abs(from.feet() - cell.getY()) < 0.01 && from.feet() + 1 <= climbCeiling
                && clear(from.x(), from.z(), from.feet(), from.feet() + 1 + BODY, from.y())) {
            for (int[] d : DIRECTIONS) {
                if (d[0] != 0 && d[1] != 0) continue;
                int nx = from.x() + d[0], nz = from.z() + d[1];
                if (nx < area.minX || nx >= area.maxX || nz < area.minZ || nz >= area.maxZ) continue;
                BlockPos step = new BlockPos(nx, cell.getY(), nz), ground = step.below();
                if (!level.getBlockState(step).canBeReplaced() || !pillarAllowed.test(step) || placed != null && placed.contains(step.asLong())) continue;
                if (!level.getBlockState(ground).isCollisionShapeFullBlock(level, ground)) continue;
                if (!clear(nx, nz, from.feet() + 1, from.feet() + 1 + BODY, Integer.MIN_VALUE)
                        || crosses(placed, nx, nz, from.feet() + 1, from.feet() + 1 + BODY)) continue;
                // The look down at the ground passes through the spot: nothing there with an outline in the way.
                if (!seeThrough(nx, nz, step.getY(), step.getY())) continue;
                Node next = new Node(nx, step.getY(), nz, from.feet() + 1, Move.STEP);
                if (badSteps.isEmpty() || !badSteps.contains(stepKey(from, next))) out.add(next);
            }
        }
        // Swimming up: a column of water over the feet, and at its top a block beside to climb out onto. Wading is what a walk
        // does everywhere else; this is the way out of a water channel a player fell into, closed over but for the water
        // (v4-full17r, 22:22, the channel at 9 67 5..7 under open gates, every helper block's spot taken by the build).
        if (deepWater(cell)) {
            int top = cell.getY();
            boolean column = true;
            while (column && top + 1 < area.maxY && top - cell.getY() < SWIM_RISE) {
                BlockPos up = new BlockPos(from.x(), top + 1, from.z());
                FluidState water = level.getFluidState(up);
                if (!water.getType().isSame(Fluids.WATER)) break;
                if (!level.getBlockState(up).getCollisionShape(level, up).isEmpty()) column = false;
                else top++;
            }
            for (int y = cell.getY(); column && y <= top; y++) column = !dragsDown(new BlockPos(from.x(), y, from.z()));
            BlockPos topCell = new BlockPos(from.x(), top, from.z());
            double surface = top + level.getFluidState(topCell).getHeight(level, topCell);
            if (column) {
                for (int[] d : DIRECTIONS) {
                    if (d[0] != 0 && d[1] != 0) continue;
                    int nx = from.x() + d[0], nz = from.z() + d[1];
                    if (nx < area.minX || nx >= area.maxX || nz < area.minZ || nz >= area.maxZ) continue;
                    for (int ny = top + 1; ny >= cell.getY(); ny--) {
                        Double feet = standable(nx, ny, nz);
                        if (feet == null || feet <= from.feet() + STEP || feet > surface + SWIM_OUT) continue;
                        if (!clear(from.x(), from.z(), from.feet(), feet + BODY, from.y()) || crosses(placed, from.x(), from.z(), from.feet(), feet + BODY)) continue;
                        if (!clear(nx, nz, feet, feet + BODY, ny) || crosses(placed, nx, nz, feet, feet + BODY)) continue;
                        // Out of the water over a block's whole side only: swimming against a wall is what lifts the body
                        // over the edge, and a thin side, a fence gate's or a hopper's spout, gives no wall to swim
                        // against (v4-full19, 23:19: swum up to 68.86 beside 9 68 16 again and again, never onto it).
                        BlockPos edge = new BlockPos(nx, ny, nz);
                        if (Math.abs(feet - (ny + 1)) > 0.01 || !level.getBlockState(edge).isFaceSturdy(level, edge, Direction.getApproximateNearest(-d[0], 0, -d[1]))) continue;
                        Node next = new Node(nx, ny, nz, feet, Move.SWIM);
                        if (badSteps.isEmpty() || !badSteps.contains(stepKey(from, next))) out.add(next);
                        break;
                    }
                }
            }
        }
        // Bridging: crouched at the edge, a block put against the side of the one underfoot, and a step onto it, as a
        // player crosses a gap a block wide, or gets from a pillar onto a build beside it. Only straight out from a full
        // block, over a spot with nothing to stand on at the feet's height.
        BlockPos under = from.floor();
        // One block across, not a way on through the air: a bridge goes on from solid ground only, which also keeps a
        // search from spreading over every empty spot at that height.
        if (bridges && from.move() != Move.BRIDGE && Math.abs(from.feet() - (from.y() + 1)) < 0.01
                && level.getBlockState(under).isCollisionShapeFullBlock(level, under) && !drifts(from)) {
            for (int[] d : DIRECTIONS) {
                if (d[0] != 0 && d[1] != 0) continue;
                int nx = from.x() + d[0], nz = from.z() + d[1];
                if (nx < area.minX || nx >= area.maxX || nz < area.minZ || nz >= area.maxZ) continue;
                BlockPos floor = new BlockPos(nx, from.y(), nz);
                if (!level.getBlockState(floor).canBeReplaced() || !pillarAllowed.test(floor) || placed != null && placed.contains(floor.asLong())) continue;
                if (!clear(nx, nz, from.feet(), from.feet() + BODY, Integer.MIN_VALUE) || crosses(placed, nx, nz, from.feet(), from.feet() + BODY)) continue;
                // The look down at the side of the block underfoot passes through the cells over the gap: an open fence
                // gate there, or a pane, has no collision but stops the crosshair on its outline, and the block to step
                // across on never goes in.
                if (!seeThrough(nx, nz, from.y() + 1, from.y() + 2)) continue;
                Node next = new Node(nx, from.y(), nz, from.feet(), Move.BRIDGE);
                if (badSteps.isEmpty() || !badSteps.contains(stepKey(from, next))) out.add(next);
            }
        }
        return out;
    }

    /** Whether a place to stand at {@code feet} on the floor at x, y, z leads on, on foot, to one beside it. */
    private boolean openFoot(int x, int y, int z, double feet) {
        for (int[] d : DIRECTIONS) {
            if (d[0] != 0 && d[1] != 0) continue;
            Double next = standable(x + d[0], y, z + d[1]);
            if (next != null && Math.abs(next - feet) <= STEP && clear(x + d[0], z + d[1], next, Math.max(next, feet) + BODY, y)) return true;
        }
        return false;
    }

    /** Whether the column at x, z has ground on one of the two floors given, within a step of the feet heights a and b. */
    private boolean holds(int x, int z, int floorA, int floorB, double a, double b) {
        for (int y : new int[] {floorA, floorB}) {
            Double feet = standable(x, y, z);
            if (feet != null && feet >= Math.min(a, b) - STEP && feet <= Math.max(a, b) + STEP) return true;
        }
        return false;
    }

    /** The height of the highest part of the block at x, y, z: a bowl's rim, above where the feet rest in it. */
    private double rim(int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        VoxelShape shape = level.getBlockState(pos).getCollisionShape(level, pos);
        return shape.isEmpty() ? Double.NEGATIVE_INFINITY : y + shape.max(Direction.Axis.Y);
    }

    /**
     * Whether a look straight down the column, from the top of a climbing jump to the block under the feet, meets
     * nothing on the way: an open fence gate or a pane has no collision, so the body passes into it, but the crosshair
     * stops on its outline and the block to climb on never goes in.
     */
    /** How deep water may be, from the bottom of the feet's cell, for a jump from it: deeper, the player swims instead. */
    static final double SWIM_DEPTH = 0.4;
    /** The most blocks a swim up a column of water rises, and how far over the water's top a block beside is climbed onto. */
    private static final int SWIM_RISE = 4;
    private static final double SWIM_OUT = 1.2;

    /** Whether the cell holds a bubble column that pulls down, over magma: nobody swims up that. */
    private boolean dragsDown(BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.is(Blocks.BUBBLE_COLUMN) && state.getValue(net.minecraft.world.level.block.BubbleColumnBlock.DRAG_DOWN);
    }

    /**
     * Whether a player standing still at {@code node} drifts off it: in running water, or on ice, slippery past
     * {@link #SLIPPERY}. A block put down from there by a look held a few ticks, a bridge's or a step's, goes amiss.
     */
    private boolean drifts(Node node) {
        BlockPos feet = node.feetCell();
        if (level.getFluidState(feet).getFlow(level, feet).lengthSqr() > 1e-6) return true;
        return level.getBlockState(node.floor()).getBlock().getFriction() > SLIPPERY;
    }

    /** What a walk into or out of water with a drop beside costs more; and a jump or a drop. */
    private static final double WET_EDGE = 6, WET_EDGE_LEAP = 30;
    /** {@link #wetOverDrop} worked out in this search, by place. */
    private final Map<Long, Boolean> wetEdges = new HashMap<>();

    /** Whether the feet at {@code node} are in water with a drop of more than a block and a half beside them. */
    private boolean wetOverDrop(Node node) {
        return wetEdges.computeIfAbsent(node.key(), k -> {
            BlockPos feet = node.feetCell();
            if (level.getFluidState(feet).isEmpty()) return false;
            BlockPos floor = node.floor();
            for (Direction side : Direction.Plane.HORIZONTAL) {
                BlockPos beside = floor.relative(side);
                boolean ground = false;
                // As in narrowOverDrop: something beside at most a block and a half under the feet, or a wall.
                for (int dy = 1; dy >= -1 && !ground; dy--) {
                    BlockPos cell = beside.above(dy);
                    VoxelShape shape = level.getBlockState(cell).getCollisionShape(level, cell);
                    ground = !shape.isEmpty() && cell.getY() + shape.max(Direction.Axis.Y) >= node.feet() - 1.5;
                }
                if (!ground) return true;
            }
            return false;
        });
    }

    /** Friction past which a floor is slippery, as ice is: 0.6 is the usual. */
    private static final float SLIPPERY = 0.6f;

    /** Whether the feet's cell holds water deeper than {@link #SWIM_DEPTH}, where a jump only swims up. */
    private boolean deepWater(BlockPos cell) {
        FluidState fluid = level.getFluidState(cell);
        return fluid.getType().isSame(Fluids.WATER) && fluid.getHeight(level, cell) > SWIM_DEPTH;
    }

    private boolean seeThrough(int x, int z, int fromY, int toY) {
        for (int y = fromY; y <= toY; y++) {
            BlockPos pos = new BlockPos(x, y, z);
            if (!level.getBlockState(pos).getShape(level, pos).isEmpty()) return false;
        }
        return true;
    }

    /** The feet's height standing on the block at x, y, z, or null when it can't be stood on there. */
    Double standable(int x, int y, int z) {
        long key = BlockPos.asLong(x, y, z);
        Double known = standing.get(key);
        if (known == null) {
            Double feet = workOutStanding(x, y, z);
            known = feet == null ? Double.NaN : feet;
            standing.put(key, known);
        }
        return known.isNaN() ? null : known;
    }

    private Double workOutStanding(int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        BlockState floor = level.getBlockState(pos);
        if (hurts(floor)) return null;
        VoxelShape shape = floor.getCollisionShape(level, pos);
        double top = topUnderFeet(shape);
        if (Double.isNaN(top) || top <= 0 || top > 1.5) return null;
        double feet = y + top;
        return clear(x, z, feet, feet + BODY, y) ? feet : null;
    }

    /**
     * Where the feet come to rest on a block, standing in the middle of it: the top of what is under a player's
     * footprint there. A hopper or composter is a bowl, its rim higher than the floor the player sinks onto. NaN when
     * nothing is under the footprint.
     */
    static double topUnderFeet(VoxelShape shape) {
        double top = Double.NaN;
        for (AABB box : shape.toAabbs()) {
            if (box.minX < 0.8 && box.maxX > 0.2 && box.minZ < 0.8 && box.maxZ > 0.2) top = Double.isNaN(top) ? box.maxY : Math.max(top, box.maxY);
        }
        return top;
    }

    /** Whether the block at x, y, z is stood on by a top less than half a block wide: a fence, a closed gate, a pane. */
    private boolean narrow(int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        return narrowTop(level.getBlockState(pos).getCollisionShape(level, pos));
    }

    /**
     * Whether the shape's top, where a player stands on it, leaves the middle of the block open somewhere: the middle,
     * from 0.3 to 0.7 each way, isn't all under it. However the shape is cut into boxes.
     */
    /**
     * Whether a cell beside the place to stand, at the feet's height, has nothing under it either to stop the player: an
     * open gate, water over a gap, plain air. A step that comes down onto such a place carries the body on past it.
     */
    static boolean besideFall(BlockGetter level, Node node) {
        BlockPos feet = node.feetCell();
        for (Direction side : Direction.Plane.HORIZONTAL) {
            BlockPos beside = feet.relative(side);
            if (level.getBlockState(beside).getCollisionShape(level, beside).isEmpty()
                    && level.getBlockState(beside.below()).getCollisionShape(level, beside.below()).isEmpty()) return true;
        }
        return false;
    }

    /**
     * Whether the place to stand is a narrow top, a closed gate's or a fence's, with a drop of two blocks or more right
     * beside it: a few centimetres of drift off it and the player is down there.
     */
    static boolean narrowOverDrop(BlockGetter level, Node node) {
        BlockPos floor = node.floor();
        if (!narrowTop(level.getBlockState(floor).getCollisionShape(level, floor))) return false;
        for (Direction side : Direction.Plane.HORIZONTAL) {
            BlockPos beside = floor.relative(side);
            boolean ground = false;
            // Something beside at most a block and a half under the feet, or a wall: a step, not a drop.
            for (int dy = 1; dy >= -1 && !ground; dy--) {
                BlockPos cell = beside.above(dy);
                VoxelShape shape = level.getBlockState(cell).getCollisionShape(level, cell);
                ground = !shape.isEmpty() && cell.getY() + shape.max(Direction.Axis.Y) >= node.feet() - 1.5;
            }
            if (!ground) return true;
        }
        return false;
    }

    /**
     * Whether a step from {@code from} to {@code to} at the same height keeps the feet over the tops all the way: the tops
     * meet, as along a fence or a row of closed gates facing the same way, so a crouched player walks it held by the edges.
     * Across the gap between two gates' tops, which upright a player crosses in a stride, they don't.
     */
    static boolean supportedAlong(BlockGetter level, Node from, Node to) {
        if (Math.abs(from.feet() - to.feet()) > 0.01) return false;
        List<AABB> tops = new ArrayList<>();
        for (BlockPos floor : List.of(from.floor(), to.floor())) {
            for (AABB box : level.getBlockState(floor).getCollisionShape(level, floor).toAabbs()) {
                if (Math.abs(floor.getY() + box.maxY - from.feet()) < 0.01) tops.add(box.move(floor.getX(), floor.getY(), floor.getZ()));
            }
        }
        for (int k = 0; k <= 4; k++) {
            double t = k / 4.0, x = from.x() + 0.5 + (to.x() - from.x()) * t, z = from.z() + 0.5 + (to.z() - from.z()) * t;
            AABB feet = new AABB(x - 0.3, from.feet() - 0.1, z - 0.3, x + 0.3, from.feet() + 0.1, z + 0.3);
            if (tops.stream().noneMatch(feet::intersects)) return false;
        }
        return true;
    }

    static boolean narrowTop(VoxelShape shape) {
        double top = topUnderFeet(shape);
        if (Double.isNaN(top)) return false;
        List<AABB> tops = shape.toAabbs().stream().filter(box -> Math.abs(box.maxY - top) < 1e-6).toList();
        for (double x = 0.3; x < 0.75; x += 0.1) {
            for (double z = 0.3; z < 0.75; z += 0.1) {
                double px = x, pz = z;
                if (tops.stream().noneMatch(box -> box.minX <= px && px <= box.maxX && box.minZ <= pz && pz <= box.maxZ)) return true;
            }
        }
        return false;
    }

    /** Whether nothing solid or harmful fills the column between two heights; the block {@code floorY} is left out. */
    private boolean clear(int x, int z, double from, double to, int floorY) {
        for (int y = (int) Math.floor(from + 0.001); y <= (int) Math.floor(to - 0.001); y++) {
            if (y == floorY) continue;
            BlockPos pos = new BlockPos(x, y, z);
            BlockState state = level.getBlockState(pos);
            // Water is waded through, as shallow water in a farm is; lava never.
            if (state.getFluidState().is(net.minecraft.tags.FluidTags.LAVA) || hurts(state)) return false;
            if (!state.getCollisionShape(level, pos).isEmpty()) return false;
        }
        return true;
    }

    /** Whether the block at {@code pos} is {@code block}: honey under a jump, slime under a drop. */
    private boolean unsteady(BlockPos pos, Block block) {
        return level.getBlockState(pos).is(block);
    }

    static boolean hurts(BlockState state) {
        return state.is(BlockTags.FIRE) || state.is(Blocks.LAVA) || state.is(Blocks.MAGMA_BLOCK) || state.is(BlockTags.CAMPFIRES)
                || state.is(Blocks.CACTUS) || state.is(Blocks.SWEET_BERRY_BUSH) || state.is(Blocks.WITHER_ROSE)
                || state.is(Blocks.POWDER_SNOW) || state.is(Blocks.COBWEB) || state.is(Blocks.POINTED_DRIPSTONE);
    }
}
