package dev.donut.client.schematic;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
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
    }

    /** Ticks without getting closer before the walk counts as stuck. */
    private static final int STUCK_TICKS = 30;
    /** How far the yaw turns in a tick, at most: a player turns, not snaps. */
    private static final float TURN = 40;
    /** How far the pitch turns in a tick, at most. */
    private static final float TILT = 22;
    /** Facing further than this from the next step, the player turns on the spot before walking on. */
    private static final float TURN_FIRST = 35;

    private List<Navigator.Node> path;
    private int index;
    private int stuck, climbTicks, climbTries, jumpTicks, placedAt;
    private double closest;
    private boolean placedClimb;
    /** The pitch a climbing step looks down with. */
    private float climbLook;
    private String failure = "";
    /** The step being walked when the walk failed, from and to; null when it failed otherwise. */
    private Navigator.Node[] failedStep;

    void start(List<Navigator.Node> path) {
        this.path = path;
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
            // Wait until the player has come to rest, so a block placed next is aimed from where they stand.
            if (player.onGround() && player.getDeltaMovement().horizontalDistanceSqr() < 1e-4) {
                path = null;
                return State.ARRIVED;
            }
            return State.WALKING;
        }
        Navigator.Node from = path.get(index - 1), to = path.get(index);
        Vec3 at = player.position();
        if (at.y < Math.min(from.feet(), to.feet()) - 1.5) return fail(client, "fell below the path");
        if (to.move() == Navigator.Move.PILLAR) return climb(client, player, from, to, climb);
        double dx = to.x() + 0.5 - at.x, dz = to.z() + 0.5 - at.z;
        double distance = Math.sqrt(dx * dx + dz * dz);
        boolean last = index == path.size() - 1;
        // The last step ends standing; one on the way also counts passed over mid-hop, close above where it stands.
        boolean there = last ? player.onGround() && Math.abs(at.y - to.feet()) < 0.6
                : (player.onGround() ? Math.abs(at.y - to.feet()) < 0.6 : at.y >= to.feet() - 0.05 && at.y - to.feet() < 0.45);
        if (there && distance < (last ? (player.onGround() && slippery(player) ? 0.4 : 0.25) : 0.45)) {
            index++;
            stuck = 0;
            closest = Double.MAX_VALUE;
            return State.WALKING;
        }
        if (distance < closest - 0.03) {
            closest = distance;
            stuck = 0;
        } else if (++stuck > STUCK_TICKS) {
            return fail(client, "stuck on the way to " + to + (player.onGround() ? ", on the ground" : ", in the air") + ", moving "
                    + player.getDeltaMovement() + (client.options.keyJump.isDown() ? ", jumping" : ""));
        }
        turnToward(player, dx, dz);
        // Facing well away from the next step, a player on the ground turns first and walks after, as on a ledge: walking
        // while turning swings them out sideways, off a narrow top or over the edge of a walkway.
        float off = Math.abs(Mth.wrapDegrees((float) Math.toDegrees(Math.atan2(-dx, dz)) - player.getYRot()));
        boolean facing = off <= TURN_FIRST || !player.onGround() || distance < 0.5;
        // Near the end of the path, let go a little early: friction carries the rest, much further on ice, where a
        // player who has come to a stop short of the spot steps on again.
        boolean slippery = player.onGround() && slippery(player);
        double letGo = last ? (slippery ? 0.9 : 0.2) : slippery ? 0.35 : 0.05;
        boolean stopped = player.getDeltaMovement().horizontalDistanceSqr() < 0.02 * 0.02;
        client.options.keyUp.setDown(facing && (distance > letGo || stopped && distance > (last ? 0.2 : 0.05)));
        client.options.keySprint.setDown(false);
        // A step up from where the player really is, though the path planned it from elsewhere, slid off ice say.
        boolean jump = facing && (to.move() == Navigator.Move.JUMP || to.feet() - at.y > Navigator.STEP) && player.onGround() && distance < 1.3;
        // In water the feet are never on the ground: holding jump swims up and over the edge, as a player climbs out.
        if (player.isInWater() && to.feet() > at.y + 0.3 && distance < 1.6) jump = true;
        client.options.keyJump.setDown(jump);
        return State.WALKING;
    }

    /** Stands still over the spot, looks down at it, jumps, and at the top has the block placed where the feet were. */
    private State climb(Minecraft client, LocalPlayer player, Navigator.Node from, Navigator.Node to, Climb climb) {
        client.options.keyUp.setDown(false);
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
        if (++climbTicks > 200) return fail(client, "climb took too long");
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
        if (dx * dx + dz * dz > 0.04) {
            // Over the middle of the block first, so the jump lands back on the new one.
            turnToward(player, dx, dz, false);
            client.options.keyUp.setDown(true);
            client.options.keyJump.setDown(false);
            return State.WALKING;
        }
        if (!climb.ready(cell)) return fail(client, "no block to climb on in the hotbar");
        // Looking straight down, give or take a degree or two, as a player pillaring does: once the jump lifts the feet
        // off the block, the crosshair rests on its top, where the helper block goes, whichever way the player faces.
        if (climbLook == 0) climbLook = 88 + ThreadLocalRandom.current().nextFloat() * 2;
        float want = climbLook;
        if (Math.abs(want - player.getXRot()) > 1) {
            tilt(player, want);
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

    /** Whether the player stands on ice or something as slippery, which carries them on after they let go. */
    private static boolean slippery(LocalPlayer player) {
        BlockPos below = BlockPos.containing(player.getX(), player.getY() - 0.05, player.getZ());
        return player.level().getBlockState(below).getBlock().getFriction() > 0.7f;
    }

    private static void turnToward(LocalPlayer player, double dx, double dz) { turnToward(player, dx, dz, true); }

    /** Turns toward a step; {@code lookAhead} also brings a look at the ground back up, which a climb keeps. */
    private static void turnToward(LocalPlayer player, double dx, double dz, boolean lookAhead) {
        if (dx * dx + dz * dz < 1e-4) return;
        float want = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float now = player.getYRot();
        float change = Mth.clamp(Mth.wrapDegrees(want - now), -TURN, TURN);
        player.setYRot(now + change);
        player.setYHeadRot(now + change);
        // Looking ahead again after a climb looked down, a little each tick.
        if (lookAhead && Math.abs(player.getXRot()) > 35) tilt(player, Math.signum(player.getXRot()) * 25);
    }

    /** Turns the pitch toward {@code want}, quickly at first and slowing as it closes in. */
    private static void tilt(LocalPlayer player, float want) {
        float delta = want - player.getXRot();
        float step = Math.abs(delta) < 1.5f ? delta : Mth.clamp(delta * 0.5f, -TILT, TILT);
        if (Math.abs(step) < 1.5f && Math.abs(delta) >= 1.5f) step = Math.copySign(1.5f, delta);
        player.setXRot(Mth.clamp(player.getXRot() + step, -90, 90));
    }

    private State fail(Minecraft client, String why) {
        failure = why;
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
        client.options.keyUp.setDown(false);
        client.options.keyJump.setDown(false);
        client.options.keySprint.setDown(false);
    }
}
