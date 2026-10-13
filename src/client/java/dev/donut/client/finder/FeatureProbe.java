package dev.donut.client.finder;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;

/**
 * Tests whether a server places Nether features from its world seed, using ore blobs anti-xray never hides: magma,
 * gravel and blackstone. It predicts where each blob centers in the loaded chunks and counts the centers that hold
 * the blob's block. Features placed from the seed match far more often than a wrong seed, run alongside as a control;
 * a server that gives its features their own seeds matches no better than the control. Each ore is also tried at
 * every feature index, so a server whose datapacks reorder the Nether's features shows where they moved.
 */
public final class FeatureProbe {
    private static final int STEP = GenerationStep.Decoration.UNDERGROUND_DECORATION.ordinal();
    private static final int MAX_INDEX = 30;
    static final Ore MAGMA = new Ore("magma", Blocks.MAGMA_BLOCK, 11, 4, 27, 36, false);
    static final Ore GRAVEL = new Ore("gravel", Blocks.GRAVEL, 17, 2, 5, 41, true);
    static final Ore BLACKSTONE = new Ore("blackstone", Blocks.BLACKSTONE, 18, 2, 5, 31, true);
    private static final List<Ore> ORES = List.of(MAGMA, GRAVEL, BLACKSTONE);

    /**
     * A Nether ore feature of size 33 replacing netherrack: its index among the underground decorations, blobs per
     * chunk and height range. Some are missing from basalt deltas, where their biome filter drops them.
     */
    record Ore(String name, Block block, int index, int count, int minY, int maxY, boolean notInDeltas) { }

    /** One ore's matches at its own index, under a wrong seed, and at the index that matched best. */
    public record OreResult(String name, int index, int checked, int matched, int controlMatched, int bestIndex, int bestMatched) {
        public String describe() {
            return "%s %d of %d at index %d (wrong seed %d; best index %d with %d)"
                    .formatted(name, matched, checked, index, controlMatched, bestIndex, bestMatched);
        }

        /** Far more blobs where the seed puts them than where a wrong seed does. */
        public boolean matches() { return matched >= 20 && matched >= 4 * Math.max(1, controlMatched); }
    }

    public record Result(List<OreResult> ores) {
        public String describe() { return String.join("; ", ores.stream().map(OreResult::describe).toList()); }

        /** Magma, the densest, decides whether this server places Nether features from the seed. */
        public boolean seedPlacesFeatures() { return ores.getFirst().matches(); }
    }

    private FeatureProbe() { }

    public static Result run(ClientLevel level, ChunkPos center, int radius, long seed) {
        List<LevelChunk> chunks = new ArrayList<>();
        for (int x = center.x - radius; x <= center.x + radius; x++) {
            for (int z = center.z - radius; z <= center.z + radius; z++) {
                LevelChunk chunk = level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
                if (chunk != null) chunks.add(chunk);
            }
        }
        WorldgenRandom random = new WorldgenRandom(new XoroshiroRandomSource(0));
        List<OreResult> results = new ArrayList<>();
        for (Ore ore : ORES) {
            int[] own = count(level, chunks, random, seed, ore, ore.index());
            int control = count(level, chunks, random, seed + 1, ore, ore.index())[1];
            int bestIndex = ore.index(), bestMatched = own[1];
            for (int index = 0; index <= MAX_INDEX; index++) {
                int matched = count(level, chunks, random, seed, ore, index)[1];
                if (matched > bestMatched) { bestIndex = index; bestMatched = matched; }
            }
            results.add(new OreResult(ore.name(), ore.index(), own[0], own[1], control, bestIndex, bestMatched));
        }
        return new Result(List.copyOf(results));
    }

    /** Predicted blob centers in these chunks and how many hold the ore, with the feature at this index. */
    /**
     * Gravel and blackstone blob centers predicted in one chunk, and how many hold their block. Anti-xray leaves both
     * alone, so they show whether this part of a server's world placed its ores from the seed.
     */
    static int[] verify(Level level, LevelChunk chunk, WorldgenRandom random, long seed) {
        int[] gravel = count(level, List.of(chunk), random, seed, GRAVEL, GRAVEL.index());
        int[] blackstone = count(level, List.of(chunk), random, seed, BLACKSTONE, BLACKSTONE.index());
        return new int[] {gravel[0] + blackstone[0], gravel[1] + blackstone[1]};
    }

    private static int[] count(Level level, List<LevelChunk> chunks, WorldgenRandom random, long seed, Ore ore, int index) {
        int[] counts = new int[2];
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (LevelChunk chunk : chunks) {
            int minX = chunk.getPos().getMinBlockX(), minZ = chunk.getPos().getMinBlockZ();
            random.setFeatureSeed(random.setDecorationSeed(seed, minX, minZ), index, STEP);
            for (int i = 0; i < ore.count(); i++) {
                int x = minX + random.nextInt(16);
                int z = minZ + random.nextInt(16);
                int y = Mth.randomBetweenInclusive(random, ore.minY(), ore.maxY());
                // A filtered blob draws nothing more.
                if (ore.notInDeltas() && level.getBiome(pos.set(x, y, z)).is(Biomes.BASALT_DELTAS)) continue;
                // OreFeature draws the same numbers wherever it lands: an angle, two heights, a radius per sphere.
                random.nextFloat();
                int low = random.nextInt(3) - 2, high = random.nextInt(3) - 2;
                for (int sphere = 0; sphere < 33; sphere++) random.nextDouble();
                // The blob centers on the start's column, up to two blocks below it. A center dug out or flooded
                // since says nothing either way; servers whose players mine heavily are full of them.
                BlockState center = chunk.getBlockState(pos.set(x, y + Math.floorDiv(low + high, 2), z));
                if (center.isAir() || !center.getFluidState().isEmpty()) continue;
                counts[0]++;
                if (center.is(ore.block())) counts[1]++;
            }
        }
        return counts;
    }
}
