package dev.donut.client.schematic;

import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;

/**
 * Turns the player's view as a hand on a mouse does: picking up speed out of rest and easing off into the target,
 * never a jump of the camera from one spot to the next. Yaw and pitch keep their own speeds, so the view curves a
 * little on its way, and every turn has a pace of its own.
 */
final class Head {
    /**
     * The fastest a turn goes, in degrees a tick, picked for each turn between these. Not below 8: a walk setting off
     * facing the other way turns about on the spot first, and must be round well within Walker's ticks for being stuck.
     */
    static final float PEAK_MIN = 10, PEAK_MAX = 18;
    /** How quickly a turn speeds up and slows down, in degrees a tick per tick, picked the same way. */
    static final float PUSH_MIN = 2.8f, PUSH_MAX = 4.4f;
    /** A target further than this from the last one starts a new turn. */
    private static final float NEW_TURN = 8;
    /** Close enough to the target, in degrees, to come to rest on it. */
    private static final float THERE = 0.3f;

    private float yawSpeed, pitchSpeed;
    /** The settings' share of the usual pace, and a share more for a brisk turn, as on the spot before setting off. */
    private float pace = 1, boost = 1;
    private float peak = 11, push = 3, pitchPart = 0.8f;
    private float lastYaw = Float.NaN, lastPitch = Float.NaN;

    /** One tick of turning toward {@code yaw} and {@code pitch}; true once the view rests on them. */
    boolean turn(LocalPlayer player, float yaw, float pitch) {
        pitch = Mth.clamp(pitch, -90, 90);
        if (Float.isNaN(lastYaw) || Math.abs(Mth.wrapDegrees(yaw - lastYaw)) > NEW_TURN || Math.abs(pitch - lastPitch) > NEW_TURN) {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            peak = (PEAK_MIN + random.nextFloat() * (PEAK_MAX - PEAK_MIN)) * pace;
            push = (PUSH_MIN + random.nextFloat() * (PUSH_MAX - PUSH_MIN)) * pace;
            pitchPart = 0.65f + random.nextFloat() * 0.3f;
        }
        lastYaw = yaw;
        lastPitch = pitch;
        float dyaw = Mth.wrapDegrees(yaw - player.getYRot()), dpitch = pitch - player.getXRot();
        if (Math.abs(dyaw) < THERE && Math.abs(dpitch) < THERE) {
            set(player, player.getYRot() + dyaw, pitch);
            yawSpeed = pitchSpeed = 0;
            return true;
        }
        float quick = boost;
        yawSpeed = speed(dyaw, yawSpeed, peak * quick, push * quick);
        pitchSpeed = speed(dpitch, pitchSpeed, peak * pitchPart * quick, push * pitchPart * quick);
        set(player, player.getYRot() + yawSpeed, player.getXRot() + pitchSpeed);
        return false;
    }

    /** One tick of turning the yaw toward {@code yaw}, the pitch staying where it is; true once there. */
    boolean turnYaw(LocalPlayer player, float yaw) {
        return turn(player, yaw, player.getXRot());
    }

    /** Turns from the next turn on at {@code pace} times the usual pace. */
    void pace(float pace) { this.pace = pace; }

    /**
     * Turns {@code boost} times quicker until set back to 1: still picking up speed and easing off, only a brisker hand.
     */
    void boost(float boost) { this.boost = boost; }

    /** Forgets the speed of the last turn, as after the view was set some other way. */
    void rest() {
        yawSpeed = pitchSpeed = 0;
        lastYaw = lastPitch = Float.NaN;
    }

    /**
     * The next tick's turn on one axis, {@code remaining} degrees from the target at {@code speed}: up by {@code push}
     * toward {@code peak}, and down again in time to come to rest on the target rather than past it.
     */
    static float speed(float remaining, float speed, float peak, float push) {
        float direction = Math.signum(remaining), distance = Math.abs(remaining);
        if (distance < 1e-3f) return 0;
        // The most speed from which slowing down by push a tick still stops by the target.
        float most = Math.min(peak, (float) Math.sqrt(2 * push * distance));
        float along = speed * direction;
        float next = along < most ? Math.min(most, along + push) : Math.max(most, along - push * 1.5f);
        // Never less than a crawl toward it, and never past it.
        next = Math.max(next, Math.min(distance, 0.3f));
        return direction * Math.min(next, distance);
    }

    private static void set(LocalPlayer player, float yaw, float pitch) {
        player.setYRot(yaw);
        player.setYHeadRot(yaw);
        player.setXRot(Mth.clamp(pitch, -90, 90));
    }
}
