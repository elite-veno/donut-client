package dev.donut.client.finder;

import net.minecraft.core.BlockPos;

/**
 * @param flagged a spawner a player has been near, or a seed estimate of hidden debris; drawn in the finder's
 *                secondary color
 */
public record FoundBlock(BlockPos pos, TargetKind kind, boolean flagged) {
    public FoundBlock(BlockPos pos, TargetKind kind) { this(pos, kind, false); }
}
