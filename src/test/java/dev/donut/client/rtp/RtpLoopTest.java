package dev.donut.client.rtp;

import static org.junit.jupiter.api.Assertions.*;

import dev.donut.client.rtp.RtpLoop.Action;
import dev.donut.client.rtp.RtpLoop.Phase;
import dev.donut.client.rtp.RtpLoop.Result;
import java.util.List;
import org.junit.jupiter.api.Test;

class RtpLoopTest {
    private static final int INTERVAL = 10 * 20, SCAN = 4 * 20;

    private long tick;
    private final RtpLoop loop = new RtpLoop();

    private RtpLoop started(int maxAttempts) {
        loop.configure(INTERVAL / 20.0, SCAN / 20.0, maxAttempts);
        loop.start(tick);
        return loop;
    }

    /** Advance until the loop asks for something or the tick budget runs out. */
    private Action run(int ticks, boolean teleported, int hits, boolean blind) {
        return run(ticks, teleported, hits, blind, true);
    }

    private Action run(int ticks, boolean teleported, int hits, boolean blind, boolean overworld) {
        for (int i = 0; i < ticks; i++) {
            Action action = loop.tick(tick++, teleported, false, hits, blind, overworld, true);
            if (action != Action.NONE) return action;
        }
        return Action.NONE;
    }

    /** Sends the first command, lands, and lets the whole scan window pass without a hit. */
    private void quietFirstArea() {
        assertEquals(Action.SEND, run(1, false, 0, false));
        run(1, true, 0, false);
        assertEquals(Action.NONE, run(SCAN, true, 0, false));
        assertEquals(Phase.COOLDOWN, loop.phase());
    }

    @Test
    void aHitThatShowsUpAfterTheScanWindowStillStopsTheRun() {
        started(5);
        quietFirstArea();
        // An outer chunk arrives, or a refresh corrects one, a moment after the scan ended.
        assertEquals(Action.STOP, run(1, true, 1, false));
        assertEquals(Result.FOUND, loop.result());
        assertEquals(1, loop.areaAttempt());
        assertFalse(loop.commandPending());
    }

    @Test
    void theLastAttemptWaitsOutItsIntervalForLateHits() {
        started(1);
        quietFirstArea();
        assertEquals(Action.NONE, run(INTERVAL / 2, true, 0, false));
        assertEquals(Action.STOP, run(1, true, 1, false));
        assertEquals(Result.FOUND, loop.result(), "not EXHAUSTED next to a hit");
    }

    @Test
    void aHitDuringTheNextCountdownStopsAndSaysTheCommandWasSent() {
        started(5);
        quietFirstArea();
        assertEquals(Action.SEND, run(INTERVAL, true, 0, false));
        assertEquals(Phase.AWAIT_TELEPORT, loop.phase());
        // Still standing where the first teleport landed; the second has not happened yet.
        assertEquals(Action.STOP, run(1, false, 1, false));
        assertEquals(Result.FOUND, loop.result());
        assertEquals(1, loop.areaAttempt());
        assertTrue(loop.commandPending());
    }

    @Test
    void hitsWhereTheRunStartedDoNotCount() {
        started(5);
        assertEquals(Action.SEND, run(1, false, 0, false));
        // The player's own base, before any teleport: the command times out and the next one goes out.
        assertEquals(Action.SEND, run(RtpLoop.TELEPORT_TIMEOUT + INTERVAL, false, 1, false));
        assertNull(loop.result());
    }

    @Test
    void landingOutsideTheOverworldStopsTheRun() {
        started(5);
        run(1, false, 0, false);
        run(1, true, 0, false, false);
        assertEquals(Action.NONE, run(RtpLoop.MIN_SCAN - 2, true, 0, false, false), "the dimension is settled first");
        assertEquals(Action.STOP, run(5, true, 0, false, false));
        assertEquals(Result.NOT_OVERWORLD, loop.result());
    }

    @Test
    void cooldownNoticesOnlyCountRightAfterTheCommandAndAreCapped() {
        started(5);
        loop.serverCooldown(tick, 30);
        assertEquals(Action.SEND, run(1, false, 0, false), "nothing was sent yet, so a notice is someone else's");
        loop.serverCooldown(tick, 7200);
        assertEquals(Phase.COOLDOWN, loop.phase());
        assertEquals(tick + (RtpLoop.MAX_COOLDOWN + 1) * 20L, loop.nextSend(), "a two hour wait is capped");
    }

    @Test
    void aCountdownMisreadAsACooldownStillScansTheArea() {
        started(5);
        run(1, false, 0, false);
        loop.serverCooldown(tick, 5);
        assertEquals(0, loop.attempts());
        run(1, true, 0, false);
        assertEquals(Phase.SCANNING, loop.phase(), "the teleport went through after all");
        assertEquals(1, loop.attempts());
        assertEquals(Action.STOP, run(RtpLoop.MIN_SCAN + 1, true, 1, false));
        assertEquals(Result.FOUND, loop.result());
    }

    @Test
    void aRefusedStartIsRecordedWhileARunningOneIsStopped() {
        loop.refuse(Result.GONUTS_BUSY);
        assertEquals(Result.GONUTS_BUSY, loop.result());
        assertEquals(Phase.IDLE, loop.phase());
        started(5);
        run(1, false, 0, false);
        loop.refuse(Result.GONUTS_BUSY);
        assertEquals(Phase.IDLE, loop.phase());
        assertEquals(1, loop.attempts(), "a stopped run keeps its count");
    }

    @Test
    void sendsAtOnceThenStopsWhenTheNewAreaShowsActivity() {
        started(5);
        assertEquals(Action.SEND, run(1, false, 0, false));
        assertEquals(Phase.AWAIT_TELEPORT, loop.phase());
        assertEquals(Action.NONE, run(40, false, 0, false), "nothing happens until the teleport");
        run(1, true, 0, false);
        assertEquals(Phase.SCANNING, loop.phase());
        // A hit in the first moments may still be the old area, so it is not trusted yet.
        assertEquals(Action.NONE, run(RtpLoop.MIN_SCAN - 2, true, 3, false));
        assertEquals(Action.STOP, run(5, true, 3, false));
        assertEquals(Result.FOUND, loop.result());
        assertEquals(1, loop.attempts());
        assertEquals(Phase.IDLE, loop.phase());
    }

    @Test
    void quietAreasWaitOutTheIntervalAndTheLastAttemptEndsTheRun() {
        started(2);
        long firstSend = tick;
        run(1, false, 0, false);
        run(1, true, 0, false);
        assertEquals(Action.NONE, run(SCAN, true, 0, false));
        assertEquals(Phase.COOLDOWN, loop.phase());
        assertEquals(firstSend + INTERVAL, loop.nextSend(), "attempts are spaced from send to send");
        assertEquals(Action.SEND, run(INTERVAL, false, 0, false));
        assertEquals(firstSend + INTERVAL, tick - 1);
        run(1, true, 0, false);
        run(SCAN + 1, true, 0, false);
        assertEquals(Action.STOP, run(INTERVAL, false, 0, false));
        assertEquals(Result.EXHAUSTED, loop.result());
        assertEquals(2, loop.attempts());
    }

    @Test
    void threeCommandsWithoutATeleportStopTheRun() {
        started(10);
        for (int attempt = 1; attempt <= 2; attempt++) {
            assertEquals(Action.SEND, run(INTERVAL + 1, false, 0, false));
            assertEquals(Action.NONE, run(RtpLoop.TELEPORT_TIMEOUT, false, 0, false));
            assertEquals(Phase.COOLDOWN, loop.phase());
        }
        assertEquals(Action.SEND, run(INTERVAL + 1, false, 0, false));
        assertEquals(Action.STOP, run(RtpLoop.TELEPORT_TIMEOUT + 1, false, 0, false));
        assertEquals(Result.NO_TELEPORT, loop.result());
    }

    @Test
    void aServerCooldownDoesNotUseAnAttemptAndDelaysTheNextOne() {
        started(1);
        run(1, false, 0, false);
        loop.serverCooldown(tick, 30);
        assertEquals(Phase.COOLDOWN, loop.phase());
        assertEquals(0, loop.attempts());
        assertEquals(Action.NONE, run(30 * 20, false, 0, false));
        assertEquals(Action.SEND, run(40, false, 0, false), "the one allowed attempt is still available");
        assertEquals(1, loop.attempts());
    }

    @Test
    void takingDamageStopsImmediatelyUnlessDisabled() {
        started(5);
        run(1, false, 0, false);
        assertEquals(Action.NONE, loop.tick(tick++, false, true, 0, false, true, false));
        assertEquals(Action.STOP, loop.tick(tick++, false, true, 0, false, true, true));
        assertEquals(Result.HURT, loop.result());
    }

    @Test
    void threeAreasWithoutAnyAmethystStopTheRunButOneWithAmethystResetsTheCount() {
        started(20);
        for (boolean blind : new boolean[] {true, true, false, true, true}) {
            assertEquals(Action.SEND, run(INTERVAL + 1, false, 0, false));
            run(1, true, 0, blind);
            assertEquals(Action.NONE, run(SCAN, true, 0, blind));
        }
        assertEquals(Action.SEND, run(INTERVAL + 1, false, 0, false));
        run(1, true, 0, true);
        assertEquals(Action.STOP, run(SCAN, true, 0, true));
        assertEquals(Result.NO_AMETHYST, loop.result());
    }

    @Test
    void stoppingAnIdleLoopKeepsTheLastResult() {
        started(1);
        loop.stop(Result.STOPPED);
        loop.stop(Result.HURT);
        assertEquals(Result.STOPPED, loop.result());
        assertEquals(Action.NONE, run(5, true, 5, false));
    }

    @Test
    void cooldownMessagesAreReadButTeleportCountdownsAreNot() {
        assertEquals(270, RtpLoop.cooldownSeconds("You must wait 4m 30s before using this again!"));
        assertEquals(10, RtpLoop.cooldownSeconds("You are on cooldown. Try again in 10 seconds."));
        assertEquals(300, RtpLoop.cooldownSeconds("You can use /rtp again in 5 minutes"));
        assertEquals(3723, RtpLoop.cooldownSeconds("Cooldown: 1h 2m 3s"));
        assertEquals(-1, RtpLoop.cooldownSeconds("Teleporting in 5 seconds, don't move!"));
        assertEquals(-1, RtpLoop.cooldownSeconds("Please wait 3 seconds before teleporting, do not move"));
        assertEquals(-1, RtpLoop.cooldownSeconds("Teleporting you to a random location in 5 seconds"));
        assertEquals(-1, RtpLoop.cooldownSeconds("You are on cooldown"));
        assertEquals(-1, RtpLoop.cooldownSeconds("Welcome to the server"));
    }

    @Test
    void menuItemsMatchByNameOrLoreIgnoringCase() {
        assertTrue(RtpLoop.matchesMenuItem("OVERWORLD", List.of(), "Overworld"));
        assertTrue(RtpLoop.matchesMenuItem("Random Teleport", List.of("Click to go to the overworld"), "Overworld"));
        assertFalse(RtpLoop.matchesMenuItem("Nether", List.of("Hot"), "Overworld"));
        assertFalse(RtpLoop.matchesMenuItem("Overworld", List.of(), "  "));
    }
}
