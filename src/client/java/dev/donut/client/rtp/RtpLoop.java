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
        HURT("you took damage"),
        MENU_MISSING("the command opened a menu without the configured item"),
        STOPPED("stopped");

        public final String reason;
        Result(String reason) { this.reason = reason; }
    }

    public enum Action { NONE, SEND, STOP }

    /** Long enough for "teleporting in 5 seconds, don't move" countdowns. */
    static final int TELEPORT_TIMEOUT = 30 * 20;
    /** New chunks need a moment to arrive before an early hit is trusted. */
    static final int MIN_SCAN = 2 * 20;
    private static final int GIVE_UP_AFTER = 3;
    private static final Pattern DURATION = Pattern.compile(
            "(\\d+)\\s*(h|hours?|hrs?|m|min|mins|minutes?|s|sec|secs|seconds?)\\b");

    private int intervalTicks = 20 * 20, scanTicks = 8 * 20, maxAttempts = 25;
    private Phase phase = Phase.IDLE;
    private Result result;
    private int attempts, missedTeleports, blindAreas;
    private long sentAt, scanStart, nextSend;

    public void configure(double intervalSeconds, double scanSeconds, int maxAttempts) {
        this.intervalTicks = (int) Math.round(intervalSeconds * 20);
        this.scanTicks = (int) Math.round(scanSeconds * 20);
        this.maxAttempts = maxAttempts;
    }

    public void start(long tick) {
        phase = Phase.COOLDOWN;
        result = null;
        attempts = missedTeleports = blindAreas = 0;
        nextSend = tick;
    }

    public void stop(Result why) {
        if (phase == Phase.IDLE) return;
        phase = Phase.IDLE;
        result = why;
    }

    /**
     * @param teleported the player left the area the command was sent from
     * @param hurt the player lost health since the previous tick
     * @param hits chunks currently at or above ActivityFinder's threshold
     * @param blind an Overworld area with plenty of loaded chunks but no budding amethyst at all
     */
    public Action tick(long tick, boolean teleported, boolean hurt, int hits, boolean blind, boolean stopWhenHurt) {
        if (phase == Phase.IDLE) return Action.NONE;
        if (hurt && stopWhenHurt) return finish(Result.HURT);
        switch (phase) {
            case COOLDOWN -> {
                if (tick < nextSend) return Action.NONE;
                if (attempts >= maxAttempts) return finish(Result.EXHAUSTED);
                attempts++;
                sentAt = tick;
                phase = Phase.AWAIT_TELEPORT;
                return Action.SEND;
            }
            case AWAIT_TELEPORT -> {
                if (teleported) {
                    missedTeleports = 0;
                    scanStart = tick;
                    phase = Phase.SCANNING;
                } else if (tick - sentAt >= TELEPORT_TIMEOUT) {
                    if (++missedTeleports >= GIVE_UP_AFTER) return finish(Result.NO_TELEPORT);
                    waitForNext(tick);
                }
                return Action.NONE;
            }
            case SCANNING -> {
                long scanned = tick - scanStart;
                if (hits > 0 && scanned >= MIN_SCAN) return finish(Result.FOUND);
                if (scanned < scanTicks) return Action.NONE;
                blindAreas = blind ? blindAreas + 1 : 0;
                if (blindAreas >= GIVE_UP_AFTER) return finish(Result.NO_AMETHYST);
                waitForNext(tick);
                return Action.NONE;
            }
            default -> { return Action.NONE; }
        }
    }

    /** The server refused the teleport for now; the attempt does not count and the next one waits it out. */
    public void serverCooldown(long tick, int seconds) {
        if (phase == Phase.IDLE) return;
        if (phase == Phase.AWAIT_TELEPORT) {
            attempts--;
            phase = Phase.COOLDOWN;
        }
        nextSend = Math.max(nextSend, tick + (seconds + 1) * 20L);
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
    public int maxAttempts() { return maxAttempts; }
    public long nextSend() { return nextSend; }
    public long scanStart() { return scanStart; }
    public int scanTicks() { return scanTicks; }

    /** Seconds the server asks to wait, or -1 when the message is not a cooldown notice. */
    public static int cooldownSeconds(String message) {
        String text = message.toLowerCase(Locale.ROOT);
        // "Please wait 5 seconds, don't move" is a teleport countdown, not a refusal.
        if (text.contains("don't move") || text.contains("do not move")) return -1;
        boolean refusal = text.contains("cooldown") || text.contains("again in")
                || text.contains("wait") && text.contains("before");
        if (!refusal) return -1;
        Matcher matcher = DURATION.matcher(text);
        int seconds = 0;
        boolean found = false;
        while (matcher.find()) {
            int amount = Integer.parseInt(matcher.group(1));
            char unit = matcher.group(2).charAt(0);
            seconds += unit == 'h' ? amount * 3600 : unit == 'm' ? amount * 60 : amount;
            found = true;
        }
        return found ? seconds : -1;
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
