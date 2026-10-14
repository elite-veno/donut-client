package dev.donut.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ConfigStore {
    private static final Logger LOGGER = LoggerFactory.getLogger("Donut/Config");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path path;
    /** The file could not be read nor backed up: saving over it would lose the user's settings for good. */
    private boolean readOnly;
    private String problem = "";

    public ConfigStore(Path path) { this.path = path; }

    /** What went wrong loading the file, for a one-time message in game, or empty. */
    public String problem() { return problem; }

    /**
     * The file is laid over the defaults, so a setting the file lacks gets its own module's default, not a generic
     * one. Each top-level section is checked on its own: one bad value resets only its module.
     */
    public ClientConfig load() {
        if (!Files.exists(path)) return new ClientConfig();
        JsonObject file;
        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (!parsed.isJsonObject()) throw new IllegalArgumentException("Not a configuration object");
            file = parsed.getAsJsonObject();
        } catch (IOException e) {
            LOGGER.warn("Cannot read Donut config; using defaults", e);
            if (!backUp()) readOnly = true;
            problem = "Donut couldn't read its settings file, so it is using defaults" + (readOnly ? " and won't save this session." : ".");
            return new ClientConfig();
        } catch (RuntimeException e) {
            LOGGER.warn("Donut config is not valid JSON; using defaults", e);
            if (!backUp()) readOnly = true;
            problem = "Donut's settings file was damaged; defaults are in use" + (readOnly ? "." : " and the old file was kept beside it.");
            return new ClientConfig();
        }
        JsonObject merged = GSON.toJsonTree(new ClientConfig()).getAsJsonObject();
        List<String> failed = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : file.entrySet()) {
            String key = entry.getKey();
            if (!merged.has(key)) continue;
            JsonObject attempt = merged.deepCopy();
            attempt.add(key, overlay(attempt.get(key), entry.getValue()));
            try {
                GSON.fromJson(attempt, ClientConfig.class);
                merged = attempt;
            } catch (RuntimeException e) {
                LOGGER.warn("Donut config section '{}' is invalid; using its defaults", key, e);
                failed.add(key);
            }
        }
        ClientConfig result = GSON.fromJson(merged, ClientConfig.class);
        result.sanitize();
        if (!failed.isEmpty()) {
            boolean kept = backUp();
            problem = "Donut reset these settings, which were damaged: " + String.join(", ", failed)
                    + (kept ? ". The old file was kept beside it." : ".");
        }
        return result;
    }

    /** The file laid over the defaults, object by object; anything else in the file replaces the default. */
    private static JsonElement overlay(JsonElement defaults, JsonElement file) {
        if (!defaults.isJsonObject() || !file.isJsonObject()) return file;
        JsonObject result = defaults.getAsJsonObject().deepCopy();
        for (Map.Entry<String, JsonElement> entry : file.getAsJsonObject().entrySet()) {
            JsonElement base = result.get(entry.getKey());
            result.add(entry.getKey(), base == null ? entry.getValue() : overlay(base, entry.getValue()));
        }
        return result;
    }

    private boolean backUp() {
        try {
            Files.copy(path, path.resolveSibling(path.getFileName() + ".invalid-" + System.currentTimeMillis()), StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException e) {
            LOGGER.warn("Could not back up the invalid configuration", e);
            return false;
        }
    }

    public void save(ClientConfig config) {
        config.sanitize();
        if (readOnly) return;
        Path temporary = null;
        try {
            Path directory = path.toAbsolutePath().getParent();
            Files.createDirectories(directory);
            // A unique name, so two game instances sharing the folder can't write over each other's half-written file.
            temporary = Files.createTempFile(directory, path.getFileName().toString(), ".tmp");
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer bytes = StandardCharsets.UTF_8.encode(GSON.toJson(config));
                while (bytes.hasRemaining()) channel.write(bytes);
                // On disk before the rename, so a power cut can't leave an empty settings file behind.
                channel.force(true);
            }
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOGGER.error("Could not save Donut configuration to {}", path, e);
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // Left for the next save's directory.
                }
            }
        }
    }
}
