package dev.donut.client.schematic;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.world.phys.Vec3;

/**
 * Walks the player along a {@link Navigator} path with the movement keys, as the player would: turning toward each
 * step, holding forward, jumping where the path climbs a block, walking off where it drops. A climbing step stops over
 * the middle of its block, looks down at it, jumps, and has a helper block placed on it at the top of the jump.
 */
final class Walker {
    enum State { WALKING, ARRIVED, FAILED }

    /** The helper blocks for climbing steps. */
    interface Climb {
        /** Takes the block for {@code cell} in hand before the jump; false when the hotbar has none. */
        boolean ready(BlockPos cell);

        /** Places the helper block into the cell the feet just left, with the look the player has; false when it can't. */
        boolean placeBelow(BlockPos cell);

        /** Crouches, or stands up again: crouched, the player can't walk off an edge. */
        void crouch(boolean on);

        /** Places the helper block against {@code face} of {@code support}, where the crosshair is; false while it isn't. */
        boolean placeBeside(BlockPos support, Direction face);
    }

    /** Ticks without getting closer before the walk counts as stuck. */
    private static final int STUCK_TICKS = 30;
    /** How far off the middle of a block on open ground a walk may pass, in blocks: each walk takes its own line. */
    private static final double WANDER = 0.14;
    /**
     * How near the middle of the block a climb starts from, in blocks along each axis: the new block lands under the feet
     * from there, and the body, 0.6 wide, keeps inside the block's column, clear of anything beside it.
     */
    private static final double CENTRED = 0.15;
    /** Facing further than this from the next step, the player turns on the spot before walking on. */
    private static final float TURN_FIRST = 35;
    /**
     * The same near an edge, as on a walkway one block wide: a turn takes a few ticks, and the line walked meanwhile
     * swings out by as much as half a block, over the edge.
     */
    private static final float TURN_FIRST_NARROW = 8;
    /**
     * How much quicker a turn on the spot goes than one on the way: standing, it swings no one off an edge, and a player
     * about to set off turns round briskly.
     */
    private static final float TURN_ROUND = 1.4f;
    /**
     * How far from the end of a walk over open ground the eyes go ahead of the feet, in blocks: the view turns to the
     * block the walk is for while the feet step the rest of the way there, sideways or back as needed.
     */
    private static final double EYES_AHEAD = 1.5;
    /** Sprinting only above this food level; the builder eats at it. */
    private static final int SPRINT_FOOD = 14;
    /** How many ticks of the walk the log gets when it fails. */
    private static final int TRAIL = 12;

    private final Head head;
    private List<Navigator.Node> path;
    /** Where in each block of the path the walk passes, off its middle, as {x, z} for each step. */
    private double[][] offsets;
    /** Ticks still to stand before setting off, as a player looks before walking. */
    private int hesitate;
    /** The way the keys last went in {@link #strafe}, relative to the view, and for how many more ticks it is held. */
    private float strafeWay;
    private int strafeHold;
    /** What the player turns toward while coming to rest at the end, as the block the walk is for; null for nothing. */
    private Vec3 lookAt;
    /**
     * Whether this walk sidesteps: a few steps on open ground toward more of the same work, which a player takes
     * without turning away from it, keys to the side, the view on the next block. Worked out on the walk's first tick.
     */
    private Boolean sidestep;
    private int index;
    private int stuck, climbTicks, climbTries, jumpTicks, placedAt;
    private double closest;
    private boolean placedClimb;
    /** Whether this walk crouched for a bridging step, how many ticks it has been at it, and when it last clicked. */
    private boolean bridgeCrouch;
    private int bridgeTicks, bridgeClicked;
    /** Whether this walk crouched for its last bit, onto a narrow top over a drop. */
    private boolean edgeCrouch;
    /** The pitch a climbing step looks down with. */
    private float climbLook;
    private String failure = "";
    /** The last few ticks of the walk, for the log when it fails: the step, where the player was and faced, the keys held. */
    private final ArrayDeque<String> trail = new ArrayDeque<>();
    /** The step being walked when the walk failed, from and to; null when it failed otherwise. */
    private Navigator.Node[] failedStep;

    /** Of every hundred walks, how many start with a moment of standing still; and whether longer ones are sprinted. */
    private int hesitateChance = 12;
    private boolean sprints = true;

    Walker(Head head) { this.head = head; }

    /** The pace the settings ask for, from the next walk on. */
    void pace(int hesitateChance, boolean sprints) {
        this.hesitateChance = hesitateChance;
        this.sprints = sprints;
    }

    void start(List<Navigator.Node> path) { start(path, null); }

    void start(List<Navigator.Node> path, Vec3 lookAt) {
        this.path = path;
        this.lookAt = lookAt;
        ThreadLocalRandom random = ThreadLocalRandom.current();
        offsets = new double[path.size()][];
        for (int i = 0; i < path.size(); i++) {
            offsets[i] = new double[] {(random.nextDouble() * 2 - 1) * WANDER, (random.nextDouble() * 2 - 1) * WANDER};
        }
        hesitate = random.nextInt(100) < hesitateChance ? 3 + random.nextInt(8) : 0;
        sidestep = null;
        bridgeCrouch = false;
        edgeCrouch = false;
        bridgeTicks = 0;
        strafeHold = 0;
        trail.clear();
        index = 1;
        stuck = climbTicks = climbTries = jumpTicks = placedAt = 0;
        closest = Double.MAX_VALUE;
        placedClimb = false;
    }

    boolean active() { return path != null; }

    Navigator.Node target() { return path == null ? null : path.getLast(); }

    State tick(Minecraft client, LocalPlayer player, Climb climb) {
        if (path == null) return State.FAILED;
        if (index >= path.size()) {
            release(client);
            Navigator.Node end = path.getLast();
            if (!edgeCrouch && player.onGround() && Math.abs(player.getY() - end.feet()) < 0.05
                    && Navigator.narrowOverDrop(player.level(), end)) {
                // Sliding to a stop on a narrow top over a drop: crouched, the edge holds the player.
                climb.crouch(true);
                edgeCrouch = true;
            }
            // Still sliding on ice, a player taps the key the other way to stop rather than wait the slide out.
            if (player.onGround() && slippery(player)) brake(client, player);
            // Sliding to a stop, the player already turns toward what they walked here for, which a turn doesn't slow:
            // the aim after it has less to turn.
            if (lookAt != null) {
                float[] look = PlacementSolver.look(player.getEyePosition(), lookAt);
                head.turn(player, look[0], look[1]);
            }
            // Wait until the player has as good as come to rest, so a block placed next is aimed from where they stand: a
            // last few centimetres of slide the aim follows, as it is worked out again from the eye each tick.
            if (player.onGround() && restsWithin(player, 0.1)) {
                path = null;
                return State.ARRIVED;
            }
            return State.WALKING;
        }
        Navigator.Node from = path.get(index - 1), to = path.get(index);
        Vec3 at = player.position();
        if (trail.size() >= TRAIL) trail.removeFirst();
        trail.add(String.format(Locale.ROOT, "%d %.2f %.2f %.2f %.0f%s%s%s%s%s%s", index, at.x, at.y, at.z, player.getYRot(),
                client.options.keyUp.isDown() ? "^" : "", client.options.keyDown.isDown() ? "v" : "", client.options.keyLeft.isDown() ? "<" : "",
                client.options.keyRight.isDown() ? ">" : "", client.options.keyJump.isDown() ? "J" : "", client.options.keySprint.isDown() ? "S" : ""));
        if (at.y < Math.min(from.feet(), to.feet()) - 1.5) return fail(client, "fell below the path");
        if (hesitate > 0) {
            hesitate--;
            release(client);
            return State.WALKING;
        }
        if (to.move() == Navigator.Move.PILLAR) return climb(client, player, from, to, climb);
        if (to.move() == Navigator.Move.BRIDGE && player.level().getBlockState(to.floor()).canBeReplaced()) {
            return bridge(client, player, from, to, climb);
        }
        if (sidestep == null) sidestep = sidesteps(player);
        boolean last = index == path.size() - 1;
        // On open ground a walk passes a little off the middle of each block, a line of its own, and ends a little off
        // it too, so the next look isn't the very same; a climb, and anything near an edge or on a narrow top keep to
        // the middle.
        double offX = 0, offZ = 0;
        if (to.move() == Navigator.Move.WALK && open(player, to)) {
            double share = last ? 0.7 : 1;
            offX = offsets[index][0] * share;
            offZ = offsets[index][1] * share;
        }
        double dx = to.x() + 0.5 + offX - at.x, dz = to.z() + 0.5 + offZ - at.z;
        double distance = Math.sqrt(dx * dx + dz * dz);
        // Onto a narrow top over a drop, a closed gate's or a fence's, the last bit is walked crouched, as a player edges
        // onto one: crouched, the edge holds them, where the run-on of a step or the slide to a stop carries a player
        // walking upright off the top, a block or more down, into what may be a pit with no way out. Only once the feet
        // are at the top's height: crouched, a player can't step down onto it.
        if (last && !edgeCrouch && player.onGround() && Math.abs(at.y - to.feet()) < 0.05 && distance < 1.2
                && Navigator.narrowOverDrop(player.level(), to)) {
            climb.crouch(true);
            edgeCrouch = true;
        }
        // The last step ends standing; one on the way also counts passed over mid-hop, close above where it stands.
        boolean there = last ? player.onGround() && Math.abs(at.y - to.feet()) < 0.6
                : (player.onGround() ? Math.abs(at.y - to.feet()) < 0.6 : at.y >= to.feet() - 0.05 && at.y - to.feet() < 0.45);
        if (there && distance < (last ? (player.onGround() && slippery(player) ? 0.4 : 0.25) : 0.45)) {
            if (bridgeCrouch) {
                // On the block put down: standing up again.
                climb.crouch(false);
                bridgeCrouch = false;
            }
            index++;
            stuck = 0;
            closest = Double.MAX_VALUE;
            return State.WALKING;
        }
        // Wading is slow, against a current slower still: it gets longer before it counts as stuck.
        boolean wading = player.isInWater();
        if (distance < closest - 0.03) {
            closest = distance;
            stuck = 0;
        } else if (++stuck > (wading ? STUCK_TICKS * 2 : STUCK_TICKS)) {
            return fail(client, "stuck on the way to " + to + (player.onGround() ? ", on the ground" : ", in the air") + ", moving "
                    + player.getDeltaMovement() + (client.options.keyJump.isDown() ? ", jumping" : ""));
        }
        // On ice the last step lets go once the slide alone carries the player to the spot, and taps the keys the other
        // way if it would carry them past, as a player does, rather than slide on into the next block. The view keeps on
        // what the walk is for.
        if (last && player.onGround() && slippery(player) && slide(player) > distance) {
            release(client);
            brake(client, player);
            if (lookAt != null) {
                float[] look = PlacementSolver.look(player.getEyePosition(), lookAt);
                head.turn(player, look[0], look[1]);
            }
            return State.WALKING;
        }
        // The last bit of a walk over open ground, the eyes already on the block it is for.
        boolean eyesAhead = last && lookAt != null && distance < EYES_AHEAD && player.onGround() && !wading
                && to.move() == Navigator.Move.WALK && open(player, to) && open(player, from);
        if (sidestep || eyesAhead) {
            // The view stays on the work, turning calmly toward the block the walk is for; the feet step there sideways
            // or back as needed, as a player building a row does.
            float[] look = PlacementSolver.look(player.getEyePosition(), lookAt);
            head.turn(player, look[0], look[1]);
            boolean go = distance > (last ? 0.2 : 0.05) || player.getDeltaMovement().horizontalDistanceSqr() < 0.02 * 0.02 && distance > 0.12;
            strafe(client, player, dx, dz, go);
            client.options.keyJump.setDown(false);
            client.options.keySprint.setDown(false);
            return State.WALKING;
        }
        // Turning on the spot, about to set off, as worked out below once turned this tick.
        boolean wayOpen = to.move() == Navigator.Move.WALK && open(player, to) && open(player, from);
        float turnFirst = wayOpen ? 100 : open(player, from) && open(player, to) ? TURN_FIRST : TURN_FIRST_NARROW;
        float offNow = Math.abs(Mth.wrapDegrees((float) Math.toDegrees(Math.atan2(-dx, dz)) - player.getYRot()));
        head.boost(offNow > turnFirst && player.onGround() && !wading && distance >= 0.5 ? TURN_ROUND : 1);
        turnToward(player, dx, dz);
        head.boost(1);
        // A step up is jumped onto squarely: off to the side across the way, the body catches on whatever stands beside the
        // step, a wall or the next step up, and never gets on. A jump goes along an axis, never slanting: across it, the
        // player steps into line first.
        if (to.move() == Navigator.Move.JUMP && player.onGround() && !wading && distance < 1.6) {
            boolean alongX = to.x() != from.x();
            double cross = alongX ? to.z() + 0.5 - at.z : to.x() + 0.5 - at.x;
            if (Math.abs(cross) > 0.15) {
                strafe(client, player, alongX ? 0 : cross, alongX ? cross : 0, true);
                client.options.keyJump.setDown(false);
                client.options.keySprint.setDown(false);
                return State.WALKING;
            }
        }
        // In the air, and over a step down or up, the keys push toward the spot whichever way the player faces: a calm turn
        // would go on pushing the old way, past a ledge a block wide, where a little air control steers onto it. Close
        // over the spot, nothing is held, and the player comes down on it.
        boolean landing = to.move() != Navigator.Move.WALK && (distance < 0.5 || Math.abs(at.y - to.feet()) < 0.6 && distance < 1.0);
        if (!wading && (!player.onGround() || landing)) {
            strafe(client, player, dx, dz, distance > (last ? 0.2 : 0.15));
            client.options.keySprint.setDown(false);
            client.options.keyJump.setDown(player.onGround()
                    && (to.move() == Navigator.Move.JUMP && to.feet() - at.y > 0.1 || to.feet() - at.y > Navigator.STEP));
            return State.WALKING;
        }
        // Close to the spot with it well off to the side, a step that way rather than walking round it: a calm turn
        // can't tighten enough to reach a point less than a block off, and would circle it.
        float aside = Math.abs(Mth.wrapDegrees((float) Math.toDegrees(Math.atan2(-dx, dz)) - player.getYRot()));
        if (distance < 1.0 && aside > 20 && player.onGround() && !wading && to.move() == Navigator.Move.WALK) {
            boolean near = distance > (last ? 0.2 : 0.05) || player.getDeltaMovement().horizontalDistanceSqr() < 0.02 * 0.02 && distance > 0.12;
            strafe(client, player, dx, dz, near);
            client.options.keyJump.setDown(false);
            client.options.keySprint.setDown(false);
            return State.WALKING;
        }
        // Facing well away from the next step, a player on the ground turns first and walks after, as on a ledge: walking
        // while turning swings them out sideways, off a narrow top or over the edge of a walkway.
        float off = Math.abs(Mth.wrapDegrees((float) Math.toDegrees(Math.atan2(-dx, dz)) - player.getYRot()));
        // In water the current carries a player who stands to turn: there they walk on while turning. On open ground,
        // with no edge to swing out over, they walk on while turning too; near an edge they turn all but fully first.
        boolean openWay = wayOpen;
        // There, facing well away, a player sets off at once, stepping back or sideways while they turn toward the way,
        // rather than turning on the spot first.
        if (openWay && off > 45 && player.onGround() && !wading && distance >= 0.5) {
            strafe(client, player, dx, dz, true);
            client.options.keyJump.setDown(false);
            client.options.keySprint.setDown(false);
            return State.WALKING;
        }
        boolean facing = off <= turnFirst || !player.onGround() || wading || distance < 0.5;
        // Near the end of the path, let go a little early: friction carries the rest, much further on ice, where a
        // player who has come to a stop short of the spot steps on again.
        boolean slippery = player.onGround() && slippery(player);
        double letGo = last ? (slippery ? 0.9 : 0.2) : slippery ? 0.35 : 0.05;
        boolean stopped = player.getDeltaMovement().horizontalDistanceSqr() < 0.02 * 0.02;
        client.options.keyDown.setDown(false);
        client.options.keyLeft.setDown(false);
        client.options.keyRight.setDown(false);
        client.options.keyUp.setDown(facing && (distance > letGo || stopped && distance > (last ? 0.2 : 0.05)));
        // A longer way over open ground is sprinted, as a player does, and let go of two steps before the end, so the
        // player comes to rest where the walk ends rather than past it. Sprinting makes hungry: only with the food bar
        // well up, so what is left heals rather than runs.
        boolean sprint = sprints && openWay && !wading && off <= 15 && path.size() - 1 - index >= 2 && player.onGround()
                && player.getFoodData().getFoodLevel() > SPRINT_FOOD;
        client.options.keySprint.setDown(sprint);
        // A step up from where the player really is, though the path planned it from elsewhere, slid off ice say.
        // Only while the feet are still below the step: hopping on once up there, the player never settles on the spot.
        boolean jump = facing && (to.move() == Navigator.Move.JUMP && to.feet() - at.y > 0.1 || to.feet() - at.y > Navigator.STEP)
                && player.onGround() && distance < 1.3;
        // In water the feet are never on the ground: holding jump swims up and over the edge, as a player climbs out.
        if (player.isInWater() && to.feet() > at.y + 0.3 && distance < 1.6) jump = true;
        client.options.keyJump.setDown(jump);
        return State.WALKING;
    }

    /**
     * A bridging step: crouched, out over the edge as far as a crouch lets the body lean, looking down at the side of
     * the block underfoot, the helper block goes in against it, into the spot the step stands on. The step itself is
     * then walked, still crouched.
     */
    private State bridge(Minecraft client, LocalPlayer player, Navigator.Node from, Navigator.Node to, Climb climb) {
        client.options.keySprint.setDown(false);
        client.options.keyJump.setDown(false);
        if (!bridgeCrouch) {
            climb.crouch(true);
            bridgeCrouch = true;
            bridgeTicks = 0;
            bridgeClicked = -10;
        }
        if (++bridgeTicks > 200) return fail(client, "couldn't put a block down to step across to " + to);
        int dx = to.x() - from.x(), dz = to.z() - from.z();
        BlockPos support = from.floor();
        Direction face = Direction.getApproximateNearest(dx, 0, dz);
        // The upper half of that side, just under the edge.
        Vec3 point = Vec3.atCenterOf(support).add(dx * 0.5, 0.3, dz * 0.5);
        float[] look = PlacementSolver.look(player.getEyePosition(), point);
        head.turn(player, look[0], look[1]);
        if (!player.isShiftKeyDown()) {
            // Crouched first: then the edge holds the player.
            release(client);
            return State.WALKING;
        }
        // The side is only in view from out past its plane, as far as a crouch lets the body lean, almost 0.3; and in
        // line with the step, so the block goes where the step stands.
        double past = dx != 0 ? (player.getX() - (from.x() + 0.5 + dx * 0.5)) * dx : (player.getZ() - (from.z() + 0.5 + dz * 0.5)) * dz;
        double across = dx != 0 ? from.z() + 0.5 - player.getZ() : from.x() + 0.5 - player.getX();
        if (past < 0.12 || Math.abs(across) > 0.2) {
            strafe(client, player, dx != 0 ? dx : across, dz != 0 ? dz : across, true);
            return State.WALKING;
        }
        release(client);
        if (!climb.ready(to.floor())) return fail(client, "no block to step across on in the hotbar, holding " + player.getMainHandItem());
        // A click every few ticks, once the crosshair is on the side: the server may take a moment to show the block.
        if (bridgeTicks - bridgeClicked >= 4 && climb.placeBeside(support, face)) bridgeClicked = bridgeTicks;
        return State.WALKING;
    }

    /**
     * Whether the walk is crouched, for a bridging step or onto a narrow top over a drop, which the crouch must not be let
     * go of.
     */
    boolean holdsCrouch() { return path != null && (bridgeCrouch || edgeCrouch); }

    /** Stands still over the spot, looks down at it, jumps, and at the top has the block placed where the feet were. */
    private State climb(Minecraft client, LocalPlayer player, Navigator.Node from, Navigator.Node to, Climb climb) {
        client.options.keyUp.setDown(false);
        // A step taken sideways or back on the way here, or a sprint, ends here: a climb only steps forward.
        client.options.keyDown.setDown(false);
        client.options.keyLeft.setDown(false);
        client.options.keyRight.setDown(false);
        client.options.keySprint.setDown(false);
        BlockPos cell = from.feetCell();
        if (player.onGround() && Math.abs(player.getY() - to.feet()) < 0.05) {
            // Standing on the new block.
            index++;
            placedClimb = false;
            climbTicks = jumpTicks = climbTries = 0;
            climbLook = 0;
            client.options.keyJump.setDown(false);
            return State.WALKING;
        }
        if (++climbTicks > 200) {
            double offX = from.x() + 0.5 - player.getX(), offZ = from.z() + 0.5 - player.getZ();
            return fail(client, String.format(java.util.Locale.ROOT,
                    "climb took too long: %s, %.2f from the middle, pitch %.1f, jumped %s, tries %d, block %s, holding %s",
                    player.onGround() ? "on the ground" : "in the air", Math.sqrt(offX * offX + offZ * offZ), player.getXRot(),
                    jumpTicks > 0 ? "yes" : "no", climbTries, placedClimb ? "placed" : "not placed", player.getMainHandItem()));
        }
        if (placedClimb) {
            client.options.keyJump.setDown(false);
            return climbTicks - placedAt > 30 ? fail(client, "didn't land on the block climbed on") : State.WALKING;
        }
        if (!player.onGround()) {
            client.options.keyJump.setDown(false);
            // Only at the top of the jump this climb made: dropping in from a step before, the feet pass that height
            // too, before the climb has looked down or jumped. Then it waits to land.
            if (jumpTicks > 0 && player.getY() >= from.feet() + 1.02) {
                if (!climb.placeBelow(cell)) return fail(client, "couldn't place the block to climb on at " + cell.toShortString());
                placedClimb = true;
                placedAt = climbTicks;
            }
            return State.WALKING;
        }
        double dx = from.x() + 0.5 - player.getX(), dz = from.z() + 0.5 - player.getZ();
        if (Math.abs(dx) > CENTRED || Math.abs(dz) > CENTRED) {
            // Over the middle of the block first, the whole body inside its column: the jump lands back on the new block,
            // and nothing beside the column at head height, a hopper's rim or a slab, catches the head on the way up. A
            // step straight there, whichever way the player faces: walking while turning circles round the middle.
            strafe(client, player, dx, dz, true);
            client.options.keyJump.setDown(false);
            return State.WALKING;
        }
        if (!climb.ready(cell)) {
            return fail(client, "no block to climb on in the hotbar, holding " + player.getMainHandItem() + ", "
                    + player.getInventory().getSelectedSlot() + " selected");
        }
        // Looking straight down, give or take a degree or two, as a player pillaring does: once the jump lifts the feet
        // off the block, the crosshair rests on its top, where the helper block goes, whichever way the player faces.
        if (climbLook == 0) climbLook = 88 + ThreadLocalRandom.current().nextFloat() * 2;
        float want = climbLook;
        if (Math.abs(want - player.getXRot()) > 1) {
            // Standing still over the spot, the look down is as brisk as a turn round before setting off.
            head.boost(TURN_ROUND);
            tilt(player, want);
            head.boost(1);
            client.options.keyJump.setDown(false);
            return State.WALKING;
        }
        // A jump that came down again without the block in place is tried a few times.
        if (jumpTicks > 0 && climbTicks - jumpTicks > 14) {
            if (++climbTries > 3) return fail(client, "jumped without getting the block in");
            jumpTicks = 0;
        }
        if (jumpTicks == 0) jumpTicks = climbTicks;
        client.options.keyJump.setDown(true);
        return State.WALKING;
    }

    /** A short walk on open ground, every step a plain one, toward a block to look at the while: sidestepped. */
    private boolean sidesteps(LocalPlayer player) {
        if (lookAt == null || path.size() > 4) return false;
        for (Navigator.Node node : path) {
            if (node.move() != Navigator.Move.START && node.move() != Navigator.Move.WALK) return false;
            if (!open(player, node)) return false;
        }
        return true;
    }

    /**
     * Holds the movement keys that take the player toward {@code dx}, {@code dz} as they face now: forward, back, left
     * and right, two at once for a slant.
     */
    private void strafe(Minecraft client, LocalPlayer player, double dx, double dz, boolean go) {
        float angle = Mth.wrapDegrees((float) Math.toDegrees(Math.atan2(-dx, dz)) - player.getYRot());
        // The keys go eight ways, 45 degrees apart. A way between two is kept on average by going each of them in turn,
        // the nearer more of the time, each for a few ticks as a finger holds a key: a way 10 degrees off forward would
        // otherwise be walked straight forward, and drift.
        float lower = (float) Math.floor(angle / 45) * 45;
        boolean between = strafeWay == lower || strafeWay == lower + 45;
        if (--strafeHold < 0 || !between) {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            strafeWay = random.nextFloat() * 45 < angle - lower ? lower + 45 : lower;
            strafeHold = 1 + random.nextInt(3);
        }
        float way = strafeWay;
        client.options.keyUp.setDown(go && Math.abs(way) <= 67.5f);
        client.options.keyDown.setDown(go && Math.abs(way) >= 112.5f);
        client.options.keyRight.setDown(go && way >= 22.5f && way <= 157.5f);
        client.options.keyLeft.setDown(go && way <= -22.5f && way >= -157.5f);
    }

    /**
     * Holds the keys against the slide while it is quicker than a tick of them takes off on this ground, so it never
     * sets the player going back: the ground's friction times 0.91 keeps that much of it, and a key adds the player's
     * speed times 0.216 over that cubed, as the game works it out.
     */
    private void brake(Minecraft client, LocalPlayer player) {
        Vec3 motion = player.getDeltaMovement();
        double keep = keep(player);
        double push = player.getSpeed() * 0.21600002 / (keep * keep * keep);
        if (Math.sqrt(motion.horizontalDistanceSqr()) * keep > push) strafe(client, player, -motion.x, -motion.z, true);
    }

    /** How much further the player slides with no key held, in blocks. */
    private static double slide(LocalPlayer player) {
        double keep = keep(player);
        return Math.sqrt(player.getDeltaMovement().horizontalDistanceSqr()) * keep / (1 - keep);
    }

    /** The share of the speed the ground the player stands on keeps each tick: its friction times 0.91. */
    private static double keep(LocalPlayer player) {
        BlockPos below = BlockPos.containing(player.getX(), player.getY() - 0.05, player.getZ());
        return player.level().getBlockState(below).getBlock().getFriction() * 0.91;
    }

    /** Whether the place to stand has full, sturdy ground under it and on all four sides at its height: no edge near. */
    private static boolean open(LocalPlayer player, Navigator.Node node) {
        var level = player.level();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx != 0 && dz != 0) continue;
                BlockPos floor = new BlockPos(node.x() + dx, node.y(), node.z() + dz);
                if (!level.getBlockState(floor).isFaceSturdy(level, floor, net.minecraft.core.Direction.UP)) return false;
                if (Math.abs(floor.getY() + 1 - node.feet()) > 0.01) return false;
            }
        }
        return true;
    }

    /** Whether the player stands on ice or something as slippery, which carries them on after they let go. */
    private static boolean slippery(LocalPlayer player) {
        BlockPos below = BlockPos.containing(player.getX(), player.getY() - 0.05, player.getZ());
        return player.level().getBlockState(below).getBlock().getFriction() > 0.7f;
    }

    /**
     * Whether the player, on the ground with no key held, slides less than {@code slide} blocks further: each tick keeps
     * the ground's friction times 0.91 of the speed, so what is left is the speed over one less that. On ice that takes
     * a far slower speed than on stone.
     */
    private static boolean restsWithin(LocalPlayer player, double slide) {
        BlockPos below = BlockPos.containing(player.getX(), player.getY() - 0.05, player.getZ());
        double keep = player.level().getBlockState(below).getBlock().getFriction() * 0.91;
        return Math.sqrt(player.getDeltaMovement().horizontalDistanceSqr()) < slide * (1 - keep);
    }

    private void turnToward(LocalPlayer player, double dx, double dz) { turnToward(player, dx, dz, true); }

    /** Turns toward a step, smoothly; {@code lookAhead} also brings a look at the ground back up, which a climb keeps. */
    private void turnToward(LocalPlayer player, double dx, double dz, boolean lookAhead) {
        if (dx * dx + dz * dz < 1e-4) return;
        float want = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = player.getXRot();
        // Looking ahead again after a climb or a click looked down.
        if (lookAhead && Math.abs(pitch) > 35) pitch = Math.signum(pitch) * 25;
        head.turn(player, want, pitch);
    }

    /** Turns the pitch toward {@code want}, smoothly, the yaw staying where it is. */
    private void tilt(LocalPlayer player, float want) {
        head.turn(player, player.getYRot(), want);
    }

    private State fail(Minecraft client, String why) {
        StringBuilder steps = new StringBuilder();
        if (path != null) {
            for (Navigator.Node node : path) {
                steps.append(steps.isEmpty() ? "" : ", ").append(node.x()).append(' ').append(node.feet()).append(' ').append(node.z())
                        .append(' ').append(node.move());
            }
        }
        failure = why + (Boolean.TRUE.equals(sidestep) ? ", sidestepping" : "") + " (step " + index + " of " + steps
                + "; last ticks, as step x y z yaw keys: " + String.join(" | ", trail) + ")";
        failedStep = path != null && index >= 1 && index < path.size() ? new Navigator.Node[] {path.get(index - 1), path.get(index)} : null;
        release(client);
        path = null;
        return State.FAILED;
    }

    /** Why the last walk failed. */
    String failure() { return failure; }

    /** The step the last walk failed on, from and to, or null. */
    Navigator.Node[] failedStep() { return failedStep; }

    void stop(Minecraft client) {
        if (path != null) release(client);
        path = null;
    }

    private static void release(Minecraft client) {
        client.options.keyDown.setDown(false);
        client.options.keyLeft.setDown(false);
        client.options.keyRight.setDown(false);
        client.options.keyUp.setDown(false);
        client.options.keyJump.setDown(false);
        client.options.keySprint.setDown(false);
    }
}
