package dev.donut.client.rtp;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Auto RTP state machine, kept free of game classes: send the command, wait for the teleport,
 * let ActivityFinder scan the new area, then stop on a hit or wait out the interval and try again.
 * Time is counted in client ticks.
 */
public final class RtpLoop {
    public enum Phase { IDLE, COOLDOWN, AWAIT_TELEPORT, SCANNING }

    public enum Result {
        FOUND("found likely player activity"),
        EXHAUSTED("used every attempt without finding activity"),
        NO_TELEPORT("the command did not teleport you three times in a row"),
        NO_AMETHYST("three areas in a row had no budding amethyst; the server may hide it"),
        NOT_OVERWORLD("the teleport did not land in the Overworld, the only place geodes generate"),
        ACTIVITY_OFF("ActivityFinder was turned off"),
        HURT("you took damage"),
        MENU_MISSING("the command opened a menu without the configured item"),
        BUILDING("the Schematic Builder is building; stop it first"),
        STOPPED("stopped");

        public final String reason;
        Result(String reason) { this.reason = reason; }
    }

    public enum Action { NONE, SEND, STOP }

    /** DonutSMP can take well over half a minute between /rtp and the teleport. */
    static final int TELEPORT_TIMEOUT = 60 * 20;
    /** New chunks need a moment to arrive before an early hit is trusted. */
    static final int MIN_SCAN = 2 * 20;
    /** A cooldown notice only counts this soon after the command; later ones are someone else's chat. */
    static final int NOTICE_WINDOW = 15 * 20;
    /** Longer cooldowns are more likely misread chat than a real refusal. */
    static final int MAX_COOLDOWN = 15 * 60;
    private static final int GIVE_UP_AFTER = 3;
    /** "5m30s" and "1.5 seconds" too; more than six digits is not a cooldown. */
    private static final Pattern DURATION = Pattern.compile(
            "(?<![\\d.])(\\d{1,6}(?:\\.\\d+)?)\\s*(h|hours?|hrs?|m|min|mins|minutes?|s|sec|secs|seconds?)(?![a-z])");

    private int intervalTicks = 20 * 20, scanTicks = 8 * 20, maxAttempts = 25;
    private Phase phase = Phase.IDLE;
    private Result result;
    private int attempts, missedTeleports, blindAreas, areaAttempt;
    private long sentAt, scanStart, nextSend;
    /** A teleport landed in the area the player now stands in. */
    private boolean arrived;
    /** A cooldown notice took back the last command; it may yet teleport if the notice was a countdown. */
    private boolean refused;
    /** The run found activity after the next command was already sent. */
    private boolean commandPending;

    public void configure(double intervalSeconds, double scanSeconds, int maxAttempts) {
        this.intervalTicks = (int) Math.round(intervalSeconds * 20);
        this.scanTicks = (int) Math.round(scanSeconds * 20);
        this.maxAttempts = maxAttempts;
    }

    public void start(long tick) {
        phase = Phase.COOLDOWN;
        result = null;
        attempts = missedTeleports = blindAreas = areaAttempt = 0;
        arrived = refused = commandPending = false;
        sentAt = Long.MIN_VALUE / 2;
        nextSend = tick;
    }

    public void stop(Result why) {
        if (phase == Phase.IDLE) return;
        phase = Phase.IDLE;
        result = why;
    }

    /** Stops the run, or, when none is running, records why one could not start. */
    public void refuse(Result why) {
        if (phase != Phase.IDLE) {
            stop(why);
            return;
        }
        result = why;
        attempts = areaAttempt = 0;
        commandPending = false;
    }

    /**
     * @param teleported the player left the area the command was sent from
     * @param hurt the player lost health since the previous tick
     * @param hits chunks currently at or above ActivityFinder's threshold
     * @param blind an Overworld area with plenty of loaded chunks but no budding amethyst at all
     * @param overworld the player stands in an Overworld-type dimension
     */
    public Action tick(long tick, boolean teleported, boolean hurt, int hits, boolean blind, boolean overworld,
                       boolean stopWhenHurt) {
        if (phase == Phase.IDLE) return Action.NONE;
        if (hurt && stopWhenHurt) return finish(Result.HURT);
        switch (phase) {
            case COOLDOWN -> {
                if (refused && teleported) {
                    // The "cooldown" was a countdown after all, and the command went through.
                    attempts++;
                    land(tick);
                    return Action.NONE;
                }
                if (lateHit(tick, hits)) return finish(Result.FOUND);
                if (tick < nextSend) return Action.NONE;
                if (attempts >= maxAttempts) return finish(Result.EXHAUSTED);
                attempts++;
                sentAt = tick;
                refused = false;
                phase = Phase.AWAIT_TELEPORT;
                return Action.SEND;
            }
            case AWAIT_TELEPORT -> {
                if (teleported) {
                    missedTeleports = 0;
                    land(tick);
                } else if (lateHit(tick, hits)) {
                    commandPending = true;
                    return finish(Result.FOUND);
                } else if (tick - sentAt >= TELEPORT_TIMEOUT) {
                    if (++missedTeleports >= GIVE_UP_AFTER) return finish(Result.NO_TELEPORT);
                    waitForNext(tick);
                }
                return Action.NONE;
            }
            case SCANNING -> {
                long scanned = tick - scanStart;
                if (scanned < MIN_SCAN) return Action.NONE;
                if (!overworld) return finish(Result.NOT_OVERWORLD);
                if (hits > 0) return finish(Result.FOUND);
                if (scanned < scanTicks) return Action.NONE;
                blindAreas = blind ? blindAreas + 1 : 0;
                if (blindAreas >= GIVE_UP_AFTER) return finish(Result.NO_AMETHYST);
                waitForNext(tick);
                return Action.NONE;
            }
            default -> { return Action.NONE; }
        }
    }

    /**
     * The server refused the teleport for now; the attempt does not count and the next one waits it out.
     *
     * @return whether the notice counted
     */
    public boolean serverCooldown(long tick, int seconds) {
        if (phase == Phase.IDLE || tick - sentAt > NOTICE_WINDOW) return false;
        if (phase == Phase.AWAIT_TELEPORT) {
            attempts--;
            refused = true;
            phase = Phase.COOLDOWN;
        }
        nextSend = Math.max(nextSend, tick + (Math.min(seconds, MAX_COOLDOWN) + 1) * 20L);
        return true;
    }

    /**
     * Chunks keep arriving, and get rescanned, after the scan window. A hit still counts while the player stands in
     * the area a teleport brought them to, through the wait and the next command's countdown.
     */
    private boolean lateHit(long tick, int hits) {
        return hits > 0 && arrived && tick - scanStart >= MIN_SCAN;
    }

    private void land(long tick) {
        arrived = true;
        areaAttempt = attempts;
        scanStart = tick;
        phase = Phase.SCANNING;
    }

    private void waitForNext(long tick) {
        phase = Phase.COOLDOWN;
        nextSend = Math.max(nextSend, Math.max(tick, sentAt + intervalTicks));
    }

    private Action finish(Result why) {
        stop(why);
        return Action.STOP;
    }

    public Phase phase() { return phase; }
    public Result result() { return result; }
    public int attempts() { return attempts; }
    /** The attempt whose teleport reached the area the player stands in. */
    public int areaAttempt() { return areaAttempt; }
    /** The run found activity while the next command was already on its way. */
    public boolean commandPending() { return commandPending; }
    public int maxAttempts() { return maxAttempts; }
    public long nextSend() { return nextSend; }
    public long scanStart() { return scanStart; }
    public int scanTicks() { return scanTicks; }

    /** Seconds the server asks to wait, or -1 when the message is not a cooldown notice. */
    public static int cooldownSeconds(String message) {
        String text = message.toLowerCase(Locale.ROOT);
        // "Please wait 5 seconds, don't move" is a teleport countdown, not a refusal.
        if (text.contains("don't move") || text.contains("do not move")) return -1;
        // DonutSMP's command rate limit: "You need to wait another 1.5 seconds to execute a command".
        boolean refusal = text.contains("cooldown") || text.contains("again in") || text.contains("wait another")
                || text.contains("wait") && text.contains("before");
        if (!refusal) return -1;
        Matcher matcher = DURATION.matcher(text);
        double seconds = 0;
        boolean found = false;
        while (matcher.find()) {
            double amount = Double.parseDouble(matcher.group(1));
            char unit = matcher.group(2).charAt(0);
            seconds += unit == 'h' ? amount * 3600 : unit == 'm' ? amount * 60 : amount;
            found = true;
        }
        return found ? (int) Math.min(Math.ceil(seconds), Integer.MAX_VALUE / 40) : -1;
    }

    /** Whether a menu item is the one to click, by its name or any lore line, ignoring case. */
    public static boolean matchesMenuItem(String name, List<String> lore, String target) {
        String wanted = target.strip().toLowerCase(Locale.ROOT);
        if (wanted.isEmpty()) return false;
        if (name.toLowerCase(Locale.ROOT).contains(wanted)) return true;
        for (String line : lore) if (line.toLowerCase(Locale.ROOT).contains(wanted)) return true;
        return false;
    }
}
