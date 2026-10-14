package dev.donut.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.donut.client.config.ClientConfig;
import dev.donut.client.config.ConfigStore;
import dev.donut.client.finder.DebrisLiveTest;
import dev.donut.client.finder.FinderScanner;
import dev.donut.client.freecam.FreecamController;
import dev.donut.client.fullbright.FullbrightController;
import dev.donut.client.gui.DonutScreen;
import dev.donut.client.gui.FinderHud;
import dev.donut.client.gui.ModuleArrayList;
import dev.donut.client.input.ModuleKeybinds;
import dev.donut.client.render.HighlightRenderer;
import dev.donut.client.rtp.AutoRtpController;
import dev.donut.client.xray.XrayController;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class DonutClient implements ClientModInitializer {
    public static final String MOD_ID = "donut";
    private static final Logger LOGGER = LoggerFactory.getLogger("Donut");
    private ConfigStore store;
    private ClientConfig config;
    private FinderScanner scanner;
    private boolean configProblemShown;

    @Override
    public void onInitializeClient() {
        store = new ConfigStore(FabricLoader.getInstance().getConfigDir().resolve("donut-client.json"));
        config = store.load();
        scanner = new FinderScanner(config);
        DebrisLiveTest.registerIfRequested(config, scanner);
        FreecamController freecam = new FreecamController(config);
        ClientTickEvents.START_CLIENT_TICK.register(freecam::tick);
        HighlightRenderer renderer = new HighlightRenderer(config, scanner);
        renderer.register();
        XrayController xray = new XrayController(config);
        FullbrightController fullbright = new FullbrightController(config);
        AutoRtpController rtp = new AutoRtpController(config, scanner, this::save);
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            // This runs inside packet handling, where an exception disconnects the player.
            try {
                rtp.onServerMessage(message.getString());
            } catch (RuntimeException e) {
                LOGGER.error("Auto RTP could not read a server message", e);
            }
        });

        KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "client"));
        KeyMapping open = bind("open_gui", GLFW.GLFW_KEY_RIGHT_SHIFT, category);
        KeyMapping storage = bind("toggle_storage", InputConstants.UNKNOWN.getValue(), category);
        KeyMapping spawner = bind("toggle_spawner", InputConstants.UNKNOWN.getValue(), category);
        // F6 is vanilla's debug options key.
        KeyMapping camera = bind("toggle_freecam", GLFW.GLFW_KEY_F7, category);
        KeyMapping debris = bind("toggle_debris", InputConstants.UNKNOWN.getValue(), category);
        KeyMapping activity = bind("toggle_activity", InputConstants.UNKNOWN.getValue(), category);
        KeyMapping autoRtp = bind("toggle_auto_rtp", InputConstants.UNKNOWN.getValue(), category);
        KeyMapping xrayKey = bind("toggle_xray", InputConstants.UNKNOWN.getValue(), category);
        KeyMapping fullbrightKey = bind("toggle_fullbright", InputConstants.UNKNOWN.getValue(), category);
        KeyMapping entities = bind("toggle_entities", InputConstants.UNKNOWN.getValue(), category);
        KeyMapping tunnels = bind("toggle_tunnels", InputConstants.UNKNOWN.getValue(), category);
        KeyMapping bases = bind("toggle_bases", InputConstants.UNKNOWN.getValue(), category);
        ModuleKeybinds.initialize(storage, spawner, camera, debris, activity, autoRtp, xrayKey, fullbrightKey, entities, tunnels, bases);

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            boolean inputBlocked = ModuleKeybinds.inputBlocked(client);
            while (open.consumeClick()) {
                if (!inputBlocked && client.gui.screen() == null) client.gui.setScreen(new DonutScreen(config, this::save, scanner::activityStats, rtp::status));
            }
            if (ModuleKeybinds.consumePress(client, 0)) {
                if (!inputBlocked && client.gui.screen() == null && client.player != null) { config.storage.enabled = !config.storage.enabled; save(); }
            }
            if (ModuleKeybinds.consumePress(client, 1)) {
                if (!inputBlocked && client.gui.screen() == null && client.player != null) { config.spawner.enabled = !config.spawner.enabled; save(); }
            }
            if (ModuleKeybinds.consumePress(client, 2)) {
                if (!inputBlocked && client.gui.screen() == null && client.player != null) freecam.setEnabled(!freecam.isActive());
            }
            if (ModuleKeybinds.consumePress(client, 3)) {
                if (!inputBlocked && client.gui.screen() == null && client.player != null) { config.debris.enabled = !config.debris.enabled; save(); }
            }
            if (ModuleKeybinds.consumePress(client, 4)) {
                if (!inputBlocked && client.gui.screen() == null && client.player != null) { config.activity.enabled = !config.activity.enabled; save(); }
            }
            if (ModuleKeybinds.consumePress(client, 5)) {
                if (!inputBlocked && client.gui.screen() == null && client.player != null) rtp.setEnabled(!config.rtp.enabled);
            }
            if (ModuleKeybinds.consumePress(client, 6)) {
                if (!inputBlocked && client.gui.screen() == null && client.player != null) { config.xray.enabled = !config.xray.enabled; save(); }
            }
            if (ModuleKeybinds.consumePress(client, 7)) {
                if (!inputBlocked && client.gui.screen() == null && client.player != null) { config.fullbright.enabled = !config.fullbright.enabled; save(); }
            }
            if (ModuleKeybinds.consumePress(client, 8)) {
                if (!inputBlocked && client.gui.screen() == null && client.player != null) { config.entities.enabled = !config.entities.enabled; save(); }
            }
            if (ModuleKeybinds.consumePress(client, 9)) {
                if (!inputBlocked && client.gui.screen() == null && client.player != null) { config.tunnels.enabled = !config.tunnels.enabled; save(); }
            }
            if (ModuleKeybinds.consumePress(client, 10)) {
                if (!inputBlocked && client.gui.screen() == null && client.player != null) { config.bases.enabled = !config.bases.enabled; save(); }
            }
            xray.tick(client);
            fullbright.tick();
            scanner.tick(client);
            rtp.tick(client);
        });

        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (screen instanceof TitleScreen) {
                Screens.getWidgets(screen).add(Button.builder(Component.literal("Donut"), button ->
                        client.gui.setScreen(new DonutScreen(config, this::save, scanner::activityStats, rtp::status)))
                        .bounds(width - 66, height - 28, 58, 20).build());
            }
        });

        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(MOD_ID, "status"), (graphics, delta) -> {
            Minecraft client = Minecraft.getInstance();
            if (!config.showHud || client.player == null || client.gui.screen() != null || client.gui.hud.isHidden()) return;
            ModuleArrayList.render(graphics, client.font, client.getWindow().getGuiScaledWidth(), config, freecam.isActive());
        });
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(MOD_ID, "finder_info"), (graphics, delta) -> {
            Minecraft client = Minecraft.getInstance();
            // Typing in chat keeps the labels: they are often what is being typed about.
            boolean chatting = client.gui.screen() instanceof ChatScreen;
            if (client.player == null || client.gui.screen() != null && !chatting || client.gui.hud.isHidden()) return;
            FinderHud.render(graphics, client, config, scanner);
        });
        // GoNuts's own HUD keeps its panel, alerts and nearby-player list off both.
        ModuleArrayList.reserveSpace(config, freecam::isActive);
        FinderHud.reserveSpace(config, scanner);
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> xray.close());

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> client.execute(() -> {
            // A damaged settings file is otherwise only in the log.
            if (configProblemShown || store.problem().isEmpty() || client.player == null) return;
            configProblemShown = true;
            client.player.sendSystemMessage(Component.literal("[Donut] " + store.problem()).withStyle(net.minecraft.ChatFormatting.YELLOW));
        }));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            // Fabric can fire this on the network thread when the connection drops. The tick handlers notice the
            // missing world on their own as well, so a task that a disconnect drops costs nothing.
            Runnable cleanup = () -> {
                scanner.clear();
                freecam.disable();
            };
            if (client.isSameThread()) cleanup.run();
            else client.execute(cleanup);
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            freecam.disable();
            save();
            renderer.close();
        });
    }

    private KeyMapping bind(String name, int key, KeyMapping.Category category) {
        return KeyMappingHelper.registerKeyMapping(new KeyMapping("key.donut." + name, InputConstants.Type.KEYSYM, key, category));
    }

    private void save() { store.save(config); }
}
