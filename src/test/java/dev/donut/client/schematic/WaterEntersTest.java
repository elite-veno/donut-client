package dev.donut.client.schematic;

import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Where running water goes, for the run a water source waits on (da-59, da-62). */
class WaterEntersTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void openCellsAndWhatWaterWashesAwayLetItIn() {
        assertTrue(SchematicBuilder.waterEnters(Blocks.AIR.defaultBlockState()));
        assertTrue(SchematicBuilder.waterEnters(Blocks.WATER.defaultBlockState()));
        assertTrue(SchematicBuilder.waterEnters(Blocks.REDSTONE_WIRE.defaultBlockState()), "washed away");
        assertTrue(SchematicBuilder.waterEnters(Blocks.SHORT_GRASS.defaultBlockState()), "washed away");
    }

    @Test
    void blocksThatHoldWaterThemselvesOrStopItKeepItOut() {
        assertFalse(SchematicBuilder.waterEnters(Blocks.GLOW_LICHEN.defaultBlockState()), "the crafter's 40,67,24");
        assertFalse(SchematicBuilder.waterEnters(Blocks.OAK_SLAB.defaultBlockState()));
        assertFalse(SchematicBuilder.waterEnters(Blocks.OAK_DOOR.defaultBlockState()));
        assertFalse(SchematicBuilder.waterEnters(Blocks.LADDER.defaultBlockState()));
        assertFalse(SchematicBuilder.waterEnters(Blocks.GLASS.defaultBlockState()));
        assertFalse(SchematicBuilder.waterEnters(Blocks.HOPPER.defaultBlockState()));
    }
}
