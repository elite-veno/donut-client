package dev.donut.client.gametest;

import dev.donut.client.config.ClientConfig;
import dev.donut.client.config.RenderMode;
import dev.donut.client.finder.ActivityArea;
import dev.donut.client.finder.FinderScanner;
import dev.donut.client.finder.FoundBlock;
import dev.donut.client.finder.TargetKind;
import dev.donut.client.gui.DonutScreen;
import dev.donut.client.render.HighlightRenderer;
import java.util.EnumSet;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import org.lwjgl.glfw.GLFW;

/** Exercises real GUI input, loaded block entities, and every overlay pipeline in a disposable flat world. */
public final class DonutClientGameTest implements FabricClientGameTest {
    private static final EnumSet<TargetKind> ALL_KINDS = EnumSet.allOf(TargetKind.class);

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientConfig config = new ClientConfig();
        config.storage.distanceFade = false;
        config.spawner.distanceFade = false;
        config.debris.distanceFade = false;
        config.activity.distanceFade = false;
        FinderScanner scanner = new FinderScanner(config);
        AtomicBoolean active = new AtomicBoolean(true);
        AtomicInteger saves = new AtomicInteger();
        HighlightRenderer renderer = context.computeOnClient(client -> {
            client.options.guiScale().set(1);
            HighlightRenderer created = new HighlightRenderer(config, scanner);
            created.register();
            ClientTickEvents.END_CLIENT_TICK.register(mc -> {
                if (active.get()) scanner.tick(mc);
            });
            return created;
        });
        context.getInput().resizeWindow(1280, 800);

        try (var world = context.worldBuilder().create()) {
            createFixtures(world.getServer());
            context.waitTicks(5);
            world.getClientWorld().waitForChunksRender();
            context.waitFor(client -> client.player != null && Math.abs(client.player.getY() - 65) < 0.1);
            context.runOnClient(client -> require(scanner.snapshot().isEmpty(), "Disabled modules must not find targets"));
            screenshot(context, "donut-00-fixtures-disabled");

            // Use the production screen and mouse event path; all changes stay in the test's config.
            context.setScreen(() -> new DonutScreen(config, saves::incrementAndGet));
            context.waitForScreen(DonutScreen.class);
            context.waitTicks(2);
            clickPanel(context, 592, 152); // StorageFinder toggle.
            clickPanel(context, 592, 229); // SpawnerFinder toggle.
            context.runOnClient(client -> require(config.storage.enabled && config.spawner.enabled,
                    "Module toggles did not update the supplied config"));
            require(saves.get() >= 2, "Changing module toggles must invoke the save callback");
            // The DebrisFinder card sits below the fold, so enable it from its own settings page.
            clickPanel(context, 70, 253); // Debris sidebar entry.
            context.waitTick();
            clickPanel(context, 610, 86); // Header toggle.
            context.runOnClient(client -> require(config.debris.enabled, "Debris toggle did not update the supplied config"));
            screenshot(context, "donut-01-clickgui-debris-settings");
            clickPanel(context, 70, 288); // Activity sidebar entry.
            clickPanel(context, 610, 86); // Header toggle.
            context.runOnClient(client -> require(config.activity.enabled, "Activity toggle did not update the supplied config"));
            screenshot(context, "donut-01-clickgui-activity-settings");
            context.getInput().pressKey(GLFW.GLFW_KEY_END);
            context.waitTicks(10);
            screenshot(context, "donut-01-clickgui-activity-detection");
            clickPanel(context, 70, 113); // Back to the overview.
            context.waitTick();
            awaitKinds(context, scanner, ALL_KINDS);
            screenshot(context, "donut-01-clickgui-modules");

            clickPanel(context, 290, 143); // Open StorageFinder settings.
            context.waitTick();
            clickPanel(context, 372, 229); // Outline mode.
            context.runOnClient(client -> require(config.storage.mode == RenderMode.OUTLINE,
                    "Render mode button did not select Outline"));
            clickPanel(context, 384, 281); // Outline opacity slider at 50%.
            context.runOnClient(client -> require(Math.abs(config.storage.outlineOpacity - 0.5) < 0.02,
                    "Opacity slider did not update the setting"));
            screenshot(context, "donut-02-clickgui-storage-settings");
            context.runOnClient(client -> client.screen.onClose());
            context.waitForScreen(null);

            // Confirm a user filter changes the actual loaded-entity results.
            context.runOnClient(client -> config.storage.droppers = false);
            EnumSet<TargetKind> withoutDropper = EnumSet.copyOf(ALL_KINDS);
            withoutDropper.remove(TargetKind.DROPPER);
            awaitKinds(context, scanner, withoutDropper);
            context.runOnClient(client -> {
                config.storage.droppers = true;
                config.storage.outlineOpacity = 0.95;
                config.spawner.outlineOpacity = 0.95;
                config.debris.outlineOpacity = 0.95;
                config.storage.fillOpacity = 0.28;
                config.spawner.fillOpacity = 0.28;
                config.debris.fillOpacity = 0.28;
                config.storage.lineWidth = 3;
                config.spawner.lineWidth = 3;
                config.debris.lineWidth = 3;
            });
            awaitKinds(context, scanner, ALL_KINDS);
            screenshot(context, "donut-03-fixtures-highlighted");

            // An opaque wall hides the right-hand fixtures, distinguishing both depth policies visually.
            world.getServer().runCommand("fill 1 65 4 8 68 4 minecraft:stone_bricks");
            context.waitTicks(3);
            world.getClientWorld().waitForChunksRender(false);
            for (RenderMode mode : RenderMode.values()) {
                for (boolean throughWalls : new boolean[] {false, true}) {
                    context.runOnClient(client -> {
                        config.storage.mode = mode;
                        config.spawner.mode = mode;
                        config.debris.mode = mode;
                        config.storage.throughWalls = throughWalls;
                        config.spawner.throughWalls = throughWalls;
                        config.debris.throughWalls = throughWalls;
                    });
                    context.waitTicks(2);
                    screenshot(context, "donut-04-" + mode.name().toLowerCase(Locale.ROOT)
                            + (throughWalls ? "-through-walls" : "-visible-only"));
                }
            }

            // Natural debris is sealed in netherrack, often in another chunk, and is placed after that chunk was indexed.
            world.getServer().runCommand("fill 16 63 -15 18 65 -13 minecraft:netherrack");
            world.getServer().runCommand("setblock 17 64 -14 minecraft:ancient_debris");
            context.waitFor(client -> scanner.debrisCount() == 2);
            context.runOnClient(client -> require(scanner.snapshot().stream()
                            .anyMatch(target -> target.kind().isDebris() && target.pos().equals(new BlockPos(17, 64, -14))),
                    "Buried debris in a neighboring chunk was not found"));
            screenshot(context, "donut-05-debris-buried");
            // Mined debris must not wait for its chunk's periodic rescan.
            world.getServer().runCommand("setblock 8 65 0 minecraft:air");
            context.waitFor(client -> scanner.debrisCount() == 1, 15);

            // A geode as worldgen leaves it stays unmarked; one with a cluster on every open face marks its chunk.
            createGeode(world.getServer(), -9, -25, false);
            createGeode(world.getServer(), 5, -25, true);
            // Ground over the grown geode, below eye level so the surface square is seen from above.
            world.getServer().runCommand("fill 0 63 -32 15 63 -17 minecraft:grass_block");
            ChunkPos fresh = new ChunkPos(-1, -2), grown = new ChunkPos(0, -2);
            context.runOnClient(client -> config.activity.minActivity = 0);
            context.waitFor(client -> scanner.activity().size() == 2
                    && scanner.activity().stream().anyMatch(area -> area.surfaceY() == 63));
            context.runOnClient(client -> {
                for (ActivityArea area : scanner.activity()) {
                    require(area.budding() == 8 && area.minY() == 59 && area.maxY() == 59, "Unexpected geode bounds: " + area);
                    if (area.chunk().equals(fresh)) require(area.score() < 0.05, "A worldgen-like geode scored " + area.score());
                    else require(area.chunk().equals(grown) && area.score() == 1 && area.surfaceY() == 63,
                            "A fully grown geode under grass at y 63 was read as " + area);
                }
            });
            context.runOnClient(client -> config.activity.minActivity = 0.5);
            context.waitFor(client -> scanner.activity().size() == 1 && scanner.activity().getFirst().chunk().equals(grown));
            context.waitTicks(2);
            screenshot(context, "donut-06-activity-surface");
            context.runOnClient(client -> config.activity.surfaceMarker = false);
            context.waitTicks(2);
            screenshot(context, "donut-06-activity-geode-only");
            context.runOnClient(client -> config.activity.surfaceMarker = true);

            // All loaded chunks reaches past the detection range, which only applies once it is turned off.
            createGeode(world.getServer(), 52, -42, true);
            ChunkPos distant = new ChunkPos(3, -3);
            context.runOnClient(client -> config.activity.range = 48);
            context.waitFor(client -> scanner.activity().size() == 2
                    && scanner.activity().stream().anyMatch(area -> area.chunk().equals(distant)));
            context.runOnClient(client -> require(scanner.activityRange() > 150,
                    "All loaded chunks should cover the render distance, not " + scanner.activityRange()));
            screenshot(context, "donut-07-activity-all-loaded");
            context.runOnClient(client -> config.activity.allLoadedChunks = false);
            context.waitFor(client -> scanner.activity().size() == 1 && scanner.activity().getFirst().chunk().equals(grown));
            context.runOnClient(client -> {
                config.activity.allLoadedChunks = true;
                config.activity.range = 96;
            });

            context.runOnClient(client -> {
                config.storage.enabled = false;
                config.spawner.enabled = false;
                config.debris.enabled = false;
                config.activity.enabled = false;
            });
            context.waitFor(client -> scanner.snapshot().isEmpty() && scanner.activity().isEmpty());
        } finally {
            active.set(false);
            context.runOnClient(client -> {
                scanner.clear();
                renderer.close();
            });
        }
    }

    private static void createFixtures(TestServerContext server) {
        server.runCommand("gamemode creative @a");
        server.runCommand("time set noon");
        server.runCommand("fill -10 64 -2 10 64 16 minecraft:smooth_stone");
        server.runCommand("setblock -6 65 0 minecraft:chest");
        server.runCommand("setblock -4 65 0 minecraft:trapped_chest");
        server.runCommand("setblock -2 65 0 minecraft:ender_chest");
        server.runCommand("setblock 0 65 0 minecraft:purple_shulker_box");
        server.runCommand("setblock 2 65 0 minecraft:dropper");
        server.runCommand("setblock 4 65 0 minecraft:spawner");
        server.runCommand("setblock 6 65 0 minecraft:trial_spawner");
        server.runCommand("setblock 8 65 0 minecraft:ancient_debris");
        server.runCommand("tp @a 0.5 65 12.5 180 12");
    }

    /** Eight budding blocks in calcite, each with one open face upward. */
    private static void createGeode(TestServerContext server, int x, int z, boolean grown) {
        server.runCommand("fill %d 58 %d %d 60 %d minecraft:calcite".formatted(x - 1, z - 1, x + 4, z + 2));
        server.runCommand("fill %d 59 %d %d 59 %d minecraft:budding_amethyst".formatted(x, z, x + 3, z + 1));
        server.runCommand("fill %d 60 %d %d 60 %d minecraft:%s".formatted(x, z, x + 3, z + 1,
                grown ? "amethyst_cluster[facing=up]" : "air"));
        if (grown) return;
        // Worldgen gives about a third of budding blocks one bud of any size.
        server.runCommand("setblock %d 60 %d minecraft:small_amethyst_bud[facing=up]".formatted(x, z));
        server.runCommand("setblock %d 60 %d minecraft:medium_amethyst_bud[facing=up]".formatted(x + 1, z));
        server.runCommand("setblock %d 60 %d minecraft:amethyst_cluster[facing=up]".formatted(x + 2, z + 1));
    }

    private static void awaitKinds(ClientGameTestContext context, FinderScanner scanner, EnumSet<TargetKind> expected) {
        context.waitFor(client -> scanner.snapshot().size() == expected.size()
                && scanner.snapshot().stream().map(FoundBlock::kind).collect(Collectors.toSet()).equals(expected));
        context.runOnClient(client -> {
            require(scanner.storageCount() == expected.stream().filter(TargetKind::isStorage).count(),
                    "Storage counter did not match loaded fixtures");
            require(scanner.spawnerCount() == expected.stream().filter(TargetKind::isSpawner).count(),
                    "Spawner counter did not match loaded fixtures");
            require(scanner.debrisCount() == expected.stream().filter(TargetKind::isDebris).count(),
                    "Debris counter did not match loaded fixtures");
        });
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
