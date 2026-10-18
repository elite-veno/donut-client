package dev.donut.client.schematic;

import dev.donut.client.finder.LoadedChunks;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;

/**
 * A schematic placed in the world: every block it needs, where, grouped into layers from the bottom up, and what the
 * world holds at each spot now. Statuses are read from the world a slice at a time, so a large schematic costs a few
 * thousand block reads a tick rather than all of them at once.
 */
public final class BuildPlan {
    public enum Status {
        /** Still to place, top up or click. */
        PENDING,
        /** As the schematic has it. */
        DONE,
        /** The right block, set differently than placing it again could fix, such as a stair facing the wrong way. */
        MISPLACED,
        /** Another block stands there; the builder never breaks blocks. */
        WRONG,
        /** Its chunk isn't loaded, so what is there is unknown. */
        UNLOADED,
        /** Comes with another block, or no item places it. */
        SKIPPED,
        /** Clicked three times without the server keeping it. */
        FAILED
    }

    /** Tries without the block changing before the builder gives up on it. */
    static final int MAX_TRIES = 3;

    private final long[] positions;
    private final BlockState[] targets;
    private final BlockRules.Kind[] kinds;
    private final Status[] status;
    private final int[] tries;
    /** The block there at the last try, to tell a try that did something from one that didn't. */
    private final BlockState[] triedOn;
    private final boolean[] failed;
    /** Support last worked out, with a fingerprint of the blocks around it then; it holds while they don't change. */
    private final long[] supportStamp;
    private final byte[] support;
    private final Map<Long, Integer> index = new HashMap<>();
    /** Each layer's blocks by height, in placing order: plain blocks, then redstone, then what moves. */
    private final TreeMap<Integer, int[]> layers = new TreeMap<>();
    /** With redstone last: for each block, the blocks of an earlier group in its layer at most two blocks away. */
    private final int[][] earlierNear;
    private static final int[] NONE = new int[0];
    private final int[] counts = new int[Status.values().length];
    /** Whether each block is water, lava or what grows in water; and of those, how many have each status. */
    private final boolean[] wet;
    /** Each block's layer, by its place from the bottom (-1 for none); and the blocks not wet still to go in, by layer. */
    private final int[] layerOf;
    private int[] dryLeft = new int[0];
    /** Whether each block is counted in {@link #dryLeft} now. */
    private final boolean[] inDryLeft;
    /**
     * Whether each block holds water in the schematic; of those, whether it stands placed but still dry, whether it was
     * taken away to go in again into water, and whether its spot holds that water now.
     */
    private final boolean[] soak;
    private final boolean[] dryDone;
    private final boolean[] drained;
    private final boolean[] waterIn;
    /** Spots holding a helper block in water the build has there, with the layer each counts in (see {@link #hold}). */
    private final Map<Long, Integer> holds = new HashMap<>();
    public final AABB bounds;
    private int cursor;
    private Map<Item, Integer> needs = Map.of();
    private final Map<Item, Integer> needsBuilding = new HashMap<>();
    private boolean swept;

    private BuildPlan(long[] positions, BlockState[] targets, boolean redstoneLast) {
        this.positions = positions;
        this.targets = targets;
        int n = targets.length;
        kinds = new BlockRules.Kind[n];
        status = new Status[n];
        tries = new int[n];
        triedOn = new BlockState[n];
        failed = new boolean[n];
        supportStamp = new long[n];
        support = new byte[n];
        wet = new boolean[n];
        inDryLeft = new boolean[n];
        soak = new boolean[n];
        dryDone = new boolean[n];
        drained = new boolean[n];
        waterIn = new boolean[n];
        layerOf = new int[n];
        Arrays.fill(layerOf, -1);
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        Map<Integer, List<Integer>> byY = new TreeMap<>();
        for (int i = 0; i < n; i++) {
            BlockPos pos = BlockPos.of(positions[i]);
            index.put(positions[i], i);
            kinds[i] = BlockRules.kind(targets[i]);
            status[i] = placed(i) ? Status.UNLOADED : Status.SKIPPED;
            counts[status[i].ordinal()]++;
            wet[i] = BlockRules.wet(targets[i]);
            soak[i] = kinds[i] == BlockRules.Kind.PLACE && BlockRules.soaked(targets[i]);
            minX = Math.min(minX, pos.getX());
            minY = Math.min(minY, pos.getY());
            minZ = Math.min(minZ, pos.getZ());
            maxX = Math.max(maxX, pos.getX());
            maxY = Math.max(maxY, pos.getY());
            maxZ = Math.max(maxZ, pos.getZ());
            if (placed(i)) byY.computeIfAbsent(pos.getY(), y -> new ArrayList<>()).add(i);
        }
        for (Map.Entry<Integer, List<Integer>> layer : byY.entrySet()) {
            List<Integer> order = layer.getValue();
            if (redstoneLast) order.sort(Comparator.comparingInt(i -> BlockRules.group(this.targets[i])));
            layers.put(layer.getKey(), order.stream().mapToInt(Integer::intValue).toArray());
        }
        dryLeft = new int[layers.size()];
        int number = 0;
        for (int[] layer : layers.values()) {
            for (int i : layer) {
                layerOf[i] = number;
                if (!wet[i] && left(status[i])) {
                    dryLeft[number]++;
                    inDryLeft[i] = true;
                }
            }
            number++;
        }
        bounds = n == 0 ? new AABB(BlockPos.ZERO) : new AABB(minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1);
        earlierNear = new int[n][];
        for (int i = 0; i < n; i++) {
            int group = BlockRules.group(targets[i]);
            if (!redstoneLast || !placed(i) || group == 0) {
                earlierNear[i] = NONE;
                continue;
            }
            BlockPos pos = BlockPos.of(positions[i]);
            List<Integer> near = new ArrayList<>();
            for (int dx = -NEAR; dx <= NEAR; dx++) {
                for (int dz = -NEAR; dz <= NEAR; dz++) {
                    int j = indexOf(pos.offset(dx, 0, dz));
                    if (j >= 0 && placed(j) && BlockRules.group(targets[j]) < group) near.add(j);
                }
            }
            earlierNear[i] = near.stream().mapToInt(Integer::intValue).toArray();
        }
    }

    /** How far apart, across a layer, a block and one of an earlier group can be and still have to go in in order. */
    public static final int NEAR = 2;

    /** The schematic placed at {@code origin}, mirrored and then rotated about it. */
    public static BuildPlan of(Schematic schematic, BlockPos origin, Mirror mirror, Rotation rotation, boolean redstoneLast) {
        long[] positions = new long[schematic.size()];
        BlockState[] targets = new BlockState[schematic.size()];
        for (int i = 0; i < schematic.size(); i++) {
            positions[i] = Schematic.place(schematic.pos(i), origin, mirror, rotation).asLong();
            targets[i] = Schematic.place(schematic.state(i), mirror, rotation);
        }
        return new BuildPlan(positions, targets, redstoneLast);
    }

    /** Built straight from positions and states, for tests. */
    static BuildPlan of(Map<BlockPos, BlockState> blocks, boolean redstoneLast) {
        long[] positions = new long[blocks.size()];
        BlockState[] targets = new BlockState[blocks.size()];
        int i = 0;
        for (Map.Entry<BlockPos, BlockState> entry : blocks.entrySet()) {
            positions[i] = entry.getKey().asLong();
            targets[i++] = entry.getValue();
        }
        return new BuildPlan(positions, targets, redstoneLast);
    }

    public int size() { return targets.length; }

    /** Whether the builder puts this block in itself: placed with its item, or poured from a bucket. */
    private boolean placed(int i) { return kinds[i] == BlockRules.Kind.PLACE || kinds[i] == BlockRules.Kind.FLUID; }

    public BlockRules.Kind kind(int i) { return kinds[i]; }

    public BlockPos pos(int i) { return BlockPos.of(positions[i]); }

    public BlockState target(int i) { return targets[i]; }

    public int indexOf(BlockPos pos) { return indexOf(pos.asLong()); }

    public int indexOf(long pos) { return index.getOrDefault(pos, -1); }

    /**
     * The blocks of an earlier group than block {@code i} in its layer, at most {@link #NEAR} blocks away across it: with
     * redstone last, these go in before it. Empty without redstone last.
     */
    public int[] earlierNear(int i) { return earlierNear[i]; }

    /** Layer heights from the bottom up, each with its blocks in placing order. */
    public TreeMap<Integer, int[]> layers() { return layers; }

    public int count(Status s) { return counts[s.ordinal()]; }

    /** Whether block {@code i} is water, lava or what grows in water: those go in once the rest of their layer is. */
    public boolean wet(int i) { return wet[i]; }

    /**
     * Whether block {@code i} stands as the schematic has it but for the water it holds, a chest or trapdoor placed dry:
     * a bucket poured into it is all it still takes. It counts as in for the blocks placed after it, and gets its water
     * with the rest of its layer's.
     */
    public boolean soaking(int i) { return dryDone[i] && status[i] == Status.PENDING; }

    /**
     * Notes that block {@code i}, {@link #soaking}, is taken away to go in again into water, as a chest is filled: water
     * poured into its spot first, then the block placed into it.
     */
    public void drain(int i) {
        if (soak[i]) drained[i] = true;
    }

    /** Whether block {@code i} was taken away to go in again into water, and its spot is still without it. */
    public boolean refilling(int i) { return drained[i] && status[i] == Status.PENDING && !dryDone[i]; }

    /** Whether block {@code i} is {@link #refilling} and its spot wants the water poured first. */
    public boolean needsPour(int i) { return refilling(i) && !waterIn[i]; }

    /** The item block {@code i} takes next: a water bucket for one {@link #soaking} or {@link #needsPour}, else its own. */
    public Item item(int i) { return soaking(i) || needsPour(i) ? Items.WATER_BUCKET : BlockRules.item(targets[i]); }

    /**
     * Whether wet block {@code i}, or one {@link #soaking} or {@link #refilling}, still waits: blocks other than water,
     * lava and what grows in water are still to go in, or not read yet, in its layer or one below it. Water flows sideways
     * and down only, so those are what hold it in; it goes in before the layer above roofs it over, while it can still be
     * reached from above.
     */
    public boolean wetWaits(int i) {
        if (!wet[i] && !soaking(i) && !refilling(i) || layerOf[i] < 0) return false;
        for (int layer = 0; layer <= layerOf[i]; layer++) if (dryLeft[layer] > 0) return true;
        return false;
    }

    private static boolean left(Status s) { return s == Status.PENDING || s == Status.UNLOADED; }

    /** Blocks the builder places, as opposed to those that come with another or can't be placed. */
    public int placeable() { return targets.length - counts[Status.SKIPPED.ordinal()]; }

    /** Items still needed for every block left, as of the last full pass over the plan. */
    public Map<Item, Integer> needs() { return needs; }

    /** Whether every block has been read from the world at least once. */
    public boolean swept() { return swept; }

    public Status status(int i) { return status[i]; }

    /** Reads the next {@code budget} blocks from the world; one full pass also totals the items still needed. */
    public void refresh(Level level, int budget) {
        int n = targets.length;
        if (n == 0) {
            swept = true;
            return;
        }
        for (int k = 0; k < Math.min(budget, n); k++) {
            if (cursor == 0) needsBuilding.clear();
            Status now = update(level, cursor);
            if (now == Status.PENDING || now == Status.UNLOADED) {
                Item item = BlockRules.item(targets[cursor]);
                BlockState current = now == Status.UNLOADED ? net.minecraft.world.level.block.Blocks.AIR.defaultBlockState()
                        : level.getBlockState(pos(cursor));
                boolean bucket = soaking(cursor) || needsPour(cursor);
                int need = kinds[cursor] == BlockRules.Kind.FLUID || bucket ? 1 : BlockRules.itemsNeeded(current, targets[cursor]);
                if (bucket) item = Items.WATER_BUCKET;
                if (item != null && need > 0) needsBuilding.merge(item, need, Integer::sum);
            }
            if (++cursor == n) {
                cursor = 0;
                needs = Map.copyOf(needsBuilding);
                swept = true;
            }
        }
    }

    /** What block {@code i} needs now, read from the world. */
    public Status update(Level level, int i) {
        if (!placed(i)) return set(i, Status.SKIPPED);
        if (!LoadedChunks.has(level, pos(i))) return set(i, Status.UNLOADED);
        return update(i, level.getBlockState(pos(i)));
    }

    /** What block {@code i} needs with {@code current} in its spot, as read from the world. */
    Status update(int i, BlockState current) {
        return set(i, placed(i) ? compute(current, i) : Status.SKIPPED);
    }

    private Status set(int i, Status now) {
        if (now != Status.PENDING) {
            dryDone[i] = false;
            waterIn[i] = false;
        }
        if (now == Status.DONE || now == Status.SKIPPED) drained[i] = false;
        if (now != status[i]) {
            counts[status[i].ordinal()]--;
            counts[now.ordinal()]++;
            status[i] = now;
        }
        // A block standing dry for its water, or taken away to go in again into water, is in for the layer's other blocks:
        // the water waits for those, not they for it.
        boolean dry = !wet[i] && layerOf[i] >= 0 && left(now) && !dryDone[i] && !drained[i];
        if (dry != inDryLeft[i]) {
            dryLeft[layerOf[i]] += dry ? 1 : -1;
            inDryLeft[i] = dry;
        }
        return now;
    }

    private Status compute(BlockState current, int i) {
        BlockState target = targets[i];
        dryDone[i] = false;
        waterIn[i] = false;
        if (kinds[i] == BlockRules.Kind.FLUID) {
            // Only a source counts: water flowing past is no source, and the builder pours over it. Kelp grown up into
            // it, or a bubble column it turned into, is that water still.
            if (BlockRules.holdsFluid(current, target)) return Status.DONE;
            if (failed[i]) return Status.FAILED;
            return current.canBeReplaced() ? Status.PENDING : Status.WRONG;
        }
        if (BlockRules.satisfied(current, target)) return Status.DONE;
        dryDone[i] = soak[i] && BlockRules.needsWater(current, target) && BlockRules.placedFully(current, target);
        waterIn[i] = drained[i] && current.getBlock() instanceof LiquidBlock && current.getFluidState().isSourceOfType(Fluids.WATER);
        if (failed[i]) return Status.FAILED;
        if (BlockRules.sameBlock(current.getBlock(), target.getBlock())) {
            return BlockRules.placementMatches(current, target) ? Status.PENDING : Status.MISPLACED;
        }
        return current.canBeReplaced() ? Status.PENDING : Status.WRONG;
    }

    /**
     * While a helper block stands in water the build has at {@code pos}, as one climbed on out of a flooded shaft: it counts
     * as a block of the layer at that height still to go in, so that layer's water, and the water and layers above, wait
     * until it is gone and can't close over it. False takes the hold away. A spot under every layer holds nothing.
     */
    public void hold(BlockPos pos, boolean on) {
        long key = pos.asLong();
        if (on == holds.containsKey(key)) return;
        if (on) {
            int layer = layers.headMap(pos.getY(), true).size() - 1;
            if (layer < 0) return;
            holds.put(key, layer);
            dryLeft[layer]++;
        } else {
            dryLeft[holds.remove(key)]--;
        }
    }

    /** Whether some spot is held (see {@link #hold}). */
    public boolean holding() { return !holds.isEmpty(); }

    /** The height of the lowest layer a held spot counts in, or {@link Integer#MAX_VALUE} with none held. */
    public int heldLayerY() {
        int lowest = Integer.MAX_VALUE;
        for (long key : holds.keySet()) lowest = Math.min(lowest, layers.floorKey(BlockPos.getY(key)));
        return lowest;
    }

    /** Takes every hold away. */
    public void releaseHolds() {
        for (int layer : holds.values()) dryLeft[layer]--;
        holds.clear();
    }

    /** Gives up on block {@code i}: it counts as failed until it stands as the schematic has it. */
    public void giveUp(int i) { failed[i] = true; }

    /** Notes a click at block {@code i}; three in a row that leave it unchanged give up on it. */
    public void tried(int i, BlockState before) {
        if (triedOn[i] != before) tries[i] = 0;
        triedOn[i] = before;
        if (++tries[i] >= MAX_TRIES) failed[i] = true;
    }

    /** Support worked out for block {@code i} while the blocks around it read {@code stamp}: 1 yes, 2 no, 0 unknown. */
    public byte support(int i, long stamp) { return supportStamp[i] == stamp ? support[i] : 0; }

    public void support(int i, long stamp, boolean supported) {
        supportStamp[i] = stamp;
        support[i] = (byte) (supported ? 1 : 2);
    }

    /** A fingerprint of the 27 blocks around and at {@code pos}. */
    public static long stamp(Level level, BlockPos pos) {
        long hash = 1125899906842597L;
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int dy = -1; dy <= 1; dy++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    hash = 31 * hash + System.identityHashCode(level.getBlockState(at.setWithOffset(pos, dx, dy, dz)));
                }
            }
        }
        return hash == 0 ? 1 : hash;
    }
}
