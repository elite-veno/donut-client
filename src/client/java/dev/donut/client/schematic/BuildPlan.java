package dev.donut.client.schematic;

import dev.donut.client.finder.LoadedChunks;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
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
    private final int[] counts = new int[Status.values().length];
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
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        Map<Integer, List<Integer>> byY = new TreeMap<>();
        for (int i = 0; i < n; i++) {
            BlockPos pos = BlockPos.of(positions[i]);
            index.put(positions[i], i);
            kinds[i] = BlockRules.kind(targets[i]);
            status[i] = kinds[i] == BlockRules.Kind.PLACE ? Status.UNLOADED : Status.SKIPPED;
            counts[status[i].ordinal()]++;
            minX = Math.min(minX, pos.getX());
            minY = Math.min(minY, pos.getY());
            minZ = Math.min(minZ, pos.getZ());
            maxX = Math.max(maxX, pos.getX());
            maxY = Math.max(maxY, pos.getY());
            maxZ = Math.max(maxZ, pos.getZ());
            if (kinds[i] == BlockRules.Kind.PLACE) byY.computeIfAbsent(pos.getY(), y -> new ArrayList<>()).add(i);
        }
        for (Map.Entry<Integer, List<Integer>> layer : byY.entrySet()) {
            List<Integer> order = layer.getValue();
            if (redstoneLast) order.sort(Comparator.comparingInt(i -> BlockRules.group(this.targets[i])));
            layers.put(layer.getKey(), order.stream().mapToInt(Integer::intValue).toArray());
        }
        bounds = n == 0 ? new AABB(BlockPos.ZERO) : new AABB(minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1);
    }

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

    public BlockPos pos(int i) { return BlockPos.of(positions[i]); }

    public BlockState target(int i) { return targets[i]; }

    public int indexOf(BlockPos pos) { return index.getOrDefault(pos.asLong(), -1); }

    /** Layer heights from the bottom up, each with its blocks in placing order. */
    public TreeMap<Integer, int[]> layers() { return layers; }

    public int count(Status s) { return counts[s.ordinal()]; }

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
                int need = BlockRules.itemsNeeded(current, targets[cursor]);
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
        Status now = compute(level, i);
        if (now != status[i]) {
            counts[status[i].ordinal()]--;
            counts[now.ordinal()]++;
            status[i] = now;
        }
        return now;
    }

    private Status compute(Level level, int i) {
        if (kinds[i] != BlockRules.Kind.PLACE) return Status.SKIPPED;
        BlockPos pos = pos(i);
        if (!LoadedChunks.has(level, pos)) return Status.UNLOADED;
        BlockState current = level.getBlockState(pos), target = targets[i];
        if (BlockRules.satisfied(current, target)) return Status.DONE;
        if (failed[i]) return Status.FAILED;
        if (current.getBlock() == target.getBlock()) {
            return BlockRules.placementMatches(current, target) ? Status.PENDING : Status.MISPLACED;
        }
        return current.canBeReplaced() ? Status.PENDING : Status.WRONG;
    }

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
