package dev.donut.client.license;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/**
 * What one answer from Lemon Squeezy's License API means for this copy of Donut Client. Kept apart from the network and
 * the game so it can be tested on the answers the API documents.
 */
public final class LicenseCheck {
    static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH).withZone(ZoneId.systemDefault());

    /** A key that may be used, until {@code expiresAt} (null when it never runs out), on the instance named, if any. */
    public record Verdict(boolean ok, String message, Instant expiresAt, String instanceId) {
        static Verdict refused(String message) { return new Verdict(false, message, null, null); }
    }

    private LicenseCheck() { }

    /**
     * Judges an answer from {@code /v1/licenses/validate} or {@code /v1/licenses/activate}. The key must belong to
     * {@code productId}, be active, and not have run out by {@code now}; any key from any Lemon Squeezy store would
     * otherwise pass.
     */
    public static Verdict judge(String body, long productId, Instant now) {
        JsonObject root;
        try {
            JsonElement parsed = JsonParser.parseString(body);
            if (!parsed.isJsonObject()) return Verdict.refused("The key server sent an answer Donut can't read. Try again later.");
            root = parsed.getAsJsonObject();
        } catch (RuntimeException e) {
            return Verdict.refused("The key server sent an answer Donut can't read. Try again later.");
        }
        JsonObject key = object(root, "license_key");
        JsonObject meta = object(root, "meta");
        String status = key == null ? "" : text(key, "status");
        Instant expiresAt = key == null ? null : instant(text(key, "expires_at"));
        // A key that ran out is worth saying so plainly, whatever else the answer holds.
        if (status.equals("expired") || expiresAt != null && !expiresAt.isAfter(now)) {
            return Verdict.refused("This key ran out" + (expiresAt == null ? "" : " on " + DAY.format(expiresAt))
                    + ". Buy a new one to keep going.");
        }
        if (status.equals("disabled")) return Verdict.refused("This key was turned off by the seller.");
        boolean passed = bool(root, "valid") || bool(root, "activated");
        String error = text(root, "error");
        if (!passed) return Verdict.refused(friendly(error));
        if (meta == null || number(meta, "product_id") != productId) return Verdict.refused("That key is for a different product.");
        JsonObject instance = object(root, "instance");
        String instanceId = instance == null ? null : text(instance, "id");
        return new Verdict(true, expiresAt == null ? "Key active." : "Key active until " + DAY.format(expiresAt) + ".",
                expiresAt, instanceId == null || instanceId.isBlank() ? null : instanceId);
    }

    /** Lemon Squeezy's error in plain words, for the ones a player is likely to meet. */
    static String friendly(String error) {
        if (error == null || error.isBlank()) return "That key isn't valid.";
        String lower = error.toLowerCase(Locale.ROOT);
        if (lower.contains("not found")) return "That key doesn't exist. Check it for typos.";
        if (lower.contains("activation limit")) return "This key is already in use on as many PCs as it allows.";
        if (lower.contains("instance")) return "This PC's activation was removed. Enter the key again.";
        return error.endsWith(".") ? error : error + ".";
    }

    private static JsonObject object(JsonObject parent, String name) {
        JsonElement element = parent.get(name);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    private static String text(JsonObject parent, String name) {
        JsonElement element = parent.get(name);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : "";
    }

    private static boolean bool(JsonObject parent, String name) {
        JsonElement element = parent.get(name);
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isBoolean() && element.getAsBoolean();
    }

    private static long number(JsonObject parent, String name) {
        JsonElement element = parent.get(name);
        try {
            return element != null && element.isJsonPrimitive() ? element.getAsLong() : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    static Instant instant(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
