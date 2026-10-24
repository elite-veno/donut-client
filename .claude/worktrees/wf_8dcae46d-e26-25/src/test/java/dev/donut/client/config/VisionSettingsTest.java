package dev.donut.client.config;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Settings for X-ray, Fullbright, TunnelFinder, EntityFinder and the finder HUD. */
class VisionSettingsTest {
    @TempDir Path directory;

    @Test
    void newModulesStartOffWithUsefulDefaults() {
        ClientConfig config = new ClientConfig();

        assertFalse(config.xray.enabled);
        assertTrue(config.xray.hideStone);
        assertTrue(config.xray.hideNether);
        assertFalse(config.xray.hideLava);
        assertFalse(config.xray.exposedOresOnly);
        assertFalse(config.fullbright.enabled);
        assertTrue(config.fullbright.noFog);
        assertFalse(config.tunnels.enabled);
        assertTrue(config.tunnels.tunnels && config.tunnels.holes && config.tunnels.staircases);
        assertEquals(10, config.tunnels.minLength);
        assertFalse(config.entities.enabled);
        assertTrue(config.entities.players);
        assertFalse(config.entities.hostiles);
        assertTrue(config.entities.tracers);
        assertTrue(config.debris.filterFakes);
        assertTrue(config.debris.notify);
        assertTrue(config.spawner.activatedSpawners);
        assertTrue(config.hud.info && config.hud.labels);
    }

    @Test
    void anOlderConfigWithoutTheNewModulesGetsTheirDefaults() throws IOException {
        Path path = directory.resolve("donut.json");
        Files.writeString(path, """
                {"storage":{"enabled":true,"range":64},"debris":{"enabled":true,"color":8314254}}
                """);

        ClientConfig loaded = new ConfigStore(path).load();

        assertTrue(loaded.storage.enabled);
        assertTrue(loaded.storage.barrels && loaded.storage.hoppers && loaded.storage.furnaces);
        // A file from before version 2 gets DebrisFinder turned off; its other settings stay.
        assertFalse(loaded.debris.enabled);
        assertTrue(loaded.debris.filterFakes);
        assertEquals(0x7EDD8E, loaded.debris.color);
        // Fields the file lacks get DebrisFinder's own defaults, not the generic ones.
        assertEquals(0xFF7AC6, loaded.debris.secondaryColor);
        assertEquals(0xFFB347, loaded.debris.tertiaryColor);
        assertFalse(loaded.debris.perTypeColors);
        assertEquals(64, loaded.storage.range);
        assertNotNull(loaded.xray);
        assertNotNull(loaded.fullbright);
        assertNotNull(loaded.hud);
        assertEquals(0x68B4FF, loaded.tunnels.color);
        assertEquals(128, loaded.tunnels.range);
        assertEquals(0xFF5A5A, loaded.entities.color);
    }

    @Test
    void visionSettingsSurviveSaveAndReload() {
        Path path = directory.resolve("donut.json");
        ClientConfig config = new ClientConfig();
        config.xray.enabled = true;
        config.xray.hideWater = false;
        config.xray.hideLava = true;
        config.xray.exposedOresOnly = true;
        config.xray.extraHidden = "minecraft:obsidian, cobblestone";
        config.fullbright.enabled = true;
        config.fullbright.noFog = false;
        config.tunnels.enabled = true;
        config.tunnels.minLength = 14;
        config.tunnels.staircases = false;
        config.entities.enabled = true;
        config.entities.hostiles = true;
        config.hud.labels = false;
        config.hud.maxLabels = 20;
        config.storage.tracers = true;

        new ConfigStore(path).save(config);
        ClientConfig loaded = new ConfigStore(path).load();

        assertTrue(loaded.xray.enabled);
        assertFalse(loaded.xray.hideWater);
        assertTrue(loaded.xray.hideLava);
        assertTrue(loaded.xray.exposedOresOnly);
        assertEquals("minecraft:obsidian, cobblestone", loaded.xray.extraHidden);
        assertTrue(loaded.fullbright.enabled);
        assertFalse(loaded.fullbright.noFog);
        assertTrue(loaded.tunnels.enabled);
        assertEquals(14, loaded.tunnels.minLength);
        assertFalse(loaded.tunnels.staircases);
        assertTrue(loaded.entities.enabled);
        assertTrue(loaded.entities.hostiles);
        assertFalse(loaded.hud.labels);
        assertEquals(20, loaded.hud.maxLabels);
        assertTrue(loaded.storage.tracers);
    }

    @Test
    void outOfRangeValuesAreRepaired() {
        ClientConfig config = new ClientConfig();
        config.tunnels.minLength = 1;
        config.entities.minLength = Double.NaN;
        config.hud.maxLabels = 500;
        config.xray.extraHidden = null;
        config.tunnels.secondaryColor = -1;

        config.sanitize();

        assertEquals(4, config.tunnels.minLength);
        assertEquals(10, config.entities.minLength);
        assertEquals(32, config.hud.maxLabels);
        assertEquals("", config.xray.extraHidden);
        assertEquals(0xFFFFFF, config.tunnels.secondaryColor);
    }
}
