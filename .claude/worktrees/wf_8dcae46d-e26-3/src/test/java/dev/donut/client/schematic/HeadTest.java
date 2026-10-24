package dev.donut.client.schematic;

import static org.junit.jupiter.api.Assertions.*;

import java.util.SplittableRandom;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;

class HeadTest {
    @Test
    void aTurnSpeedsUpEasesOffAndNeverOvershoots() {
        float peak = 12, push = 3, left = 90, speed = 0, last = 0;
        int ticks = 0;
        boolean slowedDown = false;
        while (left > 1e-3f) {
            speed = Head.speed(left, speed, peak, push);
            assertTrue(speed > 0, "always toward the target");
            assertTrue(speed <= peak + 1e-4f, "never faster than its peak");
            assertTrue(speed <= left + 1e-4f, "never past the target");
            if (ticks == 0) assertTrue(speed <= push + 1e-4f, "a turn starts from rest, not with a jump");
            if (speed < last - 1e-4f) slowedDown = true;
            last = speed;
            left -= speed;
            assertTrue(++ticks < 60, "it gets there");
        }
        assertTrue(slowedDown, "it eases off into the target");
        assertTrue(ticks >= 9, "a quarter turn takes the better part of half a second, not a flick: " + ticks + " ticks");
    }

    @Test
    void evenTheQuickestTurnIsNoFlick() {
        float left = 90, speed = 0;
        int ticks = 0;
        while (left > 1e-3f && ticks < 60) {
            speed = Head.speed(left, speed, Head.PEAK_MAX, Head.PUSH_MAX);
            assertTrue(speed <= Head.PEAK_MAX + 1e-4f, "never faster than its peak");
            left -= speed;
            ticks++;
        }
        assertTrue(ticks >= 7, "a quarter turn still takes over a third of a second: " + ticks + " ticks");
    }

    @Test
    void aSmallCorrectionIsQuick() {
        float left = 4, speed = 0;
        int ticks = 0;
        while (left > 1e-3f && ticks < 60) {
            speed = Head.speed(left, speed, 12, 3);
            left -= speed;
            ticks++;
        }
        assertTrue(ticks <= 6, "a few degrees take a few ticks: " + ticks);
    }
    /** The usual mouse step, at the default sensitivity. */
    private static final float MOUSE = 0.15f;

    @Test
    void anAimThatLandsOffCorrectsAndOnlyThenIsThere() {
        int corrected = 0;
        for (int seed = 0; seed < 200; seed++) {
            Head head = new Head();
            head.misses(100);
            RandomGenerator random = new SplittableRandom(seed);
            float[] view = {0, 0};
            float[] speeds = new float[60];
            int ticks = 0;
            while (true) {
                float yaw = view[0];
                boolean there = head.step(view, 120, 20, true, MOUSE, random);
                speeds[ticks] = view[0] - yaw;
                assertTrue(++ticks < 60, "it gets there");
                if (there) break;
            }
            assertEquals(120, view[0], 1e-4f, "there means on the target");
            assertEquals(20, view[1], 1e-4f, "there means on the target");
            // After the fastest of it, slowing and speeding up again, or turning back: a second movement.
            int peak = 0;
            for (int i = 1; i < ticks; i++) if (speeds[i] > speeds[peak]) peak = i;
            for (int i = peak + 1; i < ticks; i++) {
                if (speeds[i] > speeds[i - 1] + 0.3f || speeds[i] < -1e-3f) {
                    corrected++;
                    break;
                }
            }
        }
        assertTrue(corrected > 100, "most aims told to land off do, and correct: " + corrected);
    }

    @Test
    void aTurnWhileWalkingGoesStraightThere() {
        for (int seed = 0; seed < 100; seed++) {
            Head head = new Head();
            head.misses(100);
            RandomGenerator random = new SplittableRandom(seed);
            float[] view = {10, 12};
            int ticks = 0;
            while (!head.step(view, 130, 12, false, MOUSE, random)) {
                assertTrue(view[0] <= 130 + MOUSE, "never past the target");
                assertEquals(12, view[1], 1e-4f, "the pitch stays");
                assertTrue(++ticks < 60, "it gets there");
            }
        }
    }

    @Test
    void everyMoveOnTheWayIsWholeMouseSteps() {
        Head head = new Head();
        head.misses(0);
        RandomGenerator random = new SplittableRandom(7);
        float[] view = {0, 0};
        for (int tick = 0; tick < 60; tick++) {
            float yaw = view[0], pitch = view[1];
            boolean there = head.step(view, 75, -30, true, MOUSE, random);
            if (there) return;
            float steps = (view[0] - yaw) / MOUSE, pitchSteps = (view[1] - pitch) / MOUSE;
            assertEquals(Math.round(steps), steps, 2e-3f, "yaw by whole mouse steps");
            assertEquals(Math.round(pitchSteps), pitchSteps, 2e-3f, "pitch by whole mouse steps");
        }
        fail("never got there");
    }

    @Test
    void aLongerTurnGoesQuickerWithoutAFlatTop() {
        float[] about = speeds(175), quarter = speeds(90), short_ = speeds(40);
        assertTrue(max(about) > max(quarter) && max(quarter) > max(short_) - 1e-3f, "a longer sweep goes quicker");
        assertTrue(max(short_) >= Head.PEAK_FLOOR - 1e-3f || max(short_) < Head.PEAK_FLOOR && short_.length <= 8,
                "short turns aren't slowed below the floor");
        int top = 0;
        for (float speed : about) if (speed > max(about) * 0.95f) top++;
        assertTrue(top <= 3, "no long stretch at one top speed: " + top + " ticks");
        assertTrue(about.length >= 10, "even turning about takes half a second, no flick: " + about.length + " ticks");
    }

    @Test
    void anAboutTurnSometimesGoesTheLongWay() {
        int left = 0, right = 0;
        for (int seed = 0; seed < 100; seed++) {
            Head head = new Head();
            float[] view = {0, 0};
            head.step(view, 170, 0, true, MOUSE, new SplittableRandom(seed));
            if (view[0] > 0) right++;
            else left++;
        }
        assertTrue(right > 50 && left > 15, "mostly the short way, now and then the long: " + right + " and " + left);
    }

    @Test
    void aLandedLookHoldsStillThenFollowsExactly() {
        for (float mouse : new float[] {0.01f, MOUSE, 0.61f}) {
            Head head = new Head();
            RandomGenerator random = new SplittableRandom(1);
            float[] view = {0, 0};
            int ticks = 0;
            while (!head.step(view, 30, 10, true, mouse, random)) assertTrue(++ticks < 60, "it gets there");
            assertEquals(30, view[0], 1e-4f, "exactly there, whatever the mouse step");
            assertEquals(10, view[1], 1e-4f, "exactly there, whatever the mouse step");
            // A sliding player: the look worked out from the eye creeps on. The view holds still while it is close, so
            // the look the server has is the one clicked with, and moves only onto it, exactly.
            int still = 0;
            for (int tick = 1; tick <= 30; tick++) {
                float yaw = 30 + tick * 0.015f, pitch = 10 - tick * 0.005f, before = view[0], beforePitch = view[1];
                assertTrue(head.step(view, yaw, pitch, true, mouse, random), "still there");
                boolean moved = view[0] != before || view[1] != beforePitch;
                if (moved) {
                    assertEquals(yaw, view[0], 1e-4f, "onto it exactly");
                    assertEquals(pitch, view[1], 1e-4f, "onto it exactly");
                } else {
                    still++;
                    assertTrue(Math.abs(yaw - view[0]) < 0.05f && Math.abs(pitch - view[1]) < 0.05f, "never far behind");
                }
            }
            assertTrue(still >= 20, "mostly still: " + still + " of 30 ticks");
        }
    }

    /** Each tick's turn of an aim {@code degrees} round at the quickest pace picked, to the end. */
    private static float[] speeds(float degrees) {
        Head head = new Head();
        head.misses(0);
        RandomGenerator quickest = new RandomGenerator() {
            @Override public long nextLong() { return 0; }
            @Override public float nextFloat() { return 0.999f; }
            @Override public int nextInt(int bound) { return bound - 1; }
            @Override public double nextGaussian() { return 0; }
        };
        float[] view = {0, 0}, speeds = new float[80];
        int ticks = 0;
        while (ticks < 80) {
            float yaw = view[0];
            boolean there = head.step(view, degrees, 0, true, MOUSE, quickest);
            speeds[ticks++] = view[0] - yaw;
            if (there) break;
        }
        return java.util.Arrays.copyOf(speeds, ticks);
    }

    private static float max(float[] values) {
        float most = 0;
        for (float value : values) most = Math.max(most, value);
        return most;
    }
}
