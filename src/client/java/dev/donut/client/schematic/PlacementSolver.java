package dev.donut.client.schematic;

import dev.donut.client.mixin.BlockItemAccess;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongPredicate;
import java.util.function.Predicate;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LiquidBlockContainer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Finds a right click that places a block exactly as the schematic has it, as a player places it: looking straight at
 * a spot on a face they can see, within reach, standing or crouching. Each spot is handed to the block's own placement
 * code with the look that points at it, which says what it would place; the first that makes the target wins. A block
 * whose facing depends on the look, such as a repeater or an observer, can only go in from where that look points at a
 * face, so where the player stands decides it.
 */
public final class PlacementSolver {
    /** Where on a face to click: its middle first, then toward its edges, which top slabs, stairs and door hinges need. */
    private static final double[] SPOTS = {0.5, 0.2, 0.8};
    /** How far, as a share of the face, a spot aimed at may be off one of those. */
    private static final double JITTER = 0.06;
    /** Looks tried to tell whether a block can go in at all: along the axes, between them, and steeply up and down. */
    private static final float[] YAWS = {0, 90, 180, -90, 45, 135, -135, -45};
    private static final float[] PITCHES = {0, 90, -90, 45, -45, 60, -60, 25, -25};
    /** Eye heights standing and crouching. */
    static final double STAND_EYE = 1.62, CROUCH_EYE = 1.27;

    public enum Problem {
        /** The player stands where the block goes. */
        IN_THE_WAY,
        /** Nothing next to it can hold it yet, or it would fall. */
        NO_SUPPORT,
        /** It can go in, but not with a look from where the player is. */
        OUT_OF_REACH,
        /** From here, but in the other stance: crouching, or standing up. The click says which. */
        STANCE,
        /** Water or lava to pour, but no face beside the spot is in view for the bucket's look. */
        NO_VIEW
    }

    /**
     * A right click: the spot looked at and clicked, the look that points at it, and whether the player crouches for
     * it, as they must to place against a chest or a repeater rather than use it.
     */
    public record Click(BlockHitResult hit, float yaw, float pitch, boolean crouch) { }

    public record Result(Click click, Problem problem) {
        static Result of(Click click) { return new Result(click, null); }

        static Result failed(Problem problem) { return new Result(null, problem); }
    }

    private record Spot(BlockHitResult hit, boolean facesEye) { }

    private PlacementSolver() { }

    /** How to place {@code target} at {@code pos} from where the player stands, in the stance they are in. */
    public static Result find(LocalPlayer player, BlockPos pos, BlockState target, ItemStack stack, double reach, LongPredicate unclickable) {
        Level level = player.level();
        if (inTheWay(player.getBoundingBox(), level, pos, target) || sharesSpot(player.getBoundingBox(), level, pos, target)) {
            return Result.failed(Problem.IN_THE_WAY);
        }
        if (target.getBlock() instanceof FallingBlock && FallingBlock.isFree(level.getBlockState(pos.below()))) {
            return Result.failed(Problem.NO_SUPPORT);
        }
        boolean crouching = player.isShiftKeyDown();
        Click click = look(player, level, player.getEyePosition(), crouching, pos, target, stack, reach, unclickable);
        if (click != null) return Result.of(click);
        Vec3 feet = player.position();
        Vec3 otherEye = feet.add(0, crouching ? STAND_EYE : CROUCH_EYE, 0);
        click = look(player, level, otherEye, !crouching, pos, target, stack, reach, unclickable);
        if (click != null) return new Result(click, Problem.STANCE);
        return Result.failed(supported(player, pos, target, stack, unclickable) ? Problem.OUT_OF_REACH : Problem.NO_SUPPORT);
    }

    /** A click that would place the block from a place the player might stand, with the feet at {@code feet}, either stance. */
    public static Click fromSpot(LocalPlayer player, Vec3 feet, BlockPos pos, BlockState target, ItemStack stack, double reach,
                                 LongPredicate unclickable) {
        return fromSpot(player, player.level(), feet, pos, target, stack, reach, unclickable);
    }

    /** As the other {@code fromSpot}, with the looks traced through {@code view}: from a pit not dug yet, say. */
    public static Click fromSpot(LocalPlayer player, BlockGetter view, Vec3 feet, BlockPos pos, BlockState target, ItemStack stack,
                                 double reach, LongPredicate unclickable) {
        AABB body = new AABB(feet.x - 0.3, feet.y, feet.z - 0.3, feet.x + 0.3, feet.y + 1.8, feet.z + 0.3);
        if (inTheWay(body, player.level(), pos, target)) return null;
        Click click = look(player, view, feet.add(0, STAND_EYE, 0), false, pos, target, stack, reach, unclickable);
        return click != null ? click : look(player, view, feet.add(0, CROUCH_EYE, 0), true, pos, target, stack, reach, unclickable);
    }

    /**
     * Whether the block can go in at all yet, wherever the player might stand: some face beside it, clicked with some
     * look, makes it. Where that look can come from is the walk's business.
     */
    public static boolean supported(LocalPlayer player, BlockPos pos, BlockState target, ItemStack stack, LongPredicate unclickable) {
        return supported(player, player.level(), pos, target, stack, unclickable);
    }

    /**
     * As the other {@code supported}, with the blocks around read from {@code view}: with a block not placed yet put
     * in, it tells whether that block would shut this one in.
     */
    public static boolean supported(LocalPlayer player, BlockGetter view, BlockPos pos, BlockState target, ItemStack stack,
                                    LongPredicate unclickable) {
        if (target.getBlock() instanceof FallingBlock && FallingBlock.isFree(player.level().getBlockState(pos.below()))) return false;
        Level level = player.level();
        boolean partnerReady = partnerReady(level, pos, target);
        List<Spot> spots = spots(view, pos, target, Vec3.atCenterOf(pos), Double.POSITIVE_INFINITY, unclickable, null);
        for (boolean crouch : new boolean[] {false, true}) {
            for (float[] look : looks(target)) {
                Vec3 direction = Vec3.directionFromRotation(look[1], look[0]);
                for (Spot spot : spots) {
                    if (!allowed(view, spot, pos, crouch)) continue;
                    if (!throughOpenSide(view, pos, spot.hit().getLocation(), spot.hit().getDirection(), direction)) continue;
                    BlockState placed = simulate(player, pos, stack, spot.hit(), look[0], look[1], crouch, planned(view, level, spot));
                    if (placed != null && BlockRules.placementMatches(placed, target, partnerReady)) return true;
                }
            }
        }
        return false;
    }

    /**
     * Where a helper block would let the block go in when nothing around it does: a hopper facing into open air needs
     * a block there to click, which comes out again once the hopper stands. Null when no spot {@code allowed} lets it,
     * or when the block would need the helper to keep standing.
     */
    public static BlockPos helperFor(LocalPlayer player, BlockPos pos, BlockState target, ItemStack stack, Predicate<BlockPos> allowed) {
        if (!(stack.getItem() instanceof BlockItem item)) return null;
        Level level = player.level();
        if (!level.getBlockState(pos).canBeReplaced()) return null;
        AABB full = new AABB(0, 0, 0, 1, 1, 1);
        for (Direction side : Direction.values()) {
            BlockPos helper = pos.relative(side);
            if (!allowed.test(helper)) continue;
            Direction face = side.getOpposite();
            for (float[] look : looks(target)) {
                Vec3 point = facePoint(helper, full, face, 0.5, 0.5);
                if (!throughOpenSide(level, pos, point, face, Vec3.directionFromRotation(look[1], look[0]))) continue;
                BlockHitResult hit = new BlockHitResult(point, face, helper, false);
                float oldYaw = player.getYRot(), oldPitch = player.getXRot();
                boolean blocks = player.blocksBuilding;
                try {
                    player.setYRot(look[0]);
                    player.setXRot(look[1]);
                    player.blocksBuilding = false;
                    BlockPlaceContext context = new AgainstHelper(player, stack, hit);
                    if (!context.getClickedPos().equals(pos) || !context.canPlace()) continue;
                    BlockState placed = ((BlockItemAccess) item).donut$placementState(context);
                    // Placed against a block that isn't there yet: anything that needs it to stand fails here already.
                    if (placed != null && BlockRules.placementMatches(placed, target, partnerReady(level, pos, target))) return helper;
                } finally {
                    player.setYRot(oldYaw);
                    player.setXRot(oldPitch);
                    player.blocksBuilding = blocks;
                }
            }
        }
        return null;
    }

    /** A click on a block that isn't there yet: the spot in front of its face is where the block goes. */
    private static final class AgainstHelper extends BlockPlaceContext {
        AgainstHelper(LocalPlayer player, ItemStack stack, BlockHitResult hit) {
            super(player.level(), player, InteractionHand.MAIN_HAND, stack, hit);
            replaceClicked = false;
        }
    }

    /**
     * A click on the placed block itself, as a repeater's delay or an open door needs, looked at from {@code eye}: the
     * nearest face in view. Opening a fence gate turns it to face away from the player, so for a gate the look must not
     * come from its far side. It is clicked standing: crouching with something in hand would skip the block's use.
     */
    public static Click use(LocalPlayer player, Vec3 eye, BlockPos pos, double reach, BlockState target) {
        Level level = player.level();
        BlockState state = level.getBlockState(pos);
        VoxelShape shape = state.getShape(level, pos);
        if (shape.isEmpty()) return null;
        AABB box = shape.bounds();
        Direction keep = target.getBlock() instanceof FenceGateBlock ? target.getValue(FenceGateBlock.FACING) : null;
        Click best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Direction face : Direction.values()) {
            for (double a : SPOTS) {
                for (double b : SPOTS) {
                    Vec3 point = facePoint(pos, box, face, a, b);
                    double distance = eye.distanceToSqr(point);
                    if (distance > reach * reach || distance >= bestDistance || !facesEye(face, point, eye)) continue;
                    float[] look = look(eye, point);
                    if (keep != null && Direction.fromYRot(look[0]) == keep.getOpposite()) continue;
                    BlockHitResult seen = seen(level, player, eye, point, face);
                    if (seen == null || !seen.getBlockPos().equals(pos)) continue;
                    best = new Click(seen, look[0], look[1], false);
                    bestDistance = distance;
                }
            }
        }
        return best;
    }

    /**
     * How to pour water or lava into {@code pos} from a bucket, looked at from {@code eye}: a face beside it in view. The
     * server traces the look itself, through water, and pours in front of the first face it meets, so the face must be
     * the first thing on that line.
     */
    public static Click pour(Level level, LocalPlayer player, Vec3 eye, BlockPos pos, Fluid fluid, double reach) {
        Click best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Direction side : Direction.values()) {
            BlockPos clicked = pos.relative(side);
            Direction face = side.getOpposite();
            AABB box = pourFace(level, clicked, fluid, player);
            if (box == null) continue;
            for (double a : SPOTS) {
                for (double b : SPOTS) {
                    Vec3 point = facePoint(clicked, box, face, a, b);
                    double distance = eye.distanceToSqr(point);
                    if (distance > reach * reach || distance >= bestDistance || !facesEye(face, point, eye)) continue;
                    Vec3 end = eye.add(point.subtract(eye).normalize().scale(reach));
                    BlockHitResult seen = level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
                    if (seen.getType() != HitResult.Type.BLOCK || !seen.getBlockPos().equals(clicked) || seen.getDirection() != face) continue;
                    float[] look = look(eye, point);
                    best = new Click(seen, look[0], look[1], false);
                    bestDistance = distance;
                }
            }
        }
        return best;
    }

    /** Whether there is a face next to {@code pos} a bucket could pour against, from wherever the player might stand. */
    public static boolean pourable(LocalPlayer player, BlockPos pos, Fluid fluid) {
        for (Direction side : Direction.values()) if (pourFace(player.level(), pos.relative(side), fluid, player) != null) return true;
        return false;
    }

    /**
     * The outline of a block a bucket can be emptied against, or null: air and water have none, and a block that holds
     * water itself is never clicked. One that takes more, such as a chest, would fill instead of the spot in front of it;
     * one that is full, such as kelp, has the bucket look past it for a spot, which goes by the server's own trace of the
     * look and can pour somewhere else.
     */
    private static AABB pourFace(Level level, BlockPos clicked, Fluid fluid, LocalPlayer player) {
        BlockState state = level.getBlockState(clicked);
        if (state.isAir()) return null;
        if (state.getBlock() instanceof LiquidBlockContainer) return null;
        VoxelShape shape = state.getShape(level, clicked);
        return shape.isEmpty() ? null : shape.bounds();
    }

    /** The first spot in view from {@code eye} that, looked at straight on, places the target, in the given stance. */
    private static Click look(LocalPlayer player, BlockGetter view, Vec3 eye, boolean crouch, BlockPos pos, BlockState target,
                              ItemStack stack, double reach, LongPredicate unclickable) {
        Level level = player.level();
        boolean partnerReady = partnerReady(level, pos, target);
        // The faces to click are those of the view: with blocks not placed yet put in, theirs count too.
        Click fallback = null;
        for (Spot spot : spots(view, pos, target, eye, reach, unclickable, eye)) {
            if (!spot.facesEye() || !allowed(view, spot, pos, crouch)) continue;
            Vec3 point = spot.hit().getLocation();
            BlockHitResult seen = seen(view, player, eye, point, spot.hit().getDirection());
            if (seen == null || !seen.getBlockPos().equals(spot.hit().getBlockPos())) continue;
            float[] look = look(eye, point);
            boolean planned = planned(view, level, spot);
            BlockState placed = simulate(player, pos, stack, seen, look[0], look[1], crouch, planned);
            if (placed == null || !BlockRules.placementMatches(placed, target, partnerReady)) continue;
            Click click = new Click(seen, look[0], look[1], crouch);
            // A look on the edge between two facings, an observer looked at at 45 degrees say, flips with the least
            // drift of the aim: one that keeps its facing a few degrees off either way is taken if there is one.
            if (steady(player, pos, stack, seen, look, crouch, planned, target, partnerReady)) return click;
            if (fallback == null) fallback = click;
        }
        return fallback;
    }

    /**
     * Whether the click places the same block with the look a few degrees off in every direction, and with the spot
     * clicked a little off along the face: a door's hinge, a slab's or a stair's half, goes by which side of the middle
     * of the face the click lands on.
     */
    private static boolean steady(LocalPlayer player, BlockPos pos, ItemStack stack, BlockHitResult hit, float[] look, boolean crouch,
                                  boolean planned, BlockState target, boolean partnerReady) {
        for (float[] off : new float[][] {{STEADY, 0}, {-STEADY, 0}, {0, STEADY}, {0, -STEADY}}) {
            BlockState placed = simulate(player, pos, stack, hit, look[0] + off[0], Math.clamp(look[1] + off[1], -90, 90), crouch, planned);
            if (placed == null || !BlockRules.placementMatches(placed, target, partnerReady)) return false;
        }
        for (Direction along : Direction.values()) {
            if (along.getAxis() == hit.getDirection().getAxis()) continue;
            Vec3 moved = hit.getLocation().add(along.getStepX() * NUDGE, along.getStepY() * NUDGE, along.getStepZ() * NUDGE);
            BlockHitResult nudged = new BlockHitResult(moved, hit.getDirection(), hit.getBlockPos(), hit.isInside());
            BlockState placed = simulate(player, pos, stack, nudged, look[0], look[1], crouch, planned);
            if (placed == null || !BlockRules.placementMatches(placed, target, partnerReady)) return false;
        }
        return true;
    }

    /** How far, in blocks, the spot a click lands on may be off along the face and still place the same block. */
    private static final double NUDGE = 0.06;

    /** How far a look may drift, in degrees, and still give a steady click the same facing. */
    static final float STEADY = 4;

    /** For the log: how many spots around the block each check lets through, looked at from {@code eye}. */
    static String explain(LocalPlayer player, Vec3 eye, boolean crouch, BlockPos pos, BlockState target, ItemStack stack, double reach,
                          LongPredicate unclickable) {
        Level level = player.level();
        int all = 0, facing = 0, allowed = 0, inView = 0, placeable = 0;
        String last = "none";
        for (Spot spot : spots(level, pos, target, eye, reach, unclickable, eye)) {
            all++;
            if (!spot.facesEye()) continue;
            facing++;
            if (!allowed(level, spot, pos, crouch)) continue;
            allowed++;
            BlockHitResult seen = seen(level, player, eye, spot.hit().getLocation(), spot.hit().getDirection());
            if (seen == null || !seen.getBlockPos().equals(spot.hit().getBlockPos())) continue;
            inView++;
            float[] look = look(eye, spot.hit().getLocation());
            BlockState placed = simulate(player, pos, stack, seen, look[0], look[1], crouch, false);
            last = seen.getDirection() + " of " + seen.getBlockPos().toShortString() + " gives " + placed;
            if (placed != null && BlockRules.placementMatches(placed, target, partnerReady(level, pos, target))) placeable++;
        }
        return (crouch ? "crouching: " : "standing: ") + all + " spots, " + facing + " facing, " + allowed + " allowed, " + inView
                + " in view, " + placeable + " right; last " + last;
    }

    /**
     * Whether the spot can be clicked in this stance: a block with a use, a chest or a repeater, only crouching, so it
     * is placed against; the block itself, topped up with a candle or a second slab, only standing.
     */
    private static boolean allowed(BlockGetter level, Spot spot, BlockPos pos, boolean crouch) {
        BlockPos clicked = spot.hit().getBlockPos();
        if (clicked.equals(pos)) return !crouch;
        return crouch || !BlockRules.interactive(level.getBlockState(clicked).getBlock());
    }

    /** What the eye sees looking at {@code point}: the block and face the crosshair is on, or null when it is something else. */
    private static BlockHitResult seen(BlockGetter level, LocalPlayer player, Vec3 eye, Vec3 point, Direction face) {
        // A top is traced on past the face along the look, as the crosshair goes on: the top of a hopper or composter
        // is a bowl, its floor below the outline's top. A side must be met where the outline has it: a look past it to
        // a face further in grazes others on the way, and the slightest drift of the aim lands on one of them.
        Vec3 inside = face == Direction.UP ? point.add(point.subtract(eye).normalize().scale(0.5))
                : point.subtract(face.getStepX() * 0.01, face.getStepY() * 0.01, face.getStepZ() * 0.01);
        BlockHitResult seen = level.clip(new ClipContext(eye, inside, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        if (seen.getType() != HitResult.Type.BLOCK || seen.getDirection() != face) return null;
        return seen;
    }

    /** Whether the spot is on a block the view puts in that the level doesn't have yet. */
    private static boolean planned(BlockGetter view, Level level, Spot spot) {
        BlockPos clicked = spot.hit().getBlockPos();
        return view != level && view.getBlockState(clicked) != level.getBlockState(clicked);
    }

    /** Whether the other half of a double chest stands ready, alone and facing the same way, for this half to join. */
    private static boolean partnerReady(Level level, BlockPos pos, BlockState target) {
        BlockPos partner = BlockRules.chestPartner(pos, target);
        if (partner == null) return false;
        BlockState there = level.getBlockState(partner);
        return there.getBlock() == target.getBlock() && there.getValue(ChestBlock.FACING) == target.getValue(ChestBlock.FACING)
                && there.getValue(ChestBlock.TYPE) == ChestType.SINGLE;
    }

    /**
     * Every spot worth clicking: the faces of the blocks around {@code pos} that face it, and, for a block that takes
     * more of its item, the block itself; the nearest to {@code eye} first, those facing it before the rest.
     */
    private static List<Spot> spots(BlockGetter level, BlockPos pos, BlockState target, Vec3 eye, double reach,
                                    LongPredicate unclickable, Vec3 facing) {
        List<Spot> spots = new ArrayList<>();
        BlockState here = level.getBlockState(pos);
        boolean adding = BlockRules.sameBlock(here.getBlock(), target.getBlock());
        for (Direction side : Direction.values()) {
            if (!unclickable.test(pos.relative(side).asLong())) addSpots(spots, level, pos.relative(side), side.getOpposite(), eye, reach, facing);
            if (adding) addSpots(spots, level, pos, side, eye, reach, facing);
        }
        // Nearest first, give or take: a click doesn't aim at the very same spot, at the very same angle, each time.
        Map<Spot, Double> order = new IdentityHashMap<>();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (Spot spot : spots) order.put(spot, spot.hit().getLocation().distanceToSqr(eye) * (0.8 + 0.4 * random.nextDouble()));
        spots.sort(Comparator.comparing((Spot s) -> !s.facesEye()).thenComparingDouble(order::get));
        return spots;
    }

    private static void addSpots(List<Spot> spots, BlockGetter level, BlockPos clicked, Direction face, Vec3 eye, double reach,
                                 Vec3 facing) {
        BlockState state = level.getBlockState(clicked);
        if (state.isAir()) return;
        VoxelShape shape = state.getShape(level, clicked);
        if (shape.isEmpty()) return;
        AABB box = shape.bounds();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (double a : SPOTS) {
            for (double b : SPOTS) {
                // A little off the same few spots each time, as a hand aims.
                Vec3 point = facePoint(clicked, box, face, a + (random.nextDouble() * 2 - 1) * JITTER, b + (random.nextDouble() * 2 - 1) * JITTER);
                if (eye.distanceToSqr(point) > reach * reach) continue;
                spots.add(new Spot(new BlockHitResult(point, face, clicked, false), facing == null || facesEye(face, point, facing)));
            }
        }
    }

    /** A point on a face of the block's outline, {@code a} and {@code b} of the way across it. */
    private static Vec3 facePoint(BlockPos pos, AABB box, Direction face, double a, double b) {
        Direction.Axis axis = face.getAxis();
        double[] at = new double[3];
        int i = 0;
        for (Direction.Axis other : Direction.Axis.values()) {
            double value;
            if (other == axis) {
                value = face.getAxisDirection() == Direction.AxisDirection.POSITIVE ? box.max(other) : box.min(other);
            } else {
                double fraction = i++ == 0 ? a : b;
                value = box.min(other) + (box.max(other) - box.min(other)) * fraction;
            }
            at[other.ordinal()] = value;
        }
        return new Vec3(pos.getX() + at[0], pos.getY() + at[1], pos.getZ() + at[2]);
    }

    private static boolean facesEye(Direction face, Vec3 point, Vec3 eye) {
        return (eye.x - point.x) * face.getStepX() + (eye.y - point.y) * face.getStepY() + (eye.z - point.z) * face.getStepZ() > 1e-4;
    }

    /**
     * Whether a look along {@code look} could meet {@code point} on a face from somewhere the player might be: it meets
     * the face from the front, and traced back it leaves the space in front of the face, and the spot being placed in
     * if it crosses that, through a side that is open. The spot counts as open, for it is empty until the block goes
     * in. A look up at a block on the ground, which would have to come out of the ground, never can.
     */
    static boolean throughOpenSide(BlockGetter view, BlockPos pos, Vec3 point, Direction face, Vec3 look) {
        if (look.x * face.getStepX() + look.y * face.getStepY() + look.z * face.getStepZ() > -1e-3) return false;
        Vec3 back = look.reverse();
        Vec3 at = point.add(back.scale(1e-4));
        BlockPos cell = BlockPos.containing(at);
        for (int step = 0; step < 3; step++) {
            double nearest = Double.POSITIVE_INFINITY;
            Direction exit = null;
            for (Direction.Axis axis : Direction.Axis.values()) {
                double b = back.get(axis);
                if (Math.abs(b) < 1e-9) continue;
                double edge = b > 0 ? cell.get(axis) + 1 : cell.get(axis);
                double t = (edge - at.get(axis)) / b;
                if (t >= 0 && t < nearest) {
                    nearest = t;
                    exit = Direction.fromAxisAndDirection(axis, b > 0 ? Direction.AxisDirection.POSITIVE : Direction.AxisDirection.NEGATIVE);
                }
            }
            if (exit == null) return false;
            at = at.add(back.scale(nearest + 1e-4));
            cell = cell.relative(exit);
            if (cell.equals(pos)) continue;
            BlockState state = view.getBlockState(cell);
            for (AABB box : state.getShape(view, cell).toAabbs()) if (box.move(cell).contains(at)) return false;
            return true;
        }
        return true;
    }

    /** The yaw and pitch that look from {@code eye} at {@code point}. */
    static float[] look(Vec3 eye, Vec3 point) {
        double dx = point.x - eye.x, dy = point.y - eye.y, dz = point.z - eye.z;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) Math.toDegrees(-Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        return new float[] {yaw, pitch};
    }

    /**
     * Looks in every direction along the axes and between them, and a sign's sixteen: enough to tell whether some
     * place to stand could give the block its facing.
     */
    private static List<float[]> looks(BlockState target) {
        List<float[]> looks = new ArrayList<>();
        for (float pitch : PITCHES) for (float yaw : YAWS) looks.add(new float[] {yaw, pitch});
        if (target.hasProperty(BlockStateProperties.ROTATION_16)) {
            // A sign or banner faces the player: segment r comes from a yaw of r * 22.5 - 180.
            int rotation = target.getValue(BlockStateProperties.ROTATION_16);
            looks.addFirst(new float[] {rotation * 22.5f - 180, 0});
        }
        return looks;
    }

    /**
     * What the item would place for this click, look and stance, by putting the player's look and crouch in place for
     * the moment it takes to ask; nothing is sent. {@code planned}: the block clicked isn't there yet, so the click is
     * asked as one against it, not one into the empty spot it stands in now.
     */
    static BlockState simulate(LocalPlayer player, BlockPos pos, ItemStack stack, BlockHitResult hit, float yaw, float pitch, boolean crouch,
                               boolean planned) {
        if (!(stack.getItem() instanceof BlockItem item)) return null;
        float oldYaw = player.getYRot(), oldPitch = player.getXRot();
        Input oldInput = player.input.keyPresses;
        boolean blocks = player.blocksBuilding;
        try {
            player.setYRot(yaw);
            player.setXRot(pitch);
            if (oldInput.shift() != crouch) player.input.keyPresses = withShift(oldInput, crouch);
            // Where the player stands now is checked apart; asked from another place, their body is not in the way.
            player.blocksBuilding = false;
            BlockPlaceContext context = planned ? new AgainstHelper(player, stack, hit)
                    : new BlockPlaceContext(player, InteractionHand.MAIN_HAND, stack, hit);
            if (!context.canPlace() || !context.getClickedPos().equals(pos)) return null;
            BlockPlaceContext updated = item.updatePlacementContext(context);
            if (updated == null || !updated.getClickedPos().equals(pos)) return null;
            return ((BlockItemAccess) item).donut$placementState(updated);
        } finally {
            player.setYRot(oldYaw);
            player.setXRot(oldPitch);
            player.input.keyPresses = oldInput;
            player.blocksBuilding = blocks;
        }
    }

    private static Input withShift(Input input, boolean shift) {
        return new Input(input.forward(), input.backward(), input.left(), input.right(), input.jump(), shift, input.sprint());
    }

    /**
     * Whether the body stands partly in the spot of a block that would take up some of it, if not the part the body is
     * in: a fence gate or a pane put in beside the player boxes them against it, and a walk planned from the middle of
     * the spot runs into it. The player steps aside first.
     */
    static boolean sharesSpot(AABB body, Level level, BlockPos pos, BlockState target) {
        return !placedForm(target).getCollisionShape(level, pos).isEmpty() && body.intersects(new AABB(pos));
    }

    /**
     * The block as a click first puts it in: a door, trapdoor or fence gate goes in closed and is opened after, so
     * it is in the way of a body as a closed one.
     */
    private static BlockState placedForm(BlockState target) {
        return target.hasProperty(BlockStateProperties.OPEN) ? target.setValue(BlockStateProperties.OPEN, false) : target;
    }

    /** Whether the block, once placed, would take up space a body there stands in. */
    public static boolean inTheWay(AABB body, Level level, BlockPos pos, BlockState target) {
        VoxelShape shape = placedForm(target).getCollisionShape(level, pos);
        if (shape.isEmpty()) return false;
        for (AABB part : shape.toAabbs()) if (part.move(pos).intersects(body)) return true;
        return false;
    }
}
