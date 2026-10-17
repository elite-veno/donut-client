package dev.donut.client.schematic;

import dev.donut.client.config.ClientConfig;
import dev.donut.client.config.SchematicSettings;
import dev.donut.client.finder.FinderSummary;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongPredicate;
import java.util.stream.Stream;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds a Litematica schematic in the world, a layer at a time from the bottom up, from what the player carries, the
 * way a player on foot would.
 *
 * <p>A layer is done before the next starts. Within it, blocks that something must hold go in once what holds them is
 * there: a wall torch after its wall, a lantern hanging from a ceiling in the layer above after that ceiling. Redstone
 * parts follow the plain blocks of their layer, pistons, observers and dispensers come next, then the layer's water,
 * and what grows in it. Each block waits a random time between the shortest and longest delay set.
 *
 * <p>Every click is a real one: the camera turns to the spot, the crosshair rests on a face the player can see, and the
 * click goes out with that look, crouching where a chest or repeater must be placed against. Where nothing is in reach
 * it walks: steps, jumps, drops and, with helper blocks, pillars up, then takes the helpers away again. A block found
 * only in a shulker box in the inventory is fetched by placing the box, swapping what the next layers need for what
 * they don't, and breaking the box to pick it up again.
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
    /** Ticks a look rests on its spot before the click, so the server has the head turned the same way. */
    private static final int REST_TICKS = 2;
    /** The most the camera turns in a tick. */
    private static final float TURN_MAX = 40;
    /** How many of the nearest blocks a walk checks a real look for at each place it considers. */
    private static final int VERIFY = 3;
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

    /** What a click is for. */
    private enum Act { PLACE, POUR, ADJUST, HELPER, REFILL }

    /**
     * A click being lined up: the camera turns to it, rests, and the click goes out once the crosshair is on the same
     * face. {@code at} is the block it changes, whose state {@code before} it must still have.
     */
    private record Aim(Act act, int index, PlacementSolver.Click click, Item item, BlockPos at, BlockState before, int layerNumber) { }

    /** A helper block the builder put down, and the block it is there for, or -1 for one climbed on. */
    private record Helper(int serves, Block block) { }

    private Aim aim;
    /** Blocks whose click the crosshair missed from where the player stands, at {@code missedAt}: tried elsewhere first. */
    private final Set<Integer> missedHere = new HashSet<>();
    private BlockPos missedAt;
    private int restingTicks;
    /** The block whose missing place to stand was last explained in the log, and when: once in ten seconds is enough. */
    private int lastExplained = -1;
    private long explainedAt;
    /** When the next click may go out at the earliest: the delay set counts from one click to the next. */
    private long nextClick;
    /** Whether the builder holds the crouch key. */
    private boolean crouchHeld;
    /** Walks the player to where blocks are in reach; it never flies. */
    private final Walker walker = new Walker();
    private int walkFailures;
    /** Places tried and found useless since the last block went in, so the next walk goes somewhere else. */
    private final Set<Long> uselessSpots = new HashSet<>();
    /** Helper blocks standing in the world, by position. */
    private final Map<Long, Helper> helpers = new HashMap<>();
    /** Where a block that nothing holds can be placed against a helper block, by the block's index. */
    private final Map<Integer, BlockPos> helperSpot = new HashMap<>();
    /** Helper blocks that couldn't be reached to take away. */
    private final List<BlockPos> helpersLeft = new ArrayList<>();
    private String helperList = "";
    private Set<Item> helperItems = Set.of();
    /** A helper block being broken, and for how long. */
    private BlockPos breaking;
    private int breakTicks;
    /** Ground dug out to stand lower, by position, with the block that was there; filled in again on the way out. */
    private final Map<Long, BlockState> dug = new LinkedHashMap<>();
    /** Dug-out ground filled in with something else, for want of the block that was there; and spots never filled. */
    private int refilledOther;
    private final List<BlockPos> dugLeft = new ArrayList<>();
    /** A pit being dug to stand in, and for how long. */
    private Pit pit;
    private int digTicks;
    /** The last pit dug, whether a block went in since, and pits that didn't help. */
    private Pit lastPit;
    private boolean placedSincePit;
    private final Set<Pit> uselessPits = new HashSet<>();
    /** Pits given up on before they were dug, by how often: a walk there can fail once and work the next time. */
    private final Map<Pit, Integer> pitTries = new HashMap<>();
    /**
     * Whether blocks that would shut in a neighbor may go in anyway, because nothing else can; and what is known of
     * which blocks would, by the blocks around them.
     */
    private boolean sealsLifted;
    private long[] sealStamp = new long[0];
    private byte[] seal = new byte[0];

    /** An item being moved into the hotbar with the inventory open: from which slot, to which, and when the steps come. */
    private int swapFrom = -1, swapTo, swapTicks, swapAt, swapClose;

    private Restock restock = Restock.NONE;
    private Item restockItem;
    private BlockPos box;
    private PlacementSolver.Click boxClick;
    private int restockTicks, nextTake, boxesBefore, restockFailures;
    /** What the layers being built need, worked out when a box opens, so taking and putting back agree. */
    private Map<Item, Integer> soon = Map.of();
    /** Whether the builder holds the forward key, walking to a dropped shulker box. */
    private boolean walking;

    /**
     * A pit dug straight down from the block at {@code x, top, z}, {@code depth} blocks deep, to stand in and look up at
     * a block from below, as a hanging lantern or an observer facing up on the ground needs.
     */
    private record Pit(int x, int top, int z, int depth) {
        BlockPos cell(int k) { return new BlockPos(x, top - k, z); }

        Vec3 feet() { return new Vec3(x + 0.5, top + 1 - depth, z + 0.5); }
    }

    /** Fetching blocks from a shulker box, a step at a time. */
    private enum Restock { NONE, PLACE, PLACED, OPENED, TAKE, BREAK, PICKUP }

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

    /** While the builder walks, clicks or breaks, the player's own clicks would get in the way and wait. */
    public static boolean holdsHands() {
        return instance != null && instance.active && (instance.restock != Restock.NONE || instance.breaking != null
                || instance.walker.active() || instance.aim != null || instance.swapFrom >= 0 || instance.pit != null);
    }

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
        Minecraft client = Minecraft.getInstance();
        settings().enabled = false;
        active = false;
        restock = Restock.NONE;
        aim = null;
        swapFrom = -1;
        walker.stop(client);
        releaseKeys(client);
        breaking = null;
        pit = null;
        helpers.clear();
        helperSpot.clear();
        helpersLeft.clear();
        dug.clear();
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
        if (player.getAbilities().flying) {
            // The builder walks; flying would only carry the player away from where it stands.
            player.getAbilities().flying = false;
            player.onUpdateAbilities();
        }
        if (!player.isAlive()) { finish(client, "stopped: you died.", true); return; }
        if (player.isSpectator()) { finish(client, "stopped: spectators can't place blocks.", true); return; }
        if (client.level != planLevel) { finish(client, "stopped: you left the world it was started in.", true); return; }
        load(false);
        if (schematic == null) { finish(client, "stopped: " + loadProblem + ".", true); return; }
        if (!planKey.equals(key())) preparePlan(client);
        plan.refresh(client.level, REFRESH_BUDGET);
        if (swapFrom >= 0) {
            swapTick(client, player);
            return;
        }
        if (restock != Restock.NONE) {
            restockTick(client, player);
            if (tick % 10 == 0) updatePreview(player);
            return;
        }
        if (client.gui.screen() != null) {
            walker.stop(client);
            show("Paused while a menu is open", false);
            return;
        }
        if (player.isUsingItem() || player.isSleeping()) { show("Waiting while you use an item", false); return; }
        if (tick % 10 == 0) updatePreview(player);
        if (!plan.swept()) { show("Reading the build site", false); return; }
        if (!shortageReported) reportShortage(client, player);
        if (breaking != null) {
            breakTick(client, player);
            return;
        }
        if (walker.active()) {
            walk(client, player);
            return;
        }
        if (pit != null) {
            digTick(client, player);
            return;
        }
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
        aim = null;
        buildSlot = -1;
        walkFailures = 0;
        uselessSpots.clear();
        missedHere.clear();
        helpers.clear();
        helperSpot.clear();
        helpersLeft.clear();
        dug.clear();
        dugLeft.clear();
        refilledOther = 0;
        pit = lastPit = null;
        uselessPits.clear();
        pitTries.clear();
        sealsLifted = false;
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
        sealStamp = new long[plan.size()];
        seal = new byte[plan.size()];
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

    /** Picks the next block and lines up its click, or walks to where one can go in, or says what it waits for. */
    private void decide(Minecraft client, LocalPlayer player) {
        ClientLevel level = client.level;
        long started = System.nanoTime();
        boolean creative = player.hasInfiniteMaterials();
        Materials materials = Materials.of(player.getInventory());
        // Every look and walk is worked out from where the player stands: in the air after a jump, they land first.
        if (!player.onGround() && !player.isInWater() && !player.onClimbable()) return;
        if (removeUsedHelper(client, player)) return;
        if (refill(client, player, false)) return;
        // The layer: the lowest with a block that can go in now; lower blocks still waiting need something above them.
        // Each layer lists its blocks in placing order, so the first block found also sets the group.
        boolean byGroup = settings().redstoneLast;
        int[] layer = null;
        int layerNumber = 0, number = 0, group = 0;
        boolean lift = sealsLifted;
        for (Map.Entry<Integer, int[]> entry : plan.layers().entrySet()) {
            number++;
            int first = firstReady(player, entry.getValue(), lift, started);
            if (first == -1 && !lift) {
                // Only blocks that would shut in a neighbor are left: one of them goes in after all.
                first = firstReady(player, entry.getValue(), true, started);
                if (first >= 0) lift = true;
            }
            if (first == -2) { show("Working out the build order", false); return; }
            if (first < 0) continue;
            layer = entry.getValue();
            group = byGroup ? BlockRules.group(plan.target(first)) : 0;
            if (entry.getKey() != layerY) sealsLifted = false;
            layerY = entry.getKey();
            layerNumber = number;
            break;
        }
        if (layer == null) {
            if (cleanUp(client, player)) return;
            finishBuild(client);
            return;
        }
        Vec3 eye = player.getEyePosition();
        List<Integer> candidates = new ArrayList<>();
        boolean dryFirst = plan.solidsLeft();
        for (int i : layer) {
            BuildPlan.Status st = plan.status(i);
            if (st != BuildPlan.Status.PENDING && st != BuildPlan.Status.UNLOADED) continue;
            // Water, lava and kelp wait until every other block of the build is in.
            if (dryFirst && plan.wet(i)) continue;
            if (byGroup && !inOrder(player, i, group, lift, started)) continue;
            candidates.add(i);
        }
        // Of the blocks in reach, those whose item is in the hotbar go first: an item is swapped in from the inventory
        // once its blocks here run out, not back and forth. Blocks out of reach follow, nearest first.
        Set<Item> atHand = new HashSet<>();
        for (int slot = 0; slot < Inventory.getSelectionSize(); slot++) atHand.add(player.getInventory().getItem(slot).getItem());
        double inReach = (player.blockInteractionRange() + 1) * (player.blockInteractionRange() + 1);
        candidates.sort(Comparator.comparingInt((Integer i) -> Vec3.atCenterOf(plan.pos(i)).distanceToSqr(eye) > inReach ? 2
                        : creative || atHand.contains(BlockRules.item(plan.target(i))) ? 0 : 1)
                .thenComparingDouble(i -> Vec3.atCenterOf(plan.pos(i)).distanceToSqr(eye)));
        if (settings().walk && wet(player.level(), player.blockPosition())) {
            // Water running past pushes the player about and spoils every look: out of it first, onto dry ground.
            nextAction = System.nanoTime() + 250_000_000L;
            if (planWalk(client, player, candidates, materials, layerNumber, lift)) return;
        }
        double reach = player.blockInteractionRange();
        missing.clear();
        PlacementSolver.Problem seen = null;
        int nearest = -1;
        boolean sealing = false;
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
            if (!lift) {
                byte seals = seals(player, i, started);
                if (seals == 0) return;
                if (seals == 1) {
                    sealing = true;
                    continue;
                }
            }
            if (missedHere.contains(i) && player.blockPosition().equals(missedAt)) {
                if (seen == null) seen = PlacementSolver.Problem.OUT_OF_REACH;
                continue;
            }
            if (System.nanoTime() - started > PLAN_NANOS) return;
            BlockState current = level.getBlockState(pos), target = plan.target(i);
            if (plan.kind(i) == BlockRules.Kind.FLUID) {
                Item bucket = BlockRules.item(target);
                if (!creative && materials.total(bucket) == 0) {
                    if (!missing.contains(bucket)) missing.add(bucket);
                    continue;
                }
                if (player.isShiftKeyDown()) {
                    stance(client, false);
                    return;
                }
                PlacementSolver.Click pour = PlacementSolver.pour(level, player, eye, pos, target.getFluidState().getType(), reach);
                if (pour == null) {
                    if (seen == null) seen = PlacementSolver.Problem.NO_VIEW;
                    continue;
                }
                if (!creative && materials.loose(bucket) == 0) {
                    startRestock(client, player, bucket, materials);
                    return;
                }
                startAim(client, player, new Aim(Act.POUR, i, pour, bucket, pos, current, layerNumber));
                return;
            }
            if (current.getBlock() == target.getBlock() && BlockRules.missing(current, target) == 0) {
                // A chest waiting for its other half needs nothing; clicking it would only open it.
                if (BlockRules.adjustClicks(current, target) == 0) continue;
                if (player.isShiftKeyDown()) {
                    stance(client, false);
                    return;
                }
                PlacementSolver.Click click = PlacementSolver.use(player, eye, pos, reach, target);
                if (click == null) continue;
                startAim(client, player, new Aim(Act.ADJUST, i, click, null, pos, current, layerNumber));
                return;
            }
            Item item = BlockRules.item(target);
            if (!creative && materials.total(item) == 0) {
                if (!missing.contains(item)) missing.add(item);
                continue;
            }
            int index = i;
            PlacementSolver.Result result = PlacementSolver.find(player, pos, target, new ItemStack(item), reach, at -> unclickable(at, index));
            if (result.click() == null && debug()) LOGGER.info("Can't place {} at {} yet: {}", target, pos.toShortString(), result.problem());
            if (result.click() == null) {
                BlockPos helper = helperSpot.get(i);
                if (result.problem() == PlacementSolver.Problem.NO_SUPPORT && helper != null) {
                    if (placeHelper(client, player, i, helper, layerNumber)) return;
                    if (seen == null) seen = PlacementSolver.Problem.OUT_OF_REACH;
                    continue;
                }
                if (seen == null || result.problem() == PlacementSolver.Problem.IN_THE_WAY) seen = result.problem();
                continue;
            }
            if (!lift && !wayOut(player, i)) {
                // It would wall the player in: other blocks first, or a walk out to place it from outside.
                sealing = true;
                continue;
            }
            if (!creative && materials.loose(item) == 0) {
                startRestock(client, player, item, materials);
                return;
            }
            startAim(client, player, new Aim(Act.PLACE, i, result.click(), item, pos, current, layerNumber));
            return;
        }
        nextAction = System.nanoTime() + 250_000_000L;
        int left = 0;
        for (int i : layer) if (plan.status(i) == BuildPlan.Status.PENDING || plan.status(i) == BuildPlan.Status.UNLOADED) left++;
        if (settings().walk && planWalk(client, player, candidates, materials, layerNumber, lift)) return;
        if (settings().walk && planPit(client, player, candidates, materials, layerNumber, lift)) return;
        if (sealing && !lift) {
            // Every block left in reach of a walk would shut in another: let them go in after all.
            sealsLifted = true;
            nextAction = System.nanoTime();
            return;
        }
        next = nearest < 0 ? null : plan.pos(nearest);
        nextIndex = nearest;
        String where = " in layer " + layerNumber + " of " + plan.layers().size() + " (y " + layerY + ")";
        if (!missing.isEmpty()) {
            show("Out of " + names(missing) + where + ": put more in your inventory or a shulker box in it", true);
        } else if (seen == PlacementSolver.Problem.IN_THE_WAY) {
            show("Step aside: you stand where the next block goes", true);
        } else if (settings().walk) {
            show("Can't find a place to stand for the " + left + (left == 1 ? " block" : " blocks") + " left" + where
                    + (helperItem(player) == null ? ": put helper blocks, such as dirt, in your inventory to climb" : ""), true);
        } else {
            double distance = next == null ? 0 : Math.sqrt(Vec3.atCenterOf(next).distanceToSqr(eye));
            show("Move closer: " + left + (left == 1 ? " block" : " blocks") + " left" + where
                    + (next == null ? "" : ", nearest " + Math.round(distance) + " m away"), false);
        }
    }

    /**
     * The first block of a layer, in its placing order, that can go in now, or an unloaded one that may need to: -1
     * when there is none, -2 when working it out has to wait for the next tick. Unless {@code lift}, blocks that would
     * shut in a neighbor are passed over.
     */
    private int firstReady(LocalPlayer player, int[] layer, boolean lift, long started) {
        boolean dryFirst = plan.solidsLeft();
        for (int i : layer) {
            if (dryFirst && plan.wet(i)) continue;
            BuildPlan.Status st = plan.status(i);
            if (st == BuildPlan.Status.PENDING) st = plan.update(player.level(), i);
            if (st == BuildPlan.Status.PENDING) {
                byte support = support(player, i, started);
                if (support == 0) return -2;
                if (support != 1) continue;
                if (!lift) {
                    byte seals = seals(player, i, started);
                    if (seals == 0) return -2;
                    if (seals == 1) continue;
                }
            } else if (st != BuildPlan.Status.UNLOADED) {
                // An unloaded block may well be missing too; the layer waits until the player is near enough to see it.
                continue;
            }
            return i;
        }
        return -1;
    }

    /**
     * Whether placing block {@code i} now would shut in a block beside it that is still to go in: one that can go in
     * now, against what is there, and no longer could with this one in place, every look at it then coming through a
     * closed side. Such a block waits for its neighbor. 1 yes, 2 no, 0 out of time.
     */
    private byte seals(LocalPlayer player, int i, long started) {
        if (plan.kind(i) != BlockRules.Kind.PLACE) return 2;
        Level level = player.level();
        BlockPos pos = plan.pos(i);
        if (!level.getBlockState(pos).canBeReplaced()) return 2;
        long stamp = BuildPlan.stamp(level, pos);
        if (sealStamp[i] == stamp) return seal[i];
        if (System.nanoTime() - started > PLAN_NANOS) return 0;
        Overlay view = null;
        byte result = 2;
        for (Direction side : Direction.values()) {
            BlockPos at = pos.relative(side);
            int j = plan.indexOf(at);
            if (j < 0 || plan.kind(j) != BlockRules.Kind.PLACE || plan.status(j) != BuildPlan.Status.PENDING) continue;
            if (!level.getBlockState(at).canBeReplaced()) continue;
            byte support = support(player, j, started);
            if (support == 0) return 0;
            if (support != 1 || helperSpot.containsKey(j)) continue;
            Item item = BlockRules.item(plan.target(j));
            if (item == null) continue;
            if (view == null) view = new Overlay(level, Map.of(pos.asLong(), plan.target(i)));
            int neighbor = j;
            if (!PlacementSolver.supported(player, view, at, plan.target(j), new ItemStack(item), a -> unclickable(a, neighbor))) {
                if (debug()) LOGGER.info("{} at {} waits: it would shut in {} at {}", plan.target(i).getBlock().getName().getString(),
                        pos.toShortString(), plan.target(j).getBlock().getName().getString(), at.toShortString());
                result = 1;
                break;
            }
        }
        sealStamp[i] = stamp;
        seal[i] = result;
        return result;
    }

    /**
     * With redstone last, whether block {@code i} may go in now: one of {@code group}, the earliest group in the layer
     * with a block that can go in now, always may. A redstone part, piston, observer or dispenser of a later group may
     * once no block of an earlier group that can go in now is left within {@link BuildPlan#NEAR} blocks of it, so a
     * finished part of the layer gets its redstone without a walk round the whole layer for each group. Water, and
     * what grows in it, waits for the whole layer.
     */
    private boolean inOrder(LocalPlayer player, int i, int group, boolean lift, long started) {
        int own = BlockRules.group(plan.target(i));
        if (own == group) return true;
        if (own < group || own > 2) return false;
        for (int j : plan.earlierNear(i)) {
            BuildPlan.Status st = plan.status(j);
            if (st == BuildPlan.Status.UNLOADED) return false;
            if (st != BuildPlan.Status.PENDING) continue;
            byte support = support(player, j, started);
            // Not worked out yet: it waits for a later decision.
            if (support == 0) return false;
            if (support != 1) continue;
            if (lift) return false;
            byte seals = seals(player, j, started);
            if (seals != 1) return false;
        }
        return true;
    }

    /** Whether water is where a player standing with the feet in {@code feet} has their feet or head. */
    private static boolean wet(Level level, BlockPos feet) {
        return !level.getFluidState(feet).isEmpty() || !level.getFluidState(feet.above()).isEmpty();
    }

    /**
     * Whether the player could still get out with block {@code i} in place: walking, jumping or climbing onto the top
     * of the layer being built, or out of the schematic. Only blocks close to the player can shut them in.
     */
    private boolean wayOut(LocalPlayer player, int i) {
        BlockPos pos = plan.pos(i);
        if (!player.getBoundingBox().inflate(2.5).intersects(new AABB(pos))) return true;
        if (plan.target(i).getCollisionShape(player.level(), pos).isEmpty()) return true;
        Overlay view = new Overlay(player.level(), Map.of(pos.asLong(), plan.target(i)));
        AABB outside = plan.bounds.inflate(1);
        Navigator.Path path = navigator(player, view, true).limit(3000).find(Navigator.standingAt(view, player),
                node -> node.feet() >= layerY + 1 - 1e-3 || !outside.contains(node.standing()) ? 1 : 0);
        if (path == null && debug()) LOGGER.info("{} at {} waits: it would shut you in", plan.target(i).getBlock().getName().getString(),
                pos.toShortString());
        return path != null;
    }

    /** Whether block {@code i} can go in yet, worked out once for the blocks around it as they are: 1 yes, 2 no, 0 out of time. */
    private byte support(LocalPlayer player, int i, long started) {
        BlockPos pos = plan.pos(i);
        long stamp = BuildPlan.stamp(player.level(), pos);
        byte known = plan.support(i, stamp);
        if (known != 0) return known;
        if (System.nanoTime() - started > PLAN_NANOS) return 0;
        BlockState target = plan.target(i), current = player.level().getBlockState(pos);
        // Worked out as for any block, wherever the player stands: the looks it tries leave the body out, and a block
        // called ready only because the player is in its spot would hold the layer up when nothing holds it yet.
        boolean supported;
        if (plan.kind(i) == BlockRules.Kind.FLUID) {
            supported = PlacementSolver.pourable(player, pos, target.getFluidState().getType());
        } else if (current.getBlock() == target.getBlock()) {
            // Topping up or clicking a block already there needs nothing more.
            supported = true;
        } else {
            Item item = BlockRules.item(target);
            supported = item != null && PlacementSolver.supported(player, pos, target, new ItemStack(item), at -> unclickable(at, i));
            helperSpot.remove(i);
            if (!supported && item != null && helperItem(player) != null) {
                // Nothing to click that gives it the right facing: a helper block put down and taken away again can.
                BlockPos helper = PlacementSolver.helperFor(player, pos, target, new ItemStack(item), cell -> helperAllowed(player, cell));
                if (helper != null) {
                    helperSpot.put(i, helper);
                    supported = true;
                }
            }
        }
        plan.support(i, stamp, supported);
        return plan.support(i, stamp);
    }

    /** Whether a placement for block {@code i} must leave this block alone: helper blocks come out again, except its own. */
    private boolean unclickable(long at, int i) {
        Helper helper = helpers.get(at);
        return helper != null && helper.serves() != i;
    }

    // Looking and clicking -----------------------------------------------------------------------------------------

    /** Crouches or stands up for the next click; the decision after next is made in that stance. */
    private void stance(Minecraft client, boolean crouch) {
        setCrouch(client, crouch);
        nextAction = System.nanoTime() + 150_000_000L;
    }

    private void setCrouch(Minecraft client, boolean crouch) {
        KeyMapping key = client.options.keyShift;
        if (client.options.toggleCrouch().get()) {
            // A toggled crouch flips on each press.
            if (key.isDown() != crouch) key.setDown(true);
        } else {
            key.setDown(crouch);
        }
        crouchHeld = crouch;
    }

    private void releaseKeys(Minecraft client) {
        if (crouchHeld) setCrouch(client, false);
        if (walking) {
            client.options.keyUp.setDown(false);
            walking = false;
        }
    }

    /** Holds what the click needs, then lines it up: the camera turns to it over the next ticks. */
    private void startAim(Minecraft client, LocalPlayer player, Aim planned) {
        if (planned.act() == Act.ADJUST) {
            // An empty hand, so the block is used rather than something placed against it.
            int empty = emptyHotbarSlot(player.getInventory());
            if (empty >= 0) player.getInventory().setSelectedSlot(empty);
        } else if (!holdInHand(client, player, planned.item())) {
            // Moving it into the hotbar first; the next decision picks this block again.
            if (swapFrom >= 0) return;
            show("Can't get " + name(planned.item()) + " into your hand", true);
            nextAction = System.nanoTime() + 1_000_000_000L;
            return;
        }
        aim = planned;
        restingTicks = 0;
        next = planned.at();
        nextIndex = planned.index();
    }

    /**
     * Turns the camera toward a look a little each tick, quickly at first and slowing as it closes in, as a hand on a
     * mouse does; true once it has rested there for a couple of ticks.
     */
    private boolean looked(LocalPlayer player, float yaw, float pitch) {
        float dyaw = Mth.wrapDegrees(yaw - player.getYRot()), dpitch = pitch - player.getXRot();
        if (Math.abs(dyaw) > 0.4f || Math.abs(dpitch) > 0.4f) {
            restingTicks = 0;
            float ease = 0.55f + ThreadLocalRandom.current().nextFloat() * 0.25f;
            player.setYRot(player.getYRot() + step(dyaw, ease));
            player.setXRot(Mth.clamp(player.getXRot() + step(dpitch, ease), -90, 90));
            return false;
        }
        player.setYRot(player.getYRot() + dyaw);
        player.setXRot(pitch);
        return ++restingTicks > REST_TICKS;
    }

    private static float step(float delta, float ease) {
        float step = delta * ease;
        float least = Math.min(Math.abs(delta), 2f);
        if (Math.abs(step) < least) step = Math.copySign(least, delta);
        return Mth.clamp(step, -TURN_MAX, TURN_MAX);
    }

    /** What the crosshair is on: the same trace the game makes for a click. */
    private static BlockHitResult crosshair(LocalPlayer player) {
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getViewVector(1.0f).scale(player.blockInteractionRange()));
        return player.level().clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
    }

    /** Lines the click up and, once the crosshair rests on the face it was meant for, clicks. */
    private void fire(Minecraft client, LocalPlayer player) {
        Aim planned = aim;
        ClientLevel level = client.level;
        boolean changed = planned.act() == Act.POUR
                ? plan.update(level, planned.index()) != BuildPlan.Status.PENDING
                : level.getBlockState(planned.at()) != planned.before();
        if (changed) {
            aim = null;
            return;
        }
        PlacementSolver.Click click = planned.click();
        if (click.crouch() != player.isShiftKeyDown()) {
            setCrouch(client, click.crouch());
            return;
        }
        // Aimed from where the eye is now, not where it was when the click was worked out: a few centimetres of slide or
        // crouch would otherwise move a look that only just clears an edge onto it.
        float[] aimed = PlacementSolver.look(player.getEyePosition(), click.hit().getLocation());
        if (!looked(player, aimed[0], aimed[1])) return;
        // The camera turned during the wait since the last click; the click itself waits for its time.
        if (System.nanoTime() - nextClick < 0) return;
        aim = null;
        BlockHitResult hit = crosshair(player);
        if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(click.hit().getBlockPos())
                || hit.getDirection() != click.hit().getDirection()) {
            // Something moved into the line, or the player did: work it out again, with this block left for later from
            // here, so a look that only just reaches its face isn't tried over and over.
            if (debug()) LOGGER.info("Crosshair on {} {} instead of {} {}", hit.getBlockPos().toShortString(), hit.getDirection(),
                    click.hit().getBlockPos().toShortString(), click.hit().getDirection());
            if (!player.blockPosition().equals(missedAt)) missedHere.clear();
            missedAt = player.blockPosition();
            missedHere.add(planned.index());
            return;
        }
        if (planned.act() != Act.ADJUST && !player.getMainHandItem().is(planned.item()) && !player.hasInfiniteMaterials()) return;
        InteractionResult result = planned.act() == Act.POUR ? client.gameMode.useItem(player, InteractionHand.MAIN_HAND)
                : client.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
        if (result instanceof InteractionResult.Success success && success.swingSource() == InteractionResult.SwingSource.CLIENT) {
            player.swing(InteractionHand.MAIN_HAND);
        }
        clicked(planned, hit);
    }

    /** What follows a click: the next wait, and what the status line says. */
    private void clicked(Aim planned, BlockHitResult hit) {
        int i = planned.index(), layerNumber = planned.layerNumber();
        uselessSpots.clear();
        walkFailures = 0;
        String layer = "Layer " + layerNumber + " of " + plan.layers().size() + " (y " + layerY + "): ";
        switch (planned.act()) {
            case REFILL -> {
                BlockState was = dug.remove(planned.at().asLong());
                if (was != null && !planned.item().equals(was.getBlock().asItem())) refilledOther++;
                paceAfterClick(quick());
                show("Filling in the ground dug out at " + planned.at().getX() + " " + planned.at().getY() + " " + planned.at().getZ(), false);
                return;
            }
            case HELPER -> {
                helpers.put(planned.at().asLong(), new Helper(i, Block.byItem(planned.item())));
                paceAfterClick(quick());
                show(layer + "a helper block to place " + plan.target(i).getBlock().getName().getString() + " against", false);
                return;
            }
            case ADJUST -> {
                plan.tried(i, planned.before());
                paceAfterClick(quick());
                show(layer + "setting " + planned.before().getBlock().getName().getString(), false);
            }
            case PLACE, POUR -> {
                placedSincePit = true;
                sealsLifted = false;
                // A block in changes what a pit beside it can do.
                BlockPos in = planned.at();
                uselessPits.removeIf(p -> Math.abs(p.x() - in.getX()) <= 3 && Math.abs(p.z() - in.getZ()) <= 3);
                pitTries.keySet().removeIf(p -> Math.abs(p.x() - in.getX()) <= 3 && Math.abs(p.z() - in.getZ()) <= 3);
                if (debug()) {
                    LOGGER.info("Placed {} at {}: {} face of {} at {}, look {} {}{}", plan.target(i), planned.at().toShortString(),
                            hit.getDirection(), hit.getBlockPos().toShortString(), hit.getLocation(), planned.click().yaw(),
                            planned.click().pitch(), planned.click().crouch() ? ", crouching" : "");
                }
                // Water flowing by keeps changing what a pour finds there, so its tries count against one state.
                plan.tried(i, planned.act() == Act.POUR ? Blocks.AIR.defaultBlockState() : planned.before());
                paceAfterClick(delay());
                BlockPos pos = planned.at();
                show(layer + "placed " + plan.target(i).getBlock().getName().getString() + " at " + pos.getX() + " " + pos.getY() + " "
                        + pos.getZ(), false);
            }
        }
        updateProgress(layerNumber);
    }

    /**
     * Paces the next click {@code wait} nanoseconds after this one. The next block is picked and the camera turns to
     * it in the meantime, after a moment to react, as a player turns while waiting.
     */
    private void paceAfterClick(long wait) {
        long now = System.nanoTime();
        nextClick = now + wait;
        nextAction = now + Math.min(wait, ThreadLocalRandom.current().nextLong(60_000_000L, 160_000_000L));
    }

    /** A short wait for clicks that follow each other: a repeater set right after placing it, a helper taken away. */
    private static long quick() { return ThreadLocalRandom.current().nextLong(150_000_000L, 320_000_000L); }

    /** The configured wait before the next block, in nanoseconds: anywhere between the shortest and longest delay. */
    private long delay() {
        double min = settings().minDelay, max = settings().maxDelay;
        double seconds = max > min ? ThreadLocalRandom.current().nextDouble(min, max) : min;
        return (long) (seconds * 1_000_000_000L);
    }

    // Helper blocks ------------------------------------------------------------------------------------------------

    /** The helper block item to use: one the settings list that the build itself doesn't need. */
    private Item helperItem(LocalPlayer player) {
        String list = settings().helperBlocks;
        if (!list.equals(helperList)) {
            Set<Item> items = new LinkedHashSet<>();
            for (String id : list.split("[\\s,]+")) {
                if (id.isEmpty()) continue;
                Identifier name = Identifier.tryParse(id.contains(":") ? id : "minecraft:" + id);
                if (name == null) continue;
                BuiltInRegistries.ITEM.getOptional(name).filter(item -> item instanceof BlockItem).ifPresent(items::add);
            }
            helperItems = items;
            helperList = list;
        }
        if (helperItems.isEmpty()) return null;
        if (player.hasInfiniteMaterials()) return helperItems.iterator().next();
        Map<Item, Integer> needs = plan == null ? Map.of() : plan.needs();
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < Materials.SLOTS; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && helperItems.contains(stack.getItem()) && !needs.containsKey(stack.getItem())) return stack.getItem();
        }
        return null;
    }

    /** Whether a helper block may go here: an empty, dry spot the schematic leaves empty, or only has water run through. */
    private boolean helperAllowed(LocalPlayer player, BlockPos cell) {
        BlockState state = player.level().getBlockState(cell);
        if (!state.canBeReplaced() || !state.getFluidState().isEmpty() || helpers.containsKey(cell.asLong())) return false;
        int i = plan.indexOf(cell);
        return i < 0 || plan.kind(i) == BlockRules.Kind.AUTO && plan.target(i).getBlock() instanceof LiquidBlock;
    }

    /** Puts down the helper block that block {@code i} is placed against; false when it can't from here. */
    private boolean placeHelper(Minecraft client, LocalPlayer player, int i, BlockPos helper, int layerNumber) {
        if (!helperAllowed(player, helper)) {
            // Something else went there since; work out the block's support again.
            helperSpot.remove(i);
            plan.support(i, 0, false);
            return false;
        }
        Item item = helperItem(player);
        if (item == null) return false;
        BlockState state = Block.byItem(item).defaultBlockState();
        PlacementSolver.Result result = PlacementSolver.find(player, helper, state, new ItemStack(item), player.blockInteractionRange(),
                helpers::containsKey);
        if (result.click() == null) return false;
        startAim(client, player, new Aim(Act.HELPER, i, result.click(), item, helper, player.level().getBlockState(helper), layerNumber));
        return true;
    }

    /** Breaks a helper block whose block is in, once it is in reach; helper blocks climbed on wait for the end. */
    private boolean removeUsedHelper(Minecraft client, LocalPlayer player) {
        double reach = player.blockInteractionRange() - 0.25;
        for (Iterator<Map.Entry<Long, Helper>> it = helpers.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Long, Helper> entry = it.next();
            BlockPos at = BlockPos.of(entry.getKey());
            if (client.level.getBlockState(at).getBlock() != entry.getValue().block()) {
                it.remove();
                continue;
            }
            int serves = entry.getValue().serves();
            if (serves < 0 || plan.update(client.level, serves) == BuildPlan.Status.PENDING) continue;
            if (inView(player, player.getEyePosition(), at, reach)) {
                startBreak(at);
                return true;
            }
        }
        return false;
    }

    /**
     * Once every block is in, takes the helper blocks away, highest first, so a pillar comes down a block at a time
     * under the player's feet. True while there is one to go to or break.
     */
    private boolean cleanUp(Minecraft client, LocalPlayer player) {
        if (refill(client, player, true)) return true;
        helpers.entrySet().removeIf(e -> client.level.getBlockState(BlockPos.of(e.getKey())).getBlock() != e.getValue().block());
        if (helpers.isEmpty()) return false;
        BlockPos top = helpers.keySet().stream().map(BlockPos::of).max(Comparator.comparingInt(BlockPos::getY)).orElseThrow();
        double reach = player.blockInteractionRange() - 0.25;
        if (inView(player, player.getEyePosition(), top, reach)) {
            startBreak(top);
            return true;
        }
        if (settings().walk) {
            Navigator.Path path = navigator(player, false).find(Navigator.standingAt(player.level(), player),
                    node -> inView(player, node.eye(), top, reach) ? 1 : 0);
            if (path != null && path.nodes().size() > 1) {
                walker.start(path.nodes());
                show("Taking the helper blocks away", false);
                return true;
            }
        }
        helpers.remove(top.asLong());
        helpersLeft.add(top);
        return true;
    }

    private void startBreak(BlockPos pos) {
        breaking = pos;
        breakTicks = 0;
        restingTicks = 0;
    }

    /** Looks at the helper block being taken away and mines it, with the best tool in the hotbar. */
    private void breakTick(Minecraft client, LocalPlayer player) {
        breakTicks++;
        BlockState state = client.level.getBlockState(breaking);
        if (state.isAir() || state.canBeReplaced()) {
            helpers.remove(breaking.asLong());
            breaking = null;
            nextAction = System.nanoTime() + quick();
            return;
        }
        if (player.isShiftKeyDown()) setCrouch(client, false);
        if (breakTicks == 1 && !holdTool(client, player, state)) {
            breakTicks = 0;
            return;
        }
        show("Taking away a helper block at " + breaking.getX() + " " + breaking.getY() + " " + breaking.getZ(), false);
        if (mine(client, player, breaking) && breakTicks <= 20 * 15) return;
        // Out of view, or not breaking: it is left for later, from somewhere else.
        client.gameMode.stopDestroyBlock();
        if (breakTicks > 20 * 15) {
            helpersLeft.add(breaking);
            helpers.remove(breaking.asLong());
        }
        breaking = null;
        nextAction = System.nanoTime() + quick();
    }

    /** Whether a face of the block at {@code pos} is in view and in reach from {@code eye}, to mine it. */
    private static boolean inView(LocalPlayer player, Vec3 eye, BlockPos pos, double reach) {
        if (new AABB(pos).distanceToSqr(eye) > reach * reach) return false;
        return PlacementSolver.use(player, eye, pos, reach + 0.5, player.level().getBlockState(pos)) != null;
    }

    /**
     * One tick of mining a block as a player does: looking at a face of it in view, then holding attack on it. True
     * while it goes on; false when no face is in view from here.
     */
    private boolean mine(Minecraft client, LocalPlayer player, BlockPos pos) {
        PlacementSolver.Click face = PlacementSolver.use(player, player.getEyePosition(), pos, player.blockInteractionRange() + 0.5,
                client.level.getBlockState(pos));
        if (face == null) return false;
        if (!looked(player, face.yaw(), face.pitch())) return true;
        // Keep the crosshair on the face while mining; a small drift of the aim would start over.
        restingTicks = REST_TICKS + 1;
        BlockHitResult hit = crosshair(player);
        if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(pos)) return true;
        if (client.gameMode.continueDestroyBlock(pos, hit.getDirection())) player.swing(InteractionHand.MAIN_HAND);
        return true;
    }

    // Walking ------------------------------------------------------------------------------------------------------

    /** The planner for walks around the build, with climbing allowed where helper blocks may go. */
    private Navigator navigator(LocalPlayer player, boolean climb) { return navigator(player, player.level(), climb); }

    private Navigator navigator(LocalPlayer player, net.minecraft.world.level.BlockGetter view, boolean climb) {
        AABB b = plan.bounds;
        AABB area = new AABB(b.minX - 12, b.minY - 6, b.minZ - 12, b.maxX + 12, b.maxY + 4, b.maxZ + 12)
                .minmax(new AABB(player.blockPosition()).inflate(6));
        Item helper = climb ? helperItem(player) : null;
        Level level = player.level();
        return new Navigator(view, area, cell -> helper != null && helperAllowed(player, cell)
                && !BlockRules.interactive(level.getBlockState(cell.below()).getBlock()), key -> {
            int i = plan.indexOf(key);
            return i >= 0 && plan.status(i) == BuildPlan.Status.PENDING;
        });
    }

    /**
     * Plans a walk to where blocks left in the layer can go in with a real look, the most of them in reach, and starts
     * it. Spots already tried without placing anything are passed over.
     */
    private boolean planWalk(Minecraft client, LocalPlayer player, List<Integer> candidates, Materials materials, int layerNumber,
                             boolean lift) {
        ClientLevel level = client.level;
        double reach = player.blockInteractionRange() - 0.2;
        boolean creative = player.hasInfiniteMaterials();
        List<Integer> near = new ArrayList<>();
        long started = System.nanoTime();
        for (int i : candidates) {
            if (plan.status(i) != BuildPlan.Status.PENDING || support(player, i, started) != 1) continue;
            if (!lift && seals(player, i, started) == 1) continue;
            Item item = BlockRules.item(plan.target(i));
            if (item == null || !creative && materials.total(item) == 0) continue;
            near.add(i);
            if (near.size() >= 48) break;
        }
        if (near.isEmpty()) return false;
        // Blocks left that a block placed from the same place could hold, by position, whether or not they can go in yet.
        Map<Long, Integer> pool = new HashMap<>();
        for (int i : candidates) {
            if (plan.status(i) != BuildPlan.Status.PENDING || plan.kind(i) != BlockRules.Kind.PLACE) continue;
            Item item = BlockRules.item(plan.target(i));
            if (item == null || !creative && materials.total(item) == 0 || level.getBlockState(plan.pos(i)).getBlock() == plan.target(i).getBlock()) continue;
            pool.put(plan.pos(i).asLong(), i);
            if (pool.size() >= 256) break;
        }
        int[] budget = {CHAIN_LOOKS};
        Navigator.Node start = Navigator.standingAt(level, player);
        Map<Long, Boolean> verified = new HashMap<>();
        int[] seen = new int[3];
        List<Navigator.Node> sample = new ArrayList<>();
        Navigator.Goal goal = node -> {
            seen[0]++;
            if (uselessSpots.contains(node.key())) return 0;
            // Waded through, never stood in: water would push the player off every look.
            if (wet(player.level(), node.feetCell())) return 0;
            Vec3 feet = node.standing(), eye = node.eye();
            AABB body = new AABB(node.x() + 0.2, node.feet(), node.z() + 0.2, node.x() + 0.8, node.feet() + 1.8, node.z() + 0.8);
            List<Integer> inReach = new ArrayList<>();
            for (int i : near) {
                AABB cell = new AABB(plan.pos(i));
                if (!cell.intersects(body) && cell.distanceToSqr(eye) <= (reach + 0.5) * (reach + 0.5)) inReach.add(i);
            }
            if (inReach.isEmpty()) return 0;
            // Places for the log: those standing on top of the layer, nearest the first block, are picked out below.
            seen[1]++;
            if (debug() && sample.size() < 400) sample.add(node);
            // Close enough by distance; whether a real look reaches them from here is checked for the nearest few.
            inReach.sort(Comparator.comparingDouble(i -> Vec3.atCenterOf(plan.pos(i)).distanceToSqr(eye)));
            int good = 0;
            List<Integer> placeable = new ArrayList<>();
            for (int i : inReach.subList(0, Math.min(VERIFY, inReach.size()))) {
                long key = node.key() * 31 + i;
                Boolean ok = verified.get(key);
                if (ok == null) {
                    ok = placeableFrom(player, feet, i, reach);
                    verified.put(key, ok);
                }
                if (ok) {
                    good++;
                    placeable.add(i);
                }
            }
            if (good > 0) {
                seen[2]++;
                good += chained(player, node, reach, placeable, pool, budget);
            }
            return good == 0 ? 0 : good * 2 + Math.min(inReach.size(), 24) * 0.25;
        };
        // Standing more than a couple of blocks over the layer sees nothing more of it.
        double ceiling = layerY + 2.5;
        Navigator.Path path = navigator(player, true).climbUpTo(ceiling).find(start, goal);
        if (path == null) {
            long now = System.nanoTime();
            if (debug() && (near.getFirst() != lastExplained || now - explainedAt > 10_000_000_000L)) {
                lastExplained = near.getFirst();
                explainedAt = now;
                LOGGER.info("No place to stand for {} blocks, such as {} at {}: {} places reached from {}, {} with one in reach, {} with a look",
                        near.size(), plan.target(near.getFirst()), plan.pos(near.getFirst()).toShortString(), seen[0], start, seen[1], seen[2]);
                int first = near.getFirst();
                Item item = BlockRules.item(plan.target(first));
                Vec3 middle = Vec3.atCenterOf(plan.pos(first));
                List<Navigator.Node> high = sample.stream().filter(n -> n.feet() >= plan.pos(first).getY() + 1)
                        .sorted(Comparator.comparingDouble(n -> n.eye().distanceToSqr(middle))).limit(5).toList();
                for (Navigator.Node node : high) {
                    for (boolean crouch : new boolean[] {false, true}) {
                        Vec3 eye = node.standing().add(0, crouch ? PlacementSolver.CROUCH_EYE : PlacementSolver.STAND_EYE, 0);
                        LOGGER.info("  from {}: {}", node, PlacementSolver.explain(player, eye, crouch, plan.pos(first), plan.target(first),
                                new ItemStack(item), reach, cell -> unclickable(cell, first)));
                    }
                }
            }
            return false;
        }
        if (path.nodes().size() == 1) {
            // A real look should reach a block from here, yet none went in: look further.
            uselessSpots.add(start.key());
            path = navigator(player, true).climbUpTo(ceiling).find(start, goal);
            if (path == null || path.nodes().size() == 1) return false;
        }
        if (path.nodes().stream().anyMatch(node -> node.move() == Navigator.Move.PILLAR)) {
            // The climb places helper blocks mid-jump, so they are in the hotbar before setting off.
            Item helper = helperItem(player);
            if (helper != null && !selectInHotbar(player, helper)) {
                holdInHand(client, player, helper);
                return true;
            }
        }
        releaseKeys(client);
        walker.start(path.nodes());
        Navigator.Node end = path.end();
        show("Layer " + layerNumber + " of " + plan.layers().size() + ": walking to " + end.x() + " " + Math.round(end.feet()) + " " + end.z(), false);
        if (debug()) LOGGER.info("Walking {} steps to {} {} {}", path.nodes().size() - 1, end.x(), end.feet(), end.z());
        return true;
    }

    /** The most blocks a place to stand is credited with for going in one after another from there. */
    private static final int CHAIN = 6;
    /** The most looks one walk's search works out for such rows of blocks; the nearest places to stand get them. */
    private static final int CHAIN_LOOKS = 400;

    /**
     * How many more blocks can go in from a place to stand once those that can now are in, each against one placed
     * before it from there: a row of glass over air, or hoppers each feeding the next, goes in from one place rather
     * than a walk along it a block at a time. {@code pool} holds the blocks left by position; {@code budget} counts down
     * the looks the whole search may still work out.
     */
    private int chained(LocalPlayer player, Navigator.Node node, double reach, List<Integer> placeable, Map<Long, Integer> pool,
                        int[] budget) {
        Map<Long, BlockState> placed = new HashMap<>();
        ArrayDeque<BlockPos> open = new ArrayDeque<>();
        for (int i : placeable) {
            if (plan.kind(i) != BlockRules.Kind.PLACE) continue;
            placed.put(plan.pos(i).asLong(), plan.target(i));
            open.add(plan.pos(i));
        }
        // The view reads the map as it grows, so each block found counts as there for the next.
        Overlay view = new Overlay(player.level(), placed);
        Vec3 feet = node.standing(), eye = node.eye();
        AABB body = new AABB(node.x() + 0.2, node.feet(), node.z() + 0.2, node.x() + 0.8, node.feet() + 1.8, node.z() + 0.8);
        int more = 0;
        while (!open.isEmpty() && more < CHAIN && budget[0] > 0) {
            BlockPos from = open.poll();
            for (Direction side : Direction.values()) {
                BlockPos at = from.relative(side);
                Integer j = pool.get(at.asLong());
                if (j == null || placed.containsKey(at.asLong())) continue;
                AABB cell = new AABB(at);
                if (cell.intersects(body) || cell.distanceToSqr(eye) > (reach + 0.5) * (reach + 0.5)) continue;
                if (budget[0]-- <= 0) break;
                int index = j;
                BlockState target = plan.target(index);
                if (PlacementSolver.fromSpot(player, view, feet, at, target, new ItemStack(BlockRules.item(target)), reach,
                        a -> unclickable(a, index)) == null) continue;
                placed.put(at.asLong(), target);
                open.add(at);
                if (++more >= CHAIN) break;
            }
        }
        return more;
    }

    /** Whether block {@code i} could go in, with a real look, from a place to stand with the feet at {@code feet}. */
    private boolean placeableFrom(LocalPlayer player, Vec3 feet, int i, double reach) {
        Level level = player.level();
        BlockPos pos = plan.pos(i);
        BlockState target = plan.target(i), current = level.getBlockState(pos);
        Vec3 eye = feet.add(0, PlacementSolver.STAND_EYE, 0);
        if (plan.kind(i) == BlockRules.Kind.FLUID) {
            return PlacementSolver.pour(level, player, eye, pos, target.getFluidState().getType(), reach) != null;
        }
        if (current.getBlock() == target.getBlock() && BlockRules.missing(current, target) == 0) {
            return PlacementSolver.use(player, eye, pos, reach, target) != null;
        }
        Item item = BlockRules.item(target);
        if (item == null) return false;
        BlockPos helper = helperSpot.get(i);
        if (helper != null && level.getBlockState(helper).canBeReplaced()) {
            BlockState helperState = Block.byItem(helperItem(player) == null ? Items.DIRT : helperItem(player)).defaultBlockState();
            return PlacementSolver.fromSpot(player, feet, helper, helperState, new ItemStack(helperState.getBlock()), reach,
                    helpers::containsKey) != null;
        }
        LongPredicate unclickable = at -> unclickable(at, i);
        return PlacementSolver.fromSpot(player, feet, pos, target, new ItemStack(item), reach, unclickable) != null;
    }

    private void walk(Minecraft client, LocalPlayer player) {
        if (player.isShiftKeyDown()) setCrouch(client, false);
        Walker.State state = walker.tick(client, player, new Walker.Climb() {
            @Override
            public boolean ready(BlockPos cell) {
                // Ground dug out is filled with what was there, when the hotbar has it.
                BlockState was = dug.get(cell.asLong());
                if (was != null && selectInHotbar(player, was.getBlock().asItem())) return true;
                Item item = helperItem(player);
                return item != null && selectInHotbar(player, item);
            }

            @Override
            public boolean placeBelow(BlockPos cell) { return climbPlace(client, player, cell); }
        });
        if (state == Walker.State.ARRIVED) {
            walkFailures = 0;
            nextAction = System.nanoTime();
        } else if (state == Walker.State.FAILED) {
            if (debug()) LOGGER.info("Walk failed at {}: {}", player.position(), walker.failure());
            if (++walkFailures >= 8) {
                finish(client, "stopped: it can't find a way to walk to the next blocks.", true);
                return;
            }
            nextAction = System.nanoTime() + 200_000_000L;
        }
        if (tick % 10 == 0) updatePreview(player);
    }

    /**
     * Puts a helper block under the jumping player, into the cell their feet just left: the walker has them looking
     * straight down, so the crosshair is on the top of the block they jumped from.
     */
    private boolean climbPlace(Minecraft client, LocalPlayer player, BlockPos cell) {
        BlockState was = dug.get(cell.asLong());
        Item item = was != null && player.getMainHandItem().is(was.getBlock().asItem()) ? was.getBlock().asItem() : helperItem(player);
        if (item == null || !player.getMainHandItem().is(item)) {
            if (debug()) LOGGER.info("Climb at {}: holding {} instead of {}", cell.toShortString(), player.getMainHandItem(), item);
            return false;
        }
        BlockHitResult hit = crosshair(player);
        if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(cell.below()) || hit.getDirection() != Direction.UP) {
            if (debug()) LOGGER.info("Climb at {}: crosshair on {} {} {} at {}, look {} {}, holding {}", cell.toShortString(), hit.getType(),
                    hit.getBlockPos().toShortString(), hit.getDirection(), hit.getLocation(), player.getYRot(), player.getXRot(),
                    player.getMainHandItem());
            return false;
        }
        InteractionResult result = client.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
        if (result instanceof InteractionResult.Success success && success.swingSource() == InteractionResult.SwingSource.CLIENT) {
            player.swing(InteractionHand.MAIN_HAND);
        }
        if (was != null) {
            // The pit is filled in again on the way out, and stays filled.
            dug.remove(cell.asLong());
            if (item != was.getBlock().asItem()) refilledOther++;
        } else {
            helpers.put(cell.asLong(), new Helper(-1, Block.byItem(item)));
        }
        return true;
    }

    // Pits ---------------------------------------------------------------------------------------------------------

    /**
     * Digs in to stand lower, when a block left can't go in with a look from any place to stand: a lantern hanging from
     * a ceiling one block over the floor, an observer facing up on the ground, are only placed looking up at them.
     * Beside such a block, outside the schematic, it looks for ground one or two blocks deep from which a look would
     * place it, walks onto it and digs straight down. Climbing out fills the pit in again with what was dug.
     */
    private boolean planPit(Minecraft client, LocalPlayer player, List<Integer> candidates, Materials materials, int layerNumber,
                            boolean lift) {
        // Without blocks to climb on, a pit two deep is a trap.
        if (helperItem(player) == null) return false;
        if (lastPit != null && !placedSincePit) uselessPits.add(lastPit);
        Level level = player.level();
        double reach = player.blockInteractionRange() - 0.2;
        boolean creative = player.hasInfiniteMaterials();
        long started = System.nanoTime();
        int tried = 0;
        for (int i : candidates) {
            if (plan.status(i) != BuildPlan.Status.PENDING || plan.kind(i) != BlockRules.Kind.PLACE || support(player, i, started) != 1) continue;
            if (!lift && seals(player, i, started) == 1) continue;
            BlockPos pos = plan.pos(i);
            BlockState target = plan.target(i);
            if (level.getBlockState(pos).getBlock() == target.getBlock()) continue;
            Item item = BlockRules.item(target);
            if (item == null || !creative && materials.total(item) == 0) continue;
            if (++tried > 6 || System.nanoTime() - started > 40_000_000L) break;
            for (int depth = 1; depth <= 2; depth++) {
                for (int[] offset : PIT_OFFSETS) {
                    Pit candidate = pitAt(level, pos.getX() + offset[0], pos.getY(), pos.getZ() + offset[1], depth);
                    if (candidate == null || uselessPits.contains(candidate)) continue;
                    Map<Long, BlockState> hollow = new HashMap<>();
                    for (int k = 0; k < depth; k++) hollow.put(candidate.cell(k).asLong(), Blocks.AIR.defaultBlockState());
                    int index = i;
                    PlacementSolver.Click click = PlacementSolver.fromSpot(player, new Overlay(level, hollow), candidate.feet(), pos, target,
                            new ItemStack(item), reach, at -> unclickable(at, index));
                    if (click == null) continue;
                    Navigator.Node start = Navigator.standingAt(level, player);
                    Navigator.Path path = navigator(player, true).find(start, node -> node.x() == candidate.x() && node.z() == candidate.z()
                            && node.y() == candidate.top() ? 1 : 0);
                    if (path == null) continue;
                    if (debug()) LOGGER.info("Digging a pit {} deep at {} {} {} for {} at {}", depth, candidate.x(), candidate.top(),
                            candidate.z(), target, pos.toShortString());
                    pit = lastPit = candidate;
                    placedSincePit = false;
                    digTicks = 0;
                    if (path.nodes().size() > 1) {
                        releaseKeys(client);
                        walker.start(path.nodes());
                    }
                    show("Layer " + layerNumber + " of " + plan.layers().size() + ": digging in at " + candidate.x() + " "
                            + candidate.top() + " " + candidate.z() + " to place " + target.getBlock().getName().getString() + " from below", false);
                    return true;
                }
            }
        }
        return false;
    }

    /** Columns beside a block to dig a pit in, nearest first. */
    private static final int[][] PIT_OFFSETS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1},
            {2, 0}, {-2, 0}, {0, 2}, {0, -2}, {2, 1}, {2, -1}, {-2, 1}, {-2, -1}, {1, 2}, {-1, 2}, {1, -2}, {-1, -2}};

    /**
     * A pit {@code depth} deep in the column at x, z, under ground that can be stood on just below {@code y}, or null:
     * the ground must be plain blocks outside the schematic, with nothing placed on it, no water or lava beside it,
     * and solid ground under the pit.
     */
    private Pit pitAt(Level level, int x, int y, int z, int depth) {
        int top = Integer.MIN_VALUE;
        for (int sy = y - 1; sy >= y - 3; sy--) {
            BlockPos at = new BlockPos(x, sy, z);
            if (fullTop(level, at) && level.getBlockState(at.above()).isAir() && level.getBlockState(at.above(2)).getCollisionShape(level, at.above(2)).isEmpty()) {
                top = sy;
                break;
            }
            if (!level.getBlockState(at).getCollisionShape(level, at).isEmpty()) return null;
        }
        if (top == Integer.MIN_VALUE) return null;
        // Nothing the schematic puts over the pit may go in while the player stands in it: the spot right above is
        // never one of its blocks, and the one above that only in a higher layer, built once the pit is filled in.
        if (plan.indexOf(new BlockPos(x, top + 1, z)) >= 0) return null;
        if (plan.indexOf(new BlockPos(x, top + 2, z)) >= 0 && top + 2 <= y) return null;
        Pit candidate = new Pit(x, top, z, depth);
        for (int k = 0; k < depth; k++) if (!diggable(level, candidate.cell(k))) return null;
        BlockPos floor = candidate.cell(depth);
        if (!fullTop(level, floor) || Navigator.hurts(level.getBlockState(floor))) return null;
        return candidate;
    }

    private static boolean fullTop(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!state.getFluidState().isEmpty()) return false;
        var shape = state.getCollisionShape(level, pos);
        return !shape.isEmpty() && shape.max(Direction.Axis.Y) == 1 && state.isFaceSturdy(level, pos, Direction.UP);
    }

    /** Ground that may be dug out for a while: a plain block, quick to mine, outside the schematic and away from it, dry. */
    private boolean diggable(Level level, BlockPos cell) {
        BlockState state = level.getBlockState(cell);
        if (state.isAir() || !state.getFluidState().isEmpty() || level.getBlockEntity(cell) != null) return false;
        float hardness = state.getDestroySpeed(level, cell);
        if (hardness < 0 || hardness > 3.5f || state.getBlock() instanceof ShulkerBoxBlock || Navigator.hurts(state)) return false;
        if (plan.indexOf(cell) >= 0 || helpers.containsKey(cell.asLong())) return false;
        for (Direction side : Direction.values()) {
            BlockPos next = cell.relative(side);
            if (!level.getFluidState(next).isEmpty()) return false;
            // A block of the schematic beside it might hang on it; the one above the pit is never there yet.
            if (side != Direction.UP && plan.indexOf(next) >= 0) return false;
        }
        return true;
    }

    /** Stands over the middle of the pit and mines the ground under the feet, a block at a time, looking down at it. */
    private void digTick(Minecraft client, LocalPlayer player) {
        ClientLevel level = client.level;
        if (++digTicks > 20 * 40) {
            abandonPit(client, "took too long");
            return;
        }
        BlockPos next = null;
        for (int k = 0; k < pit.depth(); k++) {
            BlockPos cell = pit.cell(k);
            if (!level.getBlockState(cell).getCollisionShape(level, cell).isEmpty()) {
                next = cell;
                break;
            }
        }
        if (next == null) {
            client.gameMode.stopDestroyBlock();
            if (player.onGround() && Math.abs(player.getY() - pit.feet().y) < 0.1) {
                pit = null;
                nextAction = System.nanoTime();
            } else if (digTicks > 20 * 30) {
                abandonPit(client, "didn't land in it");
            }
            return;
        }
        if (!player.onGround()) return;
        if (Math.abs(player.getY() - (next.getY() + 1)) > 0.1) {
            abandonPit(client, "isn't standing on it");
            return;
        }
        double dx = pit.x() + 0.5 - player.getX(), dz = pit.z() + 0.5 - player.getZ();
        if (dx * dx + dz * dz > 0.15 * 0.15) {
            // Over the middle, so the player drops into the pit rather than standing on its edge.
            if (dx * dx + dz * dz > 1.2 * 1.2) {
                abandonPit(client, "is too far from it");
                return;
            }
            stepToward(client, player, dx, dz);
            return;
        }
        stopWalking(client);
        if (player.isShiftKeyDown()) setCrouch(client, false);
        if (!dug.containsKey(next.asLong())) {
            // With the right tool, so the ground drops itself to fill the pit in again with.
            if (!holdTool(client, player, level.getBlockState(next))) return;
            dug.put(next.asLong(), level.getBlockState(next));
            restingTicks = 0;
        }
        show("Digging in at " + pit.x() + " " + pit.top() + " " + pit.z() + " to place a block from below", false);
        if (!mine(client, player, next)) abandonPit(client, "can't see the ground under the feet");
    }

    private void abandonPit(Minecraft client, String why) {
        if (debug()) LOGGER.info("Gave up the pit at {} {} {}: {}", pit.x(), pit.top(), pit.z(), why);
        stopWalking(client);
        if (client.gameMode != null) client.gameMode.stopDestroyBlock();
        if (pitTries.merge(pit, 1, Integer::sum) >= 3) uselessPits.add(pit);
        pit = null;
        // Not dug, so not the pit to blame if nothing goes in next.
        lastPit = null;
        nextAction = System.nanoTime() + 200_000_000L;
    }

    /** A small step toward a spot close by, turning to it as a player does. */
    private void stepToward(Minecraft client, LocalPlayer player, double dx, double dz) {
        float want = (float) Math.toDegrees(Math.atan2(-dx, dz));
        player.setYRot(player.getYRot() + Mth.clamp(Mth.wrapDegrees(want - player.getYRot()), -TURN_MAX, TURN_MAX));
        client.options.keyUp.setDown(Math.abs(Mth.wrapDegrees(want - player.getYRot())) < 30);
        walking = true;
    }

    /**
     * Fills in ground dug out that the player has left, the bottom of each pit first, with what was there if the
     * inventory has it, else helper blocks. While building it only does so from where the player stands and keeps the
     * pit just dug for the block it was dug for; once every block is in ({@code finish}) it walks to what is left. True
     * while there is one to fill or walk to.
     */
    private boolean refill(Minecraft client, LocalPlayer player, boolean finish) {
        Level level = player.level();
        dug.entrySet().removeIf(e -> !level.getBlockState(BlockPos.of(e.getKey())).canBeReplaced());
        if (dug.isEmpty()) return false;
        List<BlockPos> open = dug.keySet().stream().map(BlockPos::of).sorted(Comparator.comparingInt(BlockPos::getY)).toList();
        AABB body = player.getBoundingBox().inflate(0.1);
        for (BlockPos cell : open) {
            // Bottom first, and never under the player.
            if (dug.containsKey(cell.below().asLong())) continue;
            if (new AABB(cell.getX(), cell.getY(), cell.getZ(), cell.getX() + 1, cell.getY() + 3, cell.getZ() + 1).intersects(body)) continue;
            if (!finish && lastPit != null && !placedSincePit && cell.getX() == lastPit.x() && cell.getZ() == lastPit.z()) continue;
            BlockState was = dug.get(cell.asLong());
            Item item = was.getBlock().asItem();
            boolean creative = player.hasInfiniteMaterials();
            if (item == Items.AIR || !creative && Materials.of(player.getInventory()).total(item) == 0) item = helperItem(player);
            if (item == null) {
                if (!finish) continue;
                dug.remove(cell.asLong());
                dugLeft.add(cell);
                return true;
            }
            BlockState state = Block.byItem(item).defaultBlockState();
            PlacementSolver.Result result = PlacementSolver.find(player, cell, state, new ItemStack(item), player.blockInteractionRange(),
                    helpers::containsKey);
            if (result.click() != null) {
                if (!creative && Materials.of(player.getInventory()).loose(item) == 0) {
                    startRestock(client, player, item, Materials.of(player.getInventory()));
                    return true;
                }
                startAim(client, player, new Aim(Act.REFILL, -1, result.click(), item, cell, level.getBlockState(cell), 0));
                return true;
            }
            if (!finish) continue;
            if (settings().walk) {
                Item fill = item;
                double reach = player.blockInteractionRange() - 0.2;
                Navigator.Path path = navigator(player, true).find(Navigator.standingAt(level, player), node -> PlacementSolver.fromSpot(player,
                        node.standing(), cell, state, new ItemStack(fill), reach, helpers::containsKey) != null ? 1 : 0);
                if (path != null && path.nodes().size() > 1) {
                    releaseKeys(client);
                    walker.start(path.nodes());
                    show("Walking over to fill in the ground dug out", false);
                    return true;
                }
            }
            dug.remove(cell.asLong());
            dugLeft.add(cell);
            return true;
        }
        return false;
    }

    // Finishing ----------------------------------------------------------------------------------------------------

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
        if (refilledOther > 0) {
            notes.add(refilledOther + (refilledOther == 1 ? " block" : " blocks") + " of ground dug out filled in with "
                    + (helperItem(Minecraft.getInstance().player) == null ? "helper blocks" : name(helperItem(Minecraft.getInstance().player))));
        }
        if (!dugLeft.isEmpty()) {
            BlockPos first = dugLeft.getFirst();
            notes.add(dugLeft.size() + (dugLeft.size() == 1 ? " spot" : " spots") + " of ground dug out left open, such as at "
                    + first.getX() + " " + first.getY() + " " + first.getZ());
        }
        if (!helpersLeft.isEmpty()) {
            BlockPos first = helpersLeft.getFirst();
            notes.add(helpersLeft.size() + (helpersLeft.size() == 1 ? " helper block" : " helper blocks") + " left, such as at "
                    + first.getX() + " " + first.getY() + " " + first.getZ());
        }
        int skipped = plan.count(BuildPlan.Status.SKIPPED);
        if (skipped > 0) notes.add(skipped + " skipped (other halves, flowing water and blocks no item places)");
        String text = "finished " + schematic.name + ": " + plan.count(BuildPlan.Status.DONE) + " of " + plan.placeable() + " blocks in place"
                + (notes.isEmpty() ? "." : "; " + String.join(", ", notes) + ".");
        finish(client, text, false);
        client.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.PLAYER_LEVELUP, 1.0f));
    }

    private void finish(Minecraft client, String text, boolean warn) {
        if (restock != Restock.NONE) abandonRestock(client);
        abandonSwap(client);
        aim = null;
        walker.stop(client);
        releaseKeys(client);
        if ((breaking != null || pit != null) && client.gameMode != null) client.gameMode.stopDestroyBlock();
        breaking = null;
        pit = null;
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
            Map<Item, Integer> counts = new HashMap<>();
            BlockState air = Blocks.AIR.defaultBlockState();
            for (int i = 0; i < current.size(); i++) {
                BlockState target = current.state(i);
                BlockRules.Kind kind = BlockRules.kind(target);
                if (kind == BlockRules.Kind.FLUID) counts.merge(BlockRules.item(target), 1, Integer::sum);
                else if (kind == BlockRules.Kind.PLACE) counts.merge(BlockRules.item(target), BlockRules.itemsNeeded(air, target), Integer::sum);
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

    // Hands --------------------------------------------------------------------------------------------------------

    /**
     * Selects a hotbar slot holding the item, or in creative makes it; true when it is in hand now. An item only in
     * the inventory is moved into the hotbar first, which takes a few ticks with the inventory open: false then, with
     * the move under way.
     */
    private boolean holdInHand(Minecraft client, LocalPlayer player, Item item) {
        if (selectInHotbar(player, item)) return true;
        Inventory inventory = player.getInventory();
        if (player.hasInfiniteMaterials()) {
            int to = buildSlot(inventory);
            ItemStack stack = new ItemStack(item, item.getDefaultMaxStackSize());
            inventory.setItem(to, stack);
            inventory.setSelectedSlot(to);
            client.gameMode.handleCreativeModeItemAdd(stack, 36 + to);
            return true;
        }
        int from = Materials.slotWith(inventory, item);
        return from >= Inventory.getSelectionSize() && holdSlot(client, player, from);
    }

    /** Selects a hotbar slot holding the item, as scrolling to it does; false when the hotbar has none. */
    private static boolean selectInHotbar(LocalPlayer player, Item item) {
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < Inventory.getSelectionSize(); slot++) {
            if (inventory.getItem(slot).is(item)) {
                inventory.setSelectedSlot(slot);
                return true;
            }
        }
        return false;
    }

    /** Selects the item in this inventory slot; one outside the hotbar is moved into it first, and false returned meanwhile. */
    private boolean holdSlot(Minecraft client, LocalPlayer player, int from) {
        Inventory inventory = player.getInventory();
        if (from < Inventory.getSelectionSize()) {
            inventory.setSelectedSlot(from);
            return true;
        }
        startSwap(client, player, from, buildSlot(inventory));
        return false;
    }

    /** Opens the inventory to move an item into the hotbar, as a player does with a number key over its slot. */
    private void startSwap(Minecraft client, LocalPlayer player, int from, int to) {
        walker.stop(client);
        releaseKeys(client);
        swapFrom = from;
        swapTo = to;
        swapTicks = 0;
        swapAt = 3 + ThreadLocalRandom.current().nextInt(4);
        swapClose = swapAt + 2 + ThreadLocalRandom.current().nextInt(4);
        client.gui.setScreen(new InventoryScreen(player));
    }

    private void swapTick(Minecraft client, LocalPlayer player) {
        swapTicks++;
        if (!(client.gui.screen() instanceof InventoryScreen)) {
            // Closed by the player; the next decision looks again.
            swapFrom = -1;
            return;
        }
        if (swapTicks == swapAt) {
            // The inventory screen's slots 9 to 35 are the inventory's own; a swap trades one with the hotbar slot.
            client.gameMode.handleContainerInput(player.inventoryMenu.containerId, swapFrom, swapTo, ContainerInput.SWAP, player);
        } else if (swapTicks >= swapClose) {
            player.closeContainer();
            player.getInventory().setSelectedSlot(swapTo);
            buildSlot = swapTo;
            swapFrom = -1;
            nextAction = System.nanoTime() + quick();
        }
    }

    private void abandonSwap(Minecraft client) {
        if (swapFrom < 0) return;
        swapFrom = -1;
        if (client.player != null && client.gui.screen() instanceof InventoryScreen) client.player.closeContainer();
    }

    /**
     * The hotbar slot blocks are swapped into: an empty one, else the one whose item the layers being built need
     * least, so the blocks in use stay at hand rather than taking turns in one slot; else the selected one. Tools and
     * one stack of helper blocks keep their slots; more helper stacks, buckets and shulker boxes, fetched again when
     * needed, may go back into the inventory.
     */
    private int buildSlot(Inventory inventory) {
        int empty = emptyHotbarSlot(inventory);
        if (empty >= 0) return buildSlot = empty;
        Map<Item, Integer> soon = plan == null || Minecraft.getInstance().level == null ? Map.of() : soonNeeds(Minecraft.getInstance().level);
        Set<Item> helpersKept = new HashSet<>();
        int best = -1, bestNeed = Integer.MAX_VALUE;
        for (int slot = 0; slot < Inventory.getSelectionSize(); slot++) {
            ItemStack held = inventory.getItem(slot);
            Item item = held.getItem();
            if (!(item instanceof BlockItem) && !(item instanceof BucketItem)) continue;
            if (helperItems.contains(item) && helpersKept.add(item)) continue;
            int need = Materials.isShulkerBox(held) || helperItems.contains(item) ? 0 : soon.getOrDefault(item, 0);
            if (need < bestNeed) {
                best = slot;
                bestNeed = need;
            }
        }
        return buildSlot = best >= 0 ? best : inventory.getSelectedSlot();
    }

    private static int emptyHotbarSlot(Inventory inventory) {
        int selected = inventory.getSelectedSlot();
        if (inventory.getItem(selected).isEmpty()) return selected;
        for (int slot = 0; slot < Inventory.getSelectionSize(); slot++) if (inventory.getItem(slot).isEmpty()) return slot;
        return -1;
    }

    /**
     * Takes the best tool for a block in hand: one it drops its item for, the fastest of those, else an empty hand.
     * True once it is in hand; false while it is moved into the hotbar from the inventory, to come back to.
     */
    private boolean holdTool(Minecraft client, LocalPlayer player, BlockState state) {
        Inventory inventory = player.getInventory();
        int best = -1;
        double bestScore = toolScore(ItemStack.EMPTY, state);
        for (int slot = 0; slot < Materials.SLOTS; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) continue;
            double score = toolScore(stack, state);
            // A tool in the hotbar wins a tie, saving a trip to the inventory.
            if (score > bestScore + (best >= 0 && best < Inventory.getSelectionSize() ? 0.01 : 0)) {
                bestScore = score;
                best = slot;
            }
        }
        if (best < 0) {
            int empty = emptyHotbarSlot(inventory);
            if (empty >= 0) inventory.setSelectedSlot(empty);
            return true;
        }
        return holdSlot(client, player, best);
    }

    private static double toolScore(ItemStack stack, BlockState state) {
        boolean drops = !state.requiresCorrectToolForDrops() || stack.isCorrectToolForDrops(state);
        return (drops ? 1000 : 0) + (stack.isEmpty() ? 1 : stack.getDestroySpeed(state));
    }

    // Shulker boxes ------------------------------------------------------------------------------------------------

    private void startRestock(Minecraft client, LocalPlayer player, Item item, Materials materials) {
        Inventory inventory = player.getInventory();
        if (!settings().useShulkers) {
            show("Out of " + name(item) + " outside shulker boxes; turn on Use shulker boxes or take some out", true);
            nextAction = System.nanoTime() + 1_000_000_000L;
            return;
        }
        int slot = Materials.shulkerWith(inventory, item);
        if (slot < 0) return;
        ItemStack shulker = inventory.getItem(slot);
        BlockState boxState = ((BlockItem) shulker.getItem()).getBlock().defaultBlockState().setValue(ShulkerBoxBlock.FACING, Direction.UP);
        BlockPos spot = null;
        PlacementSolver.Click placeClick = null;
        for (BlockPos candidate : boxSpots(player, player.blockPosition(), player.position())) {
            PlacementSolver.Result result = PlacementSolver.find(player, candidate, boxState, shulker, player.blockInteractionRange(),
                    helpers::containsKey);
            // A click in the other stance does too: placing the box crouches or stands up for it first.
            if (result.click() != null && (result.problem() == null || result.problem() == PlacementSolver.Problem.STANCE)) {
                spot = candidate;
                placeClick = result.click();
                break;
            }
        }
        if (spot == null) {
            if (settings().walk) {
                // Somewhere else, where a box goes in with a real look: here none did, though there may be room.
                Navigator.Node start = Navigator.standingAt(player.level(), player);
                double reach = player.blockInteractionRange() - 0.2;
                Navigator.Path path = navigator(player, false).find(start, node -> {
                    if (node.key() == start.key()) return 0;
                    List<BlockPos> spots = boxSpots(player, node.floor().above(), node.standing());
                    for (BlockPos at : spots.subList(0, Math.min(3, spots.size()))) {
                        if (PlacementSolver.fromSpot(player, node.standing(), at, boxState, shulker, reach, helpers::containsKey) != null) return 1;
                    }
                    return 0;
                });
                if (path != null && path.nodes().size() > 1) {
                    releaseKeys(client);
                    walker.start(path.nodes());
                    show("Walking to open ground to empty a shulker box", false);
                    return;
                }
            }
            show("No room beside you to open a shulker box: stand on open, flat ground", true);
            nextAction = System.nanoTime() + 1_000_000_000L;
            return;
        }
        boxesBefore = shulkerCount(inventory);
        if (!holdSlot(client, player, slot)) {
            // Moving the box into the hotbar first.
            show("Taking " + name(item) + " out of a shulker box", false);
            return;
        }
        if (Materials.countInside(player.getMainHandItem(), item) == 0) {
            show("Can't get the shulker box into your hand", true);
            nextAction = System.nanoTime() + 1_000_000_000L;
            return;
        }
        restock = Restock.PLACE;
        restockItem = item;
        box = spot;
        boxClick = placeClick;
        restockTicks = 0;
        restingTicks = 0;
        show("Taking " + name(item) + " out of a shulker box", false);
    }

    /**
     * Spots beside the player's feet, outside the schematic first: a shulker box there opens upward into free space,
     * and once broken it drops where the player picks it up.
     */
    private List<BlockPos> boxSpots(LocalPlayer player, BlockPos feet, Vec3 standing) {
        ClientLevel level = (ClientLevel) player.level();
        AABB keepClear = plan.bounds.inflate(1);
        List<BlockPos> outside = new ArrayList<>(), inside = new ArrayList<>();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (dx == 0 && dz == 0) continue;
                BlockPos at = feet.offset(dx, 0, dz);
                BlockState state = level.getBlockState(at);
                if (!state.canBeReplaced() || !level.getFluidState(at).isEmpty()) continue;
                if (!level.getBlockState(at.above()).getCollisionShape(level, at.above()).isEmpty()) continue;
                // Something under it to place it on, and not ice: the box, once broken, would slide off out of reach.
                if (!level.getBlockState(at.below()).isFaceSturdy(level, at.below(), Direction.UP)) continue;
                if (level.getBlockState(at.below()).getBlock().getFriction() > 0.6f) continue;
                // Never on a spot the schematic fills.
                if (plan.indexOf(at) >= 0) continue;
                (keepClear.contains(Vec3.atCenterOf(at)) ? inside : outside).add(at);
            }
        }
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
            case PLACE -> {
                // Looking at where the box goes, crouching if it is placed against a chest or the like.
                if (boxClick.crouch() != player.isShiftKeyDown()) {
                    setCrouch(client, boxClick.crouch());
                    return;
                }
                if (!looked(player, boxClick.yaw(), boxClick.pitch())) {
                    if (restockTicks > 60) restockFailed(client, "it couldn't turn to where the shulker box goes");
                    return;
                }
                BlockHitResult hit = crosshair(player);
                if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(boxClick.hit().getBlockPos())
                        || !Materials.isShulkerBox(player.getMainHandItem())) {
                    restockFailed(client, "the spot for the shulker box went out of view");
                    return;
                }
                client.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
                player.swing(InteractionHand.MAIN_HAND);
                LOGGER.info("Placing a shulker box at {} for {}", box.toShortString(), name(restockItem));
                restock = Restock.PLACED;
                restockTicks = 0;
                restingTicks = 0;
            }
            case PLACED -> {
                // The server confirms the box before it can be opened; it opens standing, looking at it.
                if (!placed) {
                    if (restockTicks > 40) restockFailed(client, "the shulker box wasn't placed");
                    return;
                }
                if (player.isShiftKeyDown()) {
                    setCrouch(client, false);
                    return;
                }
                PlacementSolver.Click open = PlacementSolver.use(player, player.getEyePosition(), box, player.blockInteractionRange(), at);
                if (open == null) { restockFailed(client, "the shulker box is out of view"); return; }
                if (restockTicks < 4 || !looked(player, open.yaw(), open.pitch())) return;
                BlockHitResult hit = crosshair(player);
                if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(box)) return;
                client.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
                player.swing(InteractionHand.MAIN_HAND);
                LOGGER.info("Opening the shulker box at {}", box.toShortString());
                restock = Restock.OPENED;
                restockTicks = 0;
            }
            case OPENED -> {
                if (player.containerMenu instanceof ShulkerBoxMenu) {
                    // Its items arrive just after it opens.
                    if (restockTicks >= 3) {
                        restock = Restock.TAKE;
                        restockTicks = 0;
                        nextTake = 2;
                        soon = soonNeeds(level);
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
                int slot = nextToDeposit(menu, player);
                if (slot < 0) slot = nextToTake(menu, player);
                if (slot < 0) {
                    LOGGER.info("Took what the next layers need out of the shulker box");
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
                    client.gameMode.stopDestroyBlock();
                    restock = Restock.PICKUP;
                    restockTicks = 0;
                    return;
                }
                if (client.gui.screen() != null) return;
                if (player.isShiftKeyDown()) setCrouch(client, false);
                if (restockTicks == 1 && !holdTool(client, player, at)) {
                    restockTicks = 0;
                    return;
                }
                if (!mine(client, player, box) && restockTicks > 40) {
                    restockFailed(client, "the shulker box is out of view");
                    return;
                }
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
                // A box that rolled or slid out of reach is walked to, straight at it, for some seconds.
                if (restockTicks >= 10 && restockTicks < 240) walkTo(client, player, droppedBox(level, player));
                else stopWalking(client);
                if (restockTicks == 250) {
                    message(client, "pick up your shulker box near " + box.getX() + " " + box.getY() + " " + box.getZ()
                            + "; the build goes on once it's back.", ChatFormatting.YELLOW);
                }
                if (restockTicks >= 250) show("Waiting for you to pick up the shulker box near " + box.getX() + " " + box.getY() + " " + box.getZ(), true);
            }
            case NONE -> { }
        }
    }

    private void startBreaking() {
        LOGGER.info("Breaking the shulker box at {}", box.toShortString());
        restock = Restock.BREAK;
        restockTicks = 0;
        restingTicks = 0;
    }

    /** The dropped shulker box item near where the box stood, or null. */
    private ItemEntity droppedBox(ClientLevel level, LocalPlayer player) {
        // Within a few blocks of where it stood: a box can bounce, or slide on ice nearby.
        List<ItemEntity> items = level.getEntitiesOfClass(ItemEntity.class, new AABB(box).inflate(8),
                item -> Materials.isShulkerBox(item.getItem()) && Math.abs(item.getY() - player.getY()) < 3);
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
        float want = (float) Math.toDegrees(Math.atan2(-dx, dz));
        player.setYRot(player.getYRot() + Mth.clamp(Mth.wrapDegrees(want - player.getYRot()), -TURN_MAX, TURN_MAX));
        client.options.keyUp.setDown(true);
        walking = true;
    }

    private void stopWalking(Minecraft client) {
        if (!walking) return;
        client.options.keyUp.setDown(false);
        walking = false;
    }

    /**
     * What the layer being built, the ones below it still waiting, and the next two still take, by item: what a box is
     * worth taking from, and what can go back into it.
     */
    private Map<Item, Integer> soonNeeds(Level level) {
        Map<Item, Integer> needs = new HashMap<>();
        boolean dryFirst = plan.solidsLeft();
        for (int[] layer : plan.layers().headMap(layerY + 2, true).values()) {
            for (int i : layer) {
                BuildPlan.Status st = plan.status(i);
                if (st != BuildPlan.Status.PENDING && st != BuildPlan.Status.UNLOADED) continue;
                // Water and kelp wait for every solid block: their buckets, a slot each, stay in the box till then.
                if (dryFirst && plan.wet(i)) continue;
                Item item = BlockRules.item(plan.target(i));
                if (item == null) continue;
                int count = plan.kind(i) == BlockRules.Kind.FLUID ? 1 : BlockRules.itemsNeeded(level.getBlockState(plan.pos(i)), plan.target(i));
                needs.merge(item, count, Integer::sum);
            }
        }
        if (restockItem != null) needs.merge(restockItem, 1, Math::max);
        return needs;
    }

    /**
     * A slot of the player's inventory, in the open box's menu, to put back into the box when room is short: blocks the
     * next layers don't need, and empty buckets. -1 when there is room enough or nothing to put back.
     */
    private int nextToDeposit(ShulkerBoxMenu menu, LocalPlayer player) {
        if (Materials.of(player.getInventory()).freeSlots() >= 3) return -1;
        boolean boxHasRoom = false;
        for (int slot = 0; slot < 27; slot++) if (menu.getSlot(slot).getItem().isEmpty()) boxHasRoom = true;
        if (!boxHasRoom) return -1;
        Map<Item, Integer> needs = plan.needs();
        for (int slot = 27; slot < menu.slots.size(); slot++) {
            ItemStack stack = menu.getSlot(slot).getItem();
            if (stack.isEmpty() || Materials.isShulkerBox(stack) || helperItems.contains(stack.getItem())) continue;
            boolean material = needs.containsKey(stack.getItem()) && !soon.containsKey(stack.getItem());
            if (material || stack.is(Items.BUCKET)) return slot;
        }
        return -1;
    }

    /** The next slot of the open box to take: the item it was opened for first, then what the next layers need. */
    private int nextToTake(ShulkerBoxMenu menu, LocalPlayer player) {
        Materials carried = Materials.of(player.getInventory());
        // One slot stays free for the box itself.
        if (carried.freeSlots() <= 1) return -1;
        for (boolean first : new boolean[] {true, false}) {
            for (int slot = 0; slot < 27; slot++) {
                ItemStack stack = menu.getSlot(slot).getItem();
                if (stack.isEmpty()) continue;
                Item item = stack.getItem();
                if (first != (item == restockItem)) continue;
                if (carried.loose(item) < soon.getOrDefault(item, 0)) return slot;
            }
        }
        return -1;
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
