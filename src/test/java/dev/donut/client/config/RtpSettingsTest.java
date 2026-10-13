package dev.donut.client.config;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RtpSettingsTest {
    @TempDir Path directory;

    @Test
    void autoRtpNeverStartsFromSavedOrHandEditedConfiguration() throws IOException {
        Path path = directory.resolve("donut.json");
        ConfigStore store = new ConfigStore(path);
        ClientConfig config = new ClientConfig();
        config.rtp.enabled = true;
        config.rtp.command = "rtp world";
        config.rtp.menuItem = "Wild";
        config.rtp.intervalSeconds = 45;
        config.rtp.stopAtGrowth = 0.7;
        config.rtp.scanSeconds = 12;
        config.rtp.maxAttempts = 7;
        config.rtp.stopWhenHurt = false;

        store.save(config);

        var json = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        assertFalse(json.getAsJsonObject("rtp").has("enabled"));
        ClientConfig loaded = store.load();
        assertFalse(loaded.rtp.enabled);
        assertEquals("rtp world", loaded.rtp.command);
        assertEquals("Wild", loaded.rtp.menuItem);
        assertEquals(45, loaded.rtp.intervalSeconds);
        assertEquals(0.7, loaded.rtp.stopAtGrowth);
        assertEquals(12, loaded.rtp.scanSeconds);
        assertEquals(7, loaded.rtp.maxAttempts);
        assertFalse(loaded.rtp.stopWhenHurt);

        Files.writeString(path, """
                {"rtp":{"enabled":true,"command":"rtp"}}
                """);
        assertFalse(store.load().rtp.enabled, "A JSON enabled field must not start teleporting on join");
    }

    @Test
    void olderAndNullConfigurationsRestoreDefaults() throws IOException {
        Path path = directory.resolve("donut.json");
        ConfigStore store = new ConfigStore(path);
        for (String json : new String[] {"{}", "{\"rtp\":null}"}) {
            Files.writeString(path, json);
            RtpSettings loaded = store.load().rtp;
            assertNotNull(loaded);
            assertFalse(loaded.enabled);
            assertEquals("rtp", loaded.command);
            assertEquals("Overworld", loaded.menuItem);
            assertEquals(20, loaded.intervalSeconds);
            assertEquals(0.55, loaded.stopAtGrowth);
            assertEquals(8, loaded.scanSeconds);
            assertEquals(25, loaded.maxAttempts);
            assertTrue(loaded.stopWhenHurt);
        }
    }

    @Test
    void commandsLoseSlashesAndBlankValuesFallBack() {
        RtpSettings settings = new RtpSettings();
        for (String[] pair : new String[][] {{"/rtp ", "rtp"}, {"//rtp world", "rtp world"}, {"  ", "rtp"}, {null, "rtp"}, {"/", "rtp"}}) {
            settings.command = pair[0];
            settings.sanitize();
            assertEquals(pair[1], settings.command, "command: " + pair[0]);
        }
        settings.menuItem = null;
        settings.sanitize();
        assertEquals("Overworld", settings.menuItem);
        settings.menuItem = "  Nether  ";
        settings.sanitize();
        assertEquals("Nether", settings.menuItem);
    }

    @Test
    void timingIsClampedAndNonFiniteValuesUseDefaults() {
        RtpSettings low = new RtpSettings();
        low.intervalSeconds = 0;
        low.scanSeconds = 0;
        low.maxAttempts = -5;
        low.stopAtGrowth = -1;
        RtpSettings high = new RtpSettings();
        high.intervalSeconds = 10_000;
        high.scanSeconds = 1_000;
        high.maxAttempts = 10_000;
        high.stopAtGrowth = 3;
        RtpSettings broken = new RtpSettings();
        broken.intervalSeconds = Double.NaN;
        broken.scanSeconds = Double.POSITIVE_INFINITY;
        broken.stopAtGrowth = Double.NaN;

        low.sanitize();
        high.sanitize();
        broken.sanitize();

        assertEquals(5, low.intervalSeconds);
        assertEquals(2, low.scanSeconds);
        assertEquals(1, low.maxAttempts);
        assertEquals(0, low.stopAtGrowth);
        assertEquals(1, high.stopAtGrowth);
        assertEquals(0.55, broken.stopAtGrowth);
        assertEquals(600, high.intervalSeconds);
        assertEquals(30, high.scanSeconds);
        assertEquals(200, high.maxAttempts);
        assertEquals(20, broken.intervalSeconds);
        assertEquals(8, broken.scanSeconds);
    }
}
