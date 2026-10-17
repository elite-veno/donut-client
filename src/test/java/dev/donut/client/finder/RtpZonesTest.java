package dev.donut.client.finder;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class RtpZonesTest {
    private static final double[] BORDER = {-30_000_000, -30_000_000, 30_000_000, 30_000_000};

    private static RtpZones.Zone zone(int x, int z) { return new RtpZones.Zone("donutsmp.net", "minecraft:overworld", x, z); }

    @Test
    void theConfiguredCommandIsTheRandomTeleport() {
        assertTrue(RtpZones.isRtp("rtp", "rtp"));
        assertTrue(RtpZones.isRtp("/RTP overworld", "rtp"));
        assertFalse(RtpZones.isRtp("rtpmenu", "rtp"));
        assertFalse(RtpZones.isRtp("home", "rtp"));
        assertFalse(RtpZones.isRtp("rtp", " "));
    }

    @Test
    void aLongJumpSoonAfterTheCommandIsALanding() {
        RtpZones watch = new RtpZones();
        assertNull(watch.tick(0, 0, 0, false));
        watch.commandSent(1);
        assertNull(watch.tick(20, 3, 0, false), "walking is no teleport");
        double[] landing = watch.tick(400, 5000, -1200, false);
        assertArrayEquals(new double[] {5000, -1200}, landing);
        assertNull(watch.tick(401, 9000, 9000, false), "one command, one landing");
    }

    @Test
    void otherTeleportsAreNoLandings() {
        RtpZones watch = new RtpZones();
        watch.tick(0, 0, 0, false);
        assertNull(watch.tick(1, 8000, 0, false), "a /home without /rtp");
        watch.commandSent(2);
        assertNull(watch.tick(2 + RtpZones.LANDING_WINDOW + 1, 20000, 0, false), "too long after the command");
        watch.commandSent(5000);
        assertArrayEquals(new double[] {20000, 0}, watch.tick(5001, 20000, 0, true), "a teleport into another world");
    }

    @Test
    void theTravelTargetIsFarEnoughFromEveryLanding() {
        List<RtpZones.Zone> zones = List.of(zone(1000, 1000));
        // Standing at the landing: straight away from the world's middle.
        double[] target = RtpZones.travelTarget(zones, 1000, 1000, 2000, BORDER);
        assertNotNull(target);
        assertTrue(RtpZones.distance(zones, target[0], target[1]) >= 2000);
        assertTrue(target[0] > 1000 && target[1] > 1000, "outward from 0,0");
        // Already walking east: keep going east.
        double[] east = RtpZones.travelTarget(zones, 1600, 1000, 2000, BORDER);
        assertEquals(1000 + 2000 + RtpZones.MARGIN, east[0], 1);
        assertEquals(1000, east[1], 1);
        assertNull(RtpZones.travelTarget(zones, 3500, 1000, 2000, BORDER), "far enough out already");
        assertNull(RtpZones.travelTarget(List.of(), 0, 0, 2000, BORDER));
    }

    @Test
    void theTravelTargetGoesAroundOtherLandingsAndStaysInsideTheBorder() {
        List<RtpZones.Zone> zones = List.of(zone(0, 0), zone(2200, 0));
        double[] target = RtpZones.travelTarget(zones, 100, 0, 2000, BORDER);
        assertNotNull(target);
        assertTrue(RtpZones.distance(zones, target[0], target[1]) >= 2000, "clear of both landings");
        // A border 2500 blocks east turns the player another way.
        double[] border = {-10_000, -10_000, 2500, 10_000};
        double[] turned = RtpZones.travelTarget(List.of(zone(1000, 0)), 1200, 0, 2000, border);
        assertNotNull(turned);
        assertTrue(turned[0] <= 2500 - RtpZones.BORDER_MARGIN);
        assertTrue(RtpZones.distance(List.of(zone(1000, 0)), turned[0], turned[1]) >= 2000);
        // A world too small to get that far from the landing has no target.
        assertNull(RtpZones.travelTarget(List.of(zone(0, 0)), 0, 0, 2000, new double[] {-1000, -1000, 1000, 1000}));
    }
}
