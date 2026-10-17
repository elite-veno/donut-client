package dev.donut.client.miner;

import dev.donut.client.config.ClientConfig;
import dev.donut.client.config.MinerSettings;
import dev.donut.client.config.SchematicSettings;
import dev.donut.client.finder.FinderSummary;
import dev.donut.client.rtp.AutoRtpController;
import dev.donut.client.schematic.PlacementSolver;
import dev.donut.client.schematic.Schematic;
import dev.donut.client.schematic.SchematicBuilder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Supplier;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Digs out the room a schematic needs, under a good chunk ChunkFinder found or where the schematic's origin is: a
 * staircase down (or up) to it from where the player stands, then the room from the top down, with room around the
 * schematic and a storage room beside it, the floor left whole to walk on.
 *
 * <p>It plays like a person: the camera turns to each block at a limited speed, easing in and out by whole mouse
 * steps, rests on a point somewhat off the face's middle, and shakes a little while the block breaks; blocks follow
 * each other after a random wait, with now and then a longer pause, and are not always taken in the same order. It
 * walks with the movement keys and only breaks a block the crosshair is on.
 *
 * <p>An area pickaxe, such as DonutSMP's Amethyst Pickaxe, is used for its 3x3 swings: the miner plans with the
 * square each swing breaks, so no swing ever takes the floor away, and checks on the first swings what the tool really
 * breaks.
 */
public final class ChunkMiner {
    private static final Logger LOGGER = LoggerFactory.getLogger("Donut/Miner");
    /** How often the blocks left are counted again, in ticks. */
    private static final int COUNT_TICKS = 20;
    private static final int FOOD_PAUSE = 6;
    /** A block taking longer than this to break, in ticks, is left. */
    private static final int SLOWEST_BLOCK = 20 * 20;
    private static ChunkMiner instance;
    private static Supplier<List<Target>> goodChunks = List::of;
    /** Switches ChunkFinder on if it is off, saying whether it had to. */
    private static java.util.function.BooleanSupplier finderOn = () -> false;
    /** Logs every action, with -Ddonut.miner.debug=true. */
    private static boolean debug() { return Boolean.getBoolean("donut.miner.debug"); }

    /** A good chunk to dig under: which one, and how high its ground is. */
    public record Target(ChunkPos chunk, int surfaceY) { }

    private final ClientConfig config;
    private final Runnable save;
    private final Random random = new Random();

    private boolean active;
    private ClientLevel level;
    private Target target;
    /** Good chunks found unfit for the room, not tried again. */
    private final Set<ChunkPos> passedOver = new HashSet<>();
    private Room room;
    private Excavator dig;
    private String schematicName = "";
    private long tick;

    private enum Doing { NONE, AIM, BREAK, WALK, FILL, TOWER, WAIT }

    private Doing doing = Doing.NONE;
    private Swings.Swing swing;
    private int breakTicks, breakLimit, offTarget, reaims;
    private float aimYaw, aimPitch, shakeYaw, shakePitch;
    /** This turn's speed, a little different each time. */
    private double turnFactor = 1;
    private int turnTicks;
    private final List<Vec3> waypoints = new ArrayList<>();
    private int walkTicks, stuckTicks, walkFailures;
    private BlockPos walkTarget;
    private double closest;
    private float walkPitch;
    private boolean pressing;
    private BlockPos fillPos, towerFeet;
    private int towerTicks;
    private boolean towerJumped, towerPlaced;
    private double towerStartY;
    private PlacementSolver.Click fillClick;
    private int fillTries;
    private long waitUntil;
    /** A glance away during a longer pause, and where the camera was before it. */
    private float glanceYaw, glancePitch;
    private boolean glancing;
    /** The look the server has: the one sent in this tick's movement packet, before this tick's turn. */
    private float sentYaw, sentPitch;

    /** What the area pickaxe broke on a swing, checked a few ticks after the block hit went. */
    private record Check(BlockPos target, Direction face, String tool, int radius, Set<BlockPos> wereDig, long at) { }

    private Check check;
    private Set<BlockPos> before;
    /** Tools by {@link Kit#key}: the radius of the square they turned out to break, 0 for one block. */
    private final Map<String, Integer> learned = new HashMap<>();
    private final Map<String, Integer> areaMisses = new HashMap<>();

    private float lastHealth = -1;
    private String pausedFor = "";
    private long playerGoneAt;

    private volatile String status = "Idle";
    private volatile boolean statusWarn;
    private volatile int left = -1;
    private volatile BlockPos next;
    private volatile List<SchematicBuilder.Preview> preview = List.of();

    public ChunkMiner(ClientConfig config, Runnable save) {
        this.config = config;
        this.save = save;
        instance = this;
    }

    public static ChunkMiner getInstance() { return instance; }

    /** Where good chunks come from: ChunkFinder's, best first, and how to switch ChunkFinder on, saying whether it was off. */
    public static void goodChunks(Supplier<List<Target>> source, java.util.function.BooleanSupplier switchOn) {
        goodChunks = source == null ? List::of : source;
        finderOn = switchOn == null ? () -> false : switchOn;
    }

    /** Whether a dig is under way, not just waiting for a good chunk; Auto RTP waits for it. */
    public static boolean busy() { return instance != null && instance.active && instance.dig != null; }

    public MinerSettings settings() { return config.miner; }

    public boolean isActive() { return active; }

    public String status() { return status; }

    public boolean statusWarn() { return statusWarn; }

    public Room room() { return room; }

    /** The good chunk being dug under, or null. */
    public Target target() { return target; }

    public Excavator excavator() { return dig; }

    /** Blocks of the room left to dig, or -1 before it is known. */
    public int left() { return left; }

    public List<SchematicBuilder.Preview> preview() { return preview; }

    public void setEnabled(boolean enabled) {
        if (enabled == settings().enabled) return;
        settings().enabled = enabled;
        if (!enabled && active) finish(Minecraft.getInstance(), "stopped.", false);
    }

    /** Called on disconnect: the world is gone. */
    public void reset() {
        Minecraft client = Minecraft.getInstance();
        if (pressing) releaseKeys(client);
        settings().enabled = false;
        active = false;
        dig = null;
        room = null;
        target = null;
        doing = Doing.NONE;
        check = null;
        preview = List.of();
        next = null;
        left = -1;
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
        sentYaw = player.getYRot();
        sentPitch = player.getXRot();
        if (!active && !begin(client, player)) return;
        if (!player.isAlive()) { finish(client, "stopped: you died.", true); return; }
        if (player.isSpectator()) { finish(client, "stopped: spectators can't dig.", true); return; }
        if (client.level != level) { finish(client, "stopped: you left the world it was started in.", true); return; }
        float health = player.getHealth();
        boolean hurt = lastHealth >= 0 && health < lastHealth;
        lastHealth = health;
        if (dig == null) {
            locate(client, player);
            return;
        }
        if (hurt && settings().stopWhenHurt) { finish(client, "stopped: you took damage.", true); return; }
        if (player.isInLava()) { finish(client, "stopped: you are in lava.", true); return; }
        LevelSite site = new LevelSite(client.level, player);
        if (tick % COUNT_TICKS == 0) {
            left = dig.left(site);
            updatePreview();
        }
        if (check != null && tick >= check.at()) learn(client, site);
        String pause = pauseReason(client, player);
        if (!pause.isEmpty()) {
            if (!pause.equals(pausedFor)) {
                if (doing == Doing.BREAK) client.gameMode.stopDestroyBlock();
                if (pressing) releaseKeys(client);
                if (doing != Doing.WAIT) doing = Doing.NONE;
                if (pause.startsWith("Paused: ")) {
                    message(client, "paused: " + pause.substring(8) + ".", ChatFormatting.YELLOW);
                    client.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_BELL, 1.0f));
                }
            }
            pausedFor = pause;
            show(pause, pause.startsWith("Paused: "));
            return;
        }
        pausedFor = "";
        act(client, player, site);
    }

    /** Why it waits right now, or empty: a menu open, the builder at work, another player near, hunger. */
    private String pauseReason(Minecraft client, LocalPlayer player) {
        if (client.gui.screen() != null) return "Paused while a menu is open";
        if (SchematicBuilder.busy()) return "Paused while SchematicBuilder builds";
        int range = settings().pauseForPlayers;
        if (range > 0) {
            for (AbstractClientPlayer other : client.level.players()) {
                if (other == player || other.isSpectator() || other.distanceTo(player) > range) continue;
                playerGoneAt = tick;
                return "Paused: " + other.getName().getString() + " is " + Math.round(other.distanceTo(player)) + " blocks away";
            }
            // Someone who just went out of sight may come straight back.
            if (pausedFor.startsWith("Paused: ") && tick - playerGoneAt < 100) return pausedFor;
        }
        if (!player.hasInfiniteMaterials() && player.getFoodData().getFoodLevel() <= FOOD_PAUSE) {
            return "Paused: you are hungry; eat something";
        }
        return "";
    }

    // Starting -----------------------------------------------------------------------------------------------------

    private boolean begin(Minecraft client, LocalPlayer player) {
        MinerSettings s = settings();
        if (SchematicBuilder.busy()) {
            s.enabled = false;
            message(client, "can't start while SchematicBuilder builds.", ChatFormatting.YELLOW);
            return false;
        }
        if (config.rtp.enabled && !s.goodChunks) {
            s.enabled = false;
            message(client, "can't start while Auto RTP runs; stop it first.", ChatFormatting.YELLOW);
            return false;
        }
        SchematicBuilder builder = SchematicBuilder.getInstance();
        Schematic schematic = builder == null ? null : builder.schematic();
        if (schematic == null) {
            s.enabled = false;
            String why = builder == null ? "no schematic builder" : builder.loadProblem();
            message(client, "can't start: pick a schematic in SchematicBuilder first (" + why + ").", ChatFormatting.YELLOW);
            return false;
        }
        Excavator.Tools tools = tools(player);
        if (!tools.any()) {
            s.enabled = false;
            message(client, "can't start: " + noToolReason() + ".", ChatFormatting.YELLOW);
            return false;
        }
        active = true;
        level = client.level;
        lastHealth = player.getHealth();
        dig = null;
        room = null;
        target = null;
        doing = Doing.NONE;
        walkFailures = 0;
        left = -1;
        schematicName = schematic.name;
        if (s.goodChunks) {
            if (finderOn.getAsBoolean()) message(client, "turned ChunkFinder on to look for a good chunk.", ChatFormatting.GRAY);
            show("Waiting for ChunkFinder to find a good chunk", false);
            message(client, "waits for ChunkFinder's next good chunk, then digs a room for " + schematic.name + " under it at y "
                    + s.floorY + ".", ChatFormatting.GRAY);
            return true;
        }
        SchematicSettings place = config.schematic;
        if (!place.originSet) {
            BlockPos at = player.blockPosition();
            place.originSet = true;
            place.originX = at.getX();
            place.originY = at.getY();
            place.originZ = at.getZ();
            save.run();
        }
        return plan(client, player, schematic, new BlockPos(place.originX, place.originY, place.originZ));
    }

    /**
     * Waits for a good chunk, then puts the schematic in the middle of it at the floor height and plans the dig. A chunk
     * whose room would be in the deep dark or reach its ground is passed over for the next.
     */
    private void locate(Minecraft client, LocalPlayer player) {
        Schematic schematic = SchematicBuilder.getInstance() == null ? null : SchematicBuilder.getInstance().schematic();
        if (schematic == null) {
            finish(client, "stopped: the schematic can't be read any more.", true);
            return;
        }
        SchematicSettings place = config.schematic;
        Box at0 = placed(schematic, BlockPos.ZERO, place);
        MinerSettings s = settings();
        Target found = null;
        BlockPos origin = null;
        for (Target candidate : goodChunks.get()) {
            if (passedOver.contains(candidate.chunk())) continue;
            int x = candidate.chunk().getMiddleBlockX(), z = candidate.chunk().getMiddleBlockZ();
            BlockPos at = new BlockPos(x - (int) Math.floor(at0.centerX()), s.floorY - at0.minY(), z - (int) Math.floor(at0.centerZ()));
            Room trial = Room.of(placed(schematic, at, place), s.sideRoom, s.headroom, null, 0, 0);
            String why = trial.topY + 3 >= candidate.surfaceY() ? "its room would reach the ground at y " + candidate.surfaceY()
                    : deepDark(client.level, trial);
            if (why.isEmpty()) {
                // An underground lake or lava pool in the room would leave a lot undug.
                int fluids = fluids(new LevelSite(client.level, player), trial.bounds);
                if (fluids > trial.volume() / 50) why = "its room would hold " + fluids + " blocks of water or lava";
            }
            if (!why.isEmpty()) {
                passedOver.add(candidate.chunk());
                message(client, "passes over the good chunk at " + x + " " + z + ": " + why, ChatFormatting.YELLOW);
                continue;
            }
            found = candidate;
            origin = at;
            break;
        }
        if (found == null) {
            show("Waiting for ChunkFinder to find a good chunk", false);
            return;
        }
        int x = found.chunk().getMiddleBlockX(), z = found.chunk().getMiddleBlockZ();
        target = found;
        place.originSet = true;
        place.originX = origin.getX();
        place.originY = origin.getY();
        place.originZ = origin.getZ();
        save.run();
        if (config.rtp.enabled && AutoRtpController.getInstance() != null) {
            AutoRtpController.getInstance().setEnabled(false);
            message(client, "turned Auto RTP off to dig the good chunk.", ChatFormatting.GRAY);
        }
        message(client, "found a good chunk at " + x + " " + z + " (ground at y " + found.surfaceY() + ").", ChatFormatting.GREEN);
        plan(client, player, schematic, origin);
    }

    /** The schematic's whole box, air included, placed at {@code origin} as the builder places it. */
    private static Box placed(Schematic schematic, BlockPos origin, SchematicSettings place) {
        var mirror = SchematicBuilder.mirror(place.mirror);
        var rotation = SchematicBuilder.rotation(place.rotation);
        BlockPos a = Schematic.place(schematic.min, origin, mirror, rotation), b = Schematic.place(schematic.max, origin, mirror, rotation);
        return Box.of(a, b);
    }

    private boolean plan(Minecraft client, LocalPlayer player, Schematic schematic, BlockPos origin) {
        MinerSettings s = settings();
        Box box = placed(schematic, origin, config.schematic);
        BlockPos feet = feet(player);
        Room plain = Room.of(box, s.sideRoom, s.headroom, null, 0, 0);
        Direction storage = switch (s.storageSide) {
            case 1 -> Direction.NORTH;
            case 2 -> Direction.EAST;
            case 3 -> Direction.SOUTH;
            case 4 -> Direction.WEST;
            // Beside where the staircase comes in, as a base's entrance hall; south when starting inside it.
            case 5 -> plain.column(feet.getX(), feet.getZ()) ? Direction.SOUTH : Room.facing(plain.main, feet);
            default -> null;
        };
        room = Room.of(box, s.sideRoom, s.headroom, storage, s.storageDepth, s.storageHeight);
        String deepDark = deepDark(client.level, room);
        if (!deepDark.isEmpty()) {
            finish(client, "stopped: " + deepDark + ".", true);
            return false;
        }
        Excavator.Tools tools = tools(player);
        if (!tools.any()) {
            finish(client, "stopped: " + noToolReason() + ".", true);
            return false;
        }
        LevelSite site = new LevelSite(client.level, player);
        dig = new Excavator(room, feet, tools.area() != null, site);
        left = dig.left(site);
        int lava = 0, water = 0;
        Box b = room.bounds;
        for (int x = b.minX(); x <= b.maxX(); x++) {
            for (int z = b.minZ(); z <= b.maxZ(); z++) {
                for (int y = b.minY(); y <= b.maxY(); y++) {
                    int f = site.flags(x, y, z);
                    if ((f & Site.LAVA) != 0) lava++;
                    else if ((f & Site.FLUID) != 0) water++;
                }
            }
        }
        Box m = room.main;
        String tool = tools.area() != null ? "the area pickaxe" : "a pickaxe";
        int steps = dig.approach().size() - 1;
        message(client, "digs a " + m.sizeX() + " x " + m.sizeY() + " x " + m.sizeZ() + " room for " + schematic.name
                + (room.storage == null ? "" : " with a " + room.storage.sizeX() + " x " + room.storage.sizeY() + " x "
                + room.storage.sizeZ() + " storage room on its " + room.storageSide.getName() + " side")
                + ", floor at y " + room.floorY + ", " + (left >= 0 ? left + " blocks" : "") + (steps > 0 ? ", after a " + steps
                + "-step staircase from you" : "") + ", with " + tool + ".", ChatFormatting.GRAY);
        if (lava > 0 || water > 0) {
            message(client, "the room holds " + (lava > 0 ? lava + " lava" : "") + (lava > 0 && water > 0 ? " and " : "")
                    + (water > 0 ? water + " water" : "") + " blocks; it keeps clear of them.", ChatFormatting.YELLOW);
        }
        updatePreview();
        LOGGER.info("Digging {} for {}, from {}", room.bounds, schematic.name, feet.toShortString());
        return true;
    }

    /** Water and lava blocks in a box, as far as it is loaded. */
    private static int fluids(Site site, Box b) {
        int fluids = 0;
        for (int x = b.minX(); x <= b.maxX(); x++) {
            for (int z = b.minZ(); z <= b.maxZ(); z++) {
                for (int y = b.minY(); y <= b.maxY(); y++) if ((site.flags(x, y, z) & Site.FLUID) != 0) fluids++;
            }
        }
        return fluids;
    }

    /** Why the room can't go here, or empty: in the deep dark, digging sets off shriekers, which call the Warden. */
    private static String deepDark(ClientLevel level, Room room) {
        Box b = room.bounds;
        int[] xs = {b.minX(), (b.minX() + b.maxX()) / 2, b.maxX()}, zs = {b.minZ(), (b.minZ() + b.maxZ()) / 2, b.maxZ()};
        for (int x : xs) {
            for (int z : zs) {
                for (int y : new int[] {b.minY(), b.maxY()}) {
                    if (level.getBiome(new BlockPos(x, y, z)).is(Biomes.DEEP_DARK)) {
                        return "the room would be in the deep dark at " + x + " " + y + " " + z + ", where digging calls the Warden; set the floor higher";
                    }
                }
            }
        }
        return "";
    }

    // Acting -------------------------------------------------------------------------------------------------------

    private void act(Minecraft client, LocalPlayer player, LevelSite site) {
        switch (doing) {
            case AIM -> aimTick(client, player, site);
            case BREAK -> breakTick(client, player, site);
            case WALK -> walkTick(client, player);
            case FILL -> fillTick(client, player, site);
            case TOWER -> towerTick(client, player, site);
            case WAIT -> {
                if (tick >= waitUntil) {
                    doing = Doing.NONE;
                    glancing = false;
                } else {
                    idle(client, player);
                }
            }
            case NONE -> { }
        }
        if (doing == Doing.NONE) decide(client, player, site);
    }

    private void decide(Minecraft client, LocalPlayer player, LevelSite site) {
        Excavator.Tools tools = tools(player);
        Excavator.Action action = dig.next(site, reach(player), tools);
        if (debug()) LOGGER.info("Miner: {}", action);
        switch (action) {
            case Excavator.Dig d -> startSwing(client, player, d.swing());
            case Excavator.Walk w -> startWalk(w.cells());
            case Excavator.Fill f -> startFill(client, player, f.pos());
            case Excavator.Tower t -> startTower(client, player, t.feet());
            case Excavator.Wait w -> {
                show(Character.toUpperCase(w.why().charAt(0)) + w.why().substring(1), false);
                waitTicks(10);
            }
            case Excavator.Done d -> done(client, d.summary());
            case Excavator.Fail f -> finish(client, "stopped: " + (tools.any() ? f.why() : noToolReason()) + ".", true);
        }
    }

    private Swings.Reach reach(LocalPlayer player) {
        MinerSettings s = settings();
        return new Swings.Reach(player.getEyePosition(), player.getBoundingBox(), feet(player).getY(), player.blockInteractionRange(),
                s.aimSpread, s.varyOrder, random, player.getYRot(), player.getXRot());
    }

    /**
     * The block the player's feet are in, counting a slab, path or snow layer underfoot as a whole block, and the top half
     * of a fence or wall stood on as part of the block below.
     */
    private static BlockPos feet(LocalPlayer player) {
        BlockPos feet = BlockPos.containing(player.getX(), Math.floor(player.getY() + 0.5), player.getZ());
        var level = player.level();
        BlockPos under = feet.below();
        if (level.getBlockState(under).getCollisionShape(level, under).isEmpty()) {
            BlockPos lower = under.below();
            var shape = level.getBlockState(lower).getCollisionShape(level, lower);
            if (!shape.isEmpty() && shape.max(net.minecraft.core.Direction.Axis.Y) > 1.0) return under;
        }
        return feet;
    }

    // Swinging -----------------------------------------------------------------------------------------------------

    private void startSwing(Minecraft client, LocalPlayer player, Swings.Swing planned) {
        BlockState state = client.level.getBlockState(planned.target());
        int slot = toolSlot(player.getInventory(), planned.area(), state);
        if (slot < 0 || !holdSlot(client, player, slot)) {
            finish(client, "stopped: can't get a tool into your hand.", true);
            return;
        }
        swing = planned;
        aimYaw = planned.yaw();
        aimPitch = planned.pitch();
        turnFactor = 0.75 + random.nextDouble() * 0.4;
        turnTicks = 0;
        offTarget = 0;
        reaims = 0;
        doing = Doing.AIM;
        next = planned.target();
        String where = planned.target().getX() + " " + planned.target().getY() + " " + planned.target().getZ();
        show(phaseText() + ": " + state.getBlock().getName().getString() + " at " + where, false);
    }

    private void aimTick(Minecraft client, LocalPlayer player, LevelSite site) {
        if (!site.is(swing.target(), Site.DIG)) {
            // Gone already, taken by the swing before.
            doing = Doing.NONE;
            return;
        }
        boolean there = turn(client, player, aimYaw, aimPitch, 1);
        if (there) {
            doing = Doing.BREAK;
            breakTicks = 0;
            shakeYaw = shakePitch = 0;
            // What stands around the block before the swing, to see afterwards what the tool really took.
            before = new HashSet<>();
            for (BlockPos cell : ToolShape.area(2).cells(swing.target(), swing.face())) {
                if (!cell.equals(swing.target()) && site.is(cell, Site.DIG)) before.add(cell);
            }
            BlockState state = client.level.getBlockState(swing.target());
            float progress = state.getDestroyProgress(player, client.level, swing.target());
            int expected = progress <= 0 ? Integer.MAX_VALUE : (int) Math.ceil(1 / progress);
            breakLimit = expected * 2 + 40;
            if (expected > SLOWEST_BLOCK) {
                // Obsidian with an iron pickaxe and the like: it would take far too long.
                dig.skip(swing.target());
                if (debug()) LOGGER.info("Leaving {} at {}: {} ticks to break", state, swing.target().toShortString(), expected);
                doing = Doing.NONE;
            }
        } else if (turnTicks > 80) {
            dig.missed(swing.target());
            doing = Doing.NONE;
        }
    }

    private void breakTick(Minecraft client, LocalPlayer player, LevelSite site) {
        BlockPos pos = swing.target();
        if (!site.is(pos, Site.DIG)) {
            broken(client, player, site);
            return;
        }
        // The server judges the swing by the look it has, the one sent this tick.
        BlockHitResult hit = ray(client, player, sentYaw, sentPitch);
        boolean on = hit != null && hit.getBlockPos().equals(pos) && (!swing.area() || hit.getDirection() == swing.face());
        if (!on) {
            if (++offTarget > 6) {
                client.gameMode.stopDestroyBlock();
                if (++reaims > 3) {
                    // The look keeps missing it: try again from scratch, from another angle.
                    dig.missed(pos);
                    waitTicks(3);
                    return;
                }
                doing = Doing.AIM;
                turnTicks = 0;
            } else {
                turn(client, player, aimYaw + shakeYaw, aimPitch + shakePitch, 1);
            }
            return;
        }
        offTarget = 0;
        Direction face = hit.getDirection();
        if (client.gameMode.continueDestroyBlock(pos, face)) {
            client.level.addBreakingBlockEffect(pos, face);
            player.swing(InteractionHand.MAIN_HAND);
        }
        shake(client, player);
        if (++breakTicks > breakLimit) {
            client.gameMode.stopDestroyBlock();
            dig.missed(pos);
            if (debug()) LOGGER.info("Gave up on {} after {} ticks", pos.toShortString(), breakTicks);
            waitTicks(5);
        }
    }

    /** The block went: remember what the area pickaxe took, and wait before the next. */
    private void broken(Minecraft client, LocalPlayer player, LevelSite site) {
        BlockPos pos = swing.target();
        dig.broke(pos);
        ItemStack held = player.getMainHandItem();
        // Breaks around the block hit come with it or a moment later; look a few ticks on.
        if (Kit.isPickaxe(held) && before != null) check = new Check(pos, swing.face(), Kit.key(held), radius(held), before, tick + 6);
        before = null;
        waitTicks(delayTicks());
    }

    /** Compares what broke around the block hit with what the tool was thought to break. */
    private void learn(Minecraft client, LevelSite site) {
        Check c = check;
        check = null;
        int near = 0, nearDig = 0, far = 0;
        for (BlockPos cell : c.wereDig()) {
            boolean ring = Math.abs(cell.getX() - c.target().getX()) > 1 || Math.abs(cell.getY() - c.target().getY()) > 1
                    || Math.abs(cell.getZ() - c.target().getZ()) > 1;
            boolean gone = site.is(cell, Site.CLEAR);
            if (ring) {
                if (gone) far++;
            } else {
                nearDig++;
                if (gone) near++;
            }
        }
        // Too little stood around the block to tell.
        if (nearDig < 3) return;
        String name = c.tool().substring(c.tool().indexOf('|') + 1);
        boolean square = near >= Math.max(2, (nearDig + 1) / 2);
        if (c.radius() > 0 && near == 0) {
            if (areaMisses.merge(c.tool(), 1, Integer::sum) >= 2) {
                learned.put(c.tool(), 0);
                areaMisses.remove(c.tool());
                message(client, name + " breaks one block at a time here; it is used as a normal pickaxe.", ChatFormatting.YELLOW);
            }
        } else if (square) {
            int seen = far >= 3 ? 2 : 1;
            if (seen != c.radius()) {
                message(client, name + " breaks " + (2 * seen + 1) + "x" + (2 * seen + 1) + "; its swings are planned that way now.",
                        ChatFormatting.GRAY);
            }
            learned.put(c.tool(), seen);
            areaMisses.remove(c.tool());
        }
    }

    /** The hand's small drift while a block breaks, kept on the block. */
    private void shake(Minecraft client, LocalPlayer player) {
        double limit = settings().handShake;
        if (limit <= 0 || random.nextInt(3) != 0) return;
        float yaw = (float) Math.clamp(shakeYaw + random.nextGaussian() * limit * 0.3, -limit, limit);
        float pitch = (float) Math.clamp(shakePitch + random.nextGaussian() * limit * 0.3, -limit, limit);
        BlockHitResult hit = ray(client, player, aimYaw + yaw, aimPitch + pitch);
        if (hit == null || !hit.getBlockPos().equals(swing.target()) || swing.area() && hit.getDirection() != swing.face()) return;
        shakeYaw = yaw;
        shakePitch = pitch;
        double step = mouseStep(client);
        player.setYRot((float) (player.getYRot() + quantize(Mth.wrapDegrees(aimYaw + yaw - player.getYRot()), step)));
        player.setXRot(Mth.clamp((float) (player.getXRot() + quantize(aimPitch + pitch - player.getXRot(), step)), -90, 90));
    }

    private BlockHitResult ray(Minecraft client, LocalPlayer player, float yaw, float pitch) {
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(Vec3.directionFromRotation(pitch, yaw).scale(player.blockInteractionRange()));
        BlockHitResult hit = client.level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        return hit.getType() == HitResult.Type.BLOCK ? hit : null;
    }

    // Turning ------------------------------------------------------------------------------------------------------

    /**
     * Turns the camera a step toward a look, easing in at the start of a turn and out near its end, by whole mouse
     * steps, with a little wobble; true once it is there.
     */
    private boolean turn(Minecraft client, LocalPlayer player, float yaw, float pitch, double scale) {
        double dy = Mth.wrapDegrees(yaw - player.getYRot()), dp = pitch - player.getXRot();
        double distance = Math.hypot(dy, dp);
        double mouse = mouseStep(client);
        if (distance <= Math.max(0.35, mouse * 1.5)) return true;
        turnTicks++;
        double fastest = settings().turnSpeed * turnFactor * scale;
        double easeIn = Math.min(1, 0.3 + turnTicks * 0.2);
        double easeOut = Math.max(0.5, distance * (0.32 + random.nextDouble() * 0.16));
        double step = Math.min(fastest * easeIn, easeOut);
        double k = step / distance;
        double wobble = Math.min(0.5, step * 0.05);
        double my = dy * k + (random.nextDouble() * 2 - 1) * wobble, mp = dp * k + (random.nextDouble() * 2 - 1) * wobble;
        player.setYRot((float) (player.getYRot() + quantize(my, mouse)));
        player.setXRot(Mth.clamp((float) (player.getXRot() + quantize(mp, mouse)), -90, 90));
        return Math.hypot(Mth.wrapDegrees(yaw - player.getYRot()), pitch - player.getXRot()) <= Math.max(0.35, mouse * 1.5);
    }

    /** How far one mouse count turns the camera with the player's sensitivity, as Minecraft's mouse handler works it out. */
    private static double mouseStep(Minecraft client) {
        double f = client.options.sensitivity().get() * 0.6 + 0.2;
        return f * f * f * 8.0 * 0.15;
    }

    private static double quantize(double degrees, double step) {
        return step <= 0 ? degrees : Math.round(degrees / step) * step;
    }

    /** While waiting: now and then the eyes wander a little, and come back. */
    private void idle(Minecraft client, LocalPlayer player) {
        if (glancing) {
            if (turn(client, player, glanceYaw, glancePitch, 0.5) && waitUntil - tick > 25 && random.nextInt(30) == 0) {
                glanceYaw = player.getYRot() + (float) (random.nextGaussian() * 12);
                glancePitch = Mth.clamp(player.getXRot() + (float) (random.nextGaussian() * 6), -60, 70);
            }
        }
    }

    // Walking ------------------------------------------------------------------------------------------------------

    private void startWalk(List<BlockPos> cells) {
        waypoints.clear();
        for (BlockPos cell : cells) {
            double jx = (random.nextDouble() - 0.5) * 0.24, jz = (random.nextDouble() - 0.5) * 0.24;
            waypoints.add(new Vec3(cell.getX() + 0.5 + jx, cell.getY(), cell.getZ() + 0.5 + jz));
        }
        walkPitch = 6 + random.nextFloat() * 18;
        turnFactor = 0.7 + random.nextDouble() * 0.35;
        turnTicks = 0;
        walkTicks = 0;
        stuckTicks = 0;
        closest = Double.MAX_VALUE;
        doing = Doing.WALK;
        BlockPos end = cells.getLast();
        walkTarget = end;
        next = end;
        show(phaseText() + ": walking to " + end.getX() + " " + end.getY() + " " + end.getZ(), false);
    }

    private void walkTick(Minecraft client, LocalPlayer player) {
        if (waypoints.isEmpty()) {
            releaseKeys(client);
            walkFailures = 0;
            waitTicks(2 + random.nextInt(4));
            return;
        }
        walkTicks++;
        Vec3 point = waypoints.getFirst();
        double dx = point.x - player.getX(), dz = point.z - player.getZ();
        double distance = Math.hypot(dx, dz);
        boolean last = waypoints.size() == 1;
        boolean level = Math.abs(player.getY() - point.y) < 0.55;
        if (distance < (last ? 0.24 : 0.42) && level && player.onGround()) {
            waypoints.removeFirst();
            closest = Double.MAX_VALUE;
            stuckTicks = 0;
            return;
        }
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = walkPitch + (point.y < player.getY() - 0.5 ? 18 : point.y > player.getY() + 0.5 ? -8 : 0);
        turn(client, player, yaw, pitch, 0.9);
        double off = Math.abs(Mth.wrapDegrees(yaw - player.getYRot()));
        // Near the end, it lets go of the key and drifts in, as a person stopping does.
        boolean forward = off < 40 && !(last && distance < 0.3);
        client.options.keyUp.setDown(forward);
        boolean climb = point.y > player.getY() + 0.5 && distance < 1.3 && player.onGround();
        client.options.keyJump.setDown(climb || stuckTicks == 25 && player.onGround());
        pressing = true;
        if (distance < closest - 0.04) {
            closest = distance;
            stuckTicks = 0;
        } else if (++stuckTicks > 70) {
            releaseKeys(client);
            BlockPos at = feet(player);
            // Somewhere it can't get to after all, such as past a fence post or a block it took for air: try another place.
            if (walkTarget != null) dig.stuck(walkTarget);
            if (++walkFailures >= 8) {
                finish(client, "stopped: stuck at " + at.getX() + " " + at.getY() + " " + at.getZ() + ".", true);
                return;
            }
            doing = Doing.NONE;
        }
    }

    private void releaseKeys(Minecraft client) {
        client.options.keyUp.setDown(false);
        client.options.keyJump.setDown(false);
        pressing = false;
    }

    // Filling gaps -------------------------------------------------------------------------------------------------

    private void startFill(Minecraft client, LocalPlayer player, BlockPos pos) {
        String where = pos.getX() + " " + pos.getY() + " " + pos.getZ();
        if (!settings().fillGaps) {
            finish(client, "stopped: the next step at " + where + " has nothing under it; turn on Fill gaps, or fill it.", true);
            return;
        }
        if (!pos.equals(fillPos)) fillTries = 0;
        if (++fillTries > 3) {
            finish(client, "stopped: couldn't fill the gap at " + where + ".", true);
            return;
        }
        int slot = Kit.junkSlot(player.getInventory());
        if (slot < 0 || !holdSlot(client, player, slot)) {
            finish(client, "stopped: no cobblestone, dirt or other plain block to fill the gap at " + where + ".", true);
            return;
        }
        ItemStack stack = player.getMainHandItem();
        if (!(stack.getItem() instanceof BlockItem item)) {
            finish(client, "stopped: can't get a block into your hand.", true);
            return;
        }
        BlockState state = item.getBlock().defaultBlockState();
        PlacementSolver.Result result = PlacementSolver.find(player, pos, state, stack, player.blockInteractionRange(), clicked -> false);
        // A click that needs a crouch is one against a chest or the like: not worth it for a filler block.
        if (result.click() == null || result.click().crouch()) {
            finish(client, "stopped: can't reach the gap at " + where + " to fill it.", true);
            return;
        }
        fillPos = pos.immutable();
        fillClick = result.click();
        aimYaw = fillClick.yaw();
        aimPitch = fillClick.pitch();
        turnFactor = 0.8 + random.nextDouble() * 0.3;
        turnTicks = 0;
        doing = Doing.FILL;
        next = pos;
        show(phaseText() + ": filling the gap at " + where, false);
    }

    private void fillTick(Minecraft client, LocalPlayer player, LevelSite site) {
        if (site.is(fillPos, Site.SOLID)) {
            waitTicks(delayTicks());
            return;
        }
        if (!turn(client, player, aimYaw, aimPitch, 1)) return;
        // Click once the server has the look too.
        if (Math.abs(Mth.wrapDegrees(sentYaw - player.getYRot())) > 0.01 || Math.abs(sentPitch - player.getXRot()) > 0.01) return;
        InteractionResult result = client.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, fillClick.hit());
        if (result instanceof InteractionResult.Success success && success.swingSource() == InteractionResult.SwingSource.CLIENT) {
            player.swing(InteractionHand.MAIN_HAND);
        }
        waitTicks(4 + random.nextInt(4));
    }

    // Towering -----------------------------------------------------------------------------------------------------

    /** Back up a hole the player fell down: look down, jump, and put a block where the feet were. */
    private void startTower(Minecraft client, LocalPlayer player, BlockPos feet) {
        int slot = Kit.junkSlot(player.getInventory());
        if (slot < 0 || !holdSlot(client, player, slot)) {
            BlockPos at = feet;
            finish(client, "stopped: no cobblestone, dirt or other plain block to climb back up from " + at.getX() + " " + at.getY() + " "
                    + at.getZ() + ".", true);
            return;
        }
        towerFeet = feet.immutable();
        towerTicks = 0;
        towerJumped = false;
        towerPlaced = false;
        aimYaw = player.getYRot() + (float) (random.nextGaussian() * 4);
        aimPitch = 82 + random.nextFloat() * 7;
        turnFactor = 0.8 + random.nextDouble() * 0.3;
        turnTicks = 0;
        doing = Doing.TOWER;
        next = feet;
        show(phaseText() + " from y " + feet.getY(), false);
    }

    private void towerTick(Minecraft client, LocalPlayer player, LevelSite site) {
        if (++towerTicks > 80) {
            releaseKeys(client);
            dig.stuck(towerFeet);
            doing = Doing.NONE;
            return;
        }
        if (!towerJumped) {
            if (!turn(client, player, aimYaw, aimPitch, 1) || !player.onGround()) return;
            towerStartY = player.getY();
            client.options.keyJump.setDown(true);
            pressing = true;
            towerJumped = true;
            return;
        }
        client.options.keyJump.setDown(false);
        if (towerPlaced) {
            if (player.onGround()) {
                releaseKeys(client);
                waitTicks(delayTicks());
            }
            return;
        }
        // High enough that the block fits under the feet, looking down at the block it goes on.
        if (player.getY() < towerStartY + 1.05 || !site.is(towerFeet, Site.CLEAR)) {
            if (player.onGround() && towerTicks > 20) towerJumped = false;
            return;
        }
        Vec3 eye = player.getEyePosition();
        Vec3 look = Vec3.directionFromRotation(sentPitch, sentYaw);
        if (look.y > -0.5) return;
        double t = (towerFeet.getY() - eye.y) / look.y;
        Vec3 point = eye.add(look.scale(t));
        if (Math.floor(point.x) != towerFeet.getX() || Math.floor(point.z) != towerFeet.getZ()) {
            // The look misses the block's top: centre it and try on the next tick.
            aimYaw = player.getYRot();
            aimPitch = 89;
            turn(client, player, aimYaw, aimPitch, 1);
            return;
        }
        BlockHitResult hit = new BlockHitResult(point, Direction.UP, towerFeet.below(), false);
        InteractionResult result = client.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
        if (result instanceof InteractionResult.Success success && success.swingSource() == InteractionResult.SwingSource.CLIENT) {
            player.swing(InteractionHand.MAIN_HAND);
        }
        towerPlaced = true;
    }

    // Tools --------------------------------------------------------------------------------------------------------

    /** The radius of the square a tool breaks: what it was seen to do, or else what its name says. */
    public int radius(ItemStack stack) {
        Integer seen = learned.get(Kit.key(stack));
        if (seen != null) return seen;
        return Kit.saysArea(stack) ? 1 : 0;
    }

    /** The tools at hand that aren't worn down, as the settings allow them. */
    private Excavator.Tools tools(LocalPlayer player) {
        MinerSettings s = settings();
        Inventory inventory = player.getInventory();
        int area = 0;
        boolean single = false;
        for (int slot = 0; slot < Kit.SLOTS; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!Kit.isPickaxe(stack) || Kit.usesLeft(stack) <= s.keepDurability) continue;
            int r = radius(stack);
            if (r > 0) area = Math.max(area, r);
            else single = true;
        }
        if (s.pickaxe == 1) single = false;
        if (s.pickaxe == 2) area = 0;
        return new Excavator.Tools(area > 0 ? ToolShape.area(area) : null, single);
    }

    private String noToolReason() {
        return switch (settings().pickaxe) {
            case 1 -> "no Amethyst Pickaxe with more than " + settings().keepDurability + " uses left";
            case 2 -> "no normal pickaxe with more than " + settings().keepDurability + " uses left";
            default -> "no pickaxe with more than " + settings().keepDurability + " uses left";
        };
    }

    /** The slot of the tool for a swing: the area pickaxe for area swings, else the fastest other tool for the block. */
    private int toolSlot(Inventory inventory, boolean area, BlockState state) {
        int best = -1, bestRadius = 0;
        float fastest = 0;
        for (int slot = 0; slot < Kit.SLOTS; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty() || stack.isDamageableItem() && Kit.usesLeft(stack) <= settings().keepDurability) continue;
            int r = Kit.isPickaxe(stack) ? radius(stack) : 0;
            if (area ? r <= 0 : r > 0) continue;
            float speed = stack.getDestroySpeed(state) + (slot < Inventory.getSelectionSize() ? 0.01f : 0);
            if (!area && !Kit.isPickaxe(stack) && speed <= 1.01f) continue;
            if (r > bestRadius || r == bestRadius && speed > fastest) {
                best = slot;
                bestRadius = r;
                fastest = speed;
            }
        }
        return best;
    }

    /** Selects the item in this inventory slot, swapping it into the hotbar first when it isn't there. */
    private static boolean holdSlot(Minecraft client, LocalPlayer player, int from) {
        Inventory inventory = player.getInventory();
        if (from < Inventory.getSelectionSize()) {
            inventory.setSelectedSlot(from);
            return true;
        }
        int to = inventory.getSelectedSlot();
        for (int slot = 0; slot < Inventory.getSelectionSize(); slot++) {
            ItemStack held = inventory.getItem(slot);
            if (held.isEmpty()) { to = slot; break; }
            // A slot holding plain blocks is a fine place to swap a tool into.
            if (Kit.isJunk(held)) to = slot;
        }
        ItemStack moving = inventory.getItem(from);
        // The inventory screen's slots 9 to 35 are the inventory's own; a swap trades one with the hotbar slot.
        client.gameMode.handleContainerInput(player.inventoryMenu.containerId, from, to, ContainerInput.SWAP, player);
        inventory.setSelectedSlot(to);
        return ItemStack.matches(inventory.getItem(to), moving);
    }

    // Waiting ------------------------------------------------------------------------------------------------------

    private void waitTicks(int ticks) {
        doing = Doing.WAIT;
        waitUntil = tick + Math.max(1, ticks);
    }

    /** The wait after a block: a random time between the shortest and longest delay, and now and then a longer pause. */
    private int delayTicks() {
        MinerSettings s = settings();
        double seconds = s.maxDelay > s.minDelay ? s.minDelay + random.nextDouble() * (s.maxDelay - s.minDelay) : s.minDelay;
        if (random.nextDouble() < s.pauseChance) {
            seconds += s.pauseMax > s.pauseMin ? s.pauseMin + random.nextDouble() * (s.pauseMax - s.pauseMin) : s.pauseMin;
            LocalPlayer player = Minecraft.getInstance().player;
            if (player != null) {
                glancing = true;
                glanceYaw = player.getYRot() + (float) (random.nextGaussian() * 25);
                glancePitch = Mth.clamp(player.getXRot() + (float) (random.nextGaussian() * 10), -60, 70);
                turnFactor = 0.5 + random.nextDouble() * 0.3;
                turnTicks = 0;
            }
        }
        return (int) Math.round(seconds * 20);
    }

    // Finishing ----------------------------------------------------------------------------------------------------

    private void done(Minecraft client, String summary) {
        int dugLeft = left;
        String text = "finished the room for " + schematicName + " (" + room.main + ")" + (summary.isEmpty() ? "." : "; " + summary + ".");
        finish(client, text, false);
        if (dugLeft > 0) LOGGER.info("Finished with {} blocks left", dugLeft);
        client.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.PLAYER_LEVELUP, 1.0f));
        if (settings().buildAfter && SchematicBuilder.getInstance() != null) {
            message(client, "starts SchematicBuilder on the room.", ChatFormatting.GRAY);
            SchematicBuilder.getInstance().setEnabled(true);
        }
    }

    private void finish(Minecraft client, String text, boolean warn) {
        if (doing == Doing.BREAK && client.gameMode != null) client.gameMode.stopDestroyBlock();
        if (pressing) releaseKeys(client);
        active = false;
        settings().enabled = false;
        doing = Doing.NONE;
        dig = null;
        check = null;
        next = null;
        preview = List.of();
        show(text.substring(0, 1).toUpperCase(Locale.ROOT) + text.substring(1), warn);
        message(client, text, warn ? ChatFormatting.YELLOW : ChatFormatting.GREEN);
    }

    // What it shows ------------------------------------------------------------------------------------------------

    private String phaseText() {
        if (dig == null) return "Waiting";
        return switch (dig.phase()) {
            case APPROACH -> "Staircase, " + dig.stepsLeft() + " steps left";
            case CLIMB -> "Climbing to the top, " + dig.stepsLeft() + " steps left";
            case TOWER -> "Climbing back up";
            case TIER -> "Band " + dig.tierNumber() + " of " + dig.tiers().size();
            case DROP -> "Down to band " + (dig.tierNumber() + 1) + " of " + dig.tiers().size();
            case DONE -> "Done";
        };
    }

    private void updatePreview() {
        Room r = room;
        if (r == null || !settings().preview) {
            preview = List.of();
            return;
        }
        int accent = config.accentColor & 0xFFFFFF;
        List<SchematicBuilder.Preview> boxes = new ArrayList<>();
        boxes.add(new SchematicBuilder.Preview(r.main.aabb(), accent, false));
        if (r.storage != null) boxes.add(new SchematicBuilder.Preview(r.storage.aabb(), 0x7FD6FF, false));
        boxes.add(new SchematicBuilder.Preview(r.schematic.aabb().deflate(0.05), 0xF2B35E, false));
        BlockPos at = next;
        if (at != null) boxes.add(new SchematicBuilder.Preview(new AABB(at).deflate(0.25), 0xFFFFFF, true));
        preview = List.copyOf(boxes);
    }

    /** The info panel's line while digging: blocks left, and the way to the block being dug. */
    public FinderSummary summary() {
        if (!active) return null;
        BlockPos at = next;
        String note = statusWarn ? status : dig == null ? "waiting for a good chunk" : phaseText();
        if (note.length() > 40) note = note.substring(0, 39) + "...";
        return new FinderSummary("Miner", Math.max(0, left), at == null ? null : Vec3.atCenterOf(at), config.accentColor, note, statusWarn);
    }

    private void show(String text, boolean warn) {
        status = text;
        statusWarn = warn;
    }

    private static void message(Minecraft client, String text, ChatFormatting color) {
        if (client.player == null) return;
        client.player.sendSystemMessage(Component.literal("[Donut] ChunkMiner " + text).withStyle(color));
    }
}
