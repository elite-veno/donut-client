package dev.donut.client.finder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.Level;

/**
 * Whether a block's chunk has arrived. {@code ClientLevel.hasChunk}, and with it {@code hasChunkAt}, always answers
 * true in 26.2, and an unloaded block then reads as void air; the chunk cache knows.
 */
public final class LoadedChunks {
    private LoadedChunks() { }

    public static boolean has(Level level, BlockPos pos) {
        return level.getChunkSource().hasChunk(SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ()));
    }
}
