package dev.donut.client.gametest;

import dev.donut.client.config.ClientConfig;
import dev.donut.client.config.SniperSettings;
import dev.donut.client.finder.ActivityStats;
import dev.donut.client.gui.DonutScreen;
import dev.donut.client.sniper.DonutApi;
import dev.donut.client.sniper.LiveTrader;
import dev.donut.client.sniper.MarketItem;
import dev.donut.client.sniper.MarketListing;
import dev.donut.client.sniper.SniperEngine;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
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
import net.minecraft.world.item.component.ItemLore;

/**
 * Drives the live trader through client-side Auction House menus. Real clicks and commands are sent;
 * the integrated server ignores clicks in the fake menus, and stand-in commands keep it harmless.
 */
public final class SniperGameTest implements FabricClientGameTest {
    private static final MarketListing DIAMONDS = new MarketListing(
            new MarketItem("minecraft:diamond", "Diamond", 16, Map.of(), "", List.of(), false), 9_600, "Notch", 3_600);

    /** Everything the trader reports, in order. */
    private final List<String> events = new ArrayList<>();

    @Override
    public void runTest(ClientGameTestContext context) {
        SniperSettings settings = new SniperSettings();
        settings.searchCommand = "say search {item}";
        // Clearing up to {price} diamonds stands in for listing them; a malformed price fails the command.
        settings.sellCommand = "clear @s minecraft:diamond {price}";
        LiveTrader trader = new LiveTrader(settings, new LiveTrader.Listener() {
            @Override public void bought(MarketListing listing, double paid) { events.add("bought " + Math.round(paid)); }
            @Override public void listed(MarketListing listing, double price) { events.add("listed " + Math.round(price)); }
            @Override public void failed(MarketListing listing, String reason, boolean holding) {
                events.add("failed " + reason + (holding ? " (holding)" : ""));
            }
        });
        AtomicBoolean active = new AtomicBoolean(true);
        context.runOnClient(client -> {
            client.options.guiScale().set(1);
            ClientTickEvents.END_CLIENT_TICK.register(mc -> { if (active.get()) trader.tick(mc); });
        });
        context.getInput().resizeWindow(1280, 800);

        try (var world = context.worldBuilder().adjustSettings(creator -> creator.setAllowCommands(true)).create()) {
            TestServerContext server = world.getServer();
            server.runCommand("gamemode creative @a");
            context.waitTicks(5);

            // The listing is on the second page; a same-item listing at another price must be skipped.
            buy(context, trader);
            SimpleContainer page = openMenu(context, 61, 54, "Auction House");
            context.runOnClient(client -> {
                page.setItem(10, listed(Items.DIAMOND, 16, "$12K", "Bob"));
                page.setItem(12, listed(Items.EMERALD, 16, "$9.6K", "Notch"));
                page.setItem(53, named(Items.ARROW, "Next Page"));
            });
            screenshot(context, "donut-sniper-01-auction-page");
            // Clicking Next Page picks the arrow up; the server would then send page two.
            context.waitFor(client -> page.getItem(53).isEmpty(), 60);
            dropCarried(context);
            context.runOnClient(client -> {
                for (int i = 0; i < 54; i++) page.setItem(i, ItemStack.EMPTY);
                page.setItem(20, listed(Items.DIAMOND, 16, "$9,600", "Notch"));
            });
            context.waitFor(client -> trader.phase() == LiveTrader.Phase.AWAIT_CONFIRM, 60);
            dropCarried(context);
            confirmMenu(context, Items.DIAMOND);
            context.waitFor(client -> trader.phase() == LiveTrader.Phase.AWAIT_PURCHASE, 60);
            dropCarried(context);
            // The purchase lands in the main inventory, so the trader has to swap it into the hand.
            server.runCommand("item replace entity @a container.20 with minecraft:diamond 16");
            context.waitTicks(3);
            context.runOnClient(client -> trader.onMessage(client, "You bought 16 Diamond for $ 9.6K"));
            context.waitFor(client -> trader.idle(), 200);
            require(events.equals(List.of("bought 9600", "listed 15000")), "Unexpected trade: " + events);
            context.runOnClient(client -> require(client.player.getInventory().countItem(Items.DIAMOND) == 0,
                    "The sell command should have taken the diamonds"));

            // A sold-out listing ends the trade without buying.
            events.clear();
            buy(context, trader);
            SimpleContainer gone = openMenu(context, 62, 54, "Auction House");
            context.runOnClient(client -> gone.setItem(10, listed(Items.DIAMOND, 16, "$12K", "Bob")));
            context.waitFor(client -> trader.idle(), 100);
            require(events.equals(List.of("failed listing not found in the menu; it may have sold")), "Unexpected: " + events);

            // A confirmation for some other item is never clicked.
            events.clear();
            buy(context, trader);
            SimpleContainer found = openMenu(context, 63, 54, "Auction House");
            context.runOnClient(client -> found.setItem(10, listed(Items.DIAMOND, 16, "$9.6K", "Notch")));
            context.waitFor(client -> trader.phase() == LiveTrader.Phase.AWAIT_CONFIRM, 60);
            dropCarried(context);
            confirmMenu(context, Items.EMERALD);
            context.waitFor(client -> trader.idle(), 60);
            require(events.equals(List.of("failed the confirmation menu shows a different item")), "Unexpected: " + events);

            // The server says someone was faster.
            events.clear();
            buy(context, trader);
            SimpleContainer raced = openMenu(context, 64, 54, "Auction House");
            context.runOnClient(client -> raced.setItem(10, listed(Items.DIAMOND, 16, "$9.6K", "Notch")));
            context.waitFor(client -> trader.phase() == LiveTrader.Phase.AWAIT_CONFIRM, 60);
            context.runOnClient(client -> trader.onMessage(client, "This item was already bought"));
            context.waitFor(client -> trader.idle(), 20);
            require(events.equals(List.of("failed This item was already bought")), "Unexpected: " + events);

            // Paying more than the listing said keeps the item instead of reselling at a loss.
            events.clear();
            buy(context, trader);
            SimpleContainer pricier = openMenu(context, 65, 54, "Auction House");
            context.runOnClient(client -> pricier.setItem(10, listed(Items.DIAMOND, 16, "$9.6K", "Notch")));
            context.waitFor(client -> trader.phase() == LiveTrader.Phase.AWAIT_CONFIRM, 60);
            context.runOnClient(client -> trader.onMessage(client, "You bought 16 Diamond for $ 12K"));
            context.waitFor(client -> trader.idle(), 20);
            require(events.equals(List.of("bought 12000", "failed paid $12.0K instead of $9.60K (holding)")),
                    "Unexpected: " + events);

            // The Sniper tab, read from an engine that has not started.
            ClientConfig config = new ClientConfig();
            SniperEngine engine = new SniperEngine(config, new DonutApi(),
                    Files.createTempDirectory("donut-sniper").resolve("market.json"));
            context.setScreen(() -> new DonutScreen(config, () -> { }, () -> ActivityStats.EMPTY, () -> "Idle", engine));
            context.waitForScreen(DonutScreen.class);
            context.waitTicks(2);
            GuiClicks.click(context, "nav.dashboard");
            GuiClicks.settle(context);
            screenshot(context, "donut-sniper-02-dashboard");
            GuiClicks.click(context, "nav.markets");
            GuiClicks.settle(context);
            screenshot(context, "donut-sniper-02-markets");
            GuiClicks.expand(context, "AHSniper");
            screenshot(context, "donut-sniper-03-settings");
            GuiClicks.showMore(context, "AHSniper");
            GuiClicks.reveal(context, "AHSniper.confirm");
            screenshot(context, "donut-sniper-04-settings-end");
            GuiClicks.collapse(context, "AHSniper");
            context.setScreen(() -> null);
        } catch (java.io.IOException error) {
            throw new AssertionError(error);
        } finally {
            active.set(false);
        }
    }

    /** Real servers own the cursor stack in their menus; here the client's pick-up would leak into the inventory. */
    private static void dropCarried(ClientGameTestContext context) {
        context.runOnClient(client -> client.player.containerMenu.setCarried(ItemStack.EMPTY));
    }

    private static void buy(ClientGameTestContext context, LiveTrader trader) {
        context.runOnClient(client -> trader.buy(client, DIAMONDS, 15_000));
        context.waitTick();
    }

    /** A client-side chest menu, as a server plugin would open it. */
    private static SimpleContainer openMenu(ClientGameTestContext context, int id, int size, String title) {
        SimpleContainer items = new SimpleContainer(size);
        context.setScreen(() -> {
            Minecraft client = Minecraft.getInstance();
            ChestMenu menu = size == 54 ? ChestMenu.sixRows(id, client.player.getInventory(), items)
                    : ChestMenu.threeRows(id, client.player.getInventory(), items);
            client.player.containerMenu = menu;
            return new ContainerScreen(menu, client.player.getInventory(), Component.literal(title));
        });
        context.waitTick();
        return items;
    }

    private static void confirmMenu(ClientGameTestContext context, Item shown) {
        SimpleContainer confirm = openMenu(context, 70, 27, "Confirm Purchase");
        context.runOnClient(client -> {
            confirm.setItem(11, named(Items.LIME_STAINED_GLASS_PANE, "Confirm"));
            confirm.setItem(13, new ItemStack(shown, 16));
            confirm.setItem(15, named(Items.RED_STAINED_GLASS_PANE, "Cancel"));
        });
    }

    private static ItemStack listed(Item item, int count, String price, String seller) {
        ItemStack stack = new ItemStack(item, count);
        stack.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("Price: " + price),
                Component.literal("Seller: " + seller))));
        return stack;
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
