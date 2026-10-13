package dev.donut.client.finder;

import java.util.OptionalLong;
import java.util.function.LongConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;

/**
 * Replays the Nether's two ancient debris features from the world seed. Each draws its positions from a
 * random seeded by the world seed, the chunk and the feature's place in the Nether's feature order, never
 * from the terrain; the terrain only decides which of those positions took the ore. So every real debris
 * block sits on one of these positions, and a position the client shows as buried netherrack may be debris
 * that anti-xray hides.
 */
final class DebrisPredictor {
    /** Indexes of ore_ancient_debris_large and ore_debris_small among the Nether's underground decorations. */
    static final int LARGE_INDEX = 21, SMALL_INDEX = 22;
    private static final int STEP = GenerationStep.Decoration.UNDERGROUND_DECORATION.ordinal();

    private final WorldgenRandom random = new WorldgenRandom(new XoroshiroRandomSource(0));

    /** A seed as typed on the world creation screen: a number, or text hashed the same way. */
    static OptionalLong parseSeed(String text) { return text == null ? OptionalLong.empty() : WorldOptions.parseSeed(text); }

    /**
     * Every position this chunk's debris features try, as {@link BlockPos#asLong} values. Up to five; some
     * reach two blocks into a neighboring chunk.
     */
    void attempts(long seed, int chunkX, int chunkZ, LongConsumer out) {
        int minX = SectionPos.sectionToBlockCoord(chunkX), minZ = SectionPos.sectionToBlockCoord(chunkZ);
        long decoration = random.setDecorationSeed(seed, minX, minZ);
        feature(decoration, LARGE_INDEX, minX, minZ, true, out);
        feature(decoration, SMALL_INDEX, minX, minZ, false, out);
    }

    /** InSquarePlacement, HeightRangePlacement, then ScatteredOreFeature, drawing in the same order they do. */
    private void feature(long decoration, int index, int minX, int minZ, boolean large, LongConsumer out) {
        random.setFeatureSeed(decoration, index, STEP);
        int x = minX + random.nextInt(16);
        int z = minZ + random.nextInt(16);
        // A triangle from 8 to 24 for the large vein, uniform from 8 to 119 for the small one (generation ends at y 127).
        int y = large ? 8 + Mth.randomBetweenInclusive(random, 0, 8) + Mth.randomBetweenInclusive(random, 0, 8)
                : Mth.randomBetweenInclusive(random, 8, 119);
        int count = random.nextInt((large ? 3 : 2) + 1);
        for (int i = 0; i < count; i++) {
            int spread = Math.min(i, 7);
            int dx = offset(spread), dy = offset(spread), dz = offset(spread);
            out.accept(BlockPos.asLong(x + dx, y + dy, z + dz));
        }
    }

    private int offset(int spread) { return Math.round((random.nextFloat() - random.nextFloat()) * spread); }

    /**
     * The Nether's bedrock in y 1-4 and 123-126, which its surface rules draw from the world seed alone. Anti-xray
     * leaves bedrock alone, so it shows at once whether a seed is this Nether's.
     */
    static final class Bedrock {
        private final PositionalRandomFactory floor, roof;

        Bedrock(long seed) {
            // The Nether's noise settings use the legacy random source.
            PositionalRandomFactory random = new LegacyRandomSource(seed).forkPositional();
            floor = random.fromHashOf(Identifier.withDefaultNamespace("bedrock_floor")).forkPositional();
            roof = random.fromHashOf(Identifier.withDefaultNamespace("bedrock_roof")).forkPositional();
        }

        /** Whether worldgen put bedrock here; y must be 1 to 4 or 123 to 126. */
        boolean at(int x, int y, int z) {
            // Vertical gradients: always bedrock at y 0 fading out by y 5, and at y 127 fading out by y 122.
            if (y < 64) return floor.at(x, y, z).nextFloat() < Mth.map(y, 0, 5, 1.0, 0.0);
            return roof.at(x, y, z).nextFloat() >= Mth.map(y, 122, 127, 1.0, 0.0);
        }
    }
}
