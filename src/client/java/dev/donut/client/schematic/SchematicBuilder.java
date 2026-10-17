package dev.donut.client.schematic;

import dev.donut.client.config.ClientConfig;
import dev.donut.client.config.SchematicSettings;
import dev.donut.client.finder.FinderSummary;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds a Litematica schematic in the world, a layer at a time from the bottom up, from what the player carries.
 *
 * <p>A layer is done before the next starts. Within it, blocks that something must hold go in once what holds them is
 * there: a wall torch after its wall, a lantern hanging from a ceiling in the layer above after that ceiling. Those
 * wait while the layers above go in, and go in as soon as they can. Redstone parts follow the plain blocks of their
 * layer, and pistons, observers and dispensers come last, so a half-built layer fires as little as possible. Each block
 * waits a random time between the shortest and longest delay set.
 *
 * <p>Blocks come from the inventory; a block found only inside a shulker box in the inventory is fetched by placing the
 * box next to the player, taking what the build still needs, and breaking the box to pick it up again.
 */
public final class SchematicBuilder {
    private static final Logger LOGGER = LoggerFactory.getLogger("Donut/Schematic");
    /** Blocks read back from the world each tick. */
    private static final int REFRESH_BUDGET = 4096;
    /** What working out the next block may take from one tick; the rest waits for the next. */
    private static final long PLAN_NANOS = 6_000_000;
    /** How often the schematic file is checked for a newer copy. */
    private static final long FILE_CHECK_NANOS = 1_000_000_000;
    private static final int PREVIEW_RANGE = 48, PREVIEW_MAX = 1500;
    private static final int RESTOCK_FAILURES = 3;
    private static SchematicBuilder instance;
    /** Logs each placement and why blocks can't go in, with -Ddonut.schematic.debug=true. */
    private static boolean debug() { return Boolean.getBoolean("donut.schematic.debug"); }

    private final ClientConfig config;
    private final Runnable save;

    private Schematic schematic;
    private String schematicFile = "";
    private long schematicStamp = Long.MIN_VALUE, fileCheckedAt;
    private String loadProblem = "";
    private Schematic billFor;
    private Map<Item, Integer> bill = Map.of();

    private BuildPlan plan;
    private String planKey = "";
    private ClientLevel planLevel;
    private boolean active, shortageReported;
    private long tick, nextAction;
    private int buildSlot = -1;

    private volatile String status = "Idle";
    private volatile boolean statusWarn;
    private volatile BlockPos next;
    private volatile List<Preview> preview = List.of();
    private volatile Progress progress = Progress.NONE;
    private int layerY = Integer.MIN_VALUE, nextIndex = -1;
    private final List<Item> missing = new ArrayList<>();

    /**
     * A placement waiting for the server to turn the player's head: it works out pistons', observers' and dispensers'
     * facing from the head, which follows a new look only on its next tick. The look goes out first, the click a
     * couple of ticks later.
     */
    private record Aim(int index, PlacementSolver.Click click, Item item, BlockState before, int layerNumber, long at,
                       float yaw, float pitch) { }

    /** Ticks between sending a look and clicking with it. */
    private static final int AIM_TICKS = 2;
    private Aim aim;
    private Restock restock = Restock.NONE;
    private Item restockItem;
    private BlockPos box;
    private int restockTicks, nextTake, boxesBefore, restockFailures;
    /** Whether the builder holds the forward key, walking to a dropped shulker box. */
    private boolean walking;
    private Direction breakFace = Direction.UP;

    /** Fetching blocks from a shulker box, a step at a time. */
    private enum Restock { NONE, PLACED, OPENED, TAKE, BREAK, PICKUP }

    /** A box drawn in the world: the schematic's bounds, and the blocks left in the layer being built. */
    public record Preview(AABB box, int color, boolean filled) { }

    /**
     * Where the build stands: blocks placed out of those the builder places, and which layer is being built.
     *
     * @param layer the layer's place from the bottom, from 1, or 0 when none is being built
     */
    public record Progress(int placeable, int done, int left, int wrong, int failed, int misplaced, int skipped,
                           int layer, int layers, int layerY) {
        static final Progress NONE = new Progress(0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    public SchematicBuilder(ClientConfig config, Runnable save) {
        this.config = config;
        this.save = save;
        instance = this;
    }

    public static SchematicBuilder getInstance() { return instance; }

    /** Whether a build is under way; Auto RTP waits for it. */
    public static boolean busy() { return instance != null && instance.active; }

    /** While a shulker box is placed, emptied or broken, the player's own clicks would get in the way and wait. */
    public static boolean holdsHands() { return instance != null && instance.restock != Restock.NONE; }

    public SchematicSettings settings() { return config.schematic; }

    public boolean isActive() { return active; }

    public String status() { return status; }

    public boolean statusWarn() { return statusWarn; }

    public Progress progress() { return progress; }

    public List<Preview> preview() { return preview; }

    // Files ---------------------------------------------------------------------------------------------------------

    /** The folder schematics are read from, the one Litematica saves to. */
    public static Path folder() { return Minecraft.getInstance().gameDirectory.toPath().resolve("schematics"); }

    /** The .litematic files in the folder, newest first. */
    public static List<String> files() {
        Path folder = folder();
        if (!Files.isDirectory(folder)) return List.of();
        try (Stream<Path> entries = Files.list(folder)) {
            return entries.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".litematic") && Files.isRegularFile(p))
                    .sorted(Comparator.comparingLong(SchematicBuilder::modified).reversed())
                    .limit(64).map(p -> p.getFileName().toString()).toList();
        } catch (IOException e) {
            LOGGER.warn("Could not list {}", folder, e);
            return List.of();
        }
    }

    private static long modified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    /** Copies dropped .litematic files into the folder; the name of the first, or null when none was one. */
    public static String importFiles(List<Path> dropped) throws IOException {
        String first = null;
        for (Path path : dropped) {
            String name = path.getFileName().toString();
            if (!name.toLowerCase(Locale.ROOT).endsWith(".litematic") || !Files.isRegularFile(path)) continue;
            Files.createDirectories(folder());
            Path into = folder().resolve(name);
            if (!into.toAbsolutePath().normalize().equals(path.toAbsolutePath().normalize())) {
                Files.copy(path, into, StandardCopyOption.REPLACE_EXISTING);
            }
            if (first == null) first = name;
        }
        return first;
    }

    /** The schematic the settings name, read again when its file changes; null when it can't be read. */
    public Schematic schematic() {
        load(false);
        return schematic;
    }

    /** Why the chosen schematic can't be used, or empty. */
    public String loadProblem() {
        load(false);
        return loadProblem;
    }

    private void load(boolean now) {
        String file = settings().file;
        long clock = System.nanoTime();
        if (!now && file.equals(schematicFile) && clock - fileCheckedAt < FILE_CHECK_NANOS) return;
        fileCheckedAt = clock;
        Path path = folder().resolve(file);
        long stamp = file.isEmpty() || !Files.isRegularFile(path) ? -1 : modified(path);
        if (file.equals(schematicFile) && stamp == schematicStamp) return;
        schematicFile = file;
        schematicStamp = stamp;
        schematic = null;
        if (file.isEmpty()) {
            loadProblem = "No schematic chosen";
        } else if (stamp < 0) {
            loadProblem = file + " is not in the schematics folder";
        } else {
            try {
                schematic = Schematic.of(LitematicFile.read(path), Minecraft.getInstance().getFixerUpper());
                loadProblem = schematic.size() == 0 ? file + " holds no blocks" : "";
                if (schematic.size() == 0) schematic = null;
            } catch (IOException | RuntimeException e) {
                LOGGER.warn("Could not read schematic {}", path, e);
                loadProblem = "Can't read " + file + ": " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }
        }
    }

    // Control ------------------------------------------------------------------------------------------------------

    public void setEnabled(boolean enabled) {
        if (enabled == settings().enabled) return;
        settings().enabled = enabled;
        if (!enabled && active) finish(Minecraft.getInstance(), "stopped.", false);
    }

    /** Called on disconnect: the world, and anything placed in it, is gone. */
    public void reset() {
        settings().enabled = false;
        active = false;
        restock = Restock.NONE;
        aim = null;
        plan = null;
        planKey = "";
        planLevel = null;
        preview = List.of();
        progress = Progress.NONE;
        next = null;
        show("Idle", false);
    }

    public void tick(Minecraft client) {
        tick++;
        LocalPlayer player = client.player;
        if (player == null || client.level == null) {
            if (settings().enabled || active) reset();
            return;
        }
        if (!settings().enabled) {
            if (active) finish(client, "stopped.", false);
            return;
        }
        if (!active && !begin(client, player)) return;
        if (!player.isAlive()) { finish(client, "stopped: you died.", true); return; }
        if (player.isSpectator()) { finish(client, "stopped: spectators can't place blocks.", true); return; }
        if (client.level != planLevel) { finish(client, "stopped: you left the world it was started in.", true); return; }
        load(false);
        if (schematic == null) { finish(client, "stopped: " + loadProblem + ".", true); return; }
        if (!planKey.equals(key())) preparePlan(client);
        plan.refresh(client.level, REFRESH_BUDGET);
        if (restock != Restock.NONE) {
            restockTick(client, player);
            if (tick % 10 == 0) updatePreview(player);
            return;
        }
        if (client.gui.screen() != null) { show("Paused while a menu is open", false); return; }
        if (player.isUsingItem() || player.isSleeping()) { show("Waiting while you use an item", false); return; }
        if (tick % 10 == 0) updatePreview(player);
        if (!plan.swept()) { show("Reading the build site", false); return; }
        if (!shortageReported) reportShortage(client, player);
        if (aim != null) {
            fire(client, player);
            return;
        }
        if (System.nanoTime() - nextAction < 0) return;
        decide(client, player);
    }

    private boolean begin(Minecraft client, LocalPlayer player) {
        if (config.rtp.enabled) {
            settings().enabled = false;
            message(client, "can't start while Auto RTP runs; stop it first.", ChatFormatting.YELLOW);
            return false;
        }
        load(true);
        if (schematic == null) {
            settings().enabled = false;
            message(client, "can't start: " + loadProblem + ".", ChatFormatting.YELLOW);
            return false;
        }
        String originNote = "";
        if (!settings().originSet) {
            BlockPos at = player.blockPosition();
            settings().originSet = true;
            settings().originX = at.getX();
            settings().originY = at.getY();
            settings().originZ = at.getZ();
            save.run();
            originNote = " Its origin is where you stand, " + at.getX() + " " + at.getY() + " " + at.getZ() + ".";
        }
        preparePlan(client);
        active = true;
        shortageReported = false;
        restockFailures = 0;
        restock = Restock.NONE;
        buildSlot = -1;
        nextAction = System.nanoTime() + 500_000_000L;
        int layers = plan.layers().size();
        String span = layers == 0 ? "" : " (y " + plan.layers().firstKey() + (layers > 1 ? " to " + plan.layers().lastKey() : "") + ")";
        message(client, "started " + schematic.name + ": " + plan.placeable() + " blocks in " + layers
                + (layers == 1 ? " layer" : " layers") + span + ", one every " + seconds(settings().minDelay) + " to "
                + seconds(settings().maxDelay) + " s." + originNote, ChatFormatting.GRAY);
        if (!schematic.unknown.isEmpty()) {
            message(client, "leaves out blocks this game doesn't have: " + String.join(", ", schematic.unknown.keySet()) + ".",
                    ChatFormatting.YELLOW);
        }
        return true;
    }

    private String key() {
        SchematicSettings s = settings();
        return s.file + "|" + schematicStamp + "|" + s.originX + "," + s.originY + "," + s.originZ + "|" + s.rotation + "|" + s.mirror
                + "|" + s.redstoneLast;
    }

    private void preparePlan(Minecraft client) {
        SchematicSettings s = settings();
        plan = BuildPlan.of(schematic, new BlockPos(s.originX, s.originY, s.originZ), mirror(s.mirror), rotation(s.rotation), s.redstoneLast);
        planKey = key();
        planLevel = client.level;
        shortageReported = false;
        nextIndex = -1;
    }

    public static Rotation rotation(int quarterTurns) {
        return switch (Math.floorMod(quarterTurns, 4)) {
            case 1 -> Rotation.CLOCKWISE_90;
            case 2 -> Rotation.CLOCKWISE_180;
            case 3 -> Rotation.COUNTERCLOCKWISE_90;
            default -> Rotation.NONE;
        };
    }

    public static Mirror mirror(int mirror) {
        return switch (mirror) {
            case 1 -> Mirror.LEFT_RIGHT;
            case 2 -> Mirror.FRONT_BACK;
            default -> Mirror.NONE;
        };
    }

    // Deciding -----------------------------------------------------------------------------------------------------

    /** Picks the next block and places it, or says what it waits for. */
    private void decide(Minecraft client, LocalPlayer player) {
        ClientLevel level = client.level;
        long started = System.nanoTime();
        boolean creative = player.hasInfiniteMaterials();
        Materials materials = Materials.of(player.getInventory());
        // The layer: the lowest with a block that can go in now; lower blocks still waiting need something above them.
        // Each layer lists plain blocks, then redstone, then what moves, so the first block found also sets the group.
        boolean byGroup = settings().redstoneLast;
        int[] layer = null;
        int layerNumber = 0, number = 0, group = 0;
        for (Map.Entry<Integer, int[]> entry : plan.layers().entrySet()) {
            number++;
            for (int i : entry.getValue()) {
                BuildPlan.Status st = plan.status(i);
                if (st == BuildPlan.Status.PENDING) st = plan.update(level, i);
                if (st == BuildPlan.Status.PENDING) {
                    byte support = support(player, i, started);
                    if (support == 0) { show("Working out the build order", false); return; }
                    if (support != 1) continue;
                } else if (st != BuildPlan.Status.UNLOADED) {
                    // An unloaded block may well be missing too; the layer waits until the player is near enough to see it.
                    continue;
                }
                layer = entry.getValue();
                group = byGroup ? BlockRules.group(plan.target(i)) : 0;
                break;
            }
            if (layer != null) {
                layerY = entry.getKey();
                layerNumber = number;
                break;
            }
        }
        if (layer == null) {
            finishBuild(client);
            return;
        }
        Vec3 eye = player.getEyePosition();
        List<Integer> candidates = new ArrayList<>();
        for (int i : layer) {
            BuildPlan.Status st = plan.status(i);
            if (st != BuildPlan.Status.PENDING && st != BuildPlan.Status.UNLOADED) continue;
            if (byGroup && BlockRules.group(plan.target(i)) != group) continue;
            candidates.add(i);
        }
        candidates.sort(Comparator.comparingDouble(i -> Vec3.atCenterOf(plan.pos(i)).distanceToSqr(eye)));
        double reach = player.blockInteractionRange();
        boolean strict = settings().strictLooks;
        missing.clear();
        PlacementSolver.Problem seen = null;
        int nearest = -1;
        for (int i : candidates) {
            BlockPos pos = plan.pos(i);
            boolean far = Vec3.atCenterOf(pos).distanceTo(eye) > reach + 1;
            if (plan.status(i) == BuildPlan.Status.UNLOADED) {
                if (nearest < 0) nearest = i;
                if (far) break;
                continue;
            }
            byte support = support(player, i, started);
            if (support == 0) return;
            if (support != 1) continue;
            if (nearest < 0) nearest = i;
            if (far) break;
            if (System.nanoTime() - started > PLAN_NANOS) return;
            BlockState current = level.getBlockState(pos), target = plan.target(i);
            if (current.getBlock() == target.getBlock() && BlockRules.missing(current, target) == 0) {
                PlacementSolver.Click click = PlacementSolver.use(player, pos, reach);
                if (click == null) continue;
                adjust(client, player, i, click, current, layerNumber);
                return;
            }
            Item item = BlockRules.item(target);
            if (!creative && materials.total(item) == 0) {
                if (!missing.contains(item)) missing.add(item);
                continue;
            }
            PlacementSolver.Result result = PlacementSolver.find(player, pos, target, new ItemStack(item), reach, strict);
            if (result.click() == null && debug()) LOGGER.info("Can't place {} at {} yet: {}", target, pos.toShortString(), result.problem());
            if (result.click() == null) {
                if (seen == null || result.problem() == PlacementSolver.Problem.IN_THE_WAY) seen = result.problem();
                continue;
            }
            if (!creative && materials.loose(item) == 0) {
                startRestock(client, player, item, materials);
                return;
            }
            startAim(player, new Aim(i, result.click(), item, current, layerNumber, 0, 0, 0));
            return;
        }
        nextAction = System.nanoTime() + 250_000_000L;
        int left = 0;
        for (int i : layer) if (plan.status(i) == BuildPlan.Status.PENDING || plan.status(i) == BuildPlan.Status.UNLOADED) left++;
        next = nearest < 0 ? null : plan.pos(nearest);
        nextIndex = nearest;
        String where = " in layer " + layerNumber + " of " + plan.layers().size() + " (y " + layerY + ")";
        if (!missing.isEmpty()) {
            show("Out of " + names(missing) + where + ": put more in your inventory or a shulker box in it", true);
        } else if (seen == PlacementSolver.Problem.IN_THE_WAY) {
            show("Step aside: you stand where the next block goes", true);
        } else if (seen == PlacementSolver.Problem.NO_ANGLE) {
            show("No face in view sets the next block right; walk around it, or turn off Strict looks", true);
        } else {
            double distance = next == null ? 0 : Math.sqrt(Vec3.atCenterOf(next).distanceToSqr(eye));
            show("Move closer: " + left + (left == 1 ? " block" : " blocks") + " left" + where
                    + (next == null ? "" : ", nearest " + Math.round(distance) + " m away"), false);
        }
    }

    /** Whether block {@code i} can go in yet, worked out once for the blocks around it as they are: 1 yes, 2 no, 0 out of time. */
    private byte support(LocalPlayer player, int i, long started) {
        BlockPos pos = plan.pos(i);
        long stamp = BuildPlan.stamp(player.level(), pos);
        byte known = plan.support(i, stamp);
        if (known != 0) return known;
        if (System.nanoTime() - started > PLAN_NANOS) return 0;
        BlockState target = plan.target(i), current = player.level().getBlockState(pos);
        // Where the player stands nothing can go in; whether it could once they step aside is for later.
        if (PlacementSolver.inTheWay(player, pos, target)) return 1;
        boolean supported;
        if (current.getBlock() == target.getBlock()) {
            // Topping up or clicking a block already there needs nothing more.
            supported = true;
        } else {
            Item item = BlockRules.item(target);
            supported = item != null && PlacementSolver.supported(player, pos, target, new ItemStack(item));
        }
        plan.support(i, stamp, supported);
        return plan.support(i, stamp);
    }

    /** Sends the look a placement needs; the click follows in {@link #fire} once the server has turned the head. */
    private void startAim(LocalPlayer player, Aim planned) {
        PlacementSolver.Click click = planned.click();
        player.connection.send(new ServerboundMovePlayerPacket.Rot(click.yaw(), click.pitch(), player.onGround(), player.horizontalCollision));
        aim = new Aim(planned.index(), click, planned.item(), planned.before(), planned.layerNumber(), tick + AIM_TICKS,
                player.getYRot(), player.getXRot());
        next = plan.pos(planned.index());
        nextIndex = planned.index();
    }

    private void fire(Minecraft client, LocalPlayer player) {
        if (tick < aim.at()) return;
        Aim planned = aim;
        aim = null;
        if (client.level.getBlockState(plan.pos(planned.index())) != planned.before()) return;
        if (player.getYRot() != planned.yaw() || player.getXRot() != planned.pitch()) {
            // The player turned, and the server took the new look as the head's: look again first.
            startAim(player, planned);
            return;
        }
        place(client, player, planned.index(), planned.click(), planned.item(), planned.before(), planned.layerNumber());
    }

    private void place(Minecraft client, LocalPlayer player, int i, PlacementSolver.Click click, Item item, BlockState before, int layerNumber) {
        if (!holdInHand(client, player, item)) {
            show("Can't get " + name(item) + " into your hand", true);
            nextAction = System.nanoTime() + 1_000_000_000L;
            return;
        }
        click(client, player, click);
        if (debug()) {
            LOGGER.info("Placed {} at {}: {} face of {} at {}, look {} {}{}", plan.target(i), plan.pos(i).toShortString(),
                    click.hit().getDirection(), click.hit().getBlockPos().toShortString(), click.hit().getLocation(), click.yaw(), click.pitch(),
                    click.sneak() ? ", sneaking" : "");
        }
        plan.tried(i, before);
        BlockPos pos = plan.pos(i);
        next = pos;
        nextIndex = i;
        // The next block's look goes out this long before its click, so the click itself keeps to the delay.
        nextAction = System.nanoTime() + Math.max(0, delay() - AIM_TICKS * 50_000_000L);
        show("Layer " + layerNumber + " of " + plan.layers().size() + " (y " + layerY + "): placed " + plan.target(i).getBlock().getName().getString()
                + " at " + pos.getX() + " " + pos.getY() + " " + pos.getZ(), false);
        updateProgress(layerNumber);
    }

    /** A click on a placed block that sets what placing couldn't; clicks on one block follow each other quickly. */
    private void adjust(Minecraft client, LocalPlayer player, int i, PlacementSolver.Click click, BlockState before, int layerNumber) {
        int empty = emptyHotbarSlot(player.getInventory());
        if (empty >= 0) player.getInventory().setSelectedSlot(empty);
        click(client, player, click);
        plan.tried(i, before);
        next = plan.pos(i);
        nextIndex = i;
        nextAction = System.nanoTime() + ThreadLocalRandom.current().nextLong(150_000_000L, 320_000_000L);
        show("Layer " + layerNumber + " of " + plan.layers().size() + " (y " + layerY + "): setting " + before.getBlock().getName().getString(), false);
        updateProgress(layerNumber);
    }

    /** The configured wait before the next block, in nanoseconds: anywhere between the shortest and longest delay. */
    private long delay() {
        double min = settings().minDelay, max = settings().maxDelay;
        double seconds = max > min ? ThreadLocalRandom.current().nextDouble(min, max) : min;
        return (long) (seconds * 1_000_000_000L);
    }

    private void finishBuild(Minecraft client) {
        int pending = plan.count(BuildPlan.Status.PENDING);
        updateProgress(0);
        if (pending > 0) {
            finish(client, "stopped: " + pending + (pending == 1 ? " block has" : " blocks have")
                    + " nothing to hold them. Place something next to them and start again.", true);
            return;
        }
        int wrong = plan.count(BuildPlan.Status.WRONG), failed = plan.count(BuildPlan.Status.FAILED);
        int misplaced = plan.count(BuildPlan.Status.MISPLACED);
        List<String> notes = new ArrayList<>();
        if (wrong > 0) notes.add(wrong + (wrong == 1 ? " spot holds" : " spots hold") + " another block");
        if (failed > 0) notes.add(failed + (failed == 1 ? " block" : " blocks") + " the server didn't keep");
        if (misplaced > 0) notes.add(misplaced + (misplaced == 1 ? " block faces" : " blocks face") + " another way");
        int skipped = plan.count(BuildPlan.Status.SKIPPED);
        if (skipped > 0) notes.add(skipped + " skipped (other halves, liquids and blocks no item places)");
        String text = "finished " + schematic.name + ": " + plan.count(BuildPlan.Status.DONE) + " of " + plan.placeable() + " blocks in place"
                + (notes.isEmpty() ? "." : "; " + String.join(", ", notes) + ".");
        finish(client, text, false);
        client.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.PLAYER_LEVELUP, 1.0f));
    }

    private void finish(Minecraft client, String text, boolean warn) {
        if (restock != Restock.NONE) abandonRestock(client);
        aim = null;
        active = false;
        settings().enabled = false;
        next = null;
        preview = List.of();
        show(text.substring(0, 1).toUpperCase(Locale.ROOT) + text.substring(1), warn);
        message(client, text, warn ? ChatFormatting.YELLOW : ChatFormatting.GREEN);
    }

    /** Says once what the inventory and its shulker boxes lack for the whole build. */
    private void reportShortage(Minecraft client, LocalPlayer player) {
        shortageReported = true;
        if (player.hasInfiniteMaterials()) return;
        Materials materials = Materials.of(player.getInventory());
        Map<Item, Integer> short_ = shortage(materials);
        if (short_.isEmpty()) return;
        List<String> parts = new ArrayList<>();
        for (Map.Entry<Item, Integer> e : short_.entrySet()) parts.add(e.getValue() + " " + name(e.getKey()));
        message(client, "is short of " + String.join(", ", parts.subList(0, Math.min(8, parts.size())))
                + (parts.size() > 8 ? " and " + (parts.size() - 8) + " more" : "")
                + ". It builds what it can and waits when it needs them.", ChatFormatting.YELLOW);
    }

    /** Items the build still needs beyond what the inventory and its shulker boxes hold, most short first. */
    public Map<Item, Integer> shortage(Materials materials) {
        if (plan == null) return Map.of();
        Map<Item, Integer> result = new LinkedHashMap<>();
        plan.needs().entrySet().stream()
                .map(e -> Map.entry(e.getKey(), e.getValue() - materials.total(e.getKey())))
                .filter(e -> e.getValue() > 0)
                .sorted(Map.Entry.<Item, Integer>comparingByValue().reversed())
                .forEach(e -> result.put(e.getKey(), e.getValue()));
        return result;
    }

    /** Every item the whole schematic takes, most first, whether or not a build has started. */
    public Map<Item, Integer> bill() {
        Schematic current = schematic();
        if (current == null) return Map.of();
        if (current != billFor) {
            Map<Item, Integer> counts = new java.util.HashMap<>();
            BlockState air = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
            for (int i = 0; i < current.size(); i++) {
                BlockState target = current.state(i);
                if (BlockRules.kind(target) != BlockRules.Kind.PLACE) continue;
                counts.merge(BlockRules.item(target), BlockRules.itemsNeeded(air, target), Integer::sum);
            }
            Map<Item, Integer> sorted = new LinkedHashMap<>();
            counts.entrySet().stream().sorted(Map.Entry.<Item, Integer>comparingByValue().reversed())
                    .forEach(e -> sorted.put(e.getKey(), e.getValue()));
            bill = sorted;
            billFor = current;
        }
        return bill;
    }

    /** Items the rest of the build needs, most first. */
    public Map<Item, Integer> needs() {
        if (plan == null) return Map.of();
        Map<Item, Integer> result = new LinkedHashMap<>();
        plan.needs().entrySet().stream().sorted(Map.Entry.<Item, Integer>comparingByValue().reversed())
                .forEach(e -> result.put(e.getKey(), e.getValue()));
        return result;
    }

    // Hands and clicks ---------------------------------------------------------------------------------------------

    /** Selects a hotbar slot holding the item, swapping it in from the inventory, or in creative making it. */
    private boolean holdInHand(Minecraft client, LocalPlayer player, Item item) {
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < Inventory.getSelectionSize(); slot++) {
            if (inventory.getItem(slot).is(item)) {
                inventory.setSelectedSlot(slot);
                return true;
            }
        }
        int to = buildSlot(inventory);
        if (player.hasInfiniteMaterials()) {
            ItemStack stack = new ItemStack(item, item.getDefaultMaxStackSize());
            inventory.setItem(to, stack);
            inventory.setSelectedSlot(to);
            client.gameMode.handleCreativeModeItemAdd(stack, 36 + to);
            return true;
        }
        int from = Materials.slotWith(inventory, item);
        return from >= Inventory.getSelectionSize() && holdSlot(client, player, from);
    }

    /** Selects the item in this inventory slot, swapping it into the hotbar first when it isn't there. */
    private boolean holdSlot(Minecraft client, LocalPlayer player, int from) {
        Inventory inventory = player.getInventory();
        if (from < Inventory.getSelectionSize()) {
            inventory.setSelectedSlot(from);
            return true;
        }
        int to = buildSlot(inventory);
        ItemStack moving = inventory.getItem(from);
        // The inventory screen's slots 9 to 35 are the inventory's own; a swap trades one with the hotbar slot.
        client.gameMode.handleContainerInput(player.inventoryMenu.containerId, from, to, ContainerInput.SWAP, player);
        inventory.setSelectedSlot(to);
        buildSlot = to;
        return ItemStack.matches(inventory.getItem(to), moving);
    }

    /** The hotbar slot blocks are swapped into: an empty one, else one already holding a block, else the selected one. */
    private int buildSlot(Inventory inventory) {
        if (buildSlot >= 0 && buildSlot < Inventory.getSelectionSize()) {
            ItemStack held = inventory.getItem(buildSlot);
            if (held.isEmpty() || held.getItem() instanceof BlockItem && !Materials.isShulkerBox(held)) return buildSlot;
        }
        int empty = emptyHotbarSlot(inventory);
        if (empty >= 0) return buildSlot = empty;
        for (int slot = 0; slot < Inventory.getSelectionSize(); slot++) {
            ItemStack held = inventory.getItem(slot);
            if (held.getItem() instanceof BlockItem && !Materials.isShulkerBox(held)) return buildSlot = slot;
        }
        return buildSlot = inventory.getSelectedSlot();
    }

    private static int emptyHotbarSlot(Inventory inventory) {
        int selected = inventory.getSelectedSlot();
        if (inventory.getItem(selected).isEmpty()) return selected;
        for (int slot = 0; slot < Inventory.getSelectionSize(); slot++) if (inventory.getItem(slot).isEmpty()) return slot;
        return -1;
    }

    /**
     * A right click with the item in the main hand, sent with its own look and, when it needs one, a sneak around it.
     * The camera stays where the player points it: the look goes to the server only.
     */
    private static void click(Minecraft client, LocalPlayer player, PlacementSolver.Click click) {
        Input sent = player.getLastSentInput();
        boolean resneak = click.sneak() != sent.shift();
        if (resneak) player.connection.send(new ServerboundPlayerInputPacket(PlacementSolver.withShift(sent, click.sneak())));
        player.connection.send(new ServerboundMovePlayerPacket.Rot(click.yaw(), click.pitch(), player.onGround(), player.horizontalCollision));
        float yaw = player.getYRot(), pitch = player.getXRot();
        Input keys = player.input.keyPresses;
        try {
            // The client places its own copy of the block at once, and works it out from the same look and sneak.
            player.setYRot(click.yaw());
            player.setXRot(click.pitch());
            if (keys.shift() != click.sneak()) player.input.keyPresses = PlacementSolver.withShift(keys, click.sneak());
            InteractionResult result = client.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, click.hit());
            if (result instanceof InteractionResult.Success success && success.swingSource() == InteractionResult.SwingSource.CLIENT) {
                player.swing(InteractionHand.MAIN_HAND);
            }
        } finally {
            player.setYRot(yaw);
            player.setXRot(pitch);
            player.input.keyPresses = keys;
            if (resneak) player.connection.send(new ServerboundPlayerInputPacket(sent));
        }
    }

    // Shulker boxes ------------------------------------------------------------------------------------------------

    private void startRestock(Minecraft client, LocalPlayer player, Item item, Materials materials) {
        Inventory inventory = player.getInventory();
        if (!settings().useShulkers) {
            show("Out of " + name(item) + " outside shulker boxes; turn on Use shulker boxes or take some out", true);
            nextAction = System.nanoTime() + 1_000_000_000L;
            return;
        }
        if (materials.freeSlots() < 1) {
            show("Inventory full: free a slot so a shulker box can be emptied", true);
            nextAction = System.nanoTime() + 1_000_000_000L;
            return;
        }
        int slot = Materials.shulkerWith(inventory, item);
        if (slot < 0) return;
        ItemStack shulker = inventory.getItem(slot);
        BlockState boxState = ((BlockItem) shulker.getItem()).getBlock().defaultBlockState().setValue(ShulkerBoxBlock.FACING, Direction.UP);
        BlockPos spot = null;
        PlacementSolver.Click placeClick = null;
        for (BlockPos candidate : boxSpots(player)) {
            PlacementSolver.Result result = PlacementSolver.find(player, candidate, boxState, shulker, player.blockInteractionRange(), settings().strictLooks);
            if (result.click() != null) {
                spot = candidate;
                placeClick = result.click();
                break;
            }
        }
        if (spot == null) {
            show("No room beside you to open a shulker box: stand on open, flat ground", true);
            nextAction = System.nanoTime() + 1_000_000_000L;
            return;
        }
        boxesBefore = shulkerCount(inventory);
        if (!holdSlot(client, player, slot) || Materials.countInside(player.getMainHandItem(), item) == 0) {
            show("Can't get the shulker box into your hand", true);
            nextAction = System.nanoTime() + 1_000_000_000L;
            return;
        }
        click(client, player, placeClick);
        LOGGER.info("Placing a shulker box at {} for {}", spot.toShortString(), name(item));
        restock = Restock.PLACED;
        restockItem = item;
        box = spot;
        restockTicks = 0;
        show("Taking " + name(item) + " out of a shulker box", false);
    }

    /**
     * Spots beside the player's feet, outside the schematic first: a shulker box there opens upward into free space,
     * and once broken it drops where the player picks it up.
     */
    private List<BlockPos> boxSpots(LocalPlayer player) {
        ClientLevel level = (ClientLevel) player.level();
        BlockPos feet = player.blockPosition();
        AABB keepClear = plan.bounds.inflate(1);
        List<BlockPos> outside = new ArrayList<>(), inside = new ArrayList<>();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (dx == 0 && dz == 0) continue;
                BlockPos at = feet.offset(dx, 0, dz);
                BlockState state = level.getBlockState(at);
                if (!state.canBeReplaced() || !level.getFluidState(at).isEmpty()) continue;
                if (!level.getBlockState(at.above()).getCollisionShape(level, at.above()).isEmpty()) continue;
                // Never on a spot the schematic fills.
                if (plan.indexOf(at) >= 0) continue;
                (keepClear.contains(Vec3.atCenterOf(at)) ? inside : outside).add(at);
            }
        }
        Vec3 standing = player.position();
        Comparator<BlockPos> nearest = Comparator.comparingDouble(p -> Vec3.atBottomCenterOf(p).distanceToSqr(standing));
        outside.sort(nearest);
        inside.sort(nearest);
        outside.addAll(inside);
        return outside;
    }

    private void restockTick(Minecraft client, LocalPlayer player) {
        restockTicks++;
        ClientLevel level = client.level;
        BlockState at = level.getBlockState(box);
        boolean placed = at.getBlock() instanceof ShulkerBoxBlock;
        switch (restock) {
            case PLACED -> {
                // The server confirms the box before it can be opened.
                if (placed && restockTicks >= 4) {
                    PlacementSolver.Click open = PlacementSolver.use(player, box, player.blockInteractionRange());
                    if (open == null) { restockFailed(client, "the shulker box is out of reach"); return; }
                    click(client, player, new PlacementSolver.Click(open.hit(), open.yaw(), open.pitch(), false));
                    breakFace = open.hit().getDirection();
                    LOGGER.info("Opening the shulker box at {}", box.toShortString());
                    restock = Restock.OPENED;
                    restockTicks = 0;
                } else if (restockTicks > 40) {
                    restockFailed(client, "the shulker box wasn't placed");
                }
            }
            case OPENED -> {
                if (player.containerMenu instanceof ShulkerBoxMenu) {
                    // Its items arrive just after it opens.
                    if (restockTicks >= 3) {
                        restock = Restock.TAKE;
                        restockTicks = 0;
                        nextTake = 1;
                    }
                } else if (restockTicks > 40) {
                    restockFailed(client, "the shulker box didn't open");
                }
            }
            case TAKE -> {
                if (!(player.containerMenu instanceof ShulkerBoxMenu menu)) {
                    // Closed by the player; what was taken is taken.
                    startBreaking();
                    return;
                }
                if (restockTicks < nextTake) return;
                int slot = nextToTake(menu, player);
                if (slot < 0) {
                    LOGGER.info("Took what the build needs out of the shulker box");
                    player.closeContainer();
                    startBreaking();
                    return;
                }
                client.gameMode.handleContainerInput(menu.containerId, slot, 0, ContainerInput.QUICK_MOVE, player);
                nextTake = restockTicks + 2 + ThreadLocalRandom.current().nextInt(4);
            }
            case BREAK -> {
                if (!placed) {
                    LOGGER.info("Shulker box broken after {} ticks", restockTicks);
                    restock = Restock.PICKUP;
                    restockTicks = 0;
                    return;
                }
                if (client.gui.screen() != null) return;
                if (restockTicks == 1) {
                    selectTool(player.getInventory(), at);
                    float[] look = PlacementSolver.look(player.getEyePosition(), Vec3.atCenterOf(box));
                    player.connection.send(new ServerboundMovePlayerPacket.Rot(look[0], look[1], player.onGround(), player.horizontalCollision));
                }
                if (client.gameMode.continueDestroyBlock(box, breakFace)) player.swing(InteractionHand.MAIN_HAND);
                if (restockTicks > 20 * 20) {
                    client.gameMode.stopDestroyBlock();
                    restock = Restock.NONE;
                    finish(client, "stopped: couldn't break the shulker box at " + box.getX() + " " + box.getY() + " " + box.getZ()
                            + "; it still holds your items.", true);
                }
            }
            case PICKUP -> {
                if (placed) {
                    // The server kept the box after all; break it again.
                    LOGGER.info("Shulker box at {} is back; breaking it again", box.toShortString());
                    startBreaking();
                    return;
                }
                if (shulkerCount(player.getInventory()) >= boxesBefore) {
                    stopWalking(client);
                    LOGGER.info("Shulker box picked up again");
                    restock = Restock.NONE;
                    restockFailures = 0;
                    nextAction = System.nanoTime() + delay();
                    show("Took " + name(restockItem) + " out of a shulker box", false);
                    return;
                }
                // A box that rolled out of reach is walked to, a few steps at most over the ground it was placed on.
                if (restockTicks >= 10 && restockTicks < 70) walkTo(client, player, droppedBox(level, player));
                else stopWalking(client);
                if (restockTicks == 80) {
                    message(client, "pick up your shulker box near " + box.getX() + " " + box.getY() + " " + box.getZ()
                            + "; the build goes on once it's back.", ChatFormatting.YELLOW);
                }
                if (restockTicks >= 80) show("Waiting for you to pick up the shulker box near " + box.getX() + " " + box.getY() + " " + box.getZ(), true);
            }
            case NONE -> { }
        }
    }

    private void startBreaking() {
        LOGGER.info("Breaking the shulker box at {}", box.toShortString());
        restock = Restock.BREAK;
        restockTicks = 0;
    }

    /** The dropped shulker box item near where the box stood, or null. */
    private ItemEntity droppedBox(ClientLevel level, LocalPlayer player) {
        List<ItemEntity> items = level.getEntitiesOfClass(ItemEntity.class, new AABB(box).inflate(3),
                item -> Materials.isShulkerBox(item.getItem()) && Math.abs(item.getY() - player.getY()) < 1.5);
        return items.stream().min(Comparator.comparingDouble(item -> item.distanceToSqr(player))).orElse(null);
    }

    private void walkTo(Minecraft client, LocalPlayer player, ItemEntity item) {
        if (item == null) {
            stopWalking(client);
            return;
        }
        double dx = item.getX() - player.getX(), dz = item.getZ() - player.getZ();
        if (dx * dx + dz * dz < 0.16) {
            stopWalking(client);
            return;
        }
        player.setYRot((float) Math.toDegrees(Math.atan2(-dx, dz)));
        client.options.keyUp.setDown(true);
        walking = true;
    }

    private void stopWalking(Minecraft client) {
        if (!walking) return;
        client.options.keyUp.setDown(false);
        walking = false;
    }

    /** The next slot of the open box to take, the item it was opened for first; -1 when done or the inventory is full. */
    private int nextToTake(ShulkerBoxMenu menu, LocalPlayer player) {
        Inventory inventory = player.getInventory();
        Materials carried = Materials.of(inventory);
        // One slot stays free for the box itself.
        if (carried.freeSlots() <= 1) return -1;
        Map<Item, Integer> needs = plan.needs();
        for (boolean first : new boolean[] {true, false}) {
            for (int slot = 0; slot < 27; slot++) {
                ItemStack stack = menu.getSlot(slot).getItem();
                if (stack.isEmpty()) continue;
                Item item = stack.getItem();
                if (first != (item == restockItem)) continue;
                int wanted = Math.max(needs.getOrDefault(item, 0), item == restockItem ? 1 : 0);
                if (carried.loose(item) < wanted) return slot;
            }
        }
        return -1;
    }

    private static void selectTool(Inventory inventory, BlockState state) {
        int best = -1;
        float fastest = 1;
        for (int slot = 0; slot < Inventory.getSelectionSize(); slot++) {
            float speed = inventory.getItem(slot).getDestroySpeed(state);
            if (speed > fastest) {
                fastest = speed;
                best = slot;
            }
        }
        if (best < 0) best = emptyHotbarSlot(inventory);
        if (best >= 0) inventory.setSelectedSlot(best);
    }

    private static int shulkerCount(Inventory inventory) {
        int count = 0;
        for (int slot = 0; slot < Materials.SLOTS; slot++) if (Materials.isShulkerBox(inventory.getItem(slot))) count++;
        return count;
    }

    private void restockFailed(Minecraft client, String why) {
        LocalPlayer player = client.player;
        if (player != null && player.containerMenu instanceof ShulkerBoxMenu) player.closeContainer();
        if (client.level != null && client.level.getBlockState(box).getBlock() instanceof ShulkerBoxBlock) {
            // Get the box back before anything else.
            startBreaking();
            return;
        }
        restock = Restock.NONE;
        if (++restockFailures >= RESTOCK_FAILURES) {
            finish(client, "stopped: " + why + ", " + RESTOCK_FAILURES + " times.", true);
            return;
        }
        show("Couldn't empty a shulker box: " + why, true);
        nextAction = System.nanoTime() + 2_000_000_000L;
    }

    /** Stopped while fetching: close the box, stop breaking it, and say where it is if it is still there. */
    private void abandonRestock(Minecraft client) {
        stopWalking(client);
        LocalPlayer player = client.player;
        if (player != null && player.containerMenu instanceof ShulkerBoxMenu) player.closeContainer();
        if (client.gameMode != null && restock == Restock.BREAK) client.gameMode.stopDestroyBlock();
        if (client.level != null && box != null && client.level.getBlockState(box).getBlock() instanceof ShulkerBoxBlock) {
            message(client, "left your shulker box at " + box.getX() + " " + box.getY() + " " + box.getZ() + ".", ChatFormatting.YELLOW);
        }
        restock = Restock.NONE;
    }

    // What it shows ------------------------------------------------------------------------------------------------

    private void updatePreview(LocalPlayer player) {
        if (plan == null) return;
        updateProgress(progress.layer());
        if (!settings().preview) {
            preview = List.of();
            return;
        }
        int accent = config.accentColor & 0xFFFFFF;
        List<Preview> boxes = new ArrayList<>();
        boxes.add(new Preview(plan.bounds, accent, false));
        int[] layer = plan.layers().get(layerY);
        if (layer != null) {
            Vec3 at = player.position();
            int shown = 0;
            for (int i : layer) {
                if (shown >= PREVIEW_MAX) break;
                BuildPlan.Status st = plan.status(i);
                BlockPos pos = plan.pos(i);
                if (Vec3.atCenterOf(pos).distanceToSqr(at) > PREVIEW_RANGE * PREVIEW_RANGE) continue;
                int color;
                if (st == BuildPlan.Status.PENDING) {
                    Item item = BlockRules.item(plan.target(i));
                    color = i == nextIndex ? 0xFFFFFF : missing.contains(item) ? 0xFF6F6F : accent;
                } else if (st == BuildPlan.Status.WRONG || st == BuildPlan.Status.MISPLACED || st == BuildPlan.Status.FAILED) {
                    color = 0xF2B35E;
                } else {
                    continue;
                }
                boxes.add(new Preview(new AABB(pos).deflate(0.3), color, true));
                shown++;
            }
        }
        preview = List.copyOf(boxes);
    }

    private void updateProgress(int layerNumber) {
        if (plan == null) return;
        int left = plan.count(BuildPlan.Status.PENDING) + plan.count(BuildPlan.Status.UNLOADED);
        progress = new Progress(plan.placeable(), plan.count(BuildPlan.Status.DONE), left, plan.count(BuildPlan.Status.WRONG),
                plan.count(BuildPlan.Status.FAILED), plan.count(BuildPlan.Status.MISPLACED), plan.count(BuildPlan.Status.SKIPPED),
                layerNumber, plan.layers().size(), layerY);
    }

    /** The info panel's line while building: blocks left, and the way to the next one. */
    public FinderSummary summary() {
        if (!active) return null;
        Progress p = progress;
        BlockPos at = next;
        String note = p.layers() == 0 ? "" : p.layer() == 0 ? "" : "layer " + p.layer() + "/" + p.layers();
        if (statusWarn) note = status.length() > 40 ? status.substring(0, 39) + "..." : status;
        return new FinderSummary("Builder", p.left(), at == null ? null : Vec3.atCenterOf(at), config.accentColor, note, statusWarn);
    }

    private void show(String text, boolean warn) {
        status = text;
        statusWarn = warn;
    }

    private static String names(List<Item> items) {
        List<String> names = new ArrayList<>();
        for (Item item : items.subList(0, Math.min(3, items.size()))) names.add(name(item));
        return String.join(", ", names) + (items.size() > 3 ? " and " + (items.size() - 3) + " more" : "");
    }

    public static String name(Item item) { return new ItemStack(item).getHoverName().getString(); }

    private static String seconds(double value) { return String.format(Locale.ROOT, "%.2f", value); }

    private static void message(Minecraft client, String text, ChatFormatting color) {
        if (client.player == null) return;
        client.player.sendSystemMessage(Component.literal("[Donut] SchematicBuilder " + text).withStyle(color));
    }
}
