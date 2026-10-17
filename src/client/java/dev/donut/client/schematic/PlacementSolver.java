package dev.donut.client.schematic;

import dev.donut.client.mixin.BlockItemAccess;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Finds a right click that places a block exactly as the schematic has it. Each candidate, a spot on the face of a
 * block next to it and a direction to look in, is handed to the block's own placement code, which says what it would
 * place; the first that makes the target wins. Looking straight at the spot clicked comes first. Blocks whose facing
 * depends on where the player looks, such as repeaters, pistons and stairs, may then need a look of their own.
 */
public final class PlacementSolver {
    /** Where on a face to click: its middle first, then toward its edges, which top slabs, stairs and door hinges need. */
    private static final double[] SPOTS = {0.5, 0.2, 0.8};
    private static final float[] YAWS = {0, 90, 180, -90};
    private static final float[] PITCHES = {0, 90, -90, 45, -45};

    public enum Problem {
        /** The player stands where the block goes. */
        IN_THE_WAY,
        /** Nothing next to it can hold it yet, or it would fall. */
        NO_SUPPORT,
        /** It can go in, but not from where the player is. */
        OUT_OF_REACH,
        /** In reach, but no face in view gives it the right facing: it needs the player somewhere else. */
        NO_ANGLE
    }

    /**
     * A right click: the spot, the look sent with it, and whether to sneak, so that the block clicked, a chest or a
     * repeater, is placed against instead of used.
     */
    public record Click(BlockHitResult hit, float yaw, float pitch, boolean sneak) { }

    public record Result(Click click, Problem problem) {
        static Result of(Click click) { return new Result(click, null); }

        static Result failed(Problem problem) { return new Result(null, problem); }
    }

    private record Spot(BlockHitResult hit, boolean sneak, boolean facesEye) { }

    private PlacementSolver() { }

    /**
     * How to place {@code target} at {@code pos} with {@code stack} from where the player stands. With {@code strict},
     * only faces the player can see count, looked at straight on.
     */
    public static Result find(LocalPlayer player, BlockPos pos, BlockState target, ItemStack stack, double reach, boolean strict) {
        Level level = player.level();
        if (inTheWay(player, pos, target)) return Result.failed(Problem.IN_THE_WAY);
        if (target.getBlock() instanceof FallingBlock && FallingBlock.isFree(level.getBlockState(pos.below()))) {
            return Result.failed(Problem.NO_SUPPORT);
        }
        Click click = search(player, pos, target, stack, reach, strict);
        if (click != null) return Result.of(click);
        if (!supported(player, pos, target, stack)) return Result.failed(Problem.NO_SUPPORT);
        return Result.failed(strict && search(player, pos, target, stack, reach, false) != null ? Problem.NO_ANGLE : Problem.OUT_OF_REACH);
    }

    /** Whether the block can go in at all yet, from wherever the player might stand. */
    public static boolean supported(LocalPlayer player, BlockPos pos, BlockState target, ItemStack stack) {
        if (target.getBlock() instanceof FallingBlock && FallingBlock.isFree(player.level().getBlockState(pos.below()))) return false;
        return search(player, pos, target, stack, Double.POSITIVE_INFINITY, false) != null;
    }

    /** A click on the placed block itself, as a repeater's delay or an open door needs: the face the player sees best. */
    public static Click use(LocalPlayer player, BlockPos pos, double reach) {
        Level level = player.level();
        BlockState state = level.getBlockState(pos);
        VoxelShape shape = state.getShape(level, pos);
        if (shape.isEmpty()) return null;
        Vec3 eye = player.getEyePosition();
        AABB box = shape.bounds();
        Click best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Direction face : Direction.values()) {
            Vec3 point = facePoint(pos, box, face, 0.5, 0.5);
            double distance = eye.distanceToSqr(point);
            if (distance > reach * reach || !facesEye(face, point, eye)) continue;
            if (distance < bestDistance) {
                float[] look = look(eye, point);
                best = new Click(new BlockHitResult(point, face, pos, false), look[0], look[1], false);
                bestDistance = distance;
            }
        }
        return best;
    }

    private static Click search(LocalPlayer player, BlockPos pos, BlockState target, ItemStack stack, double reach, boolean strict) {
        Vec3 eye = player.getEyePosition();
        List<Spot> spots = spots(player.level(), pos, target, eye, reach);
        if (spots.isEmpty()) return null;
        for (Spot spot : spots) {
            if (strict && !visible(player, spot.hit)) continue;
            float[] look = look(eye, spot.hit.getLocation());
            if (makes(player, pos, target, stack, spot, look[0], look[1])) return new Click(spot.hit, look[0], look[1], spot.sneak);
        }
        if (strict) return null;
        for (float[] look : looks(target)) {
            for (Spot spot : spots) {
                if (makes(player, pos, target, stack, spot, look[0], look[1])) return new Click(spot.hit, look[0], look[1], spot.sneak);
            }
        }
        return null;
    }

    /**
     * Every spot worth clicking: the faces of the blocks around {@code pos} that face it, and, for a block that takes
     * more of its item, the block itself. Faces that need no sneaking come first, then those facing the player, then the
     * nearest.
     */
    private static List<Spot> spots(Level level, BlockPos pos, BlockState target, Vec3 eye, double reach) {
        List<Spot> spots = new ArrayList<>();
        BlockState here = level.getBlockState(pos);
        boolean adding = here.getBlock() == target.getBlock();
        for (Direction side : Direction.values()) {
            addSpots(spots, level, pos.relative(side), side.getOpposite(), eye, reach, false);
            if (adding) addSpots(spots, level, pos, side, eye, reach, true);
        }
        spots.sort(Comparator.comparing((Spot s) -> s.sneak).thenComparing(s -> !s.facesEye)
                .thenComparingDouble(s -> s.hit.getLocation().distanceToSqr(eye)));
        return spots;
    }

    private static void addSpots(List<Spot> spots, Level level, BlockPos clicked, Direction face, Vec3 eye, double reach, boolean self) {
        BlockState state = level.getBlockState(clicked);
        if (state.isAir()) return;
        VoxelShape shape = state.getShape(level, clicked);
        if (shape.isEmpty()) return;
        AABB box = shape.bounds();
        // Adding a candle or a second slab clicks the block itself, which must be used as an item is, without sneaking.
        boolean sneak = !self && BlockRules.interactive(state.getBlock());
        for (double a : SPOTS) {
            for (double b : SPOTS) {
                Vec3 point = facePoint(clicked, box, face, a, b);
                if (eye.distanceToSqr(point) > reach * reach) continue;
                spots.add(new Spot(new BlockHitResult(point, face, clicked, false), sneak, facesEye(face, point, eye)));
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

    /** Whether nothing stands between the eye and the spot, so the face clicked is the one the player sees there. */
    private static boolean visible(LocalPlayer player, BlockHitResult spot) {
        Vec3 eye = player.getEyePosition(), point = spot.getLocation();
        Direction face = spot.getDirection();
        if (!facesEye(face, point, eye)) return false;
        Vec3 inside = point.subtract(face.getStepX() * 0.01, face.getStepY() * 0.01, face.getStepZ() * 0.01);
        BlockHitResult seen = player.level().clip(new ClipContext(eye, inside, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        return seen.getType() == HitResult.Type.BLOCK && seen.getBlockPos().equals(spot.getBlockPos()) && seen.getDirection() == face;
    }

    /** The yaw and pitch that look from {@code eye} at {@code point}. */
    static float[] look(Vec3 eye, Vec3 point) {
        double dx = point.x - eye.x, dy = point.y - eye.y, dz = point.z - eye.z;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) Math.toDegrees(-Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        return new float[] {yaw, pitch};
    }

    /** Looks to try when looking at the spot doesn't give the facing: each way along the axes, and a sign's sixteen. */
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

    private static boolean makes(LocalPlayer player, BlockPos pos, BlockState target, ItemStack stack, Spot spot, float yaw, float pitch) {
        BlockState placed = simulate(player, pos, stack, spot, yaw, pitch);
        return placed != null && BlockRules.placementMatches(placed, target);
    }

    /**
     * What the item would place for this click and look, by putting the player's look and sneaking in place for the
     * moment it takes to ask; nothing is sent.
     */
    static BlockState simulate(LocalPlayer player, BlockPos pos, ItemStack stack, Spot spot, float yaw, float pitch) {
        if (!(stack.getItem() instanceof BlockItem item)) return null;
        float oldYaw = player.getYRot(), oldPitch = player.getXRot();
        Input oldInput = player.input.keyPresses;
        try {
            player.setYRot(yaw);
            player.setXRot(pitch);
            if (oldInput.shift() != spot.sneak) player.input.keyPresses = withShift(oldInput, spot.sneak);
            BlockPlaceContext context = new BlockPlaceContext(player, InteractionHand.MAIN_HAND, stack, spot.hit);
            if (!context.canPlace() || !context.getClickedPos().equals(pos)) return null;
            BlockPlaceContext updated = item.updatePlacementContext(context);
            if (updated == null || !updated.getClickedPos().equals(pos)) return null;
            return ((BlockItemAccess) item).donut$placementState(updated);
        } finally {
            player.setYRot(oldYaw);
            player.setXRot(oldPitch);
            player.input.keyPresses = oldInput;
        }
    }

    static Input withShift(Input input, boolean shift) {
        return new Input(input.forward(), input.backward(), input.left(), input.right(), input.jump(), shift, input.sprint());
    }

    /** Whether the block, once placed, would take up space the player stands in. */
    public static boolean inTheWay(LocalPlayer player, BlockPos pos, BlockState target) {
        VoxelShape shape = target.getCollisionShape(player.level(), pos);
        if (shape.isEmpty()) return false;
        AABB body = player.getBoundingBox();
        for (AABB part : shape.toAabbs()) if (part.move(pos).intersects(body)) return true;
        return false;
    }
}
