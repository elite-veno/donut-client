package dev.donut.client.gametest;

import dev.donut.client.config.ClientConfig;
import dev.donut.client.finder.ChunkDump;
import dev.donut.client.finder.ChunkEvents;
import dev.donut.client.finder.DebrisStats;
import dev.donut.client.finder.FinderScanner;
import dev.donut.client.finder.FoundBlock;
import dev.donut.client.finder.FeatureProbe;
import dev.donut.client.render.HighlightRenderer;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Generates the Nether from the DonutSMP seed and checks DebrisFinder's seed estimates against the debris
 * worldgen really placed. Anti-xray is imitated on the client by turning buried debris into netherrack.
 * A dedicated server runs it the way DonutSMP does, from the seed typed in the settings; singleplayer
 * must use its own seed instead.
 */
public final class DebrisSeedGameTest implements FabricClientGameTest {
    private static final String SEED = "6608149111735331168";
    private static final double RANGE = 128;

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientConfig config = new ClientConfig();
        config.debris.enabled = true;
        config.debris.distanceFade = false;
        config.debris.range = RANGE;
        FinderScanner scanner = new FinderScanner(config);
        AtomicBoolean active = new AtomicBoolean(true);
        HighlightRenderer renderer = context.computeOnClient(client -> {
            client.options.guiScale().set(1);
            HighlightRenderer created = new HighlightRenderer(config, scanner);
            created.register();
            ClientTickEvents.END_CLIENT_TICK.register(mc -> { if (active.get()) scanner.tick(mc); });
            return created;
        });
        context.getInput().resizeWindow(1280, 800);

        try {
            context.runOnClient(client -> config.debris.seed = SEED);
            try (var server = context.worldBuilder().adjustSettings(creator -> creator.setSeed(SEED)).createServer();
                 var connection = server.connect()) {
                verify(context, server, connection, scanner, "server");
                // A wrong seed is caught by the debris the server shows.
                context.runOnClient(client -> config.debris.seed = "12345");
                // Every chunk reads as generated another way, so no real debris counts against the seed.
                context.waitFor(client -> scanner.debrisStats().bedrockMismatch() && scanner.debrisStats().offSeedChunks() > 100, 400);
                DebrisStats wrong = scanner.debrisStats();
                log("server, wrong seed: seed check %d of %d, bedrock %d of %d, chunks on seed %d, off seed %d, %d estimates",
                        wrong.seedMatched(), wrong.seedChecked(), wrong.bedrockMatched(), wrong.bedrockChecked(), wrong.seedChunks(),
                        wrong.offSeedChunks(), wrong.estimates());
                // Ores off the seed's positions keep a wrong seed's estimates from showing.
                require(wrong.estimates() == 0, "A wrong seed still drew estimates: " + wrong.estimates());
                screenshot(context, "donut-debris-seed-server-03-wrong-seed");
            }

            context.runOnClient(client -> config.debris.seed = "not this world's seed");
            try (var world = context.worldBuilder().adjustSettings(creator -> creator.setSeed(SEED)).create()) {
                verify(context, world.getServer(), world.getConnection(), scanner, "singleplayer");
            }
        } finally {
            active.set(false);
            context.runOnClient(client -> {
                scanner.clear();
                renderer.close();
                ChunkEvents.unregister(scanner);
            });
        }
    }

    private static void verify(ClientGameTestContext context, TestServerContext server, TestServerConnection clientWorld,
                               FinderScanner scanner, String name) {
        server.runCommand("gamemode spectator @a");
        server.runCommand("execute in minecraft:the_nether run tp @a 8.5 20 8.5 0 25");
        context.waitFor(client -> client.level != null && client.level.dimension() == Level.NETHER, 400);
        clientWorld.waitForChunksRender();
        context.waitFor(client -> scanner.debrisStats().nether() && scanner.debrisStats().scannedChunks() >= 150, 1200);
        context.waitTicks(80);

        // Exposed debris is real; every block of it must lie where the seed says.
        DebrisStats plain = scanner.debrisStats();
        log("%s: %d chunks, %d buried, %d exposed, seed check %d of %d, bedrock %d of %d, chunks on seed %d, off seed %d, %d estimates",
                name, plain.scannedChunks(), plain.buried(), plain.exposed(), plain.seedMatched(), plain.seedChecked(),
                plain.bedrockMatched(), plain.bedrockChecked(), plain.seedChunks(), plain.offSeedChunks(), plain.estimates());
        require(plain.seedChunks() > 100 && plain.offSeedChunks() == 0,
                name + ": ores read as off the seed in " + plain.offSeedChunks() + " chunks, on it in " + plain.seedChunks());
        require(plain.seeded() && plain.seedChecked() > 0 && plain.seedMatched() == plain.seedChecked(),
                name + ": exposed debris off the seed positions: " + plain.seedMatched() + " of " + plain.seedChecked());
        // Bedrock comes from the seed alone, so all of it must match.
        require(plain.bedrockReady() && plain.bedrockMatched() == plain.bedrockChecked(),
                name + ": bedrock off the seed's pattern: " + plain.bedrockMatched() + " of " + plain.bedrockChecked());
        // Magma and glowstone, which anti-xray never hides, tell whether a server places Nether features from the seed.
        FeatureProbe.Result features = context.computeOnClient(client ->
                FeatureProbe.run(client.level, client.player.chunkPosition(), 6, Long.parseLong(SEED)));
        log("%s: %s", name, features.describe());
        require(features.seedPlacesFeatures(), name + ": features off the seed: " + features.describe());
        // A reference for studying dumps from live servers.
        context.runOnClient(client -> {
            try {
                ChunkDump.write(FabricLoader.getInstance().getGameDir().resolve("donut-dumps"), client.level, client.player.blockPosition(), 6);
            } catch (java.io.IOException e) {
                throw new AssertionError("Could not save the reference dump", e);
            }
        });
        screenshot(context, "donut-debris-seed-" + name + "-01-plain");

        // Anti-xray engine mode 1: buried debris arrives as netherrack.
        Set<BlockPos> hidden = context.computeOnClient(client -> hideBuriedDebris(client.level, client.player.chunkPosition()));
        require(!hidden.isEmpty(), name + ": no buried debris to hide");
        context.waitFor(client -> scanner.debrisStats().buried() == 0, 200);
        context.waitTicks(80);

        context.runOnClient(client -> {
            Vec3 origin = client.player.position();
            Set<BlockPos> estimates = scanner.snapshot().stream().filter(t -> t.kind().isDebris() && t.flagged())
                    .map(FoundBlock::pos).collect(Collectors.toSet());
            Set<BlockPos> inRange = hidden.stream().filter(pos -> pos.distToCenterSqr(origin) <= RANGE * RANGE)
                    .collect(Collectors.toSet());
            Set<BlockPos> found = new HashSet<>(inRange);
            found.retainAll(estimates);
            DebrisStats stats = scanner.debrisStats();
            log("%s, anti-xray: %d hidden in range, %d marked, %d estimates (%.0f%% are debris), seed check %d of %d", name,
                    inRange.size(), found.size(), estimates.size(), 100.0 * found.size() / Math.max(1, estimates.size()),
                    stats.seedMatched(), stats.seedChecked());
            // The client keeps debris in a section's palette after the swap, as Paper's anti-xray leaves it, unless the
            // swap adds a block type the palette lacked: then it rebuilds the palette from what is left. Paper never does.
            // A section gets a search area only where no estimate already pinpoints its debris.
            Set<Long> areas = scanner.debrisAreas().stream().map(SectionPos::asLong).collect(Collectors.toSet());
            Set<Long> pinpointed = estimates.stream().map(SectionPos::asLong).collect(Collectors.toSet());
            long unflagged = inRange.stream().filter(pos -> pos.distToCenterSqr(origin) <= (RANGE - 14) * (RANGE - 14)
                    && !areas.contains(SectionPos.asLong(pos)) && !pinpointed.contains(SectionPos.asLong(pos))).count();
            log("%s, anti-xray: %d search areas, %d hidden debris in neither an area nor an estimated section", name, areas.size(), unflagged);
            require(unflagged * 33 <= inRange.size(), name + ": hidden debris in neither a search area nor an estimate: " + unflagged);
            require(found.size() * 33 >= inRange.size() * 32, name + ": hidden debris without an estimate: "
                    + inRange.stream().filter(pos -> !estimates.contains(pos)).limit(5).toList());
            require(found.size() == estimates.size(), name + ": estimates on blocks that are not debris: " + (estimates.size() - found.size()));
            require(!stats.seedMismatch(), name + ": the right seed was reported as wrong");
        });
        screenshot(context, "donut-debris-seed-" + name + "-02-anti-xray");
    }

    /** Turns every buried debris block in the loaded chunks around the player into netherrack, on this client only. */
    private static Set<BlockPos> hideBuriedDebris(ClientLevel level, ChunkPos center) {
        Set<BlockPos> buried = new HashSet<>();
        int radius = 10;
        for (int cx = center.x() - radius; cx <= center.x() + radius; cx++) {
            for (int cz = center.z() - radius; cz <= center.z() + radius; cz++) {
                if (!level.hasChunk(cx, cz)) continue;
                for (BlockPos pos : BlockPos.betweenClosed(cx * 16, 0, cz * 16, cx * 16 + 15, 127, cz * 16 + 15)) {
                    if (level.getBlockState(pos).is(Blocks.ANCIENT_DEBRIS) && buried(level, pos)) buried.add(pos.immutable());
                }
            }
        }
        // Without update flags no block change fires, as when Paper sends a chunk already disguised: the section's
        // palette keeps listing debris, and DebrisFinder sees the swap only when it rescans the chunk.
        for (BlockPos pos : buried) level.setBlock(pos, Blocks.NETHERRACK.defaultBlockState(), 0);
        return buried;
    }

    /** Anti-xray leaves a block alone when any neighbor lets light or redstone through, as DebrisFinder assumes. */
    private static boolean buried(ClientLevel level, BlockPos pos) {
        for (Direction direction : Direction.values()) {
            BlockPos neighbor = pos.relative(direction);
            if (!level.getBlockState(neighbor).isRedstoneConductor(level, neighbor)) return false;
        }
        return true;
    }

    private static void log(String format, Object... args) {
        System.out.println("[DebrisSeedGameTest] " + format.formatted(args));
    }

    private static void screenshot(ClientGameTestContext context, String name) {
        context.takeScreenshot(TestScreenshotOptions.of(name).withSize(1280, 800));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
