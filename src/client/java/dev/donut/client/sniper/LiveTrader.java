package dev.donut.client.sniper;

import dev.donut.client.config.SniperSettings;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

/**
 * Buys one listing through the in-game Auction House and lists it again. Every click is checked first:
 * the listing's slot must show the item, stack size and price the API reported, and the confirmation menu
 * must show the same item. Anything unexpected aborts instead of clicking something else.
 */
public final class LiveTrader {
    public enum Phase { IDLE, FIND_LISTING, AWAIT_CONFIRM, AWAIT_PURCHASE, PREPARE_SALE, AWAIT_LISTING }

    /** What the engine hears back about a trade. */
    public interface Listener {
        void bought(MarketListing listing, double paid);
        void listed(MarketListing listing, double price);
        /** @param holding whether the item was bought and is still in the inventory */
        void failed(MarketListing listing, String reason, boolean holding);
    }

    private static final int MENU_TIMEOUT = 6 * 20, CONFIRM_TIMEOUT = 5 * 20, PURCHASE_TIMEOUT = 6 * 20;
    private static final int SALE_TIMEOUT = 6 * 20, PAGE_SETTLE = 6, MAX_PAGES = 3;

    private final SniperSettings settings;
    private final Listener listener;
    private Phase phase = Phase.IDLE;
    private MarketListing listing;
    private long resalePrice;
    /** Far enough back that the first settle check passes; a minimum long would overflow the subtraction. */
    private static final long NEVER = -1_000_000;
    private long tick, phaseStart, lastClick = NEVER, menuSeenAt = -1;
    private int pages;
    private double paid;
    private int itemCountBefore;
    /** When the bought stack was swapped into the hand; a second swap would move it straight back. */
    private long swappedAt = NEVER;

    public LiveTrader(SniperSettings settings, Listener listener) {
        this.settings = settings;
        this.listener = listener;
    }

    public Phase phase() { return phase; }

    public boolean idle() { return phase == Phase.IDLE; }

    public void buy(Minecraft client, MarketListing listing, long resalePrice) {
        if (!idle() || client.player == null) return;
        this.listing = listing;
        this.resalePrice = resalePrice;
        pages = 0;
        lastClick = NEVER;
        menuSeenAt = -1;
        swappedAt = NEVER;
        enter(Phase.FIND_LISTING);
        client.player.connection.sendCommand(settings.searchCommand.replace("{item}", searchTerm(listing.item())));
    }

    public static String searchTerm(MarketItem item) {
        return item.name().isEmpty() ? item.id().replace('_', ' ') : item.name();
    }

    public void tick(Minecraft client) {
        tick++;
        if (idle()) return;
        if (client.player == null) { fail(client, "disconnected", phase.ordinal() >= Phase.PREPARE_SALE.ordinal()); return; }
        long waited = tick - phaseStart;
        switch (phase) {
            case FIND_LISTING -> {
                if (!(client.screen instanceof ContainerScreen screen)) {
                    if (waited > MENU_TIMEOUT) fail(client, "the Auction House did not open", false);
                    return;
                }
                // Give each page a moment to fill after opening or turning it.
                if (menuSeenAt < 0) menuSeenAt = tick;
                if (tick - lastClick < PAGE_SETTLE || tick - menuSeenAt < PAGE_SETTLE) return;
                Slot slot = findListing(screen.getMenu());
                if (slot != null) {
                    click(client, screen.getMenu(), slot);
                    enter(Phase.AWAIT_CONFIRM);
                    return;
                }
                Slot next = findNamed(screen.getMenu(), "next");
                if (next != null && pages < MAX_PAGES) {
                    pages++;
                    click(client, screen.getMenu(), next);
                    return;
                }
                fail(client, "listing not found in the menu; it may have sold", false);
            }
            case AWAIT_CONFIRM -> {
                if (client.screen instanceof ContainerScreen screen && tick - lastClick >= PAGE_SETTLE) {
                    Slot confirm = findNamed(screen.getMenu(), settings.confirmText);
                    if (confirm != null) {
                        if (findItem(screen.getMenu()) == null) {
                            fail(client, "the confirmation menu shows a different item", false);
                            return;
                        }
                        click(client, screen.getMenu(), confirm);
                        enter(Phase.AWAIT_PURCHASE);
                        return;
                    }
                }
                if (waited > CONFIRM_TIMEOUT) fail(client, "no confirmation menu or purchase message", false);
            }
            case AWAIT_PURCHASE -> {
                if (waited > PURCHASE_TIMEOUT) fail(client, "no purchase message", false);
            }
            case PREPARE_SALE -> {
                if (waited < 10) return;
                closeMenu(client);
                if (!holdItem(client)) {
                    if (waited > SALE_TIMEOUT) fail(client, "could not find the bought item in the inventory", true);
                    return;
                }
                itemCountBefore = client.player.getMainHandItem().getCount();
                client.player.connection.sendCommand(settings.sellCommand.replace("{price}", Long.toString(resalePrice)));
                enter(Phase.AWAIT_LISTING);
            }
            case AWAIT_LISTING -> {
                if (client.screen instanceof ContainerScreen screen && tick - phaseStart >= PAGE_SETTLE) {
                    Slot confirm = findNamed(screen.getMenu(), settings.confirmText);
                    if (confirm != null && tick - lastClick > 20) click(client, screen.getMenu(), confirm);
                }
                ItemStack hand = client.player.getMainHandItem();
                boolean gone = !matchesItem(hand) || hand.getCount() < itemCountBefore;
                if (gone && waited >= 10) {
                    closeMenu(client);
                    MarketListing done = listing;
                    reset();
                    listener.listed(done, resalePrice);
                } else if (waited > SALE_TIMEOUT) {
                    fail(client, "the sell command did not take the item", true);
                }
            }
            case IDLE -> { }
        }
    }

    /** Server chat while a trade runs: DonutSMP confirms with "You bought 1 Diamond Pickaxe for $ 37.9K". */
    public void onMessage(Minecraft client, String text) {
        if (phase != Phase.AWAIT_CONFIRM && phase != Phase.AWAIT_PURCHASE && phase != Phase.FIND_LISTING) return;
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("you bought")) {
            Money.Amount amount = Money.find(text);
            paid = amount == null ? listing.price() : amount.value();
            listener.bought(listing, paid);
            if (amount != null && !amount.matches(listing.price())) {
                // Selling at the planned price could lose money if more was paid; keep the item instead.
                fail(client, "paid " + Money.format(paid) + " instead of " + Money.format(listing.price()), true);
                return;
            }
            enter(Phase.PREPARE_SALE);
        } else if (lower.contains("already bought") || lower.contains("not enough money") || lower.contains("expired")
                || lower.contains("no longer")) {
            fail(client, MarketItem.clean(text), false);
        }
    }

    private Slot findListing(ChestMenu menu) {
        Slot withSeller = null, any = null;
        int matches = 0;
        for (Slot slot : containerSlots(menu)) {
            ItemStack stack = slot.getItem();
            if (!matchesItem(stack) || stack.getCount() != listing.item().count()) continue;
            List<String> lore = lore(stack);
            boolean priced = lore.stream().map(Money::find).anyMatch(amount -> amount != null && amount.matches(listing.price()));
            if (!priced) continue;
            matches++;
            any = slot;
            String seller = listing.seller().toLowerCase(Locale.ROOT);
            if (!seller.isEmpty() && lore.stream().anyMatch(line -> line.toLowerCase(Locale.ROOT).contains(seller))) withSeller = slot;
        }
        // Several identical listings at the same price are interchangeable; a seller match is preferred.
        return withSeller != null ? withSeller : matches > 0 ? any : null;
    }

    private Slot findItem(ChestMenu menu) {
        for (Slot slot : containerSlots(menu)) {
            if (matchesItem(slot.getItem()) && slot.getItem().getCount() == listing.item().count()) return slot;
        }
        return null;
    }

    private static Slot findNamed(ChestMenu menu, String text) {
        String wanted = text.toLowerCase(Locale.ROOT);
        for (Slot slot : containerSlots(menu)) {
            ItemStack stack = slot.getItem();
            if (!stack.isEmpty() && stack.getHoverName().getString().toLowerCase(Locale.ROOT).contains(wanted)) return slot;
        }
        return null;
    }

    private static List<Slot> containerSlots(ChestMenu menu) {
        int size = menu.getContainer().getContainerSize();
        return menu.slots.subList(0, Math.min(size, menu.slots.size()));
    }

    private boolean matchesItem(ItemStack stack) {
        if (stack.isEmpty()) return false;
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
        if (!id.equals(listing.item().id())) return false;
        String name = listing.item().name().toLowerCase(Locale.ROOT);
        return name.isEmpty() || MarketItem.clean(stack.getHoverName().getString()).toLowerCase(Locale.ROOT).contains(name);
    }

    private static List<String> lore(ItemStack stack) {
        List<String> lines = new ArrayList<>();
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore != null) for (Component line : lore.lines()) lines.add(line.getString());
        return lines;
    }

    /** Puts the bought stack in the main hand, swapping it into the selected hotbar slot if needed. */
    private boolean holdItem(Minecraft client) {
        var inventory = client.player.getInventory();
        if (matchesItem(inventory.getSelectedItem()) && inventory.getSelectedItem().getCount() == listing.item().count()) return true;
        for (int index = 0; index < 36; index++) {
            ItemStack stack = inventory.getItem(index);
            if (!matchesItem(stack) || stack.getCount() != listing.item().count()) continue;
            if (index < 9) {
                inventory.setSelectedSlot(index);
            } else if (tick - swappedAt > 40) {
                // One swap, then wait for it to apply; the server may take a moment to confirm.
                swappedAt = tick;
                var menu = client.player.inventoryMenu;
                client.gameMode.handleInventoryMouseClick(menu.containerId, index, inventory.getSelectedSlot(), ClickType.SWAP, client.player);
            }
            return false; // Checked again next tick, once the change has applied.
        }
        return false;
    }

    private void click(Minecraft client, ChestMenu menu, Slot slot) {
        lastClick = tick;
        client.gameMode.handleInventoryMouseClick(menu.containerId, slot.index, 0, ClickType.PICKUP, client.player);
    }

    private void enter(Phase next) {
        phase = next;
        phaseStart = tick;
    }

    /** Closing through the player tells the server and resets the open menu, so later clicks are not ignored. */
    private static void closeMenu(Minecraft client) {
        if (client.player != null && client.screen instanceof ContainerScreen) client.player.closeContainer();
    }

    private void fail(Minecraft client, String reason, boolean holding) {
        MarketListing failed = listing;
        closeMenu(client);
        reset();
        listener.failed(failed, reason, holding);
    }

    private void reset() {
        phase = Phase.IDLE;
        listing = null;
    }

    /** Stops a trade that has not bought anything yet. */
    public void cancel(Minecraft client) {
        if (idle()) return;
        if (phase.ordinal() >= Phase.PREPARE_SALE.ordinal()) return;
        fail(client, "stopped", false);
    }
}
