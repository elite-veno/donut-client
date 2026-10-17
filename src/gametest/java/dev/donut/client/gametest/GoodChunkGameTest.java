package dev.donut.client.gametest;

import dev.donut.client.config.ClientConfig;
import dev.donut.client.finder.ChunkEvents;
import dev.donut.client.finder.ChunkSign;
import dev.donut.client.finder.ChunkStats;
import dev.donut.client.finder.FinderScanner;
import dev.donut.client.finder.GoodChunk;
import dev.donut.client.render.HighlightRenderer;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CactusBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * ChunkFinder against real worldgen. Freshly generated chunks, a few hundred of them, must show no sign of players:
 * that checks what the plant clocks take worldgen to leave. Cactus random ticks grew before anyone came near must be a
 * sign that spots keep away from, and cactus placed and left alone must vouch for a spot.
 */
public final class GoodChunkGameTest implements FabricClientGameTest {
    /** DonutSMP's seed, so the plants are the kind its overworld has. */
    private static final String SEED = "6608149111735331168";
    /** Far apart, so every stop is ground nobody has been near. */
    private static final int[][] STOPS = {{0, 0}, {3000, 0}, {-3000, 1500}, {1500, -3000}, {-1500, -3000}};
    /** Pads of cactus: one grown by random ticks before the player arrives, one left as placed. */
    private static final int GROWN_X = 9000, GROWN_Z = 9000, FRESH_X = -9000, FRESH_Z = 9000, PAD_Y = 230;

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientConfig config = new ClientConfig();
        config.chunks.enabled = true;
        config.chunks.notify = false;
        FinderScanner scanner = new FinderScanner(config);
        AtomicBoolean active = new AtomicBoolean(true);
        int renderDistance = context.computeOnClient(client -> client.options.renderDistance().get());
        HighlightRenderer renderer = context.computeOnClient(client -> {
            client.options.renderDistance().set(12);
            HighlightRenderer created = new HighlightRenderer(config, scanner);
            created.register();
            ClientTickEvents.END_CLIENT_TICK.register(mc -> {
                if (active.get()) scanner.tick(mc);
            });
            return created;
        });
        context.getInput().resizeWindow(1280, 800);

        try (var world = context.worldBuilder().setUseConsistentSettings(false)
                .adjustSettings(creator -> creator.setSeed(SEED)).create()) {
            TestServerContext server = world.getServer();
            // Frozen: nothing may grow while fresh chunks are read.
            server.runCommand("gamerule random_tick_speed 0");
            server.runCommand("gamemode spectator @a");
            int clearance = (int) Math.round(config.chunks.minClearance);

            Map<ChunkPos, String> signs = new HashMap<>();
            int scanned = 0, withPlants = 0, spots = 0;
            for (int[] stop : STOPS) {
                teleport(context, server, stop[0], 120, stop[1]);
                settle(context, scanner);
                ChunkStats stats = context.computeOnClient(client -> scanner.chunkStats());
                List<GoodChunk> found = context.computeOnClient(client -> scanner.goodChunks());
                context.runOnClient(client -> {
                    for (ChunkSign sign : scanner.chunkSigns()) signs.put(sign.chunk(), sign.reason());
                });
                scanned += stats.scanned();
                withPlants += stats.withClocks();
                spots += found.size();
                log("stop %d,%d: %d chunks, %d with plants, %d signs, %d spots%s", stop[0], stop[1], stats.scanned(),
                        stats.withClocks(), stats.signs(), found.size(), found.isEmpty() ? "" : ", best " + describe(found.getFirst()));
            }
            log("fresh worldgen: %d chunks read, %d with plants, %d spots, signs %s", scanned, withPlants, spots, signs);
            require(scanned >= 2000, "Only " + scanned + " chunks were read");
            require(withPlants >= 50, "Too few chunks with plants to judge: " + withPlants);
            require(signs.isEmpty(), "Freshly generated chunks showed signs of players: " + signs);
            require(spots > 0, "No spot was found on fresh ground");

            // Someone stays near a cactus pad for a while before we come: random ticks age every top.
            buildPad(context, server, GROWN_X, GROWN_Z);
            server.runCommand("gamerule random_tick_speed 1000");
            context.waitTicks(60);
            server.runCommand("gamerule random_tick_speed 0");
            server.runCommand("forceload remove all");
            buildPad(context, server, FRESH_X, FRESH_Z);
            server.runCommand("forceload remove all");

            ChunkPos grown = ChunkPos.containing(new BlockPos(GROWN_X, PAD_Y, GROWN_Z));
            teleport(context, server, GROWN_X, PAD_Y + 4, GROWN_Z);
            // Not waitFor, which would fail before the pad's cacti are logged.
            for (int second = 0; second < 60; second++) {
                context.waitTicks(20);
                if (context.computeOnClient(client -> scanner.chunkSigns().stream().anyMatch(sign -> sign.chunk().equals(grown)))) break;
            }
            settle(context, scanner);
            String reason = context.computeOnClient(client -> scanner.chunkSigns().stream()
                    .filter(sign -> sign.chunk().equals(grown)).map(ChunkSign::reason).findFirst().orElse(""));
            List<GoodChunk> nearGrown = context.computeOnClient(client -> scanner.goodChunks());
            log("grown pad: %s, cactus ages %s; %d spots, best %s", reason, cactusAges(context, GROWN_X, GROWN_Z), nearGrown.size(),
                    nearGrown.isEmpty() ? "none" : describe(nearGrown.getFirst()));
            require(reason.startsWith("Cactus aged"), "The grown pad's chunk was not read as cactus growth: " + reason);
            for (GoodChunk spot : nearGrown) {
                int distance = distance(spot.chunk(), grown);
                require(distance > clearance, "A spot " + distance + " chunks from the grown pad: " + describe(spot));
            }
            overlook(context, server, GROWN_X, GROWN_Z, "donut-chunkfinder-grown-pad");

            ChunkPos fresh = ChunkPos.containing(new BlockPos(FRESH_X, PAD_Y, FRESH_Z));
            teleport(context, server, FRESH_X, PAD_Y + 4, FRESH_Z);
            context.waitFor(client -> scanner.bestGoodChunk() != null, 1200);
            settle(context, scanner);
            GoodChunk best = context.computeOnClient(client -> scanner.bestGoodChunk());
            boolean freshSign = context.computeOnClient(client -> scanner.chunkSigns().stream().anyMatch(sign -> sign.chunk().equals(fresh)));
            log("fresh pad: best %s", best == null ? "none" : describe(best));
            require(!freshSign, "The untouched pad was read as a sign");
            require(best != null && distance(best.chunk(), fresh) <= clearance && best.freshClocks() >= 49,
                    "The untouched pad did not vouch for a spot: " + (best == null ? "none" : describe(best)));
            overlook(context, server, FRESH_X, FRESH_Z, "donut-chunkfinder-fresh-pad");
        } finally {
            active.set(false);
            context.runOnClient(client -> {
                client.options.renderDistance().set(renderDistance);
                renderer.close();
                scanner.clear();
                ChunkEvents.unregister(scanner);
            });
        }
    }

    private static void teleport(ClientGameTestContext context, TestServerContext server, int x, int y, int z) {
        server.runCommand("execute in minecraft:overworld run tp @a %d %d %d".formatted(x, y, z));
        context.waitFor(client -> client.player != null && client.level != null && client.level.dimension() == Level.OVERWORLD
                && Math.abs(client.player.getX() - x) < 2 && Math.abs(client.player.getZ() - z) < 2, 400);
    }

    /** Sand on stone with 49 single cacti two blocks apart, high in the air in one chunk, loaded by forceload while nobody is near. */
    private static void buildPad(ClientGameTestContext context, TestServerContext server, int x, int z) {
        server.runCommand("forceload add %d %d %d %d".formatted(x - 16, z - 16, x + 16, z + 16));
        // Generating the chunks takes a moment; blocks can only be set once they are loaded.
        context.waitTicks(100);
        // Stone under the sand, which would fall otherwise and take the cacti with it.
        server.runCommand("execute in minecraft:overworld run fill %d %d %d %d %d %d minecraft:stone".formatted(
                x - 6, PAD_Y - 1, z - 6, x + 6, PAD_Y - 1, z + 6));
        server.runCommand("execute in minecraft:overworld run fill %d %d %d %d %d %d minecraft:sand".formatted(
                x - 6, PAD_Y, z - 6, x + 6, PAD_Y, z + 6));
        for (int dx = -6; dx <= 6; dx += 2) {
            for (int dz = -6; dz <= 6; dz += 2) {
                server.runCommand("execute in minecraft:overworld run setblock %d %d %d minecraft:cactus".formatted(x + dx, PAD_Y + 1, z + dz));
            }
        }
        context.waitTicks(5);
    }

    /** A look at the pad from above and behind, with the marks drawn, saved as a screenshot. */
    private static void overlook(ClientGameTestContext context, TestServerContext server, int x, int z, String name) {
        server.runCommand("execute in minecraft:overworld run tp @a %d %d %d -45 35".formatted(x - 40, PAD_Y + 30, z - 40));
        context.waitTicks(60);
        context.takeScreenshot(TestScreenshotOptions.of(name).withSize(1280, 800));
    }

    /** The pad's cacti as the client has them: each top's age, with a mark for one that grew a block. */
    private static String cactusAges(ClientGameTestContext context, int x, int z) {
        return context.computeOnClient(client -> {
            StringBuilder ages = new StringBuilder();
            for (int dx = -6; dx <= 6; dx += 2) {
                for (int dz = -6; dz <= 6; dz += 2) {
                    for (int dy = 3; dy >= 1; dy--) {
                        BlockState state = client.level.getBlockState(new BlockPos(x + dx, PAD_Y + dy, z + dz));
                        if (!state.is(Blocks.CACTUS)) continue;
                        ages.append(state.getValue(CactusBlock.AGE)).append(dy > 1 ? "^ " : " ");
                        break;
                    }
                }
            }
            return ages.toString().strip();
        });
    }

    /** Waits until the read chunk count holds still for three seconds, up to a minute and a half. */
    private static void settle(ClientGameTestContext context, FinderScanner scanner) {
        int last = -1, same = 0;
        for (int second = 0; second < 90 && same < 3; second++) {
            context.waitTicks(20);
            int now = context.computeOnClient(client -> scanner.chunkStats().scanned());
            same = now == last && now > 0 ? same + 1 : 0;
            last = now;
        }
    }

    private static int distance(ChunkPos a, ChunkPos b) { return Math.max(Math.abs(a.x() - b.x()), Math.abs(a.z() - b.z())); }

    private static String describe(GoodChunk spot) {
        return String.format(Locale.ROOT, "%s clear %d, %d plants, unseen %.1f min, confidence %.3f", spot.chunk(), spot.clearance(),
                spot.freshClocks(), spot.unseenMinutes(), spot.confidence());
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void log(String format, Object... args) {
        System.out.println("[GoodChunkGameTest] " + String.format(Locale.ROOT, format, args));
    }
}
