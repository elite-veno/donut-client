package dev.donut.client.finder;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.data.worldgen.placement.OrePlacements;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.levelgen.GenerationStep;
import org.junit.jupiter.api.Test;

class DebrisPredictorTest {
    /** Sorts the Nether's features the way its chunk generator does, so a game update that reorders them fails here. */
    @Test
    void featureIndexesMatchTheNethersFeatureOrder() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        HolderLookup.Provider lookup = VanillaRegistries.createLookup();
        var nether = MultiNoiseBiomeSource.createFromPreset(lookup.lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
                .getOrThrow(MultiNoiseBiomeSourceParameterLists.NETHER));
        List<FeatureSorter.StepFeatureData> steps = FeatureSorter.buildFeaturesPerStep(List.copyOf(nether.possibleBiomes()),
                biome -> biome.value().getGenerationSettings().features(), true);
        var step = steps.get(GenerationStep.Decoration.UNDERGROUND_DECORATION.ordinal());
        var placed = lookup.lookupOrThrow(Registries.PLACED_FEATURE);
        int large = step.indexMapping().applyAsInt(placed.getOrThrow(OrePlacements.ORE_ANCIENT_DEBRIS_LARGE).value());
        int small = step.indexMapping().applyAsInt(placed.getOrThrow(OrePlacements.ORE_ANCIENT_DEBRIS_SMALL).value());
        // Expected first: the vanilla registries are the truth the constants must match.
        assertEquals(large, DebrisPredictor.LARGE_INDEX);
        assertEquals(small, DebrisPredictor.SMALL_INDEX);
        assertEquals(step.indexMapping().applyAsInt(placed.getOrThrow(OrePlacements.ORE_MAGMA).value()), FeatureProbe.MAGMA.index());
        assertEquals(step.indexMapping().applyAsInt(placed.getOrThrow(OrePlacements.ORE_GRAVEL_NETHER).value()), FeatureProbe.GRAVEL.index());
        assertEquals(step.indexMapping().applyAsInt(placed.getOrThrow(OrePlacements.ORE_BLACKSTONE).value()), FeatureProbe.BLACKSTONE.index());
    }
}
