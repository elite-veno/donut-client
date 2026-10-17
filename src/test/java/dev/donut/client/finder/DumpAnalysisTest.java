package dev.donut.client.finder;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Studies chunk dumps saved by {@link ChunkDump} on a live server: which blocks its anti-xray hides, which chunks
 * place visible ores from the seed, and what lies on the debris positions the seed predicts. Runs only when
 * DONUT_DUMPS names a directory of dumps.
 */
class DumpAnalysisTest {
    private static final long SEED = 6608149111735331168L;
    private static final Set<String> OPEN = Set.of("air", "cave_air", "void_air", "lava", "fire", "soul_fire", "water");
    private static final List<String> CENSUS = List.of("ancient_debris", "nether_quartz_ore", "nether_gold_ore", "magma_block",
            "glowstone", "gravel", "blackstone", "soul_sand", "soul_soil", "basalt", "netherrack", "bone_block", "nether_bricks");

    private record Chunk(int x, int z, short[] blocks, short[] biomes) { }

    private record Dump(String name, BlockPos player, String[] palette, Map<Long, Chunk> chunks) {
        String block(int x, int y, int z) {
            if (y < 0 || y >= 128) return null;
            Chunk chunk = chunks.get(ChunkPos.pack(x >> 4, z >> 4));
            return chunk == null ? null : palette[chunk.blocks()[(y * 16 + (z & 15)) * 16 + (x & 15)]];
        }

        String biome(int x, int y, int z) {
            Chunk chunk = chunks.get(ChunkPos.pack(x >> 4, z >> 4));
            return palette[chunk.biomes()[((y >> 2) * 4 + ((z & 15) >> 2)) * 4 + ((x & 15) >> 2)]];
        }

        boolean exposed(int x, int y, int z) {
            int[][] offsets = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
            for (int[] o : offsets) {
                String neighbor = block(x + o[0], y + o[1], z + o[2]);
                if (neighbor != null && OPEN.contains(neighbor)) return true;
            }
            return false;
        }
    }

    @Test
    void analyze() throws IOException {
        String directory = System.getenv("DONUT_DUMPS");
        Assumptions.assumeTrue(directory != null, "DONUT_DUMPS is not set");
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        try (Stream<Path> files = Files.list(Path.of(directory))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".bin.gz")).sorted().toList()) analyze(read(file));
        }
    }

    private static Dump read(Path file) throws IOException {
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(Files.newInputStream(file)))) {
            if (!in.readUTF().equals("DONUTDUMP1")) throw new IOException("not a dump: " + file);
            BlockPos player = new BlockPos(in.readInt(), in.readInt(), in.readInt());
            String[] palette = new String[in.readInt()];
            for (int i = 0; i < palette.length; i++) palette[i] = in.readUTF();
            Map<Long, Chunk> chunks = new HashMap<>();
            int count = in.readInt();
            for (int c = 0; c < count; c++) {
                int x = in.readInt(), z = in.readInt();
                short[] blocks = new short[16 * 128 * 16], biomes = new short[4 * 32 * 4];
                for (int i = 0; i < blocks.length; i++) blocks[i] = in.readShort();
                for (int i = 0; i < biomes.length; i++) biomes[i] = in.readShort();
                chunks.put(ChunkPos.pack(x, z), new Chunk(x, z, blocks, biomes));
            }
            return new Dump(file.getFileName().toString(), player, palette, chunks);
        }
    }

    private static void analyze(Dump dump) {
        System.out.println("=== " + dump.name() + ": " + dump.chunks().size() + " chunks around " + dump.player().toShortString());
        census(dump);
        Map<Long, Integer> oreHits = oreMap(dump);
        debris(dump, oreHits);
    }

    /** Buried blocks of a type anti-xray hides are missing: the client sees them only where they touch open space. */
    private static void census(Dump dump) {
        Map<String, int[]> counts = new TreeMap<>();
        for (Chunk chunk : dump.chunks().values()) {
            for (int y = 1; y < 127; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        String name = dump.palette()[chunk.blocks()[(y * 16 + z) * 16 + x]];
                        if (!CENSUS.contains(name)) continue;
                        int[] c = counts.computeIfAbsent(name, k -> new int[2]);
                        c[0]++;
                        if (dump.exposed(chunk.x() * 16 + x, y, chunk.z() * 16 + z)) c[1]++;
                    }
                }
            }
        }
        StringBuilder line = new StringBuilder("census (total/exposed):");
        counts.forEach((name, c) -> line.append(' ').append(name).append(' ').append(c[0]).append('/').append(c[1]));
        System.out.println(line);
    }

    /** Gravel and blackstone blob centers that hold their block, per chunk, tried as their features place them. */
    private static Map<Long, Integer> oreMap(Dump dump) {
        Map<Long, Integer> hits = new HashMap<>();
        int[] total = new int[4];
        WorldgenRandom random = new WorldgenRandom(new XoroshiroRandomSource(0));
        NetherFeatures features = NetherFeatures.get().orElseThrow();
        for (Chunk chunk : dump.chunks().values()) {
            int own = ore(dump, chunk, random, SEED, features.gravel, total, 0) + ore(dump, chunk, random, SEED, features.blackstone, total, 0);
            ore(dump, chunk, random, SEED + 1, features.gravel, total, 2);
            ore(dump, chunk, random, SEED + 1, features.blackstone, total, 2);
            hits.put(ChunkPos.pack(chunk.x(), chunk.z()), own);
        }
        System.out.printf("gravel+blackstone centers: seed %d of %d, wrong seed %d of %d%n", total[1], total[0], total[3], total[2]);
        int minX = dump.chunks().values().stream().mapToInt(Chunk::x).min().orElse(0);
        int maxX = dump.chunks().values().stream().mapToInt(Chunk::x).max().orElse(0);
        int minZ = dump.chunks().values().stream().mapToInt(Chunk::z).min().orElse(0);
        int maxZ = dump.chunks().values().stream().mapToInt(Chunk::z).max().orElse(0);
        System.out.println("per-chunk ore hits (rows z " + minZ + ".." + maxZ + ", columns x " + minX + ".." + maxX + "):");
        for (int z = minZ; z <= maxZ; z++) {
            StringBuilder row = new StringBuilder("  ");
            for (int x = minX; x <= maxX; x++) {
                Integer h = hits.get(ChunkPos.pack(x, z));
                row.append(h == null ? ' ' : (char) ('0' + h));
            }
            System.out.println(row);
        }
        return hits;
    }

    private static int ore(Dump dump, Chunk chunk, WorldgenRandom random, long seed, NetherFeatures.Ore ore, int[] total, int slot) {
        int minX = chunk.x() * 16, minZ = chunk.z() * 16, hits = 0;
        String block = BuiltInRegistries.BLOCK.getKey(ore.block()).getPath();
        random.setFeatureSeed(random.setDecorationSeed(seed, minX, minZ), ore.index(), NetherFeatures.STEP);
        for (int placements = ore.count().sample(random); placements > 0; placements--) {
            int x = minX + random.nextInt(16), z = minZ + random.nextInt(16);
            int y = ore.y(random);
            String biome = dump.biome(x, y, z);
            if (ore.missingFrom().stream().anyMatch(key -> key.identifier().getPath().equals(biome))) continue;
            random.nextFloat();
            int low = random.nextInt(3) - 2, high = random.nextInt(3) - 2;
            for (int sphere = 0; sphere < ore.size(); sphere++) random.nextDouble();
            String center = dump.block(x, y + Math.floorDiv(low + high, 2), z);
            // A center dug out or flooded since says nothing either way.
            if (center == null || OPEN.contains(center)) continue;
            total[slot]++;
            if (block.equals(center)) { total[slot + 1]++; hits++; }
        }
        return hits;
    }

    /** A dug block joins a tunnel; a block anti-xray turned to air is a sealed pocket with no open side. */
    private static int openNeighbors(Dump dump, int x, int y, int z) {
        int open = 0;
        int[][] offsets = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int[] o : offsets) {
            String neighbor = dump.block(x + o[0], y + o[1], z + o[2]);
            if (neighbor != null && OPEN.contains(neighbor)) open++;
        }
        return open;
    }

    /** What the client got at each seed debris position, split by whether the chunk's visible ores follow the seed. */
    private static void debris(Dump dump, Map<Long, Integer> oreHits) {
        Map<String, Integer> seeded = new TreeMap<>(), unseeded = new TreeMap<>();
        DebrisPredictor predictor = new DebrisPredictor();
        for (Chunk chunk : dump.chunks().values()) {
            boolean follows = oreHits.getOrDefault(ChunkPos.pack(chunk.x(), chunk.z()), 0) >= 2;
            predictor.attempts(SEED, chunk.x(), chunk.z(), packed -> {
                int x = BlockPos.getX(packed), y = BlockPos.getY(packed), z = BlockPos.getZ(packed);
                String name = dump.block(x, y, z);
                if (name == null) return;
                String key = name + (OPEN.contains(name) ? " (" + openNeighbors(dump, x, y, z) + " open sides)"
                        : dump.exposed(x, y, z) ? " (exposed)" : " (buried)");
                (follows ? seeded : unseeded).merge(key, 1, Integer::sum);
            });
        }
        System.out.println("debris positions in chunks whose ores follow the seed: " + seeded);
        System.out.println("debris positions in other chunks: " + unseeded);
        // The same positions for a wrong seed: what the terrain holds at random.
        Map<String, Integer> control = new TreeMap<>();
        for (Chunk chunk : dump.chunks().values()) {
            predictor.attempts(SEED + 1, chunk.x(), chunk.z(), packed -> {
                int x = BlockPos.getX(packed), y = BlockPos.getY(packed), z = BlockPos.getZ(packed);
                String name = dump.block(x, y, z);
                if (name == null) return;
                String kind = OPEN.contains(name) ? name.equals("lava") ? "lava" : "open" : dump.exposed(x, y, z) ? "solid, exposed" : "solid, buried";
                control.merge(kind, 1, Integer::sum);
            });
        }
        System.out.println("wrong-seed debris positions: " + control);
    }
}
