package dev.donut.client.miner;

import dev.donut.client.finder.LoadedChunks;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Fallable;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** The client's world as the miner sees it. */
public final class LevelSite implements Site {
    private final ClientLevel level;
    private final Entity viewer;
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

    public LevelSite(ClientLevel level, Entity viewer) {
        this.level = level;
        this.viewer = viewer;
    }

    @Override
    public int flags(int x, int y, int z) {
        cursor.set(x, y, z);
        if (!LoadedChunks.has(level, cursor)) return UNLOADED;
        return flags(level.getBlockState(cursor));
    }

    private int flags(BlockState state) {
        FluidState fluid = state.getFluidState();
        int flags = 0;
        if (!fluid.isEmpty()) {
            flags |= FLUID;
            if (fluid.is(FluidTags.LAVA)) flags |= LAVA;
        }
        boolean solid = !state.getCollisionShape(level, cursor).isEmpty();
        if (solid) flags |= SOLID;
        if (state.getBlock() instanceof Fallable) flags |= FALLS;
        if (state.isAir() || state.canBeReplaced() && !solid) {
            if (fluid.isEmpty()) flags |= CLEAR;
        } else if (fluid.isEmpty()) {
            // Ice broken over a block turns to water, so it is left like water.
            boolean unbreakable = state.getDestroySpeed(level, cursor) < 0 || state.is(Blocks.REINFORCED_DEEPSLATE)
                    || state.is(Blocks.ICE) || state.is(Blocks.FROSTED_ICE);
            // Blocks with contents, such as chests, spawners and sculk shriekers, are someone's or best left alone.
            flags |= unbreakable || state.hasBlockEntity() ? HARD : DIG;
        }
        return flags;
    }

    @Override
    public boolean sees(Vec3 eye, Vec3 point, BlockPos pos, Direction face) {
        Vec3 through = point.add(point.subtract(eye).normalize().scale(0.05));
        BlockHitResult hit = level.clip(new ClipContext(eye, through, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, viewer));
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos) && hit.getDirection() == face;
    }
}
