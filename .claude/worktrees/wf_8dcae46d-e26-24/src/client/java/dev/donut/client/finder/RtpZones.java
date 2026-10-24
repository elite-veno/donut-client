package dev.donut.client.finder;

import java.util.List;
import java.util.Locale;

/**
 * Where random teleports land, kept free of game classes. On DonutSMP everyone who uses /rtp lands somewhere around
 * there, so ChunkFinder keeps spots {@code distance} blocks from every landing it has seen, 2000 by default, and points
 * the player away from the landing until they are that far out.
 */
public final class RtpZones {
    /** A landing, on one server in one dimension. */
    public record Zone(String server, String dimension, int x, int z) { }

    /** A random teleport moves much further than this in one tick; walking or riding never does. */
    static final double TELEPORT_JUMP = 256;
    /** DonutSMP can take well over half a minute between /rtp and the teleport. */
    static final int LANDING_WINDOW = 90 * 20;
    /** The travel target lies this far past the distance, so the clearance around a spot there is outside it too. */
    static final double MARGIN = 160;
    /** A target is kept this far inside the world border. */
    static final double BORDER_MARGIN = 64;

    private long sentAt = Long.MIN_VALUE / 2;
    private double lastX, lastZ;
    private boolean placed;

    /** Whether a command sent to the server is the random teleport, such as "rtp" or "rtp overworld". */
    public static boolean isRtp(String command, String rtpCommand) {
        String sent = command.strip().toLowerCase(Locale.ROOT), rtp = rtpCommand.strip().toLowerCase(Locale.ROOT);
        while (sent.startsWith("/")) sent = sent.substring(1);
        return !rtp.isEmpty() && (sent.equals(rtp) || sent.startsWith(rtp + " "));
    }

    /** The random teleport command went out at this tick. */
    public void commandSent(long tick) { sentAt = tick; }

    /**
     * Follows the player; returns where they landed when a random teleport just brought them here, otherwise null.
     *
     * @param newLevel the player came into another level since the last tick, as a teleport to another world does
     */
    public double[] tick(long tick, double x, double z, boolean newLevel) {
        boolean jumped = placed && (newLevel || Math.hypot(x - lastX, z - lastZ) >= TELEPORT_JUMP);
        lastX = x;
        lastZ = z;
        placed = true;
        if (!jumped || tick - sentAt > LANDING_WINDOW) return null;
        sentAt = Long.MIN_VALUE / 2;
        return new double[] {x, z};
    }

    /** Forgets the last position, as when leaving a server; a pending command stays pending. */
    public void lost() { placed = false; }

    /** The horizontal distance from a point to the nearest of these zones; infinite without any. */
    public static double distance(List<Zone> zones, double x, double z) {
        double nearest = Double.POSITIVE_INFINITY;
        for (Zone zone : zones) nearest = Math.min(nearest, Math.hypot(zone.x() - x, zone.z() - z));
        return nearest;
    }

    /**
     * Where to go for ground {@code distance} blocks from every zone: straight on from the nearest landing through the
     * player, or away from the world's middle when the player still stands at the landing, a margin past the distance
     * and inside the border. Other headings are tried when that one leaves the border or comes near another zone.
     *
     * @param border min x, min z, max x, max z of the world border
     * @return the target's x and z, or null when there are no zones, the player is already far enough out, or no
     *         heading reaches ground that far from every zone inside the border
     */
    public static double[] travelTarget(List<Zone> zones, double x, double z, double distance, double[] border) {
        if (zones.isEmpty() || distance <= 0 || distance(zones, x, z) >= distance) return null;
        Zone nearest = zones.getFirst();
        for (Zone zone : zones) if (Math.hypot(zone.x() - x, zone.z() - z) < Math.hypot(nearest.x() - x, nearest.z() - z)) nearest = zone;
        double dx = x - nearest.x(), dz = z - nearest.z();
        if (Math.hypot(dx, dz) < 16) {
            dx = nearest.x();
            dz = nearest.z();
        }
        if (Math.hypot(dx, dz) < 1) dx = 1;
        double heading = Math.atan2(dz, dx);
        // The heading first, then turning further from it either way, 15 degrees at a time.
        for (int step = 0; step <= 12; step++) {
            for (int side : step == 0 ? new int[] {1} : new int[] {1, -1}) {
                double angle = heading + side * step * Math.toRadians(15);
                double[] target = along(zones, nearest, angle, distance, border);
                if (target != null) return target;
            }
        }
        return null;
    }

    /** The first point on this heading from the zone that is far enough from every zone, if it is inside the border. */
    private static double[] along(List<Zone> zones, Zone from, double angle, double distance, double[] border) {
        double cos = Math.cos(angle), sin = Math.sin(angle);
        for (double reach = distance + MARGIN; reach <= distance * 4 + MARGIN; reach += 64) {
            double tx = from.x() + cos * reach, tz = from.z() + sin * reach;
            if (tx < border[0] + BORDER_MARGIN || tz < border[1] + BORDER_MARGIN
                    || tx > border[2] - BORDER_MARGIN || tz > border[3] - BORDER_MARGIN) return null;
            if (distance(zones, tx, tz) >= distance + MARGIN) return new double[] {Math.round(tx), Math.round(tz)};
        }
        return null;
    }
}
