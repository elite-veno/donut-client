package dev.donut.client.finder;

import java.util.OptionalLong;
import java.util.function.LongConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
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
    private final WorldgenRandom random = new WorldgenRandom(new XoroshiroRandomSource(0));

    /** A seed as typed on the world creation screen: a number, or text hashed the same way. */
    static OptionalLong parseSeed(String text) { return text == null ? OptionalLong.empty() : WorldOptions.parseSeed(text); }

    /**
     * Every position this chunk's debris features try, as {@link BlockPos#asLong} values: a handful, some
     * reaching a block or two into a neighboring chunk. None if this game's debris can't be replayed.
     */
    void attempts(long seed, int chunkX, int chunkZ, LongConsumer out) {
        NetherFeatures features = NetherFeatures.get().orElse(null);
        if (features == null) return;
        int minX = SectionPos.sectionToBlockCoord(chunkX), minZ = SectionPos.sectionToBlockCoord(chunkZ);
        long decoration = random.setDecorationSeed(seed, minX, minZ);
        feature(decoration, features.debrisLarge, minX, minZ, out);
        feature(decoration, features.debrisSmall, minX, minZ, out);
    }

    /** CountPlacement, InSquarePlacement, HeightRangePlacement, then ScatteredOreFeature, drawing in the same order they do. */
    private void feature(long decoration, NetherFeatures.Ore ore, int minX, int minZ, LongConsumer out) {
        random.setFeatureSeed(decoration, ore.index(), NetherFeatures.STEP);
        for (int placements = ore.count().sample(random); placements > 0; placements--) {
            int x = minX + random.nextInt(16);
            int z = minZ + random.nextInt(16);
            int y = ore.y(random);
            int count = random.nextInt(ore.size() + 1);
            for (int i = 0; i < count; i++) {
                int spread = Math.min(i, 7);
                int dx = offset(spread), dy = offset(spread), dz = offset(spread);
                out.accept(BlockPos.asLong(x + dx, y + dy, z + dz));
            }
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
