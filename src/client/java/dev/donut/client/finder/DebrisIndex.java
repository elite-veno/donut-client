package dev.donut.client.finder;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.Vec3;

/** Ancient debris has no block entity, so loaded chunk sections are searched directly. */
final class DebrisIndex {
    private static final Predicate<BlockState> DEBRIS = state -> state.is(Blocks.ANCIENT_DEBRIS);

    private final ChunkIndex<List<FoundBlock>> chunks = new ChunkIndex<>(DebrisIndex::find, 8);

    void clear() { chunks.clear(); }

    void tick(ClientLevel level, ChunkPos center, double range) { chunks.tick(level, center, range); }

    void collect(ClientLevel level, Vec3 origin, double range, List<FoundBlock> found) {
        double rangeSqr = range * range;
        chunks.forEach(debris -> {
            for (FoundBlock block : debris) {
                // Cached hits are rechecked so mined debris disappears before its chunk is rescanned.
                if (block.pos().distToCenterSqr(origin) <= rangeSqr && DEBRIS.test(level.getBlockState(block.pos()))) {
                    found.add(block);
                }
            }
        });
    }

    private static List<FoundBlock> find(LevelChunk chunk) {
        List<FoundBlock> found = new ArrayList<>();
        LevelChunkSection[] sections = chunk.getSections();
        int minX = chunk.getPos().getMinBlockX(), minZ = chunk.getPos().getMinBlockZ();
        for (int index = 0; index < sections.length; index++) {
            LevelChunkSection section = sections[index];
            // The palette check skips almost every section without reading its blocks.
            if (section.hasOnlyAir() || !section.maybeHas(DEBRIS)) continue;
            int minY = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(index));
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        if (DEBRIS.test(section.getBlockState(x, y, z))) {
                            found.add(new FoundBlock(new BlockPos(minX + x, minY + y, minZ + z), TargetKind.ANCIENT_DEBRIS));
                        }
                    }
                }
            }
        }
        return found.isEmpty() ? List.of() : List.copyOf(found);
    }
}
