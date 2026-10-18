package dev.donut.client.schematic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.LongPredicate;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
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

    public enum Move { START, WALK, JUMP, DROP, PILLAR, BRIDGE }

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
    public static long stepKey(Node from, Node to) { return from.key() * 31 + to.key(); }

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
            for (Node next : neighbors(node)) {
                int used = climbs + (next.move() == Move.PILLAR || next.move() == Move.BRIDGE ? 1 : 0);
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
            case JUMP -> 2;
            case DROP -> 1.5 + (from.feet() - to.feet()) * 0.5;
            case PILLAR -> 6;
            case BRIDGE -> 8;
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
        return cost;
    }

    private List<Node> neighbors(Node from) {
        List<Node> out = new ArrayList<>(10);
        for (int[] d : DIRECTIONS) {
            int nx = from.x() + d[0], nz = from.z() + d[1];
            if (nx < area.minX || nx >= area.maxX || nz < area.minZ || nz >= area.maxZ) continue;
            boolean diagonal = d[0] != 0 && d[1] != 0;
            for (int ny = from.y() + 2; ny >= from.y() - 4; ny--) {
                if (ny < area.minY || ny >= area.maxY) continue;
                Double feet = standable(nx, ny, nz);
                if (feet == null) continue;
                double rise = feet - from.feet();
                if (rise > JUMP || rise < -DROP) continue;
                // Into or out of a bowl, as a hopper's or a composter's, the feet get over its rim on the way, higher
                // than where they rest: a step that looks small can take a jump.
                double over = Math.max(rise, Math.max(rim(nx, ny, nz), rim(from.x(), from.y(), from.z())) - from.feet());
                if (over > JUMP) continue;
                double top = Math.max(feet, from.feet() + Math.max(over, 0)) + BODY;
                // The way across: the new spot's column is clear from its feet to the head at the higher of the two;
                // a jump needs the head room above the old spot too, and a diagonal both corners clear.
                if (!clear(nx, nz, feet, top, ny)) continue;
                if (over > STEP && !clear(from.x(), from.z(), from.feet(), from.feet() + over + BODY, from.y())) continue;
                if (diagonal && (!clear(from.x() + d[0], from.z(), Math.max(feet, from.feet()), top, Integer.MIN_VALUE)
                        || !clear(from.x(), from.z() + d[1], Math.max(feet, from.feet()), top, Integer.MIN_VALUE))) continue;
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
                Node next = new Node(nx, ny, nz, feet, move);
                if (!badSteps.isEmpty() && badSteps.contains(stepKey(from, next))) continue;
                out.add(next);
            }
        }
        // Climbing: jump and place a block where the feet were, then stand on it.
        BlockPos cell = from.feetCell();
        if (Math.abs(from.feet() - cell.getY()) < 0.01 && cell.getY() + 1 < area.maxY && from.feet() + 1 <= climbCeiling
                && pillarAllowed.test(cell)
                && clear(from.x(), from.z(), from.feet() + 1, from.feet() + 1 + BODY, Integer.MIN_VALUE)
                && seeThrough(from.x(), from.z(), cell.getY(), cell.getY() + 2)) {
            Node next = new Node(cell.getX(), cell.getY(), cell.getZ(), from.feet() + 1, Move.PILLAR);
            if (badSteps.isEmpty() || !badSteps.contains(stepKey(from, next))) out.add(next);
        }
        // Bridging: crouched at the edge, a block put against the side of the one underfoot, and a step onto it, as a
        // player crosses a gap a block wide, or gets from a pillar onto a build beside it. Only straight out from a full
        // block, over a spot with nothing to stand on at the feet's height.
        BlockPos under = from.floor();
        // One block across, not a way on through the air: a bridge goes on from solid ground only, which also keeps a
        // search from spreading over every empty spot at that height.
        if (from.move() != Move.BRIDGE && Math.abs(from.feet() - (from.y() + 1)) < 0.01
                && level.getBlockState(under).isCollisionShapeFullBlock(level, under)) {
            for (int[] d : DIRECTIONS) {
                if (d[0] != 0 && d[1] != 0) continue;
                int nx = from.x() + d[0], nz = from.z() + d[1];
                if (nx < area.minX || nx >= area.maxX || nz < area.minZ || nz >= area.maxZ) continue;
                BlockPos floor = new BlockPos(nx, from.y(), nz);
                if (!level.getBlockState(floor).canBeReplaced() || !pillarAllowed.test(floor)) continue;
                if (!clear(nx, nz, from.feet(), from.feet() + BODY, Integer.MIN_VALUE)) continue;
                Node next = new Node(nx, from.y(), nz, from.feet(), Move.BRIDGE);
                if (badSteps.isEmpty() || !badSteps.contains(stepKey(from, next))) out.add(next);
            }
        }
        return out;
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

    static boolean hurts(BlockState state) {
        return state.is(BlockTags.FIRE) || state.is(Blocks.LAVA) || state.is(Blocks.MAGMA_BLOCK) || state.is(BlockTags.CAMPFIRES)
                || state.is(Blocks.CACTUS) || state.is(Blocks.SWEET_BERRY_BUSH) || state.is(Blocks.WITHER_ROSE)
                || state.is(Blocks.POWDER_SNOW) || state.is(Blocks.COBWEB) || state.is(Blocks.POINTED_DRIPSTONE);
    }
}
