package dev.donut.client.config;

public final class RtpSettings {
    /** Auto RTP always starts off when joining a new session. */
    public transient boolean enabled = false;
    public boolean favorite = false;
    /** Sent without the leading slash. */
    public String command = "rtp";
    /** Clicked when the command opens a menu instead of teleporting. */
    public String menuItem = "Overworld";
    /** Growth a chunk needs before Auto RTP stops; separate from ActivityFinder's square threshold. */
    public double stopAtGrowth = 0.55;
    public double intervalSeconds = 20;
    /** How much each wait between commands varies about the interval (log-normal sigma); 0 is a fixed clock. */
    public double intervalSpread = 0.25;
    /** The usual moment, in seconds, from a command's menu showing its items to the click on one. */
    public double menuDelay = 0.45;
    public double scanSeconds = 8;
    public int maxAttempts = 25;
    public boolean stopWhenHurt = true;

    public void sanitize() {
        command = clean(command, "rtp");
        menuItem = menuItem == null ? "Overworld" : menuItem.strip();
        stopAtGrowth = Double.isFinite(stopAtGrowth) ? Math.clamp(stopAtGrowth, 0, 1) : 0.55;
        intervalSeconds = Double.isFinite(intervalSeconds) ? Math.clamp(intervalSeconds, 5, 600) : 20;
        intervalSpread = Double.isFinite(intervalSpread) ? Math.clamp(intervalSpread, 0, 0.5) : 0.25;
        menuDelay = Double.isFinite(menuDelay) ? Math.clamp(menuDelay, 0.15, 1.5) : 0.45;
        scanSeconds = Double.isFinite(scanSeconds) ? Math.clamp(scanSeconds, 2, 30) : 8;
        maxAttempts = Math.clamp(maxAttempts, 1, 200);
    }

    private static String clean(String value, String fallback) {
        if (value == null) return fallback;
        // The server kicks a client that sends a section sign or a control character, every attempt.
        StringBuilder allowed = new StringBuilder();
        value.codePoints().filter(c -> c != '\u00a7' && c >= ' ' && c != 127).limit(256).forEach(allowed::appendCodePoint);
        String stripped = allowed.toString().strip();
        while (stripped.startsWith("/")) stripped = stripped.substring(1).strip();
        return stripped.isEmpty() ? fallback : stripped;
    }
}
