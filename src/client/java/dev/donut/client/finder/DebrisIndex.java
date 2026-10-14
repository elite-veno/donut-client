package dev.donut.client.finder;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.function.Predicate;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ancient debris has no block entity, so loaded chunk sections are searched directly.
 *
 * <p>Worldgen never places debris next to air, so nearly all real debris is buried. Anti-xray plugins
 * either hide buried ores (nothing to find until mining exposes them) or fill buried stone with random
 * fake ores. Both leave exposed blocks alone, and both send the real block once a neighbor is mined.
 * A chunk with far more buried debris than worldgen places is treated as faked: only its exposed debris
 * and debris the server confirmed with a block update are shown.
 *
 * <p>With the world seed, the positions worldgen tried for debris are known (see {@link DebrisPredictor}).
 * One the server shows as buried netherrack is drawn as an estimate of hidden debris, as is buried debris
 * there in a faked chunk. Exposed debris shows whether the seed is right: real debris lies on these positions.
 * A server can generate parts of its world some other way, so estimates show only in chunks whose gravel and
 * blackstone, which anti-xray leaves alone, lie where the seed puts them.
 *
 * <p>A chunk section reaches the client as a palette of block types plus each block's index into it. Paper's anti-xray
 * rewrites the indexes but leaves the palette, so a section whose palette lists debris that none of its blocks show
 * still holds hidden debris: a search area. The server's palette keeps a type after its last block is gone, so a
 * section where this client saw debris disappear proves nothing. Where search areas show, the server leaks this way,
 * and estimates in sections whose palette never listed debris are left out: they hold none.
 */
final class DebrisIndex {
    private static final Predicate<BlockState> DEBRIS = state -> state.is(Blocks.ANCIENT_DEBRIS);
    /** No server sends this; a section whose palette "has" it uses the global palette, which lists nothing. */
    private static final Predicate<BlockState> NEVER_SENT = state -> state.is(Blocks.STRUCTURE_VOID);
    /**
     * What anti-xray shows in place of buried debris: Paper's engine mode 1 uses netherrack in the Nether, and engine
     * mode 2 also random ores. Basalt or blackstone at a seed spot is what is really there.
     */
    private static final Predicate<BlockState> COVER = state -> state.is(Blocks.NETHERRACK);
    /** Engine mode 2 shows a buried block as any of its hidden types; Paper's Nether list includes these. */
    private static final Predicate<BlockState> FAKE_ORE = state -> state.is(Blocks.ANCIENT_DEBRIS)
            || state.is(Blocks.NETHER_QUARTZ_ORE) || state.is(Blocks.NETHER_GOLD_ORE) || state.is(Blocks.GILDED_BLACKSTONE)
            || state.is(Blocks.GLOWSTONE) || state.is(Blocks.MAGMA_BLOCK) || state.is(Blocks.BONE_BLOCK) || state.is(Blocks.NETHER_BRICKS);
    private static final Direction[] DIRECTIONS = Direction.values();
    private static final Logger LOGGER = LoggerFactory.getLogger("Donut/Debris");
    /** Lines the live log writes per world, so a server without anti-xray can't flood it. */
    private static final int LOG_LIMIT = 1500;
    /** Worldgen's two debris features place a handful per chunk; anti-xray fakes number in the hundreds. */
    static final int FAKE_THRESHOLD = 24;
    /** Search areas it takes to believe the server leaks; one stale palette elsewhere must not hide every estimate. */
    private static final int LEAK_EVIDENCE = 3;
    /**
     * The live log, which lets a seed be checked against a real server from latest.log, is written only on request:
     * by {@link DebrisLiveTest} or {@code -Ddonut.debrisLog=true}.
     */
    private static boolean liveLog = Boolean.getBoolean("donut.debrisLog");

    private final ChunkIndex<DebrisChunk> chunks = new ChunkIndex<>(this::scan, 8);
    /** Positions the server sent as debris in a block update, which anti-xray never fakes. */
    private final LongSet confirmed = new LongOpenHashSet();
    /** Sections where this client saw debris turn into another block; the server's palette still lists it. */
    private final LongSet stale = new LongOpenHashSet();
    /**
     * Sections whose palette listed debris since their chunk last arrived. The client rebuilds a full palette from the
     * blocks it can see when a block update needs room, which drops hidden debris from it.
     */
    private final LongSet everListed = new LongOpenHashSet();
    private final DebrisPredictor predictor = new DebrisPredictor();
    private final WorldgenRandom oreRandom = new WorldgenRandom(new XoroshiroRandomSource(0));
    /** The world seed estimates come from, if any. */
    private OptionalLong seed = OptionalLong.empty();
    private DebrisPredictor.Bedrock bedrock;
    private DebrisStats stats = DebrisStats.EMPTY;
    private List<SectionPos> areas = List.of();
    /** Estimate spots already counted as a hit or a miss. */
    private final LongSet judged = new LongOpenHashSet();
    /** Debris and estimate outcomes already written to the live log. */
    private final LongSet logged = new LongOpenHashSet();
    private final LongSet loggedAreas = new LongOpenHashSet();
    private int logLines;
    private int collects;
    private String lastSummary = "";

    void clear() {
        chunks.clear();
        confirmed.clear();
        stale.clear();
        everListed.clear();
        judged.clear();
        logged.clear();
        loggedAreas.clear();
        areas = List.of();
        logLines = 0;
        lastSummary = "";
        stats = DebrisStats.EMPTY;
    }

    void tick(ClientLevel level, ChunkPos center, double range, OptionalLong seed) {
        // Cached chunks hold the old seed's estimates.
        if (!seed.equals(this.seed)) {
            this.seed = seed;
            bedrock = seed.isPresent() ? new DebrisPredictor.Bedrock(seed.getAsLong()) : null;
            chunks.clear();
            // Logged debris is relogged against the new seed's positions.
            logged.clear();
            loggedAreas.clear();
            logLines = 0;
            lastSummary = "";
            if (seed.isPresent()) log("seed %d", seed.getAsLong());
        }
        chunks.tick(level, center, range);
    }

    void markDirty(BlockPos pos) { chunks.markDirty(SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ())); }

    void markDirty(ChunkPos pos) { chunks.markDirty(pos.x(), pos.z()); }

    /** Debris this client saw turn into another block, such as by mining: its section's palette goes stale. */
    void debrisRemoved(BlockPos pos) { stale.add(SectionPos.asLong(pos)); }

    /**
     * A chunk sent again brings the server's palettes, read afresh on its next scan. Stale sections stay stale: the
     * server's palette keeps mined debris until it unloads the chunk.
     */
    void chunkReloaded(ClientLevel level, ChunkPos pos) {
        for (int y = level.getMinSectionY(); y <= level.getMaxSectionY(); y++) everListed.remove(SectionPos.asLong(pos.x(), y, pos.z()));
    }

    static void enableLiveLog() { liveLog = true; }

    static boolean nether(Level level) { return level.dimensionTypeRegistration().is(BuiltinDimensionTypes.NETHER); }

    /** Whether this position's chunk is filled with anti-xray fake debris. */
    boolean inFakedChunk(BlockPos pos) {
        DebrisChunk chunk = chunks.get(SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ()));
        return chunk != null && chunk.faked();
    }

    enum Reveal { NONE, HIT, MISS }

    /**
     * The server sends real blocks in block updates, including those it hid around a block just mined. At an estimate
     * spot in a seed chunk that tells whether the seed's debris is really there.
     */
    Reveal reveal(BlockPos pos, BlockState state) {
        if (seed.isEmpty() || state.isAir()) return Reveal.NONE;
        DebrisChunk chunk = chunks.get(SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ()));
        if (chunk == null || !chunk.estimates().contains(pos) || !Boolean.TRUE.equals(followsSeed(chunk))) return Reveal.NONE;
        if (!judged.add(pos.asLong())) return Reveal.NONE;
        return DEBRIS.test(state) ? Reveal.HIT : Reveal.MISS;
    }

    /** @return true the first time the server confirms debris at this position */
    boolean confirm(BlockPos pos, boolean debris) {
        if (!debris) { confirmed.remove(pos.asLong()); return false; }
        return confirmed.add(pos.asLong());
    }

    DebrisStats stats() { return stats; }

    /** Search areas in range, from the last collect. */
    List<SectionPos> areas() { return areas; }

    /** @param estimates whether to draw seed estimates; counting and checking the seed goes on either way */
    void collect(ClientLevel level, Vec3 origin, double range, boolean filterFakes, boolean searchAreas, boolean estimates,
                 List<FoundBlock> found) {
        double rangeSqr = range * range;
        boolean nether = nether(level), seeded = nether && seed.isPresent();
        BlockPos.MutableBlockPos neighbor = new BlockPos.MutableBlockPos();
        // Stale sections still listed debris, which is all the estimate filter asks.
        LongSet listed = new LongOpenHashSet(everListed);
        List<SectionPos> inRange = new ArrayList<>();
        int[] clueCount = new int[1];
        chunks.forEach(chunk -> {
            for (long section : chunk.listed()) listed.add(section);
            // Engine mode 2 lists every hidden type in every section's palette, so a faked chunk's palettes prove nothing.
            if (chunk.faked()) return;
            for (long section : chunk.clues()) {
                if (stale.contains(section)) continue;
                clueCount[0]++;
                SectionPos pos = SectionPos.of(section);
                if (pos.center().distToCenterSqr(origin) <= rangeSqr) inRange.add(pos);
            }
        });
        inRange.sort(java.util.Comparator.comparingDouble(pos -> pos.center().distToCenterSqr(origin)));
        boolean leaks = clueCount[0] >= LEAK_EVIDENCE;
        LongSet estimated = new LongOpenHashSet();
        int[] counts = new int[14];
        chunks.forEach(chunk -> {
            Boolean follows = seeded ? followsSeed(chunk) : null;
            if (Boolean.TRUE.equals(follows)) counts[11]++;
            if (Boolean.FALSE.equals(follows)) counts[12]++;
            counts[2] += chunk.exposed().size();
            counts[8] += chunk.bedrockChecked();
            counts[9] += chunk.bedrockMatched();
            counts[10] += chunk.bedrockSeen();
            boolean faked = filterFakes && chunk.faked();
            if (chunk.faked()) counts[3]++;
            // Real debris in a chunk generated another way says nothing about the seed.
            boolean checks = seeded && !Boolean.FALSE.equals(follows);
            for (BlockPos pos : chunk.exposed()) {
                add(level, pos, origin, rangeSqr, found);
                if (checks) checkSeed(chunk, pos, counts);
                if (nether) logDebris(chunk, pos, "exposed");
            }
            for (BlockPos pos : chunk.buried()) {
                boolean real = confirmed.contains(pos.asLong());
                // Revealed debris no longer counts as hidden.
                if (!real) counts[1]++;
                if (checks && real) checkSeed(chunk, pos, counts);
                if (nether && (real || !chunk.faked())) logDebris(chunk, pos, real ? "revealed" : "buried");
                if (faked && !real) { counts[4]++; continue; }
                add(level, pos, origin, rangeSqr, found);
            }
            if (!Boolean.TRUE.equals(follows)) return;
            for (LongIterator iterator = chunk.attempts().iterator(); iterator.hasNext(); ) {
                if (level.getBlockState(BlockPos.of(iterator.nextLong())).isAir()) counts[13]++;
            }
            for (BlockPos pos : estimates ? chunk.estimates() : List.<BlockPos>of()) {
                if (confirmed.contains(pos.asLong())) { logOutcome(pos, "debris"); continue; }
                if (pos.distToCenterSqr(origin) > rangeSqr || !LoadedChunks.has(level, pos)) continue;
                // On a server whose palettes give hidden debris away, a section whose palette lacks it holds none.
                if (leaks && !listed.contains(SectionPos.asLong(pos))) continue;
                BlockState state = level.getBlockState(pos);
                // Debris drawn as found needs no estimate; a block mined out or opened up was not hiding any.
                if (DEBRIS.test(state) && !faked) continue;
                if (!COVER.test(state) && !FAKE_ORE.test(state) || exposed(level, level.getChunkAt(pos), pos, neighbor)) {
                    logOutcome(pos, BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath());
                    continue;
                }
                counts[5]++;
                estimated.add(SectionPos.asLong(pos));
                found.add(new FoundBlock(pos, TargetKind.ANCIENT_DEBRIS, true));
            }
        });
        // A chunk sent again hides revealed debris once more, though the server already showed it is there.
        for (LongIterator iterator = confirmed.iterator(); iterator.hasNext(); ) {
            BlockPos pos = BlockPos.of(iterator.nextLong());
            if (pos.distToCenterSqr(origin) > rangeSqr || !LoadedChunks.has(level, pos)) continue;
            if (COVER.test(level.getBlockState(pos))) found.add(new FoundBlock(pos, TargetKind.ANCIENT_DEBRIS));
        }
        // An estimate pinpoints its section's debris; an area is for the sections where the seed can't.
        inRange.removeIf(area -> estimated.contains(area.asLong()));
        areas = searchAreas ? List.copyOf(inRange) : List.of();
        stats = new DebrisStats(nether, chunks.size(), counts[1], counts[2], counts[3], counts[4], seeded, counts[5], counts[6], counts[7],
                counts[8], counts[9], counts[10], counts[11], counts[12], counts[13], areas.size());
        if (nether) for (SectionPos area : areas) logArea(area);
        if (nether && collects++ % 60 == 0) logSummary();
    }

    /**
     * Whether the ores around this chunk lie where the seed puts them, or null when there are too few to tell even
     * three chunks out, as deep in basalt deltas. Of the gravel and blackstone centers still solid, worldgen's own hold
     * their ore over 90% of the time; another placement's about 3%.
     */
    private Boolean followsSeed(DebrisChunk chunk) {
        // Neighbors must not carry a chunk whose own ores say otherwise; a seed chunk shows this about once in 2000.
        if (chunk.oreChecked() >= 3 && chunk.oreMatched() == 0) return false;
        for (int radius = 1; radius <= 3; radius++) {
            int checked = 0, matched = 0;
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    DebrisChunk near = chunks.get(chunk.x() + dx, chunk.z() + dz);
                    if (near == null) continue;
                    checked += near.oreChecked();
                    matched += near.oreMatched();
                }
            }
            if (checked >= 8) return matched * 2 >= checked;
        }
        return null;
    }

    /** Debris the server proved real lies on a seed position, unless the seed is not this world's. */
    private static void checkSeed(DebrisChunk chunk, BlockPos pos, int[] counts) {
        counts[6]++;
        if (chunk.attempts().contains(pos.asLong())) counts[7]++;
    }

    private static void add(ClientLevel level, BlockPos pos, Vec3 origin, double rangeSqr, List<FoundBlock> found) {
        // Cached hits are rechecked so mined debris disappears before its chunk is rescanned.
        if (pos.distToCenterSqr(origin) <= rangeSqr && DEBRIS.test(level.getBlockState(pos))) {
            found.add(new FoundBlock(pos, TargetKind.ANCIENT_DEBRIS));
        }
    }

    // The live log: what the server shows, so a seed can be checked against a real server from latest.log.

    private void logDebris(DebrisChunk chunk, BlockPos pos, String how) {
        if (!liveLog || logLines >= LOG_LIMIT || !logged.add(pos.asLong())) return;
        String spot = seed.isEmpty() ? "" : chunk.attempts().contains(pos.asLong()) ? ", on a seed spot" : ", NOT on a seed spot";
        log("debris %d %d %d %s%s", pos.getX(), pos.getY(), pos.getZ(), how, spot);
    }

    private void logOutcome(BlockPos pos, String block) {
        if (liveLog && logLines < LOG_LIMIT && logged.add(pos.asLong())) log("estimate %d %d %d is %s", pos.getX(), pos.getY(), pos.getZ(), block);
    }

    private void logArea(SectionPos area) {
        if (!liveLog || logLines >= LOG_LIMIT || !loggedAreas.add(area.asLong())) return;
        DebrisChunk chunk = chunks.get(area.x(), area.z());
        int spots = 0, hidden = 0;
        if (chunk != null) {
            for (LongIterator iterator = chunk.attempts().iterator(); iterator.hasNext(); ) {
                if (SectionPos.asLong(BlockPos.of(iterator.next())) == area.asLong()) spots++;
            }
            for (BlockPos pos : chunk.estimates()) if (SectionPos.asLong(pos) == area.asLong()) hidden++;
        }
        log("search area %d %d %d (blocks %d %d %d), seed spots inside %d, estimates inside %d", area.x(), area.y(), area.z(),
                area.minBlockX(), area.minBlockY(), area.minBlockZ(), spots, hidden);
    }

    private void logSummary() {
        if (!liveLog) return;
        DebrisStats s = stats;
        String summary = ("chunks %d, buried %d, exposed %d, faked chunks %d, estimates %d, debris on seed spots %d of %d, "
                + "bedrock %d of %d match (%d bedrock), chunks on seed %d, off seed %d, seed spots dug out %d, search areas %d")
                .formatted(s.scannedChunks(), s.buried(), s.exposed(), s.fakedChunks(), s.estimates(), s.seedMatched(), s.seedChecked(),
                        s.bedrockMatched(), s.bedrockChecked(), s.bedrockSeen(), s.seedChunks(), s.offSeedChunks(), s.dugOut(), s.searchAreas());
        if (summary.equals(lastSummary)) return;
        lastSummary = summary;
        LOGGER.info("[DebrisFinder] {}", summary);
    }

    private void log(String format, Object... args) {
        if (liveLog && logLines++ < LOG_LIMIT) LOGGER.info("[DebrisFinder] {}", format.formatted(args));
    }

    private DebrisChunk scan(LevelChunk chunk) {
        List<BlockPos> exposed = new ArrayList<>(), buried = new ArrayList<>();
        LongArrayList listed = new LongArrayList(), clues = new LongArrayList();
        Level level = chunk.getLevel();
        LevelChunkSection[] sections = chunk.getSections();
        int minX = chunk.getPos().getMinBlockX(), minZ = chunk.getPos().getMinBlockZ();
        BlockPos.MutableBlockPos neighbor = new BlockPos.MutableBlockPos();
        for (int index = 0; index < sections.length; index++) {
            LevelChunkSection section = sections[index];
            // The palette check skips almost every section without reading its blocks.
            if (section.hasOnlyAir() || !section.maybeHas(DEBRIS)) continue;
            int minY = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(index));
            int found = exposed.size() + buried.size();
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        if (!DEBRIS.test(section.getBlockState(x, y, z))) continue;
                        BlockPos pos = new BlockPos(minX + x, minY + y, minZ + z);
                        (exposed(level, chunk, pos, neighbor) ? exposed : buried).add(pos);
                    }
                }
            }
            long sectionPos = SectionPos.asLong(chunk.getPos().x(), chunk.getSectionYFromSectionIndex(index), chunk.getPos().z());
            // The global palette lists every type: it may hold debris, but it is no clue. Listed but nowhere in the blocks: hidden.
            listed.add(sectionPos);
            if (section.maybeHas(NEVER_SENT)) continue;
            if (exposed.size() + buried.size() == found) clues.add(sectionPos);
        }
        everListed.addAll(listed);
        LongSet attempts = attempts(chunk);
        boolean faked = buried.size() > FAKE_THRESHOLD;
        List<BlockPos> estimates = new ArrayList<>();
        for (LongIterator iterator = attempts.iterator(); iterator.hasNext(); ) {
            BlockPos pos = BlockPos.of(iterator.nextLong());
            BlockState state = chunk.getBlockState(pos);
            // Without fake ores, buried debris and ores reach the client as they are; only netherrack can hide debris.
            boolean cover = COVER.test(state) || faked && FAKE_ORE.test(state);
            if (cover && !exposed(level, chunk, pos, neighbor)) estimates.add(pos);
        }
        int[] bedrockCounts = checkBedrock(chunk);
        // Engine mode 2 also disguises buried gravel and blackstone, so a faked chunk checks only the exposed blobs.
        int[] ores = attempts.isEmpty() ? new int[2] : FeatureProbe.verify(level, chunk, oreRandom, seed.getAsLong(), faked);
        if (exposed.isEmpty() && buried.isEmpty() && attempts.isEmpty() && listed.isEmpty()) return null;
        return new DebrisChunk(chunk.getPos().x(), chunk.getPos().z(), List.copyOf(exposed), List.copyOf(buried), List.copyOf(estimates),
                attempts, bedrockCounts[0], bedrockCounts[1], bedrockCounts[2], ores[0], ores[1], listed.toLongArray(), clues.toLongArray());
    }

    /** Compares every other column of the chunk's bedrock layers with the seed: checked, matched, bedrock seen. */
    private int[] checkBedrock(LevelChunk chunk) {
        int[] counts = new int[3];
        if (bedrock == null || !nether(chunk.getLevel())) return counts;
        int minX = chunk.getPos().getMinBlockX(), minZ = chunk.getPos().getMinBlockZ();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y : new int[] {1, 2, 3, 4, 123, 124, 125, 126}) {
            for (int x = minX; x < minX + 16; x += 2) {
                for (int z = minZ; z < minZ + 16; z += 2) {
                    boolean actual = chunk.getBlockState(pos.set(x, y, z)).is(Blocks.BEDROCK);
                    counts[0]++;
                    if (actual == bedrock.at(x, y, z)) counts[1]++;
                    if (actual) counts[2]++;
                }
            }
        }
        return counts;
    }

    /** The seed positions inside this chunk, including those its neighbors' features reach into it. */
    private LongSet attempts(LevelChunk chunk) {
        if (seed.isEmpty() || !nether(chunk.getLevel())) return LongSets.EMPTY_SET;
        ChunkPos center = chunk.getPos();
        LongSet inside = new LongOpenHashSet();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                predictor.attempts(seed.getAsLong(), center.x() + dx, center.z() + dz, packed -> {
                    if (SectionPos.blockToSectionCoord(BlockPos.getX(packed)) == center.x()
                            && SectionPos.blockToSectionCoord(BlockPos.getZ(packed)) == center.z()) inside.add(packed);
                });
            }
        }
        return inside;
    }

    /** Anti-xray only fakes blocks whose six neighbors all conduct redstone, the way stone does. */
    private static boolean exposed(Level level, LevelChunk chunk, BlockPos pos, BlockPos.MutableBlockPos neighbor) {
        for (Direction direction : DIRECTIONS) {
            neighbor.setWithOffset(pos, direction);
            BlockState state;
            if (SectionPos.blockToSectionCoord(neighbor.getX()) == chunk.getPos().x()
                    && SectionPos.blockToSectionCoord(neighbor.getZ()) == chunk.getPos().z()) {
                if (chunk.isOutsideBuildHeight(neighbor)) continue;
                state = chunk.getBlockState(neighbor);
            } else {
                // An unloaded neighbor tells us nothing either way.
                if (!LoadedChunks.has(level, neighbor)) continue;
                state = level.getBlockState(neighbor);
            }
            if (!state.isRedstoneConductor(level, neighbor)) return true;
        }
        return false;
    }

    /**
     * One chunk's debris: exposed blocks are always real; buried ones are real unless the chunk is faked.
     * Attempts are the seed positions in the chunk; estimates are those where a buried block may hide debris. Listed
     * are the sections whose palette names debris; clues are those of them whose blocks show none.
     */
    record DebrisChunk(int x, int z, List<BlockPos> exposed, List<BlockPos> buried, List<BlockPos> estimates, LongSet attempts,
                       int bedrockChecked, int bedrockMatched, int bedrockSeen, int oreChecked, int oreMatched, long[] listed,
                       long[] clues) {
        boolean faked() { return buried.size() > FAKE_THRESHOLD; }
    }
}
