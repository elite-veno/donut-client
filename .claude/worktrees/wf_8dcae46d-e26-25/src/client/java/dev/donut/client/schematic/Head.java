package dev.donut.client.schematic;

import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;

/**
 * Turns the player's view as a hand on a mouse does: picking up speed out of rest and easing off into the target,
 * never a jump of the camera from one spot to the next. Yaw and pitch keep their own speeds, so the view curves a
 * little on its way, and every turn has a pace of its own. A longer turn goes quicker than a short one, as a hand
 * sweeping further does, rather than at one top speed. On its way it moves by whole mouse steps of the player's
 * sensitivity, a little unsteadily, and it comes to rest exactly on the target; an aim at a block now and then lands a
 * little off first and corrects.
 */
final class Head {
    /**
     * The fastest a quarter turn goes, in degrees a tick, picked for each turn between these; a longer turn goes
     * quicker and a shorter one calmer, by the square root of how much longer or shorter.
     */
    static final float PEAK_MIN = 10, PEAK_MAX = 18;
    /**
     * No turn's fastest is below this: a walk setting off facing the other way turns about on the spot first, and must
     * be round well within Walker's ticks for being stuck.
     */
    static final float PEAK_FLOOR = 8;
    /** How quickly a turn speeds up and slows down, in degrees a tick per tick, picked the same way. */
    static final float PUSH_MIN = 2.8f, PUSH_MAX = 4.4f;
    /** The turn the peaks are for, in degrees; the share a turn's peak takes of it stays between these. */
    private static final float USUAL_TURN = 90, SHARE_MIN = 0.7f, SHARE_MAX = 1.5f;
    /** A target further than this from the last one starts a new turn. */
    private static final float NEW_TURN = 8;
    /** Close enough to the target, in degrees, to come to rest on it. */
    private static final float THERE = 0.3f;
    /** An aim at least this long, in degrees, may land a little off and correct. */
    static final float MISS_FROM = 15;
    /** How far off such an aim lands, as a share of the turn along it and the most in degrees, and the most across it. */
    private static final float MISS_SHARE_MIN = 0.03f, MISS_SHARE_MAX = 0.08f, MISS_MOST = 5, MISS_ACROSS = 2;
    /** Of every hundred aims that land off, how many fall short; the rest go past. */
    private static final int SHORT_OF_IT = 65;
    /** An aim turning further round than this may go the long way, as a hand that doesn't weigh which way is shorter. */
    private static final float LONG_WAY = 155;
    private static final int LONG_WAY_CHANCE = 35;
    /**
     * The unsteadiness of a turn under way, as a share of its speed each tick: across its way while aiming, and in how
     * far it gets each tick; and the most across, in degrees.
     */
    private static final float WOBBLE = 0.04f, UNEVEN = 0.025f, WOBBLE_MOST = 0.6f;

    private float yawSpeed, pitchSpeed;
    /** The settings' share of the usual pace, and a share more for a brisk turn, as on the spot before setting off. */
    private float pace = 1, boost = 1;
    private float peak = 11, push = 3, pitchPart = 0.8f;
    private float lastYaw = Float.NaN, lastPitch = Float.NaN;
    /** Of every hundred aims long enough, about how many land a little off and correct. */
    private int missChance = 30;
    /** How far off the target this aim's first movement lands, as yaw and pitch; 0 once it has, or for none. */
    private float missYaw, missPitch;
    /** Which way round this turn goes, 1 or -1; 0 the shorter way. */
    private int way;
    /**
     * Whether the view rests on the target. It then holds still while the look worked out again from the eye of a
     * player still sliding stays within {@link #HOLD} of it: a click goes out only after a tick the look stayed put, as
     * the server judges it by the look of the tick before. Further off, it follows onto it exactly.
     */
    private boolean resting;
    /** How far, in degrees, a target may creep from a view resting on it before the view follows. */
    private static final float HOLD = 0.05f;

    /** One tick of turning toward {@code yaw} and {@code pitch}, straight there; true once the view rests on them. */
    boolean turn(LocalPlayer player, float yaw, float pitch) { return move(player, yaw, pitch, false); }

    /**
     * One tick of aiming at a spot to click, the target of {@link #turn}: some aims land a little off first and
     * correct, and a turn about may go the long way round. Not for turns while walking, which an overshoot would swing
     * off a narrow path. True once the view rests on the spot itself.
     */
    boolean aim(LocalPlayer player, float yaw, float pitch) { return move(player, yaw, pitch, true); }

    /** One tick of turning the yaw toward {@code yaw}, the pitch staying where it is; true once there. */
    boolean turnYaw(LocalPlayer player, float yaw) {
        return turn(player, yaw, player.getXRot());
    }

    /** Turns from the next turn on at {@code pace} times the usual pace. */
    void pace(float pace) { this.pace = pace; }

    /** From the next aim on, about {@code chance} of every hundred long enough land a little off and correct. */
    void misses(int chance) { this.missChance = chance; }

    /**
     * Turns {@code boost} times quicker until set back to 1: still picking up speed and easing off, only a brisker hand.
     */
    void boost(float boost) { this.boost = boost; }

    /** Forgets the speed of the last turn, as after the view was set some other way. */
    void rest() {
        yawSpeed = pitchSpeed = 0;
        lastYaw = lastPitch = Float.NaN;
        missYaw = missPitch = 0;
        way = 0;
        resting = false;
    }

    private boolean move(LocalPlayer player, float yaw, float pitch, boolean aiming) {
        float[] view = {player.getYRot(), player.getXRot()};
        boolean there = step(view, yaw, pitch, aiming, mouseStep(), ThreadLocalRandom.current());
        set(player, view[0], view[1]);
        return there;
    }

    /**
     * One tick of turning {@code view}, as {yaw, pitch}, toward {@code yaw} and {@code pitch}: on the way by whole
     * multiples of {@code mouse} degrees, the last of it exactly; true once it rests on them.
     */
    boolean step(float[] view, float yaw, float pitch, boolean aiming, float mouse, RandomGenerator random) {
        pitch = Mth.clamp(pitch, -90, 90);
        if (Float.isNaN(lastYaw) || Math.abs(Mth.wrapDegrees(yaw - lastYaw)) > NEW_TURN || Math.abs(pitch - lastPitch) > NEW_TURN) {
            begin(Mth.wrapDegrees(yaw - view[0]), pitch - view[1], aiming, random);
        }
        lastYaw = yaw;
        lastPitch = pitch;
        boolean missing = missYaw != 0 || missPitch != 0;
        float dyaw = Mth.wrapDegrees(yaw + missYaw - view[0]), dpitch = Mth.clamp(pitch + missPitch, -90, 90) - view[1];
        // The long way round, until what is left of it is the shorter way too.
        if (way != 0 && Math.signum(dyaw) == -way && Math.abs(dyaw) > 90) dyaw += 360 * way;
        if (resting && Math.abs(dyaw) < HOLD && Math.abs(dpitch) < HOLD) return true;
        resting = false;
        // Close enough, onto it exactly: a look that only just clears an edge must be the look the click was worked out
        // with.
        if (Math.abs(dyaw) < THERE && Math.abs(dpitch) < THERE) return land(view, dyaw, dpitch, missing);
        float quick = boost;
        yawSpeed = speed(dyaw, yawSpeed, peak * quick, push * quick);
        pitchSpeed = speed(dpitch, pitchSpeed, peak * pitchPart * quick, push * pitchPart * quick);
        // Come to rest on the target this tick: there now, not a tick later.
        if (Math.abs(dyaw - yawSpeed) < THERE && Math.abs(dpitch - pitchSpeed) < THERE) return land(view, dyaw, dpitch, missing);
        // A hand is a little uneven in how far it gets each tick, and aiming a little unsteady across its way, the more
        // so the quicker it goes; the speeds stay as they were, so it doesn't add up, and it never gets past the target.
        // Not across on a walk, whose turns keep the pitch they have, and would wander off with it.
        float moving = (float) Math.hypot(yawSpeed, pitchSpeed);
        float uneven = 1 + Mth.clamp((float) random.nextGaussian() * UNEVEN, -2 * UNEVEN, 2 * UNEVEN);
        float wobble = aiming ? Mth.clamp((float) random.nextGaussian() * WOBBLE * moving, -WOBBLE_MOST, WOBBLE_MOST) : 0;
        float acrossYaw = moving > 1e-3f ? -pitchSpeed / moving * wobble : 0, acrossPitch = moving > 1e-3f ? yawSpeed / moving * wobble : 0;
        view[0] += steps(within(yawSpeed * uneven, dyaw) + acrossYaw, dyaw, mouse);
        view[1] = Mth.clamp(view[1] + steps(within(pitchSpeed * uneven, dpitch) + acrossPitch, dpitch, mouse), -90, 90);
        return false;
    }

    /**
     * The last of a movement, onto where it was headed: the target, true; or the spot a little off it an aim landed on
     * first, false, the correction to follow from rest, as the hand stops before it moves on.
     */
    private boolean land(float[] view, float dyaw, float dpitch, boolean missing) {
        view[0] += dyaw;
        view[1] = Mth.clamp(view[1] + dpitch, -90, 90);
        yawSpeed = pitchSpeed = 0;
        if (missing) {
            missYaw = missPitch = 0;
            // A correction is a small, careful movement.
            push *= 0.6f;
            return false;
        }
        way = 0;
        resting = true;
        return true;
    }

    /** Picks the pace of a turn {@code dyaw}, {@code dpitch} long, and for an aim whether it lands off first. */
    private void begin(float dyaw, float dpitch, boolean aiming, RandomGenerator random) {
        float length = (float) Math.hypot(dyaw, dpitch);
        float share = Mth.clamp((float) Math.sqrt(length / USUAL_TURN), SHARE_MIN, SHARE_MAX);
        peak = Math.max(PEAK_FLOOR, (PEAK_MIN + random.nextFloat() * (PEAK_MAX - PEAK_MIN)) * share * pace);
        push = (PUSH_MIN + random.nextFloat() * (PUSH_MAX - PUSH_MIN)) * pace;
        pitchPart = 0.65f + random.nextFloat() * 0.3f;
        missYaw = missPitch = 0;
        way = 0;
        resting = false;
        if (!aiming) return;
        if (Math.abs(dyaw) > LONG_WAY && random.nextInt(100) < LONG_WAY_CHANCE) {
            way = dyaw > 0 ? -1 : 1;
            dyaw += 360 * way;
            length = (float) Math.hypot(dyaw, dpitch);
        }
        if (length < MISS_FROM || random.nextInt(100) >= missChance) return;
        float along = Math.min(MISS_MOST, length * (MISS_SHARE_MIN + random.nextFloat() * (MISS_SHARE_MAX - MISS_SHARE_MIN)));
        if (random.nextInt(100) < SHORT_OF_IT) along = -along;
        float across = Mth.clamp((float) random.nextGaussian() * length * 0.015f, -MISS_ACROSS, MISS_ACROSS);
        float alongYaw = dyaw / length, alongPitch = dpitch / length;
        missYaw = alongYaw * along - alongPitch * across;
        missPitch = alongPitch * along + alongYaw * across;
    }

    /**
     * The next tick's turn on one axis, {@code remaining} degrees from the target at {@code speed}: up by {@code push}
     * toward {@code peak}, and down again in time to come to rest on the target rather than past it.
     */
    static float speed(float remaining, float speed, float peak, float push) {
        float direction = Math.signum(remaining), distance = Math.abs(remaining);
        if (distance < 1e-3f) return 0;
        // The most speed from which slowing down by push a tick still stops by the target, tick by tick: from v that
        // takes v + (v - push) + ... , about v * v / (2 push) + v / 2, so the last tick of a turn is a short one.
        float most = Math.min(peak, (float) (Math.sqrt(push * push / 4 + 2 * push * distance) - push / 2));
        float along = speed * direction;
        float next = along < most ? Math.min(most, along + push) : Math.max(most, along - push * 1.5f);
        // Never less than a crawl toward it, and never past it.
        next = Math.max(next, Math.min(distance, 0.3f));
        return direction * Math.min(next, distance);
    }

    /** {@code speed}, but no further than {@code remaining}, the same way. */
    private static float within(float speed, float remaining) {
        return Math.signum(speed) == Math.signum(remaining) && Math.abs(speed) > Math.abs(remaining) ? remaining : speed;
    }

    /** {@code degrees} in whole mouse steps; never none while {@code remaining} is a step or more, so it gets there. */
    static float steps(float degrees, float remaining, float mouse) {
        float moved = quantize(degrees, mouse);
        if (moved == 0 && Math.abs(remaining) >= mouse * 0.5f && Math.signum(degrees) == Math.signum(remaining)) {
            moved = Math.copySign(mouse, remaining);
        }
        return moved;
    }

    static float quantize(float degrees, float mouse) {
        return mouse <= 0 ? degrees : Math.round(degrees / mouse) * mouse;
    }

    /** How far one mouse count turns the camera with the player's sensitivity, as Minecraft's mouse handler works it out. */
    static float mouseStep() {
        double f = Minecraft.getInstance().options.sensitivity().get() * 0.6 + 0.2;
        return (float) (f * f * f * 8.0 * 0.15);
    }

    private static void set(LocalPlayer player, float yaw, float pitch) {
        player.setYRot(yaw);
        player.setYHeadRot(yaw);
        player.setXRot(Mth.clamp(pitch, -90, 90));
    }
}
