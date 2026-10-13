package dev.donut.client.gametest;

import dev.donut.client.config.ClientConfig;
import dev.donut.client.config.RenderMode;
import dev.donut.client.finder.ActivityArea;
import dev.donut.client.finder.FinderScanner;
import dev.donut.client.finder.FoundBlock;
import dev.donut.client.finder.TargetKind;
import dev.donut.client.finder.TunnelDetector;
import dev.donut.client.fullbright.FullbrightController;
import dev.donut.client.gui.FinderHud;
import dev.donut.client.xray.XrayController;
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
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.ChunkPos;

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
        config.tunnels.distanceFade = false;
        config.entities.distanceFade = false;
        FinderScanner scanner = new FinderScanner(config);
        XrayController xray = new XrayController(config);
        FullbrightController fullbright = new FullbrightController(config);
        AtomicBoolean active = new AtomicBoolean(true);
        AtomicInteger saves = new AtomicInteger();
        HighlightRenderer renderer = context.computeOnClient(client -> {
            client.options.guiScale().set(1);
            HighlightRenderer created = new HighlightRenderer(config, scanner);
            created.register();
            ClientTickEvents.END_CLIENT_TICK.register(mc -> {
                if (!active.get()) return;
                scanner.tick(mc);
                xray.tick(mc);
                fullbright.tick();
            });
            // The info panel and labels for this test's own finders.
            HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("donut-gametest", "finder_info"), (graphics, delta) -> {
                if (active.get() && mc().player != null && mc().screen == null) FinderHud.render(graphics, mc(), config, scanner);
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
            context.setScreen(() -> new DonutScreen(config, saves::incrementAndGet, scanner::activityStats));
            context.waitForScreen(DonutScreen.class);
            context.waitTicks(2);
            GuiClicks.click(context, "module.StorageFinder");
            GuiClicks.click(context, "module.SpawnerFinder");
            context.runOnClient(client -> require(config.storage.enabled && config.spawner.enabled,
                    "Module toggles did not update the supplied config"));
            require(saves.get() >= 2, "Changing module toggles must invoke the save callback");
            GuiClicks.expand(context, "DebrisFinder");
            GuiClicks.click(context, "module.DebrisFinder");
            context.runOnClient(client -> require(config.debris.enabled, "Debris toggle did not update the supplied config"));
            GuiClicks.settle(context);
            screenshot(context, "donut-01-clickgui-debris-settings");
            GuiClicks.collapse(context, "DebrisFinder");
            GuiClicks.expand(context, "ActivityFinder");
            GuiClicks.click(context, "module.ActivityFinder");
            context.runOnClient(client -> require(config.activity.enabled, "Activity toggle did not update the supplied config"));
            GuiClicks.settle(context);
            screenshot(context, "donut-01-clickgui-activity-settings");
            GuiClicks.reveal(context, "ActivityFinder.section.Live scan");
            screenshot(context, "donut-01-clickgui-activity-detection");
            GuiClicks.collapse(context, "ActivityFinder");
            awaitKinds(context, scanner, ALL_KINDS);
            screenshot(context, "donut-01-clickgui-modules");

            GuiClicks.expand(context, "StorageFinder");
            GuiClicks.click(context, "StorageFinder.mode.Outline");
            context.runOnClient(client -> require(config.storage.mode == RenderMode.OUTLINE,
                    "Render mode button did not select Outline"));
            GuiClicks.showMore(context, "StorageFinder");
            GuiClicks.slide(context, "StorageFinder.outlineOpacity", .5);
            context.runOnClient(client -> require(Math.abs(config.storage.outlineOpacity - 0.5) < 0.02,
                    "Opacity slider did not update the setting"));
            GuiClicks.settle(context);
            screenshot(context, "donut-02-clickgui-storage-settings");
            GuiClicks.collapse(context, "StorageFinder");
            GuiClicks.click(context, "nav.keybinds");
            GuiClicks.settle(context);
            screenshot(context, "donut-02-clickgui-keybinds");
            GuiClicks.click(context, "nav.theme");
            GuiClicks.settle(context);
            screenshot(context, "donut-02-clickgui-theme");
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
            context.waitFor(client -> scanner.activityStats().amethystChunks() == 3);
            context.runOnClient(client -> {
                var stats = scanner.activityStats();
                require(stats.overworld() && stats.budding() == 24 && stats.scannedChunks() > 50
                        && stats.best() != null && stats.best().score() == 1, "Unexpected live scan: " + stats);
            });
            // The live scan on the settings page explains what the world view shows.
            context.setScreen(() -> new DonutScreen(config, saves::incrementAndGet, scanner::activityStats));
            context.waitForScreen(DonutScreen.class);
            context.waitTicks(2);
            GuiClicks.expand(context, "ActivityFinder");
            GuiClicks.reveal(context, "ActivityFinder.section.Live scan");
            screenshot(context, "donut-07-activity-live-scan");
            GuiClicks.collapse(context, "ActivityFinder");
            context.setScreen(() -> null);
            screenshot(context, "donut-07-activity-all-loaded");
            context.runOnClient(client -> config.activity.allLoadedChunks = false);
            context.waitFor(client -> scanner.activity().size() == 1 && scanner.activity().getFirst().chunk().equals(grown));
            context.runOnClient(client -> {
                config.activity.allLoadedChunks = true;
                config.activity.range = 96;
            });

            visionChecks(context, world, config, scanner);

            context.runOnClient(client -> {
                config.storage.enabled = false;
                config.spawner.enabled = false;
                config.debris.enabled = false;
                config.activity.enabled = false;
                config.tunnels.enabled = false;
                config.entities.enabled = false;
            });
            context.waitFor(client -> scanner.snapshot().isEmpty() && scanner.activity().isEmpty() && scanner.tunnels().isEmpty());
        } finally {
            context.runOnClient(client -> {
                config.xray.enabled = false;
                config.fullbright.enabled = false;
            });
            context.waitTicks(2);
            active.set(false);
            context.runOnClient(client -> {
                scanner.clear();
                renderer.close();
            });
        }
    }

    private static net.minecraft.client.Minecraft mc() { return net.minecraft.client.Minecraft.getInstance(); }

    /** Activated spawners, anti-xray fake debris, dug passages, entities, X-ray, Fullbright and labels. */
    private static void visionChecks(ClientGameTestContext context, TestSingleplayerContext world, ClientConfig config, FinderScanner scanner) {
        TestServerContext server = world.getServer();
        context.runOnClient(client -> {
            config.storage.enabled = true;
            config.spawner.enabled = true;
            config.debris.enabled = true;
        });
        // A spawner whose timer ran while someone was near, and an untouched one too far away for ours to run.
        server.runCommand("setblock 40 65 30 minecraft:spawner{Delay:300s}");
        server.runCommand("setblock -40 65 -40 minecraft:spawner");
        BlockPos visited = new BlockPos(40, 65, 30), fresh = new BlockPos(-40, 65, -40);
        context.waitFor(client -> scanner.snapshot().stream().anyMatch(target -> target.pos().equals(visited))
                && scanner.snapshot().stream().anyMatch(target -> target.pos().equals(fresh)));
        context.runOnClient(client -> {
            require(scanner.snapshot().stream().anyMatch(target -> target.pos().equals(visited) && target.flagged()),
                    "A spawner with a used timer was not marked as activated");
            require(scanner.snapshot().stream().anyMatch(target -> target.pos().equals(fresh) && !target.flagged()),
                    "An untouched spawner was marked as activated");
        });

        // Anti-xray engine mode 2 fills buried stone with fake ores, sent inside chunk data. Build such a chunk
        // out of view, so it arrives the same way, and stand next to it.
        server.runCommand("forceload add 992 992 1007 1023");
        server.runCommand("fill 992 50 992 1007 58 1007 minecraft:netherrack");
        server.runCommand("fill 993 51 993 1006 57 1006 minecraft:ancient_debris");
        server.runCommand("fill 998 69 1012 1002 69 1018 minecraft:smooth_stone");
        server.runCommand("tp @a 1000.5 70 1015.5 180 35");
        context.waitFor(client -> scanner.debrisStats().fakedChunks() == 1 && scanner.debrisStats().hiddenFakes() > 1000, 400);
        context.runOnClient(client -> require(scanner.debrisCount() == 0, "Fake debris was drawn: " + scanner.debrisCount()));
        // Mining one exposes its six neighbors, which anti-xray would have to send as they are.
        server.runCommand("setblock 1000 54 999 minecraft:air");
        context.waitFor(client -> scanner.debrisCount() == 6, 20);
        // A block update from the server confirms buried debris, the way anti-xray reveals real ore.
        server.runCommand("setblock 996 52 996 minecraft:netherrack");
        context.waitTicks(2);
        server.runCommand("setblock 996 52 996 minecraft:ancient_debris");
        context.waitFor(client -> scanner.debrisCount() == 7, 20);
        context.waitTicks(2);
        screenshot(context, "donut-08-debris-anti-xray");
        context.runOnClient(client -> config.debris.filterFakes = false);
        context.waitFor(client -> scanner.debrisCount() > 1000);
        context.waitTicks(2);
        screenshot(context, "donut-08-debris-unfiltered");
        context.runOnClient(client -> config.debris.filterFakes = true);
        context.waitFor(client -> scanner.debrisCount() == 7);
        server.runCommand("forceload remove 992 992 1007 1023");
        server.runCommand("tp @a 0.5 65 12.5 180 12");
        // The server must load the chunks around spawn again before blocks can be placed there.
        context.waitFor(client -> client.player.position().distanceTo(new net.minecraft.world.phys.Vec3(0.5, 65, 12.5)) < 1);
        world.getClientWorld().waitForChunksRender();

        // Dug passages inside a block of stone: a 14-long tunnel, a 19-deep hole and a 6-step staircase.
        server.runCommand("fill -40 30 20 -20 56 26 minecraft:stone");
        server.runCommand("fill -38 42 23 -25 43 23 minecraft:air");
        server.runCommand("fill -22 32 21 -22 50 21 minecraft:air");
        for (int i = 0; i < 6; i++) server.runCommand("fill %d %d 25 %d %d 25 minecraft:air".formatted(-35 + i, 50 - i, -35 + i, 52 - i));
        // A cave-like room nearby must not count.
        server.runCommand("fill -38 34 20 -30 38 22 minecraft:air");
        context.runOnClient(client -> {
            config.tunnels.enabled = true;
            config.tunnels.tracers = true;
        });
        context.waitFor(client -> scanner.tunnels().size() >= 3, 200);
        context.waitTicks(20);
        context.runOnClient(client -> require(scanner.tunnels().size() == 3, "Unexpected passages: " + scanner.tunnels()));
        context.runOnClient(client -> {
            for (TunnelDetector.Segment segment : scanner.tunnels()) {
                switch (segment.kind()) {
                    case TUNNEL -> require(segment.length() == 14 && segment.x() == -38 && segment.y() == 42, "Unexpected tunnel " + segment);
                    case HOLE -> require(segment.length() == 19 && segment.y() == 32, "Unexpected hole " + segment);
                    case STAIRS -> require(segment.length() == 6 && segment.dy() == -1, "Unexpected staircase " + segment);
                }
            }
        });

        // X-ray hides the stone, so the passages and their highlights show from outside.
        server.runCommand("tp @a -10.5 52 40.5 150 25");
        context.runOnClient(client -> config.xray.enabled = true);
        context.waitTicks(3);
        context.runOnClient(client -> {
            require(XrayController.enabled(), "X-ray did not turn on");
            var stone = Blocks.STONE.defaultBlockState();
            require(XrayController.filter(client.level, new BlockPos(-30, 40, 24), stone).isAir(), "X-ray did not hide stone");
            var sideways = Blocks.DEEPSLATE.defaultBlockState().setValue(net.minecraft.world.level.block.RotatedPillarBlock.AXIS,
                    net.minecraft.core.Direction.Axis.X);
            require(!XrayController.filter(client.level, BlockPos.ZERO, sideways).isAir(), "X-ray hid sideways deepslate");
            require(FullbrightController.effectiveGamma(0.5) > 1, "X-ray did not brighten the world");
        });
        world.getClientWorld().waitForChunksRender(false);
        context.waitTicks(2);
        screenshot(context, "donut-09-tunnels-xray");
        context.runOnClient(client -> config.xray.enabled = false);
        context.waitTicks(3);
        world.getClientWorld().waitForChunksRender(false);
        context.waitTicks(2);
        screenshot(context, "donut-09-tunnels-tracers");

        // EntityFinder outlines mobs with tracers; labels name the nearest finds.
        server.runCommand("tp @a 0.5 65 12.5 180 12");
        server.runCommand("summon minecraft:pig 3 65 6 {NoAI:1b}");
        server.runCommand("difficulty easy");
        server.runCommand("summon minecraft:husk -3 65 6 {NoAI:1b,PersistenceRequired:1b}");
        context.runOnClient(client -> {
            config.tunnels.enabled = false;
            config.entities.enabled = true;
            config.entities.hostiles = true;
            config.entities.passives = true;
            config.storage.tracers = true;
        });
        context.waitFor(client -> scanner.summaries().stream()
                .anyMatch(line -> line.name().equals("Entities") && line.count() >= 2));
        context.waitTicks(3);
        context.runOnClient(client -> require(!dev.donut.client.render.HighlightRenderer.labels().isEmpty(),
                "No labels were projected for visible highlights"));
        screenshot(context, "donut-10-entities-tracers-labels");

        // Fullbright at midnight, with and without fog.
        server.runCommand("time set midnight");
        context.runOnClient(client -> {
            config.entities.enabled = false;
            config.storage.tracers = false;
            config.fullbright.enabled = true;
        });
        context.waitTicks(3);
        context.runOnClient(client -> require(FullbrightController.effectiveGamma(0.5) > 1 && FullbrightController.noFog(),
                "Fullbright did not apply"));
        screenshot(context, "donut-11-fullbright-midnight");
        context.runOnClient(client -> config.fullbright.enabled = false);
        context.waitTicks(3);
        context.runOnClient(client -> require(FullbrightController.effectiveGamma(0.5) == 0.5 && !FullbrightController.noFog(),
                "Fullbright did not turn off"));
        screenshot(context, "donut-11-normal-midnight");
        server.runCommand("time set noon");
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
        server.runCommand("setblock -6 65 2 minecraft:barrel");
        server.runCommand("setblock -4 65 2 minecraft:hopper");
        server.runCommand("setblock -2 65 2 minecraft:dispenser");
        server.runCommand("setblock 0 65 2 minecraft:furnace");
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


    private static void screenshot(ClientGameTestContext context, String name) {
        context.takeScreenshot(TestScreenshotOptions.of(name).withSize(1280, 800));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
