package dev.donut.client.rtp;

import dev.donut.client.config.ClientConfig;
import dev.donut.client.config.RtpSettings;
import dev.donut.client.finder.ActivityArea;
import dev.donut.client.finder.ActivityStats;
import dev.donut.client.finder.FinderScanner;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.phys.Vec3;

/** Drives {@link RtpLoop} from the client: sends the command, clicks its menu, and watches ActivityFinder. */
public final class AutoRtpController {
    /** A random teleport moves much further than this; walking during a countdown does not. */
    private static final double TELEPORT_DISTANCE = 48;
    /** How long after sending a newly opened menu is taken to be the command's. */
    private static final int MENU_WINDOW = 10 * 20;
    private static final int BLIND_CHUNKS = 100;
    private static AutoRtpController instance;

    private final ClientConfig config;
    private final FinderScanner scanner;
    private final Runnable save;
    private final RtpLoop loop = new RtpLoop();
    private long tick, sentTick;
    private Vec3 sentFrom;
    private ClientLevel sentLevel;
    /** The menu that was open when the command went out; only a menu opened after it can be the command's. */
    private int sentMenuId = -1;
    private boolean menuHandled;
    private float lastHealth = -1;
    private int lastMenuSlot = -1;
    private String lastMessage = "";

    public AutoRtpController(ClientConfig config, FinderScanner scanner, Runnable save) {
        this.config = config;
        this.scanner = scanner;
        this.save = save;
        instance = this;
    }

    public static AutoRtpController getInstance() { return instance; }

    private RtpSettings settings() { return config.rtp; }

    public boolean isActive() { return loop.phase() != RtpLoop.Phase.IDLE; }

    public boolean isEnabled() { return settings().enabled; }

    public RtpLoop.Phase phase() { return loop.phase(); }

    public RtpLoop.Result result() { return loop.result(); }

    public int attempts() { return loop.attempts(); }

    /** The slot the last command menu was clicked at, for tests. */
    public int lastMenuSlot() { return lastMenuSlot; }

    public void setEnabled(boolean enabled) {
        if (enabled == settings().enabled) return;
        settings().enabled = enabled;
        if (!enabled) finish(Minecraft.getInstance(), RtpLoop.Result.STOPPED);
    }

    public void tick(Minecraft client) {
        tick++;
        if (client.player == null || client.level == null) {
            if (settings().enabled || isActive()) { settings().enabled = false; loop.stop(RtpLoop.Result.STOPPED); }
            lastHealth = -1;
            forgetSend();
            return;
        }
        if (!settings().enabled) {
            // Turned off from the GUI or a keybind.
            if (isActive()) finish(client, RtpLoop.Result.STOPPED);
            lastHealth = client.player.getHealth();
            return;
        }
        if (!isActive()) begin(client);
        if (!config.activity.enabled) {
            // Without ActivityFinder every remaining attempt would be spent blind.
            finish(client, RtpLoop.Result.ACTIVITY_OFF);
            return;
        }
        RtpSettings s = settings();
        // Timing changes made during a run apply to its next step.
        loop.configure(s.intervalSeconds, s.scanSeconds, s.maxAttempts);

        float health = client.player.getHealth();
        boolean hurt = lastHealth >= 0 && health < lastHealth;
        lastHealth = health;

        long sinceSend = tick - sentTick;
        if (loop.phase() == RtpLoop.Phase.AWAIT_TELEPORT && !menuHandled && sinceSend <= MENU_WINDOW
                && client.gui.screen() instanceof ContainerScreen menu && menu.getMenu().containerId != sentMenuId && hasItems(menu)) {
            // Items can arrive a tick or a few packets after the screen opens, so a menu without a match gets the
            // rest of the window before the run gives up on it.
            if (clickMenuItem(client, menu)) menuHandled = true;
            else if (sinceSend == MENU_WINDOW) { finish(client, RtpLoop.Result.MENU_MISSING); return; }
        }

        ActivityStats stats = scanner.activityStats();
        boolean blind = stats.overworld() && stats.scannedChunks() >= BLIND_CHUNKS && stats.amethystChunks() == 0;
        // The best chunk that worldgen practically never grows this far, not just the highest score: a lucky piece of a
        // geode must not hide a real one. A score of 0 is plain worldgen, which a 0% threshold must not stop on.
        ActivityArea best = stats.certain();
        int hits = best != null && best.score() > 0 && best.score() >= s.stopAtGrowth ? 1 : 0;
        RtpLoop.Action action = loop.tick(tick, teleported(client), hurt, hits, blind, stats.overworld(),
                settings().stopWhenHurt);
        if (action == RtpLoop.Action.SEND) send(client);
        else if (action == RtpLoop.Action.STOP) finish(client, loop.result());
    }

    private void begin(Minecraft client) {
        RtpSettings s = settings();
        loop.configure(s.intervalSeconds, s.scanSeconds, s.maxAttempts);
        loop.start(tick);
        lastMenuSlot = -1;
        String turnedOn = "";
        if (!config.activity.enabled || !config.activity.allLoadedChunks) {
            // Auto RTP stops on ActivityFinder's results, so it needs the finder running over every chunk the server sent.
            config.activity.enabled = true;
            config.activity.allLoadedChunks = true;
            save.run();
            turnedOn = " ActivityFinder is now on, over all loaded chunks.";
        }
        message(client, "started. Sending /" + s.command + " every " + Math.round(s.intervalSeconds)
                + " s, up to " + s.maxAttempts + (s.maxAttempts == 1 ? " time" : " times") + ", until a chunk reaches "
                + Math.round(s.stopAtGrowth * 100) + "% growth." + turnedOn, ChatFormatting.GRAY);
    }

    private void forgetSend() {
        // The world the last command was sent from can be gone; holding it would keep all its chunks in memory.
        sentFrom = null;
        sentLevel = null;
    }

    private void send(Minecraft client) {
        sentFrom = client.player.position();
        sentLevel = client.level;
        sentTick = tick;
        sentMenuId = client.player.containerMenu.containerId;
        menuHandled = false;
        // Sent exactly as if typed in chat; the local line shows it, since chat does not echo commands.
        client.player.connection.sendCommand(settings().command);
        message(client, "/" + settings().command + " (attempt " + loop.attempts() + " of " + loop.maxAttempts() + ")",
                ChatFormatting.DARK_GRAY);
    }

    private boolean teleported(Minecraft client) {
        if (sentFrom == null) return false;
        if (client.level != sentLevel) return true;
        Vec3 now = client.player.position();
        double dx = now.x - sentFrom.x, dz = now.z - sentFrom.z;
        return dx * dx + dz * dz > TELEPORT_DISTANCE * TELEPORT_DISTANCE;
    }

    private static boolean hasItems(ContainerScreen screen) {
        var menu = screen.getMenu();
        int size = Math.min(menu.getContainer().getContainerSize(), menu.slots.size());
        for (int index = 0; index < size; index++) if (!menu.slots.get(index).getItem().isEmpty()) return true;
        return false;
    }

    private boolean clickMenuItem(Minecraft client, ContainerScreen screen) {
        var menu = screen.getMenu();
        int size = menu.getContainer().getContainerSize();
        List<String> names = new ArrayList<>();
        // A name match beats a lore match, so "Choose Overworld, Nether or End" on an info item doesn't win.
        for (boolean byLore : new boolean[] {false, true}) {
            for (int index = 0; index < size && index < menu.slots.size(); index++) {
                Slot slot = menu.slots.get(index);
                ItemStack stack = slot.getItem();
                if (stack.isEmpty()) continue;
                String name = stack.getHoverName().getString();
                if (!byLore) names.add(name);
                List<String> lore = new ArrayList<>();
                if (byLore) {
                    ItemLore itemLore = stack.get(DataComponents.LORE);
                    if (itemLore != null) for (Component line : itemLore.lines()) lore.add(line.getString());
                }
                if (RtpLoop.matchesMenuItem(byLore ? "" : name, lore, settings().menuItem)) {
                    lastMenuSlot = slot.index;
                    client.gameMode.handleContainerInput(menu.containerId, slot.index, 0, ContainerInput.PICKUP, client.player);
                    return true;
                }
            }
        }
        lastMessage = "Menu items: " + String.join(", ", names.subList(0, Math.min(names.size(), 8)));
        return false;
    }

    /** Server chat: a cooldown notice delays the next attempt instead of spamming the command. */
    public void onServerMessage(String text) {
        if (!isActive()) return;
        int seconds = RtpLoop.cooldownSeconds(text);
        if (seconds >= 0 && loop.serverCooldown(tick, seconds)) {
            long wait = Math.max(0, (loop.nextSend() - tick + 19) / 20);
            message(Minecraft.getInstance(), "server cooldown: next /" + settings().command + " in " + wait + " s.",
                    ChatFormatting.DARK_GRAY);
        }
    }

    private void finish(Minecraft client, RtpLoop.Result result) {
        loop.stop(result);
        settings().enabled = false;
        forgetSend();
        if (client.player == null || result == null) return;
        if (result == RtpLoop.Result.FOUND) {
            ActivityStats stats = scanner.activityStats();
            ActivityArea best = stats.certain() != null ? stats.certain() : stats.best();
            String where = best == null ? "" : ": " + Math.round(best.score() * 100) + "% growth at x "
                    + (best.chunk().getMinBlockX() + 8) + ", z " + (best.chunk().getMinBlockZ() + 8)
                    + ", geode at y " + best.minY() + (best.maxY() > best.minY() ? " to " + best.maxY() : "")
                    + ", " + Math.round(best.horizontalDistance(client.player.position())) + " blocks away";
            int attempts = loop.areaAttempt();
            String pending = loop.commandPending() ? " The next /" + settings().command
                    + " was already sent: close its menu or move to cancel the teleport." : "";
            message(client, "found likely player activity after " + attempts + " "
                    + (attempts == 1 ? "attempt" : "attempts") + where + "." + pending, ChatFormatting.GREEN);
            client.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.PLAYER_LEVELUP, 1.0f));
        } else if (result == RtpLoop.Result.STOPPED) {
            message(client, "stopped.", ChatFormatting.GRAY);
        } else {
            String extra = result == RtpLoop.Result.MENU_MISSING && !lastMessage.isEmpty() ? " " + lastMessage : "";
            message(client, "stopped: " + result.reason + "." + extra, ChatFormatting.YELLOW);
        }
    }

    private static void message(Minecraft client, String text, ChatFormatting color) {
        if (client.player == null) return;
        client.player.sendSystemMessage(Component.literal("[Donut] Auto RTP " + text).withStyle(color));
    }

    /** A short line for the settings page. */
    public String status() {
        if (!isActive()) {
            RtpLoop.Result result = loop.result();
            if (result == null) return "Idle";
            int attempts = result == RtpLoop.Result.FOUND ? loop.areaAttempt() : loop.attempts();
            return "Last run: " + result.reason + (attempts == 0 ? "" : " (" + attempts + (attempts == 1 ? " attempt)" : " attempts)"));
        }
        String attempt = "attempt " + loop.attempts() + " of " + loop.maxAttempts();
        long wait = Math.max(0, (loop.nextSend() - tick + 19) / 20);
        return switch (loop.phase()) {
            case COOLDOWN -> loop.nextSend() <= tick ? "Starting"
                    : (loop.attempts() >= loop.maxAttempts() ? "Finishing in " : "Next in ") + wait + " s (" + attempt + ")";
            case AWAIT_TELEPORT -> "Waiting for the teleport (" + attempt + ")";
            case SCANNING -> "Scanning the new area (" + attempt + ")";
            case IDLE -> "Idle";
        };
    }
}
