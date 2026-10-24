package dev.donut.client.config;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GuiSettingsTest {
    @TempDir Path directory;

    @Test
    void panelPlacesSurviveSaveAndReload() {
        ConfigStore store = new ConfigStore(directory.resolve("donut.json"));
        ClientConfig config = new ClientConfig();
        GuiSettings.Panel finders = new GuiSettings.Panel();
        finders.x = 140;
        finders.y = -12;
        finders.open = false;
        config.gui.panels.put("finders", finders);
        store.save(config);

        GuiSettings.Panel loaded = store.load().gui.panels.get("finders");

        assertNotNull(loaded);
        assertEquals(140, loaded.x);
        assertEquals(-12, loaded.y);
        assertFalse(loaded.open);
    }

    @Test
    void olderConfigsWithoutALayoutGetAnEmptyOne() throws IOException {
        Path path = directory.resolve("donut.json");
        Files.writeString(path, "{\"accentColor\": 1234}");

        ClientConfig config = new ConfigStore(path).load();

        assertNotNull(config.gui);
        assertTrue(config.gui.panels.isEmpty());
    }

    @Test
    void brokenOrAbsurdPanelEntriesAreRepaired() throws IOException {
        Path path = directory.resolve("donut.json");
        Files.writeString(path, "{\"gui\": {\"panels\": {\"render\": null, \"client\": {\"x\": 999999, \"y\": -999999}}}}");

        ClientConfig config = new ConfigStore(path).load();

        assertFalse(config.gui.panels.containsKey("render"));
        GuiSettings.Panel client = config.gui.panels.get("client");
        assertEquals(4096, client.x);
        assertEquals(-4096, client.y);
        assertTrue(client.open);

        config.gui.panels = null;
        config.sanitize();
        assertNotNull(config.gui.panels);
    }
}
