package dev.donut.client.finder;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.GlowItemFrame;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.phys.Vec3;

/**
 * Scores each loaded chunk by what players leave behind; see {@link BaseEvidence}. Only blocks open to air or another
 * see-through block count: anti-xray's fake ores, which can include chests and ender chests, are always buried.
 */
final class BaseIndex {
    private static final Predicate<BlockState> ANCIENT_CITY = state -> state.is(Blocks.REINFORCED_DEEPSLATE);
    private static final Direction[] DIRECTIONS = Direction.values();
    /** Item frames and armor stands: none generate outside an end ship's single frame and one taiga house's stand. */
    private static final int ENTITY_WEIGHT = 2, ENTITY_CAP = 3;

    private final ChunkIndex<Scan> chunks = new ChunkIndex<>(BaseIndex::scan, 8, 200);

    void clear() { chunks.clear(); }

    void tick(ClientLevel level, ChunkPos center, double range) { chunks.tick(level, center, range); }

    void markDirty(int chunkX, int chunkZ) { chunks.markDirty(chunkX, chunkZ); }

    /** Chunks at or above the score within range, with item frames and armor stands added. */
    void collect(ClientLevel level, Vec3 origin, double range, double minScore, List<BaseArea> found) {
        boolean end = level.dimensionTypeRegistration().is(BuiltinDimensionTypes.END);
        // Per chunk: item frames, armor stands, and the heights they span.
        Long2ObjectOpenHashMap<int[]> entities = new Long2ObjectOpenHashMap<>();
        for (Entity entity : level.entitiesForRendering()) {
            // An end ship's elytra hangs in an item frame.
            boolean frame = entity instanceof ItemFrame && !(end && !(entity instanceof GlowItemFrame));
            if (!frame && !(entity instanceof ArmorStand)) continue;
            int[] counts = entities.computeIfAbsent(ChunkPos.pack(entity.chunkPosition().x(), entity.chunkPosition().z()),
                    key -> new int[] {0, 0, Integer.MAX_VALUE, Integer.MIN_VALUE});
            counts[frame ? 0 : 1]++;
            counts[2] = Math.min(counts[2], entity.getBlockY());
            counts[3] = Math.max(counts[3], entity.getBlockY());
        }
        LongSet seen = new LongOpenHashSet();
        chunks.forEach(scan -> {
            long key = ChunkPos.pack(scan.chunk.x(), scan.chunk.z());
            seen.add(key);
            add(scan, entities.get(key), origin, range, minScore, found);
        });
        // Chunks whose only evidence is item frames or armor stands.
        for (var entry : entities.long2ObjectEntrySet()) {
            if (seen.contains(entry.getLongKey())) continue;
            int[] near = entry.getValue();
            ChunkPos chunk = new ChunkPos(ChunkPos.getX(entry.getLongKey()), ChunkPos.getZ(entry.getLongKey()));
            add(new Scan(chunk, new int[BaseEvidence.values().length], 0, near[2], near[3]), near, origin, range, minScore, found);
        }
    }

    private static void add(Scan scan, int[] near, Vec3 origin, double range, double minScore, List<BaseArea> found) {
        int frames = near == null ? 0 : near[0], stands = near == null ? 0 : near[1];
        int extra = Math.min(frames, ENTITY_CAP) * ENTITY_WEIGHT + Math.min(stands, ENTITY_CAP) * ENTITY_WEIGHT;
        int score = BaseEvidence.score(scan.counts, scan.storage, extra);
        if (score < minScore) return;
        List<String> named = new ArrayList<>();
        if (frames > 0) named.add(frames == 1 ? "item frame" : frames + " item frames");
        if (stands > 0) named.add(stands == 1 ? "armor stand" : stands + " armor stands");
        int minY = scan.minY, maxY = scan.maxY;
        if (near != null) {
            minY = Math.min(minY, near[2]);
            maxY = Math.max(maxY, near[3]);
        }
        BaseArea area = new BaseArea(scan.chunk, minY, maxY, score, BaseEvidence.reasons(scan.counts, scan.storage, named));
        if (area.horizontalDistance(origin) <= range) found.add(area);
    }

    /** A chunk's own blocks scored as BaseFinder scores them, without item frames or armor stands; null without any. */
    static BaseArea blocks(LevelChunk chunk) {
        Scan scan = scan(chunk);
        if (scan == null) return null;
        return new BaseArea(scan.chunk, scan.minY, scan.maxY, BaseEvidence.score(scan.counts, scan.storage, 0),
                BaseEvidence.reasons(scan.counts, scan.storage, List.of()));
    }

    /** The highest score in range, for the HUD, or 0. */
    static int best(List<BaseArea> areas) {
        int best = 0;
        for (BaseArea area : areas) best = Math.max(best, area.score());
        return best;
    }

    private static Scan scan(LevelChunk chunk) {
        Level level = chunk.getLevel();
        boolean nether = level.dimensionTypeRegistration().is(BuiltinDimensionTypes.NETHER);
        boolean end = level.dimensionTypeRegistration().is(BuiltinDimensionTypes.END);
        LevelChunkSection[] sections = chunk.getSections();
        boolean ancientCity = false;
        for (LevelChunkSection section : sections) {
            if (!section.hasOnlyAir() && section.maybeHas(ANCIENT_CITY)) { ancientCity = true; break; }
        }
        int[] counts = new int[BaseEvidence.values().length];
        int storage = 0, minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        int minX = chunk.getPos().getMinBlockX(), minZ = chunk.getPos().getMinBlockZ();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(), neighbor = new BlockPos.MutableBlockPos();
        for (int index = 0; index < sections.length; index++) {
            LevelChunkSection section = sections[index];
            // The palette check skips almost every section without reading its blocks.
            if (section.hasOnlyAir() || !section.maybeHas(BaseEvidence.ANY)) continue;
            int sectionY = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(index));
            for (int i = 0; i < 4096; i++) {
                BlockState state = section.getBlockState(i & 15, i >> 8, (i >> 4) & 15);
                BaseEvidence evidence = BaseEvidence.of(state);
                boolean chest = evidence == null && BaseEvidence.storage(state);
                if (evidence == null && !chest) continue;
                if (evidence != null && evidence.natural(nether, end, ancientCity)) continue;
                pos.set(minX + (i & 15), sectionY + (i >> 8), minZ + ((i >> 4) & 15));
                if (!exposed(level, chunk, pos, neighbor)) continue;
                if (chest) storage++;
                else counts[evidence.ordinal()]++;
                minY = Math.min(minY, pos.getY());
                maxY = Math.max(maxY, pos.getY());
            }
        }
        if (minY == Integer.MAX_VALUE) return null;
        return new Scan(chunk.getPos(), counts, storage, minY, maxY);
    }

    /** Anti-xray only disguises blocks whose six neighbors all conduct redstone, as stone does. */
    private static boolean exposed(Level level, LevelChunk chunk, BlockPos pos, BlockPos.MutableBlockPos neighbor) {
        for (Direction direction : DIRECTIONS) {
            neighbor.setWithOffset(pos, direction);
            BlockState state;
            if (SectionPos.blockToSectionCoord(neighbor.getX()) == chunk.getPos().x()
                    && SectionPos.blockToSectionCoord(neighbor.getZ()) == chunk.getPos().z()) {
                if (chunk.isOutsideBuildHeight(neighbor)) continue;
                state = chunk.getBlockState(neighbor);
            } else {
                if (!LoadedChunks.has(level, neighbor)) continue;
                state = level.getBlockState(neighbor);
            }
            if (!state.isRedstoneConductor(level, neighbor)) return true;
        }
        return false;
    }

    /** One chunk's evidence, by {@link BaseEvidence} ordinal, its chests and barrels, and the heights they span. */
    private record Scan(ChunkPos chunk, int[] counts, int storage, int minY, int maxY) { }
}
