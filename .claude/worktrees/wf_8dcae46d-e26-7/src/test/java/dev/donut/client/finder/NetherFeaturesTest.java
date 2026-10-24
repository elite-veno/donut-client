package dev.donut.client.finder;

import static org.junit.jupiter.api.Assertions.*;

import java.util.IntSummaryStatistics;
import java.util.Set;
import java.util.stream.IntStream;
import net.minecraft.SharedConstants;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;

class NetherFeaturesTest {
    /**
     * The Nether's ore features as read from 26.2's registries: the numbers the replays used to carry as constants,
     * which DebrisSeedGameTest checked against real worldgen. An update that changes them fails here, while the
     * replays already follow it.
     */
    @Test
    void readsTheNethersOreFeatures() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        NetherFeatures features = new NetherFeatures(VanillaRegistries.createLookup());
        assertOre(features.debrisLarge, 21, 1, 8, 24, 3, Blocks.ANCIENT_DEBRIS, Set.of());
        assertOre(features.debrisSmall, 22, 1, 8, 119, 2, Blocks.ANCIENT_DEBRIS, Set.of());
        assertOre(features.magma, 11, 4, 27, 36, 33, Blocks.MAGMA_BLOCK, Set.of());
        assertOre(features.gravel, 17, 2, 5, 41, 33, Blocks.GRAVEL, Set.of(Biomes.BASALT_DELTAS));
        assertOre(features.blackstone, 18, 2, 5, 31, 33, Blocks.BLACKSTONE, Set.of(Biomes.BASALT_DELTAS));
    }

    private static void assertOre(NetherFeatures.Ore ore, int index, int count, int minY, int maxY, int size, Block block,
                                  Set<ResourceKey<Biome>> missingFrom) {
        assertEquals(index, ore.index(), ore.name());
        assertEquals(count, ore.count().minInclusive(), ore.name());
        assertEquals(count, ore.count().maxInclusive(), ore.name());
        // Enough draws to reach both ends of the range, even a triangle's.
        RandomSource random = RandomSource.create(1);
        IntSummaryStatistics heights = IntStream.range(0, 20_000).map(i -> ore.y(random)).summaryStatistics();
        assertEquals(minY, heights.getMin(), ore.name());
        assertEquals(maxY, heights.getMax(), ore.name());
        assertEquals(size, ore.size(), ore.name());
        assertEquals(block, ore.block(), ore.name());
        assertEquals(missingFrom, ore.missingFrom(), ore.name());
    }
}
