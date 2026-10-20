package dev.donut.client.miner;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Picks the next block to break: one in reach, on a face in view, whose swing breaks nothing it must not. */
public final class Swings {
    /** Blocks looked at for one choice, nearest first; enough for a swing in every direction. */
    private static final int CANDIDATES = 40;

    private Swings() { }

    /**
     * A swing: the block hit, the face hit, the point looked at, and the blocks it should break.
     *
     * @param area   whether it is meant for the area pickaxe
     * @param clears how many of the wanted blocks it breaks
     */
    public record Swing(BlockPos target, Direction face, Vec3 aim, float yaw, float pitch, boolean area, int clears, double score) { }

    /** Where the player is and looks, for one choice. */
    public record Reach(Vec3 eye, AABB body, int feetY, double reach, double spread, boolean vary, Random random, float yaw, float pitch) {
        public Reach(Vec3 eye, AABB body, int feetY, double reach, double spread, boolean vary, Random random) {
            this(eye, body, feetY, reach, spread, vary, random, 0, 0);
        }
    }

    /**
     * The best swing with {@code tool} at one of the wanted blocks, or null.
     *
     * @param guarded   blocks a swing must not break: the floor, steps' floors
     * @param counts    blocks worth breaking besides the wanted ones; others broken are a small cost
     * @param blocked   gets wanted blocks found in reach and in view whose every swing would let water or lava in
     */
    public static Swing choose(Site site, Reach at, ToolShape tool, Collection<BlockPos> wanted, Predicate<BlockPos> guarded,
                               Predicate<BlockPos> counts, Set<BlockPos> blocked) {
        return choose(site, at, tool, wanted, guarded, counts, blocked, Integer.MIN_VALUE);
    }

    /**
     * As {@link #choose(Site, Reach, ToolShape, Collection, Predicate, Predicate, Set)}, on a band whose lowest layer is
     * {@code bottom}: an area swing that takes the block over one of that layer's blocks and leaves the block itself
     * costs a lot, since an area pickaxe can then only reach it with a look steeper than it can give from beside it.
     */
    public static Swing choose(Site site, Reach at, ToolShape tool, Collection<BlockPos> wanted, Predicate<BlockPos> guarded,
                               Predicate<BlockPos> counts, Set<BlockPos> blocked, int bottom) {
        Set<BlockPos> wantedSet = wanted instanceof Set<BlockPos> s ? s : new HashSet<>(wanted);
        double limit = at.reach() + 0.9;
        List<BlockPos> near = new ArrayList<>();
        for (BlockPos pos : wanted) {
            if (Vec3.atCenterOf(pos).distanceToSqr(at.eye()) <= limit * limit && site.is(pos, Site.DIG)) near.add(pos);
        }
        near.sort(Comparator.comparingDouble(p -> Vec3.atCenterOf(p).distanceToSqr(at.eye())));
        Swing best = null;
        for (BlockPos pos : near.subList(0, Math.min(CANDIDATES, near.size()))) {
            boolean seen = false, wet = false;
            for (Direction face : Direction.values()) {
                int beside = site.flags(pos.relative(face));
                if ((beside & Site.CLEAR) == 0) continue;
                Vec3 middle = faceMiddle(pos, face);
                if (at.eye().subtract(middle).dot(Vec3.atLowerCornerOf(face.getUnitVec3i())) <= 0) continue;
                List<BlockPos> cells = tool.cells(pos, face);
                Verdict verdict = check(site, at, cells, guarded);
                if (verdict == Verdict.WET) wet = true;
                if (verdict != Verdict.OK) continue;
                Aim aim = aim(site, at, pos, face, tool.area());
                if (aim == null) continue;
                seen = true;
                int clears = 0, extra = 0, stranded = 0;
                for (BlockPos cell : cells) {
                    if (!site.is(cell, Site.DIG)) continue;
                    if (wantedSet.contains(cell)) clears++;
                    else if (!counts.test(cell)) extra++;
                    if (tool.area() && cell.getY() == bottom + 1 && site.is(cell.below(), Site.DIG) && !cells.contains(cell.below())
                            && wantedSet.contains(cell.below())) stranded++;
                }
                // A block near where the camera already points is quick to turn to, the way a hand sweeps along a wall.
                double turn = Math.hypot(Mth.wrapDegrees(aim.yaw - at.yaw()), aim.pitch - at.pitch());
                double score = clears * 10 - extra * 4 - stranded * 25 - Math.sqrt(Vec3.atCenterOf(pos).distanceToSqr(at.eye())) * 0.6
                        - turn * 0.05 + (at.vary() ? at.random().nextDouble() * 4 : 0);
                if (best == null || score > best.score()) {
                    best = new Swing(pos, face, aim.point, aim.yaw, aim.pitch, tool.area(), clears, score);
                }
            }
            if (!seen && wet) blocked.add(pos);
        }
        return best;
    }

    private enum Verdict { OK, GUARDED, WET, FALLS, HARD }

    /** Whether a swing breaking these blocks is safe. */
    private static Verdict check(Site site, Reach at, List<BlockPos> cells, Predicate<BlockPos> guarded) {
        for (BlockPos cell : cells) {
            int flags = site.flags(cell);
            if ((flags & Site.UNLOADED) != 0) return Verdict.HARD;
            if ((flags & Site.DIG) == 0) {
                // A container caught by an area swing would spill; bedrock just stays.
                if ((flags & Site.HARD) != 0 && cell != cells.getFirst()) return Verdict.HARD;
                // A block holding water, caught by an area swing, is broken and leaves its water in the room.
                if ((flags & Site.FLUID) != 0) return Verdict.WET;
                continue;
            }
            if (guarded.test(cell)) return Verdict.GUARDED;
            for (Direction side : Direction.values()) {
                if (site.is(cell.relative(side), Site.FLUID)) return Verdict.WET;
            }
            if (fallsFromWater(site, cell)) return Verdict.WET;
            // Sand or gravel over a block in the player's own column would come down on their head.
            if (site.is(cell.above(), Site.FALLS) && cell.getY() >= at.feetY() && overColumn(at.body(), cell)) return Verdict.FALLS;
        }
        return Verdict.OK;
    }

    /**
     * Whether sand or gravel over {@code cell} comes down once it goes, and water or lava follows it: over the stack, or
     * beside a block of it, which the fall opens. A riverbed or a beach over the staircase or the room.
     */
    private static boolean fallsFromWater(Site site, BlockPos cell) {
        BlockPos up = cell.above();
        int falling = 0;
        for (; falling < 64 && site.is(up, Site.FALLS); falling++, up = up.above()) {
            for (Direction side : Direction.Plane.HORIZONTAL) {
                if (site.is(up.relative(side), Site.FLUID)) return true;
            }
        }
        return falling > 0 && site.is(up, Site.FLUID);
    }

    private static boolean overColumn(AABB body, BlockPos cell) {
        return body.maxX > cell.getX() && body.minX < cell.getX() + 1 && body.maxZ > cell.getZ() && body.minZ < cell.getZ() + 1;
    }

    private record Aim(Vec3 point, float yaw, float pitch) { }

    /** A point on the face in reach and in view, somewhat off its middle, with a look that agrees with it if it must. */
    private static Aim aim(Site site, Reach at, BlockPos pos, Direction face, boolean agree) {
        double spread = Math.clamp(at.spread(), 0, 0.9) / 2;
        double[][] offsets = {
                {at.random().nextDouble(-spread, spread + 1e-9), at.random().nextDouble(-spread, spread + 1e-9)},
                {at.random().nextDouble(-spread, spread + 1e-9), at.random().nextDouble(-spread, spread + 1e-9)},
                {0, 0}, {0, 0.38}, {0, -0.38}, {0.38, 0}, {-0.38, 0}};
        Vec3 normal = Vec3.atLowerCornerOf(face.getUnitVec3i());
        for (double[] o : offsets) {
            Vec3 point = facePoint(pos, face, o[0], o[1]);
            // Never a look that grazes the face: the camera turns by whole mouse steps, and a grazing look a hair off
            // misses the block.
            if (point.distanceTo(at.eye()) > at.reach() - 0.05) continue;
            if (at.eye().subtract(point).normalize().dot(normal) < 0.25) continue;
            float[] look = look(at.eye(), point);
            if (agree && !ToolShape.agrees(face, look[0], look[1])) continue;
            if (!site.sees(at.eye(), point, pos, face)) continue;
            return new Aim(point, look[0], look[1]);
        }
        return null;
    }

    static Vec3 faceMiddle(BlockPos pos, Direction face) { return facePoint(pos, face, 0, 0); }

    /** A point on a block's face, {@code a} and {@code b} across it from its middle, each between -0.5 and 0.5. */
    static Vec3 facePoint(BlockPos pos, Direction face, double a, double b) {
        double x = pos.getX() + 0.5, y = pos.getY() + 0.5, z = pos.getZ() + 0.5;
        // Just short of the face itself, so a look at it lands on this block and not its neighbour.
        double out = 0.4995;
        return switch (face.getAxis()) {
            case X -> new Vec3(x + face.getStepX() * out, y + b, z + a);
            case Y -> new Vec3(x + a, y + face.getStepY() * out, z + b);
            case Z -> new Vec3(x + a, y + b, z + face.getStepZ() * out);
        };
    }

    /** The yaw and pitch of a look from {@code eye} at {@code point}, as Minecraft measures them. */
    public static float[] look(Vec3 eye, Vec3 point) {
        double dx = point.x - eye.x, dy = point.y - eye.y, dz = point.z - eye.z;
        double flat = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, flat));
        return new float[] {yaw, pitch};
    }
}
