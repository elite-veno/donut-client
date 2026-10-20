package dev.donut.client.schematic;

import dev.donut.client.mixin.BlockItemAccess;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongPredicate;
import java.util.function.Predicate;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
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
    /**
     * Spots close to a face's edges, tried when none of {@link #SPOTS} is in view though some face the eye, from close by:
     * a look past the top edge of the block underfoot, down a shaft beside it, reaches only the far edge of a face down
     * there, as kelp 9,70,4 of ikea_v4 from the glass 9,72,3 north of it.
     */
    private static final double[] EDGES = {0.12, 0.5, 0.88};
    /** How near the eye, to the middle of the block placed, the spots by the edges are tried: a look grazing an edge close by. */
    private static final double EDGE_RANGE = 3.0;
    /** Whether the last look over the usual spots found one facing the eye, allowed, but out of view. */
    private static boolean hiddenLast;
    /** How far, as a share of the face, a spot aimed at may be off one of those. */
    private static final double JITTER = 0.06;
    /**
     * How far a spot aimed at for real may be off one of those: up to half their spacing, so the clicks spread over the
     * whole face instead of bunching on nine points. Plans keep to {@link #JITTER}, so where to stand stays the same.
     */
    private static volatile double spread = 0.15;
    /**
     * Picks for the clicks on a block itself (a repeater set, a box opened, a block mined) and for pours: which spot, a
     * little off the grid, stays the same until the next decision, for a block mined is solved again each tick.
     */
    private static volatile long hand = ThreadLocalRandom.current().nextLong();

    /** How far a real aim may be off the grid of spots, as a share of the face (see {@link #spread}). */
    public static void spread(double share) { spread = Math.clamp(share, JITTER, 0.15); }

    /** A new hand for the next decision: other spots on a block clicked itself, or poured against. */
    public static void newHand() { hand = ThreadLocalRandom.current().nextLong(); }
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
        // From where the player stands, the spot nearest the crosshair first: the hand moves no further than it must.
        Vec3 view = player.getViewVector(1.0f);
        Click click = look(player, level, player.getEyePosition(), crouching, pos, target, stack, reach, unclickable, view);
        if (click != null) return Result.of(click);
        Vec3 feet = player.position();
        Vec3 otherEye = feet.add(0, crouching ? STAND_EYE : CROUCH_EYE, 0);
        click = look(player, level, otherEye, !crouching, pos, target, stack, reach, unclickable, view);
        if (click != null) return new Result(click, Problem.STANCE);
        return Result.failed(supported(player, pos, target, stack, unclickable) ? Problem.OUT_OF_REACH : Problem.NO_SUPPORT);
    }

    /** A click that would place the block from a place the player might stand, with the feet at {@code feet}, either stance. */
    public static Click fromSpot(LocalPlayer player, Vec3 feet, BlockPos pos, BlockState target, ItemStack stack, double reach,
                                 LongPredicate unclickable) {
        return fromSpot(player, player.level(), feet, pos, target, stack, reach, unclickable);
    }

    /**
     * As {@link #fromSpot}, standing only, from a body held at {@code feet} wherever it is: swimming in still water, as at the
     * top of a kelp channel, where nothing is under the feet. Null when the body is in a block's way there.
     */
    public static Click fromSwim(LocalPlayer player, BlockGetter view, Vec3 feet, BlockPos pos, BlockState target, ItemStack stack,
                                 double reach, LongPredicate unclickable) {
        Level level = player.level();
        AABB body = new AABB(feet.x - 0.3, feet.y, feet.z - 0.3, feet.x + 0.3, feet.y + 1.8, feet.z + 0.3);
        if (inTheWay(body, level, pos, target) || !level.noCollision(body)) return null;
        return look(player, view, feet.add(0, STAND_EYE, 0), false, pos, target, stack, reach, unclickable, null);
    }

    /** As the other {@code fromSpot}, with the looks traced through {@code view}: from a pit not dug yet, say. */
    public static Click fromSpot(LocalPlayer player, BlockGetter view, Vec3 feet, BlockPos pos, BlockState target, ItemStack stack,
                                 double reach, LongPredicate unclickable) {
        AABB body = new AABB(feet.x - 0.3, feet.y, feet.z - 0.3, feet.x + 0.3, feet.y + 1.8, feet.z + 0.3);
        if (inTheWay(body, player.level(), pos, target)) return null;
        Click click = look(player, view, feet.add(0, STAND_EYE, 0), false, pos, target, stack, reach, unclickable, null);
        return click != null ? click : look(player, view, feet.add(0, CROUCH_EYE, 0), true, pos, target, stack, reach, unclickable, null);
    }

    /** As {@link #fromSpot}, crouched only: on a narrow top over a drop, where the player stays crouched to place. */
    public static Click fromSpotCrouched(LocalPlayer player, Vec3 feet, BlockPos pos, BlockState target, ItemStack stack, double reach,
                                         LongPredicate unclickable) {
        AABB body = new AABB(feet.x - 0.3, feet.y, feet.z - 0.3, feet.x + 0.3, feet.y + 1.5, feet.z + 0.3);
        if (inTheWay(body, player.level(), pos, target)) return null;
        return look(player, player.level(), feet.add(0, CROUCH_EYE, 0), true, pos, target, stack, reach, unclickable, null);
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
        return supported(player, view, pos, target, stack, unclickable, looks(target));
    }

    /**
     * As {@code supported}, with looks every 15 degrees: a block that can go in only with a look slanting between the
     * axes, past a neighbor in the way, is found too. Several times the work, so only to make sure before holding a
     * block back for it.
     */
    public static boolean supportedFinely(LocalPlayer player, BlockGetter view, BlockPos pos, BlockState target, ItemStack stack,
                                          LongPredicate unclickable) {
        List<float[]> looks = new ArrayList<>();
        for (int pitch = -75; pitch <= 75; pitch += 15) for (int yaw = -180; yaw < 180; yaw += 15) looks.add(new float[] {yaw, pitch});
        looks.add(new float[] {0, 89});
        looks.add(new float[] {0, -89});
        return supported(player, view, pos, target, stack, unclickable, looks);
    }

    private static boolean supported(LocalPlayer player, BlockGetter view, BlockPos pos, BlockState target, ItemStack stack,
                                     LongPredicate unclickable, List<float[]> looks) {
        if (target.getBlock() instanceof FallingBlock && FallingBlock.isFree(player.level().getBlockState(pos.below()))) return false;
        Level level = player.level();
        boolean partnerReady = partnerReady(level, pos, target);
        List<Spot> spots = spots(view, pos, target, Vec3.atCenterOf(pos), Double.POSITIVE_INFINITY, unclickable, null, null, JITTER);
        for (boolean crouch : new boolean[] {false, true}) {
            for (float[] look : looks) {
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
     * or when the block would need the helper to keep standing. The helper itself goes in against a block beside it that
     * isn't {@code unclickable}, as the clicks that put helpers down never take another helper block.
     */
    public static BlockPos helperFor(LocalPlayer player, BlockPos pos, BlockState target, ItemStack stack, Predicate<BlockPos> allowed,
                                     LongPredicate unclickable) {
        if (!(stack.getItem() instanceof BlockItem item)) return null;
        Level level = player.level();
        if (!level.getBlockState(pos).canBeReplaced()) return null;
        AABB full = new AABB(0, 0, 0, 1, 1, 1);
        for (Direction side : Direction.values()) {
            BlockPos helper = pos.relative(side);
            if (!allowed.test(helper) || !standsAgainst(level, helper, pos, unclickable)) continue;
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

    /**
     * Whether a helper block at {@code helper} can go in itself: something beside it, other than the spot {@code pos} it
     * is for, to click. One in the air helps nothing, as nothing places it.
     */
    private static boolean standsAgainst(Level level, BlockPos helper, BlockPos pos, LongPredicate unclickable) {
        for (Direction side : Direction.values()) {
            BlockPos beside = helper.relative(side);
            if (!beside.equals(pos) && !level.getBlockState(beside).canBeReplaced() && !unclickable.test(beside.asLong())) return true;
        }
        return false;
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
        Direction keep = target.getBlock() instanceof FenceGateBlock ? target.getValue(FenceGateBlock.FACING) : null;
        return onBlock(player, eye, pos, reach, keep, false, false);
    }

    /**
     * How to use a hoe or a shovel on the dirt at {@code pos}: the nearest spot in view on any face but its bottom, which
     * neither tool works on.
     */
    public static Click till(LocalPlayer player, Vec3 eye, BlockPos pos, double reach) {
        return onBlock(player, eye, pos, reach, null, false, true);
    }

    /**
     * How to pour water from a bucket into the block at {@code pos} itself, a slab or leaves the schematic has full of
     * water, looked at from {@code eye}: the nearest spot on it in view. The bucket fills a block that takes water where
     * its trace meets it, whichever face that is. It is clicked standing: crouched, the bucket pours in front of it.
     */
    public static Click fill(LocalPlayer player, Vec3 eye, BlockPos pos, double reach) {
        return onBlock(player, eye, pos, reach, null, false, false);
    }

    /**
     * The nearest spot in view on the block at {@code pos}, with no look from the far side of {@code keep}, and none on its
     * bottom with {@code notBottom}.
     */
    private static Click onBlock(LocalPlayer player, Vec3 eye, BlockPos pos, double reach, Direction keep, boolean crouch,
                                 boolean notBottom) {
        Level level = player.level();
        BlockState state = level.getBlockState(pos);
        VoxelShape shape = state.getShape(level, pos);
        if (shape.isEmpty()) return null;
        AABB box = shape.bounds();
        Click best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Direction face : Direction.values()) {
            if (notBottom && face == Direction.DOWN) continue;
            for (double a : SPOTS) {
                for (double b : SPOTS) {
                    long seed = seed(pos, face, a, b);
                    Vec3 point = facePoint(pos, box, face, a + wobble(seed) * JITTER, b + wobble(seed + 1) * JITTER);
                    double away = eye.distanceToSqr(point);
                    // The nearest, give or take, as the spot itself: not the very same one from the same place each time.
                    double distance = away * (0.8 + 0.2 * (wobble(seed + 2) + 1));
                    if (away > reach * reach || distance >= bestDistance || !facesEye(face, point, eye)) continue;
                    float[] look = look(eye, point);
                    if (keep != null && Direction.fromYRot(look[0]) == keep.getOpposite()) continue;
                    BlockHitResult seen = seen(level, player, eye, point, face);
                    if (seen == null || !seen.getBlockPos().equals(pos)) continue;
                    best = new Click(seen, look[0], look[1], crouch);
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
                    long seed = seed(clicked, face, a, b);
                    Vec3 point = facePoint(clicked, box, face, a + wobble(seed) * JITTER, b + wobble(seed + 1) * JITTER);
                    double away = eye.distanceToSqr(point);
                    double distance = away * (0.8 + 0.2 * (wobble(seed + 2) + 1));
                    if (away > reach * reach || distance >= bestDistance || !facesEye(face, point, eye)) continue;
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

    /**
     * The first spot in view from {@code eye} that, looked at straight on, places the target, in the given stance; with
     * {@code aim}, the way the player looks now, the spots nearest the crosshair are tried first.
     */
    private static Click look(LocalPlayer player, BlockGetter view, Vec3 eye, boolean crouch, BlockPos pos, BlockState target,
                              ItemStack stack, double reach, LongPredicate unclickable, Vec3 aim) {
        double wide = aim == null ? JITTER : spread;
        Click click = look(player, view, eye, crouch, pos, target, stack, reach, unclickable, aim, wide, SPOTS);
        boolean hidden = hiddenLast;
        // Spread wide, a block whose only steady spots are close to the grid (a slab's half, a hinge) may find none.
        if (click == null && wide > JITTER) {
            click = look(player, view, eye, crouch, pos, target, stack, reach, unclickable, aim, JITTER, SPOTS);
            hidden |= hiddenLast;
        }
        // Faces turned to the eye but hidden, close by: the spots by their edges, past whatever hides the middles.
        if (click == null && hidden && eye.distanceToSqr(Vec3.atCenterOf(pos)) < EDGE_RANGE * EDGE_RANGE) {
            click = look(player, view, eye, crouch, pos, target, stack, reach, unclickable, aim, JITTER, EDGES);
        }
        return click;
    }

    private static Click look(LocalPlayer player, BlockGetter view, Vec3 eye, boolean crouch, BlockPos pos, BlockState target,
                              ItemStack stack, double reach, LongPredicate unclickable, Vec3 aim, double jitter, double[] grid) {
        Level level = player.level();
        boolean partnerReady = partnerReady(level, pos, target);
        // The faces to click are those of the view: with blocks not placed yet put in, theirs count too.
        Click fallback = null, grazing = null;
        hiddenLast = false;
        for (Spot spot : spots(view, pos, target, eye, reach, unclickable, eye, aim, jitter, grid)) {
            if (!spot.facesEye() || !allowed(view, spot, pos, crouch)) continue;
            Vec3 point = spot.hit().getLocation();
            BlockHitResult seen = seen(view, player, eye, point, spot.hit().getDirection());
            if (seen == null || !seen.getBlockPos().equals(spot.hit().getBlockPos())) {
                hiddenLast = true;
                continue;
            }
            float[] look = look(eye, point);
            boolean planned = planned(view, level, spot);
            BlockState placed = simulate(player, pos, stack, seen, look[0], look[1], crouch, planned);
            if (placed == null || !BlockRules.placementMatches(placed, target, partnerReady)) continue;
            Click click = new Click(seen, look[0], look[1], crouch);
            // A look on the edge between two facings, an observer looked at at 45 degrees say, flips with the least
            // drift of the aim: one that keeps its facing a few degrees off either way is taken if there is one, else
            // one that keeps it at least a little off. One right on the edge never: the server may make the other
            // facing of it.
            if (steady(player, pos, stack, seen, look, crouch, planned, target, partnerReady, STEADY, NUDGE)) {
                // Aimed for real, a spot the look only just reaches past an edge is kept for last: the crosshair a hair
                // off lands on the edge instead, and the click is aimed all over again.
                if (aim == null || clear(view, player, eye, look, seen)) return click;
                if (grazing == null) grazing = click;
                continue;
            }
            if (fallback == null && steady(player, pos, stack, seen, look, crouch, planned, target, partnerReady, MARGIN, NUDGE / 2)) {
                fallback = click;
            }
        }
        return grazing != null ? grazing : fallback;
    }

    /** How far off a look may be, in degrees, and still meet the face it is aimed at: about two mouse steps. */
    private static final float GRAZE = 0.25f;

    /** Whether looks {@link #GRAZE} off either way, and up or down, still meet the same face of the same block. */
    private static boolean clear(BlockGetter level, LocalPlayer player, Vec3 eye, float[] look, BlockHitResult seen) {
        double range = player.blockInteractionRange();
        for (float[] off : new float[][] {{GRAZE, 0}, {-GRAZE, 0}, {0, GRAZE}, {0, -GRAZE}}) {
            Vec3 direction = Vec3.directionFromRotation(Math.clamp(look[1] + off[1], -90, 90), look[0] + off[0]);
            BlockHitResult hit = level.clip(new ClipContext(eye, eye.add(direction.scale(range)), ClipContext.Block.OUTLINE,
                    ClipContext.Fluid.NONE, player));
            if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(seen.getBlockPos()) || hit.getDirection() != seen.getDirection()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether the click places the same block with the look a few degrees off in every direction, and with the spot
     * clicked a little off along the face: a door's hinge, a slab's or a stair's half, goes by which side of the middle
     * of the face the click lands on.
     */
    private static boolean steady(LocalPlayer player, BlockPos pos, ItemStack stack, BlockHitResult hit, float[] look, boolean crouch,
                                  boolean planned, BlockState target, boolean partnerReady, float drift, double nudge) {
        if (!keeps(player, pos, stack, hit, look, crouch, planned, target, partnerReady, drift)) return false;
        for (Direction along : Direction.values()) {
            if (along.getAxis() == hit.getDirection().getAxis()) continue;
            Vec3 moved = hit.getLocation().add(along.getStepX() * nudge, along.getStepY() * nudge, along.getStepZ() * nudge);
            BlockHitResult nudged = new BlockHitResult(moved, hit.getDirection(), hit.getBlockPos(), hit.isInside());
            BlockState placed = simulate(player, pos, stack, nudged, look[0], look[1], crouch, planned);
            if (placed == null || !BlockRules.placementMatches(placed, target, partnerReady)) return false;
        }
        return true;
    }

    /**
     * Whether the click places the same block with the look {@code drift} degrees off to either side and up or down: the
     * look keeps that far from an edge between two facings.
     */
    static boolean keeps(LocalPlayer player, BlockPos pos, ItemStack stack, BlockHitResult hit, float[] look, boolean crouch,
                         boolean planned, BlockState target, boolean partnerReady, float drift) {
        for (float[] off : new float[][] {{drift, 0}, {-drift, 0}, {0, drift}, {0, -drift}}) {
            BlockState placed = simulate(player, pos, stack, hit, look[0] + off[0], Math.clamp(look[1] + off[1], -90, 90), crouch, planned);
            if (placed == null || !BlockRules.placementMatches(placed, target, partnerReady)) return false;
        }
        return true;
    }

    /** How far, in blocks, the spot a click lands on may be off along the face and still place the same block. */
    private static final double NUDGE = 0.06;

    /** Degrees within which spots by the crosshair count as about as near, and are tried in no set order. */
    private static final double AIM_MIX = 3;

    /** How far a look may drift, in degrees, and still give a steady click the same facing. */
    static final float STEADY = 4;
    /**
     * The least, in degrees, a look clicked with keeps from an edge between two facings: more than a hand's last mouse
     * step, or a look the server has a hair otherwise, so what goes in is what was aimed for.
     */
    static final float MARGIN = 1.5f;

    /** For the log: how many spots around the block each check lets through, looked at from {@code eye}. */
    static String explain(LocalPlayer player, Vec3 eye, boolean crouch, BlockPos pos, BlockState target, ItemStack stack, double reach,
                          LongPredicate unclickable) {
        Level level = player.level();
        int all = 0, facing = 0, allowed = 0, inView = 0, placeable = 0;
        String last = "none", hidden = null;
        for (Spot spot : spots(level, pos, target, eye, reach, unclickable, eye, null, JITTER)) {
            all++;
            if (!spot.facesEye()) continue;
            facing++;
            if (!allowed(level, spot, pos, crouch)) continue;
            allowed++;
            BlockHitResult seen = seen(level, player, eye, spot.hit().getLocation(), spot.hit().getDirection());
            if (seen == null || !seen.getBlockPos().equals(spot.hit().getBlockPos())) {
                // The first face out of view, and what the look at it meets instead.
                if (hidden == null) {
                    BlockHitResult met = level.clip(new ClipContext(eye, spot.hit().getLocation(), ClipContext.Block.OUTLINE,
                            ClipContext.Fluid.NONE, player));
                    hidden = spot.hit().getDirection() + " of " + spot.hit().getBlockPos().toShortString() + " hidden by "
                            + (met.getType() == HitResult.Type.BLOCK ? met.getDirection() + " of " + level.getBlockState(met.getBlockPos())
                            + " at " + met.getBlockPos().toShortString() : "nothing, met at a slant");
                }
                continue;
            }
            inView++;
            float[] look = look(eye, spot.hit().getLocation());
            BlockState placed = simulate(player, pos, stack, seen, look[0], look[1], crouch, false);
            last = seen.getDirection() + " of " + seen.getBlockPos().toShortString() + " gives " + placed;
            if (placed != null && BlockRules.placementMatches(placed, target, partnerReady(level, pos, target))) placeable++;
        }
        return (crouch ? "crouching: " : "standing: ") + all + " spots, " + facing + " facing, " + allowed + " allowed, " + inView
                + " in view, " + placeable + " right; last " + last + (hidden == null ? "" : "; " + hidden);
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
     * more of its item, the block itself; those facing it before the rest, and of those the nearest to {@code eye}
     * first, or, with {@code aim}, the way the eye looks, those the least turn away.
     */
    private static List<Spot> spots(BlockGetter level, BlockPos pos, BlockState target, Vec3 eye, double reach,
                                    LongPredicate unclickable, Vec3 facing, Vec3 aim, double jitter) {
        return spots(level, pos, target, eye, reach, unclickable, facing, aim, jitter, SPOTS);
    }

    /** As the other {@code spots}, at the shares across each face {@code grid} gives. */
    private static List<Spot> spots(BlockGetter level, BlockPos pos, BlockState target, Vec3 eye, double reach,
                                    LongPredicate unclickable, Vec3 facing, Vec3 aim, double jitter, double[] grid) {
        List<Spot> spots = new ArrayList<>();
        BlockState here = level.getBlockState(pos);
        boolean adding = BlockRules.sameBlock(here.getBlock(), target.getBlock());
        for (Direction side : Direction.values()) {
            if (!unclickable.test(pos.relative(side).asLong())) addSpots(spots, level, pos.relative(side), side.getOpposite(), eye, reach, facing, jitter, grid);
            if (adding) addSpots(spots, level, pos, side, eye, reach, facing, jitter, grid);
        }
        // Nearest first, give or take: a click doesn't aim at the very same spot, at the very same angle, each time. By
        // the turn, spots a few degrees apart are as near as each other, so the nearest of them isn't always the one.
        Map<Spot, Double> order = new IdentityHashMap<>();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (Spot spot : spots) {
            Vec3 to = spot.hit().getLocation().subtract(eye);
            double away = aim == null ? to.lengthSqr() * (0.8 + 0.4 * random.nextDouble())
                    : Math.toDegrees(Math.acos(Mth.clamp(aim.dot(to.normalize()), -1, 1))) * (0.8 + 0.4 * random.nextDouble())
                            + AIM_MIX * random.nextDouble();
            order.put(spot, away);
        }
        spots.sort(Comparator.comparing((Spot s) -> !s.facesEye()).thenComparingDouble(order::get));
        return spots;
    }

    private static void addSpots(List<Spot> spots, BlockGetter level, BlockPos clicked, Direction face, Vec3 eye, double reach,
                                 Vec3 facing, double jitter, double[] grid) {
        BlockState state = level.getBlockState(clicked);
        if (state.isAir()) return;
        VoxelShape shape = state.getShape(level, clicked);
        if (shape.isEmpty()) return;
        AABB box = shape.bounds();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (double a : grid) {
            for (double b : grid) {
                // A little off the same few spots each time, as a hand aims.
                Vec3 point = facePoint(clicked, box, face, a + (random.nextDouble() * 2 - 1) * jitter, b + (random.nextDouble() * 2 - 1) * jitter);
                if (eye.distanceToSqr(point) > reach * reach) continue;
                spots.add(new Spot(new BlockHitResult(point, face, clicked, false), facing == null || facesEye(face, point, facing)));
            }
        }
    }

    /** The seed of one spot on a face for the current {@link #hand}. */
    private static long seed(BlockPos pos, Direction face, double a, double b) {
        return hand ^ pos.asLong() * 0x9E3779B97F4A7C15L ^ (face.ordinal() * 31L + Math.round(a * 10) * 7L + Math.round(b * 10)) * 0xBF58476D1CE4E5B9L;
    }

    /** A number from -1 to 1, the same for the same seed. */
    private static double wobble(long seed) { return new SplittableRandom(seed).nextDouble() * 2 - 1; }

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
