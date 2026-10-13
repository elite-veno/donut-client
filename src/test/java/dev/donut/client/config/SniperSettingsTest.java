package dev.donut.client.config;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SniperSettingsTest {
    @TempDir Path directory;

    @Test
    void neitherRunningNorLiveModeSurvivesARestart() throws IOException {
        Path path = directory.resolve("donut.json");
        ConfigStore store = new ConfigStore(path);
        ClientConfig config = new ClientConfig();
        config.sniper.enabled = true;
        config.sniper.live = true;
        config.sniper.apiKey = " key-123 ";
        config.sniper.minDeviation = 3;
        config.sniper.maxPrice = 2_500_000;

        store.save(config);

        var json = JsonParser.parseString(Files.readString(path)).getAsJsonObject().getAsJsonObject("sniper");
        assertFalse(json.has("enabled"));
        assertFalse(json.has("live"));
        ClientConfig loaded = store.load();
        assertFalse(loaded.sniper.enabled);
        assertFalse(loaded.sniper.live, "every session starts in paper mode");
        assertEquals("key-123", loaded.sniper.apiKey);
        assertEquals(3, loaded.sniper.minDeviation);
        assertEquals(2_500_000, loaded.sniper.maxPrice);

        Files.writeString(path, "{\"sniper\":{\"live\":true,\"enabled\":true}}");
        loaded = store.load();
        assertFalse(loaded.sniper.live, "hand-edited JSON must not switch on real purchases");
        assertFalse(loaded.sniper.enabled);
    }

    @Test
    void valuesAreClampedAndCommandsCleaned() {
        SniperSettings settings = new SniperSettings();
        settings.minDeviation = 0;
        settings.minMargin = Double.NaN;
        settings.taxPercent = 90;
        settings.minSales = 0;
        settings.pollSeconds = 0.1;
        settings.searchCommand = "/ah {item}";
        settings.sellCommand = "  ";
        settings.confirmText = null;
        settings.apiKey = null;

        settings.sanitize();

        assertEquals(1, settings.minDeviation);
        assertEquals(0.15, settings.minMargin);
        assertEquals(50, settings.taxPercent);
        assertEquals(3, settings.minSales);
        assertEquals(2, settings.pollSeconds);
        assertEquals("ah {item}", settings.searchCommand);
        assertEquals("ah sell {price}", settings.sellCommand);
        assertEquals("confirm", settings.confirmText);
        assertEquals("", settings.apiKey);
    }
}
