package dev.donut.client.config;

public final class RtpSettings {
    /** Auto RTP always starts off when joining a new session. */
    public transient boolean enabled = false;
    public boolean favorite = false;
    /** Sent without the leading slash. */
    public String command = "rtp";
    /** Clicked when the command opens a menu instead of teleporting. */
    public String menuItem = "Overworld";
    public double intervalSeconds = 20;
    public double scanSeconds = 8;
    public int maxAttempts = 25;
    public boolean stopWhenHurt = true;

    public void sanitize() {
        command = clean(command, "rtp");
        menuItem = menuItem == null ? "Overworld" : menuItem.strip();
        intervalSeconds = Double.isFinite(intervalSeconds) ? Math.clamp(intervalSeconds, 5, 600) : 20;
        scanSeconds = Double.isFinite(scanSeconds) ? Math.clamp(scanSeconds, 2, 30) : 8;
        maxAttempts = Math.clamp(maxAttempts, 1, 200);
    }

    private static String clean(String value, String fallback) {
        if (value == null) return fallback;
        String stripped = value.strip();
        while (stripped.startsWith("/")) stripped = stripped.substring(1).strip();
        return stripped.isEmpty() ? fallback : stripped;
    }
}
