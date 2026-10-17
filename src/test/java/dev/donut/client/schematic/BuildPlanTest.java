package dev.donut.client.schematic;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class BuildPlanTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static int[] earlierNear(BuildPlan plan, BlockPos pos) {
        int[] near = plan.earlierNear(plan.indexOf(pos)).clone();
        Arrays.sort(near);
        return near;
    }

    @Test
    void laterGroupsWaitOnlyForEarlierBlocksCloseByInTheirLayer() {
        Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
        BlockPos stone = new BlockPos(0, 0, 0), farStone = new BlockPos(6, 0, 0), wire = new BlockPos(2, 0, 0);
        BlockPos piston = new BlockPos(3, 0, 1), above = new BlockPos(3, 1, 1);
        blocks.put(stone, Blocks.STONE.defaultBlockState());
        blocks.put(farStone, Blocks.STONE.defaultBlockState());
        blocks.put(wire, Blocks.REDSTONE_WIRE.defaultBlockState());
        blocks.put(piston, Blocks.PISTON.defaultBlockState());
        blocks.put(above, Blocks.STONE.defaultBlockState());
        BuildPlan plan = BuildPlan.of(blocks, true);

        assertArrayEquals(new int[0], earlierNear(plan, stone), "plain blocks wait for nothing");
        assertArrayEquals(new int[] {plan.indexOf(stone)}, earlierNear(plan, wire), "dust two blocks from stone waits for it");
        // The piston is a block from the dust and three from the near stone: only the dust is close enough; the stone in
        // the layer above goes in with its own layer.
        assertArrayEquals(new int[] {plan.indexOf(wire)}, earlierNear(plan, piston));

        BuildPlan anyOrder = BuildPlan.of(blocks, false);
        assertEquals(0, anyOrder.earlierNear(anyOrder.indexOf(piston)).length, "without redstone last nothing waits");
    }
}
