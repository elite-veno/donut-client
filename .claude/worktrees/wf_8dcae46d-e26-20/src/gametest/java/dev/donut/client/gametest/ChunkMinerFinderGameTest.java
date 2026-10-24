package dev.donut.client.gametest;

import dev.donut.client.config.MinerSettings;
import dev.donut.client.config.SchematicSettings;
import dev.donut.client.finder.FinderScanner;
import dev.donut.client.finder.GoodChunk;
import dev.donut.client.miner.ChunkMiner;
import dev.donut.client.miner.Room;
import dev.donut.client.schematic.SchematicBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

/**
 * ChunkFinder and ChunkMiner together, on DonutSMP's seed: once ChunkFinder finds a good chunk (a pad of cactus nobody
 * has been near), the miner, switched on with Dig good chunks, locks onto it, puts the schematic in its middle at the
 * floor height, switches ChunkFinder on if it was off, and starts digging its way there from where the player stands.
 */
public final class ChunkMinerFinderGameTest implements FabricClientGameTest {
    private static final String SEED = "6608149111735331168";
    private static final int PAD_Y = 230, FLOOR_Y = 30;

    @Override
    public void runTest(ClientGameTestContext context) {
        ChunkMiner miner = ChunkMiner.getInstance();
        SchematicBuilder builder = SchematicBuilder.getInstance();
        FinderScanner scanner = FinderScanner.instance();
        require(miner != null && builder != null && scanner != null, "The production miner, builder or scanner was not created");
        MinerSettings minerSettings = miner.settings();
        SchematicSettings place = builder.settings();
        MinerSettings originalMiner = ChunkMinerGameTest.copy(minerSettings, new MinerSettings());
        SchematicSettings originalPlace = ChunkMinerGameTest.copy(place, new SchematicSettings());
        Path schematic = context.computeOnClient(client -> SchematicBuilder.folder().resolve("donut-miner-test.litematic"));
        try {
            Files.createDirectories(schematic.getParent());
            ChunkMinerGameTest.writeSchematic(schematic);
        } catch (IOException e) {
            throw new AssertionError("Could not write the test schematic", e);
        }
        context.getInput().resizeWindow(1280, 800);
        try (var world = context.worldBuilder().setUseConsistentSettings(false).adjustSettings(creator -> {
            creator.setSeed(SEED);
            creator.setAllowCommands(true);
        }).create()) {
            TestServerContext server = world.getServer();
            server.runCommand("gamerule random_tick_speed 0");
            server.runCommand("difficulty peaceful");
            server.runCommand("time set noon");
            // Dry land near spawn to start on, and beside it, high in the air, a pad of cactus as placed: a good chunk.
            server.runCommand("spreadplayers 0 0 0 300 false @a");
            context.waitTicks(40);
            context.waitFor(client -> client.player != null && client.level != null && client.level.dimension() == Level.OVERWORLD
                    && client.player.onGround(), 600);
            BlockPos ground = context.computeOnClient(client -> client.player.blockPosition());
            int padX = ground.getX() + 40, padZ = ground.getZ();
            server.runCommand("forceload add %d %d %d %d".formatted(padX - 16, padZ - 16, padX + 16, padZ + 16));
            context.waitTicks(100);
            server.runCommand("fill %d %d %d %d %d %d minecraft:stone".formatted(padX - 6, PAD_Y - 1, padZ - 6, padX + 6, PAD_Y - 1, padZ + 6));
            server.runCommand("fill %d %d %d %d %d %d minecraft:sand".formatted(padX - 6, PAD_Y, padZ - 6, padX + 6, PAD_Y, padZ + 6));
            for (int dx = -6; dx <= 6; dx += 2) {
                for (int dz = -6; dz <= 6; dz += 2) server.runCommand("setblock %d %d %d minecraft:cactus".formatted(padX + dx, PAD_Y + 1, padZ + dz));
            }
            server.runCommand("gamemode survival @a");
            server.runCommand("clear @a");
            server.runCommand("give @a minecraft:netherite_pickaxe[minecraft:enchantments={\"minecraft:efficiency\":5}]");
            server.runCommand("give @a minecraft:cobblestone 64");
            server.runCommand("effect give @a minecraft:saturation infinite 0 true");
            server.runCommand("effect give @a minecraft:haste infinite 1 true");
            context.waitFor(client -> client.player.onGround() && client.player.getInventory().contains(
                    stack -> stack.is(net.minecraft.world.item.Items.NETHERITE_PICKAXE)), 400);
            context.runOnClient(client -> {
                place.file = "donut-miner-test.litematic";
                place.rotation = 0;
                place.mirror = 0;
                minerSettings.goodChunks = true;
                minerSettings.floorY = FLOOR_Y;
                minerSettings.pickaxe = 2;
                minerSettings.pauseForPlayers = 0;
                minerSettings.stopWhenHurt = true;
                minerSettings.setMinDelay(0.05);
                minerSettings.setMaxDelay(0.1);
                require(builder.schematic() != null, "The test schematic did not load: " + builder.loadProblem());
                miner.setEnabled(true);
            });
            context.waitTicks(2);
            require(context.computeOnClient(client -> miner.isActive()), "The miner did not start: " + context.computeOnClient(client -> miner.status()));
            // It waits for a good chunk, and switched ChunkFinder on to find one.
            context.waitFor(client -> miner.excavator() != null, 20 * 120);
            ChunkMiner.Target found = context.computeOnClient(client -> miner.target());
            Room room = context.computeOnClient(client -> miner.room());
            require(found != null, "The miner started without a good chunk");
            List<ChunkPos> good = context.computeOnClient(client -> scanner.goodChunks().stream().map(GoodChunk::chunk).toList());
            require(good.contains(found.chunk()), "The miner dug under " + found.chunk() + ", which ChunkFinder doesn't call good: " + good);
            int cx = found.chunk().getMiddleBlockX(), cz = found.chunk().getMiddleBlockZ();
            // Lower only where the ground over some column of the room is too thin at the floor height.
            require(room.schematic.minY() <= FLOOR_Y, "The room's floor is at y " + room.schematic.minY() + ", over " + FLOOR_Y);
            require(Math.abs(room.schematic.centerX() - cx) <= 1 && Math.abs(room.schematic.centerZ() - cz) <= 1,
                    "The room " + room.schematic + " is not in the middle of the good chunk at " + cx + " " + cz);
            require(place.originSet && place.originY == room.schematic.minY(), "SchematicBuilder's origin was not set to the room");
            int steps = context.computeOnClient(client -> miner.excavator().stepsLeft());
            context.waitFor(client -> miner.excavator() == null || miner.excavator().stepsLeft() < steps - 5, 20 * 90);
            context.takeScreenshot(TestScreenshotOptions.of("donut-miner-good-chunk").withSize(1280, 800));
            System.out.println("[miner test] locked onto the good chunk at " + cx + " " + cz + "; " + context.computeOnClient(client -> miner.status()));
            require(context.computeOnClient(client -> miner.isActive()), "The miner stopped: " + context.computeOnClient(client -> miner.status()));
        } finally {
            context.runOnClient(client -> {
                miner.setEnabled(false);
                ChunkMinerGameTest.copy(originalMiner, minerSettings);
                ChunkMinerGameTest.copy(originalPlace, place);
            });
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
