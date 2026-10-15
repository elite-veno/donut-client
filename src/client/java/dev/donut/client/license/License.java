package dev.donut.client.license;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The key a sale copy of Donut Client needs, sold through Lemon Squeezy. A copy built with {@code -PlicenseProductId}
 * carries that product's id and stays locked until a key for it is activated; any other build needs no key at all.
 * <p>
 * An activated key is kept in {@code donut-license.json} and checked again at every start and every six hours. When the
 * key server can't be reached, a key that passed within the last three days keeps working, but never past its end date.
 */
public final class License {
    private static final Logger LOGGER = LoggerFactory.getLogger("Donut/License");
    private static final Gson GSON = new Gson();
    private static final URI API = URI.create("https://api.lemonsqueezy.com/v1/licenses/");
    static final Duration GRACE = Duration.ofDays(3);
    private static final Duration RECHECK = Duration.ofHours(6);
    private static final String UNREACHABLE = "Couldn't reach the key server. Check your connection and try again.";

    public enum State { FREE, LOCKED, CHECKING, ACTIVE }

    /** What is kept on disk: the key, this PC's activation of it, when it runs out, and when it last passed. */
    record Stored(String key, String instanceId, String expiresAt, long checkedAt) { }

    /** An answer from the key server, or null for one that never came. */
    private record Answer(int status, String body) {
        boolean reached() { return status > 0 && status < 500 && status != 429; }
    }

    private final long productId;
    private final String buyUrl;
    private final Path file;
    private final Executor onClient;
    private final Supplier<String> instanceName;
    private final List<Runnable> listeners = new ArrayList<>();
    private HttpClient http;
    private volatile State state = State.FREE;
    private volatile String message = "";
    private volatile Stored stored;
    private volatile long lastAttempt;
    private volatile boolean busy;

    License(long productId, String buyUrl, Path file, Executor onClient, Supplier<String> instanceName) {
        this.productId = productId;
        this.buyUrl = buyUrl;
        this.file = file;
        this.onClient = onClient;
        this.instanceName = instanceName;
    }

    /** The license this build asks for, read from the {@code donut-license.properties} the build filled in. */
    public static License load(Path configDir, Executor onClient, Supplier<String> instanceName) {
        Properties properties = new Properties();
        try (InputStream in = License.class.getResourceAsStream("/donut-license.properties")) {
            if (in != null) properties.load(in);
        } catch (IOException e) {
            LOGGER.warn("Cannot read donut-license.properties; no key is needed", e);
        }
        long productId;
        try {
            productId = Long.parseLong(properties.getProperty("product_id", "").strip());
        } catch (NumberFormatException e) {
            productId = 0;
        }
        String buyUrl = properties.getProperty("buy_url", "").strip();
        return new License(Math.max(0, productId), buyUrl, configDir.resolve("donut-license.json"), onClient, instanceName);
    }

    /** Reads the saved key, if any, and checks it again in the background. */
    public void start() {
        if (!required()) return;
        stored = read();
        Instant now = Instant.now();
        if (stored == null) {
            set(State.LOCKED, "Enter your key. It's in the email you got after buying.");
            return;
        }
        if (withinGrace(stored, now)) {
            Instant ends = LicenseCheck.instant(stored.expiresAt());
            set(State.ACTIVE, ends == null ? "Key active." : "Key active until " + LicenseCheck.DAY.format(ends) + ".");
        } else {
            set(State.CHECKING, "Checking your key...");
        }
        recheck();
    }

    /** Runs every client tick: locks a key the moment it runs out, and checks it again every six hours. */
    public void tick() {
        if (!required() || busy) return;
        Stored kept = stored;
        Instant now = Instant.now();
        if (state == State.ACTIVE && kept != null) {
            Instant ends = LicenseCheck.instant(kept.expiresAt());
            if (ends != null && !ends.isAfter(now)) {
                set(State.LOCKED, "This key ran out on " + LicenseCheck.DAY.format(ends) + ". Buy a new one to keep going.");
                return;
            }
            if (now.toEpochMilli() - lastAttempt > RECHECK.toMillis()) recheck();
        }
    }

    /** Checks {@code key} and, if it belongs to this product, activates it on this PC. */
    public void activate(String key) {
        String clean = key == null ? "" : key.strip();
        if (!required() || busy) return;
        if (clean.isEmpty()) {
            set(State.LOCKED, "Enter your key first.");
            return;
        }
        Stored kept = stored;
        if (kept != null && kept.key().equals(clean) && kept.instanceId() != null) {
            recheck();
            return;
        }
        busy = true;
        lastAttempt = System.currentTimeMillis();
        set(State.CHECKING, "Checking your key...");
        // The key is checked before it is activated, so a key for something else never uses up one of its activations.
        post("validate", "license_key", clean).thenCompose(first -> {
            if (!first.reached()) return CompletableFuture.completedFuture(new Result(null, UNREACHABLE));
            LicenseCheck.Verdict verdict = LicenseCheck.judge(first.body(), productId, Instant.now());
            if (!verdict.ok()) return CompletableFuture.completedFuture(new Result(null, verdict.message()));
            return post("activate", "license_key", clean, "instance_name", instanceName.get()).thenApply(second -> {
                if (!second.reached()) return new Result(null, UNREACHABLE);
                LicenseCheck.Verdict activated = LicenseCheck.judge(second.body(), productId, Instant.now());
                if (!activated.ok() || activated.instanceId() == null) {
                    return new Result(null, activated.ok() ? "The key server didn't activate this PC. Try again." : activated.message());
                }
                return new Result(activated, activated.message());
            });
        }).whenComplete((result, failure) -> onClient.execute(() -> {
            busy = false;
            if (failure != null || result == null) {
                set(State.LOCKED, UNREACHABLE);
                return;
            }
            if (result.verdict() == null) {
                set(State.LOCKED, result.message());
                return;
            }
            LicenseCheck.Verdict v = result.verdict();
            stored = new Stored(clean, v.instanceId(), v.expiresAt() == null ? null : v.expiresAt().toString(), System.currentTimeMillis());
            write(stored);
            set(State.ACTIVE, v.message());
        }));
    }

    /** Checks the saved key again; a key the server turns down is forgotten, so the next start asks for one. */
    public void recheck() {
        Stored kept = stored;
        if (!required() || busy || kept == null) return;
        busy = true;
        lastAttempt = System.currentTimeMillis();
        if (state != State.ACTIVE) set(State.CHECKING, "Checking your key...");
        post("validate", "license_key", kept.key(), "instance_id", kept.instanceId() == null ? "" : kept.instanceId())
                .whenComplete((answer, failure) -> onClient.execute(() -> {
                    busy = false;
                    Instant now = Instant.now();
                    if (failure != null || answer == null || !answer.reached()) {
                        if (withinGrace(kept, now)) {
                            if (state != State.ACTIVE) set(State.ACTIVE, "Key active; the key server will be asked again later.");
                        } else {
                            set(State.LOCKED, UNREACHABLE);
                        }
                        return;
                    }
                    LicenseCheck.Verdict verdict = LicenseCheck.judge(answer.body(), productId, now);
                    if (!verdict.ok()) {
                        forget();
                        set(State.LOCKED, verdict.message());
                        return;
                    }
                    stored = new Stored(kept.key(), kept.instanceId(),
                            verdict.expiresAt() == null ? null : verdict.expiresAt().toString(), now.toEpochMilli());
                    write(stored);
                    set(State.ACTIVE, verdict.message());
                }));
    }

    /** Whether this build needs a key at all. */
    public boolean required() { return productId > 0; }

    /** Whether Donut Client must stay off: this build needs a key and has none that passed. */
    public boolean locked() { return required() && state != State.ACTIVE; }

    public State state() { return state; }

    public String message() { return message; }

    public String buyUrl() { return buyUrl; }

    /** The saved key with all but its last four characters hidden, or empty. */
    public String maskedKey() {
        Stored kept = stored;
        if (kept == null) return "";
        String key = kept.key();
        return key.length() <= 4 ? key : "*".repeat(Math.min(12, key.length() - 4)) + key.substring(key.length() - 4);
    }

    /** When the saved key runs out, or null. */
    public Instant expiresAt() {
        Stored kept = stored;
        return kept == null ? null : LicenseCheck.instant(kept.expiresAt());
    }

    /** Runs {@code listener} on the client thread whenever the state or its message changes. */
    public void onChange(Runnable listener) { listeners.add(listener); }

    /** A key that passed within the last three days and hasn't run out may be used without asking the server. */
    static boolean withinGrace(Stored kept, Instant now) {
        if (kept == null || kept.instanceId() == null) return false;
        Instant ends = LicenseCheck.instant(kept.expiresAt());
        if (ends != null && !ends.isAfter(now)) return false;
        long since = now.toEpochMilli() - kept.checkedAt();
        return since >= 0 && since < GRACE.toMillis();
    }

    private record Result(LicenseCheck.Verdict verdict, String message) { }

    private void set(State next, String text) {
        boolean changed = next != state || !text.equals(message);
        state = next;
        message = text;
        if (changed) for (Runnable listener : List.copyOf(listeners)) listener.run();
    }

    private CompletableFuture<Answer> post(String action, String... form) {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i + 1 < form.length; i += 2) {
            if (form[i + 1].isEmpty()) continue;
            if (!body.isEmpty()) body.append('&');
            body.append(form[i]).append('=').append(URLEncoder.encode(form[i + 1], StandardCharsets.UTF_8));
        }
        HttpRequest request = HttpRequest.newBuilder(API.resolve(action))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        try {
            if (http == null) http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
            return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenApply(response -> new Answer(response.statusCode(), response.body()))
                    .exceptionally(failure -> {
                        LOGGER.warn("Key server not reached: {}", failure.toString());
                        return new Answer(0, "");
                    });
        } catch (RuntimeException e) {
            LOGGER.warn("Key server not reached", e);
            return CompletableFuture.completedFuture(new Answer(0, ""));
        }
    }

    private Stored read() {
        if (!Files.exists(file)) return null;
        try {
            Stored kept = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Stored.class);
            return kept == null || kept.key() == null || kept.key().isBlank() ? null : kept;
        } catch (IOException | JsonParseException e) {
            LOGGER.warn("Cannot read the saved Donut key", e);
            return null;
        }
    }

    private void write(Stored kept) {
        try {
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temp, GSON.toJson(kept), StandardCharsets.UTF_8);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            LOGGER.warn("Cannot save the Donut key; it will be asked for again next start", e);
        }
    }

    private void forget() {
        stored = null;
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            LOGGER.warn("Cannot remove the saved Donut key", e);
        }
    }
}
