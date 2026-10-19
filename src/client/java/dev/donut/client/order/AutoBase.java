package dev.donut.client.order;

import dev.donut.client.config.ClientConfig;
import dev.donut.client.config.PipelineSettings;
import dev.donut.client.miner.ChunkMiner;
import dev.donut.client.schematic.SchematicBuilder;
import dev.doughbay.fabric.GoNutsApi;
import dev.doughbay.fabric.MaterialBuyer;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;

/**
 * AutoBase: one run that digs the schematic's room with ChunkMiner, buys what the build still needs through GoNuts,
 * and builds it with SchematicBuilder, each step once the one before is done. A quote over the auto-spend amount waits
 * for the player's Confirm, as Order materials does.
 *
 * <p>The guard, on whether or not a run is on: while GoNuts' player guard holds, ChunkMiner and SchematicBuilder are held
 * and the buying stopped, and what was held goes on once it has been clear a while; a Staff Watch alert stops all three
 * and nothing goes on by itself after it.
 */
public final class AutoBase {
    public enum Step { IDLE, MINE, QUOTE, CONFIRM, BUY, BUILD, DONE, STOPPED }

    private static ClientConfig config;
    private static Runnable save = () -> { };
    private static Step step = Step.IDLE;
    private static String status = "Idle";
    private static boolean statusWarn;
    /** Whether the step's module has been seen at work, so that it stopping means the step is over. */
    private static boolean seenWorking;
    private static boolean minerBuildAfter;

    private static long staffSeenAt = -1;
    private static boolean heldMiner, heldBuilder;
    private static long clearSince = -1;

    private AutoBase() { }

    public static void init(ClientConfig clientConfig, Runnable saveConfig) {
        config = clientConfig;
        save = saveConfig;
    }

    private static PipelineSettings settings() { return config.pipeline; }

    public static Step step() { return step; }

    public static String status() { return status; }

    public static boolean statusWarn() { return statusWarn; }

    /** GoNuts' guard as the menu shows it: its status, and the last Staff Watch alert. */
    public static String guardStatus() {
        GoNutsApi.Guard guard = GoNutsApi.guard();
        String staff = guard.lastStaffAt() <= 0 ? "" : "; last staff alert " + guard.lastStaff() + ", "
                + Math.max(0, (System.currentTimeMillis() - guard.lastStaffAt()) / 60_000) + " min ago";
        return guard.status() + staff;
    }

    public static boolean running() { return step != Step.IDLE && step != Step.DONE && step != Step.STOPPED; }

    /** Starts a run from the first step; null, or why not. */
    public static String start() {
        if (running()) return "AutoBase is already running";
        SchematicBuilder builder = SchematicBuilder.getInstance();
        ChunkMiner miner = ChunkMiner.getInstance();
        if (builder == null || miner == null || builder.schematic() == null) return "Pick a schematic in SchematicBuilder first";
        if (!MaterialOrder.available()) return "GoNuts isn't in this build";
        if (SchematicBuilder.busy() || miner.isActive()) return "Stop ChunkMiner and SchematicBuilder first";
        if (GoNutsApi.busy()) return "GoNuts is busy; stop it first";
        // The miner would start the build itself when done; here the buying comes between.
        minerBuildAfter = config.miner.buildAfter;
        config.miner.buildAfter = false;
        settings().enabled = true;
        enter(Step.MINE, "Digging the room");
        miner.setEnabled(true);
        return null;
    }

    /** Stops the run and whatever step is at work. */
    public static void stop(String why) {
        if (!running()) {
            settings().enabled = false;
            return;
        }
        halt();
        end(Step.STOPPED, why, true);
    }

    private static void halt() {
        ChunkMiner miner = ChunkMiner.getInstance();
        if (miner != null && miner.isActive()) miner.setEnabled(false);
        SchematicBuilder builder = SchematicBuilder.getInstance();
        if (builder != null && builder.isActive()) builder.setEnabled(false);
        MaterialOrder.stop();
    }

    /** The player's Confirm on a quote over the auto-spend amount. */
    public static String confirm() {
        if (step != Step.CONFIRM) return "Nothing waits for a confirm";
        return buy();
    }

    public static void tick(Minecraft client) {
        if (config == null || client.player == null) return;
        guard(client);
        if (!running()) return;
        if (!settings().enabled) {
            stop("Stopped");
            return;
        }
        switch (step) {
            case MINE -> {
                ChunkMiner miner = ChunkMiner.getInstance();
                if (miner.isActive() || config.miner.enabled) {
                    seenWorking = true;
                    return;
                }
                if (heldMiner) return;
                if (seenWorking && miner.statusWarn()) {
                    end(Step.STOPPED, "ChunkMiner " + lower(miner.status()), true);
                    return;
                }
                quote();
            }
            case QUOTE -> {
                MaterialBuyer.Snapshot order = MaterialOrder.snapshot();
                if (order == null || order.state() == MaterialBuyer.State.QUOTING) return;
                if (order.state() != MaterialBuyer.State.QUOTED) {
                    end(Step.STOPPED, "The quote " + lower(order.message()), true);
                    return;
                }
                if (order.quoteTotal() <= 0) {
                    build();
                } else if (order.quoteTotal() <= settings().autoSpend) {
                    buy();
                } else {
                    enter(Step.CONFIRM, "Quote " + money(order.quoteTotal()) + " is over " + money(settings().autoSpend)
                            + ": confirm it in AutoBase, or stop");
                    tell(client, "AutoBase: the materials come to about " + money(order.quoteTotal()) + "; open the menu to confirm.", false);
                    client.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_BELL, 1.0f));
                }
            }
            case BUY -> {
                MaterialBuyer.Snapshot order = MaterialOrder.snapshot();
                if (order == null || order.state() == MaterialBuyer.State.BUYING) return;
                // The page GoNuts bought through closes first; the builder would only pause behind it.
                if (client.gui.screen() != null || GoNutsApi.busy()) return;
                if (order.state() != MaterialBuyer.State.DONE) {
                    end(Step.STOPPED, "Buying " + lower(order.message()), true);
                    return;
                }
                build();
            }
            case BUILD -> {
                SchematicBuilder builder = SchematicBuilder.getInstance();
                if (builder.isActive()) {
                    seenWorking = true;
                    return;
                }
                if (heldBuilder || config.schematic.enabled) return;
                // Switched off again without ever starting: it refused, and says why.
                if (!seenWorking || builder.statusWarn()) end(Step.STOPPED, "SchematicBuilder " + lower(builder.status()), true);
                else end(Step.DONE, builder.status(), false);
            }
            default -> { }
        }
    }

    private static void quote() {
        SchematicBuilder builder = SchematicBuilder.getInstance();
        MaterialOrder.clear();
        String why = MaterialOrder.quote(builder, settings().highPrice);
        if ("You carry everything it needs".equals(why)) {
            build();
            return;
        }
        if (why != null) {
            end(Step.STOPPED, "No quote: " + lower(why), true);
            return;
        }
        enter(Step.QUOTE, "Checking prices on the auction house");
    }

    private static String buy() {
        String why = MaterialOrder.buy(false);
        if (why != null) {
            end(Step.STOPPED, "Buying didn't start: " + lower(why), true);
            return why;
        }
        enter(Step.BUY, "Buying the materials");
        return null;
    }

    private static void build() {
        enter(Step.BUILD, "Building");
        SchematicBuilder.getInstance().setEnabled(true);
    }

    private static void enter(Step next, String text) {
        step = next;
        seenWorking = false;
        status = text;
        statusWarn = false;
    }

    private static void end(Step last, String text, boolean warn) {
        step = last;
        status = text;
        statusWarn = warn;
        settings().enabled = false;
        config.miner.buildAfter = minerBuildAfter;
        save.run();
        Minecraft client = Minecraft.getInstance();
        tell(client, "AutoBase " + (last == Step.DONE ? "finished: " : "stopped: ") + lower(text) + ".", warn);
    }

    /** GoNuts' guards over the three modules, whether or not a run is on. */
    private static void guard(Minecraft client) {
        if (!MaterialOrder.available()) return;
        GoNutsApi.Guard guard = GoNutsApi.guard();
        if (staffSeenAt < 0) staffSeenAt = guard.lastStaffAt();
        if (guard.lastStaffAt() > staffSeenAt) {
            staffSeenAt = guard.lastStaffAt();
            if (settings().staffStop && anyWorking()) {
                halt();
                heldMiner = heldBuilder = false;
                tell(client, "Staff Watch alert (" + guard.lastStaff() + "): ChunkMiner, buying and SchematicBuilder stopped.", true);
                client.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_BELL, 1.0f));
                if (running()) end(Step.STOPPED, "Staff Watch alert: " + guard.lastStaff(), true);
                return;
            }
        }
        if (!settings().guardHold) {
            heldMiner = heldBuilder = false;
            return;
        }
        if (guard.holding()) {
            clearSince = -1;
            ChunkMiner miner = ChunkMiner.getInstance();
            SchematicBuilder builder = SchematicBuilder.getInstance();
            if (miner != null && miner.isActive()) {
                miner.setEnabled(false);
                heldMiner = true;
            }
            if (builder != null && builder.isActive()) {
                builder.setEnabled(false);
                heldBuilder = true;
            }
            MaterialBuyer.Snapshot order = MaterialOrder.snapshot();
            if (order != null && (order.state() == MaterialBuyer.State.QUOTING || order.state() == MaterialBuyer.State.BUYING)) {
                MaterialOrder.stop();
                if (running()) end(Step.STOPPED, "GoNuts' guard held while buying: " + lower(guard.status()), true);
            }
            if (heldMiner || heldBuilder) status = "Held: " + guard.status();
            return;
        }
        if (!heldMiner && !heldBuilder) return;
        long now = System.currentTimeMillis();
        if (clearSince < 0) clearSince = now;
        if (now - clearSince < settings().clearSeconds * 1000L) return;
        if (heldMiner) ChunkMiner.getInstance().setEnabled(true);
        if (heldBuilder) SchematicBuilder.getInstance().setEnabled(true);
        heldMiner = heldBuilder = false;
        clearSince = -1;
        tell(client, "GoNuts' guard is clear again: going on.", false);
    }

    private static boolean anyWorking() {
        ChunkMiner miner = ChunkMiner.getInstance();
        SchematicBuilder builder = SchematicBuilder.getInstance();
        return running() || heldMiner || heldBuilder || miner != null && miner.isActive() || builder != null && builder.isActive()
                || MaterialOrder.snapshot() != null && GoNutsApi.materials().state() == MaterialBuyer.State.BUYING;
    }

    private static void tell(Minecraft client, String text, boolean warn) {
        if (client.player != null) {
            client.player.sendSystemMessage(Component.literal("[Ven] " + text).withStyle(warn ? ChatFormatting.YELLOW : ChatFormatting.GRAY));
        }
    }

    private static String lower(String text) {
        if (text == null || text.isEmpty()) return "";
        String t = text.endsWith(".") ? text.substring(0, text.length() - 1) : text;
        return t.substring(0, 1).toLowerCase(Locale.ROOT) + t.substring(1);
    }

    private static String money(long amount) { return String.format(Locale.ROOT, "$%,d", amount); }
}
