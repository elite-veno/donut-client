package dev.donut.client.schematic;

import dev.donut.client.config.ClientConfig;
import dev.donut.client.config.SchematicSettings;
import dev.donut.client.finder.ChunkEvents;
import dev.donut.client.finder.FinderSummary;
import dev.donut.client.mixin.MultiPlayerGameModeAccess;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongPredicate;
import java.util.function.Predicate;
import java.util.stream.Stream;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BasePressurePlateBlock;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BubbleColumnBlock;
import net.minecraft.world.level.block.CarpetBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.CrossCollisionBlock;
import net.minecraft.world.level.block.DiodeBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.ObserverBlock;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
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
    /** Food level at or under which the builder eats, with food carried: most food then fills up with little waste. */
    private static final int EAT_AT = 14;
    /** Food level at or under which, with nothing to eat, the builder waits: a player this hungry can't sprint, and starves on. */
    private static final int HUNGRY = 6;
    /** Food the builder never eats for hunger: what harms, teleports or is worth more than a meal. */
    private static final Set<Item> NOT_EATEN = Set.of(Items.ROTTEN_FLESH, Items.SPIDER_EYE, Items.POISONOUS_POTATO, Items.PUFFERFISH,
            Items.CHICKEN, Items.CHORUS_FRUIT, Items.SUSPICIOUS_STEW, Items.GOLDEN_APPLE, Items.ENCHANTED_GOLDEN_APPLE);
    /** Stacks of each helper block kept on the player, out of the boxes: climbs and helper blocks use them up quickly. */
    private static final int HELPER_STACKS = 2;
    /** With fewer helper blocks than this left outside the boxes, more are taken out of one. */
    private static final int HELPERS_LOW = 16;
    /**
     * With a stack of helper blocks on hand, dropped ones are fetched once this many lie together, or once one has lain
     * {@link #PICKUP_WAIT} ticks: a walk for each as it falls costs what placing a few blocks does, and those that fall
     * meanwhile, or that a walk for the build passes, come along.
     */
    private static final int PICKUP_HANDFUL = 8, PICKUP_WAIT = 600;
    /** Fewer helper blocks than this in hand, one dropped below is worth the climb back up. */
    private static final int PICKUP_LAST = 4;
    /** What ending a walk for the build where dropped helper blocks are picked up is worth, in blocks placed there. */
    private static final double PICKUP_BONUS = 0.75;
    /**
     * How many of the nearest blocks a walk checks a real look for at each place it considers: enough to tell a place
     * from which a handful go in from one from which a dozen do.
     */
    private static final int VERIFY = 6;
    private static SchematicBuilder instance;
    /** Logs each placement and why blocks can't go in, with -Ddonut.schematic.debug=true. */
    private static boolean debug() { return Boolean.getBoolean("donut.schematic.debug"); }

    /**
     * Whether walks weigh places to swim in still water, held at a height there: only measured and logged so far (see
     * {@link #logSwimLooks}), as the walker can't hold a height in water yet.
     */
    private static boolean swimStands() { return Boolean.getBoolean("donut.schematic.swimStands"); }

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
    /**
     * The yaw at the start of each of the last few ticks, as the movement packets of those ticks carried it to the
     * server; NaN before there is one.
     */
    private final float[] recentYaws = {Float.NaN, Float.NaN, Float.NaN};
    /**
     * For the debug log: the look at the start of the last tick, how many ticks since changed it, and how many of those
     * by something other than whole mouse steps, which no mouse does.
     */
    private float lastYaw = Float.NaN, lastPitch;
    private int lookChanges, offGridChanges;
    /**
     * The hotbar slot and the item last seen in hand, and the tick from which the hand may use it: a moment after either
     * changes, as a player presses a number key and clicks after.
     */
    private int handSlot = -1;
    private Item handItem;
    private long handReadyTick;
    /**
     * When the hands may do something again after a swap, a block broken or a box picked up: the view may already turn
     * toward what comes next, see {@link #paceAfterHands}.
     */
    private long handsAt;
    /**
     * When a block the builder broke last went, and how long water waits after that: on a server the drop shows up a
     * moment later than the block goes, and water poured meanwhile, before anything can see the drop to pick it up first,
     * carries it off into the farm.
     */
    private long brokeAt = -1000;
    /**
     * Whether attack is still held from the block just broken: on through the turn to the next, the hand swinging at
     * what the crosshair passes, as long as vanilla's delay before the next block runs; and the last tick it was used.
     */
    private boolean attackHeld;
    private long attackTick = -1;
    private static final int DROPS_SETTLE = 10;
    private int buildSlot = -1;

    private volatile String status = "Idle";
    private volatile boolean statusWarn;
    private volatile BlockPos next;
    private volatile List<Preview> preview = List.of();
    private volatile Progress progress = Progress.NONE;
    private int layerY = Integer.MIN_VALUE, nextIndex = -1;
    private final List<Item> missing = new ArrayList<>();

    /** What a click is for. */
    private enum Act { PLACE, POUR, ADJUST, HELPER, REFILL, FILL_BUCKET, TILL }

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
    /**
     * Blocks whose click missed from a place to stand, keyed by place and block as planWalk keys them: a walk there no
     * longer counts on them, so it doesn't walk straight back to try the same look. Forgotten with the layer.
     */
    private final Set<Long> missedFrom = new HashSet<>();
    private BlockPos missedAt;
    /**
     * Where the player stood when the click being lined up was worked out, by {@link Navigator.Node#key}, and where the
     * feet were: a player carried off from there while turning works it out again rather than chase it.
     */
    private long aimFrom;
    private Vec3 aimFeet;
    /**
     * Places to stand the player was carried off from while lining up a click, by water running past, ice or a push:
     * walks don't end there again. Forgotten with the layer, or with the clicks that missed when there is no other way on.
     */
    private final Set<Long> carriedFrom = new HashSet<>();
    /** How far the feet may move off where a click was worked out, as a slide coming to rest does, before it is given up. */
    private static final double CARRIED = 0.3;
    /** Whether a walk is being looked for that may climb through cells the build fills later: helper blocks may go there. */
    private boolean escaping;
    /** When a helper block climbed on in a cell the build fills was last checked for whether it can go. */
    private long escapeChecked = -100_000;
    /**
     * Blocks with no click from where the player stands, and why, worked out at {@code noClickFrom} in that stance: passed
     * over until the player moves, a block goes in or a couple of seconds go by. A long row of them would otherwise use
     * up each tick's planning time before a walk is worked out, and the next tick would start over with the same ones.
     */
    private final Map<Integer, PlacementSolver.Problem> noClickHere = new HashMap<>();
    /**
     * Blocks that need a helper block put down first, which from where the player stands wouldn't go in against it even
     * then, found as {@link #noClickHere} is: their helper waits until no block in reach goes in without one, as a block
     * put in beside first often holds the next by its side, and then no helper is needed at all.
     */
    private final Set<Integer> helperLater = new HashSet<>();
    /** The cells the way out from where the player stands passes through, worked out at tick {@code exitTick}; null for none. */
    private Set<Long> exitCells;
    private long exitTick = -1;
    /** When the builder last placed a sign, whose editor it then closes, and the tick it does: a moment later, as a hand. */
    private long signPlacedAt = -1000, signDoneAt;
    /** Water sources that weren't there again after a bucket was filled from them: not filled from again. */
    private final Set<Long> finiteSources = new HashSet<>();
    /** The water source a bucket was last filled from, and when, to see that it comes back. */
    private BlockPos filledFrom;
    private long filledAt;
    /** Water near the player that comes back when a bucket is filled from it, nearest first, found at {@code waterTick}. */
    private List<BlockPos> renewing = List.of();
    private long waterTick = -1000;
    /** While eating: since when, and what was in hand then and how many; {@code eatingSince} is -1 when not eating. */
    private long eatingSince = -1;
    private Item eatingItem;
    private int eatingCount;
    /** When eating last came to nothing, as with food that wouldn't be eaten: not tried again for half a minute. */
    private long eatFailedAt = -100_000;
    /** Since when, and last when, the builder found nothing to do from where the player is, for {@link #stepDown}. */
    private long idleSince = -1000, idleLast = -1000;
    private Vec3 noClickFrom;
    private boolean noClickCrouch;
    private long noClickTick;
    /** How far a player still sliding may get from where {@link #noClickHere} was worked out before it is worked out again. */
    private static final double NO_CLICK_SLIDE = 0.25;
    /**
     * Ticks a look has rested on its spot, and is to rest before the click, so the server has the head turned the same
     * way: as many as set, now and then one more, as a hand doesn't click on the very same beat each time.
     */
    private int restingTicks, restFor = 1;
    /** How far, in degrees, the view turned on its way to the look it now rests on, to rest a little longer after a long turn. */
    private float turned;
    /** How many misplaced blocks the log has been told of. */
    private int misplacedLogged, wrongLogged;
    /** The block whose missing place to stand was last explained in the log, and when: once in ten seconds is enough. */
    private int lastExplained = -1;
    private long explainedAt;
    /** When the next click may go out at the earliest: the delay set counts from one click to the next. */
    private long nextClick;
    /** Whether the builder holds the crouch key. */
    private boolean crouchHeld;
    /** Whether the climb under way puts its block onto one with a use, crouched so the click doesn't open it. */
    private boolean climbCrouch;
    /** Walks the player to where blocks are in reach; it never flies. */
    /** Turns the view smoothly, for aiming, walking and looking about alike. */
    private final Head head = new Head();
    private final Walker walker = new Walker(head);
    /** When the next break starts, and while on one, when it ends and where the player glances next and when. */
    private long breakAt = -1, breakEnds = -1, nextGlance;
    /** When the next pause of a second or a few comes, and whether the one under way is such a pause rather than a break. */
    private long pauseAt = -1;
    private boolean pausing;
    private float glanceYaw, glancePitch;
    private int walkFailures;
    /** For the debug log: the blocks the walk under way was planned for, and where it was to end. */
    private final Set<Integer> walkExpects = new HashSet<>();
    /**
     * The blocks the last walk was made for with the seals lifted. A block placed on arriving puts the seals back, and
     * the others it was made for would wait again, unmentioned, though the walk was for them: they keep the lift there.
     */
    private final Set<Integer> liftedFor = new HashSet<>();
    private Navigator.Node walkEnd;
    /** Places tried and found useless since the last block went in, so the next walk goes somewhere else. */
    private final Set<Long> uselessSpots = new HashSet<>();
    /** Places walked to since the last block went in to search again from nearer the blocks (see planWalk): once each. */
    private final Set<Long> approached = new HashSet<>();
    /**
     * Blocks changed in or near the build, and chunks loaded, since the game started: a walk search that found no place to
     * stand finds none again until one of them, or the player, the inventory or what the search leaves out, changes.
     */
    private long worldChanges;
    /** How far round the build a changed block counts for {@link #worldChanges}: about what a walk search reaches. */
    private static final double CHANGE_MARGIN = 24;
    /**
     * What the last walk searches that found nothing were made with, and when, see {@link #walkSearchKey}: one for walks on
     * the ground, one for walks that climb through the build's cells ({@link #escaping}), as both are tried each time.
     * And the same for the last pit search.
     */
    private final long[] failedWalkKey = new long[2], failedWalkAt = {Long.MIN_VALUE, Long.MIN_VALUE};
    private long failedPitKey, failedPitAt = Long.MIN_VALUE;
    /** However little changed, a search that found nothing is made again after this long. */
    private static final long FAILED_SEARCH_NANOS = 5_000_000_000L;
    /** Where the last walk for blocks was to end, and whether anything was clicked since it started. */
    private Navigator.Node lastWalkEnd;
    private boolean clickedSinceWalk;
    /** Steps that failed when walked, by {@link Navigator#stepKey}, this layer: walks go another way. */
    private final Set<Long> badSteps = new HashSet<>();
    /** When the steps that failed were last given another try, for want of any other way on. */
    private long badStepsRetried = -100_000;
    /**
     * How often each step failed this layer, and when it last did: one that failed twice stays out of the walks when the
     * others get another try, for five minutes. And the walking steps that failed by a fall off them: on their next try
     * they are walked crouched, so the edge holds the player.
     */
    private final Map<Long, Integer> stepFailures = new HashMap<>();
    private final Map<Long, Long> stepFailedAt = new HashMap<>();
    private final Set<Long> carefulSteps = new HashSet<>();
    /**
     * Places to stand that a block would shut the player in from, by the block's index: placed from any of them, it
     * leaves no way out, so walks for that block go somewhere else. Worked out when {@link #wayOut} holds it back.
     */
    private final Map<Integer, Set<Long>> shutIn = new HashMap<>();
    /**
     * Places to stand with no way back out onto the layer or off the build, found when a walk would drop into them, as
     * through an open fence gate: walks never end there. Kept as long as {@link #shutIn}.
     */
    private final Set<Long> traps = new HashSet<>();
    /** A cell water or lava was just poured into, and when to see, for the log, whether it holds it. */
    private BlockPos pourCheck;
    private long pourCheckAt;
    /**
     * Water and lava poured, and cells opened beside water by taking a block away, by position, with the tick: for a
     * while water there still spreads, where settled water runs no further.
     */
    private final Map<Long, Long> pours = new HashMap<>();
    /** Helper blocks standing in the world, by position. */
    private final Map<Long, Helper> helpers = new HashMap<>();
    /** Where a block that nothing holds can be placed against a helper block, by the block's index. */
    private final Map<Integer, BlockPos> helperSpot = new HashMap<>();
    /** Helper blocks that couldn't be reached to take away. */
    private final List<BlockPos> helpersLeft = new ArrayList<>();
    /** Whether the helper blocks left during the build were given another try once every block was in. */
    private boolean leftRetried;
    /** Whether the cleanup's last try is on: climbing searches that go much further before a helper block is left. */
    private boolean cleanupWide;
    /** Climbs out of where the cleanup's walks shut the player in, over cells the build has open (see {@link #cleanUp}). */
    private int cleanupEscapes;
    private static final int CLEANUP_ESCAPES = 3;
    /**
     * Whether the cleanup's walks may jump down off the build, as {@link Navigator#dropsOff} lets them: a last round, for
     * helper blocks on the ground round a build the player is up on, with no other way down.
     */
    private boolean cleanupDrop;
    /**
     * Spots the cleanup has taken a helper block away from: no climb puts one there again, or two columns are taken down
     * and put up again in turn, each climb for one going up where the other stood.
     */
    private final Set<Long> cleared = new HashSet<>();
    /** Walks into a narrow pit to fill it by climbing out, by its cell (see {@link #refill}). */
    private final Map<Long, Integer> pitWalks = new HashMap<>();
    /** The narrow pit the player walked into to fill by climbing out, or null. */
    private BlockPos pitFill;
    private boolean cleaning;
    /**
     * Tops of helper columns no walk reached from where the player stood, standing at {@code cleanupFrom}: tried again
     * from anywhere else, as what can't be reached from up on a pillar often can from below.
     */
    private final Set<Long> cleanupSkipped = new HashSet<>();
    /** When a bridging click that couldn't go out was last explained in the log. */
    private long bridgeExplained = -1000;
    /** Bridges cleanup walks have put down: a few, to get back across the gaps the build bridged, and no more. */
    private int cleanupBridges;
    private static final int CLEANUP_BRIDGES = 4;
    /** How far off a helper column, sideways, cleanup walks there on foot before it searches for a climb to its top. */
    private static final double NEAR_COLUMN = 4;
    /** Walks to beside each helper column so far, by its top: twice at most, so two columns can't send the player to and fro. */
    private final Map<Long, Integer> nearWalks = new HashMap<>();
    /** The helper block the last cleanup climb was for; null for none. */
    private BlockPos cleanupTarget;
    /** The helper column the last cleanup walk on foot went beside, by its top; null for none. */
    private BlockPos cleanupColumn;
    /** How far above where a walk on foot to beside its column ends a helper top may be: higher, it is a climb from there. */
    private static final int COLUMN_RISE = 4;
    /**
     * Cleanup climbs that ended with their block still out of reach, by block and the place they ended at: that place is
     * no goal for that block again, wherever a walk sets off from. And how many such climbs each block has had.
     */
    private final Set<Long> cleanupFailed = new HashSet<>();
    private final Map<Long, Integer> cleanupMisses = new HashMap<>();
    /** Cleanup climbs that end out of reach of their block, after which it is left for good. */
    private static final int CLEANUP_MISSES = 4;
    /**
     * Helper columns, by x and z, the player stood on top of with helper tops as high elsewhere to walk to first, and no
     * walk to any of them found: from there on each comes down under the player's feet as before.
     */
    private final Set<Long> cleanupKeptUp = new HashSet<>();
    /** How many other helper tops as high as the one underfoot, out of reach, keep the player up there to walk to them first. */
    private static final int KEEP_UP_TOPS = 2;
    private long cleanupFrom = Long.MIN_VALUE;
    private String helperList = "";
    private Set<Item> helperItems = Set.of();
    /** A helper block being broken, and for how long. */
    private BlockPos breaking;
    private int breakTicks;
    /**
     * Blocks this build placed, by index, with the tick of the click: one the server then sets another way than the click
     * was for is broken and placed again.
     */
    private final Map<Integer, Long> placedAt = new HashMap<>();
    /** How often each block was broken to be placed again; and the one being broken for that now, or -1. */
    private final Map<Integer, Integer> repairs = new HashMap<>();
    /** Trips to the player's orders for blocks the build ran out of; see {@link SchematicSettings#fromOrders}. */
    private final dev.donut.client.order.OrderCollector orders = new dev.donut.client.order.OrderCollector();
    private long ordersTriedAt = Long.MIN_VALUE / 2;
    /** After a trip that found nothing, or couldn't start, the next waits this long. */
    private static final int ORDERS_RETRY_TICKS = 200;
    /** Last rounds over the blocks given up on, once nothing else is left; see {@link #lastRound}. */
    private int lastRounds;
    /** The most last rounds a build makes before it counts what is still wrong. */
    private static final int LAST_ROUNDS = 2;
    private int repairing = -1;
    /** Items of blocks broken to be placed again, whose drops are picked up. */
    private final Set<Item> repairDrops = new HashSet<>();
    /** Walks made to a block to place again, by index: one still out of view after these is left as it is. */
    private final Map<Integer, Integer> repairWalks = new HashMap<>();
    /**
     * Blocks of the build broken to get out of a pocket the player was shut in, by index: left out until the player is out,
     * then placed again like any other. And when that was last looked for.
     */
    private final Set<Integer> brokeOut = new HashSet<>();
    private long breakOutChecked = -100_000;
    /** When the player was first seen out of the pocket since, or -1 while not; and the places to stand it held. */
    private long outSince = -1;
    private final Set<Long> pocketPlaces = new HashSet<>();
    /** The other block of a way out through a wall, broken next; null when there is none. */
    private BlockPos pairNext;
    /**
     * Blocks of the build broken to open a look at one beside them that no look reached (see {@link #unblock}), by index:
     * each waits until that {@code stuck} block is in, or five minutes from {@code since}.
     */
    private record Unblock(int stuck, long since) { }
    private final Map<Integer, Unblock> unblocking = new HashMap<>();
    /** How often each stuck block had a block round it broken, and walked to one; and the surroundings it had none in. */
    private final Map<Integer, Integer> unblocks = new HashMap<>();
    private final Map<Integer, Integer> unblockWalks = new HashMap<>();
    private final Map<Integer, Long> unblockSearched = new HashMap<>();
    /** Blocks the last walk search found no place to stand for, the first first; and when each was last weighed for that. */
    private List<Integer> noStand = List.of();
    private final Map<Integer, Long> standSearched = new HashMap<>();
    /**
     * Helper blocks climbed on during a walk out of where the player was shut in, by position: taken away as soon as
     * the player is out, as the pocket they stand in may be closed again after; and whether the walk under way is one.
     */
    private final Set<Long> escapeHelpers = new HashSet<>();
    private boolean escapeWalk;
    /** Walks made to bring each of those into view, by position. */
    private final Map<Long, Integer> escapeWalks = new HashMap<>();
    /** Walks made to take back a helper block of an earlier walk, out of them (see {@link #walkToSpareHelper}), by position. */
    private final Map<Long, Integer> reclaimWalks = new HashMap<>();
    /** When each of those was last checked with a search for a way on without it (see {@link #reclaimable}). */
    private final Map<Long, Long> reclaimChecked = new HashMap<>();
    /** Since when one of those has stood in the build's own water, holding the layers above (see BuildPlan.hold), or -1. */
    private long holdSince = -1;
    /** Whether the way out {@link #wayOut} keeps clear climbs over cells the build fills later. */
    private boolean exitEscapes;
    /** How often each block was broken to go in again into water, and the tick it last was. */
    private final Map<Integer, Integer> drains = new HashMap<>();
    private final Map<Integer, Long> drainedAt = new HashMap<>();
    /** Ground dug out to stand lower, by position, with the block that was there; filled in again on the way out. */
    private final Map<Long, BlockState> dug = new LinkedHashMap<>();
    /**
     * Dug-out ground filled in with something else, for want of the block that was there in hand, by position, with that
     * block; and spots never filled. Once every block is in, those the inventory has the block for are put right.
     */
    private final Map<Long, BlockState> filledOther = new LinkedHashMap<>();
    /** Walks made to put each of those right, by position. */
    private final Map<Long, Integer> redoWalks = new HashMap<>();
    /** All ground ever dug out, by position, with the block that was there: what the ground must hold again at the end. */
    private final Map<Long, BlockState> groundDug = new LinkedHashMap<>();
    /** Ground under a spot being put right: the spot over it waits to be filled in until it is (see {@link #redoFills}). */
    private final Set<Long> redoUnder = new HashSet<>();
    private final List<BlockPos> dugLeft = new ArrayList<>();
    /** Walks made during the build to fill in dug ground, by spot: one that still can't be filled from there is left for the end. */
    private final Map<Long, Integer> refillWalks = new HashMap<>();
    /** A pit being dug to stand in, and for how long. */
    private Pit pit;
    private int digTicks;
    /**
     * Whether the pit is dug from beside, from the floor of the one the player stands in, so the pits run on as a
     * trench; and whether its upper block goes first.
     */
    private boolean pitAlong, pitTopFirst;
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
    /**
     * Whether blocks may go in ahead of their turn in the redstone-last order, because the blocks whose turn it is can't
     * be reached from anywhere and nothing else can go in: one of those may need a later one beside it to click on.
     */
    private boolean orderLifted;
    /** Walks made for blocks of the layer since the last click that placed one. */
    private int walksSinceClick;
    /** Blocks a walk was made for and that didn't go in on arrival, how often, this layer: past two, only firm looks count. */
    private final Map<Integer, Integer> arrivalMisses = new HashMap<>();
    /** After how many such walks, none of them placing anything, the layer's order is lifted. */
    private static final int WALKS_WITHOUT_CLICK = 25;
    private long[] sealStamp = new long[0];
    private byte[] seal = new byte[0];
    /** What {@link #heldByLater} found for each block, against blocks of group 1 and later and of group 2, and when. */
    private long[] laterStamp = new long[0];
    private byte[] later = new byte[0];

    /** An item being moved into the hotbar with the inventory open: from which slot, to which, and when the steps come. */
    private int swapFrom = -1, swapTo, swapTicks, swapAt, swapClose;
    /** Whether the swap under way waits for the hands to be free before its inventory opens. */
    private boolean swapOpening;

    private Restock restock = Restock.NONE;
    /**
     * The item a walk to open ground was for, or a shulker box just swapped into the hotbar, to fetch first thing on
     * arrival or once the swap is done, before the blocks in reach send the player back; and how many such walks in a row
     * found no room yet.
     */
    private Item restockAfterWalk;
    private int restockWalks;
    /**
     * Swaps for a restock not started yet that carried it over, to go on once the box is in hand: after two, a further
     * swap no longer does, so one that keeps being undone can't send the builder round again and again.
     */
    private int restockSwaps;
    private Item restockItem;
    private BlockPos box;
    private PlacementSolver.Click boxClick;
    private int restockTicks, nextTake, boxesBefore, restockFailures;
    /**
     * Items a box had but the inventory had no room for, by the blocks done when that was found: the build goes on with
     * what is carried, which frees slots, and fetches them again later. And the blocks done at the last such find.
     */
    private final Map<Item, long[]> noRoomFor = new HashMap<>();
    private int noRoomDone = -1;
    /**
     * Items no spot for their shulker box was found for, from where the player stood or a walk without climbing, by the
     * tick: their blocks wait {@link #NO_BOX_WAIT} ticks while the build goes on with the rest, rather than ask again
     * every second from the same place.
     */
    private final Map<Item, Long> noBoxSpot = new HashMap<>();
    private static final int NO_BOX_WAIT = 400;
    /** When helper blocks were last taken out of a box for running low: not tried again for a while, should it fail. */
    private long helperFetchTick = -100_000;
    /**
     * Walks made to pick up a dropped block, by entity id: a helper block is not walked to again, should it have stayed
     * where it was; a block broken to go in again is, a few times.
     */
    private final Map<Integer, Integer> pickupTried = new HashMap<>();
    /** Dropped helper blocks left lying below the layer for its sweep, by entity id: not searched for again until then. */
    private final Set<Integer> pickupBelow = new HashSet<>();
    /**
     * Whether every dropped helper block in the build is gathered up before it goes on: before a layer's water goes in,
     * which would carry them off into the farm, and once a layer is done. And the layer whose drops were gathered before
     * its water.
     */
    private boolean pickupSweep;
    private int sweptLayer = Integer.MIN_VALUE;
    /** Walks planned to a dropped box since it broke, a few at a time; and whether the player was asked to pick it up. */
    private int boxWalks;
    /** Walks to where the placed box can be seen, and rounds of trying to get at it, since this box was placed. */
    private int boxViewWalks, boxRounds;
    private boolean boxAsked;
    /** What the layers being built need, worked out when a box opens, so taking and putting back agree. */
    private Map<Item, Integer> soon = Map.of();
    /** Slots of the open box's menu still to click, one at a time: a stack swapped for one in a full box. */
    private final ArrayDeque<Integer> swapClicks = new ArrayDeque<>();
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
        ChunkEvents.register(new ChunkEvents.Listener() {
            @Override
            public void blockChanged(ClientLevel level, BlockPos pos, BlockState previous, BlockState current) {
                if (plan != null && plan.bounds.inflate(CHANGE_MARGIN).contains(Vec3.atCenterOf(pos))) worldChanges++;
            }

            @Override
            public void chunkLoaded(ClientLevel level, net.minecraft.world.level.ChunkPos pos) {
                worldChanges++;
            }
        });
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
        swapOpening = false;
        handsAt = System.nanoTime();
        attackHeld = false;
        walker.stop(client);
        releaseKeys(client);
        // Disconnected while eating: use let go, and the next build doesn't take up a meal begun in another world, with
        // whatever is in hand then, a block that use would place.
        if (eatingSince >= 0) {
            client.options.keyUse.setDown(false);
            eatingSince = -1;
        }
        breaking = null;
        pit = null;
        pitAlong = false;
        finiteSources.clear();
        filledFrom = null;
        helpers.clear();
        helperSpot.clear();
        guardSpot.clear();
        guardWalks.clear();
        guardMemoCleared.clear();
        helpersLeft.clear();
        leftRetried = false;
        cleanupWide = false;
        cleanupDrop = false;
        cleanupEscapes = 0;
        cleared.clear();
        pitWalks.clear();
        pitFill = null;
        cleaning = false;
        cleanupSkipped.clear();
        cleanupBridges = 0;
        nearWalks.clear();
        cleanupTarget = null;
        cleanupColumn = null;
        cleanupFailed.clear();
        cleanupMisses.clear();
        cleanupKeptUp.clear();
        dug.clear();
        refillWalks.clear();
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
        recentYaws[(int) Math.floorMod(tick, (long) recentYaws.length)] = player.getYRot();
        if (debug()) countLookChanges(player);
        noteHand(player);
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
        if (!planKey.equals(key()) && !replan(client)) return;
        plan.refresh(client.level, REFRESH_BUDGET);
        head.pace((float) settings().turnSpeed);
        PlacementSolver.spread(settings().clickSpread);
        head.misses(settings().aimCorrections);
        walker.pace(settings().hesitate, settings().sprint);
        if (swapFrom >= 0) {
            swapTick(client, player);
            return;
        }
        if (orders.busy()) {
            // Taking what the build ran out of from the player's orders: the order pages are GoNuts', the build waits.
            orders.tick(client);
            show("Collecting what the build needs from your orders", false);
            if (!orders.busy()) nextAction = System.nanoTime();
            return;
        }
        if (restock != Restock.NONE) {
            restockTick(client, player);
            if (tick % 10 == 0) updatePreview(player);
            return;
        }
        if (client.gui.screen() instanceof AbstractSignEditScreen editor && tick - signPlacedAt < 100) {
            // A sign the builder placed opens its editor. The schematic's text isn't written: Done, a moment later, as long
            // as a player takes to see the editor and close it.
            if (tick >= signDoneAt) editor.onClose();
            return;
        }
        if (client.gui.screen() instanceof AbstractContainerScreen<?> && !(client.gui.screen() instanceof InventoryScreen)
                && clickedBlock != null && tick - clickedAt <= MENU_AFTER_CLICK && hasMenu(client.level, clickedPos)) {
            // A click of the builder's own opened the block's menu: the block takes a crouched click from now on, as a
            // player learns which blocks open on a click. Closed again at once, and the click is made again.
            BlockRules.learnInteractive(clickedBlock);
            LOGGER.info("Clicking {} opened its menu: closed, and it takes a crouched click from now on", clickedBlock.getName().getString());
            clickedBlock = null;
            player.closeContainer();
            return;
        }
        if (client.gui.screen() != null) {
            walker.stop(client);
            show("Paused while a menu is open", false);
            return;
        }
        if (player.isUsingItem() || player.isSleeping()) { show("Waiting while you use an item", false); return; }
        if (tick % 10 == 0) updatePreview(player);
        if (pourCheck != null && tick >= pourCheckAt) {
            if (debug()) {
                StringBuilder around = new StringBuilder();
                for (BlockPos near : BlockPos.betweenClosed(pourCheck.offset(-1, -1, -1), pourCheck.offset(1, 1, 1))) {
                    FluidState fluid = player.level().getFluidState(near);
                    if (fluid.isSource()) around.append(' ').append(near.toShortString());
                }
                LOGGER.info("Poured at {}: it holds {}; sources around:{}", pourCheck.toShortString(), player.level().getBlockState(pourCheck), around);
            }
            pourCheck = null;
        }
        if (!plan.swept()) { show("Reading the build site", false); return; }
        if (!shortageReported) reportShortage(client, player);
        holdAttack(client, player);
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
        if (takingBreak(client, player)) return;
        if (System.nanoTime() - nextAction < 0) return;
        decide(client, player);
        // The turn toward the block picked starts on the tick it was picked.
        if (aim != null && !walker.active() && pit == null && breaking == null && swapFrom < 0 && restock == Restock.NONE) fire(client, player);
    }

    /** Notes a change of hotbar slot, or of the item in hand, and when the hand may first use what it now holds. */
    private void noteHand(LocalPlayer player) {
        int slot = player.getInventory().getSelectedSlot();
        Item item = player.getMainHandItem().getItem();
        if (slot == handSlot && item == handItem) return;
        handSlot = slot;
        handItem = item;
        double most = settings().itemSwitch;
        double seconds = ThreadLocalRandom.current().nextDouble(most / 3, most + 1e-9);
        // Never the tick it was taken: a key pressed and a click within the same tick is no hand's.
        handReadyTick = tick + Math.max(1, Math.round(seconds * 20));
    }

    /**
     * Whether the hand may use what it holds: only a moment after another slot or item was taken, as a player presses a
     * number key and then clicks.
     */
    private boolean handReady(LocalPlayer player) {
        noteHand(player);
        return tick >= handReadyTick && System.nanoTime() - handsAt >= 0;
    }

    /**
     * Counts the ticks the look changed in, and those it changed by other than whole mouse steps, for the debug log once
     * a minute: anything that turns the view must do it as a mouse does.
     */
    private void countLookChanges(LocalPlayer player) {
        float yaw = player.getYRot(), pitch = player.getXRot();
        if (!Float.isNaN(lastYaw) && (yaw != lastYaw || pitch != lastPitch)) {
            lookChanges++;
            float step = Head.mouseStep();
            // At the end of its range the pitch stops short of a whole step, as it does for a mouse.
            boolean pitchClamped = Math.abs(pitch) >= 90 || Math.abs(lastPitch) >= 90;
            if (!wholeSteps(yaw - lastYaw, step) || !pitchClamped && !wholeSteps(pitch - lastPitch, step)) offGridChanges++;
        }
        lastYaw = yaw;
        lastPitch = pitch;
        if (tick % 1200 == 0 && lookChanges > 0) {
            LOGGER.info("Look changed in {} ticks, {} of them not by whole mouse steps", lookChanges, offGridChanges);
        }
    }

    private static boolean wholeSteps(float degrees, float step) {
        float steps = degrees / step;
        return Math.abs(steps - Math.round(steps)) < 0.01f + Math.abs(steps) * 1e-5f;
    }

    /**
     * Now and then, between one block and the next, a break: the player stands a while and looks about, as a player
     * does, before building on. True while on one.
     */
    private boolean takingBreak(Minecraft client, LocalPlayer player) {
        SchematicSettings s = settings();
        long now = System.nanoTime();
        if (!s.breaks) {
            breakAt = breakEnds = pauseAt = -1;
            return false;
        }
        ThreadLocalRandom random = ThreadLocalRandom.current();
        if (breakEnds > 0) {
            if (now - breakEnds >= 0) {
                breakEnds = -1;
                if (pausing) pauseAt = -1;
                else breakAt = pauseAt = -1;
                pausing = false;
                // Back to it after a moment, mostly a short one.
                nextAction = now + (long) (skewed(random, 0.4, 0.5, 0.2, 1.2) * 1e9);
                show("Back to building", false);
                return false;
            }
            if (now - nextGlance >= 0) {
                // A look somewhere else every few seconds, now and then back at the build.
                glanceYaw = player.getYRot() + (random.nextBoolean() ? 1 : -1) * (15 + random.nextFloat() * 70);
                glancePitch = -15 + random.nextFloat() * 50;
                nextGlance = now + (long) ((1.2 + random.nextDouble() * 3.5) * 1e9);
            }
            head.aim(player, glanceYaw, glancePitch);
            show(pausing ? "Looking about" : "Taking a short break (" + (breakEnds - now + 999_999_999L) / 1_000_000_000L + " s)", false);
            return true;
        }
        if (breakAt < 0) {
            // Every few minutes as set, mostly near it, now and then much sooner or later: no two stretches alike.
            double minutes = skewed(random, s.breakEvery, 0.5, s.breakEvery * 0.25, s.breakEvery * 3);
            breakAt = now + (long) (minutes * 60e9);
            return false;
        }
        if (pauseAt < 0) {
            pauseAt = now + (long) (skewed(random, s.pauseEvery, 0.6, s.pauseEvery * 0.2, s.pauseEvery * 4) * 1e9);
            return false;
        }
        if (!player.onGround() || player.isInWater()) return false;
        boolean breakDue = now - breakAt >= 0;
        if (!breakDue && now - pauseAt < 0) return false;
        pausing = !breakDue;
        // A break mostly about half the longest, a few as long; a pause a second or two, a few some seconds.
        double seconds = pausing ? skewed(random, 1.6, 0.5, 0.8, 4)
                : skewed(random, s.breakLength * 0.45, 0.6, Math.min(3, s.breakLength), s.breakLength);
        breakEnds = now + (long) (seconds * 1e9);
        nextGlance = now + (long) (random.nextDouble() * 1.5e9);
        glanceYaw = player.getYRot();
        glancePitch = player.getXRot();
        releaseKeys(client);
        if (player.isShiftKeyDown() && !onNarrowEdge(player)) setCrouch(client, false);
        if (debug()) LOGGER.info(pausing ? "A pause of {} s" : "A break of {} s", String.format(Locale.ROOT, "%.1f", seconds));
        return true;
    }

    /**
     * A time about {@code median}, as people's pauses go: most near it, a few much longer, fewer much shorter (log-normal
     * with {@code sigma}), within {@code least} and {@code most}.
     */
    private static double skewed(ThreadLocalRandom random, double median, double sigma, double least, double most) {
        return Math.clamp(median * Math.exp(sigma * random.nextGaussian()), least, Math.max(least, most));
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
        noRoomFor.clear();
        noRoomDone = -1;
        noBoxSpot.clear();
        restock = Restock.NONE;
        restockAfterWalk = null;
        aheadLayer.clear();
        fetchedBeforeWalk.clear();
        swappedIn.clear();
        restockWalks = 0;
        restockSwaps = 0;
        aim = null;
        buildSlot = -1;
        walkFailures = 0;
        uselessSpots.clear();
        approached.clear();
        lastWalkEnd = null;
        shutIn.clear();
        traps.clear();
        badSteps.clear();
        badStepsRetried = -100_000;
        stepFailures.clear();
        stepFailedAt.clear();
        carefulSteps.clear();
        missedFrom.clear();
        carriedFrom.clear();
        pours.clear();
        missedHere.clear();
        forgetNoClicks();
        pickupTried.clear();
        pickupBelow.clear();
        pickupSweep = false;
        sweptLayer = Integer.MIN_VALUE;
        placedAt.clear();
        repairs.clear();
        lastRounds = 0;
        repairDrops.clear();
        repairWalks.clear();
        drains.clear();
        drainedAt.clear();
        brokeOut.clear();
        breakOutChecked = -100_000;
        outSince = -1;
        pocketPlaces.clear();
        pairNext = null;
        unblocking.clear();
        unblocks.clear();
        unblockWalks.clear();
        unblockSearched.clear();
        noStand = List.of();
        noneInReach = 0;
        lastDone = -1;
        standSearched.clear();
        escapeHelpers.clear();
        escapeWalk = false;
        escapeWalks.clear();
        reclaimWalks.clear();
        reclaimChecked.clear();
        holdSince = -1;
        repairing = -1;
        helperFetchTick = -100_000;
        helpers.clear();
        helperSpot.clear();
        guardSpot.clear();
        guardWalks.clear();
        guardMemoCleared.clear();
        helpersLeft.clear();
        leftRetried = false;
        cleanupWide = false;
        cleanupDrop = false;
        cleanupEscapes = 0;
        cleared.clear();
        pitWalks.clear();
        pitFill = null;
        cleaning = false;
        cleanupSkipped.clear();
        cleanupBridges = 0;
        nearWalks.clear();
        cleanupTarget = null;
        cleanupColumn = null;
        cleanupFailed.clear();
        cleanupMisses.clear();
        cleanupKeptUp.clear();
        dug.clear();
        refillWalks.clear();
        dugLeft.clear();
        filledOther.clear();
        redoWalks.clear();
        groundDug.clear();
        redoUnder.clear();
        pit = lastPit = null;
        uselessPits.clear();
        pitTries.clear();
        sealsLifted = false;
        sealsLiftedAt = UNLIFTED;
        orderLifted = false;
        // A build begun again, or another with its bottom layer at the same height, starts its first layer afresh: no walks
        // or misses carried over from the last one.
        layerY = Integer.MIN_VALUE;
        walksSinceClick = 0;
        arrivalMisses.clear();
        lastWalkEnd = null;
        walkEnd = null;
        walkExpects.clear();
        liftedFor.clear();
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

    /**
     * The schematic file, its place, turn or order changed while building. The same blocks at the same places, as when
     * the file is saved again unchanged, keep the plan, and all that is known by block number with it. Anything else
     * stops the build: what was worked out by block number, the aim under way, helpers and repairs, belongs to the old
     * plan, and a block number of it may be another block, or none, in the new one. False when stopped.
     */
    private boolean replan(Minecraft client) {
        SchematicSettings s = settings();
        BuildPlan next = BuildPlan.of(schematic, new BlockPos(s.originX, s.originY, s.originZ), mirror(s.mirror), rotation(s.rotation),
                s.redstoneLast);
        boolean same = next.size() == plan.size();
        for (int i = 0; same && i < next.size(); i++) same = next.pos(i).equals(plan.pos(i)) && next.target(i) == plan.target(i);
        if (same) {
            planKey = key();
            return true;
        }
        finish(client, "stopped: the schematic, its place or its turn changed while building; start it again.", true);
        return false;
    }

    private void preparePlan(Minecraft client) {
        SchematicSettings s = settings();
        plan = BuildPlan.of(schematic, new BlockPos(s.originX, s.originY, s.originZ), mirror(s.mirror), rotation(s.rotation), s.redstoneLast);
        opened = null;
        openedView = null;
        planKey = key();
        planLevel = client.level;
        shortageReported = false;
        nextIndex = -1;
        sealStamp = new long[plan.size()];
        seal = new byte[plan.size()];
        laterStamp = new long[2 * plan.size()];
        later = new byte[2 * plan.size()];
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
        // Each decision a fresh hand: the spots on a block clicked itself, or poured against, are other ones than last time.
        PlacementSolver.newHand();
        ClientLevel level = client.level;
        long started = System.nanoTime();
        boolean creative = player.hasInfiniteMaterials();
        Materials materials = Materials.of(player.getInventory());
        int done = plan.count(BuildPlan.Status.DONE);
        if (done != lastDone) {
            lastDone = done;
            lastProgress = tick;
        }
        // Every look and walk is worked out from where the player stands: in the air after a jump, they land first.
        if (!player.onGround() && !player.isInWater() && !player.onClimbable()) return;
        if (!creative && eat(client, player)) return;
        if (removeUsedHelper(client, player)) return;
        if (repairMisplaced(client, player)) return;
        if (refill(client, player, false)) return;
        if (restockAfterWalk != null) {
            Item wanted = restockAfterWalk;
            restockAfterWalk = null;
            // Helper blocks are fetched while some are still carried (helperToFetch): the walk to open ground was for them too.
            boolean wants = materials.loose(wanted) == 0 || helperItems.contains(wanted) && helpersLoose(player) < HELPERS_LOW;
            if (!creative && wants && materials.total(wanted) > 0) {
                startRestock(client, player, wanted, materials);
                return;
            }
        }
        // Standing where a box goes in, blocks of the layer that are only in boxes come out now, before a walk takes the
        // player up onto the build, where there may be no room for a box at all. Only for a layer built from higher up
        // than a drop gets back down from: one at about the player's height is built from the ground round it, where a
        // box goes in whenever its blocks come up.
        int aheadAt = aheadY();
        if (!creative && settings().walk && tick - aheadChecked >= 20 && aheadAt != Integer.MIN_VALUE
                && aheadAt + 1 - player.getY() > Navigator.DROP) {
            aheadChecked = tick;
            Item ahead = restockAhead(player, materials);
            if (ahead != null && !boxSpots(player, Navigator.standingAt(level, player).floor().above(), player.position()).isEmpty()) {
                if (debug()) LOGGER.info("Taking {} out of a shulker box while there is room for one here", name(ahead));
                startRestock(client, player, ahead, materials);
                if (restock != Restock.NONE || swapFrom >= 0 || walker.active()) return;
            }
        }
        // Helper blocks running low: more out of a box before they run out mid-climb.
        Item fetch = creative || !settings().useShulkers ? null : helperToFetch(player, materials);
        // While some are still carried, only where a box goes in right here: a walk off to open ground for them from the
        // place a walk just came to, for the blocks there, sends the player away before any of those go in.
        if (fetch != null && helpersLoose(player) > 0
                && boxSpots(player, Navigator.standingAt(level, player).floor().above(), player.position()).isEmpty()) {
            fetch = null;
        }
        if (fetch != null) {
            // At most once a minute: with the inventory full, a box opened for them gives none, and is opened again and
            // again otherwise.
            helperFetchTick = tick;
            startRestock(client, player, fetch, materials);
            if (restock != Restock.NONE || swapFrom >= 0 || walker.active()) return;
            // No box could go down for them, as with no room beside the player: the build goes on, and tries again later.
            helperFetchTick = tick;
        }
        // The layer: the lowest with a block that can go in now; lower blocks still waiting need something above them.
        // Each layer lists its blocks in placing order, so the first block found also sets the group.
        boolean byGroup = settings().redstoneLast;
        int[] layer = null;
        int layerNumber = 0, number = 0, group = 0;
        boolean lift = sealsLifted;
        int heldY = plan.heldLayerY();
        if (plan.holding()) {
            if (holdSince < 0) holdSince = tick;
            if (tick - holdSince > 2400) {
                // Two minutes and still not taken away: the layers go on, and the helper waits for the cleanup.
                if (debug()) LOGGER.info("Helper blocks in the build's water not taken away in time: the layers above go on");
                plan.releaseHolds();
                heldY = Integer.MAX_VALUE;
            }
        } else {
            holdSince = -1;
        }
        int heldNumber = 0;
        // A block broken to open a look at the one beside it goes in again once that one is in.
        unblocking.values().removeIf(u -> {
            BuildPlan.Status stuck = plan.update(level, u.stuck());
            return stuck != BuildPlan.Status.PENDING && stuck != BuildPlan.Status.UNLOADED || tick - u.since() > 6000;
        });
        for (Map.Entry<Integer, int[]> entry : plan.layers().entrySet()) {
            number++;
            if (entry.getKey() > heldY) {
                // A helper block stands in water the build has in a layer below, climbed on out of a flooded shaft: built
                // over, it would stay there out of reach, holding the water back. That layer stays the one built until
                // the helper is gone (see walkToEscapeHelper).
                layer = plan.layers().get(heldY);
                if (heldY != layerY) {
                    sealsLifted = false;
                    orderLifted = false;
                    // Walks, misses and useless places counted in the layer left: none of them is about this one.
                    walksSinceClick = 0;
                    liftedFor.clear();
                    arrivalMisses.clear();
                    uselessSpots.clear();
                    approached.clear();
                    lastWalkEnd = null;
                    shutIn.clear();
                    traps.clear();
                    badSteps.clear();
                    stepFailures.clear();
                    stepFailedAt.clear();
                    carefulSteps.clear();
                    missedFrom.clear();
                    carriedFrom.clear();
                }
                layerY = heldY;
                layerNumber = heldNumber;
                break;
            }
            if (entry.getKey() == heldY) heldNumber = number;
            int first = firstReady(player, entry.getValue(), lift, started);
            if (first == -1 && !lift) {
                // Only blocks that would shut in a neighbor are left: one of them goes in after all.
                first = firstReady(player, entry.getValue(), true, started);
                if (first >= 0) {
                    lift = true;
                    // A lift too: blocks waiting for an up-facing neighbour still get their few minutes.
                    if (sealsLiftedAt == UNLIFTED) sealsLiftedAt = tick;
                }
            }
            if (first == -2) { show("Working out the build order", false); return; }
            if (first == -1 && unblock(client, player, entry.getValue(), started)) return;
            if (first < 0) continue;
            layer = entry.getValue();
            group = byGroup ? BlockRules.group(plan.target(first)) : 0;
            if (entry.getKey() != layerY) {
                pickupSweep = layerY != Integer.MIN_VALUE;
                sealsLifted = false;
                orderLifted = false;
                walksSinceClick = 0;
                liftedFor.clear();
                arrivalMisses.clear();
                uselessSpots.clear();
                approached.clear();
                lastWalkEnd = null;
                shutIn.clear();
                traps.clear();
                badSteps.clear();
                stepFailures.clear();
                stepFailedAt.clear();
                carefulSteps.clear();
                missedFrom.clear();
                carriedFrom.clear();
            }
            layerY = entry.getKey();
            layerNumber = number;
            break;
        }
        if (layer == null) {
            // Only blocks broken to get out of a pocket are left: out first, then they go in again.
            if (!brokeOut.isEmpty()) {
                if (settings().walk && walkOut(client, player)) return;
                brokeOut.clear();
                nextAction = System.nanoTime();
                return;
            }
            if (settings().walk && walkToRepair(client, player)) return;
            if (lastRound(player)) {
                nextAction = System.nanoTime();
                return;
            }
            if (cleanUp(client, player)) return;
            finishBuild(client);
            return;
        }
        Vec3 eye = player.getEyePosition();
        if (plan.holding() && layerY == plan.heldLayerY() && settings().walk && walkToEscapeHelper(client, player)) return;
        if (!brokeOut.isEmpty()) {
            // Out: on top of the layer or off the build, or anywhere the pocket didn't reach with a way on from there. A way
            // out found from inside, as soon as the block is gone, is no reason yet to put it back.
            Navigator.Node here = Navigator.standingAt(level, player);
            boolean out = player.getY() >= layerY + 1 - 1e-3 || offBuild(player.position())
                    || !pocketPlaces.contains(here.key()) && exitFrom(player, level, here, null) != null;
            if (!out) outSince = -1;
            else if (outSince < 0) outSince = tick;
            // Once out, the helper blocks of the climb out come away first: with the block back in they would be shut in. A
            // walk brings each into view in turn; one no walk does is left for the cleanup, and two minutes is the most it
            // waits.
            if (out && settings().walk && walkToEscapeHelper(client, player)) return;
            if (out && (escapeHelpers.isEmpty() || tick - outSince > 2400)) {
                brokeOut.clear();
                pocketPlaces.clear();
                outSince = -1;
            }
        }
        List<Integer> candidates = new ArrayList<>();
        for (int i : layer) {
            BuildPlan.Status st = plan.status(i);
            if (st != BuildPlan.Status.PENDING && st != BuildPlan.Status.UNLOADED) continue;
            // Broken to get out of where the player was shut in: back in once they are out, not to shut them in again.
            // Broken to open a look at a block beside: back in once that one is.
            if (brokeOut.contains(i) || unblocking.containsKey(i)) continue;
            // Water, lava and kelp wait until every other block of their layer, and of those below, is in.
            if (plan.wetWaits(i)) continue;
            if (byGroup && !inOrder(player, i, group, lift, started)) continue;
            candidates.add(i);
        }
        // Of the blocks in reach, those in front go first, as a player places what they look at before turning round:
        // a block behind costs about what bringing another item into the hotbar does, so an item is swapped in once its
        // blocks here run out rather than back and forth, and the view turns round once rather than to and fro.
        // Blocks out of reach follow, nearest first.
        Set<Item> atHand = new HashSet<>();
        for (int slot = 0; slot < Inventory.getSelectionSize(); slot++) atHand.add(player.getInventory().getItem(slot).getItem());
        double inReach = (player.blockInteractionRange() + 1) * (player.blockInteractionRange() + 1);
        Vec3 view = player.getViewVector(1.0f);
        Map<Integer, Double> order = new HashMap<>();
        for (int i : candidates) {
            Vec3 to = Vec3.atCenterOf(plan.pos(i)).subtract(eye);
            double away = to.lengthSqr();
            if (away > inReach) {
                order.put(i, 1000 + away);
                continue;
            }
            double turn = away < 1e-6 ? 0 : Math.toDegrees(Math.acos(Mth.clamp(to.normalize().dot(view), -1, 1)));
            boolean inHand = creative || atHand.contains(plan.item(i));
            order.put(i, turn / 60 + (inHand ? 0 : 2) + Math.sqrt(away) * 0.3);
        }
        candidates.sort(Comparator.comparingDouble(order::get));
        // Back to front, as a player builds: a block in reach that stands in the line from the eye to a farther one would
        // take that block's only look, and a walk would come back for it. The farther one, when it has a chance from here
        // (in reach, its item in hand, not found without a click from here), goes in just before it; the rest keep their
        // order, so no turn or item swap comes in that wasn't there.
        double range = player.blockInteractionRange();
        List<Integer> backToFront = new ArrayList<>(candidates.size());
        Set<Integer> movedUp = new HashSet<>();
        for (int p = 0; p < candidates.size(); p++) {
            int i = candidates.get(p);
            if (movedUp.contains(i)) continue;
            if (order.get(i) < 1000 && plan.kind(i) == BlockRules.Kind.PLACE) {
                AABB cell = new AABB(plan.pos(i)).deflate(0.05);
                double away = Vec3.atCenterOf(plan.pos(i)).distanceToSqr(eye);
                for (int q = p + 1; q < candidates.size(); q++) {
                    int j = candidates.get(q);
                    if (order.get(j) >= 1000) break;
                    Vec3 behind = Vec3.atCenterOf(plan.pos(j));
                    double far = behind.distanceToSqr(eye);
                    if (movedUp.contains(j) || plan.kind(j) != BlockRules.Kind.PLACE || far <= away || far > range * range
                            || !creative && !atHand.contains(plan.item(j)) || noClickHere.containsKey(j)
                            || missedHere.contains(j) && player.blockPosition().equals(missedAt) || cell.clip(eye, behind).isEmpty()) {
                        continue;
                    }
                    backToFront.add(j);
                    movedUp.add(j);
                }
            }
            backToFront.add(i);
        }
        candidates.clear();
        candidates.addAll(backToFront);
        if (settings().walk && wetSoon(player.level(), player.blockPosition(), recentPours(), new HashMap<>())
                && !wetPourStand(player.level(), Navigator.standingAt(player.level(), player).feetCell(), null)
                && !stillWater(player.level(), Navigator.standingAt(player.level(), player))) {
            // Water running past pushes the player about and spoils every look: out of it first, onto dry ground.
            nextAction = System.nanoTime() + 250_000_000L;
            if (planWalk(client, player, candidates, materials, layerNumber, lift)) return;
        }
        // On a narrow top with a drop beside it, a closed gate's or a fence's, a little drift brings the player down off it,
        // into a pit or a shaft there may be no way out of: crouched there, as a player stands on one, and only clicks
        // made crouching. Walking on stands up again.
        boolean edge = settings().walk && onNarrowEdge(player);
        if (edge && !player.isShiftKeyDown()) {
            stance(client, true);
            return;
        }
        double reach = player.blockInteractionRange();
        missing.clear();
        PlacementSolver.Problem seen = null;
        int nearest = -1;
        boolean sealing = false;
        boolean fetchTried = false;
        if (filledFrom != null && tick - filledAt > 10) {
            // Vanilla makes a new source there within a few ticks, where it does at all.
            if (!level.getFluidState(filledFrom).isSource()) finiteSources.add(filledFrom.asLong());
            filledFrom = null;
            waterTick = -1000;
        }
        List<Integer> helpersHeld = new ArrayList<>();
        // Water held back a moment after a block broke, for its drop to show up: the builder waits rather than walks off.
        boolean settling = false;
        // Still sliding to rest after a walk, the player moves a little each tick: what has no click stays known until they
        // are still, else each tick starts the row over and the next walk waits seconds for it. Once still, once more.
        boolean sliding = player.getDeltaMovement().horizontalDistanceSqr() > 1e-5;
        double drift = noClickFrom == null ? Double.POSITIVE_INFINITY : player.position().distanceToSqr(noClickFrom);
        if (drift > (sliding ? NO_CLICK_SLIDE * NO_CLICK_SLIDE : 1e-6) || player.isShiftKeyDown() != noClickCrouch || tick - noClickTick > 40) {
            forgetNoClicks();
            noClickFrom = player.position();
            noClickCrouch = player.isShiftKeyDown();
            noClickTick = tick;
        }
        // A block whose going in would take the last look at a farther one still to go in here waits for it, as long as
        // something else goes in this tick; else it goes in after all.
        // Still where the last walk, made with the seals lifted, came to rest: the blocks it was made for keep the lift.
        boolean stayLifted = !liftedFor.isEmpty() && walkEnd != null && player.position().distanceToSqr(walkEnd.standing()) < 2.25;
        Aim held = null;
        int place = -1;
        for (int i : candidates) {
            place++;
            BlockPos pos = plan.pos(i);
            boolean far = Vec3.atCenterOf(pos).distanceTo(eye) > reach + 1;
            if (plan.status(i) == BuildPlan.Status.UNLOADED) {
                if (nearest < 0) nearest = i;
                if (far) break;
                continue;
            }
            byte support = support(player, i, started);
            if (support == 0) return;
            if (support != 1) {
                passedOver(i, "nothing to place it against yet");
                continue;
            }
            if (nearest < 0) nearest = i;
            if (far) break;
            if (!lift && !(stayLifted && liftedFor.contains(i))) {
                byte seals = seals(player, i, started);
                if (seals == 0) return;
                if (seals == 1) {
                    sealing = true;
                    passedOver(i, "it would shut in a neighbour, or waits for one (a seal)");
                    continue;
                }
            }
            else if (liftHolds(player, i)) {
                passedOver(i, "it waits for an up-facing block");
                continue;
            }
            if (missedHere.contains(i) && player.blockPosition().equals(missedAt)) {
                if (seen == null) seen = PlacementSolver.Problem.OUT_OF_REACH;
                passedOver(i, "its click missed from here before");
                continue;
            }
            PlacementSolver.Problem known = noClickHere.get(i);
            if (known != null) {
                if (seen == null || known == PlacementSolver.Problem.IN_THE_WAY) seen = known;
                passedOver(i, "no click from here a moment ago (" + known + ")");
                continue;
            }
            if (helperLater.contains(i)) {
                helpersHeld.add(i);
                passedOver(i, "its helper block waits until nothing else goes in");
                continue;
            }
            if (System.nanoTime() - started > PLAN_NANOS) {
                if (held != null) startAim(client, player, held);
                return;
            }
            BlockState current = level.getBlockState(pos), target = plan.target(i);
            if (plan.kind(i) == BlockRules.Kind.FLUID) {
                // Just filled a bucket from it: it comes back by itself, or is found not to, in a moment.
                if (pos.equals(filledFrom)) continue;
                if (lift && debug() && tick - plantWaitLogged.getOrDefault(-1 - i, -1000L) > 200 && feedsPendingPlant(level, i, false)) {
                    plantWaitLogged.put(-1 - i, tick);
                    LOGGER.info("Pouring {} at {} with kelp under it still to go in: the seals are lifted", target, pos.toShortString());
                }
                Item bucket = BlockRules.item(target);
                if (!creative && materials.total(bucket) == 0) {
                    // Out of water buckets: an empty one is filled where water comes back, as a player does.
                    if (bucket == Items.WATER_BUCKET && !fetchTried && materials.total(Items.BUCKET) > 0) {
                        fetchTried = true;
                        if (fetchWater(client, player, materials, layerNumber)) return;
                    }
                    if (!missing.contains(bucket)) missing.add(bucket);
                    continue;
                }
                if (tick - brokeAt < DROPS_SETTLE) {
                    settling = true;
                    continue;
                }
                if (sweptLayer != layerY && settings().walk) {
                    // Before the layer's first water: helper blocks lying in the build would be carried off by it.
                    pickupSweep = true;
                    if (pickUpHelpers(client, player)) return;
                    sweptLayer = layerY;
                }
                // Crouched on a narrow top over a drop, as on a row of closed gates, the player stays so: a bucket emptied
                // against a face pours into the spot in front of it crouched or not, and the faces it is emptied against
                // hold no water themselves (PlacementSolver.pourFace).
                if (player.isShiftKeyDown() && !edge) {
                    stance(client, false);
                    return;
                }
                PlacementSolver.Click pour = PlacementSolver.pour(level, player, eye, pos, target.getFluidState().getType(), reach);
                if (pour == null) {
                    if (seen == null) seen = PlacementSolver.Problem.NO_VIEW;
                    if (walkEnd != null && walkExpects.contains(i)) arrivalMisses.merge(i, 1, Integer::sum);
                    if (debug() && walkExpects.remove(i)) LOGGER.info("Can't pour {} at {} from where the walk for it ended: no look", target, pos.toShortString());
                    continue;
                }
                if (floods(level, pos, player.position()) && !wetPourStand(level, Navigator.standingAt(level, player).feetCell(), pos)) {
                    // It would run where the player stands and push them off: poured from somewhere else.
                    if (seen == null) seen = PlacementSolver.Problem.OUT_OF_REACH;
                    noClickHere.put(i, PlacementSolver.Problem.OUT_OF_REACH);
                    if (debug() && walkExpects.remove(i)) LOGGER.info("Can't pour {} at {} from where the walk for it ended: it would run where you stand", target, pos.toShortString());
                    continue;
                }
                if (!creative && materials.loose(bucket) == 0) {
                    if (noBoxHere(bucket)) {
                        if (seen == null) seen = PlacementSolver.Problem.OUT_OF_REACH;
                        continue;
                    }
                    startRestock(client, player, bucket, materials);
                    return;
                }
                startAim(client, player, new Aim(Act.POUR, i, pour, bucket, pos, current, layerNumber));
                return;
            }
            if (plan.soaking(i) && !BlockRules.fillsInPlace(current)) {
                // In place but dry where the schematic has it full of water, and a chest or the like: clicked, it opens,
                // and crouched, a bucket pours in front of it. As a player does it, it is broken, the water poured into its
                // spot, and the block placed again into the water.
                if (edge) continue;
                if (drains.getOrDefault(i, 0) >= REPAIRS || !getsBack(player, current) || !safeToDrain(level, pos)) {
                    // Left dry, and counted as failed: the build goes on rather than wait for it.
                    if (debug()) LOGGER.info("Leaving {} at {} dry: it can't be broken to go in again into water", target, pos.toShortString());
                    plan.giveUp(i);
                    plan.update(level, i);
                    continue;
                }
                if (!inView(player, eye, pos, reach - 0.25)) {
                    if (seen == null) seen = PlacementSolver.Problem.OUT_OF_REACH;
                    noClickHere.put(i, PlacementSolver.Problem.OUT_OF_REACH);
                    continue;
                }
                drains.merge(i, 1, Integer::sum);
                drainedAt.put(i, tick);
                plan.drain(i);
                Item item = BlockRules.item(target);
                if (!creative && item != null) repairDrops.add(item);
                if (debug()) LOGGER.info("Breaking {} at {} to place it again into water", target, pos.toShortString());
                startBreak(pos);
                repairing = i;
                return;
            }
            if (current.canBeReplaced() && current.getFluidState().isEmpty() && waterFirst(i, layer)) {
                // Full of water in the schematic, and one a bucket can't fill once in, a chest or a wooden trapdoor, with
                // nothing else of its layer left to stand on it as in: the water goes into its spot first and the block into
                // that, rather than in dry, broken again and placed a second time (the bubble shaft's trapdoor, s37-43).
                if (debug()) LOGGER.info("{} at {} goes into water: its spot gets the water first", target, pos.toShortString());
                plan.drain(i);
            }
            if (plan.soaking(i) || plan.needsPour(i)) {
                // A block in place but dry where the schematic has it full of water gets a bucket emptied onto it; the spot
                // of one broken for that gets the water before the block goes in again. Either goes in with the layer's
                // other water, as the water runs out of it as from a source.
                Item bucket = Items.WATER_BUCKET;
                if (!creative && plan.plant(i) && lowOnWater(player, materials) && poolToMake(level, candidates)) {
                    // Few buckets left and no water about that comes back: the build's water that makes a pool goes in
                    // first, a source beside a source, to fill the rest from; a kelp spot's bucket would be one less for it.
                    passedOver(i, "a pool of water to fill buckets from goes in first");
                    continue;
                }
                if (!creative && materials.total(bucket) == 0) {
                    // Out of water buckets: an empty one is filled where water comes back, as for the build's own water. A
                    // dry kelp spot takes a bucket of its own (da-45), more than the build's sources hand out.
                    if (!fetchTried && materials.total(Items.BUCKET) > 0) {
                        fetchTried = true;
                        if (fetchWater(client, player, materials, layerNumber)) return;
                    }
                    if (!missing.contains(bucket)) missing.add(bucket);
                    continue;
                }
                if (!creative && waitsForDrop(level, i, materials)) {
                    // What breaking it dropped is picked up first: the water would carry it off.
                    if (seen == null) seen = PlacementSolver.Problem.OUT_OF_REACH;
                    continue;
                }
                if (tick - brokeAt < DROPS_SETTLE) {
                    settling = true;
                    continue;
                }
                // A block filled where it stands takes the click standing; water poured into a spot, as for kelp, doesn't.
                if (edge && plan.soaking(i)) continue;
                if (player.isShiftKeyDown() && !edge) {
                    stance(client, false);
                    return;
                }
                PlacementSolver.Click pour = plan.soaking(i) ? PlacementSolver.fill(player, eye, pos, reach)
                        : PlacementSolver.pour(level, player, eye, pos, Fluids.WATER, reach);
                if (pour == null) {
                    if (debug() && tick - pourLogged.getOrDefault(i, -1000L) > 200) {
                        pourLogged.put(i, tick);
                        LOGGER.info("No pour into {} at {} from here: {}", target.getBlock().getName().getString(), pos.toShortString(),
                                PlacementSolver.pourable(player, pos, Fluids.WATER) ? "no face beside it to pour against is in view"
                                        : "no face beside it to pour against at all");
                    }
                    if (seen == null) seen = PlacementSolver.Problem.NO_VIEW;
                    continue;
                }
                if (debug() && plan.plant(i)) LOGGER.info("{} at {} is dry: water poured in first", target.getBlock().getName().getString(),
                        pos.toShortString());
                if (plan.plant(i) && plan.needsPour(i) && player.getY() < pos.getY() - 0.01) {
                    // Never from under a kelp spot (see placeableFrom): from somewhere level with it or above.
                    if (seen == null) seen = PlacementSolver.Problem.OUT_OF_REACH;
                    noClickHere.put(i, PlacementSolver.Problem.OUT_OF_REACH);
                    continue;
                }
                if (floods(level, pos, player.position())
                        && (plan.soaking(i) || !wetPourStand(level, Navigator.standingAt(level, player).feetCell(), pos))) {
                    if (seen == null) seen = PlacementSolver.Problem.OUT_OF_REACH;
                    noClickHere.put(i, PlacementSolver.Problem.OUT_OF_REACH);
                    continue;
                }
                if (!creative && materials.loose(bucket) == 0) {
                    if (noBoxHere(bucket)) {
                        if (seen == null) seen = PlacementSolver.Problem.OUT_OF_REACH;
                        continue;
                    }
                    startRestock(client, player, bucket, materials);
                    return;
                }
                startAim(client, player, new Aim(Act.POUR, i, pour, bucket, pos, current, layerNumber));
                return;
            }
            if (BlockRules.needsTool(current, target)) {
                // Placed as dirt: tilled into farmland with a hoe, or made a path with a shovel, as a player makes them.
                if (edge) continue;
                Item tool = carriedTool(player, target);
                if (tool == null) {
                    Item wanted = target.is(Blocks.FARMLAND) ? Items.WOODEN_HOE : Items.WOODEN_SHOVEL;
                    if (!missing.contains(wanted)) missing.add(wanted);
                    continue;
                }
                PlacementSolver.Click till = PlacementSolver.till(player, eye, pos, reach);
                if (till == null) {
                    if (seen == null) seen = PlacementSolver.Problem.OUT_OF_REACH;
                    noClickHere.put(i, PlacementSolver.Problem.OUT_OF_REACH);
                    continue;
                }
                startAim(client, player, new Aim(Act.TILL, i, till, tool, pos, current, layerNumber));
                return;
            }
            if (current.getBlock() == target.getBlock() && BlockRules.missing(current, target) == 0) {
                // A chest waiting for its other half needs nothing; clicking it would only open it.
                if (BlockRules.adjustClicks(current, target) == 0 || edge) continue;
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
                passedOver(i, "none left");
                continue;
            }
            int index = i;
            PlacementSolver.Result result = PlacementSolver.find(player, pos, target, new ItemStack(item), reach, at -> unclickable(at, index));
            // A block the last walk was made for that doesn't go in on arriving: counted once a walk, with or without the log.
            boolean expectedHere = result.click() == null && walkEnd != null && walkExpects.remove(i);
            if (expectedHere) arrivalMisses.merge(i, 1, Integer::sum);
            if (expectedHere && result.problem() == PlacementSolver.Problem.IN_THE_WAY) {
                // The walk came to rest a little off the middle, into the spot of a block it was made for, as in a row of
                // blocks still to go in: never planned from there for that block again, or the same walk is made over and over.
                missedFrom.add(Navigator.pairKey(walkEnd.key(), i));
            }
            if (result.click() == null && debug()) {
                LOGGER.info("Can't place {} at {} yet: {}", target, pos.toShortString(), result.problem());
                if (expectedHere) {
                    // The walk here was planned on a look at it: from the middle of where it was to end, and from where it did.
                    LongPredicate notThere = at -> unclickable(at, index);
                    double planned = player.blockInteractionRange() - 0.2;
                    PlacementSolver.Click middle = PlacementSolver.fromSpot(player, walkEnd.standing(), pos, target, new ItemStack(item), planned, notThere);
                    PlacementSolver.Click here = PlacementSolver.fromSpot(player, player.position(), pos, target, new ItemStack(item), reach, notThere);
                    LOGGER.info("  expected from the walk to {} {} {}: from its middle {}, from the feet at {} ({} off it, {} between) {}",
                            walkEnd.x(), walkEnd.feet(), walkEnd.z(), middle == null ? "no look" : "a look", player.position(),
                            String.format(Locale.ROOT, "%.2f", player.position().subtract(walkEnd.standing()).horizontalDistance()),
                            String.format(Locale.ROOT, "%.2f", player.getY() - walkEnd.feet()), here == null ? "no look" : "a look");
                }
            }
            if (result.click() == null) {
                BlockPos helper = helperSpot.get(i);
                if (result.problem() == PlacementSolver.Problem.NO_SUPPORT && helper != null) {
                    // A helper the block then goes in against from here goes down now. One it wouldn't waits until nothing
                    // else in reach goes in: a block put in beside it first may hold it, and no helper is needed at all.
                    if (!goesInAgainst(player, i, helper, item, reach)) {
                        helperLater.add(i);
                        helpersHeld.add(i);
                        continue;
                    }
                    if (placeHelper(client, player, i, helper, layerNumber)) return;
                    if (seen == null) seen = PlacementSolver.Problem.OUT_OF_REACH;
                    noClickHere.put(i, PlacementSolver.Problem.OUT_OF_REACH);
                    continue;
                }
                if (seen == null || result.problem() == PlacementSolver.Problem.IN_THE_WAY) seen = result.problem();
                noClickHere.put(i, result.problem());
                continue;
            }
            if (edge && !result.click().crouch()) {
                if (seen == null) seen = PlacementSolver.Problem.OUT_OF_REACH;
                noClickHere.put(i, PlacementSolver.Problem.OUT_OF_REACH);
                passedOver(i, "its click is a standing one, on a narrow top over a drop");
                continue;
            }
            if (!wayOut(player, i)) {
                // It would wall the player in: other blocks first, or a walk out to place it from outside. Unlike a
                // block that would only shut in a neighbor, this never goes in for want of anything else: shut in, the
                // player can build nothing more.
                noClickHere.put(i, PlacementSolver.Problem.OUT_OF_REACH);
                passedOver(i, "it would wall you in");
                continue;
            }
            if (!creative && materials.loose(item) == 0) {
                if (waitsForRoom(item, materials)) {
                    passedOver(i, "none carried, and no room for it in the inventory a moment ago (" + materials.freeSlots() + " slots free)");
                    continue;
                }
                if (noBoxHere(item)) {
                    passedOver(i, "none carried, and no room for a box a moment ago");
                    continue;
                }
                startRestock(client, player, item, materials);
                return;
            }
            // A walk's helper block beside it in a spot the build shuts in: taken away first if it can be from here, as this
            // block may be the one that closes the spot over it. So too its own helper block when it goes in against something
            // else after all, a neighbour put in since: unused, and once this block covers it, walled in by the build round it
            // it is out of reach of everything but a look from under it, cleanup too.
            BlockPos clicked = result.click().hit().getBlockPos();
            for (Direction side : Direction.values()) {
                BlockPos beside = pos.relative(side);
                Helper helper = helpers.get(beside.asLong());
                if (helper == null || escapeHelpers.contains(beside.asLong())) continue;
                boolean unused = helper.serves() == i && !beside.equals(clicked);
                double helperReach = player.blockInteractionRange() - 0.25;
                if (unused) {
                    // Wherever its drop falls: it comes away now or never.
                    if (helpers.containsKey(beside.above().asLong()) || Navigator.standingAt(client.level, player).floor().equals(beside)
                            || missedMining(player, beside) || !inView(player, player.getEyePosition(), beside, helperReach)
                            || !safeToBreak(player, beside)) continue;
                } else if (helper.serves() >= 0 || !plan.enclosed(beside) || !reclaimable(player, beside, helperReach)) continue;
                if (debug()) LOGGER.info("{} at {} waits: first taking away the helper block at {}, {}", target, pos.toShortString(),
                        beside.toShortString(), unused ? "which it goes in without" : "which the build shuts in");
                startBreak(beside);
                return;
            }
            Aim aim = new Aim(Act.PLACE, i, result.click(), item, pos, current, layerNumber);
            if (held == null && System.nanoTime() - started < PLAN_NANOS) {
                int behind = takesLastLook(player, i, candidates.subList(place + 1, candidates.size()), atHandItems(player), reach);
                if (behind >= 0) {
                    if (debug()) LOGGER.info("{} at {} waits: it would take the last look at {} at {}", target, pos.toShortString(),
                            plan.target(behind), plan.pos(behind).toShortString());
                    held = aim;
                    continue;
                }
            }
            startAim(client, player, aim);
            return;
        }
        if (held != null) {
            // What it waited for didn't go in after all.
            startAim(client, player, held);
            return;
        }
        // Nothing in reach goes in without a helper: the helpers held back go down as before, in order.
        for (int i : helpersHeld) {
            BlockPos helper = helperSpot.get(i);
            if (helper != null && placeHelper(client, player, i, helper, layerNumber)) return;
            if (seen == null) seen = PlacementSolver.Problem.OUT_OF_REACH;
            noClickHere.put(i, PlacementSolver.Problem.OUT_OF_REACH);
            if (System.nanoTime() - started > PLAN_NANOS) return;
        }
        if (settling) {
            nextAction = System.nanoTime() + 100_000_000L;
            return;
        }
        nextAction = System.nanoTime() + 250_000_000L;
        int left = 0;
        for (int i : layer) if (plan.status(i) == BuildPlan.Status.PENDING || plan.status(i) == BuildPlan.Status.UNLOADED) left++;
        if (settings().walk && pickUpHelpers(client, player)) return;
        if (settings().walk && walkOut(client, player)) return;
        if (settings().walk && walkToRepair(client, player)) return;
        if (settings().walk && settings().redstoneLast && !orderLifted && walksSinceClick >= WALKS_WITHOUT_CLICK) {
            // Walk after walk for the blocks whose turn it is, and none of them goes in: each look counted from where a
            // walk was to end fails from where it does end. Those after them may go in first, as when none can be placed
            // at all: a hopper row a row of blocks is clicked against, say.
            orderLifted = true;
            walksSinceClick = 0;
            if (debug()) LOGGER.info("{} walks in layer {} and nothing placed: later blocks may go first", WALKS_WITHOUT_CLICK, layerNumber);
            nextAction = System.nanoTime();
            return;
        }
        if (settings().walk && orderLifted && walksSinceClick >= WALKS_WITHOUT_CLICK) {
            // Still nothing placed with the order lifted: the places the walks end on don't do. One more way, as when no
            // place to stand is found at all: helper blocks put into cells the build fills later, its water too, which
            // come away again after.
            walksSinceClick = 0;
            if (debug()) LOGGER.info("{} more walks in layer {} and still nothing placed: standing on helper blocks in the build's own cells",
                    WALKS_WITHOUT_CLICK, layerNumber);
            if (climbOut(client, player, candidates, materials, layerNumber, lift)) return;
        }
        // In a trench along a row of up-facing blocks: the next of them from a pit beside this one first, before a walk out
        // to whatever the last one let go in up there, and back down into a new pit for the next (v1-reg: three walks per
        // step along the row at x 11). What waited up there goes in on one walk along the row once the trench is done.
        if (settings().walk && digAlong(player, candidates, materials, layerNumber, lift, player.blockInteractionRange() - 0.2,
                System.nanoTime(), true)) return;
        // Before a walk off to blocks none of which is carried: where a box goes in right here, they come out of it first.
        // From where the walk ends there may be no room for one, a channel or the top of the build, and the walk is for
        // nothing (v4-full9, 21:26: down into the kelp channel for dispenser 3,70,9, none carried, "no room for a box").
        if (settings().walk && !creative && settings().useShulkers && fetchBeforeWalk(client, player, candidates, materials, lift)) return;
        if (settings().walk && planWalk(client, player, candidates, materials, layerNumber, lift)) return;
        // Twice in a row no place reached had a block in reach, with blocks left that wait for nothing (planWalk only
        // weighs those): shut in, as after a fall under the build. Climbing out through cells the build fills later is
        // tried at once, not after walks there are none to make.
        if (settings().walk && placeGuard(client, player, layerNumber)) return;
        if (settings().walk && noneInReach >= 2) {
            if (debug()) LOGGER.info("No place reached has a block in reach or a look, {} searches in a row: climbing out", noneInReach);
            noneInReach = 0;
            if (climbOut(client, player, candidates, materials, layerNumber, lift)) return;
        }
        if (settings().walk && planPit(client, player, candidates, materials, layerNumber, lift)) return;
        if (settings().walk && walkToSpareHelper(client, player)) return;
        if (settings().walk && breakOut(player)) return;
        // Before the blocks held back go in after all, which may shut the block in for good.
        if (unblockForStand(client, player)) return;
        if (sealing && !lift) {
            // Every block left in reach of a walk would shut in another: let them go in after all.
            sealsLifted = true;
            if (sealsLiftedAt == UNLIFTED) sealsLiftedAt = tick;
            nextAction = System.nanoTime();
            return;
        }
        if (settings().redstoneLast && !orderLifted) {
            // The blocks whose turn it is can't be placed from anywhere: those after them may go in first, until one
            // does. A block that can only be clicked onto a later one beside it then gets its face.
            orderLifted = true;
            if (debug()) LOGGER.info("Nothing in turn can be placed in layer {}: later blocks may go first", layerNumber);
            nextAction = System.nanoTime();
            return;
        }
        // Steps that failed once are left out of every walk in the layer. With no way on without them, they get another
        // try before anything is taken away: what made one fail, a climb begun off the middle or something in the way,
        // may be gone, and it may be the only way out.
        // Places whose click missed the same, and places the player was carried off from: tried again with them, when
        // there is no other.
        if (settings().walk && (!badSteps.isEmpty() || !missedFrom.isEmpty() || !carriedFrom.isEmpty()) && tick - badStepsRetried > 600) {
            // A step that failed twice, the second time maybe crouched, stays out for five minutes: else one bad spot on the
            // only way takes every walk there and uses up the failures that stop the build.
            badSteps.removeIf(step -> stepFailures.getOrDefault(step, 0) < 2 || tick - stepFailedAt.getOrDefault(step, 0L) > 6000);
            if (debug()) LOGGER.info("No way on without the steps that failed, {} clicks that missed and {} places carried off from: trying them again, but for {} that failed twice",
                    missedFrom.size(), carriedFrom.size(), badSteps.size());
            missedFrom.clear();
            carriedFrom.clear();
            badStepsRetried = tick;
            nextAction = System.nanoTime();
            return;
        }
        if (settings().walk && climbOut(client, player, candidates, materials, layerNumber, lift)) return;
        if (settings().walk && climbUpOut(client, player)) return;
        if (settings().walk && breakOutOfBuild(player)) return;
        // Last, from the top of a pillar of helper blocks with nothing in reach: down a block.
        if (settings().walk && stepDown(player)) return;
        // Blocks out of reach that nothing is left to place with are passed over by the walk: they are what the build
        // waits for, not a place to stand.
        if (!creative) {
            for (int i : candidates) {
                if (plan.status(i) != BuildPlan.Status.PENDING) continue;
                Item item = plan.item(i);
                if (item != null && materials.total(item) == 0 && !missing.contains(item)) missing.add(item);
            }
        }
        next = nearest < 0 ? null : plan.pos(nearest);
        nextIndex = nearest;
        String where = " in layer " + layerNumber + " of " + plan.layers().size() + " (y " + layerY + ")";
        if (!missing.isEmpty() && settings().fromOrders && tick - ordersTriedAt > ORDERS_RETRY_TICKS) {
            // Out of something: to the orders for it, those blocks first and then the rest the build still needs, as much
            // as fits.
            ordersTriedAt = tick;
            Map<Item, Integer> all = shortage(Materials.of(player.getInventory())), shortOf = new LinkedHashMap<>();
            for (Item item : missing) shortOf.put(item, Math.max(1, all.getOrDefault(item, 1)));
            all.forEach(shortOf::putIfAbsent);
            String why = orders.start(player, shortOf);
            if (why == null) {
                walker.stop(client);
                releaseKeys(client);
                show("Collecting " + names(missing) + " from your orders", false);
                return;
            }
            LOGGER.info("No trip to your orders: {}", why);
        }
        if (!missing.isEmpty()) {
            show("Out of " + names(missing) + where + ": put more in your inventory or a shulker box in it"
                    + (orders.result().isEmpty() ? "" : "; orders: " + orders.result()), true);
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
        for (int i : layer) {
            // Broken to get out of a pocket: its layer is done for now, and the build goes on with the next.
            if (plan.wetWaits(i) || brokeOut.contains(i) || unblocking.containsKey(i)) continue;
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
                else if (liftHolds(player, i)) continue;
            } else if (st != BuildPlan.Status.UNLOADED) {
                // An unloaded block may well be missing too; the layer waits until the player is near enough to see it.
                continue;
            }
            return i;
        }
        return -1;
    }

    /**
     * Whether block {@code i} waits for a block facing up beside it, still to go in, that is only placed with a look up
     * from below past this spot, as the ice ring round a column of up-facing observers took every such look. Only over
     * open space, where that look comes up from: one over a block can't be looked past from below, and is wanted in as
     * the face the click goes on. Unlike the other seals, it holds when they are lifted, until {@link #UP_WAIT_TICKS}
     * after: once in, such a block shuts the other in for good.
     */
    private boolean waitsForUpFacing(Level level, int i) {
        return upWait(level, i) != 0;
    }

    /**
     * As {@link #waitsForUpFacing}: 0 no, 1 for one beside it in its own layer, 2 for one a layer or two up, which can't
     * be tried until the build gets there.
     */
    private int upWait(Level level, int i) {
        if (plan.kind(i) != BlockRules.Kind.PLACE) return 0;
        BlockPos pos = plan.pos(i);
        // A helper block of the builder's own counts as open: it comes away again, as v4-e's bridge under 1,70,21 did not.
        if (!openForLook(level, pos.below()) || !level.getBlockState(pos).canBeReplaced()) return 0;
        boolean upFacing = BlockRules.placedLookingUp(plan.target(i));
        // One beside it, or one or two layers up beside the cells over it: a column of them stacked, each a layer higher,
        // takes its look up past this spot from as far below as the eye is.
        for (int up = 0; up <= 2; up++) {
            if (up > 0 && !openForLook(level, pos.above(up))) break;
            // A row of them goes in in any order: each is placed from below, not past the other.
            if (up == 0 && upFacing) continue;
            for (Direction side : Direction.Plane.HORIZONTAL) {
                BlockPos at = pos.relative(side).above(up);
                int j = plan.indexOf(at);
                if (j < 0 || plan.status(j) != BuildPlan.Status.PENDING || !BlockRules.placedLookingUp(plan.target(j))) continue;
                if (!level.getBlockState(at).canBeReplaced()) continue;
                if (debug() && tick - upWaitLogged.getOrDefault(i, -UP_WAIT_TICKS) > 200) {
                    upWaitLogged.put(i, tick);
                    LOGGER.info("{} at {} waits: it would take the look up at {} at {}", plan.target(i).getBlock().getName().getString(),
                            pos.toShortString(), plan.target(j).getBlock().getName().getString(), at.toShortString());
                }
                return up == 0 ? 1 : 2;
            }
        }
        return 0;
    }


    /**
     * Whether block {@code i} waits for a block one or two cells off it, still to go in, that only takes its state from a
     * level look past this spot: a piston facing south takes a look north from south of it, and a row of fence gates
     * there, open ones too, puts an outline in every such look but the steep ones. Only with the cell between them open,
     * else this one takes nothing more, and only for a block with an outline. A plain seal, lifted with the others: a
     * level look still comes past at a slant, and two such blocks each in the other's way would wait for good.
     */
    private boolean waitsForChannel(Level level, int i) {
        if (plan.kind(i) != BlockRules.Kind.PLACE) return false;
        BlockPos pos = plan.pos(i);
        BlockState own = plan.target(i);
        if (!level.getBlockState(pos).canBeReplaced() || own.getShape(level, pos).isEmpty()) return false;
        for (Direction eye : Direction.Plane.HORIZONTAL) {
            for (int k = 1; k <= 2; k++) {
                BlockPos at = pos.relative(eye.getOpposite(), k);
                if (k == 2 && !openForLook(level, pos.relative(eye.getOpposite()))) break;
                int j = plan.indexOf(at);
                if (j < 0 || plan.status(j) != BuildPlan.Status.PENDING || BlockRules.inLookChannel(pos, at, plan.target(j)) != k) continue;
                // Facing each other, each in the other's way: neither waits, the order they go in in decides.
                if (BlockRules.inLookChannel(at, pos, own) > 0 || !level.getBlockState(at).canBeReplaced()) continue;
                if (debug() && tick - channelLogged.getOrDefault(i, -UP_WAIT_TICKS) > 200) {
                    channelLogged.put(i, tick);
                    LOGGER.info("{} at {} waits: it would stand in the level look at {} at {}", own.getBlock().getName().getString(),
                            pos.toShortString(), plan.target(j).getBlock().getName().getString(), at.toShortString());
                }
                return true;
            }
        }
        return false;
    }

    private final Map<Integer, Long> channelLogged = new HashMap<>();

    /** Whether a look can come past {@code pos}: nothing there, or only a helper block of the builder's that comes away. */
    private boolean openForLook(Level level, BlockPos pos) {
        return level.getBlockState(pos).canBeReplaced() || helpers.containsKey(pos.asLong());
    }

    /**
     * Whether block {@code i} still waits with the seals lifted for this decision alone, as when only blocks held back are
     * left in its layer: for an up-facing neighbour (see {@link #upWaitHolds}), or, a water source, for the kelp under it
     * (see {@link #feedsPendingPlant}) until the seals are lifted for good, after the walks found nothing.
     */
    private boolean liftHolds(LocalPlayer player, int i) {
        return upWaitHolds(player.level(), i)
                || !sealsLifted && plan.kind(i) == BlockRules.Kind.FLUID && floodsLevelLook(player, i, false);
    }

    /** With the seals lifted, block {@code i} still waits for an up-facing neighbour, for {@link #UP_WAIT_TICKS} after. */
    private boolean upWaitHolds(Level level, int i) {
        int wait = upWait(level, i);
        // One a layer or two up isn't timed out: it can't even be tried before the build reaches its layer, and once this
        // block is in it never can (v4-g let the ice in after five minutes, before the observers a layer up).
        return wait == 2 || wait == 1 && (sealsLiftedAt == UNLIFTED || tick - sealsLiftedAt < UP_WAIT_TICKS);
    }

    /** How long, after the seals are lifted, a block still waits for an up-facing neighbour: the walks get a few more tries. */
    private static final long UP_WAIT_TICKS = 6000;
    private static final long UNLIFTED = Long.MIN_VALUE / 2;
    /**
     * When the seals were first lifted, by either way, since the build began or an up-facing block last went in; UNLIFTED
     * until then. Not cleared when the layer changes: a layer below that waits sends the build up and back, and the
     * minutes would start again each time.
     */
    private long sealsLiftedAt = UNLIFTED;
    private final Map<Integer, Long> upWaitLogged = new HashMap<>();

    /**
     * Whether placing block {@code i} now would shut in a block beside it that is still to go in: one that can go in
     * now, against what is there, and no longer could with this one in place, every look at it then coming through a
     * closed side. Such a block waits for its neighbor. 1 yes, 2 no, 0 out of time.
     */
    private byte seals(LocalPlayer player, int i, long started) {
        if (plan.kind(i) == BlockRules.Kind.FLUID) {
            return feedsPendingPlant(player.level(), i, true) || floodsLevelLook(player, i, true) ? (byte) 1 : 2;
        }
        if (plan.kind(i) != BlockRules.Kind.PLACE) return 2;
        Level level = player.level();
        BlockPos pos = plan.pos(i);
        if (!level.getBlockState(pos).canBeReplaced()) return 2;
        // Worked out afresh each time, never kept with the stamp: the block waited for is two cells off, or a layer or two
        // up, outside the cells the stamp reads, and once it went in the wait stayed as kept. A chest waiting on the level
        // look at an observer two cells off waited for good, and the lone half beside it with it (slab farm west cut).
        if (waitsForUpFacing(level, i) || waitsForChannel(level, i)) return 1;
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
            // A glow lichen, vine or vein that grows on this block's side can't be shut in by it: that face of it needs
            // this block in first, and is clicked onto it.
            BlockState grows = plan.target(j);
            if (grows.getBlock() instanceof MultifaceBlock && MultifaceBlock.hasFace(grows, side.getOpposite())) continue;
            // Kelp in a spot still dry goes in by a pour into its spot first (da-45): a block put beside it only adds a face
            // to pour against, and shuts nothing in (v4-clean: the glass 18,69,8 and the like held back for the kelp beside
            // them, the kelp waiting for its layer's dry blocks, layer 5 passed over).
            if (plan.plant(j) && plan.needsPour(j)) continue;
            byte support = support(player, j, started);
            if (support == 0) return 0;
            if (support != 1 || helperSpot.containsKey(j)) continue;
            Item item = BlockRules.item(plan.target(j));
            if (item == null) continue;
            if (view == null) view = new Overlay(level, Map.of(pos.asLong(), plan.target(i)));
            int neighbor = j;
            // Made sure of with finer looks: two blocks side by side each held back for the other would go in in whichever
            // order came first, and the one a slanting look alone can reach would be shut in.
            if (!PlacementSolver.supported(player, view, at, plan.target(j), new ItemStack(item), a -> unclickable(a, neighbor))
                    && !PlacementSolver.supportedFinely(player, view, at, plan.target(j), new ItemStack(item), a -> unclickable(a, neighbor))) {
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
        if (orderLifted) return true;
        for (int j : plan.earlierNear(i)) {
            BuildPlan.Status st = plan.status(j);
            if (st == BuildPlan.Status.UNLOADED) return false;
            if (st != BuildPlan.Status.PENDING || plan.soaking(j) || plan.refilling(j)) continue;
            byte support = support(player, j, started);
            // Not worked out yet: it waits for a later decision.
            if (support == 0) return false;
            if (support != 1) continue;
            byte held = heldByLater(player, i, j, own, started);
            if (held == 0) return false;
            if (held == 1) continue;
            if (lift) return false;
            byte seals = seals(player, j, started);
            if (seals != 1) return false;
        }
        return true;
    }

    /**
     * Whether block {@code j}, of an earlier group than block {@code i}'s {@code own}, can only be clicked onto blocks of
     * that group or a later one: a hopper feeding a dispenser or a crafter beside it, say. Such a block goes in after
     * them in any case, so {@code i} doesn't wait for it; else each would hold back the next of the other kind, and they
     * would go in one of each at a time, from either side, a walk for every block. An observer waits all the same, as
     * a block put in front of it afterwards sets it off, and so does anything that gives off power. 1 yes, 2 no, 0 out
     * of time.
     */
    private byte heldByLater(LocalPlayer player, int i, int j, int own, long started) {
        BlockState target = plan.target(j);
        if (plan.target(i).getBlock() instanceof ObserverBlock || target.isSignalSource()) return 2;
        Level level = player.level();
        BlockPos pos = plan.pos(j);
        long stamp = BuildPlan.stamp(level, pos);
        int slot = 2 * j + (own == 1 ? 0 : 1);
        if (laterStamp[slot] == stamp) return later[slot];
        Map<Long, BlockState> without = new HashMap<>();
        for (Direction side : Direction.values()) {
            BlockPos at = pos.relative(side);
            int k = plan.indexOf(at);
            if (k >= 0 && BlockRules.group(plan.target(k)) >= own && level.getBlockState(at).getBlock() == plan.target(k).getBlock()) {
                without.put(at.asLong(), Blocks.AIR.defaultBlockState());
            }
        }
        byte result = 2;
        Item item = BlockRules.item(target);
        if (without.isEmpty() && item != null) {
            // Nothing of that group in yet: whether it is what the block waits for. One with nothing to go in against now
            // but a block of that group beside it once that is in, a row of blocks over air beside a hopper row say, is
            // clicked onto it then, rather than onto a helper block put under each.
            Map<Long, BlockState> with = new HashMap<>();
            for (Direction side : Direction.values()) {
                BlockPos at = pos.relative(side);
                int k = plan.indexOf(at);
                if (k >= 0 && BlockRules.group(plan.target(k)) >= own && level.getBlockState(at).canBeReplaced()) with.put(at.asLong(), plan.target(k));
            }
            if (!with.isEmpty()) {
                if (System.nanoTime() - started > PLAN_NANOS) return 0;
                LongPredicate notThere = a -> unclickable(a, j);
                ItemStack stack = new ItemStack(item);
                Overlay withView = new Overlay(level, with);
                if (!PlacementSolver.supported(player, pos, target, stack, notThere)
                        && (PlacementSolver.supported(player, withView, pos, target, stack, notThere)
                        || PlacementSolver.supportedFinely(player, withView, pos, target, stack, notThere))) {
                    result = 1;
                }
            }
        } else if (item != null) {
            if (System.nanoTime() - started > PLAN_NANOS) return 0;
            Overlay view = new Overlay(level, without);
            if (!PlacementSolver.supported(player, view, pos, target, new ItemStack(item), a -> unclickable(a, j))
                    && !PlacementSolver.supportedFinely(player, view, pos, target, new ItemStack(item), a -> unclickable(a, j))) {
                result = 1;
            }
        }
        laterStamp[slot] = stamp;
        later[slot] = result;
        return result;
    }

    /**
     * Whether water or lava poured at {@code at} could run where a player standing with the feet at {@code feet} is: it
     * falls where it can, and spreads sideways up to a flow's length, a source always and falling water once it lands.
     * A player it reaches is pushed about, off a narrow top or down into a channel.
     */
    /**
     * Whether water may be poured with the feet in {@code feet}, though it runs there or past: a cell the build fills with
     * water itself, as kelp or water, so the player standing there gets wet anyway, on a full block, with no drop beside
     * it the water could push them into: beside it, a cell open at the feet has something under it to stand on, or is one
     * of the build's own water, kelp or the spot {@code pouredAt}, which the water fills. Out again by wading or swimming
     * along it, as a kelp channel walled in on its sides. The checks that keep blocks still to go in dry stay as they are.
     */
    private boolean wetPourStand(Level level, BlockPos feet, BlockPos pouredAt) {
        // Never under the water poured: it falls onto the player and fills where they stand, a block deep, as a corridor
        // under IKEA v1's machine filled from the kelp spots of y 68 poured into from it, feet at 66 (v1-clean3).
        if (pouredAt != null && feet.getY() < pouredAt.getY()) return false;
        int i = plan.indexOf(feet);
        if (i < 0 || !holdsWaterLater(i)) return false;
        BlockPos floor = feet.below();
        if (!level.getBlockState(floor).isCollisionShapeFullBlock(level, floor)) return false;
        for (Direction side : Direction.Plane.HORIZONTAL) {
            BlockPos beside = feet.relative(side);
            if (!level.getBlockState(beside).getCollisionShape(level, beside).isEmpty()) continue;
            if (beside.equals(pouredAt)) continue;
            int j = plan.indexOf(beside);
            if (j >= 0 && holdsWaterLater(j)) continue;
            BlockPos under = beside.below();
            if (level.getBlockState(under).getCollisionShape(level, under).isEmpty()) return false;
        }
        return true;
    }

    /**
     * Whether {@code cell} is where the look up at a block still to go in that is placed looking up comes from: one or two
     * under it, or under a cell beside it. A helper block there hides every such look, as walk helpers at 1,69,9 and
     * 2,69,10 hid the observer 2,70,9 of ikea_v4 (v4-clean2, 23:53).
     */
    private boolean inLookUpChannel(BlockPos cell) {
        for (int up = 1; up <= 2; up++) {
            BlockPos over = cell.above(up);
            if (pendingLookingUp(over)) return true;
            for (Direction side : Direction.Plane.HORIZONTAL) if (pendingLookingUp(over.relative(side))) return true;
        }
        return false;
    }

    private boolean pendingLookingUp(BlockPos pos) {
        int j = plan.indexOf(pos);
        return j >= 0 && plan.status(j) == BuildPlan.Status.PENDING && BlockRules.placedLookingUp(plan.target(j));
    }

    /** Whether a cell over or beside {@code pos} is the build's own water, kelp or seagrass. */
    private boolean bordersBuildWater(BlockPos pos) {
        for (Direction side : Direction.values()) {
            if (side == Direction.DOWN) continue;
            int j = plan.indexOf(pos.relative(side));
            if (j >= 0 && holdsWaterLater(j)) return true;
        }
        return false;
    }

    /**
     * Whether the water at a place to stand holds the player still: at the feet and the head only sources, or water running
     * straight into a wall, which presses the player against it and carries them nowhere, as the row's water running into
     * the kelp channel z 4 of ikea_v4 against the dispensers of z 3; where a cell is empty, no water running beside it to
     * run in; and a full block underfoot. A look from there holds as on dry ground, as a player placing a dispenser from the
     * kelp channel in front of it (v4-full8 3,70,9, v4-full11 3,70,3).
     */
    private static boolean stillWater(Level level, Navigator.Node node) {
        BlockPos floor = node.floor();
        if (!level.getBlockState(floor).isCollisionShapeFullBlock(level, floor)) return false;
        for (BlockPos cell : new BlockPos[] {node.feetCell(), node.feetCell().above()}) {
            FluidState fluid = level.getFluidState(cell);
            if (!fluid.isEmpty() && !fluid.isSource()) {
                // Running: only into walls. Each way it runs, the cell that way holds the player back.
                Vec3 flow = fluid.getFlow(level, cell);
                for (Direction side : Direction.Plane.HORIZONTAL) {
                    if (flow.x * side.getStepX() + flow.z * side.getStepZ() < 0.3) continue;
                    BlockPos toward = cell.relative(side);
                    if (level.getBlockState(toward).getCollisionShape(level, toward).isEmpty()) return false;
                }
                continue;
            }
            // A cell full of water takes none in from beside it; an empty one may.
            if (fluid.isSource()) continue;
            for (Direction side : Direction.Plane.HORIZONTAL) {
                FluidState beside = level.getFluidState(cell.relative(side));
                if (!beside.isEmpty() && !beside.isSource()) return false;
            }
        }
        return true;
    }

    /** Whether block {@code i} is water the build pours, or kelp or seagrass, which stand in water. */
    private boolean holdsWaterLater(int i) {
        BlockState target = plan.target(i);
        return plan.plant(i) || target.getBlock() instanceof LiquidBlock && target.getFluidState().getType().isSame(Fluids.WATER);
    }

    static boolean floods(BlockGetter level, BlockPos at, Vec3 feet) {
        AABB body = new AABB(feet.x - 0.3, feet.y, feet.z - 0.3, feet.x + 0.3, feet.y + 1.8, feet.z + 0.3);
        Map<Long, Integer> spread = new HashMap<>();
        ArrayDeque<BlockPos> open = new ArrayDeque<>();
        spread.put(at.asLong(), 0);
        open.add(at);
        while (!open.isEmpty() && spread.size() < 4000) {
            BlockPos pos = open.poll();
            if (new AABB(pos).intersects(body)) return true;
            int run = spread.get(pos.asLong());
            BlockPos below = pos.below();
            boolean falls = below.getY() > at.getY() - 24 && runsInto(level, below);
            if (falls && !spread.containsKey(below.asLong())) {
                spread.put(below.asLong(), 0);
                open.add(below);
            }
            if ((pos.equals(at) || !falls) && run < 7) {
                for (Direction side : Direction.Plane.HORIZONTAL) {
                    BlockPos next = pos.relative(side);
                    Integer known = spread.get(next.asLong());
                    if ((known == null || known > run + 1) && runsInto(level, next)) {
                        spread.put(next.asLong(), run + 1);
                        open.add(next);
                    }
                }
            }
        }
        return false;
    }

    /**
     * Whether water runs right beside a player standing with the feet in {@code feet}, at the feet or the head. A walk
     * comes to rest up to a quarter block off the middle, and a body that far off reaches into the cell beside: in a
     * current, it is carried off, along a channel on ice for blocks. Water that runs nowhere, a pool of sources, carries
     * no one.
     */
    /**
     * Whether water at the feet runs toward a cell beside them with nothing under it, an open gate or a gap: standing
     * there, the current carries the player off into it, upright or not (v4-full10, 21:34: on 3 69 8, the flow (0, 0, -1)
     * pulled the player north over the fall column at z 7 and down through the gate at 3,68,7).
     */
    /** How many of the blocks a walk search found no place for {@link #guardFor} weighs. */
    private static final int GUARD_CHECKS = 3;
    /**
     * Helper blocks put into a fall a current at a place to stand runs into, by the block that place is for (see
     * {@link #guardFor}); and walks made to put each down.
     */
    private final Map<Integer, BlockPos> guardSpot = new HashMap<>();
    private final Map<Integer, Integer> guardWalks = new HashMap<>();
    /** Blocks whose guard stands, for which the walk searches were made afresh once. */
    private final Set<Integer> guardMemoCleared = new HashSet<>();

    /**
     * Where a helper block makes a place to stand for block {@code i} safe, or null: a place near it with a look at it,
     * on a full block, ruled out only by water at the feet running into a fall beside it, an open gate or a gap with
     * nothing under it (see {@link #currentIntoFall}), as ikea_v4's kelp channel z 8 running into the fall column of z 7,
     * the only place for the dispensers facing north at z 9. With a helper block in that fall the current runs into it
     * and carries no one off. Only a fall in water or open air the build leaves so, or fills with water itself; the
     * helper block comes away once block {@code i} is in, as any helper block serving a block, and the water runs back
     * or is poured again.
     */
    private BlockPos guardFor(LocalPlayer player, int i) {
        if (plan.kind(i) != BlockRules.Kind.PLACE) return null;
        Item item = BlockRules.item(plan.target(i)), helperItem = helperItem(player);
        if (item == null || helperItem == null) return null;
        Level level = player.level();
        BlockPos pos = plan.pos(i);
        BlockState target = plan.target(i), helperState = Block.byItem(helperItem).defaultBlockState();
        ItemStack stack = new ItemStack(item);
        double reach = player.blockInteractionRange() - 0.2;
        LongPredicate notThere = at -> unclickable(at, i);
        for (BlockPos feet : BlockPos.betweenClosed(pos.offset(-2, -2, -2), pos.offset(2, 0, 2))) {
            BlockPos floor = feet.below();
            if (feet.equals(pos) || !level.getBlockState(floor).isCollisionShapeFullBlock(level, floor)) continue;
            if (!level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
                    || !level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()) continue;
            if (!currentIntoFall(level, feet)) continue;
            BlockPos fall = fallBeside(level, feet);
            if (fall == null || fall.equals(pos) || !level.getBlockState(fall).canBeReplaced()) continue;
            int j = plan.indexOf(fall);
            if (j >= 0 && !(plan.kind(j) != BlockRules.Kind.PLACE && plan.target(j).getBlock() instanceof LiquidBlock)) continue;
            Overlay guarded = new Overlay(level, Map.of(fall.asLong(), helperState));
            if (currentIntoFall(guarded, feet)) continue;
            Vec3 standing = new Vec3(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5);
            if (PlacementSolver.fromSpot(player, guarded, standing, pos, target, stack, reach, notThere) == null) continue;
            return fall.immutable();
        }
        return null;
    }

    /** The cell beside {@code feet} the water there runs into with nothing under it, as {@link #currentIntoFall} finds. */
    private static BlockPos fallBeside(BlockGetter level, BlockPos feet) {
        for (int up = 0; up <= 1; up++) {
            BlockPos cell = feet.above(up);
            FluidState fluid = level.getFluidState(cell);
            if (fluid.isEmpty()) continue;
            Vec3 flow = fluid.getFlow(level, cell);
            for (Direction side : Direction.Plane.HORIZONTAL) {
                if (flow.x * side.getStepX() + flow.z * side.getStepZ() < 0.5) continue;
                BlockPos beside = feet.relative(side);
                if (level.getBlockState(beside).getCollisionShape(level, beside).isEmpty()
                        && level.getBlockState(beside.below()).getCollisionShape(level, beside.below()).isEmpty()) return beside;
            }
        }
        return null;
    }

    /**
     * Puts down a guard of {@link #guardFor} still to go in: from here when it can, else after a walk to where a look
     * reaches its spot, the walk's places as any walk's (none in a current into a fall). True when begun.
     */
    private boolean placeGuard(Minecraft client, LocalPlayer player, int layerNumber) {
        if (guardSpot.isEmpty() || helperItem(player) == null) return false;
        Level level = player.level();
        // One guard at a time: one standing, for a block still to go in, gets its block first. The walk search made before
        // it went in found nothing, and would find nothing again for a while: made afresh.
        for (Map.Entry<Integer, BlockPos> entry : guardSpot.entrySet()) {
            Helper standing = helpers.get(entry.getValue().asLong());
            if (standing == null || standing.serves() != entry.getKey() || plan.status(entry.getKey()) != BuildPlan.Status.PENDING) continue;
            if (guardMemoCleared.add(entry.getKey())) Arrays.fill(failedWalkKey, 0);
            return false;
        }
        for (Iterator<Map.Entry<Integer, BlockPos>> it = guardSpot.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, BlockPos> entry = it.next();
            int i = entry.getKey();
            BlockPos guard = entry.getValue();
            if (plan.status(i) != BuildPlan.Status.PENDING || !level.getBlockState(guard).canBeReplaced()) {
                // In, or something there already: the guard is done with, or not needed.
                if (plan.status(i) != BuildPlan.Status.PENDING) it.remove();
                continue;
            }
            if (placeHelper(client, player, i, guard, layerNumber)) {
                if (debug()) LOGGER.info("A helper block at {}, against the current into the fall there, for {} at {}", guard.toShortString(),
                        plan.target(i).getBlock().getName().getString(), plan.pos(i).toShortString());
                return true;
            }
            if (guardWalks.merge(i, 1, Integer::sum) > REPAIRS) {
                it.remove();
                continue;
            }
            Item helperItem = helperItem(player);
            BlockState helperState = Block.byItem(helperItem).defaultBlockState();
            ItemStack helperStack = new ItemStack(helperItem);
            double reach = player.blockInteractionRange() - 0.25;
            Map<Long, Boolean> wetCells = new HashMap<>();
            List<BlockPos> poured = recentPours();
            Navigator.Path path = navigator(player, true).find(Navigator.standingAt(level, player), node -> {
                if (node.feetCell().equals(guard) || currentIntoFall(level, node.feetCell())) return 0;
                if (wetSoon(level, node.feetCell(), poured, wetCells) && !stillWater(level, node)) return 0;
                return PlacementSolver.fromSpot(player, node.standing(), guard, helperState, helperStack, reach, helpers::containsKey) != null ? 1 : 0;
            });
            if (path == null || path.nodes().size() < 2 || dropsIntoTrap(player, path)) continue;
            if (!climbReady(client, player, path)) return true;
            releaseKeys(client);
            walker.start(path.nodes(), Vec3.atCenterOf(guard));
            if (debug()) LOGGER.info("Walking {} steps to put a helper block at {} against the current into the fall there, for {} at {}",
                    path.nodes().size() - 1, guard.toShortString(), plan.target(i).getBlock().getName().getString(), plan.pos(i).toShortString());
            return true;
        }
        return false;
    }

    static boolean currentIntoFall(BlockGetter level, BlockPos feet) {
        for (int up = 0; up <= 1; up++) {
            BlockPos cell = feet.above(up);
            FluidState fluid = level.getFluidState(cell);
            if (fluid.isEmpty()) continue;
            Vec3 flow = fluid.getFlow(level, cell);
            if (flow.horizontalDistanceSqr() < 1e-4) continue;
            for (Direction side : Direction.Plane.HORIZONTAL) {
                if (flow.x * side.getStepX() + flow.z * side.getStepZ() < 0.5) continue;
                BlockPos beside = feet.relative(side);
                if (level.getBlockState(beside).getCollisionShape(level, beside).isEmpty()
                        && level.getBlockState(beside.below()).getCollisionShape(level, beside.below()).isEmpty()) return true;
            }
        }
        return false;
    }

    static boolean currentBeside(BlockGetter level, BlockPos feet) {
        for (int up = 0; up <= 1; up++) {
            for (Direction side : Direction.Plane.HORIZONTAL) {
                BlockPos cell = feet.above(up).relative(side);
                FluidState fluid = level.getFluidState(cell);
                if (!fluid.isEmpty() && fluid.getFlow(level, cell).lengthSqr() > 1e-4) return true;
            }
        }
        return false;
    }

    /** Whether water could run into the cell at {@code pos}: empty, or something it washes away or already fills. */
    private static boolean runsInto(BlockGetter level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.canBeReplaced() || !state.getFluidState().isEmpty();
    }

    /**
     * Whether water is where a player standing with the feet in {@code feet} has their feet or head, or soon will be.
     * Water that has settled runs no further. Water just poured, at one of {@code poured}, still spreads, a block every
     * few ticks: on that level within a flow's length, with open cells between, it gets there while they stand and work,
     * as it does from just above the head. Worked out once a cell, in {@code known}.
     */
    static boolean wetSoon(BlockGetter level, BlockPos feet, List<BlockPos> poured, Map<Long, Boolean> known) {
        Boolean cached = known.get(feet.asLong());
        if (cached != null) return cached;
        boolean wet = !level.getFluidState(feet).isEmpty() || !level.getFluidState(feet.above()).isEmpty();
        boolean spreading = poured.stream().anyMatch(at -> Math.abs(at.getX() - feet.getX()) <= 9 && Math.abs(at.getZ() - feet.getZ()) <= 9
                && at.getY() >= feet.getY() - 1 && at.getY() <= feet.getY() + 5);
        if (!wet && spreading) wet = reaches(level, feet) || reaches(level, feet.above());
        for (int up = 2; !wet && spreading && up <= 4; up++) {
            BlockPos above = feet.above(up);
            if (!runsInto(level, above)) break;
            wet = !level.getFluidState(above).isEmpty();
        }
        known.put(feet.asLong(), wet);
        return wet;
    }

    /**
     * Where water or lava went in, or a cell was opened, over the last four seconds: water there may still be spreading.
     * It runs a block every five ticks, a flow's length in under two seconds.
     */
    private List<BlockPos> recentPours() {
        pours.values().removeIf(at -> tick - at > 80);
        List<BlockPos> recent = new ArrayList<>(pours.size());
        for (long key : pours.keySet()) recent.add(BlockPos.of(key));
        return recent;
    }

    /** Whether water could run along the level into the cell at {@code start} from water a flow's length away at most. */
    private static boolean reaches(BlockGetter level, BlockPos start) {
        if (!level.getFluidState(start).isEmpty()) return true;
        if (!runsInto(level, start)) return false;
        Map<Long, Integer> run = new HashMap<>();
        ArrayDeque<BlockPos> open = new ArrayDeque<>();
        run.put(start.asLong(), 0);
        open.add(start);
        while (!open.isEmpty()) {
            BlockPos pos = open.poll();
            int steps = run.get(pos.asLong());
            if (steps >= 7) continue;
            for (Direction side : Direction.Plane.HORIZONTAL) {
                BlockPos next = pos.relative(side);
                if (run.containsKey(next.asLong()) || !runsInto(level, next)) continue;
                if (!level.getFluidState(next).isEmpty()) return true;
                run.put(next.asLong(), steps + 1);
                open.add(next);
            }
        }
        return false;
    }

    /**
     * Whether the player could still get out with block {@code i} in place: walking, jumping or climbing onto the top
     * of the layer being built, or out of the schematic. Any block in reach can shut them in, a far one by closing the
     * last way out at its far end.
     */
    private boolean wayOut(LocalPlayer player, int i) {
        BlockPos pos = plan.pos(i);
        if (plan.target(i).getCollisionShape(player.level(), pos).isEmpty()) return true;
        // Out already: on top of the layer, or off the build.
        if (player.getY() >= layerY + 1 - 1e-3 || offBuild(player.position())) return true;
        // The way out there is now, worked out once a tick. A block anywhere off it, near or as far as reach goes,
        // leaves it open; one on it may close the last way out, and the search is made again with it in place.
        if (exitTick != tick) {
            Navigator.Node start = Navigator.standingAt(player.level(), player);
            Navigator.Path exit = exitFrom(player, player.level(), start, null);
            // With no other way out, the climb over cells the build fills later is the way out: kept clear as well.
            exitEscapes = exit == null && (exit = escapeFrom(player, player.level(), start, null)) != null;
            exitCells = exit == null ? null : bodyCells(exit);
            exitTick = tick;
        }
        // Shut in already without it, the block changes nothing: getting out is up to breakOut.
        if (exitCells == null || !exitCells.contains(pos.asLong())) return true;
        Overlay view = new Overlay(player.level(), Map.of(pos.asLong(), plan.target(i)));
        // Every place this search reaches is shut in with the player when it finds no way out.
        Set<Long> reached = new HashSet<>();
        Navigator.Node viewStart = Navigator.standingAt(view, player);
        if ((exitEscapes ? escapeFrom(player, view, viewStart, reached) : exitFrom(player, view, viewStart, reached)) != null) return true;
        // A climb out over cells the build fills later is a way out too, as up a kelp channel the block walls in: out that
        // way once it is in (climbOut), the helper blocks coming away after. Only from a cell of the build's own water or
        // kelp, such a channel: elsewhere the climb a search finds through cells of blocks still to go in may not go up in
        // the end (v1-clean, 22:10: walled in under IKEA v1's machine by a piston and a gate, every step up failed).
        int standing = plan.indexOf(viewStart.feetCell());
        if (!exitEscapes && standing >= 0 && holdsWaterLater(standing) && escapeFrom(player, view, viewStart, null) != null) {
            if (debug()) LOGGER.info("{} at {} walls you in, but a climb out stays", plan.target(i).getBlock().getName().getString(),
                    pos.toShortString());
            return true;
        }
        shutIn.put(i, reached);
        if (debug()) LOGGER.info("{} at {} waits: it would shut you in, with {} places to stand", plan.target(i).getBlock().getName().getString(),
                pos.toShortString(), reached.size());
        return false;
    }

    /**
     * The cells a body walking along {@code path} passes through: each place's column from the feet to above the head,
     * as high as the higher of two places a step joins, and both corners of a diagonal step.
     */
    private static Set<Long> bodyCells(Navigator.Path path) {
        Set<Long> cells = new HashSet<>();
        Navigator.Node before = null;
        for (Navigator.Node node : path.nodes()) {
            double low = node.feet(), high = node.feet();
            if (before != null) {
                low = Math.min(low, before.feet());
                high = Math.max(high, before.feet());
            }
            int from = (int) Math.floor(low + 1e-3), to = (int) Math.floor(high + 2.1);
            for (int y = from; y <= to; y++) {
                cells.add(BlockPos.asLong(node.x(), y, node.z()));
                if (before == null) continue;
                cells.add(BlockPos.asLong(before.x(), y, before.z()));
                if (before.x() != node.x() && before.z() != node.z()) {
                    cells.add(BlockPos.asLong(before.x(), y, node.z()));
                    cells.add(BlockPos.asLong(node.x(), y, before.z()));
                }
            }
            before = node;
        }
        return cells;
    }

    /**
     * A way from {@code start} onto the top of the layer or off the build, in {@code view}, or null when there is none;
     * every place the search reaches goes into {@code reached}, when given.
     */
    private Navigator.Path exitFrom(LocalPlayer player, BlockGetter view, Navigator.Node start, Set<Long> reached) {
        return navigator(player, view, true).limit(3000).find(start, node -> {
            if (reached != null) reached.add(node.key());
            return node.feet() >= layerY + 1 - 1e-3 || offBuild(node.standing()) ? 1 : 0;
        });
    }

    /** As {@link #exitFrom}, climbing over cells the build fills later too, a helper block put in each, as {@link #climbOut} does. */
    private Navigator.Path escapeFrom(LocalPlayer player, BlockGetter view, Navigator.Node start, Set<Long> reached) {
        boolean was = escaping;
        escaping = true;
        try {
            return exitFrom(player, view, start, reached);
        } finally {
            escaping = was;
        }
    }

    /**
     * Shut in with a block of the build broken to get out (see {@link #breakOutOfBuild}): the walk out, climbing over
     * cells the build fills later, the broken block's among them.
     */
    private boolean walkOut(Minecraft client, LocalPlayer player) {
        if (brokeOut.isEmpty() || helperItem(player) == null) return false;
        Level level = player.level();
        Navigator.Path path = escapeFrom(player, level, Navigator.standingAt(level, player), null);
        if (path == null || path.nodes().size() < 2) return false;
        if (!climbReady(client, player, path)) return true;
        releaseKeys(client);
        walker.start(path.nodes());
        escapeWalk = true;
        show("Climbing out of where you were shut in", false);
        if (debug()) LOGGER.info("Climbing out, {} steps, over where a block of the build was broken", path.nodes().size() - 1);
        return true;
    }

    /**
     * Down in the build below its layer, as after a fall into a channel, with no place to stand for any block from where
     * the walks reach: out first, up to the layer or off the build, climbing over cells the build fills later, as a player
     * climbs out of a hole before looking for the next block. The places to stand are searched again from up there.
     */
    private boolean climbUpOut(Minecraft client, LocalPlayer player) {
        if (helperItem(player) == null || tick - climbUpChecked < 100) return false;
        climbUpChecked = tick;
        Level level = player.level();
        Navigator.Node start = Navigator.standingAt(level, player);
        if (start.feet() >= layerY + 1 - 1e-3 || offBuild(start.standing())) return false;
        Navigator.Path path = escapeFrom(player, level, start, null);
        if (path == null || path.nodes().size() < 2) return false;
        if (!climbReady(client, player, path)) {
            // Helper blocks on their way into the hotbar: the climb out is tried again once they are there, not five
            // seconds on. With none moving, the usual wait.
            if (swapFrom >= 0) climbUpChecked = Long.MIN_VALUE / 2;
            return true;
        }
        releaseKeys(client);
        walker.start(path.nodes());
        escapeWalk = true;
        show("Climbing out of the build to find a place to stand", false);
        if (debug()) LOGGER.info("Down in the build with no place to stand from here: climbing out first, {} steps", path.nodes().size() - 1);
        return true;
    }

    private long climbUpChecked = Long.MIN_VALUE / 2;

    /**
     * Shut in with nothing to place from here and no climb out, not even over cells the build fills later, as after a
     * fall into a finished part of a machine: one of the build's own blocks in reach is broken, as a player digs out of
     * a pocket, the quickest to break of those that open a way out. Only a plain block that breaking gives back, with
     * nothing resting on it, hanging from it or held back by it; it goes in again once the player is out.
     */
    private boolean breakOutOfBuild(LocalPlayer player) {
        if (helperItem(player) == null || tick - breakOutChecked < (brokeOut.isEmpty() ? 100 : 10)) return false;
        breakOutChecked = tick;
        Level level = player.level();
        Navigator.Node start = Navigator.standingAt(level, player);
        if (escapeFrom(player, level, start, null) != null) return false;
        Set<Long> inside = new HashSet<>();
        exitFrom(player, level, start, inside);
        double reach = player.blockInteractionRange() - 0.25;
        Vec3 eye = player.getEyePosition();
        BlockPos middle = BlockPos.containing(eye);
        int r = (int) Math.ceil(reach);
        List<BlockPos> candidates = new ArrayList<>();
        for (BlockPos at : BlockPos.betweenClosed(middle.offset(-r, -r, -r), middle.offset(r, r, r))) {
            int i = plan.indexOf(at);
            if (i < 0 || plan.status(i) != BuildPlan.Status.DONE || plan.kind(i) != BlockRules.Kind.PLACE || at.equals(start.floor())) continue;
            BlockState state = level.getBlockState(at);
            if (BlockRules.group(state) != 0 || BlockRules.wet(state) || !spares(player, state, BlockRules.item(plan.target(i)))
                    || state.getDestroySpeed(level, at) < 0 || level.getBlockEntity(at) != null) continue;
            // Shut in a flooded tunnel, every block round it has water beside it: the water runs into the opening, and
            // stops once the block goes in again.
            if (new AABB(at).distanceToSqr(eye) > reach * reach || !safeToBreak(level, at, 0, true)) continue;
            // Not one with the build's water or kelp over or beside it, a kelp channel's floor say: once that water is in,
            // a look back at its spot comes from neither side, and it stays out (v4-full3, 7,68,4 under kelp 7,69,4).
            if (bordersBuildWater(at)) continue;
            candidates.add(at.immutable());
        }
        candidates.sort(Comparator.comparingDouble(at -> breakTicks(player, level, at) + new AABB(at).distanceToSqr(eye)));
        // The second block of a way out through a wall goes before any other: else each round would start on another.
        if (pairNext != null && candidates.remove(pairNext)) candidates.addFirst(pairNext);
        pairNext = null;
        Set<BlockPos> breakable = new HashSet<>(candidates);
        long started = System.nanoTime();
        for (BlockPos at : candidates) {
            if (System.nanoTime() - started > 40_000_000L) break;
            if (missedMining(player, at) || !inView(player, eye, at, reach)) continue;
            Overlay view = new Overlay(level, Map.of(at.asLong(), Blocks.AIR.defaultBlockState()));
            if (escapeFrom(player, view, Navigator.standingAt(view, player), null) == null) {
                // A way out through a wall takes two blocks, one over the other, a body's height: this one first, the other
                // found on its own once this one is gone.
                BlockPos other = breakable.contains(at.above()) ? at.above() : breakable.contains(at.below()) ? at.below() : null;
                if (other == null) continue;
                Overlay both = new Overlay(level, Map.of(at.asLong(), Blocks.AIR.defaultBlockState(), other.asLong(), Blocks.AIR.defaultBlockState()));
                if (escapeFrom(player, both, Navigator.standingAt(both, player), null) == null) continue;
                pairNext = other;
            }
            int i = plan.indexOf(at);
            if (debug()) LOGGER.info("Shut in for good: breaking {} at {}, the build's own, to climb out; it goes in again after",
                    level.getBlockState(at), at.toShortString());
            brokeOut.add(i);
            pocketPlaces.addAll(inside);
            Item item = BlockRules.item(plan.target(i));
            if (!player.hasInfiniteMaterials() && item != null) repairDrops.add(item);
            startBreak(at);
            repairing = i;
            return true;
        }
        return false;
    }

    /**
     * Out of the pocket, with helper blocks of the climb out still standing: a walk to where the top one of a column is in
     * view, for {@link #removeUsedHelper} to take it away. False when one is in view already, or none is left to go to.
     */
    private boolean walkToEscapeHelper(Minecraft client, LocalPlayer player) {
        Level level = player.level();
        double reach = player.blockInteractionRange() - 0.25;
        for (Iterator<Long> it = escapeHelpers.iterator(); it.hasNext(); ) {
            long key = it.next();
            BlockPos at = BlockPos.of(key);
            Helper helper = helpers.get(key);
            if (helper == null || level.getBlockState(at).getBlock() != helper.block()) {
                it.remove();
                plan.hold(at, false);
                continue;
            }
            if (helpers.containsKey(at.above().asLong())) continue;
            // In view, not underfoot, and the water it holds back, if any, can't reach the player: removeUsedHelper takes it
            // from here. Standing on it, the player steps off first, rather than idle there until stepDown digs them back
            // down where they climbed out of.
            if (inView(player, player.getEyePosition(), at, reach) && safeToBreak(player, at)
                    && !Navigator.standingAt(level, player).floor().equals(at)) return false;
            if (escapeWalks.getOrDefault(key, 0) >= 3) {
                // Left for the cleanup: the layers above it go on.
                it.remove();
                plan.hold(at, false);
                continue;
            }
            // Where it is in view from as far round the middle as a walk comes to rest, as down a narrow hole only a few
            // places see it, and only just. Never from on top of the column, nor from back down in the pocket: from there it
            // can't be broken, and the walk out would only be made again.
            Navigator.Node start = Navigator.standingAt(level, player);
            // Nor from where the water it holds back would run to once it is gone (see safeToBreak), as with one put into the
            // build's own water: from there it is never taken away.
            boolean holdsWater = holdsBackWater(level, at);
            // Nor from here: from where the player stands it isn't, though it may be from the middle of this place, a few
            // tenths off, as a walk's search sees it. Found here again, no walk was made and it waited out the two minutes
            // (v4-fresh3, 17, 66, 6, under the floor stood on beside it).
            // Nor from down in the build, under its layer: climbUpOut takes the player back up from there before the helper
            // comes away, onto the very helper, and the two walks went to and fro until escapeWalks gave up on it (v4-fresh3
            // 05:42:56, 4, 69, 28 in the kelp channel, walked to from 5 69 28 three times).
            double out = layerY + 1 - 1e-3;
            Navigator.Path path = navigator(player, true).find(start, node -> node.key() == start.key() || pocketPlaces.contains(node.key())
                    || node.feet() < out && !offBuild(node.standing())
                    || node.x() == at.getX() && node.z() == at.getZ() && node.feet() >= at.getY()
                    || holdsWater && floods(level, at, node.standing()) ? 0 : viewScore(player, node.eye(), at, reach));
            if (path == null || dropsIntoTrap(player, path)) {
                it.remove();
                plan.hold(at, false);
                continue;
            }
            // Here already, only a little off where it is in view: tried again from wherever the build takes the player.
            if (path.nodes().size() < 2) continue;
            escapeWalks.merge(key, 1, Integer::sum);
            if (!climbReady(client, player, path)) return true;
            releaseKeys(client);
            walker.start(path.nodes(), Vec3.atCenterOf(at));
            show("Walking to the helper blocks climbed out on, to take them away", false);
            if (debug()) LOGGER.info("Walking {} steps to take away the helper block climbed out on at {}", path.nodes().size() - 1, at.toShortString());
            return true;
        }
        return false;
    }

    /**
     * How well a face of the block at {@code pos} is in view from {@code eye}: 0 when not from there, else 1 and one more for
     * each of the eight places round it, as far off as a walk comes to rest, it is in view from too.
     */
    private static int viewScore(LocalPlayer player, Vec3 eye, BlockPos pos, double reach) {
        if (!inView(player, eye, pos, reach)) return 0;
        int score = 1;
        for (double dx : new double[] {-REST_SPREAD, 0, REST_SPREAD}) {
            for (double dz : new double[] {-REST_SPREAD, 0, REST_SPREAD}) {
                if ((dx != 0 || dz != 0) && inView(player, eye.add(dx, 0, dz), pos, reach)) score++;
            }
        }
        return score;
    }

    /**
     * Notes a helper block put down on a way out of where the player was shut in; one in water the build has there holds
     * the layers above until it is gone (see BuildPlan.hold).
     */
    private void addEscapeHelper(BlockPos cell) {
        escapeHelpers.add(cell.asLong());
        int i = plan.indexOf(cell);
        BlockState target = i < 0 ? null : plan.target(i);
        if (target != null && (target.getBlock() instanceof LiquidBlock && target.getFluidState().is(FluidTags.WATER) || plan.plant(i))) {
            plan.hold(cell, true);
        }
    }

    /** Forgets a helper block of a way out, gone or left for the cleanup, and the hold it had. */
    private void dropEscapeHelper(long key) {
        escapeHelpers.remove(key);
        plan.hold(BlockPos.of(key), false);
    }

    /** Roughly how many ticks breaking the block takes with the best tool carried, for picking the quickest. */
    private static double breakTicks(LocalPlayer player, Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        float hardness = state.getDestroySpeed(level, pos);
        if (hardness <= 0) return 0;
        double best = Double.MAX_VALUE;
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < Materials.SLOTS; slot++) {
            ItemStack stack = inventory.getItem(slot);
            boolean right = !state.requiresCorrectToolForDrops() || stack.isCorrectToolForDrops(state);
            best = Math.min(best, hardness * (right ? 30 : 100) / Math.max(1f, stack.getDestroySpeed(state)));
        }
        return best;
    }

    /**
     * Whether a spot is off the build: beside it, a block or more clear of its sides, at any height. In a pit dug under
     * the build a player is lower than its bottom, yet not out: the way out is a climb, which blocks above can close.
     */
    private boolean offBuild(Vec3 at) {
        AABB b = plan.bounds;
        return at.x < b.minX - 1 || at.x >= b.maxX + 1 || at.z < b.minZ - 1 || at.z >= b.maxZ + 1;
    }

    /** Whether a walk drops into a place with no way back out; if so, every place shut in with it goes into {@link #traps}. */
    private boolean dropsIntoTrap(LocalPlayer player, Navigator.Path path) {
        if (path.nodes().stream().noneMatch(node -> node.move() == Navigator.Move.DROP)) return false;
        Set<Long> reached = new HashSet<>();
        if (exitFrom(player, player.level(), path.end(), reached) != null) return false;
        traps.addAll(reached);
        if (debug()) LOGGER.info("Not walking to {} {} {}: no way back out from there, with {} places to stand", path.end().x(),
                path.end().feet(), path.end().z(), reached.size());
        return true;
    }

    /**
     * When no place to stand is found from where the player is, and they stand on a helper block, as on top of a pillar
     * they climbed, takes that block away, as a player digs down a pillar: a block lower, other places may be reached.
     */
    private boolean stepDown(LocalPlayer player) {
        // Nothing to do may be only for now, as places to stand stay wet a few seconds after a pour: waited out first.
        // A step down that leaves nothing to do again goes on at once.
        if (tick - idleLast > 40) idleSince = tick;
        idleLast = tick;
        if (tick - idleSince < 100) return false;
        return stepDownNow(player);
    }

    /** Takes away the helper block underfoot, on top of a pillar of them, to stand a block lower: false when there is none. */
    private boolean stepDownNow(LocalPlayer player) {
        Level level = player.level();
        BlockPos under = BlockPos.containing(player.getX(), player.getY() - 0.05, player.getZ());
        if (!player.onGround() || !helpers.containsKey(under.asLong())) return false;
        // Down a pillar onto the block under it, never off a helper bridge into a drop or water.
        BlockPos landing = under.below();
        if (level.getBlockState(landing).getCollisionShape(level, landing).isEmpty()) return false;
        if (!inView(player, player.getEyePosition(), under, player.blockInteractionRange() - 0.25)) return false;
        if (debug()) LOGGER.info("No place to stand from up here: taking away the helper block underfoot at {}", under.toShortString());
        startBreak(under);
        return true;
    }

    /**
     * When the player has no way back out onto the layer or off the build, breaks a helper block in reach whose going
     * opens one: the builder's own blocks, which come away at the end anyway. False when the player isn't shut in, or
     * no helper block in reach would let them out.
     */
    private boolean breakOut(LocalPlayer player) {
        Level level = player.level();
        if (exitFrom(player, level, Navigator.standingAt(level, player), null) != null) return false;
        double reach = player.blockInteractionRange() - 0.25;
        Vec3 eye = player.getEyePosition();
        List<BlockPos> candidates = new ArrayList<>();
        for (long key : helpers.keySet()) candidates.add(BlockPos.of(key));
        candidates.addAll(helpersLeft);
        candidates.sort(Comparator.comparingDouble(pos -> new AABB(pos).distanceToSqr(eye)));
        for (BlockPos pos : candidates) {
            BlockState state = level.getBlockState(pos);
            if (state.canBeReplaced() || missedMining(player, pos) || !inView(player, eye, pos, reach)) continue;
            Overlay view = new Overlay(level, Map.of(pos.asLong(), Blocks.AIR.defaultBlockState()));
            if (exitFrom(player, view, Navigator.standingAt(view, player), null) == null) continue;
            if (debug()) LOGGER.info("Shut in: taking away the helper block at {} to get out", pos.toShortString());
            helpersLeft.remove(pos);
            helpers.putIfAbsent(pos.asLong(), new Helper(-1, state.getBlock()));
            startBreak(pos);
            return true;
        }
        return false;
    }

    /** A place to swim at, held at {@code feet} in still water, and the click on block {@code i} from there. */
    record SwimSpot(int i, Vec3 feet, PlacementSolver.Click click) { }

    /** Heights in a cell of still water the feet may be held at, swimming, from low in it to near its top. */
    private static final double[] SWIM_FEET = {0.05, 0.2, 0.35, 0.5, 0.65, 0.8, 0.95};
    /** How far off the middle of the cell, each way, the body may be held: a swimmer drifts to a side as a walker does. */
    private static final double[] SWIM_OFF = {-0.25, 0, 0.25};
    private long swimLogged = Long.MIN_VALUE / 2;
    private int swimLoggedLayer = Integer.MIN_VALUE;

    /**
     * Where block {@code i}, placed with a look up, goes in from a body held swimming in still water near it, as a player
     * swims up a kelp channel to place an up-facing observer over it: a source with no flow, the body clear of every
     * block. Null when there is none. Only for the blocks with a look up, which no place to stand on the build may have.
     */
    SwimSpot swimSpot(LocalPlayer player, int i, double reach) { return swimSpot(player, player.level(), i, reach); }

    /** As {@link #swimSpot(LocalPlayer, int, double)}, with the looks traced through {@code view}: with blocks put in first. */
    SwimSpot swimSpot(LocalPlayer player, BlockGetter view, int i, double reach) {
        BlockState target = plan.target(i);
        if (!BlockRules.placedLookingUp(target)) return null;
        Item item = BlockRules.item(target);
        if (item == null) return null;
        Level level = player.level();
        BlockPos pos = plan.pos(i);
        Vec3 middle = Vec3.atCenterOf(pos);
        ItemStack stack = new ItemStack(item);
        int r = (int) Math.ceil(reach);
        SwimSpot best = null;
        double bestAway = Double.MAX_VALUE;
        for (BlockPos cell : BlockPos.betweenClosed(pos.offset(-r, -r - 2, -r), pos.offset(r, 0, r))) {
            FluidState fluid = level.getFluidState(cell);
            if (!fluid.is(FluidTags.WATER) || !fluid.isSource() || fluid.getFlow(level, cell).lengthSqr() > 0) continue;
            for (double up : SWIM_FEET) {
                for (double dx : SWIM_OFF) {
                    for (double dz : SWIM_OFF) {
                        Vec3 feet = new Vec3(cell.getX() + 0.5 + dx, cell.getY() + up, cell.getZ() + 0.5 + dz);
                        double away = feet.add(0, 1.62, 0).distanceToSqr(middle);
                        if (away > (reach + 0.5) * (reach + 0.5) || away >= bestAway) continue;
                        PlacementSolver.Click click = PlacementSolver.fromSwim(player, view, feet, pos, target, stack, reach,
                                at -> unclickable(at, i));
                        if (click == null) continue;
                        best = new SwimSpot(i, feet, click);
                        bestAway = away;
                    }
                }
            }
        }
        return best;
    }

    /**
     * With no place to stand found, how many of the blocks placed with a look up a place to swim would let go in, and
     * from where: logged once a layer and then every half minute, to see what swimming would bring before walks use it.
     */
    private void logSwimLooks(LocalPlayer player, List<Integer> near, double reach) {
        if (layerY == swimLoggedLayer && tick - swimLogged < 600) return;
        swimLogged = tick;
        swimLoggedLayer = layerY;
        List<Integer> left = new ArrayList<>();
        for (int i : near) if (BlockRules.placedLookingUp(plan.target(i))) left.add(i);
        int up = left.size();
        if (up == 0) return;
        // In turn: each one placed is the face the next along its row goes in against, as along a row of observers.
        List<SwimSpot> found = new ArrayList<>();
        Map<Long, BlockState> placed = new HashMap<>();
        long started = System.nanoTime();
        boolean more = true;
        while (more && !left.isEmpty() && System.nanoTime() - started < 2_000_000_000L) {
            more = false;
            BlockGetter view = placed.isEmpty() ? player.level() : new Overlay(player.level(), Map.copyOf(placed));
            for (Iterator<Integer> it = left.iterator(); it.hasNext(); ) {
                int i = it.next();
                SwimSpot spot = swimSpot(player, view, i, reach);
                if (spot == null) continue;
                found.add(spot);
                placed.put(plan.pos(i).asLong(), plan.target(i));
                it.remove();
                more = true;
            }
        }
        StringBuilder text = new StringBuilder();
        for (SwimSpot spot : found.subList(0, Math.min(3, found.size()))) {
            BlockHitResult hit = spot.click().hit();
            text.append("; ").append(plan.pos(spot.i()).toShortString()).append(" from feet ")
                    .append(String.format(Locale.ROOT, "%.2f %.2f %.2f", spot.feet().x, spot.feet().y, spot.feet().z))
                    .append(", ").append(hit.getDirection()).append(" face of ").append(hit.getBlockPos().toShortString())
                    .append(String.format(Locale.ROOT, ", look %.1f %.1f", spot.click().yaw(), spot.click().pitch()));
        }
        LOGGER.info("Swimming in still water would place {} of the {} blocks with a look up left here, each in turn against the last (layer y {}){}{}",
                found.size(), up, layerY, text, left.isEmpty() ? "" : "; none for " + left.stream().limit(8)
                        .map(i -> plan.pos(i).toShortString()).collect(java.util.stream.Collectors.joining("; ")));
    }

    /** How long nothing may go in, with no place to stand for the next block, before it is given up on: five minutes. */
    private static final long GIVE_UP_TICKS = 6000;
    /** As {@link #GIVE_UP_TICKS}, for the blocks placed with a look up when no place a walk reaches has a look at any: a minute. */
    private static final long UP_GIVE_UP_TICKS = 1200;
    /** Blocks done at the last decision, and when that count last went up. */
    private int lastDone = -1;
    private long lastProgress;

    /** Walk searches in a row that found no place with a block in reach, or with a look from a few places only (see {@link #planWalk}). */
    private int noneInReach;
    /** Places a walk search reaches at most for the player to count as shut in when none of them has a look. */
    private static final int SHUT_IN_PLACES = 200;

    /**
     * With no place to stand found any other way: one more look, where a walk may climb through cells the build fills
     * later, a helper block put in each on the way, as a player pillars out of a pit or a shaft they came down into. Those
     * blocks come away again once the player has a way out without them (see {@link #removeUsedHelper}), and the blocks
     * that go there after them.
     */
    private boolean climbOut(Minecraft client, LocalPlayer player, List<Integer> candidates, Materials materials, int layerNumber,
                             boolean lift) {
        if (helperItem(player) == null) return false;
        boolean walking;
        escaping = true;
        try {
            walking = planWalk(client, player, candidates, materials, layerNumber, lift);
        } finally {
            escaping = false;
        }
        if (walking && walker.active()) escapeWalk = true;
        if (walking && debug()) LOGGER.info("No place to stand otherwise: walking on over cells the build fills later");
        return walking;
    }

    /**
     * Whether the player stands on a narrow top, a closed gate's or a fence's, with a drop of two blocks or more right
     * beside it: a few centimetres of drift off it and they are down there.
     */
    private static boolean onNarrowEdge(LocalPlayer player) {
        return player.onGround() && Navigator.narrowOverDrop(player.level(), Navigator.standingAt(player.level(), player));
    }

    /**
     * Whether a block of the build beside {@code pos} is still to go in and can go in now, without a helper block: one a
     * block may be placed against once it is. Two side by side over air that each wait for the other would wait for good.
     */
    private boolean neighbourToCome(LocalPlayer player, BlockPos pos) {
        for (Direction side : Direction.values()) {
            int j = plan.indexOf(pos.relative(side));
            if (j < 0 || plan.status(j) != BuildPlan.Status.PENDING || plan.kind(j) != BlockRules.Kind.PLACE || plan.target(j).isAir()) continue;
            Item item = BlockRules.item(plan.target(j));
            if (item != null && PlacementSolver.supported(player, plan.pos(j), plan.target(j), new ItemStack(item), at -> unclickable(at, j))) return true;
        }
        return false;
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
        } else if (plan.plant(i) && plan.needsPour(i)) {
            // Kelp or seagrass in a spot still dry: it goes in once water is poured into its spot, against any face beside it
            // that takes a bucket (decide's pour-first), so its layer waits for it as a player fills a kelp row before closing
            // over it: ikea_v4's 14,69,16, left dry until the source over its column, and out of reach by then (v4-full14).
            supported = PlacementSolver.pourable(player, pos, Fluids.WATER);
            if (debug() && supported && plantWaitLogged.putIfAbsent(-2000 - i, tick) == null) {
                LOGGER.info("{} at {} is dry: it goes in once water is poured into its spot", target.getBlock().getName().getString(),
                        pos.toShortString());
            }
        } else {
            Item item = BlockRules.item(target);
            supported = item != null && (PlacementSolver.supported(player, pos, target, new ItemStack(item), at -> unclickable(at, i))
                    || PlacementSolver.supportedFinely(player, player.level(), pos, target, new ItemStack(item), at -> unclickable(at, i)));
            helperSpot.remove(i);
            if (!supported && item != null && helperItem(player) != null) {
                // Nothing to click that gives it the right facing: a helper block put down and taken away again can.
                // Against a block that isn't a helper itself: a helper spot held up by another helper, a walk's say, never
                // goes in, as helpers aren't clicked to put one down (see placeHelper), and the block waits for it for good.
                // Not in a spot the build shuts in, where nothing reaches it to take it away again (see BuildPlan.enclosed),
                // while a block of the build beside it is still to go in and may hold it up instead: the glass at
                // 31, 78, 33 in the bone-meal crafter took one in the closed-off cell under it, with the glass beside it
                // going in a moment later, and it was left standing at the end.
                BlockPos helper = PlacementSolver.helperFor(player, pos, target, new ItemStack(item),
                        cell -> helperAllowed(player, cell) && !plan.enclosed(cell), helpers::containsKey);
                if (helper == null && !neighbourToCome(player, pos)) {
                    helper = PlacementSolver.helperFor(player, pos, target, new ItemStack(item), cell -> helperAllowed(player, cell),
                            helpers::containsKey);
                }
                if (helper != null) {
                    helperSpot.put(i, helper);
                    supported = true;
                }
            }
        }
        plan.support(i, stamp, supported);
        return plan.support(i, stamp);
    }

    /** The items in the hotbar. */
    private static Set<Item> atHandItems(LocalPlayer player) {
        Set<Item> items = new HashSet<>();
        for (int slot = 0; slot < Inventory.getSelectionSize(); slot++) items.add(player.getInventory().getItem(slot).getItem());
        return items;
    }

    /** How many farther blocks {@link #takesLastLook} weighs at most: each costs two look searches. */
    private static final int LAST_LOOK_CHECKS = 4;

    /**
     * Of {@code later}, a farther block still to go in that has a look from where the player stands now and would have
     * none with block {@code i} in place, its cell in the way of every one: the first such, or -1. Only blocks the line
     * from the eye to a face of passes through {@code i}'s cell are weighed, and a few at most.
     */
    private int takesLastLook(LocalPlayer player, int i, List<Integer> later, Set<Item> atHand, double reach) {
        if (plan.kind(i) != BlockRules.Kind.PLACE) return -1;
        Level level = player.level();
        Vec3 eye = player.getEyePosition();
        BlockPos pos = plan.pos(i);
        AABB cell = new AABB(pos).deflate(0.05);
        double near = Vec3.atCenterOf(pos).distanceToSqr(eye);
        boolean creative = player.hasInfiniteMaterials();
        Overlay placed = null;
        int checks = 0;
        for (int j : later) {
            BlockPos behind = plan.pos(j);
            Vec3 middle = Vec3.atCenterOf(behind);
            double far = middle.distanceToSqr(eye);
            if (far <= near || far > (reach + 0.5) * (reach + 0.5)) continue;
            if (plan.kind(j) != BlockRules.Kind.PLACE || plan.status(j) != BuildPlan.Status.PENDING || noClickHere.containsKey(j)) continue;
            Item item = BlockRules.item(plan.target(j));
            if (item == null || !creative && !atHand.contains(item)) continue;
            boolean inLine = cell.clip(eye, middle).isPresent();
            for (Direction side : Direction.values()) {
                if (inLine) break;
                inLine = cell.clip(eye, middle.add(side.getStepX() * 0.5, side.getStepY() * 0.5, side.getStepZ() * 0.5)).isPresent();
            }
            if (!inLine) continue;
            if (checks++ >= LAST_LOOK_CHECKS) break;
            int index = j;
            LongPredicate notThere = at -> unclickable(at, index);
            ItemStack stack = new ItemStack(item);
            if (PlacementSolver.fromSpot(player, level, player.position(), behind, plan.target(j), stack, reach, notThere) == null) continue;
            if (placed == null) placed = new Overlay(level, Map.of(pos.asLong(), plan.target(i)));
            if (PlacementSolver.fromSpot(player, placed, player.position(), behind, plan.target(j), stack, reach, notThere) == null) return j;
        }
        return -1;
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
        aimFrom = Navigator.standingAt(player.level(), player).key();
        aimFeet = player.position();
        restingTicks = 0;
        turned = 0;
        next = planned.at();
        nextIndex = planned.index();
    }

    /**
     * Turns the camera toward a look a little each tick, quickly at first and slowing as it closes in, as a hand on a
     * mouse does; true once it has rested there for as many ticks as set. A tick counts only when the look stayed put
     * through it: the server judges a click by the look the player had at the end of the tick before. The view moves by
     * whole mouse steps, as a mouse moves it, and comes to rest on the nearest such look that passes {@code check}.
     */
    private boolean looked(LocalPlayer player, float yaw, float pitch, Head.Check check) {
        float yawBefore = player.getYRot(), pitchBefore = player.getXRot();
        int offGrid = head.offGrid();
        boolean there = head.aim(player, yaw, pitch, check);
        if (debug() && head.offGrid() != offGrid) {
            LOGGER.info("No look in whole mouse steps near {} {} passed; landed on it exactly, from {} {}", yaw, pitch, yawBefore, pitchBefore);
        }
        boolean moved = Math.abs(player.getYRot() - yawBefore) > 1e-3f || Math.abs(player.getXRot() - pitchBefore) > 1e-3f;
        if (moved) turned += (float) Math.hypot(Mth.wrapDegrees(player.getYRot() - yawBefore), player.getXRot() - pitchBefore);
        if (!there || moved) {
            restingTicks = 0;
            return false;
        }
        if (restingTicks == 0) {
            restFor = settings().settle + extraRest(settings().settleSpread, turned);
            turned = 0;
        }
        return ++restingTicks >= restFor;
    }

    /**
     * Ticks more than the settle the view rests before a click: none mostly, one or two often, now and then more (a
     * geometric draw with {@code mean}, at most 5), and after a turn of more than 45 degrees one more half the time, as
     * an eye takes a moment longer to find a spot it came a long way to.
     */
    static int extraRest(double mean, float turned) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        double more = mean / (1 + mean);
        int extra = 0;
        while (extra < 5 && random.nextDouble() < more) extra++;
        if (turned > 45 && random.nextBoolean()) extra++;
        return extra;
    }

    /** What the crosshair is on: the same trace the game makes for a click. */
    private static BlockHitResult crosshair(LocalPlayer player) { return crosshair(player, ClipContext.Fluid.NONE); }

    private static BlockHitResult crosshair(LocalPlayer player, ClipContext.Fluid fluids) {
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getViewVector(1.0f).scale(player.blockInteractionRange()));
        return player.level().clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, fluids, player));
    }

    /** What the crosshair would be on with this look: the same trace, the look put in place for the moment it takes. */
    private static BlockHitResult crosshair(LocalPlayer player, float yaw, float pitch) {
        float oldYaw = player.getYRot(), oldPitch = player.getXRot();
        try {
            player.setYRot(yaw);
            player.setXRot(pitch);
            return crosshair(player);
        } finally {
            player.setYRot(oldYaw);
            player.setXRot(oldPitch);
        }
    }

    /** The block a click of the builder's own went on, and when: a menu opening just after it was that click's doing. */
    private Block clickedBlock;
    private long clickedAt;
    /** How many ticks after a click a menu that opens is taken for its doing. */
    private static final int MENU_AFTER_CLICK = 10;

    private BlockPos clickedPos;

    private void noteClick(LocalPlayer player, BlockHitResult hit) {
        clickedBlock = player.isShiftKeyDown() ? null : player.level().getBlockState(hit.getBlockPos()).getBlock();
        clickedPos = hit.getBlockPos();
        clickedAt = tick;
    }

    /**
     * Whether the block clicked at {@code pos} can have a menu at all: one it hands out, or a block entity, as a chest's or
     * an ender chest's. A menu the server opens by itself just after a click, as a server's own check or shop window, is
     * none of the click's doing: it is left open, as the player's, rather than closed and the block learnt wrong.
     */
    private static boolean hasMenu(Level level, BlockPos pos) {
        if (level == null || pos == null) return false;
        BlockState state = level.getBlockState(pos);
        return state.getMenuProvider(level, pos) != null || level.getBlockEntity(pos) != null;
    }

    /** A check that the crosshair, with a look, is on {@code pos}, and on its {@code face} unless that is null. */
    private static Head.Check onBlock(LocalPlayer player, BlockPos pos, Direction face) {
        return (yaw, pitch) -> {
            BlockHitResult hit = crosshair(player, yaw, pitch);
            return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos) && (face == null || hit.getDirection() == face);
        };
    }

    /**
     * Whether a click with this look goes where it was planned: the crosshair on the face worked out, a gate opened from
     * the side that leaves it facing as it should, and a block placed as planned, a margin off any edge between two
     * facings.
     */
    private boolean clicksAsPlanned(LocalPlayer player, Aim planned, float yaw, float pitch) {
        BlockHitResult planHit = planned.click().hit();
        if (planned.act() == Act.FILL_BUCKET) {
            // An empty bucket takes the first water source on the look, which the crosshair otherwise looks through.
            float oldYaw = player.getYRot(), oldPitch = player.getXRot();
            try {
                player.setYRot(yaw);
                player.setXRot(pitch);
                BlockHitResult hit = crosshair(player, ClipContext.Fluid.SOURCE_ONLY);
                return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(planHit.getBlockPos());
            } finally {
                player.setYRot(oldYaw);
                player.setXRot(oldPitch);
            }
        }
        if (!onBlock(player, planHit.getBlockPos(), planHit.getDirection()).ok(yaw, pitch)) return false;
        if (planned.act() == Act.ADJUST && planned.index() >= 0 && plan.target(planned.index()).getBlock() instanceof FenceGateBlock
                && Direction.fromYRot(yaw) == plan.target(planned.index()).getValue(FenceGateBlock.FACING).getOpposite()) {
            return false;
        }
        if (planned.act() != Act.PLACE) return true;
        float oldYaw = player.getYRot(), oldPitch = player.getXRot();
        try {
            player.setYRot(yaw);
            player.setXRot(pitch);
            return placesTarget(player, planned, crosshair(player));
        } finally {
            player.setYRot(oldYaw);
            player.setXRot(oldPitch);
        }
    }

    /** Lines the click up and, once the crosshair rests on the face it was meant for, clicks. */
    private void fire(Minecraft client, LocalPlayer player) {
        Aim planned = aim;
        ClientLevel level = client.level;
        // A block to fill with water that is gone, or stands otherwise, takes none: the bucket would pour into its spot.
        boolean changed = planned.act() == Act.POUR
                ? plan.update(level, planned.index()) != BuildPlan.Status.PENDING
                        || plan.kind(planned.index()) != BlockRules.Kind.FLUID && !plan.soaking(planned.index()) && !plan.needsPour(planned.index())
                : level.getBlockState(planned.at()) != planned.before();
        if (changed) {
            aim = null;
            return;
        }
        if (player.position().subtract(aimFeet).horizontalDistanceSqr() > CARRIED * CARRIED || aimFeet.y - player.getY() > 0.5) {
            // Carried off while turning, by water running past or a push: the look is aimed again from the eye each tick, so
            // it would follow the player along, to wherever they come to rest, out of reach or with something in the way.
            // Worked out again from where they are, and walks don't end where it happened again.
            if (debug()) LOGGER.info("Carried from {} to {} while lining up {}: working it out again", aimFeet, player.position(),
                    planned.index() < 0 ? planned.at().toShortString() : plan.target(planned.index()));
            carriedFrom.add(aimFrom);
            aim = null;
            return;
        }
        PlacementSolver.Click click = planned.click();
        if (click.crouch() != player.isShiftKeyDown()) {
            setCrouch(client, click.crouch());
            return;
        }
        // Aimed from where the eye is now, not where it was when the click was worked out: a few centimetres of slide or
        // crouch would otherwise move a look that only just clears an edge onto it. Only where that look still gives
        // the block its facing; else the look worked out stands.
        float[] aimed = PlacementSolver.look(player.getEyePosition(), click.hit().getLocation());
        if (planned.act() == Act.PLACE && !sameResult(player, planned, aimed)) aimed = new float[] {click.yaw(), click.pitch()};
        // A gate opened from the side it faces away from swings round to face the player: the look worked out keeps clear
        // of that side, and one worked out again from here must too.
        if (planned.act() == Act.ADJUST && planned.index() >= 0 && plan.target(planned.index()).getBlock() instanceof FenceGateBlock
                && Direction.fromYRot(aimed[0]) == plan.target(planned.index()).getValue(FenceGateBlock.FACING).getOpposite()) {
            aimed = new float[] {click.yaw(), click.pitch()};
        }
        if (!looked(player, aimed[0], aimed[1], (yaw, pitch) -> clicksAsPlanned(player, planned, yaw, pitch))) return;
        // The camera turned during the wait since the last click; the click itself waits for its time.
        if (System.nanoTime() - nextClick < 0) return;
        if (!handReady(player)) return;
        if (planned.act() == Act.PLACE && !headAgrees(player, planned)) return;
        aim = null;
        boolean filling = planned.act() == Act.FILL_BUCKET;
        BlockHitResult hit = crosshair(player, filling ? ClipContext.Fluid.SOURCE_ONLY : ClipContext.Fluid.NONE);
        if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(click.hit().getBlockPos())
                || !filling && hit.getDirection() != click.hit().getDirection()) {
            // Something moved into the line, or the player did: work it out again, with this block left for later from
            // here, so a look that only just reaches its face isn't tried over and over.
            if (debug()) LOGGER.info("Crosshair on {} {} instead of {} {}", hit.getBlockPos().toShortString(), hit.getDirection(),
                    click.hit().getBlockPos().toShortString(), click.hit().getDirection());
            if (!player.blockPosition().equals(missedAt)) missedHere.clear();
            missedAt = player.blockPosition();
            if (filling) return;
            missedHere.add(planned.index());
            missedFrom.add(Navigator.pairKey(aimFrom, planned.index()));
            return;
        }
        if (planned.act() != Act.ADJUST && !player.getMainHandItem().is(planned.item()) && !player.hasInfiniteMaterials()) return;
        if (planned.act() == Act.PLACE && !placesTarget(player, planned, hit)) {
            // On the face, but a hair off where the click was worked out, on the other side of a door's middle say, which
            // hinges it the other way: worked out again, from another spot on the face.
            if (debug()) LOGGER.info("A click at {} wouldn't give {}: aiming again", hit.getLocation(), plan.target(planned.index()));
            if (!player.blockPosition().equals(missedAt)) missedHere.clear();
            missedAt = player.blockPosition();
            missedHere.add(planned.index());
            missedFrom.add(Navigator.pairKey(aimFrom, planned.index()));
            return;
        }
        if (planned.act() != Act.POUR && !filling) noteClick(player, hit);
        InteractionResult result = planned.act() == Act.POUR || filling ? client.gameMode.useItem(player, InteractionHand.MAIN_HAND)
                : client.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
        if (result instanceof InteractionResult.Success success && success.swingSource() == InteractionResult.SwingSource.CLIENT) {
            player.swing(InteractionHand.MAIN_HAND);
        }
        clicked(planned, hit);
    }

    /**
     * Whether the looks of the last few ticks, as the server may still have them for the player's head, all place the
     * planned block too. The server turns an observer, a piston or a dispenser by the head, which takes on the look in
     * the player's own tick, after the packets that bring it: a click that comes with the look that came to rest, or
     * close behind it, is judged by a look a tick or two older, the last step of the turn. While one of those would
     * place the block another way the click waits, a tick or two at most, and only then; most clicks never do.
     */
    private boolean headAgrees(LocalPlayer player, Aim planned) {
        ItemStack stack = new ItemStack(planned.item());
        BlockState target = plan.target(planned.index());
        for (float yaw : recentYaws) {
            if (Float.isNaN(yaw)) continue;
            BlockState placed = PlacementSolver.simulate(player, planned.at(), stack, planned.click().hit(), yaw, player.getXRot(),
                    player.isShiftKeyDown(), false);
            if (placed == null || !BlockRules.placementMatches(placed, target)) return false;
        }
        return true;
    }

    /**
     * Whether clicking where the crosshair is, as the player looks and stands now, places the planned block, with the
     * look a margin off any edge between two facings.
     */
    private boolean placesTarget(LocalPlayer player, Aim planned, BlockHitResult hit) {
        float[] look = {player.getYRot(), player.getXRot()};
        ItemStack stack = new ItemStack(planned.item());
        BlockState target = plan.target(planned.index());
        BlockState placed = PlacementSolver.simulate(player, planned.at(), stack, hit, look[0], look[1], player.isShiftKeyDown(), false);
        return placed != null && BlockRules.placementMatches(placed, target)
                && PlacementSolver.keeps(player, planned.at(), stack, hit, look, player.isShiftKeyDown(), false, target, false, PlacementSolver.MARGIN);
    }

    /**
     * Whether a look places the planned block just as the look it was worked out with does, a margin off any edge between
     * two facings.
     */
    private boolean sameResult(LocalPlayer player, Aim planned, float[] look) {
        ItemStack stack = new ItemStack(planned.item());
        BlockState target = plan.target(planned.index());
        PlacementSolver.Click click = planned.click();
        BlockState placed = PlacementSolver.simulate(player, planned.at(), stack, click.hit(), look[0], look[1], click.crouch(), false);
        return placed != null && BlockRules.placementMatches(placed, target)
                && PlacementSolver.keeps(player, planned.at(), stack, click.hit(), look, click.crouch(), false, target, false, PlacementSolver.MARGIN);
    }

    /** What follows a click: the next wait, and what the status line says. */
    private void clicked(Aim planned, BlockHitResult hit) {
        int i = planned.index(), layerNumber = planned.layerNumber();
        if (planned.act() == Act.PLACE || planned.act() == Act.POUR) {
            // A block went in: places passed over since may do again. A helper block alone isn't one: a place where only
            // helpers go down, one after another, and never the block, is still passed over.
            walksSinceClick = 0;
            uselessSpots.clear();
            approached.clear();
            clickedSinceWalk = true;
        }
        forgetNoClicks();
        walkFailures = 0;
        String layer = "Layer " + layerNumber + " of " + plan.layers().size() + " (y " + layerY + "): ";
        switch (planned.act()) {
            case FILL_BUCKET -> {
                filledFrom = planned.at();
                filledAt = tick;
                // An opened cell: the water around runs into it until it comes back.
                pours.put(planned.at().asLong(), tick);
                paceAfterClick(quick());
                if (debug()) LOGGER.info("Filled a bucket from the water at {}", planned.at().toShortString());
                show("Filled a bucket with water at " + planned.at().getX() + " " + planned.at().getY() + " " + planned.at().getZ(), false);
                return;
            }
            case REFILL -> {
                BlockState was = dug.remove(planned.at().asLong());
                if (was != null && !planned.item().equals(was.getBlock().asItem())) filledOther.put(planned.at().asLong(), was);
                paceAfterClick(quick());
                show("Filling in the ground dug out at " + planned.at().getX() + " " + planned.at().getY() + " " + planned.at().getZ(), false);
                return;
            }
            case HELPER -> {
                helpers.put(planned.at().asLong(), new Helper(i, Block.byItem(planned.item())));
                if (debug()) LOGGER.info("Helper block at {} for {} at {}", planned.at().toShortString(), plan.target(i), plan.pos(i).toShortString());
                paceAfterClick(quick());
                show(layer + "a helper block to place " + plan.target(i).getBlock().getName().getString() + " against", false);
                return;
            }
            case ADJUST -> {
                plan.tried(i, planned.before());
                paceAfterClick(quick());
                show(layer + "setting " + planned.before().getBlock().getName().getString(), false);
            }
            case TILL -> {
                plan.tried(i, planned.before());
                paceAfterClick(quick());
                show(layer + "making " + plan.target(i).getBlock().getName().getString(), false);
            }
            case PLACE, POUR -> {
                if (plan.target(i).getBlock() instanceof SignBlock) {
                    signPlacedAt = tick;
                    signDoneAt = tick + 10 + ThreadLocalRandom.current().nextInt(19);
                }
                if (planned.act() == Act.POUR) {
                    pours.put(planned.at().asLong(), tick);
                    pourCheck = planned.at();
                    pourCheckAt = tick + 10;
                }
                placedSincePit = true;
                restockSwaps = 0;
                if (planned.act() == Act.PLACE) placedAt.put(i, tick);
                // An up-facing block in: what waited for one gets its minutes again, should another stall the build.
                if (planned.act() == Act.PLACE && BlockRules.placedLookingUp(plan.target(i))) sealsLiftedAt = UNLIFTED;
                sealsLifted = false;
                orderLifted = false;
                shutIn.remove(i);
                // A block in changes what a pit beside it can do.
                BlockPos in = planned.at();
                uselessPits.removeIf(p -> Math.abs(p.x() - in.getX()) <= 3 && Math.abs(p.z() - in.getZ()) <= 3);
                pitTries.keySet().removeIf(p -> Math.abs(p.x() - in.getX()) <= 3 && Math.abs(p.z() - in.getZ()) <= 3);
                if (debug()) {
                    LocalPlayer player = Minecraft.getInstance().player;
                    boolean water = planned.act() == Act.POUR && plan.kind(i) != BlockRules.Kind.FLUID;
                    LOGGER.info("{} {} at {}: {} face of {} at {}, look {} {} (clicked with {} {}){}", water ? "Poured water for" : "Placed", plan.target(i),
                            planned.at().toShortString(), hit.getDirection(), hit.getBlockPos().toShortString(), hit.getLocation(),
                            planned.click().yaw(), planned.click().pitch(), player == null ? "?" : Mth.wrapDegrees(player.getYRot()),
                            player == null ? "?" : player.getXRot(), planned.click().crouch() ? ", crouching" : "");
                }
                // Water flowing by keeps changing what a pour finds there, so its tries count against one state.
                plan.tried(i, planned.act() == Act.POUR ? Blocks.AIR.defaultBlockState() : planned.before());
                paceAfterClick(delay());
                BlockPos pos = planned.at();
                String done = planned.act() != Act.POUR || plan.kind(i) == BlockRules.Kind.FLUID ? "placed "
                        : planned.before().getBlock() == plan.target(i).getBlock() ? "poured water into " : "poured water for ";
                show(layer + done + plan.target(i).getBlock().getName().getString() + " at " + pos.getX() + " " + pos.getY() + " "
                        + pos.getZ(), false);
            }
        }
        // What most clicks did is in the world already, as the client shows it (a bucket poured into a block, and a note
        // block, lever or daylight detector clicked, wait for the server): read back now rather than with the plan's
        // refresh, a few ticks behind on a big build, so a walk planned before then doesn't count on this block once more.
        plan.update(Minecraft.getInstance().level, i);
        updateProgress(layerNumber);
    }

    /**
     * Paces the next click {@code wait} nanoseconds after this one. The next block is picked and the camera turns to
     * it in the meantime, after a moment to react, as a player turns while waiting.
     */
    private void paceAfterClick(long wait) {
        long now = System.nanoTime();
        nextClick = now + wait;
        long react = (long) (settings().reaction * 1_000_000_000L);
        nextAction = now + Math.min(wait, react <= 0 ? 0 : ThreadLocalRandom.current().nextLong(react / 3, react + 1));
    }

    /**
     * Paces what follows something done with the hands other than a click, a hotbar swap, a block broken, a shulker box
     * picked up, as a click is: the next click, or anything else the hands do (mining, a box put down), no sooner than
     * {@code wait} from now, as before, and the turn toward it after a moment to react, rather than standing still for
     * all of it. The view never moves on the very tick a screen shuts or a block breaks: the tick that finishes them
     * decides nothing more.
     */
    private void paceAfterHands(long wait) {
        long now = System.nanoTime();
        nextClick = Math.max(nextClick, now + wait);
        handsAt = now + wait;
        long react = (long) (settings().reaction * 1_000_000_000L);
        nextAction = now + Math.min(wait, react <= 0 ? 0 : ThreadLocalRandom.current().nextLong(react / 3, react + 1));
    }

    /** A short wait for clicks that follow each other: a repeater set right after placing it, a helper taken away. */
    private static long quick() { return ThreadLocalRandom.current().nextLong(150_000_000L, 320_000_000L); }

    /**
     * How much the waits lean toward the shortest delay: a player's clicks mostly follow each other quickly, now and then
     * after a longer moment, rather than at any time in between as often.
     */
    private static final double DELAY_LEAN = 1.8;

    /**
     * The configured wait before the next block, in nanoseconds: anywhere between the shortest and longest delay, the
     * shorter waits more often.
     */
    private long delay() {
        double min = settings().minDelay, max = settings().maxDelay;
        double seconds = max > min ? min + (max - min) * Math.pow(ThreadLocalRandom.current().nextDouble(), DELAY_LEAN) : min;
        return (long) (seconds * 1_000_000_000L);
    }

    // Water ------------------------------------------------------------------------------------------------------

    /** Whether the water buckets carried are few and no water about comes back to fill more from (see renewingWater). */
    private boolean lowOnWater(LocalPlayer player, Materials materials) {
        if (materials.total(Items.WATER_BUCKET) > 3) return false;
        if (tick - waterTick > 40) {
            renewing = renewingWater(player);
            waterTick = tick;
        }
        return renewing.isEmpty();
    }

    /**
     * Whether one of {@code candidates} is a water source of the build still to pour that makes a pool, one that comes
     * back once filled from: beside it a source in the world already, or another such water source of the build.
     */
    private boolean poolToMake(Level level, List<Integer> candidates) {
        for (int j : candidates) {
            if (plan.kind(j) != BlockRules.Kind.FLUID || plan.status(j) != BuildPlan.Status.PENDING) continue;
            BlockState target = plan.target(j);
            if (!target.getFluidState().isSource() || !target.getFluidState().getType().isSame(Fluids.WATER)) continue;
            BlockPos pos = plan.pos(j);
            for (Direction side : Direction.Plane.HORIZONTAL) {
                BlockPos beside = pos.relative(side);
                FluidState there = level.getFluidState(beside);
                if (there.isSource() && there.getType().isSame(Fluids.WATER)) return true;
                int k = plan.indexOf(beside);
                if (k >= 0 && plan.kind(k) == BlockRules.Kind.FLUID && plan.target(k).getFluidState().isSource()) return true;
            }
        }
        return false;
    }

    /**
     * Fills an empty bucket from water that comes back, from where the player stands or after a walk to it: an empty
     * bucket out of a shulker box first if none is loose. False when there is no such water to get to.
     */
    private boolean fetchWater(Minecraft client, LocalPlayer player, Materials materials, int layerNumber) {
        if (materials.loose(Items.BUCKET) == 0) {
            startRestock(client, player, Items.BUCKET, materials);
            return true;
        }
        if (tick - waterTick > 40) {
            renewing = renewingWater(player);
            waterTick = tick;
        }
        if (renewing.isEmpty()) return false;
        double reach = player.blockInteractionRange();
        Vec3 eye = player.getEyePosition();
        for (BlockPos cell : renewing) {
            PlacementSolver.Click click = fillLook(player, eye, cell, reach, player.getViewVector(1.0f));
            if (click == null) continue;
            if (player.isShiftKeyDown()) {
                stance(client, false);
                return true;
            }
            startAim(client, player, new Aim(Act.FILL_BUCKET, -1, click, Items.BUCKET, cell, player.level().getBlockState(cell), layerNumber));
            return true;
        }
        if (!settings().walk) return false;
        List<BlockPos> sources = renewing, poured = recentPours();
        Map<Long, Boolean> wetCells = new HashMap<>();
        Navigator.Path path = navigator(player, true).find(Navigator.standingAt(player.level(), player), node -> {
            // Never from in the water, or where it is about to run: it pushes the player off the look.
            if (wetSoon(player.level(), node.feetCell(), poured, wetCells)) return 0;
            for (BlockPos cell : sources) {
                if (Vec3.atCenterOf(cell).distanceTo(node.eye()) > reach + 1) continue;
                if (fillLook(player, node.eye(), cell, reach, null) != null) return 1;
            }
            return 0;
        });
        if (path == null || path.nodes().size() < 2 || dropsIntoTrap(player, path)) return false;
        releaseKeys(client);
        walker.start(path.nodes());
        show("Walking to water to fill a bucket", false);
        return true;
    }

    /**
     * Water sources around the player that come back when a bucket is filled from them, the build's own last and the
     * nearest first: two water sources beside one and something solid or more water under it make it a source again,
     * as vanilla does. Water the build pours itself counts, where it is laid out that way.
     */
    private List<BlockPos> renewingWater(LocalPlayer player) {
        Level level = player.level();
        BlockPos center = player.blockPosition();
        List<BlockPos> found = new ArrayList<>();
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int dy = -6; dy <= 6; dy++) {
            for (int dx = -16; dx <= 16; dx++) {
                for (int dz = -16; dz <= 16; dz++) {
                    at.setWithOffset(center, dx, dy, dz);
                    BlockState state = level.getBlockState(at);
                    if (!(state.getBlock() instanceof LiquidBlock) || !state.getFluidState().isSource()
                            || !state.getFluidState().is(FluidTags.WATER) || finiteSources.contains(at.asLong())) continue;
                    // Not a spot of the build still to go in, as kelp poured into first (da-45): the bucket would take the
                    // water it waits for, to be poured back (v1-clean6, 9,69,16, twice).
                    int j = plan.indexOf(at);
                    if (j >= 0 && plan.status(j) == BuildPlan.Status.PENDING) continue;
                    int sources = 0;
                    for (Direction side : Direction.Plane.HORIZONTAL) {
                        FluidState beside = level.getFluidState(at.relative(side));
                        if (beside.isSource() && beside.is(FluidTags.WATER)) sources++;
                    }
                    if (sources < 2) continue;
                    BlockPos below = at.below();
                    FluidState under = level.getFluidState(below);
                    if (!level.getBlockState(below).isSolid() && !(under.isSource() && under.is(FluidTags.WATER))) continue;
                    found.add(at.immutable());
                }
            }
        }
        // Water that isn't the build's first, so the build's own is left alone where there is other.
        Vec3 eye = player.getEyePosition();
        found.sort(Comparator.comparingInt((BlockPos cell) -> plan.indexOf(cell) >= 0 ? 1 : 0)
                .thenComparingDouble(cell -> Vec3.atCenterOf(cell).distanceToSqr(eye)));
        return found;
    }

    /**
     * A look from {@code eye} that an empty bucket takes water from {@code cell} with: the first water source on it,
     * through anything that isn't one, is that cell. Its top is tried, then its sides; with {@code aim}, the way the
     * player looks now, the points least turn away come first, give or take, as for a block placed.
     */
    private static PlacementSolver.Click fillLook(LocalPlayer player, Vec3 eye, BlockPos cell, double reach, Vec3 aim) {
        Level level = player.level();
        double height = level.getFluidState(cell).getHeight(level, cell);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        List<Vec3> points = new ArrayList<>();
        // A little off the same few points each time, as a hand aims: never the very middle of the water every fill.
        for (double a : new double[] {0.5, 0.25, 0.75}) {
            for (double b : new double[] {0.5, 0.25, 0.75}) {
                points.add(new Vec3(cell.getX() + a + (random.nextDouble() * 2 - 1) * 0.06, cell.getY() + height - 0.01,
                        cell.getZ() + b + (random.nextDouble() * 2 - 1) * 0.06));
            }
        }
        for (Direction side : Direction.Plane.HORIZONTAL) {
            double across = (random.nextDouble() * 2 - 1) * 0.2, up = (random.nextDouble() * 2 - 1) * 0.2 * height;
            points.add(Vec3.atCenterOf(cell).add(side.getStepX() * 0.49 + side.getStepZ() * across, height / 2 - 0.5 + up,
                    side.getStepZ() * 0.49 + side.getStepX() * across));
        }
        if (aim != null) {
            // The nearest to the crosshair first, give or take: a few degrees apart are as near as each other by the turn.
            Map<Vec3, Double> away = new IdentityHashMap<>();
            for (Vec3 point : points) {
                double angle = Math.toDegrees(Math.acos(Mth.clamp(aim.dot(point.subtract(eye).normalize()), -1, 1)));
                away.put(point, angle * (0.8 + 0.4 * random.nextDouble()) + 3 * random.nextDouble());
            }
            points.sort(Comparator.comparingDouble(away::get));
        }
        for (Vec3 point : points) {
            if (point.distanceTo(eye) > reach) continue;
            Vec3 end = eye.add(point.subtract(eye).normalize().scale(reach));
            BlockHitResult hit = level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.SOURCE_ONLY, player));
            if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(cell)) continue;
            float[] look = PlacementSolver.look(eye, point);
            return new PlacementSolver.Click(hit, look[0], look[1], false);
        }
        return null;
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

    /** Whether the stack is helper blocks: of the items {@link #helperItem} picks from, which the build doesn't need. */
    private boolean isHelper(ItemStack stack) {
        return !stack.isEmpty() && helperItems.contains(stack.getItem()) && (plan == null || !plan.needs().containsKey(stack.getItem()));
    }

    /**
     * How many climbing steps one walk can take with the helper blocks carried: those in the hotbar, where each jump
     * takes one from, and the biggest stack in the inventory, which {@link #climbReady} moves in for a long climb.
     */
    private int climbBudget(LocalPlayer player) {
        if (player.hasInfiniteMaterials()) return Integer.MAX_VALUE;
        Inventory inventory = player.getInventory();
        int hotbar = 0, biggest = 0;
        for (int slot = 0; slot < Materials.SLOTS; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!isHelper(stack)) continue;
            if (slot < Inventory.getSelectionSize()) hotbar += stack.getCount();
            else biggest = Math.max(biggest, stack.getCount());
        }
        return hotbar + biggest;
    }

    /**
     * Before a walk that climbs: a helper block in the hotbar for each climbing step, with the biggest stack in the
     * inventory moved in, into a slot that holds none, where those there are too few. False while it is moved, to set
     * off after; true when nothing more can be moved in, and the walk goes as far as they last.
     */
    private boolean climbReady(Minecraft client, LocalPlayer player, Navigator.Path path) {
        long climbs = path.nodes().stream().filter(node -> node.move().placesBlock()).count();
        if (climbs == 0 || player.hasInfiniteMaterials()) return true;
        Inventory inventory = player.getInventory();
        // A climb out of ground dug out fills it with what was there, from the hotbar (see walk's Climb.ready): moved in
        // first from the inventory where it is only there, so the ground isn't filled with helper blocks instead.
        for (Navigator.Node node : path.nodes()) {
            BlockState was = dug.get(node.feetCell().asLong());
            if (was == null) continue;
            Item item = was.getBlock().asItem();
            int from = -1;
            boolean inHotbar = false;
            for (int slot = 0; slot < Materials.SLOTS; slot++) {
                if (!inventory.getItem(slot).is(item)) continue;
                if (slot < Inventory.getSelectionSize()) inHotbar = true;
                else if (from < 0) from = slot;
            }
            if (inHotbar || from < 0) break;
            int to = emptyHotbarSlot(inventory);
            if (to < 0) {
                int least = buildSlot(inventory);
                if (!isHelper(inventory.getItem(least))) to = least;
            }
            if (to < 0) break;
            startSwap(client, player, from, to);
            return false;
        }
        int hotbar = 0, from = -1;
        for (int slot = 0; slot < Materials.SLOTS; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!isHelper(stack)) continue;
            if (slot < Inventory.getSelectionSize()) hotbar += stack.getCount();
            else if (from < 0 || stack.getCount() > inventory.getItem(from).getCount()) from = slot;
        }
        if (hotbar >= climbs || from < 0) return true;
        int to = emptyHotbarSlot(inventory);
        if (to < 0) {
            int least = buildSlot(inventory);
            if (!isHelper(inventory.getItem(least))) to = least;
        }
        if (to < 0) return true;
        startSwap(client, player, from, to);
        return false;
    }

    /** How many helper blocks the player carries outside shulker boxes. */
    private int helpersLoose(LocalPlayer player) {
        Inventory inventory = player.getInventory();
        int count = 0;
        for (int slot = 0; slot < Materials.SLOTS; slot++) if (isHelper(inventory.getItem(slot))) count += inventory.getItem(slot).getCount();
        return count;
    }

    /**
     * The helper block to take out of a shulker box: one the boxes carried hold, when fewer than {@link #HELPERS_LOW} are
     * left outside them; null when enough are, or a fetch was tried in the last minute.
     */
    private Item helperToFetch(LocalPlayer player, Materials materials) {
        helperItem(player);
        int loose = helpersLoose(player);
        // Once a minute while some are left; with none left at all, every ten seconds: a full box is then swapped for
        // room, and with nothing to swap the box isn't opened over and over.
        if (tick - helperFetchTick < (loose > 0 ? 1200 : 200)) return null;
        if (loose >= HELPERS_LOW) return null;
        Map<Item, Integer> needs = plan.needs();
        for (Item item : helperItems) if (!needs.containsKey(item) && materials.boxed(item) > 0) return item;
        return null;
    }

    /**
     * Short of helper blocks, walks over dropped ones lying near, as those broken from a distance leave: a few steps
     * away, without climbing. False when there are plenty, or none to get to.
     */
    private boolean pickUpHelpers(Minecraft client, LocalPlayer player) {
        if (player.hasInfiniteMaterials()) return pickupSweep = false;
        helperItem(player);
        int loose = helpersLoose(player);
        boolean helpersFull = loose >= HELPER_STACKS * 64;
        if (helpersFull && repairDrops.isEmpty()) return pickupSweep = false;
        // With a stack or more left, a dropped helper block is worth a step or two, not a walk: one block back doesn't
        // pay for the time. Really short of them, it is, and so is each one near once a layer is done: none is left lying
        // in the build, where a hopper or a water stream would carry it into the farm.
        boolean helpersShort = loose < 64;
        AABB area = pickupSweep ? plan.bounds.inflate(4) : player.getBoundingBox().inflate(8, 4, 8);
        List<ItemEntity> drops = player.level().getEntitiesOfClass(ItemEntity.class, area,
                item -> (!helpersFull && isHelper(item.getItem()) || repairDrops.contains(item.getItem().getItem()))
                        && pickupTried.getOrDefault(item.getId(), 0) < (repairDrops.contains(item.getItem().getItem()) ? 3 : 1)
                        && (pickupSweep || loose < PICKUP_LAST || !pickupBelow.contains(item.getId())));
        drops.sort(Comparator.comparingDouble(item -> item.distanceToSqr(player)));
        Navigator.Node start = Navigator.standingAt(player.level(), player);
        for (ItemEntity drop : drops) {
            boolean anyway = helpersShort || pickupSweep || repairDrops.contains(drop.getItem().getItem());
            int most = pickupSweep ? 30 : anyway ? 12 : 3;
            // Further off than that, it is left for now, for when the player passes by.
            if (drop.distanceTo(player) > most + 2) continue;
            // A block or two just dropped waits a while: more fall near it, or a walk for the build passes them.
            if (!anyway && drop.tickCount < PICKUP_WAIT && helpersAround(player, drop) < PICKUP_HANDFUL) continue;
            boolean needed = repairDrops.contains(drop.getItem().getItem());
            if (!needed) pickupTried.merge(drop.getId(), 1, Integer::sum);
            AABB item = drop.getBoundingBox();
            // Picked up within a block of the body sideways and half a block up or down, as vanilla does; planned from the
            // middle of the block half a block short of that sideways, as a walk comes to rest up to about that far off it.
            Navigator.Path path = navigator(player, false).limit(pickupSweep ? 8000 : 1500).find(start, node -> new AABB(node.x() + 0.2, node.feet(),
                    node.z() + 0.2, node.x() + 0.8, node.feet() + 1.8, node.z() + 0.8).inflate(1 - PICKUP_SLACK, 0.5, 1 - PICKUP_SLACK)
                    .intersects(item) ? 1 : 0);
            if (path == null || path.nodes().size() < 2 || dropsIntoTrap(player, path)) continue;
            if (path.nodes().size() - 1 > most) {
                if (!needed) pickupTried.merge(drop.getId(), -1, Integer::sum);
                continue;
            }
            // Down off the layer for a block or two, the way back up is climbed again, a helper block for each step up:
            // more than the walk brings back. Left for the layer's sweep, or until there are a handful there or none at all
            // is left in hand.
            if (!needed && !pickupSweep && start.feet() - path.end().feet() > 1 && loose >= PICKUP_LAST
                    && helpersAround(player, drop) < PICKUP_HANDFUL) {
                pickupTried.merge(drop.getId(), -1, Integer::sum);
                pickupBelow.add(drop.getId());
                continue;
            }
            // A drop the build needs back: a try for each walk made to it, none for one there is no way to yet.
            if (needed) pickupTried.merge(drop.getId(), 1, Integer::sum);
            releaseKeys(client);
            walker.start(path.nodes());
            show("Picking up " + name(drop.getItem().getItem()) + " dropped nearby", false);
            if (debug()) LOGGER.info("Walking {} steps to pick up {} at {}", path.nodes().size() - 1, drop.getItem(), drop.position());
            return true;
        }
        pickupSweep = false;
        return false;
    }

    /** How many dropped helper blocks lie together around {@code drop}, as one walk there picks them up. */
    private int helpersAround(LocalPlayer player, ItemEntity drop) {
        int count = 0;
        for (ItemEntity item : player.level().getEntitiesOfClass(ItemEntity.class, drop.getBoundingBox().inflate(1.5, 1, 1.5),
                item -> isHelper(item.getItem()))) {
            count += item.getItem().getCount();
        }
        return count;
    }

    /** How far off the middle of a block a walk to pick something up may come to rest, sideways. */
    private static final double PICKUP_SLACK = 0.5;

    /**
     * Whether the water for block {@code i}, broken to go in again into water, waits for what breaking it dropped to be
     * picked up: the water would carry it off. Without another of its item at hand it waits however long that takes; with
     * one, ten seconds at most.
     */
    private boolean waitsForDrop(Level level, int i, Materials materials) {
        if (!plan.needsPour(i)) return false;
        Item own = BlockRules.item(plan.target(i));
        if (own == null || level.getEntitiesOfClass(ItemEntity.class, new AABB(plan.pos(i)).inflate(8, 4, 8),
                drop -> drop.getItem().is(own)).isEmpty()) return false;
        return materials.total(own) == 0 || tick - drainedAt.getOrDefault(i, 0L) < 200;
    }

    private final Map<Integer, Long> pourLogged = new HashMap<>();

    /** Whether a helper block may go here: an empty, dry spot the schematic leaves empty, or only has water run through. */
    private boolean helperAllowed(LocalPlayer player, BlockPos cell) { return helperAllowed(player, player.level(), cell); }

    private boolean helperAllowed(LocalPlayer player, BlockGetter view, BlockPos cell) {
        BlockState state = view.getBlockState(cell);
        // A helper block just clicked in may not be there yet; one a what-if takes away is gone.
        boolean helper = helpers.containsKey(cell.asLong()) && !(view instanceof Overlay overlay && overlay.changes(cell));
        if (!state.canBeReplaced() || helper) return false;
        if (cleaning && cleared.contains(cell.asLong())) return false;
        // A guard against a current into a fall (see guardFor): in water, or water the build has there, for a while.
        if (guardSpot.containsValue(cell)) return true;
        // Not where the look up at a block still to go in that takes one comes from (see inLookUpChannel).
        if (!escaping && inLookUpChannel(cell)) return false;
        int i = plan.indexOf(cell);
        boolean water = i >= 0 && (plan.kind(i) == BlockRules.Kind.FLUID || plan.kind(i) == BlockRules.Kind.AUTO)
                && plan.target(i).getBlock() instanceof LiquidBlock && plan.target(i).getFluidState().is(FluidTags.WATER);
        // Never in a spot that grows kelp or seagrass, not even on the way out of a pocket: that spot holds the water of its
        // column, and a helper there shuts the water out of the kelp under it for good, as dirt in 13,72,16 did in v4-o.
        if (i >= 0 && plan.plant(i)) {
            // But climbing out of a kelp channel the player stands in, walled in once its last block went in (v4-full12,
            // the dispensers 3..17,70,3 over the channel z 4): a pillar up through the channel's own cells, with no water
            // standing in them yet. It holds the layer and comes away once the player is out, as any climb out.
            int here = plan.indexOf(Navigator.standingAt(player.level(), player).feetCell());
            return escaping && here >= 0 && plan.plant(here) && !state.getFluidState().isSource();
        }
        // Nor in a spot a water source goes into over kelp or seagrass, dry, with water running through or with its source
        // already in: that source fills the column under it (5,72,4 and 6,72,4 over the kelp at 6,70,4 in v4-p), and a
        // helper bridged into it once poured was taken away and bridged in again and again (v4-r, 5,72,4 and 14,72,4).
        // Other source spots stay open to a climb out of a pit or a flooded shaft.
        if (i >= 0 && plan.kind(i) == BlockRules.Kind.FLUID && plan.target(i).getFluidState().isSource() && feedsPlant(cell)) return false;
        if (!state.getFluidState().isEmpty()) {
            // Water run out of the build onto the ground round it, not a source and not falling: a helper block goes in as
            // into air, and the water runs back once it is gone. The cell is the schematic's to leave empty.
            FluidState fluid = state.getFluidState();
            if (i < 0 && fluid.is(FluidTags.WATER) && !fluid.isSource()
                    && !(fluid.hasProperty(FlowingFluid.FALLING) && fluid.getValue(FlowingFluid.FALLING))) return true;
            // Water in the way only for a way on found no other way: then a helper block may go into water the build has
            // there anyway, as a player builds up through a flooded shaft to reach its bottom, and the water is poured
            // again once the block is gone. Water poured too early, over blocks still to place below it, otherwise
            // leaves no place to stand above them.
            return escaping && water && state.getFluidState().is(FluidTags.WATER);
        }
        if (i < 0 || plan.kind(i) == BlockRules.Kind.AUTO && plan.target(i).getBlock() instanceof LiquidBlock) return true;
        // With no other place to stand, a walk may climb through cells the build fills later: those blocks wait until the
        // player is out.
        return escaping && (plan.status(i) == BuildPlan.Status.PENDING || view instanceof Overlay overlay && overlay.changes(cell));
    }

    /**
     * Whether water source {@code i} waits for kelp or seagrass beside where its water lands, still to go in, with no
     * water a block deep in its spot yet: poured first, its water falls down the open cells under it, kelp spots among
     * them, which it fills, and runs off at the bottom into the spots beside, as water running, never a block deep, and
     * kelp goes into none of them; ikea_v4's row of kelp at z 8 went in as a chain, each against the one before, with
     * the water over it poured after. Kelp under the source, in the cells its water falls through, it waits for no more:
     * that kelp gets its water from this source, as IKEA v1's at 5,69,17 under 5,70,17, which waited for good (v1-reg2).
     * A plain seal, lifted with the others when nothing but held-back blocks is left in the layer, as a row of such
     * sources each waiting for the kelp beside the next is.
     */
    private boolean feedsPendingPlant(Level level, int i, boolean log) {
        BlockPos pos = plan.pos(i);
        if (!plan.target(i).getFluidState().isSource() || !level.getBlockState(pos).canBeReplaced()) return false;
        // Where the water lands: the lowest of the open cells under it, a few at most.
        BlockPos lands = pos;
        for (int down = 1; down <= 4 && level.getBlockState(lands.below()).canBeReplaced(); down++) lands = lands.below();
        if (lands.equals(pos)) return false;
        for (Direction side : Direction.Plane.HORIZONTAL) {
            BlockPos at = lands.relative(side);
            int j = plan.indexOf(at);
            if (j < 0 || !plan.plant(j) || plan.status(j) != BuildPlan.Status.PENDING) continue;
            FluidState there = level.getFluidState(at);
            if (there.getType().isSame(Fluids.WATER) && there.getAmount() == 8) continue;
            if (log && debug() && tick - plantWaitLogged.getOrDefault(i, -1000L) > 200) {
                plantWaitLogged.put(i, tick);
                LOGGER.info("Water at {} waits: {} at {} beside where it lands still to go in", pos.toShortString(),
                        plan.target(j).getBlock().getName().getString(), at.toShortString());
            }
            return true;
        }
        return false;
    }

    private final Map<Integer, Long> plantWaitLogged = new HashMap<>();

    /**
     * Whether water source {@code i} waits for a block a layer up or level with it, still to go in, that takes a level look
     * (a piston, dispenser, observer or crafter facing sideways, see {@link BlockRules#eyeSide}) from a place to stand right
     * beside this water: a cell beside it, open, on a full block, with the eye there level with the block. Poured first,
     * the water runs into that place, and with the row of such blocks in the place is a pocket of water no climb leaves:
     * ikea_v4's dispensers 3..17,70,3 over the kelp channel z 4, which the water row of z 5 floods (v4-full13). As a player
     * places those first, from the channel still dry. Only for one that can go in now (support 1). A seal held through
     * the lift for one decision, as the blocks it waits for are a layer up, which comes up meanwhile; let go once the
     * seals are lifted for good, or once the block is in or given up on.
     */
    private boolean floodsLevelLook(LocalPlayer player, int i, boolean log) {
        Level level = player.level();
        BlockPos pos = plan.pos(i);
        if (!plan.target(i).getFluidState().isSource() || !level.getBlockState(pos).canBeReplaced()) return false;
        for (Direction toward : Direction.Plane.HORIZONTAL) {
            BlockPos feet = pos.relative(toward), floor = feet.below();
            if (!level.getBlockState(feet).canBeReplaced() || !level.getBlockState(floor).isCollisionShapeFullBlock(level, floor)) continue;
            for (int up = 0; up <= 1; up++) {
                for (Direction eye : Direction.Plane.HORIZONTAL) {
                    // The block whose eye side is this place: the place is the cell straight out from it on that side.
                    BlockPos at = feet.above(up).relative(eye.getOpposite());
                    int j = plan.indexOf(at);
                    if (j < 0 || plan.status(j) != BuildPlan.Status.PENDING || BlockRules.eyeSide(plan.target(j)) != eye) continue;
                    // It has something to go in against already, or the water waits for nothing: worked out here, as a block a
                    // layer up isn't weighed yet while this layer is built; from the lift's check, as last worked out.
                    byte support = log ? support(player, j, System.nanoTime()) : plan.support(j, BuildPlan.stamp(level, at));
                    if (support != 1) continue;
                    if (log && debug() && tick - plantWaitLogged.getOrDefault(-1000 - i, -1000L) > 200) {
                        plantWaitLogged.put(-1000 - i, tick);
                        LOGGER.info("Water at {} waits: it would flood the place to stand at {} for {} at {}", pos.toShortString(),
                                feet.toShortString(), plan.target(j).getBlock().getName().getString(), at.toShortString());
                    }
                    return true;
                }
            }
        }
        return false;
    }

    /** Whether kelp or seagrass grows one or two cells under {@code cell}, in the column its water fills. */
    private boolean feedsPlant(BlockPos cell) {
        for (int down = 1; down <= 2; down++) {
            int j = plan.indexOf(cell.below(down));
            if (j >= 0 && plan.plant(j)) return true;
        }
        return false;
    }

    /** Forgets what has no click from where the player stands, and the helper blocks held back with it. */
    private void forgetNoClicks() {
        noClickHere.clear();
        helperLater.clear();
    }

    /** Whether block {@code i} goes in from where the player stands once a helper block is at {@code helper}. */
    private boolean goesInAgainst(LocalPlayer player, int i, BlockPos helper, Item item, double reach) {
        Item helperItem = helperItem(player);
        if (helperItem == null) return false;
        Overlay view = new Overlay(player.level(), Map.of(helper.asLong(), Block.byItem(helperItem).defaultBlockState()));
        return PlacementSolver.fromSpot(player, view, player.position(), plan.pos(i), plan.target(i), new ItemStack(item), reach,
                at -> unclickable(at, i)) != null;
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

    /**
     * Breaks a helper block whose block is in, once it is in reach, and one climbed on in a cell of the build once the
     * player is out; helper blocks climbed on elsewhere wait for the end.
     */
    private boolean removeUsedHelper(Minecraft client, LocalPlayer player) {
        double reach = player.blockInteractionRange() - 0.25;
        boolean runOut = helpersRunOut(player);
        for (Iterator<Map.Entry<Long, Helper>> it = helpers.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Long, Helper> entry = it.next();
            BlockPos at = BlockPos.of(entry.getKey());
            if (client.level.getBlockState(at).getBlock() != entry.getValue().block()) {
                it.remove();
                dropEscapeHelper(entry.getKey());
                continue;
            }
            int serves = entry.getValue().serves();
            if (serves < 0 && (plan.indexOf(at) >= 0 || escapeHelpers.contains(entry.getKey()))) {
                // Climbed on in a cell of the build: one the build fills, on the way out of where the player was shut in, or
                // one only water runs through, where it holds the water back and the build closes in on it, as a shaft
                // roofed over with water poured onto it, out of reach by the end. Taken away once the player is out, the
                // top of its column first, while they still have a way out without it.
                if (helpers.containsKey(at.above().asLong()) || tick - escapeChecked < 20 || missedMining(player, at)
                        || !inView(player, player.getEyePosition(), at, reach) || !safeToBreak(player, at)) continue;
                Level level = client.level;
                if (Navigator.standingAt(level, player).floor().equals(at)) continue;
                escapeChecked = tick;
                Overlay without = new Overlay(level, Map.of(at.asLong(), Blocks.AIR.defaultBlockState()));
                if (exitFrom(player, without, Navigator.standingAt(without, player), null) == null) continue;
                if (debug()) LOGGER.info("Out again: taking away the helper block climbed on at {}, where the build goes", at.toShortString());
                startBreak(at);
                return true;
            }
            // One in a spot the build shuts in comes away while it can: once closed round, nothing reaches it, not the cleanup
            // either.
            boolean inLook = serves < 0 && inLookUpChannel(at);
            if (serves < 0 && (runOut || plan.enclosed(at) || inLook) && reclaimable(player, at, reach)) {
                if (debug()) LOGGER.info("{}: taking back the helper block an earlier walk put down at {}",
                        runOut ? "Short of helper blocks" : inLook ? "In the look up at a block placed looking up"
                                : "Shut in by the build later", at.toShortString());
                startBreak(at);
                return true;
            }
            // A block placed against it that only waits for its water is done with it.
            if (serves < 0 || plan.update(client.level, serves) == BuildPlan.Status.PENDING && !plan.soaking(serves)) continue;
            if (!missedMining(player, at) && inView(player, player.getEyePosition(), at, reach) && safeToBreak(player, at)) {
                startBreak(at);
                return true;
            }
        }
        return false;
    }

    /**
     * Whether helper blocks have run short, with none left in a shulker box to take out: then those walks climbed or
     * bridged on, which otherwise stand until the end, are taken back on the way. On a big build every walk leaves a few,
     * and without this the build ends up with hundreds standing and none to climb or hold a block up with.
     */
    private boolean helpersRunOut(LocalPlayer player) {
        if (player.hasInfiniteMaterials()) return false;
        helperItem(player);
        if (helperItems.isEmpty() || helpersLoose(player) >= 64) return false;
        Materials materials = Materials.of(player.getInventory());
        Map<Item, Integer> needs = plan.needs();
        for (Item item : helperItems) if (!needs.containsKey(item) && materials.boxed(item) > 0) return false;
        return true;
    }

    /**
     * Whether the helper block at {@code at}, put down by a walk outside the build, can come back now: the top of its
     * column, not underfoot, in view, nothing beside it that needs it to stand, and a way on for the player without it.
     */
    private boolean reclaimable(LocalPlayer player, BlockPos at, double reach) {
        Level level = player.level();
        // Each one looked at again a second later at the soonest: one that fails the check each time mustn't hold up the rest.
        if (helpers.containsKey(at.above().asLong()) || tick - reclaimChecked.getOrDefault(at.asLong(), -100_000L) < 20
                || Navigator.standingAt(level, player).floor().equals(at)
                || missedMining(player, at) || !inView(player, player.getEyePosition(), at, reach) || !safeToBreak(player, at)) return false;
        if (!plan.enclosed(at) && helperItem(player) != null) {
            // Taken back for its drop: one over a drop, a bridge say, falls to where a walk down and a climb back up, a new
            // helper block a step, cost more than it gives. With none left at all, any will do.
            int rest = at.getY();
            while (rest > level.getMinY() && level.getBlockState(at.atY(rest - 1)).getCollisionShape(level, at.atY(rest - 1)).isEmpty()) rest--;
            if (rest < player.getBlockY() - 1) return false;
        }
        for (Direction side : Direction.values()) {
            BlockPos next = at.relative(side);
            BlockState there = level.getBlockState(next);
            if (there.isAir() || there.getBlock() instanceof LiquidBlock || helpers.containsKey(next.asLong())) continue;
            // Sand on it falls, and lichen, a torch or a rail on it breaks off, as in safeToBreak.
            if (there.getBlock() instanceof FallingBlock) {
                if (side == Direction.UP) return false;
                continue;
            }
            if (there.isCollisionShapeFullBlock(level, next) || standsAlone(there)) continue;
            if (side != Direction.UP && restsOnFloor(there)) continue;
            return false;
        }
        Overlay without = new Overlay(level, Map.of(at.asLong(), Blocks.AIR.defaultBlockState()));
        reclaimChecked.put(at.asLong(), tick);
        Navigator.Node start = Navigator.standingAt(without, player);
        if (exitFrom(player, without, start, null) == null) return false;
        // Nor the one block that joins where the player stands to the rest: a bridge onto a row of gate tops between two
        // channels, where up at the layer counts as out, left the player on 16 tops, none to bridge or climb from (v4-z2).
        Set<Long> with = new HashSet<>(), alone = new HashSet<>();
        navigator(player, level, false).limit(CUT_OFF_PLACES).find(Navigator.standingAt(level, player), node -> {
            with.add(node.key());
            return 0;
        });
        navigator(player, without, false).limit(CUT_OFF_PLACES).find(start, node -> {
            alone.add(node.key());
            return 0;
        });
        return alone.size() >= CUT_OFF_PLACES || alone.size() >= with.size();
    }

    /** Places a walk reaches on foot past which taking a helper block away cuts nobody off: a few rooms' worth. */
    private static final int CUT_OFF_PLACES = 200;

    /**
     * Out of helper blocks with none left in a box, and nothing else to do: a walk to where one an earlier walk put down
     * is in view, for {@link #removeUsedHelper} to take back, the nearest first, each at most twice. False when there is
     * none to go to.
     */
    private boolean walkToSpareHelper(Minecraft client, LocalPlayer player) {
        if (!helpersRunOut(player)) return false;
        Level level = player.level();
        double reach = player.blockInteractionRange() - 0.25;
        List<BlockPos> spare = new ArrayList<>();
        for (Map.Entry<Long, Helper> entry : helpers.entrySet()) {
            BlockPos at = BlockPos.of(entry.getKey());
            if (entry.getValue().serves() >= 0 || plan.indexOf(at) >= 0 || escapeHelpers.contains(entry.getKey())
                    || helpers.containsKey(at.above().asLong()) || reclaimWalks.getOrDefault(entry.getKey(), 0) >= 2) continue;
            if (level.getBlockState(at).getBlock() != entry.getValue().block()) continue;
            spare.add(at);
        }
        if (spare.isEmpty()) return false;
        spare.sort(Comparator.comparingDouble(at -> at.distToCenterSqr(player.position())));
        Navigator.Node start = Navigator.standingAt(level, player);
        long started = System.nanoTime();
        for (BlockPos at : spare) {
            if (System.nanoTime() - started > 40_000_000L) break;
            reclaimWalks.merge(at.asLong(), 1, Integer::sum);
            // Never from on top of its own column: from there it doesn't come away.
            Navigator.Path path = navigator(player, false).limit(6000).find(start, node ->
                    node.x() == at.getX() && node.z() == at.getZ() && node.feet() > at.getY() ? 0 : viewScore(player, node.eye(), at, reach));
            if (path == null || path.nodes().size() < 2 || dropsIntoTrap(player, path)) continue;
            releaseKeys(client);
            walker.start(path.nodes(), Vec3.atCenterOf(at));
            show("Out of helper blocks: taking back those earlier walks put down", false);
            if (debug()) LOGGER.info("Out of helper blocks: walking {} steps to take back the one at {}", path.nodes().size() - 1, at.toShortString());
            return true;
        }
        return false;
    }

    /**
     * Once every block is in, takes the helper blocks away, each column from its top, so a pillar comes down a block at
     * a time and none is left in the air: those in reach first, highest first, then a walk to the highest a walk reaches,
     * climbing too. Where none is reached from up on helper blocks climbed, down a block and look again; from the ground,
     * what still isn't reached stays. True while there is one to go to or break.
     */
    private boolean cleanUp(Minecraft client, LocalPlayer player) {
        cleaning = true;
        if (refill(client, player, true)) return true;
        // Ground broken to be put right drops itself, and that is what it goes back in with.
        if (settings().walk && !repairDrops.isEmpty() && pickUpHelpers(client, player)) return true;
        if (redoFills(client, player)) return true;
        if (!leftRetried) {
            // Those left during the build, out of reach then or slow to break, get another try now the build is done.
            leftRetried = true;
            for (BlockPos pos : helpersLeft) {
                Block block = client.level.getBlockState(pos).getBlock();
                if (helperItems.contains(block.asItem())) helpers.putIfAbsent(pos.asLong(), new Helper(-1, block));
            }
            helpersLeft.clear();
        }
        helpers.entrySet().removeIf(e -> client.level.getBlockState(BlockPos.of(e.getKey())).getBlock() != e.getValue().block());
        if (helpers.isEmpty()) return false;
        Navigator.Node here = Navigator.standingAt(player.level(), player);
        if (here.key() != cleanupFrom) {
            cleanupSkipped.clear();
            cleanupFrom = here.key();
        }
        double reach = player.blockInteractionRange() - 0.25;
        // The top of each column: a helper block with another on it waits for that one, and so does one a bridge hangs
        // from: taken first, it leaves the bridge and what stands on it in the air, out of reach from the ground.
        Map<Long, Integer> fromGround = helperDepths(client.level);
        List<BlockPos> tops = new ArrayList<>();
        for (long key : helpers.keySet()) {
            int depth = fromGround.getOrDefault(key, 0);
            boolean held = false;
            for (Direction side : Direction.values()) {
                if (side == Direction.DOWN) continue;
                long next = BlockPos.offset(key, side);
                if (side == Direction.UP ? helpers.containsKey(next) : fromGround.getOrDefault(next, -1) > depth) {
                    held = true;
                    break;
                }
            }
            if (!held) tops.add(BlockPos.of(key));
        }
        tops.sort(Comparator.comparingInt((BlockPos pos) -> pos.getY()).reversed());
        // Just climbed for one: that one first, before the blocks climbed on to reach it, whose going would take the
        // player back down out of its reach. Still out of view, that climb from there isn't tried again.
        if (cleanupTarget != null) {
            BlockPos target = cleanupTarget;
            cleanupTarget = null;
            if (!tops.contains(target) && helpers.containsKey(target.asLong())) {
                // Covered on the way by a helper block of the climb itself: a miss, as the climb didn't bring it in reach.
                cleanupFailed.add(Navigator.pairKey(target.asLong(), here.key()));
                cleanupMisses.merge(target.asLong(), 1, Integer::sum);
            }
            if (tops.contains(target)) {
                if (!missedMining(player, target) && inView(player, player.getEyePosition(), target, reach) && safeToBreak(player, target)) {
                    startBreak(target);
                    return true;
                }
                cleanupFailed.add(Navigator.pairKey(target.asLong(), here.key()));
                cleanupMisses.merge(target.asLong(), 1, Integer::sum);
                if (debug()) LOGGER.info("Cleanup: the helper block at {} is still out of reach after the climb for it, from {}",
                        target.toShortString(), here);
            }
        }
        // Up on a helper column with helper tops as high elsewhere: the one underfoot, taken first as the highest in reach,
        // took the player down off the height they are reached from, and each was then climbed to from the ground again.
        // v1 left five tops 75 to 77 up along its back wall so, and put up a pillar of six for most of them. Those go
        // first, walked to while up here; the one underfoot after, once no walk to them is found.
        BlockPos underfoot = BlockPos.containing(player.getX(), player.getY() - 0.05, player.getZ());
        List<BlockPos> keptUpFor = List.of();
        if (settings().walk && player.onGround() && tops.contains(underfoot)
                && !cleanupKeptUp.contains(BlockPos.asLong(underfoot.getX(), 0, underfoot.getZ()))) {
            List<BlockPos> high = new ArrayList<>();
            for (BlockPos top : tops) {
                if (!top.equals(underfoot) && top.getY() >= underfoot.getY() && !inView(player, player.getEyePosition(), top, reach)) high.add(top);
            }
            if (high.size() >= KEEP_UP_TOPS) keptUpFor = high;
        }
        for (BlockPos top : tops) {
            if (!keptUpFor.isEmpty() && top.equals(underfoot)) continue;
            if (missedMining(player, top) || !inView(player, player.getEyePosition(), top, reach) || !safeToBreak(player, top)) continue;
            startBreak(top);
            return true;
        }
        if (settings().walk) {
            // Walks: the columns close by first, highest first, then the nearest far one. Highest first across the whole
            // build walked from one far column to another and back, and passed a column right beside the player for one
            // as high across the build.
            // Before any of those, the highest of the helper blocks that stand on no ground at all, hung off the side of the
            // build: reached only from up there, they go while the player still is, not after a walk down to the ground.
            // And those as high as the player's feet or higher before those below: down is a step, up a climb.
            List<BlockPos> walkOrder = new ArrayList<>(keptUpFor.isEmpty() ? tops : keptUpFor);
            int feet = player.getBlockY();
            walkOrder.sort(Comparator.comparingDouble((BlockPos pos) -> {
                if (!fromGround.containsKey(pos.asLong())) return -2_000_000 - pos.getY();
                double dx = pos.getX() + 0.5 - player.getX(), dz = pos.getZ() + 0.5 - player.getZ();
                double away = dx * dx + dz * dz;
                return (pos.getY() >= feet ? 0 : 10_000_000) + (away > NEAR_COLUMN * NEAR_COLUMN ? away : -1_000_000 - pos.getY());
            }));
            // In the last round only, and never down a gap within the build (see dropsOff).
            double cleanupDown = cleanupDrop ? farDrop(player) : 0;
            for (BlockPos top : walkOrder) {
                if (cleanupSkipped.contains(top.asLong()) || cleanupMisses.getOrDefault(top.asLong(), 0) >= CLEANUP_MISSES) continue;
                // Climbing too: the top of a tall pillar is only in reach from up beside it. Each block climbed on is a
                // helper block too, and comes down under the player's feet after it.
                // A bridge only a few times, to get back across a gap the build bridged: each puts down one more block
                // to take away. Never to stand on a block with nothing under it, which would drop the player when broken.
                // Far off, on foot to beside its column first: a search that may climb every column on the way runs out
                // of places to try long before it gets there. Close by, the climb.
                double awayX = top.getX() + 0.5 - player.getX(), awayZ = top.getZ() + 0.5 - player.getZ();
                Navigator.Path path = null;
                boolean toColumn = false;
                if (awayX * awayX + awayZ * awayZ > NEAR_COLUMN * NEAR_COLUMN && nearWalks.getOrDefault(top.asLong(), 0) < 2) {
                    path = navigator(player, false).limit(20_000).dropsOff(cleanupDown, plan.bounds).find(here, node -> {
                        double dx = top.getX() - node.x(), dz = top.getZ() - node.z();
                        return dx * dx + dz * dz <= 4 ? 1 : 0;
                    });
                    if (path != null && path.nodes().size() > 1 && top.getY() - path.end().feet() > COLUMN_RISE) {
                        // It ends on the ground far under the top: from there, the climb to it would be searched for
                        // anyway, and when it fails the next far top sends the player off again.
                        if (debug()) LOGGER.info("Cleanup: no walk on foot to the helper block at {}: it ends {} under it",
                                top.toShortString(), String.format(Locale.ROOT, "%.1f", top.getY() - path.end().feet()));
                        path = null;
                    }
                    if (path != null && path.nodes().size() > 1) {
                        nearWalks.merge(top.asLong(), 1, Integer::sum);
                        toColumn = true;
                    }
                }
                // Just walked to beside its column on foot: the climb from here is searched for as far as the last round
                // goes, not given up on for another far column, which sent the player to and fro between them.
                boolean besideColumn = path == null && top.equals(cleanupColumn);
                if (besideColumn) cleanupColumn = null;
                if (path == null) {
                    Navigator navigator = navigator(player, true).limit(cleanupWide || besideColumn ? 60_000 : 6000).dropsOff(cleanupDown, plan.bounds);
                    if (cleanupBridges >= CLEANUP_BRIDGES) navigator.noBridges();
                    boolean missedHere = missedMining(player, top);
                    long topKey = top.asLong();
                    // Never from up on its own column: a climb there puts a helper block on top of it, which is then the
                    // top, and comes away under the player's feet, again and again.
                    path = navigator.find(here, node -> node.floor().equals(top) && !groundUnder(player.level(), top)
                            || node.x() == top.getX() && node.z() == top.getZ() && node.feet() > top.getY() + 1
                            || missedHere && node.key() == here.key() || cleanupFailed.contains(Navigator.pairKey(topKey, node.key())) ? 0
                            : viewScore(player, node.eye(), top, reach));
                }
                if (path != null && cleanupDown > 0 && dropsIntoTrap(player, path)) path = null;
                if (path != null && path.nodes().size() > 1) {
                    if (!climbReady(client, player, path)) return true;
                    cleanupBridges += (int) path.nodes().stream().filter(node -> node.move() == Navigator.Move.BRIDGE).count();
                    if (debug()) LOGGER.info("Cleanup: walking {} steps, {} of them climbing, to take away the helper block at {}",
                            path.nodes().size() - 1, path.nodes().stream().filter(node -> node.move() == Navigator.Move.PILLAR).count(),
                            top.toShortString());
                    if (!toColumn) cleanupTarget = top;
                    cleanupColumn = toColumn ? top : null;
                    walker.start(path.nodes());
                    show("Taking the helper blocks away", false);
                    return true;
                }
                if (besideColumn) nearWalks.put(top.asLong(), 2);
                if (debug()) LOGGER.info("Cleanup: no walk to the helper block at {} from {}{}", top.toShortString(), here,
                        besideColumn ? ", after the walk on foot to beside it" : "");
                cleanupSkipped.add(top.asLong());
            }
            if (!keptUpFor.isEmpty()) {
                // None of those as high reached from up here: the column comes down as before, and those with it.
                cleanupKeptUp.add(BlockPos.asLong(underfoot.getX(), 0, underfoot.getZ()));
                if (debug()) LOGGER.info("Cleanup: no walk from {} to the {} helper tops as high as the one underfoot: down it after all",
                        here, keptUpFor.size());
                return true;
            }
            // None reached from up here, on helper blocks climbed: down a block, and from there they are looked for again.
            BlockPos under = underfoot;
            if (player.onGround() && helpers.containsKey(under.asLong()) && safeToBreak(player, under)) {
                if (debug()) LOGGER.info("No helper block left in reach of a walk from {}: down a block first", here);
                startBreak(under);
                return true;
            }
            // Up on a helper bridge, nothing under it to step down onto: a few steps onto the top of a helper pillar,
            // which has ground under it, to go down that one a block at a time, as a player digs down the pillar they
            // climbed rather than stay up on a bridge.
            if (player.onGround() && helpers.containsKey(under.asLong()) && !safeToBreak(player, under)) {
                Navigator.Path toPillar = navigator(player, false).limit(BOX_WALK_NODES).find(here, node -> {
                    if (node.key() == here.key()) return 0;
                    BlockPos floor = node.floor();
                    return helpers.containsKey(floor.asLong()) && groundUnder(player.level(), floor) ? 1 : 0;
                });
                if (toPillar != null && toPillar.nodes().size() > 1 && !dropsIntoTrap(player, toPillar)) {
                    if (debug()) LOGGER.info("No helper block left in reach of a walk from {}, up on a bridge: onto the helper pillar at {} {} {} to go down it",
                            here, toPillar.end().x(), toPillar.end().feet(), toPillar.end().z());
                    walker.start(toPillar.nodes());
                    show("Taking the helper blocks away", false);
                    return true;
                }
            }
        }
        if (settings().walk && !cleanupWide) {
            // One more round before any is left, with climbing searches that go ten times as far: from the ground, the way
            // up a finished build to a helper block high on it takes more places to try than a near one.
            cleanupWide = true;
            cleanupSkipped.clear();
            nearWalks.clear();
            if (debug()) LOGGER.info("Cleanup: {} helper blocks out of reach, looking further for a way to them", helpers.size());
            return true;
        }
        if (settings().walk && !cleanupDrop && farDrop(player) > 0) {
            // Still out of reach, up on the build with no way down that does no harm: once more, with a jump down off it
            // onto the ground round it allowed, as planWalk has when no place to stand is found up there.
            cleanupDrop = true;
            cleanupSkipped.clear();
            nearWalks.clear();
            if (debug()) LOGGER.info("Cleanup: {} helper blocks out of reach, looking again with a jump down off the build", helpers.size());
            return true;
        }
        if (settings().walk && cleanupEscapes < CLEANUP_ESCAPES && helperItem(player) != null
                && exitFrom(player, player.level(), here, null) == null) {
            // Shut in where a cleanup walk took the player down for a helper block, as a slot a block wide between a row of
            // crafters and the glass wall, floored with hoppers no pillar goes up from: every search from here finds nothing,
            // and every helper block would be left. Out first, climbing as a way out of a pocket does, then the rest again.
            Navigator.Path out = escapeFrom(player, player.level(), here, null);
            if (out != null && out.nodes().size() > 1) {
                cleanupEscapes++;
                if (!climbReady(client, player, out)) return true;
                releaseKeys(client);
                walker.start(out.nodes());
                escapeWalk = true;
                cleanupSkipped.clear();
                nearWalks.clear();
                show("Climbing out to take the rest of the helper blocks away", false);
                if (debug()) LOGGER.info("Cleanup: shut in at {} with {} helper blocks left: climbing out first, {} steps", here,
                        helpers.size(), out.nodes().size() - 1);
                return true;
            }
        }
        for (long key : helpers.keySet()) {
            BlockPos pos = BlockPos.of(key);
            if (debug()) LOGGER.info("Leaving the helper block at {}: no place to stand in reach of it, climbing too, from {}",
                    pos.toShortString(), here);
            helpersLeft.add(pos);
        }
        helpers.clear();
        return true;
    }

    /**
     * Whether breaking the block at {@code pos} leaves the player on their feet: it isn't the one they stand on, or it
     * has ground right under it, as in a pillar. A bridge block hangs over a gap, and the player would fall. Nor may it
     * hold back water that would then run where the player stands: a current carries them off a narrow top or a ledge,
     * as one let loose beside a row of gate tops carried a player down into the pocket under them.
     */
    private static boolean safeToBreak(LocalPlayer player, BlockPos pos) {
        BlockPos under = BlockPos.containing(player.getX(), player.getY() - 0.05, player.getZ());
        if (pos.equals(under)) return groundUnder(player.level(), pos);
        // Standing on a bridge block, nothing under it: not the block beside it the bridge was put against, which would
        // leave the player up on it with no way down but a fall.
        if (pos.getY() == under.getY() && pos.distManhattan(under) == 1 && !groundUnder(player.level(), under)
                && !player.level().getBlockState(under).getCollisionShape(player.level(), under).isEmpty()) return false;
        return !holdsBackWater(player.level(), pos) || !floods(player.level(), pos, player.position());
    }

    /** Whether water stands or runs beside or over the block at {@code pos}, to run into its cell once it is gone. */
    private static boolean holdsBackWater(BlockGetter level, BlockPos pos) {
        for (Direction side : Direction.values()) {
            if (side == Direction.DOWN) continue;
            BlockPos next = pos.relative(side);
            if (level.getFluidState(next).isEmpty()) continue;
            // Water in a block whose face toward the spot is whole stays in it, as vanilla lets it out only through a face
            // the block's shape leaves open: waterlogged leaves beside a helper block (the bone-meal crafter's 5, 66, 31 by
            // 5, 66, 32, left standing at the end, every place in reach of it called flooded).
            BlockState there = level.getBlockState(next);
            if (!(there.getBlock() instanceof LiquidBlock) && Block.isFaceFull(there.getCollisionShape(level, next), side.getOpposite())) continue;
            return true;
        }
        return false;
    }

    /** Whether something solid is right under the block at {@code pos}. */
    private static boolean groundUnder(Level level, BlockPos pos) {
        BlockPos below = pos.below();
        return !level.getBlockState(below).getCollisionShape(level, below).isEmpty();
    }

    /**
     * Once nothing else is left, one more round for the blocks given up on: those the server didn't keep, after three
     * clicks in a row, and those that came back set another way twice. What made them fail, a moment's lag, the
     * player in the way, a neighbour not yet in, may be gone by now, as a player looks over the build before calling it
     * done. Only blocks there is something to place with, and before the helper blocks come away, which the round may
     * still need. True when a round begins.
     */
    private boolean lastRound(LocalPlayer player) {
        if (lastRounds >= LAST_ROUNDS) return false;
        Materials materials = Materials.of(player.getInventory());
        boolean creative = player.hasInfiniteMaterials();
        int again = 0;
        for (int i = 0; i < plan.size(); i++) {
            BuildPlan.Status status = plan.status(i);
            boolean failed = status == BuildPlan.Status.FAILED;
            boolean misplaced = status == BuildPlan.Status.MISPLACED && repairs.getOrDefault(i, 0) >= REPAIRS;
            if (!failed && !misplaced) continue;
            Item item = plan.item(i);
            if (failed && !creative && (item == null || materials.total(item) == 0)) continue;
            plan.retry(i);
            repairs.remove(i);
            repairWalks.remove(i);
            arrivalMisses.remove(i);
            noClickHere.remove(i);
            plan.update(player.level(), i);
            again++;
        }
        if (again == 0) return false;
        lastRounds++;
        missedHere.clear();
        missedFrom.clear();
        LOGGER.info("Last round {} of {}: {} blocks given up on get another try", lastRounds, LAST_ROUNDS, again);
        return true;
    }

    /** How often a block that came back set another way is broken and placed again before it is left so. */
    private static final int REPAIRS = 2;

    /** How often a block no look reaches has a block round it broken to open one. */
    private static final int UNBLOCKS = 2;

    /**
     * A block of {@code layer} still to go in that no look reaches, while nothing else of the layer can go in either, as
     * an up-facing observer once the ice beside it went in first: one of the build's own blocks round it, whose absence
     * opens a look at it, is broken (see {@link #takeOut}). Twice at most for each stuck block.
     */
    private boolean unblock(Minecraft client, LocalPlayer player, int[] layer, long started) {
        Level level = player.level();
        for (int i : layer) {
            if (System.nanoTime() - started > PLAN_NANOS) return false;
            if (!mayUnblock(player, i)) continue;
            long stamp = BuildPlan.stamp(level, plan.pos(i));
            if (plan.support(i, stamp) != 2 || unblockSearched.getOrDefault(i, 0L) == stamp) continue;
            BlockState target = plan.target(i);
            ItemStack stack = new ItemStack(BlockRules.item(target));
            LongPredicate notThere = a -> unclickable(a, i);
            BlockPos blocker = blocker(player, i, open -> PlacementSolver.supported(player, open, plan.pos(i), target, stack, notThere)
                    || PlacementSolver.supportedFinely(player, open, plan.pos(i), target, stack, notThere));
            if (blocker != null && takeOut(client, player, i, blocker)) return true;
            unblockSearched.put(i, stamp);
        }
        return false;
    }

    /** How many blocks round a stuck one {@link #blocker} weighs at most. */
    private static final int BLOCKER_CHECKS = 6;

    /** How many of the blocks {@link #planWalk} last found no place to stand for {@link #unblockForStand} weighs. */
    private static final int UNBLOCK_STAND_CHECKS = 2;

    /**
     * Blocks {@link #planWalk} found no place to stand for, though somewhere a look at each seems to be, as an up-facing
     * observer is once the ice beside it shuts the looks from below that a walk could reach: when not even a search that
     * goes by nothing but the look finds a place, one of the build's own blocks round it is broken, one whose absence
     * gives such a place (see {@link #takeOut}). Each search is made again a minute later at the soonest.
     */
    private boolean unblockForStand(Minecraft client, LocalPlayer player) {
        Level level = player.level();
        for (int i : noStand.subList(0, Math.min(UNBLOCK_STAND_CHECKS, noStand.size()))) {
            if (!mayUnblock(player, i) || tick - standSearched.getOrDefault(i, -100_000L) < 1200) continue;
            standSearched.put(i, tick);
            String name = plan.target(i).getBlock().getName().getString();
            if (debug()) LOGGER.info("No walk to a look at {} at {}: weighing a block round it to break, from {}, a drop off the build of {} at most",
                    name, plan.pos(i).toShortString(), Navigator.standingAt(level, player), farDrop(player));
            // A place there is after all: the walk found none for what it leaves out, places tried before and the like.
            Navigator.Path there = standWithLook(player, level, i);
            if (there != null) {
                if (debug()) LOGGER.info("No walk to a look at {} at {}, yet one goes by the look alone, to {}: nothing broken for it",
                        name, plan.pos(i).toShortString(), there.end());
                continue;
            }
            BlockPos blocker = blocker(player, i, open -> standWithLook(player, open, i) != null);
            if (blocker == null) {
                if (debug()) LOGGER.info("No place to stand with a look at {} at {}, and no block round it to break gives one", name,
                        plan.pos(i).toShortString());
                continue;
            }
            if (takeOut(client, player, i, blocker)) return true;
            if (debug()) LOGGER.info("Breaking {} at {} would give a place to stand with a look at {} at {}, but it can't be reached",
                    plan.target(plan.indexOf(blocker)), blocker.toShortString(), name, plan.pos(i).toShortString());
        }
        return false;
    }

    /** A walk in {@code view} to a place to stand with a look that places block {@code i}, or null. */
    private Navigator.Path standWithLook(LocalPlayer player, BlockGetter view, int i) {
        BlockPos pos = plan.pos(i);
        BlockState target = plan.target(i);
        ItemStack stack = new ItemStack(BlockRules.item(target));
        double reach = player.blockInteractionRange() - 0.2;
        AABB cell = new AABB(pos);
        LongPredicate notThere = a -> unclickable(a, i);
        // For the log: places reached, those in reach, and those in reach with the eye under the block, the highest few.
        int[] seen = new int[3];
        List<Navigator.Node> under = new ArrayList<>();
        Navigator.Goal goal = node -> {
            seen[0]++;
            if (cell.distanceToSqr(node.eye()) > (reach + 0.5) * (reach + 0.5)) return 0;
            seen[1]++;
            if (node.eye().y < pos.getY()) {
                seen[2]++;
                if (under.size() < 400) under.add(node);
            }
            return PlacementSolver.fromSpot(player, view, node.standing(), pos, target, stack, reach, notThere) != null ? 1 : 0;
        };
        Navigator.Path path = offTheTop(player, view).climbUpTo(layerY + 2.5).find(Navigator.standingAt(view, player), goal);
        if (debug()) {
            // Every eye under it, as x feet z, the nearest across first: the looks from below come from right beside it.
            Vec3 middle = Vec3.atCenterOf(pos);
            under.sort(Comparator.comparingDouble((Navigator.Node n) -> Math.hypot(n.x() + 0.5 - middle.x, n.z() + 0.5 - middle.z))
                    .thenComparingDouble(n -> -n.feet()));
            StringBuilder all = new StringBuilder();
            for (Navigator.Node n : under) all.append(all.isEmpty() ? "" : " ").append(n.x()).append('/').append(n.feet()).append('/').append(n.z());
            LOGGER.info("  look-alone search for {} at {}{}: {} places reached, {} in reach, {} of them with the eye under it{}{}",
                    target.getBlock().getName().getString(), pos.toShortString(), view instanceof Overlay ? " with a block taken out" : "",
                    seen[0], seen[1], seen[2], under.isEmpty() ? "" : " (x/feet/z: " + all + ")", path == null ? "" : ", found " + path.end());
            // Why the nearest two within a climb's height have no look, as the world is: the walk's own explanation.
            List<Navigator.Node> near = under.stream().filter(n -> n.feet() >= pos.getY() - 5).limit(2).toList();
            if (path == null && !(view instanceof Overlay)) {
                // Whether a climb could start from the nearest two: each check a pillar step makes, as the world is.
                Level world = player.level();
                for (Navigator.Node node : under.subList(0, Math.min(2, under.size()))) {
                    BlockPos foot = node.feetCell();
                    StringBuilder cells = new StringBuilder();
                    for (int dy = 0; dy <= 3; dy++) {
                        BlockPos at = foot.above(dy);
                        BlockState state = world.getBlockState(at);
                        cells.append(dy == 0 ? "" : ", ").append(at.getY()).append(' ').append(state)
                                .append(state.getCollisionShape(world, at).isEmpty() ? "" : " solid")
                                .append(state.getShape(world, at).isEmpty() ? "" : " outline");
                    }
                    LOGGER.info("    a climb from {}: helper allowed {}, on {}{}, plan cell {}; cells up: {}", node,
                            helperAllowed(player, foot), world.getBlockState(foot.below()),
                            BlockRules.interactive(world.getBlockState(foot.below()).getBlock()) ? " (used by hand)" : "",
                            plan.indexOf(foot), cells);
                }
                for (Navigator.Node node : near) {
                    for (boolean crouch : new boolean[] {false, true}) {
                        Vec3 eye = node.standing().add(0, crouch ? PlacementSolver.CROUCH_EYE : PlacementSolver.STAND_EYE, 0);
                        LOGGER.info("    from {}: {}", node, PlacementSolver.explain(player, eye, crouch, pos, target, stack, reach, notThere));
                    }
                }
            }
        }
        return path;
    }

    /**
     * A navigator for walks round a stuck block: one that may drop off the top of the build onto the ground round it, as
     * {@link #planWalk} does once nothing else is found, for the looks from below the build's top has none of.
     */
    private Navigator offTheTop(LocalPlayer player, BlockGetter view) {
        Navigator navigator = navigator(player, view, true);
        double down = farDrop(player);
        return down > 0 ? navigator.dropsOff(down, plan.bounds) : navigator;
    }

    /**
     * Whether block {@code i} is one {@link #unblock} or {@link #unblockForStand} may break a block round: only one facing
     * up, whose look from below the blocks round it can shut for good. Others with no look wait for something, as kelp
     * waits for its water, and get one later.
     */
    private boolean mayUnblock(LocalPlayer player, int i) {
        if (!BlockRules.placedLookingUp(plan.target(i))) return false;
        if (plan.kind(i) != BlockRules.Kind.PLACE || plan.status(i) != BuildPlan.Status.PENDING || brokeOut.contains(i)
                || unblocking.containsKey(i) || unblocks.getOrDefault(i, 0) >= UNBLOCKS) return false;
        if (unblocking.values().stream().anyMatch(u -> u.stuck() == i)) return false;
        return BlockRules.item(plan.target(i)) != null && player.level().getBlockState(plan.pos(i)).canBeReplaced();
    }

    /**
     * Of the build's own blocks round block {@code i}, the quickest to break of those whose absence {@code opens} says
     * lets it go in, and that can go in again after, with it in: or null. Only a block breaking costs nothing (see
     * {@link #spares}), that lets nothing loose, and that the player doesn't stand on or in.
     */
    private BlockPos blocker(LocalPlayer player, int i, Predicate<BlockGetter> opens) {
        Level level = player.level();
        BlockPos pos = plan.pos(i);
        BlockState target = plan.target(i);
        Vec3 eye = player.getEyePosition();
        Navigator.Node standing = Navigator.standingAt(level, player);
        List<BlockPos> around = new ArrayList<>();
        for (BlockPos at : BlockPos.betweenClosed(pos.offset(-1, -1, -1), pos.offset(1, 1, 1))) {
            int j = plan.indexOf(at);
            if (j < 0 || j == i || plan.status(j) != BuildPlan.Status.DONE || plan.kind(j) != BlockRules.Kind.PLACE) continue;
            if (at.equals(standing.floor()) || player.getBoundingBox().intersects(new AABB(at))) continue;
            // One facing up goes in again only with a look up from below, as shut as the stuck block's own may be; and the
            // one under the stuck block holds up what is under that, up-facers in a column.
            if (BlockRules.placedLookingUp(plan.target(j)) || at.equals(pos.below())) continue;
            // Only one that goes in the same whichever face is clicked, with any look: glass, ice, a full block.
            if (!BlockRules.placementMatches(plan.target(j).getBlock().defaultBlockState(), plan.target(j))) continue;
            BlockState state = level.getBlockState(at);
            if (BlockRules.group(state) != 0 || BlockRules.wet(state) || state.getDestroySpeed(level, at) < 0
                    || level.getBlockEntity(at) != null || BlockRules.item(plan.target(j)) == null
                    || !spares(player, state, BlockRules.item(plan.target(j))) || !safeToBreak(level, at, 0)) continue;
            around.add(at.immutable());
        }
        if (debug()) LOGGER.info("Blocks round {} at {} that may be broken for it: {}", target.getBlock().getName().getString(),
                pos.toShortString(), around.stream().map(BlockPos::toShortString).toList());
        around.sort(Comparator.comparingDouble(at -> breakTicks(player, level, at) + new AABB(at).distanceToSqr(eye)));
        // The quickest few: each test may be a whole walk search.
        for (BlockPos at : around.subList(0, Math.min(BLOCKER_CHECKS, around.size()))) {
            if (debug()) LOGGER.info("  weighing {} at {} taken out", level.getBlockState(at).getBlock().getName().getString(), at.toShortString());
            if (!opens.test(new Overlay(level, Map.of(at.asLong(), Blocks.AIR.defaultBlockState())))) continue;
            // And it goes in again after, with the stuck block in: a face beside it to click and an open side to click it
            // from. PlacementSolver can't tell more of a spot the block still stands in, the click's checks reading the
            // world as it is; only a block that goes in the same whichever face is clicked is taken out, so that will do.
            Overlay after = new Overlay(level, Map.of(at.asLong(), Blocks.AIR.defaultBlockState(), pos.asLong(), target));
            boolean face = false, open = false;
            for (Direction side : Direction.values()) {
                BlockState beside = after.getBlockState(at.relative(side));
                if (beside.canBeReplaced()) open = true;
                else face = true;
            }
            if (debug() && !(face && open)) LOGGER.info("  {} at {} would have no face beside it or no open side to go in again",
                    plan.target(plan.indexOf(at)).getBlock().getName().getString(), at.toShortString());
            if (face && open) return at;
        }
        return null;
    }

    /**
     * Breaks {@code blocker}, one of the build's own blocks, for stuck block {@code i}; it waits until that block is in,
     * as a player takes out a block put in too soon. A walk brings it into view when it is out of reach, {@link #REPAIRS}
     * times at most. False when neither can be done.
     */
    private boolean takeOut(Minecraft client, LocalPlayer player, int i, BlockPos blocker) {
        double reach = player.blockInteractionRange() - 0.25;
        Vec3 eye = player.getEyePosition();
        int j = plan.indexOf(blocker);
        BlockPos pos = plan.pos(i);
        String name = plan.target(i).getBlock().getName().getString();
        if (!missedMining(player, blocker) && inView(player, eye, blocker, reach)) {
            if (debug()) LOGGER.info("No look reaches {} at {}: breaking {} at {}, the build's own, to open one; it goes in again after",
                    name, pos.toShortString(), plan.target(j), blocker.toShortString());
            unblocks.merge(i, 1, Integer::sum);
            unblocking.put(j, new Unblock(i, tick));
            if (!player.hasInfiniteMaterials()) repairDrops.add(BlockRules.item(plan.target(j)));
            startBreak(blocker);
            repairing = j;
            return true;
        }
        if (!settings().walk || unblockWalks.getOrDefault(i, 0) >= REPAIRS) return false;
        unblockWalks.merge(i, 1, Integer::sum);
        Navigator.Path path = offTheTop(player, player.level()).find(Navigator.standingAt(player.level(), player),
                node -> inView(player, node.eye(), blocker, reach) ? 1 : 0);
        if (path == null || path.nodes().size() < 2 || dropsIntoTrap(player, path)) return false;
        if (!climbReady(client, player, path)) return true;
        releaseKeys(client);
        walker.start(path.nodes(), Vec3.atCenterOf(blocker));
        show("Walking to " + plan.target(j).getBlock().getName().getString() + " at " + blocker.getX() + " " + blocker.getY() + " "
                + blocker.getZ() + " to take it out for the " + name + " beside it", false);
        if (debug()) LOGGER.info("Walking {} steps to break {} at {}, which takes every look at {} at {}", path.nodes().size() - 1,
                plan.target(j), blocker.toShortString(), name, pos.toShortString());
        return true;
    }

    /**
     * A block this build placed that the server set another way than the click was for, an observer facing down rather
     * than east say, is broken and placed again, as a player fixes a block put in wrong: from where the player stands, with
     * a tool that gets the block back, and only where breaking it lets nothing loose. Twice at most for each block; one
     * left wrong is counted at the end.
     */
    private boolean repairMisplaced(Minecraft client, LocalPlayer player) {
        if (placedAt.isEmpty()) return false;
        ClientLevel level = client.level;
        double reach = player.blockInteractionRange() - 0.25;
        Vec3 eye = player.getEyePosition();
        for (Map.Entry<Integer, Long> entry : placedAt.entrySet()) {
            int i = entry.getKey();
            if (plan.status(i) != BuildPlan.Status.MISPLACED || repairs.getOrDefault(i, 0) >= REPAIRS) continue;
            BlockPos pos = plan.pos(i);
            if (new AABB(pos).distanceToSqr(eye) > reach * reach || plan.update(level, i) != BuildPlan.Status.MISPLACED) continue;
            BlockState state = level.getBlockState(pos);
            Item item = BlockRules.item(plan.target(i));
            if (item == null || !spares(player, state, item) || !safeToBreak(level, pos, tick - entry.getValue())) continue;
            if (!inView(player, eye, pos, reach)) continue;
            repairs.merge(i, 1, Integer::sum);
            if (!player.hasInfiniteMaterials()) repairDrops.add(item);
            if (debug()) LOGGER.info("Breaking {} at {} to place it again: it went in as {}", plan.target(i), pos.toShortString(), state);
            startBreak(pos);
            repairing = i;
            return true;
        }
        return false;
    }

    /**
     * With nothing to place from here, to a block this build placed that came back set another way, out of reach or out
     * of view: a walk to where it can be seen, so it is broken and placed again before more is built round it. Twice at
     * most for each block, and only for one in a layer built already or being built.
     */
    private boolean walkToRepair(Minecraft client, LocalPlayer player) {
        if (placedAt.isEmpty()) return false;
        ClientLevel level = client.level;
        double reach = player.blockInteractionRange() - 0.25;
        Vec3 eye = player.getEyePosition();
        int best = -1;
        double nearest = Double.MAX_VALUE;
        for (Map.Entry<Integer, Long> entry : placedAt.entrySet()) {
            int i = entry.getKey();
            if (plan.status(i) != BuildPlan.Status.MISPLACED || repairs.getOrDefault(i, 0) >= REPAIRS
                    || repairWalks.getOrDefault(i, 0) >= REPAIRS || plan.pos(i).getY() > layerY) continue;
            BlockPos pos = plan.pos(i);
            if (plan.update(level, i) != BuildPlan.Status.MISPLACED || !spares(player, level.getBlockState(pos), BlockRules.item(plan.target(i)))
                    || !safeToBreak(level, pos, tick - entry.getValue())) continue;
            double away = Vec3.atCenterOf(pos).distanceToSqr(eye);
            if (away < nearest) {
                nearest = away;
                best = i;
            }
        }
        if (best < 0) return false;
        BlockPos pos = plan.pos(best);
        repairWalks.merge(best, 1, Integer::sum);
        Navigator.Path path = navigator(player, true).find(Navigator.standingAt(level, player),
                node -> inView(player, node.eye(), pos, reach) ? 1 : 0);
        if (path == null || path.nodes().size() < 2 || dropsIntoTrap(player, path)) return false;
        if (!climbReady(client, player, path)) return true;
        releaseKeys(client);
        walker.start(path.nodes(), Vec3.atCenterOf(pos));
        show("Walking to " + plan.target(best).getBlock().getName().getString() + " at " + pos.getX() + " " + pos.getY() + " "
                + pos.getZ() + " to place it again", false);
        if (debug()) LOGGER.info("Walking {} steps to place {} at {} again", path.nodes().size() - 1, plan.target(best), pos.toShortString());
        return true;
    }

    /**
     * Whether breaking the block costs the build nothing: it gives its own {@code item} back, with a tool that gets it,
     * glass and ice only with silk touch; or the inventory has enough of the item for every block still to go in and
     * this one again.
     */
    private boolean spares(LocalPlayer player, BlockState state, Item item) {
        if (player.hasInfiniteMaterials()) return true;
        if (!getsBack(player, state)) return false;
        if (BlockRules.dropsItself(state) || silkTouchAtHand(player)) return true;
        return item != null && Materials.of(player.getInventory()).total(item) > plan.needs().getOrDefault(item, 0);
    }

    /** Whether the inventory has a tool with silk touch, which {@link #holdTool} takes for a block that needs it. */
    private static boolean silkTouchAtHand(LocalPlayer player) {
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < Materials.SLOTS; slot++) if (BlockRules.silkTouch(inventory.getItem(slot))) return true;
        return false;
    }

    /** Whether breaking the block gives its item back: it needs no tool for that, or the inventory has one that does. */
    private static boolean getsBack(LocalPlayer player, BlockState state) {
        if (player.hasInfiniteMaterials() || !state.requiresCorrectToolForDrops()) return true;
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < Materials.SLOTS; slot++) if (inventory.getItem(slot).isCorrectToolForDrops(state)) return true;
        return false;
    }

    /**
     * Whether breaking the block at {@code pos} lets nothing loose: no water or lava beside it or in it, nothing resting on
     * it or hanging from it that would drop, and no container that may have taken items in since it was placed, which
     * {@code age} ticks ago. Only blocks that stand by themselves may be beside it.
     */
    private static boolean safeToBreak(Level level, BlockPos pos, long age) { return safeToBreak(level, pos, age, false); }

    /** As the other, with {@code waterBeside} letting water beside it run into the spot: a way out of a flooded pocket. */
    private static boolean safeToBreak(Level level, BlockPos pos, long age, boolean waterBeside) {
        if (!level.getFluidState(pos).isEmpty()) return false;
        if (level.getBlockEntity(pos) instanceof Container && age > 100) return false;
        for (Direction side : Direction.values()) {
            BlockPos next = pos.relative(side);
            BlockState there = level.getBlockState(next);
            if (!level.getFluidState(next).isEmpty()) {
                if (waterBeside && there.getBlock() instanceof LiquidBlock) continue;
                return false;
            }
            if (there.isAir()) continue;
            if (there.getBlock() instanceof FallingBlock) {
                if (side == Direction.UP) return false;
                continue;
            }
            if (there.isCollisionShapeFullBlock(level, next) || standsAlone(there)) continue;
            // Beside it or under it, a wire, a rail or a repeater rests on a floor of its own; on top of it, anything may
            // rest on it, and a torch, a ladder or a lever beside it or a lantern under it may hang from it.
            if (side != Direction.UP && restsOnFloor(there)) continue;
            return false;
        }
        return true;
    }

    /**
     * Whether a block can be broken to go in again into water, as a chest is filled: water beside it may run into its
     * spot, which gets water anyway, but nothing may rest on it, hang from it or grow on it, and no hopper may feed it.
     */
    private static boolean safeToDrain(Level level, BlockPos pos) {
        for (Direction side : Direction.values()) {
            BlockPos next = pos.relative(side);
            BlockState there = level.getBlockState(next);
            if (there.isAir() || there.getBlock() instanceof LiquidBlock || there.getBlock() instanceof BubbleColumnBlock) continue;
            if (there.getBlock() instanceof HopperBlock && next.relative(there.getValue(HopperBlock.FACING)).equals(pos)) return false;
            if (there.getBlock() instanceof FallingBlock) {
                if (side == Direction.UP) return false;
                continue;
            }
            if (there.isCollisionShapeFullBlock(level, next) || standsAlone(there)) continue;
            if (side != Direction.UP && restsOnFloor(there)) continue;
            if (there.getBlock() instanceof MultifaceBlock && !MultifaceBlock.hasFace(there, side.getOpposite())) continue;
            return false;
        }
        return true;
    }

    /**
     * Whether block {@code i}, full of water in the schematic and one a bucket can't fill once in (see
     * {@link BlockRules#fillsInPlace}), goes into water poured into its spot first: once every other dry block of its
     * layer is in. Before that, placed dry, it counts as in for those; after it, its water is next anyway. And always
     * beside the build's own water, a stream's corner say: broken there once that water runs, to go in again into
     * water, its drop floats off down the stream before it is picked up, and the build is a block short (slabtop3,
     * the waterlogged chest at 28,73,8).
     */
    private boolean waterFirst(int i, int[] layer) {
        BlockState target = plan.target(i);
        if (plan.kind(i) != BlockRules.Kind.PLACE || !BlockRules.soaked(target) || BlockRules.fillsInPlace(target)
                || plan.refilling(i) || drains.getOrDefault(i, 0) >= REPAIRS) return false;
        BlockPos pos = plan.pos(i);
        for (Direction side : Direction.values()) {
            if (side == Direction.UP) continue;
            int j = plan.indexOf(pos.relative(side));
            if (j >= 0 && plan.target(j).getBlock() instanceof LiquidBlock) return true;
        }
        for (int j : layer) {
            if (j == i || plan.wet(j) || plan.status(j) != BuildPlan.Status.PENDING) continue;
            BlockState other = plan.target(j);
            if (BlockRules.soaked(other) && !BlockRules.fillsInPlace(other)) continue;
            return false;
        }
        return true;
    }

    /** Blocks that rest on the block under them and on nothing else. */
    private static boolean restsOnFloor(BlockState state) {
        Block block = state.getBlock();
        return block instanceof DiodeBlock || block instanceof RedStoneWireBlock || block instanceof BaseRailBlock || block instanceof CarpetBlock
                || block instanceof BasePressurePlateBlock;
    }

    /** Blocks that keep standing whatever is beside, above or under them. */
    private static boolean standsAlone(BlockState state) {
        Block block = state.getBlock();
        return block instanceof ChestBlock || block instanceof HopperBlock || block instanceof SlabBlock || block instanceof StairBlock
                || block instanceof CrossCollisionBlock || block instanceof WallBlock || block instanceof FenceGateBlock
                || block instanceof TrapDoorBlock || block instanceof ShulkerBoxBlock;
    }

    private void startBreak(BlockPos pos) {
        breaking = pos;
        breakTicks = 0;
        restingTicks = 0;
    }

    /**
     * Blocks the crosshair couldn't be brought onto to mine, from where the player stood then: passed over from there,
     * looked for again once the player has moved. The face the look's trace found was hidden behind something after all.
     */
    private final Set<Long> mineMissed = new HashSet<>();
    private BlockPos mineMissedAt;
    /** The block being mined, and for how many ticks in a row the crosshair has been on something else. */
    private BlockPos minePos;
    private int mineMisses;

    /** Whether mining the block at {@code pos} failed from where the player stands, for the crosshair. */
    private boolean missedMining(LocalPlayer player, BlockPos pos) {
        if (!player.blockPosition().equals(mineMissedAt)) {
            mineMissed.clear();
            mineMissedAt = player.blockPosition();
        }
        return mineMissed.contains(pos.asLong());
    }

    /** Looks at the helper block being taken away and mines it, with the best tool in the hotbar. */
    private void breakTick(Minecraft client, LocalPlayer player) {
        breakTicks++;
        BlockState state = client.level.getBlockState(breaking);
        if (state.isAir() || state.canBeReplaced()) {
            Helper helper = helpers.remove(breaking.asLong());
            if (cleaning && helper != null) cleared.add(breaking.asLong());
            if (debug() && helper != null) {
                LOGGER.info("Took away the helper block at {} (serving {}) in {} ticks, {} helper blocks standing", breaking.toShortString(),
                        helper.serves(), breakTicks, helpers.size());
            }
            dropEscapeHelper(breaking.asLong());
            pours.put(breaking.asLong(), tick);
            // With the helper gone, a look may reach a block it was in front of, and a way out may open.
            forgetNoClicks();
            shutIn.clear();
            traps.clear();
            breaking = null;
            repairing = -1;
            brokeAt = tick;
            attackHeld = settings().holdAttack;
            paceAfterHands(quick());
            return;
        }
        // On a narrow top over a drop the player stays crouched while mining, as anywhere they could be nudged off.
        if (player.isShiftKeyDown() && !onNarrowEdge(player)) setCrouch(client, false);
        if (breakTicks == 1 && !holdTool(client, player, state)) {
            breakTicks = 0;
            return;
        }
        show((repairing >= 0 ? "Breaking " + plan.target(repairing).getBlock().getName().getString() + " to place it again at "
                : "Taking away a helper block at ") + breaking.getX() + " " + breaking.getY() + " " + breaking.getZ(), false);
        // As long as breaking it takes from where the player is, and then some: with the head in water, or off the
        // ground, a player mines five times slower for each, and a dirt block takes twenty seconds rather than one.
        float progress = state.getDestroyProgress(player, client.level, breaking);
        int limit = (int) Math.min(20 * 120, Math.max(20 * 15, 2 / Math.max(progress, 1e-4f) + 40));
        if (mine(client, player, breaking) && breakTicks <= limit) return;
        // Out of view, or not breaking: it is left for later, from somewhere else.
        if (debug() && breakTicks <= limit) LOGGER.info("Breaking {} at {}: no face of it in view from {} after all", state.getBlock().getName().getString(),
                breaking.toShortString(), player.position());
        client.gameMode.stopDestroyBlock();
        if (breakTicks > limit && repairing < 0) {
            if (debug()) LOGGER.info("Leaving the helper block at {}: not broken after {} ticks, at {} of it a tick", breaking.toShortString(),
                    breakTicks, progress);
            helpersLeft.add(breaking);
            helpers.remove(breaking.asLong());
            dropEscapeHelper(breaking.asLong());
        }
        breaking = null;
        repairing = -1;
        paceAfterHands(quick());
    }

    /**
     * Whether a face of the block at {@code pos} is in view and in reach from {@code eye}, to mine it: within the reach
     * {@link #mine} looks for one, never further, or a block found in view here would go to mine and be dropped there,
     * again and again.
     */
    private static boolean inView(LocalPlayer player, Vec3 eye, BlockPos pos, double reach) {
        if (new AABB(pos).distanceToSqr(eye) > reach * reach) return false;
        return PlacementSolver.use(player, eye, pos, Math.min(reach + 0.5, player.blockInteractionRange()),
                player.level().getBlockState(pos)) != null;
    }

    /**
     * One tick of mining a block as a player does: looking at a face of it in view, then holding attack on it. True
     * while it goes on; false when no face is in view from here.
     */
    private boolean mine(Minecraft client, LocalPlayer player, BlockPos pos) {
        // Within the reach the crosshair itself traces, not beyond it: a face half a block further looks fine to the
        // search and leaves the crosshair on nothing.
        PlacementSolver.Click face = PlacementSolver.use(player, player.getEyePosition(), pos, player.blockInteractionRange(),
                client.level.getBlockState(pos));
        if (face == null) {
            // Passed over from here, as a block the crosshair missed is: whatever found it in view, it isn't mined from here.
            missedMining(player, pos);
            mineMissed.add(pos.asLong());
            return false;
        }
        if (!pos.equals(minePos)) {
            minePos = pos;
            mineMisses = 0;
        }
        if (!looked(player, face.yaw(), face.pitch(), onBlock(player, pos, null))) return true;
        // Keep the crosshair on the face while mining; a small drift of the aim would start over.
        restingTicks = restFor + 1;
        BlockHitResult hit = crosshair(player);
        if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(pos)) {
            // The look is there, the crosshair on something else in front of it: from here it isn't mined at all.
            if (++mineMisses <= 10) return true;
            if (debug()) LOGGER.info("Can't get the crosshair onto {} to mine it: on {} {} {} instead, eye {}, look {} {}",
                    pos.toShortString(), hit.getType(), hit.getBlockPos().toShortString(), hit.getDirection(), player.getEyePosition(),
                    player.getYRot(), player.getXRot());
            missedMining(player, pos);
            mineMissed.add(pos.asLong());
            mineMisses = 0;
            return false;
        }
        mineMisses = 0;
        if (!handReady(player) || attackTick == tick) return true;
        attackHeld = false;
        attackTick = tick;
        if (client.gameMode.continueDestroyBlock(pos, hit.getDirection())) player.swing(InteractionHand.MAIN_HAND);
        return true;
    }

    /**
     * Attack held on from a block just broken, as vanilla does with the button down: each tick the crosshair is on a
     * block, vanilla's delay before the next block counts down and the hand swings, so after a turn to the next helper
     * mining starts as soon as the look rests there instead of swinging at it for the delay first. Let go once the delay
     * is over, before it could start on whatever the crosshair crosses, and for anything but mining the next block.
     */
    private void holdAttack(Minecraft client, LocalPlayer player) {
        if (!attackHeld) return;
        if (!settings().holdAttack || aim != null || walker.active() || pit != null || player.isUsingItem()
                || ((MultiPlayerGameModeAccess) client.gameMode).donut$destroyDelay() <= 0) {
            attackHeld = false;
            return;
        }
        if (attackTick == tick) return;
        BlockHitResult hit = crosshair(player);
        // On nothing, vanilla's held button counts nothing down either.
        if (hit.getType() != HitResult.Type.BLOCK || client.level.getBlockState(hit.getBlockPos()).isAir()) return;
        attackTick = tick;
        if (client.gameMode.continueDestroyBlock(hit.getBlockPos(), hit.getDirection())) {
            client.level.addBreakingBlockEffect(hit.getBlockPos(), hit.getDirection());
            player.swing(InteractionHand.MAIN_HAND);
        }
    }

    // Walking ------------------------------------------------------------------------------------------------------

    /** The planner for walks around the build, with climbing allowed where helper blocks may go. */
    private Navigator navigator(LocalPlayer player, boolean climb) { return navigator(player, player.level(), climb); }

    private Navigator navigator(LocalPlayer player, BlockGetter view, boolean climb) {
        if (view instanceof Level level) view = asOpened(level);
        BlockGetter seen = view;
        AABB b = plan.bounds;
        AABB area = new AABB(b.minX - 12, b.minY - 6, b.minZ - 12, b.maxX + 12, b.maxY + 4, b.maxZ + 12)
                .minmax(new AABB(player.blockPosition()).inflate(6));
        Item helper = climb ? helperItem(player) : null;
        return new Navigator(seen, area, cell -> helper != null && helperAllowed(player, seen, cell)
                && (escaping || !BlockRules.interactive(seen.getBlockState(cell.below()).getBlock())), key -> {
            int i = plan.indexOf(key);
            return i >= 0 && plan.status(i) == BuildPlan.Status.PENDING;
        }).avoidSteps(badSteps).pillars(helper == null ? 0 : climbBudget(player));
    }

    /** Blocks of the build that end up open, a gate or a door or a trapdoor, by index; worked out on first use. */
    private int[] opened;

    /**
     * The level as walks plan on it: a gate, door or trapdoor of the build that goes in closed and is opened right after
     * counts as open already. A walk planned over the top of one, just placed and still shut, drops the player through
     * it once it opens on the way, as one fell six blocks from a row of gate tops.
     */
    private BlockGetter asOpened(Level level) {
        // Once a tick: a search that plans many walks within one, as the one for a shulker box spot does for each place,
        // asks for it each time.
        if (openedTick == tick && openedView != null) return openedView;
        openedTick = tick;
        openedView = workOutOpened(level);
        return openedView;
    }

    private long openedTick = -1;
    private BlockGetter openedView;

    private BlockGetter workOutOpened(Level level) {
        if (opened == null) {
            List<Integer> found = new ArrayList<>();
            for (int i = 0; i < plan.size(); i++) {
                BlockState target = plan.target(i);
                if (target.hasProperty(BlockStateProperties.OPEN) && target.getValue(BlockStateProperties.OPEN)) found.add(i);
            }
            opened = found.stream().mapToInt(Integer::intValue).toArray();
        }
        Map<Long, BlockState> open = null;
        for (int i : opened) {
            BlockPos pos = plan.pos(i);
            BlockState now = level.getBlockState(pos);
            if (now.getBlock() != plan.target(i).getBlock() || now.getValue(BlockStateProperties.OPEN)) continue;
            if (open == null) open = new HashMap<>();
            open.put(pos.asLong(), now.setValue(BlockStateProperties.OPEN, true));
        }
        return open == null ? level : new Overlay(level, open);
    }

    /**
     * What a walk search for {@code near} goes by, folded into one number: the world round the build, where the player
     * stands, what they carry, and the places and steps the search leaves out. Two searches with the same key find the same.
     */
    private long walkSearchKey(LocalPlayer player, List<Integer> near, boolean lift) {
        long key = worldChanges;
        key = key * 31 + player.blockPosition().asLong();
        key = key * 31 + layerY;
        key = key * 31 + near.hashCode();
        key = key * 31 + (lift ? 1 : 0) + (escaping ? 2 : 0) + (orderLifted ? 4 : 0) + (sealsLifted ? 8 : 0);
        key = key * 31 + uselessSpots.size();
        key = key * 31 + traps.size();
        key = key * 31 + carriedFrom.size();
        key = key * 31 + missedFrom.size();
        key = key * 31 + badSteps.size();
        key = key * 31 + shutIn.size();
        key = key * 31 + arrivalMisses.hashCode();
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            key = key * 31 + (stack.isEmpty() ? 0 : stack.getItem().hashCode() * 64L + stack.getCount());
        }
        return key;
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
            if (lift && liftHolds(player, i)) continue;
            Item item = plan.item(i);
            if (item == null || !creative && (materials.total(item) == 0 || waitsForDrop(level, i, materials))) continue;
            // Only in a box, with no room for one, or for it in the inventory, a moment ago: it waits for that on arrival
            // too, and a walk for it is for nothing.
            if (!creative && materials.loose(item) == 0 && (noBoxHere(item) || waitsForRoom(item, materials))) continue;
            near.add(i);
            if (near.size() >= 48) break;
        }
        if (near.isEmpty()) return false;
        // The last walk's place didn't do: nothing went in from there, though looks were counted from it. Left out until a
        // block goes in, so two such places close by can't send the player to and fro between them, and the search goes
        // on to places further off, round the build or into it.
        if (lastWalkEnd != null && !clickedSinceWalk) {
            if (uselessSpots.add(lastWalkEnd.key()) && debug()) {
                LOGGER.info("Nothing went in from {} {} {}: no more walks there until a block does", lastWalkEnd.x(), lastWalkEnd.feet(),
                        lastWalkEnd.z());
            }
        }
        lastWalkEnd = null;
        // The same search found nothing a moment ago, and nothing it goes by has changed since: it would find nothing again,
        // and a search over some 20,000 places four times a second holds up the game.
        long searchKey = walkSearchKey(player, near, lift);
        int kind = escaping ? 1 : 0;
        if (searchKey == failedWalkKey[kind] && System.nanoTime() - failedWalkAt[kind] < FAILED_SEARCH_NANOS) return false;
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
        Map<Long, Integer> verified = new HashMap<>();
        // Places with a look that holds wherever the walk stops: the search looks well past places that have only shaky
        // ones, to a climb or two, for one of these.
        Set<Long> firm = new HashSet<>();
        int[] seen = new int[3];
        List<Navigator.Node> sample = new ArrayList<>();
        Map<Long, Boolean> wetCells = new HashMap<>();
        Map<Long, Boolean> currentCells = new HashMap<>();
        Map<Long, Boolean> intoFall = new HashMap<>();
        List<BlockPos> poured = recentPours();
        long leaning = ThreadLocalRandom.current().nextLong();
        // The nearest block a real look reaches from each place to stand, to turn toward on arriving there.
        Map<Long, Integer> firstFrom = new HashMap<>();
        // Dropped helper blocks: a walk that ends where they are picked up saves a walk for them later.
        List<AABB> dropped = player.hasInfiniteMaterials() ? List.of() : player.level().getEntitiesOfClass(ItemEntity.class,
                player.getBoundingBox().inflate(16, 6, 16), item -> isHelper(item.getItem())).stream().map(ItemEntity::getBoundingBox).toList();
        // What each place to stand is expected to place, to tell on arriving which didn't go in; and for the log, the looks
        // checked and the blocks in a row after them.
        Map<Long, List<Integer>> expectedBlocks = new HashMap<>();
        Map<Long, String> expected = debug() ? new HashMap<>() : null;
        // For the log: every place with the eye near the first block, whatever the goal makes of it.
        Vec3 firstMiddle = Vec3.atCenterOf(plan.pos(near.getFirst()));
        List<Navigator.Node> close = new ArrayList<>();
        // The highest place with a block in reach, nearest the first block: where a search that ran out of places goes on
        // from (see below).
        Navigator.Node[] nearest = new Navigator.Node[1];
        Navigator.Goal goal = node -> {
            seen[0]++;
            if (debug() && close.size() < 300 && node.eye().distanceToSqr(firstMiddle) < 25) close.add(node);
            if (uselessSpots.contains(node.key()) || traps.contains(node.key()) || carriedFrom.contains(node.key())) return 0;
            if (intoFall.computeIfAbsent(node.feetCell().asLong(), k -> currentIntoFall(player.level(), node.feetCell()))) return 0;
            // Waded through, never stood in: water would push the player off every look. But for a cell the build fills
            // with water itself, a kelp channel say, with a floor and no drop beside it: water is poured from there.
            boolean wetHere = wetSoon(player.level(), node.feetCell(), poured, wetCells);
            // Water that stands still, kelp's say, pushes no one: any block may be placed from there.
            if (wetHere && poured.isEmpty() && stillWater(level, node)) wetHere = false;
            if (wetHere && !wetPourStand(level, node.feetCell(), null)) return 0;
            Vec3 feet = node.standing(), eye = node.eye();
            AABB body = new AABB(node.x() + 0.2, node.feet(), node.z() + 0.2, node.x() + 0.8, node.feet() + 1.8, node.z() + 0.8);
            BlockPos floor = node.floor();
            VoxelShape floorShape = level.getBlockState(floor).getCollisionShape(level, floor);
            double rimTop = floorShape.isEmpty() ? node.feet() : floor.getY() + floorShape.max(Direction.Axis.Y);
            List<Integer> inReach = new ArrayList<>();
            for (int i : near) {
                Set<Long> pocket = shutIn.get(i);
                if (pocket != null && pocket.contains(node.key())) continue;
                AABB cell = new AABB(plan.pos(i));
                if (!cell.intersects(body) && cell.distanceToSqr(eye) <= (reach + 0.5) * (reach + 0.5)) inReach.add(i);
            }
            if (inReach.isEmpty()) return 0;
            // Places for the log: those standing on top of the layer, nearest the first block, are picked out below.
            seen[1]++;
            if (debug() && sample.size() < 400) sample.add(node);
            Navigator.Node was = nearest[0];
            if (was == null || node.feet() > was.feet() + 1e-3 || Math.abs(node.feet() - was.feet()) < 1e-3
                    && node.eye().distanceToSqr(firstMiddle) < was.eye().distanceToSqr(firstMiddle)) nearest[0] = node;
            // Close enough by distance; whether a real look reaches them from here is checked for the nearest few.
            inReach.sort(Comparator.comparingDouble(i -> Vec3.atCenterOf(plan.pos(i)).distanceToSqr(eye)));
            double good = 0;
            List<Integer> placeable = new ArrayList<>();
            Boolean narrowTop = null;
            for (int i : inReach.subList(0, Math.min(VERIFY, inReach.size()))) {
                long key = Navigator.pairKey(node.key(), i);
                if (missedFrom.contains(key)) continue;
                if (wetHere && plan.kind(i) != BlockRules.Kind.FLUID && !plan.needsPour(i)) continue;
                // On a narrow top over a drop the player stands crouched: water is poured from there with the crouched eye,
                // and a block filled where it stands, which takes the click standing, isn't counted for such a place.
                boolean crouched = false;
                if (narrowTop == null) narrowTop = Navigator.narrowOverDrop(level, node);
                boolean pours = plan.kind(i) == BlockRules.Kind.FLUID || plan.soaking(i) || plan.needsPour(i);
                if (pours) {
                    if (narrowTop && plan.soaking(i)) continue;
                    crouched = narrowTop;
                }
                Integer ok = verified.get(key);
                if (ok == null && crouched) {
                    ok = pourCrouched(player, feet, i, reach);
                    verified.put(key, ok);
                }
                // A block too: decide takes only a crouched click there (onNarrowEdge), a standing one stands the player
                // up over the drop. Counted with the crouched eye, or the walk there places nothing.
                if (ok == null && narrowTop && !pours) {
                    ok = placeCrouched(player, feet, i, reach);
                    verified.put(key, ok);
                }
                if (ok == null) {
                    ok = placeableFrom(player, feet, i, reach);
                    // In a bowl, a hopper's or a composter's, a walk that comes to rest a little off the middle stands up on
                    // the rim, a few tenths higher: the look must work from there too.
                    if (ok > 0 && rimTop > feet.y + 0.05) ok = Math.min(ok, placeableFrom(player, new Vec3(feet.x, rimTop, feet.z), i, reach));
                    verified.put(key, ok);
                }
                // One that a walk was made for and didn't go in twice already: only a look that holds off the middle counts.
                if (ok == 1 && arrivalMisses.getOrDefault(i, 0) >= 2) ok = 0;
                if (ok > 0) {
                    // A look that only works from the very middle of the block counts for little: the walk comes to rest
                    // a little off it, and from there the look misses, and the walk was for nothing.
                    good += ok == 2 ? 1 : 0.25;
                    if (ok == 2) firm.add(node.key());
                    placeable.add(i);
                }
            }
            if (good > 0) {
                seen[2]++;
                firstFrom.put(node.key(), placeable.getFirst());
                int more = chained(player, node, reach, placeable, pool, budget);
                good += more;
                expectedBlocks.put(node.key(), List.copyOf(placeable));
                if (expected != null) {
                    StringBuilder text = new StringBuilder();
                    for (int i : placeable) text.append(text.isEmpty() ? "" : "; ").append(plan.pos(i).toShortString());
                    expected.put(node.key(), placeable.size() + " (" + text + ") +" + more + " in a row, " + inReach.size() + " in reach");
                }
            }
            if (good == 0) return 0;
            if (!dropped.isEmpty()) {
                // Picked up within a block of the body sideways and half a block up or down, as vanilla does, give or take where
                // the walk comes to rest.
                AABB pickup = body.inflate(1 - PICKUP_SLACK, 0.5, 1 - PICKUP_SLACK);
                for (AABB item : dropped) {
                    if (item.intersects(pickup)) {
                        good += PICKUP_BONUS;
                        break;
                    }
                }
            }
            // A little leaning toward some places over others, its own each walk: not always the very same spot.
            double lean = 0.88 + 0.24 * Navigator.unit(node.key() ^ leaning);
            // Beside running water a walk that comes to rest a little off the middle is in the current: only when nothing
            // else does.
            if (currentCells.computeIfAbsent(node.feetCell().asLong(), k -> currentBeside(player.level(), node.feetCell()))) lean *= 0.2;
            return (good * 2 + Math.min(inReach.size(), 24) * 0.25) * lean;
        };
        // Standing more than a couple of blocks over the layer sees nothing more of it.
        double ceiling = layerY + 2.5;
        Navigator.Path path = navigator(player, true).climbUpTo(ceiling).vary(leaning).firmWhen(firm::contains).find(start, goal);
        if (path == null) {
            // Up on the build every place to stand is on top of it, where a block that needs a look from below or beside
            // has none: down off it onto the ground round it, a jump of a few blocks, and from there up beside it, as a
            // player would. Only once the ways that do no harm found nothing, never down a gap within the build, and on a
            // pillar of helper blocks only once it has been taken down.
            double down = farDrop(player);
            if (down > 0 && stepDownNow(player)) return true;
            if (down > 0) {
                path = navigator(player, true).climbUpTo(ceiling).vary(leaning).firmWhen(firm::contains).dropsOff(down, plan.bounds)
                        .find(start, goal);
                if (path != null && (path.nodes().size() == 1 || dropsIntoTrap(player, path))) path = null;
                if (path != null && debug()) {
                    LOGGER.info("No place to stand from up here: down off the build first, on the way to {} {} {}", path.end().x(),
                            path.end().feet(), path.end().z());
                }
            }
        }
        if (path == null && seen[2] == 0 && seen[0] >= Navigator.MAX_NODES && nearest[0] != null && approached.add(nearest[0].key())
                && approach(client, player, start, nearest[0], ceiling, leaning)) {
            // The search ran out of places before it got to one with a look: from far off, the open ground beside the
            // build after a restock say, every place below and round the build costs less than a climb up onto its top,
            // and those are searched first. Over to the highest place it did get to beside the blocks, and searched again
            // from there, as the place on top the walk was for before the restock is a few steps off from there (the
            // bone-meal crafter's bubble column at 12, 78, 34).
            if (debug()) LOGGER.info("No place to stand for {} blocks, such as {} at {}, among the {} places reached from {}: over to {} {} {}, nearer them, to search again",
                    near.size(), plan.target(near.getFirst()), plan.pos(near.getFirst()).toShortString(), seen[0], start, nearest[0].x(),
                    nearest[0].feet(), nearest[0].z());
            show("Layer " + layerNumber + " of " + plan.layers().size() + ": walking nearer the blocks left", false);
            return true;
        }
        noStand = path == null ? List.copyOf(near) : List.of();
        if (path == null) {
            // Dry kelp with no place a pour into its spot is made from: passed over again, its layer going on as before
            // da-45, the water over its column filling it later; worked out again once the blocks round it change.
            for (int i : near) {
                if (!plan.plant(i) || !plan.needsPour(i)) continue;
                plan.support(i, BuildPlan.stamp(level, plan.pos(i)), false);
                if (debug()) LOGGER.info("{} at {}: no place to pour into its spot from, passed over until the blocks round it change",
                        plan.target(i).getBlock().getName().getString(), plan.pos(i).toShortString());
            }
        }
        if (path == null && debug() && swimStands()) logSwimLooks(player, near, reach);
        if (path == null && helperItem(player) != null) {
            for (int i : near.subList(0, Math.min(GUARD_CHECKS, near.size()))) {
                if (guardSpot.containsKey(i) || guardWalks.getOrDefault(i, 0) >= REPAIRS) continue;
                BlockPos guard = guardFor(player, i);
                if (guard == null) continue;
                guardSpot.put(i, guard);
                if (debug()) LOGGER.info("{} at {}: its place to stand is in a current into a fall at {}, a helper block goes there first",
                        plan.target(i).getBlock().getName().getString(), plan.pos(i).toShortString(), guard.toShortString());
            }
        }
        if (path == null && seen[2] == 0 && seen[0] < Navigator.MAX_NODES && tick - lastProgress > UP_GIVE_UP_TICKS) {
            // Every place a walk reaches searched, well short of the limit, and not one with a look at any of these: the
            // blocks placed with a look up among them have none anywhere, as a row of up-facing observers over a solid
            // column, with only a water stream beside it (v4-z3, y 73). Given up on together, a row at a time, rather than
            // one every five minutes; counted as failed at the end.
            List<Integer> up = new ArrayList<>();
            // Not one a helper block in the current beside it is to give a place to stand first (see guardFor).
            for (int i : near) if (BlockRules.placedLookingUp(plan.target(i)) && !guardSpot.containsKey(i)) up.add(i);
            if (!up.isEmpty()) {
                if (debug()) LOGGER.info("Giving up on {} blocks placed with a look up, such as {} at {}: {} places reached, none with a look at any",
                        up.size(), plan.target(up.getFirst()), plan.pos(up.getFirst()).toShortString(), seen[0]);
                for (int i : up) {
                    plan.giveUp(i);
                    plan.update(level, i);
                }
                lastProgress = tick;
            }
        }
        if (path == null && tick - lastProgress > GIVE_UP_TICKS) {
            // Nothing has gone in for minutes, and still no place to stand with a look at it: given up on, as a player
            // leaves what they can't reach, and counted as failed at the end; the build goes on with the rest.
            int stuck = near.getFirst();
            if (debug()) LOGGER.info("Giving up on {} at {}: nothing went in for {} s and no place to stand has a look at it",
                    plan.target(stuck), plan.pos(stuck).toShortString(), (tick - lastProgress) / 20);
            plan.giveUp(stuck);
            plan.update(level, stuck);
            lastProgress = tick;
        }
        // Searches in a row that found no place at all with a block in reach: shut in somewhere, as after a fall into a
        // space under the build. So too a search that ran out of places long before its limit with blocks in reach but no
        // look at any: a few tops between two channels say, every look from them shut off.
        boolean shutIn = seen[1] == 0 || seen[2] == 0 && seen[0] < SHUT_IN_PLACES;
        noneInReach = path == null && shutIn ? noneInReach + 1 : 0;
        if (path == null) {
            long now = System.nanoTime();
            failedWalkKey[kind] = searchKey;
            failedWalkAt[kind] = now;
            if (debug() && (near.getFirst() != lastExplained || now - explainedAt > 10_000_000_000L)) {
                lastExplained = near.getFirst();
                explainedAt = now;
                LOGGER.info("No place to stand for {} blocks, such as {} at {}: {} places reached from {}, {} with one in reach, {} with a look",
                        near.size(), plan.target(near.getFirst()), plan.pos(near.getFirst()).toShortString(), seen[0], start, seen[1], seen[2]);
                int first = near.getFirst();
                Item item = BlockRules.item(plan.target(first));
                Vec3 middle = Vec3.atCenterOf(plan.pos(first));
                // Places on top of the layer, nearest the block; for one that takes a look up at it, an up-facing observer
                // say, places with the eye under it instead, where that look comes from.
                boolean up = BlockRules.placedLookingUp(plan.target(first));
                List<Navigator.Node> high = new ArrayList<>(sample.stream()
                        .filter(n -> up ? n.eye().y < plan.pos(first).getY() : n.feet() >= plan.pos(first).getY() + 1)
                        .sorted(Comparator.comparingDouble(n -> n.eye().distanceToSqr(middle))).limit(5).toList());
                // And places with the eye about level with the block, nearest first: a dispenser or piston facing sideways
                // takes a level look, from a place down beside it, a channel say, that the places on top never show.
                sample.stream().filter(n -> !high.contains(n) && n.eye().y >= plan.pos(first).getY() - 0.5
                                && n.eye().y <= plan.pos(first).getY() + 1.6)
                        .sorted(Comparator.comparingDouble(n -> n.eye().distanceToSqr(middle))).limit(3).forEach(high::add);
                boolean pours = plan.kind(first) == BlockRules.Kind.FLUID || plan.needsPour(first) || plan.soaking(first);
                for (Navigator.Node node : pours ? List.<Navigator.Node>of() : high) {
                    for (boolean crouch : new boolean[] {false, true}) {
                        Vec3 eye = node.standing().add(0, crouch ? PlacementSolver.CROUCH_EYE : PlacementSolver.STAND_EYE, 0);
                        LOGGER.info("  from {}: {}", node, PlacementSolver.explain(player, eye, crouch, plan.pos(first), plan.target(first),
                                new ItemStack(item), reach, cell -> unclickable(cell, first)));
                    }
                }
                if (pours) {
                    // A pour isn't a block placed: for the places nearest it, what each check the walk goes by makes of it.
                    BlockPos at = plan.pos(first);
                    Fluid fluid = plan.kind(first) == BlockRules.Kind.FLUID ? plan.target(first).getFluidState().getType() : Fluids.WATER;
                    Map<Long, Boolean> wetLog = new HashMap<>();
                    close.sort(Comparator.comparingDouble(n -> n.eye().distanceToSqr(firstMiddle)));
                    // Each place once: the nearest few, and then the nearest few the water wouldn't reach.
                    Map<Long, Navigator.Node> distinct = new LinkedHashMap<>();
                    for (Navigator.Node node : close) distinct.putIfAbsent(node.key(), node);
                    List<Navigator.Node> shown = new ArrayList<>(distinct.values().stream().limit(4).toList());
                    distinct.values().stream().filter(n -> !shown.contains(n) && !floods(level, at, n.standing())
                            && !wetSoon(level, n.feetCell(), recentPours(), new HashMap<>())).limit(4).forEach(shown::add);
                    for (Navigator.Node node : shown) {
                        Vec3 feet = node.standing();
                        LOGGER.info("  pour from {}: standing {}, crouched {}; narrow top {}, floods {}, wet soon {}, passed over {}", node,
                                PlacementSolver.pour(level, player, feet.add(0, PlacementSolver.STAND_EYE, 0), at, fluid, reach) != null,
                                PlacementSolver.pour(level, player, feet.add(0, PlacementSolver.CROUCH_EYE, 0), at, fluid, reach) != null,
                                Navigator.narrowOverDrop(level, node), floods(level, at, feet),
                                wetSoon(level, node.feetCell(), recentPours(), wetLog),
                                uselessSpots.contains(node.key()) || traps.contains(node.key()) || carriedFrom.contains(node.key()));
                    }
                }
            }
            return false;
        }
        if (path.nodes().size() == 1) {
            // A real look should reach a block from here, yet none went in: look further.
            uselessSpots.add(start.key());
            path = navigator(player, true).climbUpTo(ceiling).vary(leaning).firmWhen(firm::contains).find(start, goal);
            if (path == null || path.nodes().size() == 1) return false;
        }
        // A drop into a place with no way back up would end the build there: those places are left out, and the
        // search goes on for another.
        for (int tries = 0; dropsIntoTrap(player, path); tries++) {
            path = tries < 2 ? navigator(player, true).climbUpTo(ceiling).vary(leaning).firmWhen(firm::contains).find(start, goal) : null;
            if (path == null || path.nodes().size() == 1) return false;
        }
        if (!climbReady(client, player, path)) return true;
        releaseKeys(client);
        Navigator.Node end = path.end();
        Integer first = firstFrom.get(end.key());
        walker.start(path.nodes(), first == null ? null : Vec3.atCenterOf(plan.pos(first)));
        // Only looks from the very middle of the place counted for it: the walk ends there, not a few tenths off.
        walker.precise(!firm.contains(end.key()));
        walksSinceClick++;
        lastWalkEnd = end;
        clickedSinceWalk = false;
        show("Layer " + layerNumber + " of " + plan.layers().size() + ": walking to " + end.x() + " " + Math.round(end.feet()) + " " + end.z(), false);
        if (debug()) {
            LOGGER.info("Walking {} steps to {} {} {} for {}; {} climbs, {} steps on walk helpers standing", path.nodes().size() - 1, end.x(),
                    end.feet(), end.z(), expected.get(end.key()), path.nodes().stream().filter(node -> node.move() == Navigator.Move.PILLAR).count(),
                    path.nodes().stream().skip(1).filter(node -> {
                        Helper helper = helpers.get(node.floor().asLong());
                        return helper != null && helper.serves() < 0;
                    }).count());
        }
        walkExpects.clear();
        walkExpects.addAll(expectedBlocks.getOrDefault(end.key(), List.of()));
        liftedFor.clear();
        if (lift) liftedFor.addAll(walkExpects);
        walkEnd = end;
        return true;
    }

    /**
     * A walk over to {@code to}, a place planWalk's search got to: the way there worked out again, as that search went,
     * with or without a drop off the build. True once it is under way, or helper blocks are on their way for its climbs.
     */
    private boolean approach(Minecraft client, LocalPlayer player, Navigator.Node start, Navigator.Node to, double ceiling, long leaning) {
        Navigator.Goal there = node -> node.key() == to.key() ? 1 : 0;
        Navigator.Path path = navigator(player, true).climbUpTo(ceiling).vary(leaning).find(start, there);
        double down = farDrop(player);
        if (path == null && down > 0) path = navigator(player, true).climbUpTo(ceiling).vary(leaning).dropsOff(down, plan.bounds).find(start, there);
        if (path == null || path.nodes().size() < 2 || dropsIntoTrap(player, path)) return false;
        if (!climbReady(client, player, path)) {
            // Tried again once they are there.
            approached.remove(to.key());
            return true;
        }
        releaseKeys(client);
        walker.start(path.nodes());
        // Not a walk for any block: nothing is expected to go in on arriving, and the place isn't passed over for it.
        walkEnd = null;
        walkExpects.clear();
        liftedFor.clear();
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

    /** How far off the middle of a block a walk may come to rest, along each axis: where it ends, give or take the slide. */
    private static final double REST_SPREAD = 0.25;

    /**
     * Whether block {@code i} could go in, with a real look, from a place to stand with the feet at {@code feet}: 2 when
     * the click's spot is still hit from wherever the walk there may come to rest, 1 when only from the very middle of
     * the block, as a steep look up at the side of a block, 0 when not at all.
     */
    /**
     * As {@link #placeableFrom} for water or lava poured, crouched with the feet at {@code feet}, as on a narrow top over a
     * drop: 1 with a look, which counts as no firm find, the crouched eye's looks not weighed off the middle; else 0.
     */
    private int pourCrouched(LocalPlayer player, Vec3 feet, int i, double reach) {
        Level level = player.level();
        BlockPos pos = plan.pos(i);
        if (floods(level, pos, feet) && !wetPourStand(level, BlockPos.containing(feet), pos)) return 0;
        Fluid fluid = plan.kind(i) == BlockRules.Kind.FLUID ? plan.target(i).getFluidState().getType() : Fluids.WATER;
        return PlacementSolver.pour(level, player, feet.add(0, PlacementSolver.CROUCH_EYE, 0), pos, fluid, reach) != null ? 1 : 0;
    }

    /**
     * As {@link #placeableFrom} for a block placed from a narrow top over a drop, crouched: 2 for a steady click, 1 for one
     * only from the very middle, 0 for none. Not for a block in place that only takes a click to set it, which decide
     * doesn't do from there either.
     */
    private int placeCrouched(LocalPlayer player, Vec3 feet, int i, double reach) {
        BlockPos pos = plan.pos(i);
        BlockState target = plan.target(i), current = player.level().getBlockState(pos);
        if (current.getBlock() == target.getBlock() || BlockRules.needsTool(current, target)) return 0;
        Item item = BlockRules.item(target);
        if (item == null) return 0;
        PlacementSolver.Click click = PlacementSolver.fromSpotCrouched(player, feet, pos, target, new ItemStack(item), reach, at -> unclickable(at, i));
        return click == null ? 0 : steadyFrom(player, feet, click, reach) ? 2 : 1;
    }

    private int placeableFrom(LocalPlayer player, Vec3 feet, int i, double reach) {
        Level level = player.level();
        BlockPos pos = plan.pos(i);
        BlockState target = plan.target(i), current = level.getBlockState(pos);
        Vec3 eye = feet.add(0, PlacementSolver.STAND_EYE, 0);
        if (plan.kind(i) == BlockRules.Kind.FLUID) {
            if (floods(level, pos, feet) && !wetPourStand(level, BlockPos.containing(feet), pos)) return 0;
            PlacementSolver.Click pour = PlacementSolver.pour(level, player, eye, pos, target.getFluidState().getType(), reach);
            // As for a block: a pour that only works from the very middle, at the edge of reach say, counts for little, and
            // the walk for it ends there exactly.
            return pour == null ? 0 : steadyFrom(player, feet, pour, reach) ? 2 : 1;
        }
        if (plan.soaking(i) && !BlockRules.fillsInPlace(current)) return inView(player, eye, pos, reach - 0.25) ? 2 : 0;
        if (plan.soaking(i)) return !floods(level, pos, feet) && PlacementSolver.fill(player, eye, pos, reach) != null ? 2 : 0;
        if (plan.needsPour(i)) {
            // Into a kelp spot never from under it: from below, the look into the spot above the eye goes in, but the
            // click for the kelp right after misses, and the water runs down to where the player stands (v1-clean3/8, the
            // corridor x 7 under IKEA v1's machine, feet at 66, for the kelp of y 68).
            if (plan.plant(i) && feet.y < pos.getY()) return 0;
            if (floods(level, pos, feet) && !wetPourStand(level, BlockPos.containing(feet), pos)) return 0;
            // Kelp goes in right after against the same face, from where the walk comes to rest: a pour that only works
            // from the very middle counts for little, as v1-clean3's walks for the kelp of y 68 placed nothing after each
            // pour ("from its middle a look, from the feet no look").
            PlacementSolver.Click pour = PlacementSolver.pour(level, player, eye, pos, Fluids.WATER, reach);
            return pour == null ? 0 : steadyFrom(player, feet, pour, reach) ? 2 : 1;
        }
        if (BlockRules.needsTool(current, target)) {
            return carriedTool(player, target) != null && PlacementSolver.till(player, eye, pos, reach) != null ? 2 : 0;
        }
        if (current.getBlock() == target.getBlock() && BlockRules.missing(current, target) == 0) {
            // Already in as it should be, with a status not yet read again: worth a walk only when a click still sets something.
            return BlockRules.adjustClicks(current, target) > 0 && PlacementSolver.use(player, eye, pos, reach, target) != null ? 2 : 0;
        }
        Item item = BlockRules.item(target);
        if (item == null) return 0;
        // The block itself first: a helper is worked out for one no look seemed to reach, yet from some places one does.
        LongPredicate unclickable = at -> unclickable(at, i);
        PlacementSolver.Click click = PlacementSolver.fromSpot(player, feet, pos, target, new ItemStack(item), reach, unclickable);
        if (click != null) return steadyFrom(player, feet, click, reach) ? 2 : 1;
        BlockPos helper = helperSpot.get(i);
        Item helperItem = helperItem(player);
        if (helper == null || helperItem == null || !level.getBlockState(helper).canBeReplaced()) return 0;
        BlockState helperState = Block.byItem(helperItem).defaultBlockState();
        PlacementSolver.Click put = PlacementSolver.fromSpot(player, feet, helper, helperState, new ItemStack(helperItem), reach,
                helpers::containsKey);
        if (put == null) return 0;
        // Worth a walk when the block itself then goes in against the helper from here too. Only the helper counts for little:
        // the walk puts it down, nothing more can go in, and the next walk comes back from somewhere else for the block.
        Overlay withHelper = new Overlay(level, Map.of(helper.asLong(), helperState));
        PlacementSolver.Click then = PlacementSolver.fromSpot(player, withHelper, feet, pos, target, new ItemStack(item), reach, unclickable);
        if (then == null) return 1;
        return steadyFrom(player, level, feet, put, reach) && steadyFrom(player, withHelper, feet, then, reach) ? 2 : 1;
    }

    private static boolean steadyFrom(LocalPlayer player, Vec3 feet, PlacementSolver.Click click, double reach) {
        return steadyFrom(player, player.level(), feet, click, reach);
    }

    /**
     * Whether the click's spot, aimed at from {@code feet} give or take {@link #REST_SPREAD}, is hit first, on its face,
     * tracing through {@code view}.
     */
    private static boolean steadyFrom(LocalPlayer player, BlockGetter view, Vec3 feet, PlacementSolver.Click click, double reach) {
        Vec3 point = click.hit().getLocation();
        double height = click.crouch() ? PlacementSolver.CROUCH_EYE : PlacementSolver.STAND_EYE;
        for (double dx : new double[] {-REST_SPREAD, REST_SPREAD}) {
            for (double dz : new double[] {-REST_SPREAD, REST_SPREAD}) {
                Vec3 eye = feet.add(dx, height, dz);
                if (eye.distanceTo(point) > reach) return false;
                Vec3 end = eye.add(point.subtract(eye).normalize().scale(eye.distanceTo(point) + 0.5));
                BlockHitResult hit = view.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
                if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(click.hit().getBlockPos())
                        || hit.getDirection() != click.hit().getDirection()) return false;
            }
        }
        return true;
    }

    private void walk(Minecraft client, LocalPlayer player) {
        walker.careful(carefulSteps);
        if (player.isShiftKeyDown() && !walker.holdsCrouch() && !climbCrouch) setCrouch(client, false);
        Walker.State state = walker.tick(client, player, new Walker.Climb() {
            @Override
            public boolean ready(BlockPos cell) {
                // Onto a block with a use, a dispenser's top say, the block goes in crouched, as a player pillars up from
                // one: else the click opens it.
                climbCrouch = BlockRules.interactive(player.level().getBlockState(cell.below()).getBlock());
                if (climbCrouch && !crouchHeld) setCrouch(client, true);
                // Ground dug out is filled with what was there, when the hotbar has it.
                BlockState was = dug.get(cell.asLong());
                if (was != null && selectInHotbar(player, was.getBlock().asItem())) return true;
                Item item = helperItem(player);
                return item != null && selectInHotbar(player, item);
            }

            @Override
            public boolean placeBelow(BlockPos cell) { return climbPlace(client, player, cell); }

            @Override
            public void crouch(boolean on) { setCrouch(client, on); }

            @Override
            public boolean placeBeside(BlockPos support, Direction face) { return bridgePlace(client, player, support, face); }

            @Override
            public boolean handReady() { return SchematicBuilder.this.handReady(player); }
        });
        if (state != Walker.State.WALKING) {
            climbCrouch = false;
            escapeWalk = false;
        }
        if (state == Walker.State.ARRIVED) {
            if (debug()) {
                Navigator.Node end = Navigator.standingAt(player.level(), player);
                LOGGER.info("Arrived at {} {} {}, {} off the middle", end.x(), end.feet(), end.z(), String.format(Locale.ROOT, "%.2f",
                        Math.hypot(player.getX() - end.x() - 0.5, player.getZ() - end.z() - 0.5)));
            }
            walkFailures = 0;
            nextAction = System.nanoTime();
        } else if (state == Walker.State.FAILED) {
            if (debug()) LOGGER.info("Walk failed at {}: {}", player.position(), walker.failure());
            // Crouched for a bridge or an edge when it failed: up again, so what comes next isn't done from a lower eye. Not
            // on a narrow top over a drop, where crouching is what holds the player.
            if (crouchHeld && !onNarrowEdge(player)) setCrouch(client, false);
            // The same step would fail the same way: the next walk goes around it.
            Navigator.Node[] step = walker.failedStep();
            if (step != null) {
                long key = Navigator.stepKey(step[0], step[1]);
                badSteps.add(key);
                stepFailures.merge(key, 1, Integer::sum);
                stepFailedAt.put(key, tick);
                // Off the side of a walk at its own height, on ice past a bend say: tried again, it is walked crouched.
                if (step[1].move() == Navigator.Move.WALK && walker.failure().startsWith("fell")) carefulSteps.add(key);
            }
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
        if (climbCrouch && !player.isShiftKeyDown()) {
            if (debug()) LOGGER.info("Climb at {}: not crouched yet over {}", cell.toShortString(), player.level().getBlockState(cell.below()));
            return false;
        }
        noteClick(player, hit);
        InteractionResult result = client.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
        if (!(result instanceof InteractionResult.Success success)) {
            // Nothing went in, the body still in the cell, say: no helper block to count, nor ground filled in; the climb
            // fails, and the walk goes another way.
            if (debug()) LOGGER.info("Climb at {}: the click gave {}", cell.toShortString(), result);
            return false;
        }
        if (success.swingSource() == InteractionResult.SwingSource.CLIENT) player.swing(InteractionHand.MAIN_HAND);
        climbCrouch = false;
        if (was != null) {
            // The pit is filled in again on the way out, and stays filled.
            dug.remove(cell.asLong());
            if (item != was.getBlock().asItem()) filledOther.put(cell.asLong(), was);
        } else {
            helpers.put(cell.asLong(), new Helper(-1, Block.byItem(item)));
            if (debug()) LOGGER.info("Climbing: a helper block at {}, {} helper blocks standing", cell.toShortString(), helpers.size());
            if (escapeWalk || !brokeOut.isEmpty()) addEscapeHelper(cell);
        }
        return true;
    }

    /**
     * Puts a helper block against {@code face} of {@code support}, the block underfoot at the edge of a bridging step,
     * where the crosshair is: crouched, so a chest or the like there isn't opened. False while the crosshair isn't on it.
     */
    /** When the last bridging click was refused, so the next waits as long as one after a click that went in. */
    private long bridgeRefusedAt = Long.MIN_VALUE / 2;

    private boolean bridgePlace(Minecraft client, LocalPlayer player, BlockPos support, Direction face) {
        Item item = helperItem(player);
        BlockHitResult hit = crosshair(player);
        if (item == null || !player.getMainHandItem().is(item) || hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(support)
                || hit.getDirection() != face) {
            if (debug() && tick - bridgeExplained >= 20) {
                bridgeExplained = tick;
                LOGGER.info("Bridging: no click yet for the {} side of {}: crosshair on {} {} {} at {}, eye {}, look {} {}, {}, holding {}",
                        face, support.toShortString(), hit.getType(), hit.getBlockPos().toShortString(), hit.getDirection(), hit.getLocation(),
                        player.getEyePosition(), player.getYRot(), player.getXRot(), player.isShiftKeyDown() ? "crouching" : "standing",
                        player.getMainHandItem());
            }
            return false;
        }
        if (!handReady(player) || tick - bridgeRefusedAt < 4) return false;
        noteClick(player, hit);
        InteractionResult result = client.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
        if (!(result instanceof InteractionResult.Success success)) {
            // Nothing went in: no helper block counted where there is none. Clicked again a few ticks on, as before.
            if (debug()) LOGGER.info("Bridging: the click on the {} side of {} gave {}", face, support.toShortString(), result);
            bridgeRefusedAt = tick;
            return false;
        }
        if (success.swingSource() == InteractionResult.SwingSource.CLIENT) player.swing(InteractionHand.MAIN_HAND);
        helpers.put(support.relative(face).asLong(), new Helper(-1, Block.byItem(item)));
        if (escapeWalk || !brokeOut.isEmpty()) addEscapeHelper(support.relative(face));
        if (debug()) LOGGER.info("Bridging: a helper block against the {} side of {}", face, support.toShortString());
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
        if (digAlong(player, candidates, materials, layerNumber, lift, reach, started, false)) return true;
        // As for walks: a pit search that found nothing finds nothing again until something it goes by changes.
        long searchKey = walkSearchKey(player, candidates, lift) * 31 + uselessPits.size();
        if (searchKey == failedPitKey && System.nanoTime() - failedPitAt < FAILED_SEARCH_NANOS) return false;
        int tried = 0;
        for (int i : candidates) {
            if (plan.status(i) != BuildPlan.Status.PENDING || plan.kind(i) != BlockRules.Kind.PLACE || support(player, i, started) != 1) continue;
            if (!lift && seals(player, i, started) == 1) continue;
            if (lift && liftHolds(player, i)) continue;
            BlockPos pos = plan.pos(i);
            BlockState target = plan.target(i);
            if (level.getBlockState(pos).getBlock() == target.getBlock()) continue;
            Item item = BlockRules.item(target);
            if (item == null || !creative && (materials.total(item) == 0
                    || materials.loose(item) == 0 && (noBoxHere(item) || waitsForRoom(item, materials)))) continue;
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
                    if (!climbReady(client, player, path)) return true;
                    if (debug()) LOGGER.info("Digging a pit {} deep at {} {} {} for {} at {}", depth, candidate.x(), candidate.top(),
                            candidate.z(), target, pos.toShortString());
                    pit = lastPit = candidate;
                    pitAlong = false;
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
        failedPitKey = searchKey;
        failedPitAt = System.nanoTime();
        return false;
    }

    /**
     * Standing at the bottom of a pit, the next block that needs one is placed from a pit beside this one where it can
     * be: dug from here, sideways, and stepped into, so the pits run on as a trench, as a player digs along a row of
     * blocks to place from below, rather than climbing out and digging in again for each. True when one is begun.
     */
    private boolean digAlong(LocalPlayer player, List<Integer> candidates, Materials materials, int layerNumber, boolean lift,
                             double reach, long started, boolean lookingUpOnly) {
        if (lastPit == null || !inPit(player, lastPit)) return false;
        Level level = player.level();
        boolean creative = player.hasInfiniteMaterials();
        int tried = 0;
        for (int i : candidates) {
            if (plan.status(i) != BuildPlan.Status.PENDING || plan.kind(i) != BlockRules.Kind.PLACE || support(player, i, started) != 1) continue;
            if (lookingUpOnly && !BlockRules.placedLookingUp(plan.target(i))) continue;
            if (!lift && seals(player, i, started) == 1) continue;
            if (lift && liftHolds(player, i)) continue;
            BlockPos pos = plan.pos(i);
            BlockState target = plan.target(i);
            if (level.getBlockState(pos).getBlock() == target.getBlock()) continue;
            Item item = BlockRules.item(target);
            if (item == null || !creative && materials.total(item) == 0) continue;
            if (++tried > 6 || System.nanoTime() - started > 40_000_000L) break;
            for (Direction side : Direction.Plane.HORIZONTAL) {
                Pit candidate = pitAt(level, lastPit.x() + side.getStepX(), pos.getY(), lastPit.z() + side.getStepZ(), lastPit.depth());
                if (candidate == null || candidate.top() != lastPit.top() || uselessPits.contains(candidate)) continue;
                // Only a column or two from the block, as any pit for it.
                if (Math.abs(candidate.x() - pos.getX()) > 2 || Math.abs(candidate.z() - pos.getZ()) > 2) continue;
                Map<Long, BlockState> hollow = new HashMap<>();
                for (int k = 0; k < candidate.depth(); k++) hollow.put(candidate.cell(k).asLong(), Blocks.AIR.defaultBlockState());
                int index = i;
                PlacementSolver.Click click = PlacementSolver.fromSpot(player, new Overlay(level, hollow), candidate.feet(), pos, target,
                        new ItemStack(item), reach, at -> unclickable(at, index));
                if (click == null) continue;
                if (debug()) LOGGER.info("Digging on along to {} {} {} for {} at {}", candidate.x(), candidate.top(), candidate.z(), target,
                        pos.toShortString());
                pit = lastPit = candidate;
                pitAlong = true;
                pitTopFirst = ThreadLocalRandom.current().nextBoolean();
                placedSincePit = false;
                digTicks = 0;
                show("Layer " + layerNumber + " of " + plan.layers().size() + ": digging on along to place "
                        + target.getBlock().getName().getString() + " from below", false);
                return true;
            }
        }
        return false;
    }

    /** Whether the player stands on the floor of {@code pit}, dug out above them. */
    private boolean inPit(LocalPlayer player, Pit pit) {
        if (!player.onGround() || Math.abs(player.getY() - pit.feet().y) > 0.1) return false;
        if (Math.floor(player.getX()) != pit.x() || Math.floor(player.getZ()) != pit.z()) return false;
        Level level = player.level();
        for (int k = 0; k < pit.depth(); k++) {
            if (!level.getBlockState(pit.cell(k)).getCollisionShape(level, pit.cell(k)).isEmpty()) return false;
        }
        return true;
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
        if (pitAlong) {
            digOnAlong(client, player);
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
            groundDug.putIfAbsent(next.asLong(), level.getBlockState(next));
            pours.put(next.asLong(), tick);
            restingTicks = 0;
            // Ground taken out may open a way out of where a block would have shut the player in.
            shutIn.clear();
            traps.clear();
        }
        show("Digging in at " + pit.x() + " " + pit.top() + " " + pit.z() + " to place a block from below", false);
        if (!mine(client, player, next)) abandonPit(client, "can't see the ground under the feet");
    }

    /**
     * Mines the pit beside the one the player stands in, its blocks one after the other, looking at them from where the
     * player stands; then steps into it.
     */
    private void digOnAlong(Minecraft client, LocalPlayer player) {
        ClientLevel level = client.level;
        BlockPos next = null;
        for (int n = 0; n < pit.depth(); n++) {
            BlockPos cell = pit.cell(pitTopFirst ? n : pit.depth() - 1 - n);
            if (!level.getBlockState(cell).getCollisionShape(level, cell).isEmpty()) {
                next = cell;
                break;
            }
        }
        if (!player.onGround()) return;
        if (Math.abs(player.getY() - pit.feet().y) > 0.1) {
            abandonPit(client, "isn't standing at its floor");
            return;
        }
        double dx = pit.x() + 0.5 - player.getX(), dz = pit.z() + 0.5 - player.getZ();
        if (next == null) {
            client.gameMode.stopDestroyBlock();
            if (dx * dx + dz * dz <= 0.25 * 0.25) {
                pit = null;
                pitAlong = false;
                nextAction = System.nanoTime();
            } else if (dx * dx + dz * dz > 2 * 2) {
                abandonPit(client, "is too far from it");
            } else {
                // A step into it, as any walk: the walker brings the player to rest in its middle.
                walker.start(List.of(Navigator.standingAt(level, player),
                        new Navigator.Node(pit.x(), pit.top() - pit.depth(), pit.z(), pit.feet().y, Navigator.Move.WALK)));
            }
            return;
        }
        if (dx * dx + dz * dz > 1.6 * 1.6) {
            abandonPit(client, "is too far from it");
            return;
        }
        stopWalking(client);
        if (player.isShiftKeyDown()) setCrouch(client, false);
        if (!dug.containsKey(next.asLong())) {
            if (!holdTool(client, player, level.getBlockState(next))) return;
            dug.put(next.asLong(), level.getBlockState(next));
            groundDug.putIfAbsent(next.asLong(), level.getBlockState(next));
            pours.put(next.asLong(), tick);
            restingTicks = 0;
            shutIn.clear();
            traps.clear();
        }
        show("Digging on along at " + pit.x() + " " + pit.top() + " " + pit.z() + " to place a block from below", false);
        if (!mine(client, player, next)) abandonPit(client, "can't see the ground beside");
    }

    private void abandonPit(Minecraft client, String why) {
        if (debug()) LOGGER.info("Gave up the pit at {} {} {}: {}", pit.x(), pit.top(), pit.z(), why);
        stopWalking(client);
        if (client.gameMode != null) client.gameMode.stopDestroyBlock();
        if (pitTries.merge(pit, 1, Integer::sum) >= 3) uselessPits.add(pit);
        pit = null;
        pitAlong = false;
        // Not dug, so not the pit to blame if nothing goes in next.
        lastPit = null;
        nextAction = System.nanoTime() + 200_000_000L;
    }

    /** A small step toward a spot close by, turning to it as a player does. */
    private void stepToward(Minecraft client, LocalPlayer player, double dx, double dz) {
        float want = (float) Math.toDegrees(Math.atan2(-dx, dz));
        head.turnYaw(player, want);
        client.options.keyUp.setDown(Math.abs(Mth.wrapDegrees(want - player.getYRot())) < 30);
        walking = true;
    }

    /**
     * How far each helper block is from one standing on the ground, counted through helper blocks beside, under or over
     * one another: 0 for one with something not a helper block right under it. Those that reach no ground are left out.
     */
    private Map<Long, Integer> helperDepths(Level level) {
        Map<Long, Integer> depth = new HashMap<>();
        ArrayDeque<Long> queue = new ArrayDeque<>();
        for (long key : helpers.keySet()) {
            long below = BlockPos.offset(key, Direction.DOWN);
            BlockPos under = BlockPos.of(below);
            if (!helpers.containsKey(below) && !level.getBlockState(under).getCollisionShape(level, under).isEmpty()) {
                depth.put(key, 0);
                queue.add(key);
            }
        }
        while (!queue.isEmpty()) {
            long key = queue.poll();
            int next = depth.get(key) + 1;
            for (Direction side : Direction.values()) {
                long other = BlockPos.offset(key, side);
                if (!helpers.containsKey(other) || depth.containsKey(other)) continue;
                depth.put(other, next);
                queue.add(other);
            }
        }
        return depth;
    }

    /**
     * Once every block is in, ground dug out that doesn't hold its own block again, filled in with a helper block on the
     * climb out of a pit say, or left open under one filled in over it, is put right where the inventory has the block
     * that was there. From the top down: what covers the wrong spot in its pit is broken first, then the spot itself, and
     * each goes back on the list of ground dug out for {@link #refill} to fill in, bottom first. Where a face is in view,
     * after a walk if need be (at most two for each). True while there is one to break or walk to.
     */
    private boolean redoFills(Minecraft client, LocalPlayer player) {
        if (groundDug.isEmpty() || player.hasInfiniteMaterials()) return false;
        Level level = player.level();
        double reach = player.blockInteractionRange() - 0.25;
        Materials materials = Materials.of(player.getInventory());
        List<BlockPos> ready = new ArrayList<>();
        Map<BlockPos, BlockPos> wrongUnder = new HashMap<>();
        for (Map.Entry<Long, BlockState> entry : groundDug.entrySet()) {
            BlockPos cell = BlockPos.of(entry.getKey());
            BlockState now = level.getBlockState(cell), was = entry.getValue();
            if (now.getBlock() == was.getBlock()) {
                filledOther.remove(entry.getKey());
                dugLeft.remove(cell);
                redoUnder.remove(entry.getKey());
                continue;
            }
            Item item = was.getBlock().asItem();
            if (item == Items.AIR || materials.total(item) == 0 || dug.containsKey(entry.getKey())) continue;
            // What covers it: the highest ground dug out straight over it, up to the first open spot.
            BlockPos top = cell;
            for (BlockPos up = cell.above(); groundDug.containsKey(up.asLong()) && !level.getBlockState(up).canBeReplaced(); up = up.above()) top = up;
            if (top.equals(cell) && now.canBeReplaced()) {
                // Open, and nothing over it: back on the list for refill, which walks to it, twice at most.
                if (redoWalks.merge(entry.getKey(), 1, Integer::sum) > 2) continue;
                dug.put(entry.getKey(), was);
                dugLeft.remove(cell);
                return true;
            }
            BlockState covering = level.getBlockState(top);
            boolean own = covering.getBlock() == groundDug.get(top.asLong()).getBlock();
            if (!own && !helperItems.contains(covering.getBlock().asItem()) || covering.getDestroySpeed(level, top) < 0) continue;
            ready.add(top);
            wrongUnder.put(top, cell);
        }
        if (ready.isEmpty()) {
            // Nothing more can be put right: the spots over those waiting are filled in as they are.
            redoUnder.clear();
            return false;
        }
        Vec3 eye = player.getEyePosition();
        ready.sort(Comparator.comparingDouble(cell -> new AABB(cell).distanceToSqr(eye)));
        for (BlockPos cell : ready) {
            if (missedMining(player, cell) || !inView(player, eye, cell, reach) || !safeToBreak(player, cell)) continue;
            BlockPos wrong = wrongUnder.get(cell);
            if (debug()) LOGGER.info("Putting right the ground at {}: breaking {} at {} first", wrong.toShortString(),
                    level.getBlockState(cell).getBlock().getName().getString(), cell.toShortString());
            if (!cell.equals(wrong)) redoUnder.add(wrong.asLong());
            else redoUnder.remove(wrong.asLong());
            Item own = groundDug.get(cell.asLong()).getBlock().asItem();
            if (level.getBlockState(cell).getBlock().asItem() == own) repairDrops.add(own);
            dug.put(cell.asLong(), groundDug.get(cell.asLong()));
            filledOther.remove(cell.asLong());
            startBreak(cell);
            return true;
        }
        if (!settings().walk) return false;
        Navigator.Node start = Navigator.standingAt(level, player);
        for (BlockPos cell : ready) {
            if (redoWalks.getOrDefault(cell.asLong(), 0) >= 2) continue;
            redoWalks.merge(cell.asLong(), 1, Integer::sum);
            Navigator.Path path = navigator(player, false).limit(6000).find(start, node ->
                    node.x() == cell.getX() && node.z() == cell.getZ() && node.feet() > cell.getY() ? 0 : viewScore(player, node.eye(), cell, reach));
            if (path == null || path.nodes().size() < 2 || dropsIntoTrap(player, path)) continue;
            releaseKeys(client);
            walker.start(path.nodes(), Vec3.atCenterOf(cell));
            if (debug()) LOGGER.info("Putting right the ground: walking {} steps to where {} is in view", path.nodes().size() - 1, cell.toShortString());
            show("Putting right the ground dug out", false);
            return true;
        }
        // Nothing more can be put right: the spots under those waiting are filled in as they are.
        redoUnder.clear();
        return false;
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
        // Standing in a pit or a trench, what is dug stays open till the player is out, but for the cells right behind
        // them along a trench: its bottom has no click from outside once the player is out, and a block of a later layer
        // over it would close it for good. Those are filled in as the trench goes on, from inside it.
        BlockPos feetCell = player.blockPosition();
        boolean inPit = !finish && dug.containsKey(feetCell.asLong()) && !feetCell.equals(pitFill);
        List<BlockPos> open = dug.keySet().stream().map(BlockPos::of).sorted(Comparator.comparingInt(BlockPos::getY)).toList();
        AABB body = player.getBoundingBox().inflate(0.1);
        // The nearest spot out of reach from here, to walk to once nothing in reach is left to fill.
        BlockPos far = null;
        Item farItem = null;
        double farDistance = Double.MAX_VALUE;
        for (BlockPos cell : open) {
            if (inPit && (cell.getY() < feetCell.getY() || cell.getY() > feetCell.getY() + 1
                    || Math.abs(cell.getX() - feetCell.getX()) + Math.abs(cell.getZ() - feetCell.getZ()) != 1)) continue;
            // Bottom first, and never under the player.
            if (dug.containsKey(cell.below().asLong()) || redoUnder.contains(cell.below().asLong())) continue;
            if ((finish || cell.equals(pitFill)) && settings().walk && Navigator.standingAt(level, player).feetCell().equals(cell)) {
                // Got into it to fill it (see below): out again by climbing, a block put under the feet at each jump, which
                // fills it with what was there (see walk's Climb.ready).
                Navigator.Path out = navigator(player, true).find(Navigator.standingAt(level, player),
                        node -> node.feet() >= cell.getY() + 2 - 1e-3 && !dug.containsKey(node.feetCell().asLong()) ? 1 : 0);
                if (out != null && out.nodes().size() > 1) {
                    if (!climbReady(client, player, out)) return true;
                    pitFill = null;
                    releaseKeys(client);
                    walker.start(out.nodes());
                    if (debug()) LOGGER.info("Climbing out of the ground dug out at {}, filling it on the way", cell.toShortString());
                    show("Filling in the ground dug out", false);
                    return true;
                }
            }
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
            if (result.click() != null && !creative && Materials.of(player.getInventory()).loose(item) == 0) {
                Materials carried = Materials.of(player.getInventory());
                if (settings().useShulkers && !waitsForRoom(item, carried) && !noBoxHere(item)) {
                    startRestock(client, player, item, carried);
                    return true;
                }
                // What was there is only in a box that can't be fetched now, shulker boxes off, no room or no spot for one:
                // helper blocks carried fill it as well, else it waits, rather than asking for the box again each time
                // and holding up the build.
                Item helper = helperItem(player);
                if (helper == null || helper == item || carried.loose(helper) == 0) continue;
                PlacementSolver.Click instead = PlacementSolver.find(player, cell, Block.byItem(helper).defaultBlockState(), new ItemStack(helper),
                        player.blockInteractionRange(), helpers::containsKey).click();
                if (instead == null) continue;
                startAim(client, player, new Aim(Act.REFILL, -1, instead, helper, cell, level.getBlockState(cell), 0));
                return true;
            }
            if (result.click() != null) {
                startAim(client, player, new Aim(Act.REFILL, -1, result.click(), item, cell, level.getBlockState(cell), 0));
                return true;
            }
            if (inPit) continue;
            if (!finish) {
                double away = player.position().distanceToSqr(Vec3.atCenterOf(cell));
                if (away < farDistance && refillWalks.getOrDefault(cell.asLong(), 0) < 2) {
                    far = cell;
                    farItem = item;
                    farDistance = away;
                }
                continue;
            }
            if (settings().walk) {
                Item fill = item;
                double reach = player.blockInteractionRange() - 0.2;
                Navigator.Node here = Navigator.standingAt(level, player);
                Navigator.Path path = navigator(player, true).find(here, node -> PlacementSolver.fromSpot(player,
                        node.standing(), cell, state, new ItemStack(fill), reach, helpers::containsKey) != null ? 1 : 0);
                if (path != null && path.nodes().size() < 2) {
                    // A click from the middle of where the player stands, but none from where they really are, a little off
                    // it: somewhere else, rather than give the spot up.
                    path = navigator(player, true).find(here, node -> node.key() != here.key() && PlacementSolver.fromSpot(player,
                            node.standing(), cell, state, new ItemStack(fill), reach, helpers::containsKey) != null ? 1 : 0);
                }
                if (path == null || path.nodes().size() < 2) {
                    // The bottom of a narrow pit has no click from outside it: a player gets in and climbs out, a block put
                    // under their feet at each jump, which fills it with what was there (see walk's Climb.ready).
                    path = pitWalks.merge(cell.asLong(), 1, Integer::sum) > 3 ? null
                            : navigator(player, true).find(here, node -> node.feetCell().equals(cell) ? 1 : 0);
                    if (debug()) LOGGER.info("Ground dug out at {} has no click from outside: {}", cell.toShortString(),
                            path != null && path.nodes().size() > 1 ? "getting in, to climb out" : "no way in either, try " + pitWalks.get(cell.asLong()));
                    if (path != null && path.nodes().size() > 1) pitFill = cell;
                }
                if (path != null && path.nodes().size() > 1) {
                    if (!climbReady(client, player, path)) return true;
                    releaseKeys(client);
                    walker.start(path.nodes());
                    show("Walking over to fill in the ground dug out", false);
                    return true;
                }
                if (debug()) LOGGER.info("Leaving the ground dug out at {} open: no place to stand with a click on it, from {}", cell.toShortString(), here);
            }
            dug.remove(cell.asLong());
            dugLeft.add(cell);
            return true;
        }
        // Out of the pit or trench, what was dug is filled in before building on, as a player does: left open, a later
        // walk could step down into it and a block of the next layer close it over them.
        if (far != null && settings().walk) {
            refillWalks.merge(far.asLong(), 1, Integer::sum);
            BlockPos cell = far;
            Item fill = farItem;
            BlockState state = Block.byItem(fill).defaultBlockState();
            double reach = player.blockInteractionRange() - 0.2;
            Navigator.Node here = Navigator.standingAt(level, player);
            Navigator.Path path = navigator(player, true).find(here, node -> PlacementSolver.fromSpot(player,
                    node.standing(), cell, state, new ItemStack(fill), reach, helpers::containsKey) != null ? 1 : 0);
            if (path != null && path.nodes().size() > 1 && !dropsIntoTrap(player, path)) {
                if (!climbReady(client, player, path)) return true;
                releaseKeys(client);
                walker.start(path.nodes());
                show("Walking over to fill in the ground dug out", false);
                return true;
            }
            if ((path == null || path.nodes().size() < 2) && pitWalks.merge(cell.asLong(), 1, Integer::sum) <= 3) {
                // No click from anywhere outside, as at the bottom of a narrow trench: in, and out again by climbing, which
                // fills it, before anything is built over it.
                Navigator.Path in = navigator(player, true).find(here, node -> node.feetCell().equals(cell) ? 1 : 0);
                if (in != null && in.nodes().size() > 1) {
                    if (debug()) LOGGER.info("Ground dug out at {} has no click from outside: getting in, to climb out", cell.toShortString());
                    pitFill = cell;
                    releaseKeys(client);
                    walker.start(in.nodes());
                    show("Walking over to fill in the ground dug out", false);
                    return true;
                }
            }
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
        int refilledOther = filledOther.size();
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
        orders.stop("the build stopped");
        if (restock != Restock.NONE) abandonRestock(client);
        abandonSwap(client);
        aim = null;
        walker.stop(client);
        releaseKeys(client);
        if (eatingSince >= 0) {
            client.options.keyUse.setDown(false);
            eatingSince = -1;
        }
        if ((breaking != null || pit != null) && client.gameMode != null) client.gameMode.stopDestroyBlock();
        breaking = null;
        pit = null;
        pitAlong = false;
        active = false;
        settings().enabled = false;
        next = null;
        preview = List.of();
        show(text.substring(0, 1).toUpperCase(Locale.ROOT) + text.substring(1), warn);
        message(client, text, warn ? ChatFormatting.YELLOW : ChatFormatting.GREEN);
    }

    /**
     * Eats when hungry, as a player does between blocks: the most filling food carried, use held while crouching, so a
     * chest or hopper in the crosshair stays shut. Out of food and very hungry, it waits for some rather than starve on.
     * True while it eats or waits.
     */
    private boolean eat(Minecraft client, LocalPlayer player) {
        if (eatingSince >= 0) {
            if (!client.options.keyUse.isDown()) {
                // Crouched first, then use: the crouch is in when the click goes out.
                if ((!player.isShiftKeyDown() || !handReady(player)) && tick - eatingSince < 20) return true;
                client.options.keyUse.setDown(true);
                nextAction = System.nanoTime() + 250_000_000L;
                return true;
            }
            // Eaten, or never begun: use is let go either way.
            client.options.keyUse.setDown(false);
            setCrouch(client, false);
            ItemStack hand = player.getMainHandItem();
            if (hand.is(eatingItem) && hand.getCount() >= eatingCount) eatFailedAt = tick;
            eatingSince = -1;
            return false;
        }
        int food = player.getFoodData().getFoodLevel();
        if (food > EAT_AT) return false;
        Map<Item, Integer> needs = plan.needs();
        Inventory inventory = player.getInventory();
        Item best = null;
        int bestNutrition = 0;
        for (int slot = 0; slot < Materials.SLOTS; slot++) {
            ItemStack stack = inventory.getItem(slot);
            FoodProperties properties = stack.get(DataComponents.FOOD);
            if (properties == null || NOT_EATEN.contains(stack.getItem()) || needs.containsKey(stack.getItem())) continue;
            if (properties.nutrition() > bestNutrition) {
                best = stack.getItem();
                bestNutrition = properties.nutrition();
            }
        }
        if (best == null || tick - eatFailedAt < 600) {
            if (food > HUNGRY) return false;
            show("Hungry: put some food in your inventory to go on", true);
            nextAction = System.nanoTime() + 2_000_000_000L;
            return true;
        }
        if (!holdInHand(client, player, best)) return true;
        show("Eating " + name(best), false);
        setCrouch(client, true);
        eatingSince = tick;
        eatingItem = best;
        eatingCount = player.getMainHandItem().getCount();
        nextAction = System.nanoTime() + 100_000_000L;
        return true;
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
                // A block full of water takes a bucket of it besides; and kelp or seagrass may, poured into its spot first
                // where no water reaches it yet (da-45): at most one each, as the running count (BuildPlan.needs) has it.
                if (kind == BlockRules.Kind.PLACE && (BlockRules.soaked(target) || BlockRules.wet(target))) {
                    counts.merge(Items.WATER_BUCKET, 1, Integer::sum);
                }
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
    /** A hoe or shovel carried that makes {@code target} out of dirt, or null; any, in creative. */
    private static Item carriedTool(LocalPlayer player, BlockState target) {
        TagKey<Item> tools = BlockRules.tool(target);
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < Materials.SLOTS; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.is(tools)) return stack.getItem();
        }
        if (!player.hasInfiniteMaterials()) return null;
        return target.is(Blocks.FARMLAND) ? Items.IRON_HOE : Items.IRON_SHOVEL;
    }

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
        // A moment to find the item and point at it, the number key, and a moment more to shut the inventory again.
        swapAt = 5 + ThreadLocalRandom.current().nextInt(6);
        swapClose = swapAt + 3 + ThreadLocalRandom.current().nextInt(4);
        if (debug()) LOGGER.info("Hotbar swap: {} from slot {} into slot {}, for {}", player.getInventory().getItem(from).getItem(), from, to,
                restock != Restock.NONE ? "a restock" : breaking != null || pit != null ? "a tool" : "the next block");
        // Right after a swap, a block broken or a box picked up, the inventory opens no sooner than the hands are free.
        swapOpening = System.nanoTime() - handsAt < 0;
        if (!swapOpening) client.gui.setScreen(new InventoryScreen(player));
    }

    private void swapTick(Minecraft client, LocalPlayer player) {
        if (swapOpening) {
            if (client.gui.screen() != null) {
                // The player, or the server, opened something meanwhile: the next decision looks again.
                swapOpening = false;
                swapFrom = -1;
                return;
            }
            if (System.nanoTime() - handsAt < 0) return;
            swapOpening = false;
            client.gui.setScreen(new InventoryScreen(player));
            return;
        }
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
            paceAfterHands(quick());
        }
    }

    private void abandonSwap(Minecraft client) {
        if (swapFrom < 0) return;
        swapFrom = -1;
        swapOpening = false;
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
     * Takes the best tool for a block in hand: one it drops its item for, glass or ice one with silk touch, the fastest
     * of those, else an empty hand.
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
        boolean itself = drops && !BlockRules.dropsItself(state) && BlockRules.silkTouch(stack);
        return (drops ? 1000 : 0) + (itself ? 500 : 0) + (stack.isEmpty() ? 1 : stack.getDestroySpeed(state));
    }

    // Shulker boxes ------------------------------------------------------------------------------------------------

    /**
     * How far down the feet may drop off a build on the way to room for a shulker box: past the safe fall, by the damage
     * the player can take and still keep most of their health, two hearts at the most; none in creative, where falls
     * do no harm and the usual drops do.
     */
    private static double farDrop(LocalPlayer player) {
        if (player.hasInfiniteMaterials()) return 0;
        double damage = Math.floor(Math.min(4, player.getHealth() - 12));
        return damage < 1 ? 0 : player.getAttributeValue(Attributes.SAFE_FALL_DISTANCE) + damage;
    }

    /**
     * The carried box to open for {@code item}: one this restock takes everything out of, what the next layers still want
     * of each stack in it and in the room there is, and as much of the item as the box with the most would give, the one
     * with fewest stacks; else the one with the most of the item.
     * The most alone took a few stacks out of box after box and left them all part full, each opened again and again for
     * what is left in it; a box emptied is never opened again.
     */
    private int boxFor(LocalPlayer player, Item item) {
        Inventory inventory = player.getInventory();
        int most = Materials.shulkerWith(inventory, item);
        if (most < 0 || plan == null) return most;
        Materials carried = Materials.of(inventory);
        // The box's own slot comes free once it is put down; two stay free, and four before other items (nextToTake).
        int free = carried.freeSlots() + 1;
        if (free <= 2) return most;
        Map<Item, Integer> wanted = soonNeeds(player.level());
        int missing = Math.max(1, wanted.getOrDefault(item, 0) - carried.loose(item));
        int enough = Math.min(missing, Materials.countInside(inventory.getItem(most), item));
        int best = -1, fewest = Integer.MAX_VALUE;
        for (int slot = 0; slot < Materials.SLOTS; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!Materials.isShulkerBox(stack) || Materials.countInside(stack, item) == 0) continue;
            Map<Item, Integer> inside = new HashMap<>();
            int stacks = 0;
            for (ItemStack held : Materials.contents(stack)) {
                if (held.isEmpty()) continue;
                inside.merge(held.getItem(), held.getCount(), Integer::sum);
                stacks++;
            }
            boolean others = inside.size() > 1;
            if (stacks > free - (others ? 4 : 2) || stacks >= fewest || inside.get(item) < enough) continue;
            boolean all = true;
            for (Map.Entry<Item, Integer> held : inside.entrySet()) {
                if (held.getValue() > wanted.getOrDefault(held.getKey(), 0) - carried.loose(held.getKey())) all = false;
            }
            if (!all) continue;
            best = slot;
            fewest = stacks;
        }
        return best >= 0 ? best : most;
    }

    private void startRestock(Minecraft client, LocalPlayer player, Item item, Materials materials) {
        Inventory inventory = player.getInventory();
        if (!settings().useShulkers) {
            show("Out of " + name(item) + " outside shulker boxes; turn on Use shulker boxes or take some out", true);
            nextAction = System.nanoTime() + 1_000_000_000L;
            return;
        }
        int slot = boxFor(player, item);
        if (slot < 0) return;
        ItemStack shulker = inventory.getItem(slot);
        BlockState boxState = ((BlockItem) shulker.getItem()).getBlock().defaultBlockState().setValue(ShulkerBoxBlock.FACING, Direction.UP);
        BlockPos spot = null;
        PlacementSolver.Click placeClick = null;
        // Beside where the player stands, worked out as the walk there did: in a hopper's bowl the feet are inside the
        // hopper's own block, a block lower than the spot stood on.
        Navigator.Node here = Navigator.standingAt(player.level(), player);
        BlockPos feet = here.floor().above();
        List<BlockPos> candidates = boxSpots(player, feet, player.position());
        if (debug()) LOGGER.info("Shulker box for {}: {} spots beside {}", name(item), candidates.size(), here);
        for (BlockPos candidate : candidates) {
            PlacementSolver.Result result = PlacementSolver.find(player, candidate, boxState, shulker, player.blockInteractionRange(),
                    helpers::containsKey);
            if (debug()) LOGGER.info("  box spot {}: {} {}, seen {}, walks into {}", candidate.toShortString(), result.click() != null, result.problem(),
                    boxSeenFrom(player, player.getEyePosition(), candidate), walksInto(player, here, candidate));
            // A click in the other stance does too: placing the box crouches or stands up for it first.
            if (result.click() != null && (result.problem() == null || result.problem() == PlacementSolver.Problem.STANCE)
                    && boxSeenFrom(player, player.getEyePosition(), candidate) && walksInto(player, here, candidate)) {
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
                Navigator.Goal roomy = node -> {
                    if (node.key() == start.key()) return 0;
                    List<BlockPos> spots = boxSpots(player, node.floor().above(), node.standing());
                    for (BlockPos at : spots.subList(0, Math.min(3, spots.size()))) {
                        if (PlacementSolver.fromSpot(player, node.standing(), at, boxState, shulker, reach, helpers::containsKey) != null
                                && boxSeenFrom(player, node.eye(), at) && walksInto(player, node, at)) return 1;
                    }
                    return 0;
                };
                Navigator.Path path = navigator(player, false).find(start, roomy);
                // Shut in by the build on foot, as on top of a pillar among finished blocks: climbing, or down and out.
                if (path == null && helperItem(player) != null) path = navigator(player, true).find(start, roomy);
                // Up on a build there is seldom room for a box: with no way down that does no harm, a jump of a few
                // blocks onto the ground round it, as a player gets down from a build with no stairs.
                double down = farDrop(player);
                if (path == null && down > 0) {
                    path = navigator(player, false).dropsOff(down, plan.bounds).find(start, roomy);
                    if (path == null && helperItem(player) != null) path = navigator(player, true).dropsOff(down, plan.bounds).find(start, roomy);
                    if (path != null && debug()) LOGGER.info("No room for a shulker box up here: jumping down off the build to {} {} {}",
                            path.end().x(), path.end().feet(), path.end().z());
                }
                // Shut in where only a climb through the build's own cells gets out, as a climb out does (see climbOut): down
                // in a kelp channel under the layer over it, v4-fresh3 stood 16 minutes at 4 70 28 with 70 dirt carried, no
                // box spot beside it and no other way out. Those helper blocks come away once the player is out.
                boolean escape = false;
                if (path == null && helperItem(player) != null) {
                    boolean was = escaping;
                    escaping = true;
                    try {
                        path = navigator(player, true).find(start, roomy);
                        if (path == null && down > 0) path = navigator(player, true).dropsOff(down, plan.bounds).find(start, roomy);
                    } finally {
                        escaping = was;
                    }
                    escape = path != null;
                    if (escape && debug()) LOGGER.info("No room for a shulker box down here: climbing out over cells the build fills later to {} {} {}",
                            path.end().x(), path.end().feet(), path.end().z());
                }
                if (path == null && debug()) LOGGER.info("No walk to room for a shulker box from {}: {} helper blocks carried, a drop of {} at most",
                        start, helpersLoose(player), down);
                if (path != null && dropsIntoTrap(player, path)) path = null;
                if (path != null && path.nodes().size() > 1 && restockWalks < 3) {
                    if (!climbReady(client, player, path)) return;
                    releaseKeys(client);
                    walker.start(path.nodes());
                    if (escape) escapeWalk = true;
                    restockAfterWalk = item;
                    restockWalks++;
                    show("Walking to open ground to empty a shulker box", false);
                    return;
                }
            }
            restockWalks = 0;
            // The build goes on meanwhile with what the player carries: other blocks, a walk elsewhere, from where a box
            // may well go in. Asked again for this item a while later.
            noBoxSpot.put(item, tick);
            show("No room beside you to open a shulker box: stand on open, flat ground", true);
            nextAction = System.nanoTime() + 1_000_000_000L;
            return;
        }
        boxesBefore = shulkerCount(inventory);
        if (!holdSlot(client, player, slot)) {
            // Moving the box into the hotbar first. The restock goes on from here once it is: else, with the blocks it is for
            // out of reach from this spot, often one walked to for it, a walk back to them would come first, and another here.
            if (restockSwaps < 2) {
                restockSwaps++;
                restockAfterWalk = item;
            }
            show("Taking " + name(item) + " out of a shulker box", false);
            return;
        }
        if (Materials.countInside(player.getMainHandItem(), item) == 0) {
            show("Can't get the shulker box into your hand", true);
            nextAction = System.nanoTime() + 1_000_000_000L;
            return;
        }
        restock = Restock.PLACE;
        boxViewWalks = boxRounds = 0;
        restockWalks = 0;
        restockSwaps = 0;
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
        List<BlockPos> outside = new ArrayList<>(), inside = new ArrayList<>(), cornered = new ArrayList<>();
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
                // Not beside a drop either: the box, once broken, can bounce into it, down out of reach. One with a drop
                // only at a corner is kept for last, for where nothing better is: on top of a build, say.
                if (besideDrop(level, at, false)) continue;
                boolean corner = besideDrop(level, at, true);
                // A corner that drops more than a block is no place either: v4-clean3 lost a box so, broken on the glass at
                // 20 72 23 with the corner at 21 72 22 open down to the ground seven blocks under, the only spot up there.
                if (corner && deepCorner(level, at)) continue;
                // Nor where the dropped box is carried off before it is picked up: into a hopper, or down a stream.
                if (carriedOff(level, at)) continue;
                // Never on a spot the schematic fills.
                if (plan.indexOf(at) >= 0) continue;
                (corner ? cornered : keepClear.contains(Vec3.atCenterOf(at)) ? inside : outside).add(at);
            }
        }
        Comparator<BlockPos> nearest = Comparator.comparingDouble(p -> Vec3.atBottomCenterOf(p).distanceToSqr(standing));
        outside.sort(nearest);
        inside.sort(nearest);
        cornered.sort(nearest);
        outside.addAll(inside);
        outside.addAll(cornered);
        return outside;
    }

    /**
     * Whether a player standing at {@code from} can walk into {@code spot}, a few steps round at most: a box placed
     * through a gap, in a place the player can't step into, drops where it can't be picked up.
     */
    private boolean walksInto(LocalPlayer player, Navigator.Node from, BlockPos spot) {
        long floor = spot.below().asLong();
        return navigator(player, false).limit(BOX_WALK_NODES).find(from, node -> node.key() == floor ? 1 : 0) != null;
    }

    /** The most places the search for a way into a box spot looks at. */
    private static final int BOX_WALK_NODES = 300;

    /**
     * Whether a box placed at {@code spot} would show a face to {@code eye} within reach, to open and break it by: a
     * spot clicked through a gap can leave the box itself out of sight behind what stands around it.
     */
    private static boolean boxSeenFrom(LocalPlayer player, Vec3 eye, BlockPos spot) {
        Level level = player.level();
        double reach = player.blockInteractionRange() - 0.2;
        for (Direction face : Direction.values()) {
            Vec3 normal = Vec3.atLowerCornerOf(face.getUnitVec3i());
            Vec3 middle = Vec3.atCenterOf(spot).add(normal.scale(0.5));
            if (middle.subtract(eye).dot(normal) >= 0) continue;
            for (double[] off : new double[][] {{0, 0}, {0.3, 0.3}, {-0.3, 0.3}, {0.3, -0.3}, {-0.3, -0.3}}) {
                Vec3 point = middle.add(along(face, off[0], off[1]));
                if (point.distanceTo(eye) > reach) continue;
                BlockHitResult hit = level.clip(new ClipContext(eye, point, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
                if (hit.getType() == HitResult.Type.MISS || hit.getLocation().distanceTo(eye) >= point.distanceTo(eye) - 1e-3) return true;
            }
        }
        return false;
    }

    /** An offset of {@code a}, {@code b} across a face, along its two other axes. */
    private static Vec3 along(Direction face, double a, double b) {
        return switch (face.getAxis()) {
            case X -> new Vec3(0, a, b);
            case Y -> new Vec3(a, 0, b);
            case Z -> new Vec3(a, b, 0);
        };
    }

    /**
     * Whether a box dropped at {@code at} could be carried off before it is picked up: a hopper under the spot or one
     * beside it draws in what lies in the block above it, and water beside it washes an item away. A dropped item
     * comes out with a little push to the side, enough to reach the next block over.
     */
    private static boolean carriedOff(Level level, BlockPos at) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                BlockPos beside = at.offset(dx, 0, dz);
                if (level.getBlockState(beside.below()).getBlock() instanceof HopperBlock) return true;
                if (!level.getFluidState(beside).isEmpty()) return true;
            }
        }
        return false;
    }

    /**
     * Whether a spot beside {@code at}, corners too, is open with nothing under it to stop a dropped item falling: a box
     * broken there bounces off as far as a corner, as one did down into a water channel two blocks below.
     */
    private static boolean besideDrop(Level level, BlockPos at, boolean corners) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0 || (dx != 0 && dz != 0) != corners) continue;
                BlockPos next = at.offset(dx, 0, dz);
                if (level.getBlockState(next).getCollisionShape(level, next).isEmpty() && !catches(level, next.below())) return true;
            }
        }
        return false;
    }

    /** Whether a corner beside {@code at} is open with nothing to stop a dropped item within a block under it. */
    private static boolean deepCorner(Level level, BlockPos at) {
        for (int dx = -1; dx <= 1; dx += 2) {
            for (int dz = -1; dz <= 1; dz += 2) {
                BlockPos next = at.offset(dx, 0, dz), under = next.below();
                if (!level.getBlockState(next).getCollisionShape(level, next).isEmpty() || catches(level, under)) continue;
                if (!level.getBlockState(under).getCollisionShape(level, under).isEmpty() || !catches(level, under.below())) return true;
            }
        }
        return false;
    }

    /**
     * Whether the block at {@code pos} stops a dropped item on its top: most of the cell covered at the top of its shape.
     * Iron bars, a pane or a fence has a shape, but an item slips past it into the cell, as a broken box did down into the
     * bars of a farm's channel, a block under the player and out of reach.
     */
    private static boolean catches(Level level, BlockPos pos) {
        VoxelShape shape = level.getBlockState(pos).getCollisionShape(level, pos);
        if (shape.isEmpty()) return false;
        double top = shape.max(Direction.Axis.Y), area = 0;
        for (AABB box : shape.toAabbs()) {
            if (box.maxY >= top - 1e-3) area += (Math.min(box.maxX, 1) - Math.max(box.minX, 0)) * (Math.min(box.maxZ, 1) - Math.max(box.minZ, 0));
        }
        return area >= 0.5;
    }

    /** How long the look onto a placed shulker box may take before it is tried from elsewhere. */
    private static final int BOX_OPEN_TICKS = 80;

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
                if (!looked(player, boxClick.yaw(), boxClick.pitch(), onBlock(player, boxClick.hit().getBlockPos(), boxClick.hit().getDirection()))) {
                    if (restockTicks > 60) restockFailed(client, "it couldn't turn to where the shulker box goes");
                    return;
                }
                BlockHitResult hit = crosshair(player);
                if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(boxClick.hit().getBlockPos())
                        || !Materials.isShulkerBox(player.getMainHandItem())) {
                    restockFailed(client, "the spot for the shulker box went out of view");
                    return;
                }
                // On the right block but another face of it, an edge looked at: clicked, the box would go into the cell
                // beside that face, not the one it is fetched from, and stay there with what is in it. The look settles first.
                if (hit.getDirection() != boxClick.hit().getDirection()) {
                    if (restockTicks > 60) restockFailed(client, "the crosshair stayed on another side of where the shulker box goes");
                    return;
                }
                if (!handReady(player)) return;
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
                if (walker.active()) {
                    walk(client, player);
                    return;
                }
                if (player.isShiftKeyDown()) {
                    setCrouch(client, false);
                    return;
                }
                PlacementSolver.Click open = PlacementSolver.use(player, player.getEyePosition(), box, player.blockInteractionRange(), at);
                if (open == null) {
                    if (stepToBox(client, player)) {
                        restockTicks = 0;
                        return;
                    }
                    restockFailed(client, "the shulker box is out of view");
                    return;
                }
                // A look that never settles on the box, or a crosshair that stays on a block in front of it: from
                // somewhere else, or the box comes back up, rather than looking at it for ever.
                if (restockTicks > BOX_OPEN_TICKS) {
                    if (stepToBox(client, player)) {
                        restockTicks = 0;
                        return;
                    }
                    restockFailed(client, "it couldn't look at the shulker box");
                    return;
                }
                if (restockTicks < 4 || !looked(player, open.yaw(), open.pitch(), onBlock(player, box, null))) return;
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
                    // Its items arrive just after it opens; the first click comes once a player has looked them over.
                    if (restockTicks >= 3) {
                        restock = Restock.TAKE;
                        restockTicks = 0;
                        nextTake = 4 + ThreadLocalRandom.current().nextInt(6);
                        swapClicks.clear();
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
                Integer swap = swapClicks.poll();
                if (swap != null) {
                    client.gameMode.handleContainerInput(menu.containerId, swap, 0, ContainerInput.PICKUP, player);
                    nextTake = restockTicks + takePause();
                    return;
                }
                int slot = nextToDeposit(menu, player);
                if (slot < 0) slot = nextToTake(menu, player);
                if (slot < 0 && swapForRoom(menu, player)) return;
                if (slot < 0) {
                    if (!player.hasInfiniteMaterials() && Materials.of(player.getInventory()).loose(restockItem) == 0) {
                        StringBuilder carried = new StringBuilder();
                        for (int carriedSlot = 0; carriedSlot < Materials.SLOTS; carriedSlot++) {
                            ItemStack stack = player.getInventory().getItem(carriedSlot);
                            if (!stack.isEmpty()) carried.append(' ').append(stack.getCount()).append(' ').append(name(stack.getItem())).append(',');
                        }
                        int boxFree = 0;
                        for (int boxSlot = 0; boxSlot < 27; boxSlot++) if (menu.getSlot(boxSlot).getItem().isEmpty()) boxFree++;
                        LOGGER.info("No room for {}: {} slots free in the box; carried:{}", name(restockItem), boxFree, carried);
                    }
                    LOGGER.info("Took what the next layers need out of the shulker box");
                    if (debug()) logBoxes(menu, player);
                    player.closeContainer();
                    startBreaking();
                    return;
                }
                client.gameMode.handleContainerInput(menu.containerId, slot, 0, ContainerInput.QUICK_MOVE, player);
                nextTake = restockTicks + takePause();
            }
            case BREAK -> {
                if (!placed) {
                    LOGGER.info("Shulker box broken after {} ticks", restockTicks);
                    pours.put(box.asLong(), tick);
                    client.gameMode.stopDestroyBlock();
                    restock = Restock.PICKUP;
                    restockTicks = 0;
                    boxWalks = 0;
                    boxAsked = false;
                    return;
                }
                if (client.gui.screen() != null) return;
                if (walker.active()) {
                    walk(client, player);
                    if (!walker.active()) restockTicks = 0;
                    return;
                }
                if (player.isShiftKeyDown() && !onNarrowEdge(player)) setCrouch(client, false);
                if (restockTicks == 1 && !holdTool(client, player, at)) {
                    restockTicks = 0;
                    return;
                }
                if (!mine(client, player, box) && restockTicks > 40) {
                    client.gameMode.stopDestroyBlock();
                    if (stepToBox(client, player)) return;
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
                    walker.stop(client);
                    LOGGER.info("Shulker box picked up again");
                    restock = Restock.NONE;
                    if (!player.hasInfiniteMaterials() && Materials.of(player.getInventory()).loose(restockItem) == 0) {
                        // Opened and closed again without it: the inventory has no room, and the next try would go the
                        // same way. The blocks carried go in first, as a player builds with what is in hand, and that
                        // makes room; only with nothing going in between does it come to a stop.
                        int done = plan.count(BuildPlan.Status.DONE);
                        noRoomFor.put(restockItem, new long[] {done, tick});
                        if (done != noRoomDone) restockFailures = 0;
                        noRoomDone = done;
                        if (++restockFailures >= RESTOCK_FAILURES) {
                            finish(client, "stopped: no room in your inventory to take " + name(restockItem)
                                    + " out of a shulker box; make room and start again.", true);
                            return;
                        }
                        show("No room to take " + name(restockItem) + " out of a shulker box", true);
                        nextAction = System.nanoTime() + 2_000_000_000L;
                        return;
                    }
                    restockFailures = 0;
                    paceAfterHands(delay());
                    show("Took " + name(restockItem) + " out of a shulker box", false);
                    // Where a box just went in, the next one does too: blocks of the layer in other boxes, none of them
                    // carried, come out now. Up on the build there may be no room for a box at all, and every walk made
                    // there for them comes to nothing.
                    Item ahead = restockAhead(player, Materials.of(player.getInventory()));
                    if (ahead != null) {
                        restockAfterWalk = ahead;
                        if (debug()) LOGGER.info("Taking {} out of a shulker box too, while there is room for one here", name(ahead));
                    }
                    return;
                }
                // A box that rolled, slid or fell out of reach is walked to for as long as it can be seen: along a way
                // there, which goes through the middle of a gap a straight walk catches its shoulder on, and straight at
                // it only once in its block.
                ItemEntity dropped = restockTicks >= 10 ? droppedBox(level, player) : null;
                if (restockTicks % 400 == 399) boxWalks = 0;
                if (dropped != null || walker.active()) {
                    if (walker.active()) {
                        walk(client, player);
                    } else if (dropped != null && boxWalks < 3 && !dropped.blockPosition().equals(player.blockPosition())) {
                        boxWalks++;
                        // Picked up within a block of the body sideways and half a block up or down, as vanilla does:
                        // from beside the block it lies in, when that one has no room to stand.
                        AABB item = dropped.getBoundingBox();
                        Navigator.Path path = navigator(player, false).find(Navigator.standingAt(level, player), node -> new AABB(node.x() + 0.2,
                                node.feet(), node.z() + 0.2, node.x() + 0.8, node.feet() + 1.8, node.z() + 0.8).inflate(1, 0.5, 1).intersects(item) ? 1 : 0);
                        stopWalking(client);
                        // Not into a place with no way back out, for a box either; unless helper blocks climb out of it, over
                        // cells the build fills later, as climbUpOut then does: the box, with what it holds, is worth that.
                        boolean trapped = path != null && path.nodes().size() > 1 && dropsIntoTrap(player, path);
                        if (trapped && helperItem(player) != null && escapeFrom(player, level, path.end(), null) != null) {
                            if (debug()) LOGGER.info("Down for the shulker box after all: helper blocks climb back out from {} {} {}",
                                    path.end().x(), path.end().feet(), path.end().z());
                            trapped = false;
                        }
                        if (path != null && path.nodes().size() > 1 && !trapped) walker.start(path.nodes());
                    } else {
                        walkTo(client, player, dropped);
                    }
                } else {
                    walker.stop(client);
                    stopWalking(client);
                }
                if (debug() && restockTicks % 40 == 39) {
                    LOGGER.info("Picking up the shulker box: {} free slots, box {}, player at {}", Materials.of(player.getInventory()).freeSlots(),
                            dropped == null ? "not in sight" : dropped.position() + " " + Math.round(Math.sqrt(dropped.distanceToSqr(player)) * 10) / 10.0 + " m away",
                            player.position());
                }
                // Help is asked for once nothing of the box is in sight, or once a minute has gone by without it.
                boolean lost = dropped == null && restockTicks >= 250 || restockTicks >= 1200;
                if (lost && !boxAsked) {
                    boxAsked = true;
                    message(client, "pick up your shulker box near " + box.getX() + " " + box.getY() + " " + box.getZ()
                            + "; the build goes on once it's back.", ChatFormatting.YELLOW);
                }
                if (lost) show("Waiting for you to pick up the shulker box near " + box.getX() + " " + box.getY() + " " + box.getZ(), true);
            }
            case NONE -> { }
        }
    }

    /** Ticks to the next click in a box: a quick hand mostly, now and then a moment to look for what comes next. */
    private static int takePause() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        return 2 + random.nextInt(4) + (random.nextInt(5) == 0 ? 3 + random.nextInt(6) : 0);
    }

    /**
     * With the box placed but out of view from here, as when a rim or a wall stands in the line, a few steps to where
     * it can be seen and reached, as a player steps round to it. False when there is no such place, or after a few tries.
     */
    private boolean stepToBox(Minecraft client, LocalPlayer player) {
        if (!settings().walk || boxViewWalks >= 3) return false;
        boxViewWalks++;
        Level level = player.level();
        BlockState state = level.getBlockState(box);
        double reach = player.blockInteractionRange() - 0.2;
        Navigator.Path path = navigator(player, false).limit(3000).find(Navigator.standingAt(level, player),
                node -> PlacementSolver.use(player, node.eye(), box, reach, state) != null ? 1 : 0);
        if (debug()) {
            LOGGER.info("Shulker box at {} out of view from eye {}: {}", box.toShortString(), player.getEyePosition(),
                    path == null ? "no place to see it from" : "walking " + (path.nodes().size() - 1) + " steps");
        }
        if (path == null || path.nodes().size() < 2 || dropsIntoTrap(player, path)) return false;
        releaseKeys(client);
        walker.start(path.nodes(), Vec3.atCenterOf(box));
        return true;
    }

    /**
     * Whether a fetch of {@code item} waits, there having been no room for it a moment ago: till a few more blocks are
     * in or slots are free, and no longer than half a minute, after which it is tried again.
     */
    /** Whether no spot for a shulker box with {@code item} was found a short while ago: blocks of it wait meanwhile. */
    private boolean noBoxHere(Item item) {
        Long at = noBoxSpot.get(item);
        return at != null && tick - at < NO_BOX_WAIT;
    }

    private boolean waitsForRoom(Item item, Materials materials) {
        long[] since = noRoomFor.get(item);
        if (since == null) return false;
        if (materials.freeSlots() >= 3 || plan.count(BuildPlan.Status.DONE) - since[0] >= 8 || tick - since[1] > 600) {
            noRoomFor.remove(item);
            return false;
        }
        return true;
    }

    private void startBreaking() {
        LOGGER.info("Breaking the shulker box at {}", box.toShortString());
        restock = Restock.BREAK;
        restockTicks = 0;
        restingTicks = 0;
    }

    /** The dropped shulker box item near where the box stood, or null. */
    private ItemEntity droppedBox(ClientLevel level, LocalPlayer player) {
        // Within a few blocks of where it stood: a box can bounce, slide on ice nearby, or fall a few blocks, or as far as
        // the ground: one that fell seven off the top of v4 was not in sight from up there, and was waited for for good.
        List<ItemEntity> items = level.getEntitiesOfClass(ItemEntity.class, new AABB(box).inflate(8, 16, 8),
                item -> Materials.isShulkerBox(item.getItem()) && item.getY() - player.getY() < 3 && player.getY() - item.getY() < 16);
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
        head.turnYaw(player, want);
        client.options.keyUp.setDown(Math.abs(Mth.wrapDegrees(want - player.getYRot())) < 45);
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
        Map<Item, Integer> needs = needsUpTo(level, layerY + 2);
        // Helper blocks come out a couple of stacks at a time, which the build itself never lists.
        if (restockItem != null) needs.merge(restockItem, helperItems.contains(restockItem)
                ? HELPER_STACKS * restockItem.getDefaultMaxStackSize() : 1, Math::max);
        return needs;
    }

    /**
     * The item to take out of another shulker box straight after one: the layer still takes it, none is carried, a box
     * holds some and there is a slot for it. The one the layer takes most of; each once a layer, counted from being
     * picked here, so a box that gives none can't be opened over and over. Null when there is none.
     */
    private Item restockAhead(LocalPlayer player, Materials carried) {
        // Room to spare only: two slots stay free in any restock (nextToTake), and a box ahead that fills the last ones
        // leaves none for what the next box is opened for, the layer's own blocks. v4-x stopped so, "no room in your
        // inventory to take Hopper", after boxes ahead for glass (three stacks), water buckets and gates.
        if (player.hasInfiniteMaterials() || !settings().useShulkers || carried.freeSlots() < AHEAD_FREE) return null;
        // Before the first decision has picked a layer, as at the start of a build: the lowest with a block still to go in.
        int y = aheadY();
        if (y == Integer.MIN_VALUE) return null;
        Item best = null;
        int most = 0;
        for (Map.Entry<Item, Integer> need : needsUpTo(player.level(), y).entrySet()) {
            Item item = need.getKey();
            if (need.getValue() <= most || helperItems.contains(item) || carried.loose(item) > 0 || carried.total(item) == 0) continue;
            if (Integer.valueOf(y).equals(aheadLayer.get(item)) || noRoomFor.containsKey(item)) continue;
            if (Materials.shulkerWith(player.getInventory(), item) < 0) continue;
            best = item;
            most = need.getValue();
        }
        if (best != null) aheadLayer.put(best, y);
        return best;
    }

    /**
     * For the debug log: a block the walk here was made for, passed over on arriving without a "Can't place" line, and
     * why, once a walk. Such a walk places nothing, and planWalk counted it as a place where the block goes in.
     */
    private void passedOver(int i, String why) {
        if (debug() && walkEnd != null && walkExpects.remove(i)) {
            LOGGER.info("Walked for {} at {}, passed over on arriving: {}", plan.target(i), plan.pos(i).toShortString(), why);
        }
    }

    /**
     * Starts a restock here for the first block out of reach that could go in now but whose item is only in boxes, when a
     * box goes in beside the player: once per item every {@link #FETCH_BEFORE_WALK_TICKS}, so a box that gives none
     * isn't opened over and over. True when one is begun.
     */
    private boolean fetchBeforeWalk(Minecraft client, LocalPlayer player, List<Integer> candidates, Materials materials, boolean lift) {
        long started = System.nanoTime();
        int tried = 0;
        for (int i : candidates) {
            // Water too: v4-full3 walked down among the layer's last sources at 17,66,29..31 with no bucket carried, found
            // no room for a box there, climbed out and walked back down, every 25 s.
            BlockRules.Kind kind = plan.kind(i);
            if (plan.status(i) != BuildPlan.Status.PENDING || kind != BlockRules.Kind.PLACE && kind != BlockRules.Kind.FLUID) continue;
            if (kind == BlockRules.Kind.FLUID && plan.wetWaits(i)) continue;
            if (++tried > 24 || System.nanoTime() - started > 5_000_000L) break;
            Item item = plan.item(i);
            if (item == null || materials.loose(item) > 0 || materials.total(item) == 0) continue;
            if (noBoxHere(item) || waitsForRoom(item, materials)) continue;
            if (tick - fetchedBeforeWalk.getOrDefault(item, -FETCH_BEFORE_WALK_TICKS) < FETCH_BEFORE_WALK_TICKS) continue;
            if (support(player, i, started) != 1 || !lift && seals(player, i, started) == 1) continue;
            // With no room for a box here, a walk to room first (startRestock's), but only when nothing carried goes in on
            // the way: else those blocks go in first, and the fetch comes where a box goes in.
            if (boxSpots(player, Navigator.standingAt(player.level(), player).floor().above(), player.position()).isEmpty()
                    && carriesAny(player, candidates, materials, started)) return false;
            fetchedBeforeWalk.put(item, tick);
            if (debug()) LOGGER.info("Taking {} out of a shulker box before the walk for {} at {}", name(item), plan.target(i),
                    plan.pos(i).toShortString());
            startRestock(client, player, item, materials);
            return restock != Restock.NONE || swapFrom >= 0 || walker.active();
        }
        return false;
    }

    /** Whether a block of {@code candidates} that can go in now is one the player carries loose; yes when out of time. */
    private boolean carriesAny(LocalPlayer player, List<Integer> candidates, Materials materials, long started) {
        for (int i : candidates) {
            if (plan.status(i) != BuildPlan.Status.PENDING) continue;
            Item item = plan.item(i);
            if (item != null && materials.loose(item) > 0 && support(player, i, started) != 2) return true;
        }
        return false;
    }

    private final Map<Item, Long> fetchedBeforeWalk = new HashMap<>();
    private static final long FETCH_BEFORE_WALK_TICKS = 600;

    /** The layer {@link #restockAhead} fetches for: the one being built, or before one is picked the lowest with a block left. */
    private int aheadY() {
        if (layerY != Integer.MIN_VALUE) return layerY;
        for (Map.Entry<Integer, int[]> layer : plan.layers().entrySet()) {
            for (int i : layer.getValue()) {
                if (plan.status(i) == BuildPlan.Status.PENDING || plan.status(i) == BuildPlan.Status.UNLOADED) return layer.getKey();
            }
        }
        return Integer.MIN_VALUE;
    }

    /** Items swapped into the inventory by {@link #swapForRoom}, with the blocks done then. */
    private final Map<Item, Integer> swappedIn = new HashMap<>();
    /** How many blocks go in before a stack swapped in may be swapped back out for another. */
    private static final int SWAP_BACK_AFTER = 3;

    /** The free inventory slots a box opened ahead needs: two kept free, and two to take into. */
    private static final int AHEAD_FREE = 4;

    /** The layer each item was last taken out of a box ahead for, by {@link #restockAhead}. */
    private final Map<Item, Integer> aheadLayer = new HashMap<>();
    /** The tick {@link #restockAhead} was last asked from where the player stands. */
    private long aheadChecked = -100;

    /** What the layers up to height {@code top} still take, by item: the layer being built and those below it still waiting, at the least. */
    private Map<Item, Integer> needsUpTo(Level level, int top) {
        Map<Item, Integer> needs = new HashMap<>();
        // Up on a layer higher over the ground round the build than a jump down from it reaches, a box may find no room
        // there, and no way down to room: the top of the slab farm, a glass rim round water channels, nine blocks up. The
        // water of such a layer, the one being built or the next, comes along with its solid blocks, a bucket a slot: as
        // many as the inventory has room for past the slots kept free and those the solid blocks still to come out take.
        // Worked out here alone, so what a box gives and what goes back into one agree.
        // Measured by the furthest a jump down can go at full health, not now: a layer is too high or it isn't, and what
        // a box gives and takes back mustn't change as the player is hurt and heals.
        LocalPlayer player = Minecraft.getInstance().player;
        double down = player == null ? 0 : player.getAttributeValue(Attributes.SAFE_FALL_DISTANCE) + 4;
        boolean anyHigh = player != null && !player.hasInfiniteMaterials() && layerY + 2 - plan.bounds.minY > down;
        int buckets = anyHigh ? bucketRoom(player, level, top) : 0;
        for (Map.Entry<Integer, int[]> entry : plan.layers().headMap(top, true).entrySet()) {
            boolean high = anyHigh && entry.getKey() <= layerY + 1 && entry.getKey() + 1 - plan.bounds.minY > down;
            for (int i : entry.getValue()) {
                BuildPlan.Status st = plan.status(i);
                if (st != BuildPlan.Status.PENDING && st != BuildPlan.Status.UNLOADED) continue;
                // Water and kelp wait for the solid blocks of their layer: their buckets, a slot each, stay in the box till then.
                if (plan.wetWaits(i)) {
                    if (!high || buckets <= 0 || plan.item(i) != Items.WATER_BUCKET) continue;
                    buckets--;
                }
                // A block full of water in the schematic not in yet takes a bucket after it, as it goes in dry first.
                if (high && buckets > 0 && plan.kind(i) == BlockRules.Kind.PLACE && BlockRules.soaked(plan.target(i))
                        && !plan.soaking(i) && !plan.needsPour(i)) {
                    needs.merge(Items.WATER_BUCKET, 1, Integer::sum);
                    buckets--;
                }
                Item item = plan.item(i);
                if (item == null) continue;
                int count = plan.kind(i) == BlockRules.Kind.FLUID || plan.soaking(i) ? 1
                        : BlockRules.itemsNeeded(level.getBlockState(plan.pos(i)), plan.target(i));
                needs.merge(item, count, Integer::sum);
                // Broken to go in again into water: the block itself goes in once the water is poured, so the one picked up
                // isn't put back into a box while fetching the bucket.
                Item own = plan.needsPour(i) ? BlockRules.item(plan.target(i)) : null;
                if (own != null) needs.merge(own, 1, Integer::sum);
            }
        }
        return needs;
    }

    /**
     * Slots free for buckets of water carried up to a high layer (see {@link #needsUpTo}): those free now, less the ones
     * kept free and those the solid blocks of the layers up to {@code top} still take beyond what is carried, a stack each.
     */
    private int bucketRoom(LocalPlayer player, Level level, int top) {
        Materials carried = Materials.of(player.getInventory());
        Map<Item, Integer> solid = new HashMap<>();
        for (int[] layer : plan.layers().headMap(top, true).values()) {
            for (int i : layer) {
                BuildPlan.Status st = plan.status(i);
                if (st != BuildPlan.Status.PENDING && st != BuildPlan.Status.UNLOADED || plan.wetWaits(i)) continue;
                Item item = plan.item(i);
                if (item == null || item == Items.WATER_BUCKET) continue;
                solid.merge(item, BlockRules.itemsNeeded(level.getBlockState(plan.pos(i)), plan.target(i)), Integer::sum);
            }
        }
        int stacks = 0;
        for (Map.Entry<Item, Integer> e : solid.entrySet()) {
            int missing = e.getValue() - carried.loose(e.getKey());
            if (missing > 0) stacks += (missing + e.getKey().getDefaultMaxStackSize() - 1) / e.getKey().getDefaultMaxStackSize();
        }
        // The buckets carried already count as room for them: else each one taken would shrink the room, and those taken
        // would go back into the next box as wanted by nothing.
        return Math.max(0, carried.freeSlots() + carried.total(Items.WATER_BUCKET) - AHEAD_FREE - stacks);
    }

    /**
     * A slot of the player's inventory, in the open box's menu, to put back into the box when room is short, so what the
     * box was opened for fits: first blocks the next layers don't need, and empty buckets; then helper blocks past one
     * stack; then blocks the layer being built doesn't need; last, a stack more than that layer needs. Never the item
     * the box was opened for. -1 when there is room enough or nothing can go back.
     */
    private int nextToDeposit(ShulkerBoxMenu menu, LocalPlayer player) {
        Materials carried = Materials.of(player.getInventory());
        if (carried.freeSlots() >= 3) return -1;
        boolean boxHasRoom = false;
        for (int slot = 0; slot < 27; slot++) if (menu.getSlot(slot).getItem().isEmpty()) boxHasRoom = true;
        if (!boxHasRoom) return -1;
        return toPutBack(menu, player, carried, false);
    }

    /**
     * With no room in the inventory for what the box was opened for, and none in the box to put something back into, a
     * stack that can go back is swapped for a stack of it: picked up, put down on the box's, and that one put where the
     * first was, as a player does. False when there is nothing to swap.
     * An item that doesn't stack, a water bucket, is swapped again while the next layers want more of it, for stacks they
     * don't need now only: one a box trip, v4-full3 walked back to a box for each source of layer 2, the inventory full.
     */
    private boolean swapForRoom(ShulkerBoxMenu menu, LocalPlayer player) {
        Materials carried = Materials.of(player.getInventory());
        int have = carried.loose(restockItem);
        boolean more = have > 0 && restockItem.getDefaultMaxStackSize() == 1 && have < soon.getOrDefault(restockItem, 0);
        if (player.hasInfiniteMaterials() || carried.freeSlots() > 2 || have > 0 && !more) return false;
        int wanted = -1;
        for (int slot = 0; slot < 27 && wanted < 0; slot++) if (menu.getSlot(slot).getItem().is(restockItem)) wanted = slot;
        if (wanted < 0) {
            if (debug() && !more) LOGGER.info("No swap for {} in the full inventory: the open box holds none", name(restockItem));
            return false;
        }
        int back = toPutBack(menu, player, carried, !more);
        if (back < 0) {
            if (debug() && !more) LOGGER.info("No swap for {} in the full inventory: nothing carried can go back into the box", name(restockItem));
            return false;
        }
        if (debug()) LOGGER.info("Swapping {} for {} in the full shulker box", menu.getSlot(back).getItem(), menu.getSlot(wanted).getItem());
        swappedIn.put(restockItem, plan.count(BuildPlan.Status.DONE));
        swapClicks.add(back);
        swapClicks.add(wanted);
        swapClicks.add(back);
        return true;
    }

    /**
     * The slot of the player's inventory, in the box's menu, best put back into the box; -1 when nothing can go back.
     * With {@code swap}, for a swap with what the box was opened for, a stack the layer still takes all of comes last: the
     * one needed last, its nearest block farthest off. With the inventory full of boxes and every stack one the layer
     * takes, as on ikea_v4's layer 8 (26 boxes, crafters, iron doors and observers all at y 72), nothing else could go back, the box was closed
     * without what it was opened for, and the walks for its blocks came to nothing. Swapped, those blocks go in, and the
     * stack put back comes out again when its blocks come up.
     */
    private int toPutBack(ShulkerBoxMenu menu, LocalPlayer player, Materials carried, boolean swap) {
        Map<Item, Integer> needs = plan.needs(), now = needsUpTo(player.level(), layerY);
        Map<Item, Integer> helperKept = new HashMap<>();
        Map<Item, Double> nearest = null;
        int best = -1, bestRank = Integer.MAX_VALUE;
        for (int slot = 27; slot < menu.slots.size(); slot++) {
            ItemStack stack = menu.getSlot(slot).getItem();
            if (stack.isEmpty() || Materials.isShulkerBox(stack) || stack.is(restockItem)) continue;
            Item item = stack.getItem();
            int rank;
            if (helperItems.contains(item)) {
                int kept = helperKept.merge(item, 1, Integer::sum);
                // Past the stacks kept, they go back first; the second stack kept only when nothing else can, as when
                // every slot holds what the layer takes and the box is full: one stack still climbs, and more come out of a
                // box when it runs low.
                if (kept == 1) continue;
                rank = kept <= HELPER_STACKS ? 5 : 1;
            } else if (stack.is(Items.BUCKET)) {
                // A few empty ones stay, to fill for the water the next layers still take.
                if (carried.loose(Items.BUCKET) - stack.getCount() < Math.min(3, soon.getOrDefault(Items.WATER_BUCKET, 0))) continue;
                rank = 0;
            } else if (needs.containsKey(item) && !soon.containsKey(item)) {
                rank = 0;
            } else if (!needs.containsKey(item)) {
                // What the player carries of their own stays; but one left over of the build's own blocks, of a kind all placed
                // now, goes back first. The crafter run stopped in layer 9 so, 22 slots holding one each of a repeater, a
                // note block, a door and the like, with nothing to put back for the chests it opened a box for.
                if (!bill().containsKey(item)) continue;
                rank = 0;
            } else if (!now.containsKey(item)) {
                rank = 2;
            } else if (carried.loose(item) - stack.getCount() >= now.get(item)) {
                rank = 3;
            } else if (swap) {
                // Not one just swapped in for another box, before a few of its blocks went in: else box after box swaps
                // the same few stacks round, as v4-y did five times in 16 s, glass, observers, gates, hoppers, concrete.
                // Only while another can go: with nothing else, the one swapped in longest ago goes back after all, rather than
                // the box closing without what it was opened for, which in the end stops the build.
                Integer in = swappedIn.get(item);
                rank = in != null && plan.count(BuildPlan.Status.DONE) - in < SWAP_BACK_AFTER ? 7 : 6;
            } else {
                continue;
            }
            if (rank == 6 && nearest == null) nearest = nearestPending(player);
            if (rank < bestRank || rank == 6 && bestRank == 6 && laterThan(nearest, stack, menu.getSlot(best).getItem())
                    || rank == 7 && bestRank == 7 && swappedIn.get(item) < swappedIn.get(menu.getSlot(best).getItem().getItem())) {
                best = slot;
                bestRank = rank;
            }
        }
        return best;
    }

    /**
     * For a swap among stacks the layer still takes: whether {@code stack} is needed later than {@code other}, its nearest
     * block still to go in farther from the player; of one item, the larger stack. Largest first alone swapped out the
     * blue ice and iron bars round v4's up-facing observers, needed right after each observer the box was opened for, and
     * the next box swapped them back in, three boxes for three blocks (v4-clean3, 01:10).
     */
    private static boolean laterThan(Map<Item, Double> nearest, ItemStack stack, ItemStack other) {
        double a = nearest.getOrDefault(stack.getItem(), Double.MAX_VALUE), b = nearest.getOrDefault(other.getItem(), Double.MAX_VALUE);
        return a != b ? a > b : stack.getCount() > other.getCount();
    }

    /** How far from the player, squared, the nearest block of each item still to go in up to the layer being built is. */
    private Map<Item, Double> nearestPending(LocalPlayer player) {
        Map<Item, Double> nearest = new HashMap<>();
        for (int[] layer : plan.layers().headMap(layerY, true).values()) {
            for (int i : layer) {
                BuildPlan.Status st = plan.status(i);
                if (st != BuildPlan.Status.PENDING && st != BuildPlan.Status.UNLOADED) continue;
                Item item = plan.item(i);
                if (item != null) nearest.merge(item, player.distanceToSqr(Vec3.atCenterOf(plan.pos(i))), Math::min);
            }
        }
        return nearest;
    }

    /** The next slot of the open box to take: the item it was opened for first, then what the next layers need. */
    private int nextToTake(ShulkerBoxMenu menu, LocalPlayer player) {
        Materials carried = Materials.of(player.getInventory());
        // Two slots stay free: one for the box itself, and one for whatever is picked up on the way back to it, as a
        // helper block taken away, which would otherwise take the box's slot and leave it lying there. Other items only
        // go into room to spare: they would otherwise crowd out what the box was opened for, be put back for it, and be
        // taken again.
        if (carried.freeSlots() <= 2) return -1;
        for (boolean first : new boolean[] {true, false}) {
            if (!first && carried.freeSlots() <= 4) break;
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

    /**
     * For the debug log: how full the box closed is, and the boxes carried, those empty among them and the stacks they
     * hold in all, to see how many slots boxes take that hold nothing any more.
     */
    private void logBoxes(ShulkerBoxMenu menu, LocalPlayer player) {
        int kept = 0;
        for (int slot = 0; slot < 27; slot++) if (!menu.getSlot(slot).getItem().isEmpty()) kept++;
        int boxes = 0, empty = 0, stacks = 0;
        for (int slot = 0; slot < Materials.SLOTS; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (!Materials.isShulkerBox(stack)) continue;
            boxes++;
            int inside = 0;
            for (ItemStack held : Materials.contents(stack)) if (!held.isEmpty()) inside++;
            if (inside == 0) empty++;
            stacks += inside;
        }
        LOGGER.info("Box closed with {} of 27 slots full; {} boxes carried besides, {} of them empty, {} stacks in them",
                kept, boxes, empty, stacks);
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
            if (debug() && player != null) {
                LOGGER.info("Shulker box at {}: {}, from eye {}, {} m", box.toShortString(), why, player.getEyePosition(),
                        Math.round(Math.sqrt(player.getEyePosition().distanceToSqr(Vec3.atCenterOf(box))) * 10) / 10.0);
            }
            // Get the box back before anything else; one it can't get at after a few rounds is left to the player.
            if (++boxRounds > 3) {
                restock = Restock.NONE;
                finish(client, "stopped: it can't get at your shulker box at " + box.getX() + " " + box.getY() + " " + box.getZ()
                        + "; pick it up and start again.", true);
                return;
            }
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
                    Item item = plan.item(i);
                    color = i == nextIndex ? 0xFFFFFF : missing.contains(item) ? 0xFF6F6F : accent;
                } else if (st == BuildPlan.Status.WRONG || st == BuildPlan.Status.MISPLACED || st == BuildPlan.Status.FAILED) {
                    color = 0xF28C52;
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
        int misplaced = plan.count(BuildPlan.Status.MISPLACED), wrongNow = plan.count(BuildPlan.Status.WRONG);
        if (debug() && (misplaced > misplacedLogged || wrongNow > wrongLogged)) {
            // Which block went in the wrong way, or which spot holds another block, as it stands, against what was wanted.
            Level level = Minecraft.getInstance().level;
            for (int i = 0; i < plan.size() && level != null; i++) {
                BuildPlan.Status st = plan.status(i);
                if (st == BuildPlan.Status.MISPLACED || st == BuildPlan.Status.WRONG) {
                    LOGGER.info("{} at {}: {} instead of {}", st == BuildPlan.Status.WRONG ? "Wrong block" : "Misplaced",
                            plan.pos(i).toShortString(), level.getBlockState(plan.pos(i)), plan.target(i));
                }
            }
        }
        misplacedLogged = misplaced;
        wrongLogged = wrongNow;
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
        client.player.sendSystemMessage(Component.literal("[Ven] SchematicBuilder " + text).withStyle(color));
    }
}
