package dev.donut.client.schematic;

import static org.junit.jupiter.api.Assertions.*;

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
}
