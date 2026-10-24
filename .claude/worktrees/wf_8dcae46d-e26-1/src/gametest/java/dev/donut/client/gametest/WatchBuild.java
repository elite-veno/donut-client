package dev.donut.client.gametest;

import com.mojang.blaze3d.platform.InputConstants;
import dev.donut.client.config.SchematicSettings;
import dev.donut.client.schematic.LitematicFile;
import dev.donut.client.schematic.Schematic;
import dev.donut.client.schematic.SchematicBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds a schematic in a normal game window, to watch the builder as a player on a server would see it: a fresh flat
 * survival world, a stone platform four blocks deep, every block in shulker boxes, dirt to climb with and a pickaxe,
 * and the builder switched on with the delays a real build uses. Only for development: it does nothing unless the game
 * starts with {@code -Ddonut.watch=<file.litematic>}, as {@code gradlew runWatch -PwatchFile=...} does. When the
 * build stops, every block is checked and the result goes to chat.
 */
public final class WatchBuild implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("Donut/Watch");
    private static final String WORLD = "Ven Watch";
    /** On the flat world's grass, whose top three blocks become the stone platform. */
    private static final BlockPos ORIGIN = new BlockPos(0, -60, 0);

    private enum Step { TITLE, LOADING, SETTING_UP, STARTING, BUILDING, DONE }

    /** Set by a test that needs the game to hold the mouse, as turning the free camera with it does. */
    static volatile boolean mouseHeld;

    private Step step = Step.TITLE;
    private int ticks;
    private Path file;
    private Schematic schematic;

    @Override
    public void onInitializeClient() {
        String property = System.getProperty("donut.watch", "");
        if (!property.isBlank() || System.getProperty("fabric.client.gametest") != null) {
            // Watched, or run as a test, beside other windows: the game never holds the mouse in its window. The
            // builder turns the camera itself.
            ClientTickEvents.END_CLIENT_TICK.register(client -> {
                if (!mouseHeld && client.mouseHandler.isMouseGrabbed()) client.mouseHandler.releaseMouse();
                // Silent: several of these windows play at once beside whoever works at the computer.
                var master = client.options.getSoundSourceOptionInstance(SoundSource.MASTER);
                if (master.get() > 0) master.set(0.0);
                // With no key or mouse input for ten minutes the game draws 10 frames a second, and a test runs a tick
                // a frame: the build would go on at half speed. Nobody types into these windows; they count as in use.
                client.getFramerateLimitTracker().onInputReceived();
            });
        }
        if (property.isBlank()) return;
        file = Path.of(property);
        if (!Files.isRegularFile(file)) {
            LOGGER.error("donut.watch names {}, which is not a file", file);
            return;
        }
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (step == Step.TITLE && screen instanceof TitleScreen) {
                step = Step.LOADING;
                client.execute(() -> createWorld(client));
            }
        });
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    /** Opens the create-world screen, fills it in as the client tests do, and presses Create. */
    private void createWorld(Minecraft client) {
        Path old = client.getLevelSource().getBaseDir().resolve(WORLD);
        if (Files.isDirectory(old)) {
            try (Stream<Path> paths = Files.walk(old)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            } catch (IOException e) {
                LOGGER.warn("Could not delete the last watch world", e);
            }
        }
        CreateWorldScreen.openFresh(client, () -> client.gui.setScreen(new TitleScreen()));
        if (!(client.gui.screen() instanceof CreateWorldScreen screen)) {
            LOGGER.error("The create-world screen did not open");
            return;
        }
        WorldCreationUiState state = screen.getUiState();
        state.setName(WORLD);
        state.setWorldType(new WorldCreationUiState.WorldTypeEntry(
                state.getSettings().worldgenLoadContext().lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT)));
        state.setSeed("1");
        state.setGenerateStructures(false);
        state.setAllowCommands(true);
        state.getGameRules().set(GameRules.ADVANCE_TIME, false, null);
        state.getGameRules().set(GameRules.ADVANCE_WEATHER, false, null);
        state.getGameRules().set(GameRules.SPAWN_MOBS, false, null);
        for (GuiEventListener child : screen.children()) {
            if (child instanceof Button button && button.getMessage().getContents() instanceof TranslatableContents key
                    && key.getKey().equals("selectWorld.create")) {
                button.onPress(new KeyEvent(InputConstants.KEY_RETURN, 0, 0));
                return;
            }
        }
        LOGGER.error("The create-world screen has no Create button");
    }

    private void tick(Minecraft client) {
        MinecraftServer server = client.getSingleplayerServer();
        if (client.player == null || server == null) return;
        SchematicBuilder builder = SchematicBuilder.getInstance();
        ticks++;
        switch (step) {
            case LOADING -> {
                // A moment for the chunks around the player.
                if (ticks < 60) return;
                setUp(client, server, builder);
                step = Step.SETTING_UP;
                ticks = 0;
            }
            case SETTING_UP -> {
                if (ticks < 40) return;
                // Watched beside other windows: the game goes on when another one has the focus.
                client.options.pauseOnLostFocus = false;
                say(client, "builds " + schematic.name + " as a player would on a server. Press F5 to watch from behind, or use Freecam.",
                        ChatFormatting.AQUA);
                builder.setEnabled(true);
                step = Step.STARTING;
                ticks = 0;
            }
            case STARTING -> {
                if (builder.isActive()) step = Step.BUILDING;
                else if (ticks > 100) step = Step.DONE;
            }
            case BUILDING -> {
                if (builder.isActive()) return;
                step = Step.DONE;
                check(client, server);
            }
            case TITLE, DONE -> { }
        }
    }

    /** Lays the platform, gives the materials and sets the builder up; the commands run on the server. */
    private void setUp(Minecraft client, MinecraftServer server, SchematicBuilder builder) {
        try {
            schematic = Schematic.of(LitematicFile.read(file), client.getFixerUpper());
            String name = SchematicBuilder.importFiles(List.of(file));
            SchematicSettings settings = builder.settings();
            settings.file = name;
            settings.originSet = true;
            settings.originX = ORIGIN.getX();
            settings.originY = ORIGIN.getY();
            settings.originZ = ORIGIN.getZ();
            settings.rotation = 0;
            settings.mirror = 0;
            settings.walk = true;
            settings.useShulkers = true;
            settings.redstoneLast = true;
            settings.helperBlocks = "dirt";
            String delay = System.getProperty("donut.watch.delay", "");
            if (delay.contains(",")) {
                String[] parts = delay.split(",");
                settings.setMaxDelay(Double.parseDouble(parts[1].strip()));
                settings.setMinDelay(Double.parseDouble(parts[0].strip()));
            } else {
                settings.setMaxDelay(SchematicSettings.DEFAULT_MAX_DELAY);
                settings.setMinDelay(SchematicSettings.DEFAULT_MIN_DELAY);
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.error("Could not read {}", file, e);
            say(client, "could not read " + file + ": " + e.getMessage(), ChatFormatting.RED);
            step = Step.DONE;
            return;
        }
        BlockPos size = schematic.max.subtract(schematic.min).offset(1, 1, 1);
        int x0 = ORIGIN.getX() + schematic.min.getX() - 12, z0 = ORIGIN.getZ() + schematic.min.getZ() - 12;
        int x1 = ORIGIN.getX() + schematic.max.getX() + 12, z1 = ORIGIN.getZ() + schematic.max.getZ() + 12;
        int top = ORIGIN.getY() + size.getY() + 12;
        Map<Item, Integer> bill = builder.bill();
        List<String> commands = new ArrayList<>(List.of(
                "difficulty peaceful", "time set noon", "weather clear",
                "fill " + x0 + " " + (ORIGIN.getY() - 3) + " " + z0 + " " + x1 + " " + (ORIGIN.getY() - 1) + " " + z1 + " minecraft:smooth_stone",
                "gamemode survival @a", "clear @a",
                "tp @a " + (ORIGIN.getX() + schematic.min.getX() - 3) + ".5 " + ORIGIN.getY() + " "
                        + (ORIGIN.getZ() + schematic.min.getZ() - 3) + ".5 -45 20"));
        // Air over the platform, a few layers at a time: one fill may change at most 32768 blocks.
        int layers = Math.max(1, 32768 / ((x1 - x0 + 1) * (z1 - z0 + 1)));
        for (int y = ORIGIN.getY(); y <= top; y += layers) {
            commands.add(2, "fill " + x0 + " " + y + " " + z0 + " " + x1 + " " + Math.min(top, y + layers - 1) + " " + z1 + " minecraft:air");
        }
        commands.addAll(BuildCheck.shulkerGives(bill));
        commands.addAll(List.of("give @a minecraft:dirt 448", "give @a minecraft:diamond_pickaxe", "give @a minecraft:diamond_shovel"));
        server.execute(() -> {
            for (String command : commands) server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command);
        });
        LOGGER.info("Set up {} ({} blocks) at {}", schematic.name, schematic.size(), ORIGIN.toShortString());
    }

    /** Checks every block on the server and says how the build came out. */
    private void check(Minecraft client, MinecraftServer server) {
        BlockPos size = schematic.max.subtract(schematic.min).offset(1, 1, 1);
        int x0 = ORIGIN.getX() + schematic.min.getX() - 12, z0 = ORIGIN.getZ() + schematic.min.getZ() - 12;
        int x1 = ORIGIN.getX() + schematic.max.getX() + 12, z1 = ORIGIN.getZ() + schematic.max.getZ() + 12;
        server.submit(() -> {
            var level = server.overworld();
            List<String> differences = BuildCheck.differences(level, schematic, ORIGIN);
            int holes = 0, dirt = 0;
            for (BlockPos pos : BlockPos.betweenClosed(x0, ORIGIN.getY() - 3, z0, x1, ORIGIN.getY() - 1, z1)) {
                if (!level.getBlockState(pos).is(Blocks.SMOOTH_STONE)) holes++;
            }
            for (BlockPos pos : BlockPos.betweenClosed(x0, ORIGIN.getY(), z0, x1, ORIGIN.getY() + size.getY() + 8, z1)) {
                if (level.getBlockState(pos).is(Blocks.DIRT)) dirt++;
            }
            return new int[] {differences.isEmpty() ? 0 : differences.size() - 1, holes, dirt};
        }).thenAcceptAsync(result -> {
            boolean good = result[0] == 0 && result[1] == 0 && result[2] == 0;
            say(client, "checked the build: " + (result[0] == 0 ? "every block matches the schematic" : result[0] + " blocks differ (see the log)")
                    + (result[1] == 0 ? ", the ground is whole" : ", " + result[1] + " blocks of ground dug out or filled otherwise")
                    + (result[2] == 0 ? ", no helper blocks left." : ", " + result[2] + " helper blocks left."),
                    good ? ChatFormatting.GREEN : ChatFormatting.YELLOW);
        }, client);
    }

    private static void say(Minecraft client, String text, ChatFormatting color) {
        if (client.player != null) client.player.sendSystemMessage(Component.literal("[Ven Watch] " + text).withStyle(color));
    }
}
