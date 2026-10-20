package dev.donut.client.order;

import dev.donut.client.schematic.Materials;
import dev.doughbay.core.execution.ExecutionResult;
import dev.doughbay.fabric.AutomatedExecutionDriver;
import dev.doughbay.fabric.DoughBayClient;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Takes what a build needs out of the player's own buy orders on the server, filled already, as a player does with
 * materials others delivered to them: /orders, Your Orders, the order, Collect. It goes through GoNuts' own driver for
 * those pages, read first and then one order at a time, the ones the build is short of now first; what doesn't fit
 * stays on the order for the next trip. Nothing is bought or ordered here, and nothing goes to the server that the
 * driver wouldn't send for its own collect.
 */
public final class OrderCollector {
    private static final Logger LOGGER = LoggerFactory.getLogger("Donut/Orders");
    /** A trip that hangs on a page: GoNuts' own steps time out well before this. */
    private static final int TRIP_TICKS = 20 * 180;
    /** Slots the driver leaves free when it collects; with fewer, the next order waits for the next trip. */
    private static final int KEEP_FREE = 4;

    private enum Step { IDLE, READ, COLLECT }

    private Step step = Step.IDLE;
    /** The driver's last terminal event when the step under way began: a later one is its end. */
    private long seen;
    private int ticks;
    private final Deque<AutomatedExecutionDriver.OwnOrderRow> queue = new ArrayDeque<>();
    private Map<Item, Integer> wanted = Map.of();
    private int collected;
    private String result = "";

    /** Whether the bundled GoNuts and its driver are there to collect with. */
    public static boolean available() {
        try {
            return DoughBayClient.executionDriver() != null;
        } catch (LinkageError | RuntimeException e) {
            return false;
        }
    }

    public boolean busy() { return step != Step.IDLE; }

    /** How the last trip ended, for the status line: empty while one is under way or none was made. */
    public String result() { return result; }

    /** Items collected on the last trip. */
    public int collected() { return collected; }

    /**
     * Starts a trip for {@code short}, how many of each item the build needs beyond what the player carries, in the
     * order given: those first. Null, or why it can't start.
     */
    public String start(LocalPlayer player, Map<Item, Integer> shortOf) {
        if (busy()) return "Already collecting from your orders";
        if (!available()) return "GoNuts isn't in this build";
        if (shortOf.isEmpty()) return "Nothing to collect";
        AutomatedExecutionDriver driver = DoughBayClient.executionDriver();
        if (driver.operationIntent() != AutomatedExecutionDriver.OperationIntent.NONE) return "GoNuts is busy";
        // How many of each to carry once collected: what is carried now and what is short.
        Materials carried = Materials.of(player.getInventory());
        this.wanted = new LinkedHashMap<>();
        shortOf.forEach((item, count) -> this.wanted.put(item, carried.total(item) + count));
        collected = 0;
        result = "";
        queue.clear();
        seen = driver.lastTerminalEvent().sequence();
        ExecutionResult read = driver.readOwnOrders();
        if (!read.ok()) return "Your orders can't be read: " + read.detail();
        step = Step.READ;
        ticks = 0;
        LOGGER.info("Reading your orders for {}", names(this.wanted));
        return null;
    }

    /** Stops the trip; the driver's step under way finishes or times out by itself. */
    public void stop(String why) {
        if (!busy()) return;
        end("stopped: " + why);
    }

    /** Each tick while busy. */
    public void tick(Minecraft client) {
        if (!busy()) return;
        LocalPlayer player = client.player;
        if (player == null) {
            end("stopped: left the world");
            return;
        }
        AutomatedExecutionDriver driver = DoughBayClient.executionDriver();
        if (++ticks > TRIP_TICKS) {
            end("stopped: the orders pages took too long");
            return;
        }
        AutomatedExecutionDriver.TerminalEvent last = driver.lastTerminalEvent();
        if (last.sequence() <= seen || driver.operationIntent() != AutomatedExecutionDriver.OperationIntent.NONE) return;
        seen = last.sequence();
        if (last.outcome() != AutomatedExecutionDriver.TerminalOutcome.SUCCEEDED) {
            LOGGER.info("Orders step ended {}: {}", last.outcome(), last.detail());
            if (step == Step.READ) {
                end("your orders couldn't be read: " + last.detail());
                return;
            }
        }
        if (step == Step.READ) {
            plan(driver.lastOwnOrders());
        } else if (last.outcome() == AutomatedExecutionDriver.TerminalOutcome.SUCCEEDED) {
            collected += last.itemCount();
            LOGGER.info("Collected {} {}", last.itemCount(), last.itemId());
        }
        next(player, driver);
    }

    /** The orders to collect, those of the items wanted first, in the order wanted; one per item and price. */
    private void plan(List<AutomatedExecutionDriver.OwnOrderRow> rows) {
        queue.clear();
        for (Item item : wanted.keySet()) {
            String id = BuiltInRegistries.ITEM.getKey(item).toString();
            for (AutomatedExecutionDriver.OwnOrderRow row : rows) {
                // Delivered and so waiting to be collected: what was delivered, whether or not the order is done.
                if (row.itemId().equals(id) && row.delivered() > 0) queue.add(row);
            }
        }
        LOGGER.info("{} of your {} orders hold what the build needs", queue.size(), rows.size());
    }

    private void next(LocalPlayer player, AutomatedExecutionDriver driver) {
        while (!queue.isEmpty()) {
            if (free(player) <= KEEP_FREE) {
                end(collected > 0 ? "collected " + collected + " items; the rest waits on your orders" : "your inventory is full");
                return;
            }
            AutomatedExecutionDriver.OwnOrderRow row = queue.poll();
            Item item = BuiltInRegistries.ITEM.getValue(net.minecraft.resources.Identifier.parse(row.itemId()));
            Integer want = wanted.get(item);
            if (want == null || Materials.of(player.getInventory()).total(item) >= want) continue;
            ExecutionResult started = driver.collectOrder(row.itemId(), row.unitPrice());
            if (!started.ok()) {
                LOGGER.info("Can't collect {} at {}: {}", row.itemId(), row.unitPrice(), started.detail());
                continue;
            }
            step = Step.COLLECT;
            ticks = 0;
            return;
        }
        end(collected > 0 ? "collected " + collected + " items from your orders" : "nothing waiting on your orders for this build");
    }

    private static int free(LocalPlayer player) {
        int free = 0;
        for (int slot = 0; slot < Materials.SLOTS; slot++) if (player.getInventory().getItem(slot).isEmpty()) free++;
        return free;
    }

    private void end(String text) {
        step = Step.IDLE;
        queue.clear();
        result = text;
        LOGGER.info("Orders trip done: {}", text);
    }

    private static String names(Map<Item, Integer> items) {
        StringBuilder text = new StringBuilder();
        for (Map.Entry<Item, Integer> e : items.entrySet()) {
            if (!text.isEmpty()) text.append(", ");
            text.append(e.getValue()).append(' ').append(BuiltInRegistries.ITEM.getKey(e.getKey()).getPath());
        }
        return text.toString();
    }
}
