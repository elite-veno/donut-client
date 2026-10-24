package dev.donut.client.gametest;

import dev.donut.client.config.ClientConfig;
import dev.donut.client.finder.ActivityArea;
import dev.donut.client.finder.ChunkEvents;
import dev.donut.client.finder.FinderScanner;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

/**
 * ActivityFinder against real worldgen instead of hand-built geodes. The amethyst of freshly generated
 * chunks, across a few hundred of them, must not be marked; and a real geode that random ticks have grown,
 * as they do near a base, must be found and pass Auto RTP's threshold once the scan sees the growth.
 */
public final class ActivityWorldgenGameTest implements FabricClientGameTest {
    /** DonutSMP's seed, so the geodes are the kind its overworld has. */
    private static final String SEED = "6608149111735331168";
    /** Far enough apart that every stop is ground nobody has been near. */
    private static final int[][] STOPS = {{0, 0}, {2400, 0}, {-2400, 0}, {0, 2400}, {0, -2400}, {2400, 2400}};
    /** Auto RTP stops for a chunk at or above this. */
    private static final double AUTO_RTP_THRESHOLD = 0.55;

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientConfig config = new ClientConfig();
        config.activity.enabled = true;
        config.activity.distanceFade = false;
        config.activity.allLoadedChunks = true;
        // Every chunk with amethyst is reported, whatever it scores.
        config.activity.minActivity = 0;
        FinderScanner scanner = new FinderScanner(config);
        AtomicBoolean active = new AtomicBoolean(true);
        // The test client's default of 5 would show about a hundred chunks per stop; 12 shows several hundred.
        int renderDistance = context.computeOnClient(client -> client.options.renderDistance().get());
        context.runOnClient(client -> {
            client.options.renderDistance().set(12);
            ClientTickEvents.END_CLIENT_TICK.register(mc -> {
                if (active.get()) scanner.tick(mc);
            });
        });

        try (var world = context.worldBuilder().setUseConsistentSettings(false)
                .adjustSettings(creator -> creator.setSeed(SEED)).create()) {
            TestServerContext server = world.getServer();
            // Frozen: no bud may grow while the fresh chunks are being read.
            server.runCommand("gamerule random_tick_speed 0");
            server.runCommand("gamemode spectator @a");

            Map<ChunkPos, ActivityArea> fresh = new HashMap<>();
            for (int[] stop : STOPS) {
                server.runCommand("execute in minecraft:overworld run tp @a %d 120 %d".formatted(stop[0], stop[1]));
                context.waitFor(client -> client.player != null && client.level != null
                        && client.level.dimension() == Level.OVERWORLD
                        && Math.abs(client.player.getX() - stop[0]) < 2 && Math.abs(client.player.getZ() - stop[1]) < 2, 400);
                // Not waitForChunksRender: drawing hundreds of chunks outlasts its timeout, and the scan needs none drawn.
                int scanned = settle(context, scanner);
                require(scanned >= 200, "Only " + scanned + " chunks were scanned at " + stop[0] + "," + stop[1]);
                // A few rescans' worth, so edge chunks are read again once their neighbors arrived.
                context.waitTicks(100);
                int before = fresh.size();
                context.runOnClient(client -> {
                    for (ActivityArea area : scanner.activity()) fresh.merge(area.chunk(), area, (a, b) -> b);
                });
                log("stop %d,%d: %d chunks scanned, %d with amethyst here, %d so far",
                        stop[0], stop[1], scanner.activityStats().scannedChunks(), fresh.size() - before, fresh.size());
            }

            long budding = 0, faces = 0, stages = 0;
            int marked = 0, overThreshold = 0;
            ActivityArea worst = null;
            for (ActivityArea area : fresh.values()) {
                budding += area.budding();
                faces += area.faces();
                stages += area.stages();
                if (area.score() > 0) marked++;
                if (area.score() >= AUTO_RTP_THRESHOLD) overThreshold++;
                if (worst == null || area.score() > worst.score()) worst = area;
            }
            log("fresh worldgen: %d chunks with amethyst, %d budding, %.3f stages per budding block (model 0.875), "
                            + "%.2f open faces per budding block, %d scored above 0, %d at Auto RTP's threshold, highest %s",
                    fresh.size(), budding, budding == 0 ? 0 : (double) stages / budding,
                    budding == 0 ? 0 : (double) faces / budding, marked, overThreshold, worst);
            require(fresh.size() >= 40, "Too few geodes to judge: " + fresh.size() + " chunks with amethyst");
            // Worldgen leaves one bud on about a third of the budding blocks; the model expects 0.875 stages each.
            double perBlock = (double) stages / budding;
            require(perBlock > 0.6 && perBlock < 1.15, "Worldgen bud stages per budding block " + perBlock + " are off the model's 0.875");
            require(overThreshold == 0, overThreshold + " freshly generated chunks reached Auto RTP's threshold; highest " + worst);
            // One in ten thousand by design; a few hundred chunks should give none.
            require(marked <= 1, marked + " freshly generated chunks were marked as active; highest " + worst);

            // A base: someone stays by the richest geode while random ticks grow it.
            ActivityArea target = fresh.values().stream().max(Comparator.comparingInt(ActivityArea::budding)).orElseThrow();
            ChunkPos chunk = target.chunk();
            server.runCommand("gamemode creative @a");
            server.runCommand("execute in minecraft:overworld run tp @a %d %d %d".formatted(
                    chunk.getMiddleBlockX(), Math.max(target.surfaceY(), target.maxY()) + 2, chunk.getMiddleBlockZ()));
            context.waitFor(client -> client.player != null
                    && client.player.chunkPosition().equals(chunk), 400);
            context.waitFor(client -> scanner.activity().stream().anyMatch(area -> area.chunk().equals(chunk)), 1200);
            double start = score(context, scanner, chunk);
            log("growing %s from %.2f (%d budding, %d open faces, %d stages)", chunk, start, target.budding(),
                    target.faces(), target.stages());
            // Several hours of random ticks in seconds: each block gets a couple of hundred, so most open
            // faces end up with a cluster, as by a base someone has lived at for a while.
            server.runCommand("gamerule random_tick_speed 3000");
            context.waitTicks(300);
            server.runCommand("gamerule random_tick_speed 0");
            // The scan must notice the growth by itself, without the chunk being reloaded.
            ActivityArea grown = null;
            for (int second = 0; second < 40; second++) {
                context.waitTicks(20);
                grown = context.computeOnClient(client -> scanner.activity().stream()
                        .filter(area -> area.chunk().equals(chunk)).findFirst().orElse(null));
                if (grown != null && grown.score() >= AUTO_RTP_THRESHOLD) break;
            }
            log("after growth: %s", grown);
            require(grown != null && grown.stages() > target.stages(), "No growth was seen: " + grown);
            require(grown.score() >= AUTO_RTP_THRESHOLD, "Grown geode stayed under Auto RTP's threshold: " + grown);
            require(grown.chance() < 1e-6, "Grown geode not told apart from worldgen strongly enough: " + grown);
        } finally {
            active.set(false);
            context.runOnClient(client -> {
                client.options.renderDistance().set(renderDistance);
                scanner.clear();
                ChunkEvents.unregister(scanner);
            });
        }
    }

    /** Waits until the scanned chunk count holds still for three seconds, up to a minute and a half; returns it. */
    private static int settle(ClientGameTestContext context, FinderScanner scanner) {
        int last = -1, same = 0;
        for (int second = 0; second < 90 && same < 3; second++) {
            context.waitTicks(20);
            int now = context.computeOnClient(client -> scanner.activityStats().scannedChunks());
            same = now == last && now > 0 ? same + 1 : 0;
            last = now;
        }
        return last;
    }

    private static double score(ClientGameTestContext context, FinderScanner scanner, ChunkPos chunk) {
        return context.computeOnClient(client -> scanner.activity().stream().filter(area -> area.chunk().equals(chunk))
                .mapToDouble(ActivityArea::score).max().orElse(-1));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void log(String format, Object... args) {
        System.out.println("[ActivityWorldgenGameTest] " + String.format(Locale.ROOT, format, args));
    }
}
