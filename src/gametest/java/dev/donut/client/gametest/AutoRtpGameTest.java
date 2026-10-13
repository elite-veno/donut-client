package dev.donut.client.gametest;

import dev.donut.client.config.ClientConfig;
import dev.donut.client.finder.FinderScanner;
import dev.donut.client.gui.DonutScreen;
import dev.donut.client.render.HighlightRenderer;
import dev.donut.client.rtp.AutoRtpController;
import dev.donut.client.rtp.RtpLoop;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;

/**
 * Runs Auto RTP against the integrated server. A relative teleport stands in for /rtp, and a client-side
 * chest menu stands in for an RTP plugin's world picker. This suite must stay last: its controller replaces
 * the production one returned by {@link AutoRtpController#getInstance()}.
 */
public final class AutoRtpGameTest implements FabricClientGameTest {
    private static final ChunkPos GEODE = new ChunkPos(0, -51);

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientConfig config = new ClientConfig();
        config.rtp.command = "tp @s ~ ~ ~-400";
        config.rtp.intervalSeconds = 5;
        config.rtp.scanSeconds = 3;
        config.rtp.maxAttempts = 4;
        FinderScanner scanner = new FinderScanner(config);
        AtomicBoolean active = new AtomicBoolean(true);
        // The best growth seen while the first attempt scanned, to show the 52% geode was seen and passed over.
        AtomicReference<Double> firstScanBest = new AtomicReference<>(0.0);
        HighlightRenderer renderer = context.computeOnClient(client -> {
            client.options.guiScale().set(1);
            HighlightRenderer created = new HighlightRenderer(config, scanner);
            created.register();
            return created;
        });
        AutoRtpController controller = context.computeOnClient(client -> {
            AutoRtpController created = new AutoRtpController(config, scanner, () -> { });
            ClientTickEvents.END_CLIENT_TICK.register(mc -> {
                if (!active.get()) return;
                scanner.tick(mc);
                created.tick(mc);
                var best = scanner.activityStats().best();
                if (created.attempts() == 1 && created.phase() == RtpLoop.Phase.SCANNING && best != null) {
                    firstScanBest.accumulateAndGet(best.score(), Math::max);
                }
            });
            return created;
        });
        context.getInput().resizeWindow(1280, 800);

        try (var world = context.worldBuilder().adjustSettings(creator -> creator.setAllowCommands(true)).create()) {
            TestServerContext server = world.getServer();
            server.runCommand("gamemode creative @a");
            server.runCommand("time set noon");
            // A fully grown geode 800 blocks north, out of reach until the second teleport.
            server.runCommand("forceload add 0 -816 15 -801");
            server.runCommand("fill 3 58 -807 8 60 -804 minecraft:calcite");
            server.runCommand("fill 4 59 -806 7 59 -805 minecraft:budding_amethyst");
            server.runCommand("fill 4 60 -806 7 60 -805 minecraft:amethyst_cluster[facing=up]");
            server.runCommand("forceload remove 0 -816 15 -801");
            // At the first stop, a geode at 52%: enough for an ActivityFinder square, short of Auto RTP's 55%.
            server.runCommand("forceload add 0 -416 15 -401");
            server.runCommand("fill 3 58 -407 12 60 -404 minecraft:calcite");
            server.runCommand("fill 4 59 -406 11 59 -405 minecraft:budding_amethyst");
            server.runCommand("fill 4 60 -406 11 60 -405 minecraft:air");
            server.runCommand("fill 4 60 -406 8 60 -405 minecraft:amethyst_cluster[facing=up]");
            server.runCommand("forceload remove 0 -416 15 -401");
            server.runCommand("tp @a 0.5 65 0.5 180 30");
            context.waitTicks(5);
            world.getConnection().waitForChunksRender();

            // The first teleport finds nothing, waits out the interval, and the second lands by the geode.
            context.runOnClient(client -> controller.setEnabled(true));
            context.waitFor(client -> controller.result() != null && !controller.isActive(), 60 * 20);
            context.runOnClient(client -> {
                require(controller.result() == RtpLoop.Result.FOUND, "Auto RTP ended with " + controller.result());
                require(controller.attempts() == 2, "Expected the 52% geode to be passed over, got "
                        + controller.attempts() + " attempts");
                require(Math.abs(firstScanBest.get() - 0.52) < 1e-9, "The first area's best growth was " + firstScanBest.get());
                require(Math.abs(client.player.getZ() + 799.5) < 1, "The player is at z " + client.player.getZ());
                require(scanner.activity().stream().anyMatch(area -> area.chunk().equals(GEODE)),
                        "The grown geode's chunk is not highlighted");
                require(config.activity.enabled, "Auto RTP must turn ActivityFinder on");
                require(!config.rtp.enabled, "Auto RTP must switch itself off after a hit");
            });
            // The flat world's floor is far below the geode, so look up at its square.
            server.runCommand("execute as @a at @s run tp @s ~ ~ ~ 160 -70");
            context.waitTicks(5);
            screenshot(context, "donut-rtp-01-found");

            // With nothing ahead, the run ends after its last attempt.
            config.rtp.maxAttempts = 1;
            context.runOnClient(client -> controller.setEnabled(true));
            context.waitFor(client -> controller.result() == RtpLoop.Result.EXHAUSTED && !controller.isActive(), 30 * 20);
            context.runOnClient(client -> require(controller.attempts() == 1, "Expected one attempt, got " + controller.attempts()));

            // A command that opens a world picker gets the configured item clicked.
            config.rtp.command = "say Auto RTP menu test";
            config.rtp.menuItem = "Overworld";
            config.rtp.maxAttempts = 3;
            context.runOnClient(client -> controller.setEnabled(true));
            context.waitFor(client -> controller.phase() == RtpLoop.Phase.AWAIT_TELEPORT);
            openMenu(context, 41, "Random Teleport", Items.GRASS_BLOCK, "Overworld", Items.NETHERRACK, "Nether");
            context.waitFor(client -> controller.lastMenuSlot() == 11, 40);
            screenshot(context, "donut-rtp-02-menu");
            context.runOnClient(client -> controller.setEnabled(false));
            context.setScreen(() -> null);
            context.runOnClient(client -> require(controller.result() == RtpLoop.Result.STOPPED,
                    "Stopping by hand should end with STOPPED, not " + controller.result()));

            // A menu without the configured item stops the run instead of clicking something else.
            context.runOnClient(client -> controller.setEnabled(true));
            context.waitFor(client -> controller.phase() == RtpLoop.Phase.AWAIT_TELEPORT);
            openMenu(context, 42, "Teleport", Items.COMPASS, "Spawn", Items.NETHERRACK, "Nether");
            context.waitFor(client -> controller.result() == RtpLoop.Result.MENU_MISSING, 40);
            context.setScreen(() -> null);

            // The settings page shows the outcome of the last run.
            context.setScreen(() -> new DonutScreen(config, () -> { }, scanner::activityStats, controller::status));
            context.waitForScreen(DonutScreen.class);
            context.waitTicks(2);
            GuiClicks.expand(context, "AutoRTP");
            screenshot(context, "donut-rtp-03-settings");
            GuiClicks.collapse(context, "AutoRTP");
            context.setScreen(() -> null);
        } finally {
            active.set(false);
            context.runOnClient(client -> {
                controller.setEnabled(false);
                scanner.clear();
                renderer.close();
            });
        }
    }

    /** A client-side chest menu with two named items at slots 11 and 13, as an RTP plugin would send. */
    private static void openMenu(ClientGameTestContext context, int id, String title,
                                 Item first, String firstName, Item second, String secondName) {
        context.setScreen(() -> {
            Minecraft client = Minecraft.getInstance();
            SimpleContainer items = new SimpleContainer(27);
            items.setItem(11, named(first, firstName));
            items.setItem(13, named(second, secondName));
            ChestMenu menu = ChestMenu.threeRows(id, client.player.getInventory(), items);
            // A real server sets the open menu too; clicks are only sent for the menu the player has open.
            client.player.containerMenu = menu;
            return new ContainerScreen(menu, client.player.getInventory(), Component.literal(title));
        });
        context.waitTick();
    }

    private static ItemStack named(Item item, String name) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        return stack;
    }


    private static void screenshot(ClientGameTestContext context, String name) {
        context.takeScreenshot(TestScreenshotOptions.of(name).withSize(1280, 800));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
