package dev.donut.client.finder;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * Saves the loaded Nether chunks around a point, exactly as the server sent them, so a server's worldgen can be
 * studied offline. Blocks y 0 to 127 and biomes by 4x4x4 cell, as registry paths through a palette, gzipped:
 * "DONUTDUMP1", player x y z, palette size and names, chunk count, then per chunk its x and z, 16x128x16 block
 * indexes (y, then z, then x) and 4x32x4 biome indexes.
 */
public final class ChunkDump {
    private static final int HEIGHT = 128;

    private ChunkDump() { }

    public static Path write(Path directory, ClientLevel level, BlockPos player, int radius) throws IOException {
        ChunkPos center = ChunkPos.containing(player);
        List<LevelChunk> chunks = new ArrayList<>();
        for (int x = center.x() - radius; x <= center.x() + radius; x++) {
            for (int z = center.z() - radius; z <= center.z() + radius; z++) {
                LevelChunk chunk = level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
                if (chunk != null) chunks.add(chunk);
            }
        }
        Map<String, Integer> palette = new LinkedHashMap<>();
        List<short[]> blocks = new ArrayList<>(), biomes = new ArrayList<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (LevelChunk chunk : chunks) {
            int minX = chunk.getPos().getMinBlockX(), minZ = chunk.getPos().getMinBlockZ();
            short[] chunkBlocks = new short[16 * HEIGHT * 16];
            int i = 0;
            for (int y = 0; y < HEIGHT; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        var block = chunk.getBlockState(pos.set(minX + x, y, minZ + z)).getBlock();
                        chunkBlocks[i++] = index(palette, BuiltInRegistries.BLOCK.getKey(block).getPath());
                    }
                }
            }
            short[] chunkBiomes = new short[4 * (HEIGHT / 4) * 4];
            i = 0;
            for (int qy = 0; qy < HEIGHT / 4; qy++) {
                for (int qz = 0; qz < 4; qz++) {
                    for (int qx = 0; qx < 4; qx++) {
                        var biome = chunk.getNoiseBiome(QuartPos.fromBlock(minX) + qx, qy, QuartPos.fromBlock(minZ) + qz);
                        chunkBiomes[i++] = index(palette, biome.unwrapKey().map(key -> key.identifier().getPath()).orElse("unknown"));
                    }
                }
            }
            blocks.add(chunkBlocks);
            biomes.add(chunkBiomes);
        }

        Files.createDirectories(directory);
        Path file = directory.resolve("nether_%d_%d_%d.bin.gz".formatted(player.getX(), player.getZ(), System.currentTimeMillis() / 1000));
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(file)))) {
            out.writeUTF("DONUTDUMP1");
            out.writeInt(player.getX());
            out.writeInt(player.getY());
            out.writeInt(player.getZ());
            out.writeInt(palette.size());
            for (String name : palette.keySet()) out.writeUTF(name);
            out.writeInt(chunks.size());
            for (int c = 0; c < chunks.size(); c++) {
                out.writeInt(chunks.get(c).getPos().x());
                out.writeInt(chunks.get(c).getPos().z());
                for (short value : blocks.get(c)) out.writeShort(value);
                for (short value : biomes.get(c)) out.writeShort(value);
            }
        }
        return file;
    }

    private static short index(Map<String, Integer> palette, String name) {
        return (short) (int) palette.computeIfAbsent(name, key -> palette.size());
    }
}
