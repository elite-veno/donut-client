package dev.donut.client.finder;

import com.google.gson.JsonElement;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.data.worldgen.placement.OrePlacements;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.RandomSource;
import net.minecraft.util.Util;
import net.minecraft.util.valueproviders.ConstantInt;
import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.util.valueproviders.IntProviders;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;
import net.minecraft.world.level.levelgen.heightproviders.HeightProvider;
import net.minecraft.world.level.levelgen.placement.BiomeFilter;
import net.minecraft.world.level.levelgen.placement.CountPlacement;
import net.minecraft.world.level.levelgen.placement.HeightRangePlacement;
import net.minecraft.world.level.levelgen.placement.InSquarePlacement;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockMatchTest;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockStateMatchTest;
import net.minecraft.world.level.levelgen.structure.templatesystem.RuleTest;
import net.minecraft.world.level.levelgen.structure.templatesystem.TagMatchTest;
import org.slf4j.Logger;

/**
 * The Nether's ore features as the game's registries define them: each one's place in the Nether's feature order, the
 * placements it tries per chunk, their heights and its size. A server sorts and places its features from these same
 * registries, so the seed replays read them here rather than copy their numbers, and stay right when an update
 * reorders or retunes the Nether's features.
 */
final class NetherFeatures {
    private static final Logger LOGGER = LogUtils.getLogger();
    static final int STEP = GenerationStep.Decoration.UNDERGROUND_DECORATION.ordinal();

    /**
     * One ore feature: its index among the underground decorations, the placements it tries per chunk and their
     * heights, its size and block, and the Nether biomes whose biome filter drops it.
     */
    record Ore(String name, int index, IntProvider count, HeightProvider height, WorldGenerationContext heights, int size,
               Block block, Set<ResourceKey<Biome>> missingFrom) {
        /** One placement's height, drawn as HeightRangePlacement draws it. */
        int y(RandomSource random) { return height.sample(random, heights); }

        /** Whether the biome filter lets a placement in this biome through. */
        boolean placesIn(Holder<Biome> biome) { return missingFrom.stream().noneMatch(biome::is); }
    }

    final Ore debrisLarge, debrisSmall, magma, gravel, blackstone;

    /** The features, read on first use; empty if this game places them in a way the replays can't follow. */
    static Optional<NetherFeatures> get() { return Loaded.FEATURES; }

    /** Reads them off the game thread, so the first replay needn't wait the fifth of a second it takes. */
    static void preload() { Util.backgroundExecutor().execute(NetherFeatures::get); }

    /** Building the registries takes a moment, so it waits for the first replay or a preload. */
    private static final class Loaded {
        static final Optional<NetherFeatures> FEATURES = load();

        private static Optional<NetherFeatures> load() {
            try {
                return Optional.of(new NetherFeatures(VanillaRegistries.createLookup()));
            } catch (RuntimeException e) {
                LOGGER.error("Can't replay the Nether's ore features, so there are no seed estimates", e);
                return Optional.empty();
            }
        }
    }

    NetherFeatures(HolderLookup.Provider registries) {
        // The Nether a new world gets, with the chunk generator and dimension type a server builds it from.
        LevelStem nether = registries.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.NORMAL).value()
                .createWorldDimensions().get(LevelStem.NETHER).orElseThrow();
        // The chunk generator sorts its features over every biome its source can place, in this order.
        List<Holder<Biome>> biomes = List.copyOf(nether.generator().getBiomeSource().possibleBiomes());
        ToIntFunction<PlacedFeature> indexes = FeatureSorter.buildFeaturesPerStep(biomes,
                biome -> biome.value().getGenerationSettings().features(), true).get(STEP).indexMapping();
        // Heights resolve in the generator's range clipped to the dimension's, as in a chunk being decorated.
        DimensionType type = nether.type().value();
        WorldGenerationContext heights = new WorldGenerationContext(nether.generator(), LevelHeightAccessor.create(type.minY(), type.height()));
        HolderLookup.RegistryLookup<PlacedFeature> placed = registries.lookupOrThrow(Registries.PLACED_FEATURE);
        BiFunction<ResourceKey<PlacedFeature>, Feature<OreConfiguration>, Ore> read =
                (key, feature) -> read(key, placed.getOrThrow(key).value(), feature, indexes, biomes, heights);
        debrisLarge = read.apply(OrePlacements.ORE_ANCIENT_DEBRIS_LARGE, Feature.SCATTERED_ORE);
        debrisSmall = read.apply(OrePlacements.ORE_ANCIENT_DEBRIS_SMALL, Feature.SCATTERED_ORE);
        magma = read.apply(OrePlacements.ORE_MAGMA, Feature.ORE);
        gravel = read.apply(OrePlacements.ORE_GRAVEL_NETHER, Feature.ORE);
        blackstone = read.apply(OrePlacements.ORE_BLACKSTONE, Feature.ORE);
        // DebrisPredictor sees no biomes, so debris must be placed in all of them.
        require(debrisLarge.missingFrom().isEmpty() && debrisSmall.missingFrom().isEmpty(), "ancient debris", "skips some Nether biomes");
    }

    /** One placed feature, refused if it draws random numbers in a way the replays don't. */
    private static Ore read(ResourceKey<PlacedFeature> key, PlacedFeature feature, Feature<OreConfiguration> kind,
                            ToIntFunction<PlacedFeature> indexes, List<Holder<Biome>> biomes, WorldGenerationContext heights) {
        String name = key.identifier().getPath();
        int index = indexes.applyAsInt(feature);
        require(index >= 0, name, "is not an underground decoration");
        // The replays draw an optional count, then a column and a height for each placement, and stop at the biome filter.
        List<PlacementModifier> placement = feature.placement();
        boolean counted = !placement.isEmpty() && placement.getFirst() instanceof CountPlacement;
        int at = counted ? 1 : 0;
        require(placement.size() == at + 3 && placement.get(at) instanceof InSquarePlacement
                && placement.get(at + 1) instanceof HeightRangePlacement && placement.get(at + 2) instanceof BiomeFilter,
                name, "is placed by " + placement);
        IntProvider count = counted ? field(placement.getFirst(), "count", IntProviders.CODEC) : ConstantInt.of(1);
        HeightProvider height = field(placement.get(at + 1), "height", HeightProvider.CODEC);
        require(feature.feature().value().feature() == kind, name, "is not a " + kind);
        OreConfiguration config = (OreConfiguration) feature.feature().value().config();
        // Then the feature's own draws must not depend on the blocks it lands in: air exposure always or never
        // matters, and its target is matched without a draw.
        require(config.discardChanceOnAirExposure == 0 || config.discardChanceOnAirExposure == 1, name, "is discarded by chance");
        require(config.targetStates.size() == 1 && drawsNothing(config.targetStates.getFirst().target), name, "picks its target by chance");
        Set<ResourceKey<Biome>> missingFrom = biomes.stream().filter(biome -> !biome.value().getGenerationSettings().hasFeature(feature))
                .map(biome -> biome.unwrapKey().orElseThrow()).collect(Collectors.toUnmodifiableSet());
        return new Ore(name, index, count, height, heights, config.size, config.targetStates.getFirst().state.getBlock(), missingFrom);
    }

    /** CountPlacement and HeightRangePlacement keep their providers private; their codecs write them out, as a datapack would. */
    private static <T> T field(PlacementModifier modifier, String name, Codec<T> codec) {
        JsonElement encoded = PlacementModifier.CODEC.encodeStart(JsonOps.INSTANCE, modifier).getOrThrow();
        return codec.parse(JsonOps.INSTANCE, encoded.getAsJsonObject().get(name)).getOrThrow();
    }

    private static boolean drawsNothing(RuleTest test) {
        return test instanceof TagMatchTest || test instanceof BlockMatchTest || test instanceof BlockStateMatchTest;
    }

    private static void require(boolean condition, String name, String problem) {
        if (!condition) throw new IllegalStateException(name + " " + problem);
    }
}
