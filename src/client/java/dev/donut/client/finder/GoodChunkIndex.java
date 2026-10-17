package dev.donut.client.finder;

import dev.donut.client.config.FinderSettings;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CactusBlock;
import net.minecraft.world.level.block.KelpBlock;
import net.minecraft.world.level.block.SugarCaneBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * ChunkFinder: reads every loaded chunk for signs of players and picks base spots away from them. The signs are plants
 * that grew on random ticks ({@link GrowthClocks}), amethyst grown past worldgen (ActivityFinder's test), blocks only
 * players build (BaseFinder's score) and players in sight. A chunk's plants are read once, the first time it is
 * scanned, and kept per dimension for the connection: from then on our own presence grows them too, and the chunk
 * sent again later would carry it.
 */
final class GoodChunkIndex {
    /** Our own presence is followed this far back, in game ticks: half an hour. */
    private static final int TRAIL_TICKS = 36_000;
    private static final int TRAIL_SEGMENTS = 4096;
    /** First sights kept per dimension; past this, those far from the newest are forgotten. */
    private static final int MAX_SIGHTS = 200_000;
    /** Before the server sends its simulation distance, a wide one: it only makes more of our own time count as ours. */
    private static final int ASSUMED_SIMULATION_DISTANCE = 12;
    /** Spots handed out per pass, best first. */
    private static final int MAX_SPOTS = 16;
    /** What {@link GoodChunk#confidence()} is about: a visit this long, in minutes. */
    static final double CONFIDENCE_MINUTES = 10;
    private static final Predicate<BlockState> CLOCK = state -> state.is(Blocks.SUGAR_CANE) || state.is(Blocks.CACTUS)
            || state.is(Blocks.KELP) || state.is(Blocks.VINE);
    private static final Direction[] HORIZONTAL = {Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};

    private final ChunkIndex<Scan> chunks = new ChunkIndex<>(this::scan, 8, 200);
    private final Map<ResourceKey<Level>, Long2ObjectOpenHashMap<Sight>> sights = new HashMap<>();
    private Long2ObjectOpenHashMap<Sight> firstSight = new Long2ObjectOpenHashMap<>();
    /** The chunks the player stood in, oldest first, each as its packed position and the ticks spent there. */
    private final ArrayDeque<long[]> trail = new ArrayDeque<>();
    private int trailTicks;
    private int simulationDistance = ASSUMED_SIMULATION_DISTANCE;
    private ChunkStats stats = ChunkStats.EMPTY;

    /** Drops the scans; first sights and our trail stay, so turning ChunkFinder back on does not judge our own growth. */
    void clear() {
        chunks.clear();
        stats = ChunkStats.EMPTY;
    }

    /** A new level: its dimension's first sights, and a trail that starts here. */
    void useLevel(ClientLevel level) {
        clear();
        firstSight = sights.computeIfAbsent(level.dimension(), key -> new Long2ObjectOpenHashMap<>());
        trail.clear();
        trailTicks = 0;
    }

    /** Left the server: what we saw there means nothing on the next one. */
    void forget() {
        clear();
        sights.clear();
        firstSight = new Long2ObjectOpenHashMap<>();
        trail.clear();
        trailTicks = 0;
    }

    ChunkStats stats() { return stats; }

    /** Call every tick, whether ChunkFinder is on or not, so time spent nearby before turning it on counts as ours. */
    void follow(ClientLevel level, ChunkPos player) {
        int distance = level.getServerSimulationDistance();
        simulationDistance = distance > 0 ? distance : ASSUMED_SIMULATION_DISTANCE;
        long key = player.pack();
        long[] last = trail.peekLast();
        if (last != null && last[0] == key) last[1]++;
        else trail.addLast(new long[] {key, 1});
        trailTicks++;
        while (trailTicks > TRAIL_TICKS || trail.size() > TRAIL_SEGMENTS) trailTicks -= (int) trail.removeFirst()[1];
    }

    void tick(ClientLevel level, ChunkPos center, double range) { chunks.tick(level, center, range); }

    /** Player blocks or amethyst changed here. */
    void markDirty(int chunkX, int chunkZ) { chunks.markDirty(chunkX, chunkZ); }

    /** Ticks we spent within simulation distance of this chunk, where the server random-ticks it for us. */
    private int exposure(ChunkPos pos) {
        long ticks = 0;
        for (long[] segment : trail) {
            int dx = ChunkPos.getX(segment[0]) - pos.x(), dz = ChunkPos.getZ(segment[0]) - pos.z();
            if (Math.max(Math.abs(dx), Math.abs(dz)) <= simulationDistance) ticks += segment[1];
        }
        return (int) ticks;
    }

    /**
     * Judges every scanned chunk and picks spots around {@code origin}.
     *
     * @param self the player, whose own presence is no sign
     */
    void collect(ClientLevel level, Entity self, Vec3 origin, FinderSettings settings, List<GoodChunk> spots, List<ChunkSign> signs) {
        Long2ObjectOpenHashMap<String> players = new Long2ObjectOpenHashMap<>();
        for (Player player : level.players()) {
            if (player == self || EntityKind.of(player) != EntityKind.PLAYER) continue;
            players.put(player.chunkPosition().pack(), player.getName().getString());
        }
        List<SpotPicker.Cell> cells = new ArrayList<>();
        int[] counts = new int[2];
        chunks.forEach(scan -> {
            String reason = reason(scan, players.remove(scan.chunk.pack()), settings);
            cells.add(new SpotPicker.Cell(scan.chunk.x(), scan.chunk.z(), reason != null, scan.sight.clocks));
            if (scan.sight.clocks.clocks() > 0) counts[0]++;
            if (reason == null) return;
            counts[1]++;
            signs.add(new ChunkSign(scan.chunk, scan.sight.surfaceY, reason));
        });
        // Players in chunks not read yet still keep spots away.
        for (long key : players.keySet()) cells.add(new SpotPicker.Cell(ChunkPos.getX(key), ChunkPos.getZ(key), true, null));

        int clearance = (int) Math.round(settings.minClearance);
        ChunkPos center = ChunkPos.containing(BlockPos.containing(origin));
        for (SpotPicker.Spot spot : SpotPicker.pick(cells, clearance, settings.maxUnseen, center.x(), center.z(), MAX_SPOTS)) {
            Scan scan = chunks.get(spot.x(), spot.z());
            spots.add(new GoodChunk(scan.chunk, scan.sight.surfaceY, spot.area().detectChance(CONFIDENCE_MINUTES), spot.clear(),
                    spot.area().clocks(), spot.unseenMinutes()));
        }
        boolean overworld = level.dimensionTypeRegistration().is(BuiltinDimensionTypes.OVERWORLD);
        int roomy = spots.isEmpty() ? SpotPicker.roomy(cells, clearance) : spots.size();
        stats = new ChunkStats(overworld, chunks.size(), counts[0], counts[1], roomy);
    }

    /** Why spots must keep away from this chunk, or null when nothing in it shows players. */
    private String reason(Scan scan, String player, FinderSettings settings) {
        if (player != null) return "Player here: " + player;
        if (scan.blocks != null && scan.blocks.score() >= BaseEvidence.DEFAULT_MIN_SCORE) return "Player blocks: " + scan.blocks.reasons();
        if (scan.amethyst != null) {
            ActivityArea judged = judge(scan);
            if (judged.score() > 0) return "Amethyst grew " + Math.round(judged.score() * 100) + "%";
        }
        Sight sight = scan.sight;
        if (!sight.clocks.foreign(sight.ownTicks)) return null;
        double minutes = sight.clocks.minutes(sight.ownTicks);
        if (minutes < settings.ignoreVisitsUnder) return null;
        return sight.clocks.reason(sight.ownTicks) + ", " + duration(minutes);
    }

    /** ActivityFinder's judgement of this chunk's amethyst against the amethyst chunks around it. */
    private ActivityArea judge(Scan scan) {
        List<ActivityArea> around = new ArrayList<>(9);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                Scan near = chunks.get(scan.chunk.x() + dx, scan.chunk.z() + dz);
                if (near != null && near.amethyst != null) around.add(near.amethyst);
            }
        }
        return ActivityIndex.judge(scan.amethyst, around);
    }

    static String duration(double minutes) {
        if (minutes < 1) return "<1 min";
        if (minutes < 90) return "~" + Math.round(minutes) + " min";
        return "~" + Math.round(minutes / 60) + " h";
    }

    private Scan scan(LevelChunk chunk) {
        long key = chunk.getPos().pack();
        Sight sight = firstSight.get(key);
        if (sight == null) {
            sight = new Sight(clocks(chunk), exposure(chunk.getPos()), chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, 8, 8));
            if (firstSight.size() >= MAX_SIGHTS) forgetFarFrom(chunk.getPos());
            firstSight.put(key, sight);
        }
        return new Scan(chunk.getPos(), sight, ActivityIndex.scan(chunk), BaseIndex.blocks(chunk));
    }

    private void forgetFarFrom(ChunkPos pos) {
        firstSight.long2ObjectEntrySet().removeIf(entry -> Math.max(Math.abs(ChunkPos.getX(entry.getLongKey()) - pos.x()),
                Math.abs(ChunkPos.getZ(entry.getLongKey()) - pos.z())) > 128);
    }

    /** The chunk's plants as the server sent them. */
    static GrowthClocks clocks(LevelChunk chunk) {
        GrowthClocks clocks = new GrowthClocks();
        Level level = chunk.getLevel();
        LevelChunkSection[] sections = chunk.getSections();
        int minX = chunk.getPos().getMinBlockX(), minZ = chunk.getPos().getMinBlockZ();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int index = 0; index < sections.length; index++) {
            LevelChunkSection section = sections[index];
            // The palette check skips almost every section without reading its blocks.
            if (section.hasOnlyAir() || !section.maybeHas(CLOCK)) continue;
            int sectionY = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(index));
            for (int i = 0; i < 4096; i++) {
                BlockState state = section.getBlockState(i & 15, i >> 8, (i >> 4) & 15);
                if (!CLOCK.test(state)) continue;
                pos.set(minX + (i & 15), sectionY + (i >> 8), minZ + ((i >> 4) & 15));
                if (state.is(Blocks.SUGAR_CANE)) cane(chunk, pos, state, clocks);
                else if (state.is(Blocks.CACTUS)) cactus(chunk, pos, state, clocks);
                else if (state.is(Blocks.KELP)) kelp(chunk, pos, state, clocks);
                else vine(level, chunk, pos, clocks);
            }
        }
        return clocks;
    }

    /** A column's top ages until it grows to 3 blocks; any age is a random tick's. */
    private static void cane(LevelChunk chunk, BlockPos pos, BlockState state, GrowthClocks clocks) {
        BlockState above = chunk.getBlockState(pos.above());
        if (above.is(Blocks.SUGAR_CANE)) return;
        int height = below(chunk, pos, Blocks.SUGAR_CANE) + 1, age = state.getValue(SugarCaneBlock.AGE);
        if (age > 0) clocks.add(GrowthClocks.Kind.CANE, true, age);
        else if (above.isAir() && height < 3) clocks.add(GrowthClocks.Kind.CANE, false, 0);
        if (above.isAir() && (height == 2 || height == 3)) clocks.caneColumn(height == 2);
    }

    /** A top ages up to 15 whatever the height; one under a flower has stopped. */
    private static void cactus(LevelChunk chunk, BlockPos pos, BlockState state, GrowthClocks clocks) {
        BlockState above = chunk.getBlockState(pos.above());
        if (above.is(Blocks.CACTUS)) return;
        int age = state.getValue(CactusBlock.AGE);
        if (age > 0) clocks.add(GrowthClocks.Kind.CACTUS, true, age);
        else if (above.isAir()) clocks.add(GrowthClocks.Kind.CACTUS, false, 0);
    }

    /** Worldgen leaves the top at age 20 to 23, under water, in a column of at most 11; it grows only into still water. */
    private static void kelp(LevelChunk chunk, BlockPos pos, BlockState state, GrowthClocks clocks) {
        BlockState above = chunk.getBlockState(pos.above());
        int age = state.getValue(KelpBlock.AGE), column = below(chunk, pos, Blocks.KELP_PLANT) + 1;
        if (age >= 24 || above.isAir() || column >= 12) clocks.add(GrowthClocks.Kind.KELP, true, 0);
        else if (age < 25 && above.is(Blocks.WATER) && above.getFluidState().isSource()) clocks.add(GrowthClocks.Kind.KELP, false, 0);
    }

    /**
     * Read from the lowest vine of a column: worldgen hangs at most 4 below the one held by a leaf, so a fifth hanging
     * vine grew. A run of 4 with air below can still show growth.
     */
    private static void vine(Level level, LevelChunk chunk, BlockPos bottom, GrowthClocks clocks) {
        BlockState below = chunk.getBlockState(bottom.below());
        if (below.is(Blocks.VINE)) return;
        int hanging = 0;
        BlockPos.MutableBlockPos pos = bottom.mutable();
        while (hanging < 16) {
            BlockState state = chunk.getBlockState(pos);
            if (!state.is(Blocks.VINE) || held(level, pos, state)) break;
            hanging++;
            pos.move(Direction.UP);
        }
        if (hanging >= 5) clocks.add(GrowthClocks.Kind.VINE, true, 0);
        else if (hanging == 4 && below.isAir()) clocks.add(GrowthClocks.Kind.VINE, false, 0);
    }

    /** Whether a face of this vine rests on a block, rather than hanging from the vine above. */
    private static boolean held(Level level, BlockPos pos, BlockState state) {
        if (state.getValue(VineBlock.UP) && attachable(level, pos, Direction.UP)) return true;
        for (Direction direction : HORIZONTAL) {
            if (state.getValue(VineBlock.getPropertyForFace(direction)) && attachable(level, pos, direction)) return true;
        }
        return false;
    }

    /** An unloaded neighbor reads as air, so it counts as holding the vine: a run is never read longer than it is. */
    private static boolean attachable(Level level, BlockPos pos, Direction direction) {
        BlockPos next = pos.relative(direction);
        return !LoadedChunks.has(level, next) || VineBlock.isAcceptableNeighbour(level, next, direction);
    }

    /** How many blocks of this kind are stacked right below, up to 64. */
    private static int below(LevelChunk chunk, BlockPos pos, Block block) {
        BlockPos.MutableBlockPos next = pos.mutable().move(Direction.DOWN);
        int count = 0;
        while (count < 64 && next.getY() >= chunk.getMinY() && chunk.getBlockState(next).is(block)) {
            count++;
            next.move(Direction.DOWN);
        }
        return count;
    }

    /**
     * A chunk's plants as first read, how long we had been within simulation distance of it by then, and the surface
     * in its middle column.
     */
    private record Sight(GrowthClocks clocks, int ownTicks, int surfaceY) { }

    private record Scan(ChunkPos chunk, Sight sight, ActivityArea amethyst, BaseArea blocks) { }
}
