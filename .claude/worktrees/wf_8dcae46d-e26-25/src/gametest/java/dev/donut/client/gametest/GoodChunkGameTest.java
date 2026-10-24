package dev.donut.client.gametest;

import dev.donut.client.config.ClientConfig;
import dev.donut.client.finder.ChunkEvents;
import dev.donut.client.finder.Caves;
import dev.donut.client.finder.ChunkSign;
import dev.donut.client.finder.ChunkStats;
import dev.donut.client.finder.FinderScanner;
import dev.donut.client.finder.GoodChunk;
import dev.donut.client.render.HighlightRenderer;
import java.util.ArrayList;
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
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CactusBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * ChunkFinder against real worldgen. Freshly generated chunks, a few thousand of them, must show no sign of players,
 * only structures: that checks what the plant clocks take worldgen to leave, and which blocks only builds have. Cactus
 * random ticks grew before anyone came near, a crafting table and a dug hole must each be a sign that spots keep away
 * from, cactus placed and left alone must vouch for a spot, and after /rtp no spot may be offered near the landing.
 */
public final class GoodChunkGameTest implements FabricClientGameTest {
    /** DonutSMP's seed, so the plants are the kind its overworld has. */
    private static final String SEED = "6608149111735331168";
    /** Far apart, so every stop is ground nobody has been near. */
    private static final int[][] STOPS = {{0, 0}, {3000, 0}, {-3000, 1500}, {1500, -3000}, {-1500, -3000}};
    /** Pads of cactus: one grown by random ticks before the player arrives, one left as placed. */
    private static final int GROWN_X = 9000, GROWN_Z = 9000, FRESH_X = -9000, FRESH_Z = 9000, PAD_Y = 230;
    /** Where a crafting table is left, and a hole dug 64 blocks east of it, on ground nobody else has been near. */
    private static final int TRACE_X = 9000, TRACE_Z = -9000;
    /** Where a pretend /rtp lands: the fresh stop with the most spots. */
    private static final int RTP_X = 3000, RTP_Z = 0;

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

            // Fresh ground has no players: signs of them may only be structures' blocks that give nothing away, in a structure.
            Map<ChunkPos, String> wrong = new HashMap<>(), unexplained = new HashMap<>();
            List<Float> caves = new ArrayList<>();
            int scanned = 0, withPlants = 0, spots = 0, structures = 0;
            for (int[] stop : STOPS) {
                teleport(context, server, stop[0], 120, stop[1]);
                settle(context, scanner);
                ChunkStats stats = context.computeOnClient(client -> scanner.chunkStats());
                List<GoodChunk> found = context.computeOnClient(client -> scanner.goodChunks());
                List<ChunkSign> signs = context.computeOnClient(client -> scanner.chunkSigns());
                for (ChunkSign sign : signs) {
                    if (sign.structure()) { structures++; continue; }
                    boolean built = sign.reason().startsWith("Built") || sign.reason().startsWith("Dug");
                    String in = structuresAt(server, sign.chunk());
                    if (!built || in.isEmpty()) wrong.put(sign.chunk(), sign.reason());
                    else unexplained.put(sign.chunk(), sign.reason() + " in " + in);
                }
                caves.addAll(context.computeOnClient(client -> {
                    List<Float> shares = new ArrayList<>();
                    for (int dx = -10; dx <= 10; dx++) {
                        for (int dz = -10; dz <= 10; dz++) {
                            LevelChunk chunk = client.level.getChunkSource().getChunk((stop[0] >> 4) + dx, (stop[1] >> 4) + dz, ChunkStatus.FULL, false);
                            if (chunk != null) shares.add(Caves.share(chunk));
                        }
                    }
                    return shares;
                }));
                scanned += stats.scanned();
                withPlants += stats.withClocks();
                spots += found.size();
                log("stop %d,%d: %d chunks, %d with plants, %d signs, %d clear, %d solid, %d spots%s", stop[0], stop[1], stats.scanned(),
                        stats.withClocks(), stats.signs(), stats.roomy(), stats.solid(), found.size(),
                        found.isEmpty() ? "" : ", best " + describe(found.getFirst()));
            }
            caves.sort(null);
            log("fresh worldgen: %d chunks read, %d with plants, %d spots, %d structure chunks, caves by decile %s",
                    scanned, withPlants, spots, structures, deciles(caves));
            log("structure pieces read as player builds: %s", unexplained);
            log("player signs on fresh ground: %s", wrong);
            require(scanned >= 2000, "Only " + scanned + " chunks were read");
            require(withPlants >= 50, "Too few chunks with plants to judge: " + withPlants);
            require(wrong.isEmpty(), "Freshly generated chunks showed signs of players: " + wrong);
            // A structure piece with no giveaway block of its own, such as a lone shipwreck, reads as a build; it is rare.
            require(unexplained.size() * 100 <= scanned, "Too many structure pieces read as player builds: " + unexplained);
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

            // A player's crafting table on the surface, and a hole dug straight down, each far from anything else.
            ChunkPos table = ChunkPos.containing(new BlockPos(TRACE_X, 0, TRACE_Z));
            ChunkPos hole = ChunkPos.containing(new BlockPos(TRACE_X + 64, 0, TRACE_Z));
            server.runCommand("forceload add %d %d %d %d".formatted(TRACE_X, TRACE_Z, TRACE_X + 64, TRACE_Z));
            context.waitTicks(100);
            server.runCommand("execute in minecraft:overworld positioned %d 0 %d positioned over motion_blocking run setblock ~ ~ ~ minecraft:crafting_table"
                    .formatted(TRACE_X, TRACE_Z));
            int top = server.computeOnServer(mcServer -> mcServer.overworld().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, TRACE_X + 64, TRACE_Z));
            server.runCommand("execute in minecraft:overworld run fill %d %d %d %d %d %d minecraft:air".formatted(
                    TRACE_X + 64, top - 24, TRACE_Z, TRACE_X + 64, top - 1, TRACE_Z));
            server.runCommand("forceload remove all");
            teleport(context, server, TRACE_X + 32, 200, TRACE_Z);
            for (int second = 0; second < 60; second++) {
                context.waitTicks(20);
                List<ChunkSign> signs = context.computeOnClient(client -> scanner.chunkSigns());
                if (signs.stream().anyMatch(sign -> sign.chunk().equals(table)) && signs.stream().anyMatch(sign -> sign.chunk().equals(hole))) break;
            }
            settle(context, scanner);
            String tableSign = signAt(context, scanner, table), holeSign = signAt(context, scanner, hole);
            List<GoodChunk> nearTraces = context.computeOnClient(client -> scanner.goodChunks());
            log("crafting table: %s; dug hole: %s; %d spots; giveaways in the table's chunk: %s", tableSign, holeSign, nearTraces.size(),
                    context.computeOnClient(client -> {
                        StringBuilder at = new StringBuilder();
                        for (BlockPos pos : BlockPos.betweenClosed(table.getMinBlockX(), client.level.getMinY(), table.getMinBlockZ(),
                                table.getMaxBlockX(), client.level.getMaxY(), table.getMaxBlockZ())) {
                            BlockState state = client.level.getBlockState(pos);
                            if (dev.donut.client.finder.BuiltBlocks.structure(state) || state.is(Blocks.CRAFTING_TABLE)) {
                                at.append(state.getBlock().getDescriptionId()).append('@').append(pos.toShortString()).append(' ');
                            }
                        }
                        return at.toString();
                    }));
            require(tableSign.equals("Built: crafting table"), "The crafting table's chunk was not read as built: " + tableSign);
            require(holeSign.startsWith("Dug hole"), "The dug hole's chunk was not read as dug: " + holeSign);
            for (GoodChunk spot : nearTraces) {
                require(distance(spot.chunk(), table) > clearance && distance(spot.chunk(), hole) > clearance,
                        "A spot within the clearance of a player's trace: " + describe(spot));
            }

            ChunkPos fresh = ChunkPos.containing(new BlockPos(FRESH_X, PAD_Y, FRESH_Z));
            teleport(context, server, FRESH_X, PAD_Y + 4, FRESH_Z);
            context.waitFor(client -> scanner.bestGoodChunk() != null, 1200);
            settle(context, scanner);
            List<GoodChunk> nearPad = context.computeOnClient(client -> scanner.goodChunks());
            boolean freshSign = context.computeOnClient(client -> scanner.chunkSigns().stream().anyMatch(sign -> sign.chunk().equals(fresh)));
            // The pad floats, so its own chunk reads as hollow; a spot beside it has the pad's cacti in its clearance.
            GoodChunk vouched = nearPad.stream().filter(spot -> distance(spot.chunk(), fresh) <= clearance && spot.freshClocks() >= 49)
                    .findFirst().orElse(null);
            log("fresh pad: %d spots, best %s, beside the pad %s", nearPad.size(), nearPad.isEmpty() ? "none" : describe(nearPad.getFirst()),
                    vouched == null ? "none" : describe(vouched));
            require(!freshSign, "The untouched pad was read as a sign");
            require(vouched != null, "The untouched pad did not vouch for a spot");
            overlook(context, server, FRESH_X, FRESH_Z, "donut-chunkfinder-fresh-pad");

            // /rtp lands the player where the fresh sweep found the most spots: none may be offered within the RTP distance
            // of the landing, and the way out is shown.
            context.runOnClient(client -> client.player.connection.sendCommand("rtp"));
            teleport(context, server, RTP_X, 120, RTP_Z);
            settle(context, scanner);
            List<GoodChunk> atLanding = context.computeOnClient(client -> scanner.goodChunks());
            Vec3 travel = context.computeOnClient(client -> scanner.travelTarget());
            int landings = context.computeOnClient(client -> scanner.rtpZones(client).size());
            double rtpDistance = config.chunks.rtpDistance;
            log("rtp landing: %d landings noted, %d spots, travel to %s", landings, atLanding.size(), travel);
            require(landings == 1, "The /rtp landing was not noted: " + landings);
            require(atLanding.isEmpty(), "Spots offered next to the /rtp landing: " + atLanding.size());
            require(travel != null && Math.hypot(travel.x - RTP_X, travel.z - RTP_Z) >= rtpDistance,
                    "No way out of the RTP zone: " + travel);
            teleport(context, server, (int) Math.round(travel.x), 120, (int) Math.round(travel.z));
            settle(context, scanner);
            List<GoodChunk> outThere = context.computeOnClient(client -> scanner.goodChunks());
            Vec3 stillTravel = context.computeOnClient(client -> scanner.travelTarget());
            log("past the RTP distance: %d spots%s, travel %s", outThere.size(),
                    outThere.isEmpty() ? "" : ", best " + describe(outThere.getFirst()), stillTravel);
            require(stillTravel == null, "Still pointed onward at the travel target: " + stillTravel);
            for (GoodChunk spot : outThere) {
                double fromLanding = Math.hypot(spot.center().x - RTP_X, spot.center().z - RTP_Z);
                require(fromLanding >= rtpDistance, "A spot " + Math.round(fromLanding) + " blocks from the /rtp landing");
            }
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

    /** The sign ChunkFinder gives a chunk, or an empty string. */
    private static String signAt(ClientGameTestContext context, FinderScanner scanner, ChunkPos chunk) {
        return context.computeOnClient(client -> scanner.chunkSigns().stream().filter(sign -> sign.chunk().equals(chunk))
                .map(ChunkSign::reason).findFirst().orElse(""));
    }

    /** The structures the server generated in this chunk or right next to it, by name; empty when none. */
    private static String structuresAt(TestServerContext server, ChunkPos chunk) {
        return server.computeOnServer(mcServer -> {
            var registry = mcServer.registryAccess().lookupOrThrow(Registries.STRUCTURE);
            java.util.Set<String> names = new java.util.TreeSet<>();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    ChunkAccess near = mcServer.overworld().getChunk(chunk.x() + dx, chunk.z() + dz, ChunkStatus.STRUCTURE_REFERENCES, true);
                    near.getAllReferences().keySet().forEach(structure -> names.add(registry.getKey(structure).getPath()));
                    near.getAllStarts().keySet().forEach(structure -> names.add(registry.getKey(structure).getPath()));
                }
            }
            return String.join("+", names);
        });
    }

    /** The values at 0%, 10%, ... 100% of a sorted list, as percentages. */
    private static String deciles(List<Float> sorted) {
        if (sorted.isEmpty()) return "[]";
        StringBuilder text = new StringBuilder("[");
        for (int decile = 0; decile <= 10; decile++) {
            float value = sorted.get(Math.min(sorted.size() - 1, decile * (sorted.size() - 1) / 10));
            text.append(decile == 0 ? "" : " ").append(String.format(Locale.ROOT, "%.1f", value * 100));
        }
        return text.append("]%").toString();
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
