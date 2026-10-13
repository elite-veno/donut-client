package dev.donut.client.gametest;

import com.mojang.blaze3d.platform.InputConstants;
import dev.donut.client.config.ClientConfig;
import dev.donut.client.config.ConfigStore;
import dev.donut.client.freecam.FreecamController;
import dev.donut.client.gui.DonutScreen;
import dev.donut.client.input.ModuleKeybinds;
import dev.donut.client.rtp.AutoRtpController;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import org.lwjgl.glfw.GLFW;

/** Uses the production screen, saved options and keyboard event route in an isolated game-test world. */
public final class ModuleKeybindGameTest implements FabricClientGameTest {
    private static final int[] TEST_KEYS = {GLFW.GLFW_KEY_F8, GLFW.GLFW_KEY_F9, GLFW.GLFW_KEY_F10, GLFW.GLFW_KEY_F7,
            GLFW.GLFW_KEY_F12, GLFW.GLFW_KEY_F4};
    /** Modules whose overview card fits above the fold; the others are bound from their settings page. */
    private static final int OVERVIEW_CARDS = 3;
    private static final int DEBRIS = 3, ACTIVITY = 4, AUTO_RTP = 5;
    /** Sidebar entry heights for the modules bound from their settings page. */
    private static final int[] SIDEBAR_Y = {0, 0, 0, 241, 273, 305};

    @Override
    public void runTest(ClientGameTestContext context) {
        ConfigStore store = new ConfigStore(FabricLoader.getInstance().getConfigDir().resolve("donut-client.json"));
        ClientConfig originalConfig = store.load();
        FreecamController freecam = FreecamController.getInstance();
        AutoRtpController rtp = AutoRtpController.getInstance();
        String[] originalKeys = context.computeOnClient(client -> new String[] {
                ModuleKeybinds.mapping(0).saveString(), ModuleKeybinds.mapping(1).saveString(),
                ModuleKeybinds.mapping(2).saveString(), ModuleKeybinds.mapping(3).saveString(),
                ModuleKeybinds.mapping(4).saveString(), ModuleKeybinds.mapping(5).saveString()});
        int originalGuiScale = context.computeOnClient(client -> client.options.guiScale().get());
        boolean originalHideGui = context.computeOnClient(client -> client.options.hideGui);
        KeyMapping openGui = context.computeOnClient(client -> Arrays.stream(client.options.keyMappings)
                .filter(key -> key.getName().equals("key.donut.open_gui")).findFirst().orElseThrow());
        String originalOpenKey = context.computeOnClient(client -> openGui.saveString());
        context.runOnClient(client -> {
            openGui.setKey(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_RIGHT_SHIFT));
            KeyMapping.resetMapping();
            client.options.guiScale().set(1);
            client.options.hideGui = false;
        });
        context.getInput().resizeWindow(1280, 800);

        try {
            try (var world = context.worldBuilder().create()) {
                world.getServer().runCommand("gamemode creative @a");
                world.getServer().runCommand("time set noon");
                world.getServer().runCommand("fill -10 64 -10 10 64 10 minecraft:smooth_stone");
                world.getServer().runCommand("setblock -2 65 0 minecraft:chest");
                world.getServer().runCommand("setblock 2 65 0 minecraft:spawner");
                world.getServer().runCommand("tp @a 0.5 65 8.5 180 10");
                context.waitTicks(5);
                world.getClientWorld().waitForChunksRender();
                context.waitFor(client -> client.player != null && client.player.onGround());

                try {
                    openGui(context, openGui);
                    if (!originalConfig.showHud) setHud(context, store, true);

                    for (int module = 0; module < OVERVIEW_CARDS; module++) {
                        clickPanel(context, 480, 171 + module * 77);
                        context.getInput().pressKey(TEST_KEYS[module]);
                        assertKey(context, module, TEST_KEYS[module]);
                    }
                    for (int module = OVERVIEW_CARDS; module < TEST_KEYS.length; module++) {
                        clickPanel(context, 70, SIDEBAR_Y[module]);
                        clickPanel(context, 442, 82); // Settings-header binding.
                        context.getInput().pressKey(TEST_KEYS[module]);
                        assertKey(context, module, TEST_KEYS[module]);
                    }
                    clickPanel(context, 70, 113); // Back to the overview.
                    require(store.load().storage.enabled == originalConfig.storage.enabled
                                    && store.load().spawner.enabled == originalConfig.spawner.enabled
                                    && store.load().debris.enabled == originalConfig.debris.enabled
                                    && store.load().activity.enabled == originalConfig.activity.enabled,
                            "Assigning keys in the GUI must not toggle finder modules");
                    context.runOnClient(client -> require(!rtp.isEnabled(), "Assigning the Auto RTP key must not start it"));
                    context.runOnClient(client -> require(!freecam.isActive(), "Assigning the Freecam key must not activate it"));
                    screenshot(context, "donut-keybind-01-assigned-normal");

                    // Escape cancels capture without closing the GUI or changing the existing binding.
                    clickPanel(context, 480, 171);
                    screenshot(context, "donut-keybind-02-listening");
                    context.getInput().holdKey(GLFW.GLFW_KEY_ESCAPE);
                    context.runOnClient(client -> client.screen.keyPressed(new KeyEvent(GLFW.GLFW_KEY_ESCAPE, 0, 0)));
                    context.runOnClient(client -> require(client.screen instanceof DonutScreen,
                            "A repeated Escape should not close the screen after cancelling key capture"));
                    context.getInput().releaseKey(GLFW.GLFW_KEY_ESCAPE);
                    assertKey(context, 0, TEST_KEYS[0]);

                    // Both documented clear shortcuts work, and keyboard capture can start again afterwards.
                    clickPanel(context, 480, 248);
                    context.getInput().pressKey(GLFW.GLFW_KEY_DELETE);
                    context.runOnClient(client -> require(ModuleKeybinds.mapping(1).isUnbound(),
                            "Delete did not clear the selected module binding"));
                    clickPanel(context, 480, 248);
                    context.getInput().pressKey(TEST_KEYS[1]);
                    clickPanel(context, 480, 171);
                    context.getInput().pressKey(GLFW.GLFW_KEY_BACKSPACE);
                    context.runOnClient(client -> require(ModuleKeybinds.mapping(0).isUnbound(),
                            "Backspace did not clear the selected module binding"));
                    clickPanel(context, 480, 171);
                    context.getInput().pressKey(TEST_KEYS[0]);

                    // A mouse binding goes through the same capture flow, then returns to a keyboard key.
                    clickPanel(context, 480, 325);
                    context.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_4);
                    context.runOnClient(client -> require(ModuleKeybinds.mapping(2).saveString().equals(
                                    InputConstants.Type.MOUSE.getOrCreate(GLFW.GLFW_MOUSE_BUTTON_4).getName()),
                            "Mouse button capture did not update the Freecam binding"));
                    clickPanel(context, 480, 325);
                    context.getInput().pressKey(TEST_KEYS[2]);

                    // Check actual disk persistence and reload, not just the current mapping object's value.
                    context.runOnClient(client -> {
                        String options;
                        try {
                            options = Files.readString(client.gameDirectory.toPath().resolve("options.txt"));
                        } catch (IOException error) {
                            throw new AssertionError("Cannot read the saved module bindings", error);
                        }
                        for (int module = 0; module < TEST_KEYS.length; module++) {
                            KeyMapping mapping = ModuleKeybinds.mapping(module);
                            require(options.contains("key_" + mapping.getName() + ":" + mapping.saveString()),
                                    "The assigned binding was not written to options.txt: " + mapping.getName());
                            mapping.setKey(InputConstants.UNKNOWN);
                        }
                        client.options.load();
                        KeyMapping.resetMapping();
                    });
                    for (int module = 0; module < TEST_KEYS.length; module++) assertKey(context, module, TEST_KEYS[module]);

                    context.runOnClient(client -> {
                        client.options.guiScale().set(2);
                        client.resizeDisplay();
                    });
                    context.waitTicks(2);
                    screenshot(context, "donut-keybind-03-assigned-scaled");
                    clickPanel(context, 290, 143);
                    clickPanel(context, 442, 82); // Settings-header binding at the scaled GUI size.
                    screenshot(context, "donut-keybind-04-settings-listening-scaled");
                    context.getInput().pressKey(TEST_KEYS[0]);
                    assertKey(context, 0, TEST_KEYS[0]);
                    context.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
                    context.waitForScreen(null);

                    // Holding a newly bound key toggles once; releasing it does not queue a second toggle.
                    boolean storageBefore = store.load().storage.enabled;
                    context.getInput().holdKey(TEST_KEYS[0]);
                    context.waitTicks(6);
                    require(store.load().storage.enabled != storageBefore, "The storage binding did not toggle its module");
                    // Simulate the click enqueued by GLFW_REPEAT while the physical key stays held.
                    context.runOnClient(client -> KeyMapping.click(InputConstants.Type.KEYSYM.getOrCreate(TEST_KEYS[0])));
                    context.waitTicks(2);
                    require(store.load().storage.enabled != storageBefore, "A repeated key event toggled the module again");
                    context.getInput().releaseKey(TEST_KEYS[0]);
                    context.waitTicks(2);
                    require(store.load().storage.enabled != storageBefore, "A held binding toggled more than once");
                    context.getInput().pressKey(TEST_KEYS[0]);
                    require(store.load().storage.enabled == storageBefore, "A second press did not turn storage back");

                    // Closing the GUI while an unrelated movement key is held must leave shortcuts usable.
                    openGui(context, openGui);
                    context.getInput().holdKey(options -> options.keySprint);
                    context.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
                    context.waitForScreen(null);
                    context.waitTicks(2);
                    context.getInput().pressKey(TEST_KEYS[0]);
                    require(store.load().storage.enabled != storageBefore,
                            "Holding Sprint after closing the GUI blocked a module shortcut");
                    context.getInput().releaseKey(options -> options.keySprint);
                    context.getInput().pressKey(TEST_KEYS[0]);
                    require(store.load().storage.enabled == storageBefore,
                            "Storage did not toggle back after releasing Sprint");

                    boolean spawnerBefore = store.load().spawner.enabled;
                    context.getInput().pressKey(TEST_KEYS[1]);
                    require(store.load().spawner.enabled != spawnerBefore, "The spawner binding did not toggle its module");
                    for (int module : new int[] {DEBRIS, ACTIVITY}) {
                        boolean before = finderEnabled(store.load(), module);
                        context.getInput().pressKey(TEST_KEYS[module]);
                        require(finderEnabled(store.load(), module) != before, "The binding of module " + module + " did not toggle it");
                        context.getInput().pressKey(TEST_KEYS[module]);
                        require(finderEnabled(store.load(), module) == before, "A second press did not turn module " + module + " back");
                    }
                    // Auto RTP sends an unknown command in this world; stopping it straight away is enough here.
                    context.getInput().pressKey(TEST_KEYS[AUTO_RTP]);
                    context.runOnClient(client -> require(rtp.isEnabled(), "The Auto RTP binding did not start it"));
                    context.getInput().pressKey(TEST_KEYS[AUTO_RTP]);
                    context.runOnClient(client -> require(!rtp.isEnabled() && !rtp.isActive(), "A second press did not stop Auto RTP"));
                    context.getInput().pressKey(TEST_KEYS[2]);
                    context.waitFor(client -> freecam.isActive());

                    // Ordinary key presses while a GUI is open must not toggle or replay after closing it.
                    openGui(context, openGui);
                    context.getInput().pressKey(TEST_KEYS[0]);
                    require(store.load().storage.enabled == storageBefore, "A module toggled while its GUI was open");
                    context.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
                    context.waitForScreen(null);
                    context.waitTicks(2);
                    require(store.load().storage.enabled == storageBefore, "A GUI key press replayed after closing it");

                    setFinder(context, store, 0, true);
                    setFinder(context, store, 1, true);
                    setFinder(context, store, DEBRIS, true);
                    setFinder(context, store, ACTIVITY, true);
                    screenshot(context, "donut-arraylist-01-active-scaled");
                    context.getInput().pressKey(GLFW.GLFW_KEY_F1);
                    context.runOnClient(client -> require(client.options.hideGui, "F1 did not hide the HUD"));
                    screenshot(context, "donut-arraylist-02-hidden-with-f1");
                    context.getInput().pressKey(GLFW.GLFW_KEY_F1);
                    context.runOnClient(client -> {
                        client.options.guiScale().set(1);
                        client.resizeDisplay();
                    });
                    context.waitTicks(2);
                    screenshot(context, "donut-arraylist-03-active-normal");
                    context.getInput().pressKey(TEST_KEYS[2]);
                    context.waitFor(client -> !freecam.isActive());
                    setFinder(context, store, 0, false);
                    setFinder(context, store, 1, false);
                    setFinder(context, store, DEBRIS, false);
                    setFinder(context, store, ACTIVITY, false);
                    screenshot(context, "donut-arraylist-04-disabled");
                } finally {
                    context.setScreen(() -> null);
                    context.runOnClient(client -> {
                        freecam.setEnabled(false);
                        rtp.setEnabled(false);
                        for (int module = 0; module < TEST_KEYS.length; module++) {
                            ModuleKeybinds.setKey(client, module, InputConstants.Type.KEYSYM.getOrCreate(TEST_KEYS[module]));
                        }
                    });
                    context.getInput().releaseKey(TEST_KEYS[0]);
                    context.getInput().releaseKey(options -> options.keySprint);
                    context.waitTicks(2);
                    setFinder(context, store, 0, originalConfig.storage.enabled);
                    setFinder(context, store, 1, originalConfig.spawner.enabled);
                    setFinder(context, store, DEBRIS, originalConfig.debris.enabled);
                    setFinder(context, store, ACTIVITY, originalConfig.activity.enabled);
                    if (!originalConfig.showHud) {
                        openGui(context, openGui);
                        setHud(context, store, false);
                        context.setScreen(() -> null);
                    }
                }
            }
        } finally {
            context.runOnClient(client -> {
                for (int module = 0; module < originalKeys.length; module++) {
                    ModuleKeybinds.mapping(module).setKey(InputConstants.getKey(originalKeys[module]));
                }
                openGui.setKey(InputConstants.getKey(originalOpenKey));
                KeyMapping.resetMapping();
                client.options.guiScale().set(originalGuiScale);
                client.options.hideGui = originalHideGui;
                client.options.save();
                client.resizeDisplay();
            });
        }
    }

    private static void openGui(ClientGameTestContext context, KeyMapping openGui) {
        context.getInput().pressKey(openGui);
        context.waitForScreen(DonutScreen.class);
        context.waitTicks(2);
    }

    private static void setFinder(ClientGameTestContext context, ConfigStore store, int module, boolean enabled) {
        if (finderEnabled(store.load(), module) != enabled) context.getInput().pressKey(TEST_KEYS[module]);
        require(finderEnabled(store.load(), module) == enabled, "Module " + module + " did not reach the requested enabled state");
    }

    private static boolean finderEnabled(ClientConfig config, int module) {
        return switch (module) {
            case 0 -> config.storage.enabled;
            case 1 -> config.spawner.enabled;
            case DEBRIS -> config.debris.enabled;
            case ACTIVITY -> config.activity.enabled;
            default -> throw new IllegalArgumentException("Not a finder module: " + module);
        };
    }

    private static void setHud(ClientGameTestContext context, ConfigStore store, boolean enabled) {
        clickPanel(context, 402, 25);
        if (store.load().showHud != enabled) clickPanel(context, 595, 199);
        require(store.load().showHud == enabled, "The array-list appearance setting did not update");
        clickPanel(context, 230, 25);
    }

    private static void assertKey(ClientGameTestContext context, int module, int key) {
        context.runOnClient(client -> require(ModuleKeybinds.mapping(module).saveString().equals(
                        InputConstants.Type.KEYSYM.getOrCreate(key).getName()),
                "The selected key was not assigned to module " + module));
    }

    private static void clickPanel(ClientGameTestContext context, double x, double y) {
        context.runOnClient(client -> {
            require(client.screen instanceof DonutScreen, "Donut screen is not open");
            var screen = client.screen;
            float scale = Math.max(0.1f, Math.min(1, Math.min((screen.width - 16f) / 650, (screen.height - 16f) / 408)));
            double actualX = (screen.width - 650 * scale) / 2 + x * scale;
            double actualY = (screen.height - 408 * scale) / 2 + y * scale;
            var event = new MouseButtonEvent(actualX, actualY, new MouseButtonInfo(GLFW.GLFW_MOUSE_BUTTON_LEFT, 0));
            require(screen.mouseClicked(event, false), "Donut screen did not consume mouse input");
            screen.mouseReleased(event);
        });
        context.waitTick();
    }

    private static void screenshot(ClientGameTestContext context, String name) {
        context.takeScreenshot(TestScreenshotOptions.of(name).withSize(1280, 800));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
