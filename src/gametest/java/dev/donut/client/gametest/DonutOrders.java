package dev.donut.client.gametest;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.Commands;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

/**
 * DonutSMP's buy orders on the test server, as the GoNuts order driver knows them: /orders opens "Orders (Page 1)",
 * its Your Orders chest "Orders -> Your Orders" with the player's orders (the ordered item, "N requested", "$P each",
 * "D/N delivered" and the time left), an order "Orders -> Edit Order" with Collect where something waits and Cancel
 * where nothing does, and Collect "Orders -> Collect Items" with what was delivered, taken a stack at a time with a
 * shift-click. Every page is a server chest of six rows; a click on a button is taken back, as the server does, and
 * opens the next page in place of this one. Orders here are delivered in full already: everything a test build needs.
 */
final class DonutOrders {
    /** One of the player's orders: what, at how much each, how many, and how many delivered still wait to be collected. */
    static final class Order {
        final Item item;
        final long price;
        final int requested;
        int waiting;

        Order(Item item, long price, int requested) {
            this.item = item;
            this.price = price;
            this.requested = requested;
            this.waiting = requested;
        }
    }

    /** What each order costs a unit: under a thousand, which the pages show as it is. */
    static final long PRICE = 100;
    /** Stock any one order holds at most, as an order of a whole build's worth is split over a few. */
    static final int MAX_ORDER = 2304;

    private static final Map<UUID, List<Order>> ORDERS = new ConcurrentHashMap<>();
    private static boolean registered;

    private DonutOrders() { }

    /** Adds /orders to the test server; once, before the world is made. */
    static synchronized void register() {
        if (registered) return;
        registered = true;
        CommandRegistrationCallback.EVENT.register((dispatcher, context, environment) -> dispatcher.register(
                Commands.literal("orders").executes(source -> {
                    open(source.getSource().getPlayerOrException(), Page.Kind.ORDERS, null);
                    return 1;
                })));
    }

    /** Orders for the player, delivered in full: {@code bill} by item, an order for each {@link #MAX_ORDER} of it. */
    static void fill(UUID player, Map<Item, Integer> bill) {
        List<Order> orders = ORDERS.computeIfAbsent(player, key -> new ArrayList<>());
        for (Map.Entry<Item, Integer> e : bill.entrySet()) {
            for (int left = e.getValue(); left > 0; left -= MAX_ORDER) orders.add(new Order(e.getKey(), PRICE, Math.min(left, MAX_ORDER)));
        }
    }

    /** What waits on the player's orders, by item. */
    static Map<Item, Integer> waiting(UUID player) {
        Map<Item, Integer> waiting = new java.util.LinkedHashMap<>();
        for (Order order : ORDERS.getOrDefault(player, List.of())) waiting.merge(order.item, order.waiting, Integer::sum);
        return waiting;
    }

    static void clear() { ORDERS.clear(); }

    private static void open(ServerPlayer player, Page.Kind kind, Order order) {
        String title = switch (kind) {
            case ORDERS -> "Orders (Page 1)";
            case YOUR_ORDERS -> "Orders -> Your Orders";
            case EDIT -> "Orders -> Edit Order";
            case COLLECT -> "Orders -> Collect Items";
        };
        player.openMenu(new SimpleMenuProvider((id, inventory, p) -> new Page(id, inventory, kind, order, player), Component.literal(title)));
    }

    /** A blank-named pane, as the pages fill the slots round their buttons with. */
    private static ItemStack pane() {
        ItemStack stack = new ItemStack(Items.STAINED_GLASS_PANE.gray());
        stack.set(DataComponents.CUSTOM_NAME, Component.empty());
        return stack;
    }

    private static ItemStack button(Item item, String name, String... lore) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        List<Component> lines = new ArrayList<>();
        for (String line : lore) lines.add(Component.literal(line));
        stack.set(DataComponents.LORE, new ItemLore(lines));
        return stack;
    }

    /** "768", and from a thousand up "1.2K", as the order pages show counts. */
    static String count(int n) {
        if (n < 1000) return Integer.toString(n);
        double k = n / 1000.0;
        return (k == Math.floor(k) ? Long.toString((long) k) : String.format(Locale.ROOT, "%.1f", k)) + "K";
    }

    private static final class Page extends ChestMenu {
        enum Kind { ORDERS, YOUR_ORDERS, EDIT, COLLECT }

        /** Slots of the buttons. */
        private static final int YOUR_ORDERS = 47, NEXT_PAGE = 53, FILTER = 49, ITEM = 13, COLLECT_OR_CANCEL = 15, BACK = 49;

        private final Kind kind;
        private final Order order;
        private final ServerPlayer player;
        private final SimpleContainer slots;
        private final List<Order> rows = new ArrayList<>();

        Page(int id, Inventory inventory, Kind kind, Order order, ServerPlayer player) {
            this(id, inventory, kind, order, player, new SimpleContainer(54));
        }

        private Page(int id, Inventory inventory, Kind kind, Order order, ServerPlayer player, SimpleContainer slots) {
            super(MenuType.GENERIC_9x6, id, inventory, slots, 6);
            this.kind = kind;
            this.order = order;
            this.player = player;
            this.slots = slots;
            draw();
        }

        private void draw() {
            slots.clearContent();
            List<Order> mine = ORDERS.getOrDefault(player.getUUID(), List.of());
            switch (kind) {
                case ORDERS -> {
                    for (int slot = 45; slot < 54; slot++) slots.setItem(slot, pane());
                    slots.setItem(YOUR_ORDERS, button(Items.CHEST, "Your Orders", "Click to view your orders"));
                    slots.setItem(FILTER, button(Items.HOPPER, "Filter", "Most Paid"));
                    slots.setItem(NEXT_PAGE, button(Items.ARROW, "Next Page"));
                }
                case YOUR_ORDERS -> {
                    rows.clear();
                    for (Order o : mine) {
                        if (rows.size() == 45) break;
                        rows.add(o);
                        ItemStack row = new ItemStack(o.item);
                        List<Component> lore = List.of(Component.literal(count(o.requested) + " requested"),
                                Component.literal("$" + o.price + " each"),
                                Component.literal(count(o.requested) + "/" + count(o.requested) + " delivered"),
                                Component.literal("6d 23h 59m until order expires"),
                                Component.literal("Click to edit"));
                        row.set(DataComponents.LORE, new ItemLore(lore));
                        slots.setItem(rows.size() - 1, row);
                    }
                    for (int slot = 45; slot < 54; slot++) slots.setItem(slot, pane());
                }
                case EDIT -> {
                    for (int slot : new int[] {9, 10, 11, 12, 14, 16, 17}) slots.setItem(slot, pane());
                    slots.setItem(ITEM, new ItemStack(order.item));
                    slots.setItem(COLLECT_OR_CANCEL, order.waiting > 0
                            ? button(Items.CHEST_MINECART, "Collect", "Click to collect " + count(order.waiting) + " items")
                            : button(Items.BARRIER, "Cancel", "Click to cancel this order"));
                    slots.setItem(BACK, button(Items.ARROW, "Back"));
                }
                case COLLECT -> {
                    int left = order.waiting, max = order.item.getDefaultMaxStackSize();
                    for (int slot = 0; slot < 45 && left > 0; slot++) {
                        int n = Math.min(max, left);
                        slots.setItem(slot, new ItemStack(order.item, n));
                        left -= n;
                    }
                    for (int slot = 45; slot < 54; slot++) slots.setItem(slot, pane());
                }
            }
        }

        @Override
        public void clicked(int slot, int button, ContainerInput input, Player who) {
            // Nothing is picked up on these pages: the click is answered by the server, the page as it was.
            try {
                if (slot < 0 || slot >= 54) return;
                switch (kind) {
                    case ORDERS -> {
                        if (slot == YOUR_ORDERS && input == ContainerInput.PICKUP) open(player, Kind.YOUR_ORDERS, null);
                    }
                    case YOUR_ORDERS -> {
                        if (slot < rows.size() && input == ContainerInput.PICKUP) open(player, Kind.EDIT, rows.get(slot));
                    }
                    case EDIT -> {
                        if (slot == COLLECT_OR_CANCEL && order.waiting > 0 && input == ContainerInput.PICKUP) open(player, Kind.COLLECT, order);
                        else if (slot == BACK && input == ContainerInput.PICKUP) open(player, Kind.YOUR_ORDERS, null);
                    }
                    case COLLECT -> {
                        ItemStack stack = slots.getItem(slot);
                        if (slot >= 45 || stack.isEmpty() || input != ContainerInput.QUICK_MOVE) return;
                        ItemStack taken = stack.copy();
                        int before = taken.getCount();
                        player.getInventory().add(taken);
                        int moved = before - taken.getCount();
                        if (moved <= 0) return;
                        order.waiting -= moved;
                        if (order.waiting <= 0) ORDERS.getOrDefault(player.getUUID(), List.of()).remove(order);
                        draw();
                    }
                }
            } finally {
                setCarried(ItemStack.EMPTY);
                sendAllDataToRemote();
            }
        }

        @Override
        public ItemStack quickMoveStack(Player who, int slot) { return ItemStack.EMPTY; }

        @Override
        public boolean stillValid(Player who) { return true; }
    }
}
