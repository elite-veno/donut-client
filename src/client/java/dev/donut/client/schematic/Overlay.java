package dev.donut.client.schematic;

import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

/**
 * The level with a few blocks swapped for others, to work out looks before they are true: from a pit not dug yet, or
 * past a block not placed yet. Nothing in the level changes.
 */
final class Overlay implements BlockGetter {
    private final Level level;
    private final Map<Long, BlockState> changed;

    Overlay(Level level, Map<Long, BlockState> changed) {
        this.level = level;
        this.changed = changed;
    }

    /** Whether this view has its own block at {@code pos}. */
    boolean changes(BlockPos pos) { return changed.containsKey(pos.asLong()); }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        BlockState state = changed.get(pos.asLong());
        return state != null ? state : level.getBlockState(pos);
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        BlockState state = changed.get(pos.asLong());
        return state != null ? state.getFluidState() : level.getFluidState(pos);
    }

    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        return changed.containsKey(pos.asLong()) ? null : level.getBlockEntity(pos);
    }

    @Override
    public int getHeight() { return level.getHeight(); }

    @Override
    public int getMinY() { return level.getMinY(); }
}
