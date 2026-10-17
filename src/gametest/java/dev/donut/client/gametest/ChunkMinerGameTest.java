package dev.donut.client.gametest;

import dev.donut.client.config.MinerSettings;
import dev.donut.client.config.SchematicSettings;
import dev.donut.client.miner.Box;
import dev.donut.client.miner.ChunkMiner;
import dev.donut.client.miner.Room;
import dev.donut.client.miner.ToolShape;
import dev.donut.client.schematic.SchematicBuilder;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Digs two rooms in survival with the production miner, as a player would: once with a normal pickaxe, so blocks take
 * a few ticks each, and once with only an "Amethyst Pickaxe", which a stand-in for DonutSMP's plugin on the test server
 * makes break 3x3 flat against the face hit. Each time the player starts on the ground beside the room, digs a staircase
 * down to its floor, climbs inside, and digs it from the top down. Afterwards every block of the room is gone, the floor
 * under it is whole, and the staircase can still be walked; the camera never turned faster than the setting allows.
 */
public final class ChunkMinerGameTest implements FabricClientGameTest {
    private static final String FILE = "donut-miner-test.litematic";
    /** The schematic: 4 wide, 6 tall, 4 long. */
    private static final int SX = 4, SY = 6, SZ = 4;
    private static final double TURN_SPEED = 30;
    /** The face each player last started breaking, for the stand-in area pickaxe. */
    private static final Map<UUID, Direction> FACES = new ConcurrentHashMap<>();
    private static volatile boolean areaPickaxe;
    private static boolean registered;

    @Override
    public void runTest(ClientGameTestContext context) {
        ChunkMiner miner = ChunkMiner.getInstance();
        SchematicBuilder builder = SchematicBuilder.getInstance();
        require(miner != null && builder != null, "The production miner or builder was not created");
        System.setProperty("donut.miner.debug", "true");
        registerAreaPickaxe();
        MinerSettings minerSettings = miner.settings();
        SchematicSettings place = builder.settings();
        MinerSettings originalMiner = copy(minerSettings, new MinerSettings());
        SchematicSettings originalPlace = copy(place, new SchematicSettings());
        Path schematic = context.computeOnClient(client -> SchematicBuilder.folder().resolve(FILE));
        try {
            Files.createDirectories(schematic.getParent());
            writeSchematic(schematic);
        } catch (IOException e) {
            throw new AssertionError("Could not write the test schematic", e);
        }
        context.getInput().resizeWindow(1280, 800);
        try (var world = context.worldBuilder().adjustSettings(creator -> creator.setAllowCommands(true)).create()) {
            TestServerContext server = world.getServer();
            server.runCommand("difficulty peaceful");
            server.runCommand("time set noon");
            for (int y = 40; y <= 64; y += 6) server.runCommand("fill -24 " + y + " -26 30 " + Math.min(64, y + 5) + " 20 minecraft:stone");
            server.runCommand("gamemode survival @a");
            server.runCommand("effect give @a minecraft:saturation infinite 0 true");
            context.runOnClient(client -> {
                place.file = FILE;
                place.rotation = 0;
                place.mirror = 0;
                minerSettings.goodChunks = false;
                minerSettings.sideRoom = 1;
                minerSettings.headroom = 1;
                minerSettings.storageSide = 3;
                minerSettings.storageDepth = 3;
                minerSettings.storageHeight = 3;
                minerSettings.keepDurability = 5;
                minerSettings.setMinDelay(0.05);
                minerSettings.setMaxDelay(0.15);
                minerSettings.pauseChance = 0.03;
                minerSettings.setPauseMin(0.3);
                minerSettings.setPauseMax(0.8);
                minerSettings.turnSpeed = TURN_SPEED;
                minerSettings.pauseForPlayers = 0;
                minerSettings.fillGaps = true;
                minerSettings.buildAfter = false;
                minerSettings.stopWhenHurt = true;
                minerSettings.preview = true;
            });

            // A normal pickaxe: stone takes two ticks a block.
            server.runCommand("clear @a");
            server.runCommand("give @a minecraft:netherite_pickaxe[minecraft:enchantments={\"minecraft:efficiency\":5}]");
            server.runCommand("give @a minecraft:cobblestone 32");
            dig(context, server, miner, place, new BlockPos(0, 52, 0), "16.5 65 1.5 90 20", 2, "normal");

            // Only an area pickaxe, which breaks 3x3 here.
            areaPickaxe = true;
            server.runCommand("clear @a");
            server.runCommand("give @a minecraft:diamond_pickaxe[minecraft:custom_name=\"Amethyst Pickaxe\",minecraft:enchantments={\"minecraft:efficiency\":5}]");
            server.runCommand("give @a minecraft:cobblestone 32");
            server.runCommand("effect give @a minecraft:haste infinite 1 true");
            dig(context, server, miner, place, new BlockPos(0, 52, -18), "16.5 65 -16.5 90 20", 0, "amethyst");
            int radius = context.computeOnClient(client -> {
                ItemStack held = client.player.getInventory().getItem(0);
                for (int slot = 0; slot < 36; slot++) {
                    ItemStack stack = client.player.getInventory().getItem(slot);
                    if (stack.getHoverName().getString().contains("Amethyst")) held = stack;
                }
                return miner.radius(held);
            });
            require(radius == 1, "The miner took the Amethyst Pickaxe to break " + radius + " around");
        } finally {
            areaPickaxe = false;
            context.runOnClient(client -> {
                miner.setEnabled(false);
                copy(originalMiner, minerSettings);
                copy(originalPlace, place);
            });
        }
    }

    private static void dig(ClientGameTestContext context, TestServerContext server, ChunkMiner miner, SchematicSettings place,
                            BlockPos origin, String tp, int pickaxe, String name) {
        server.runCommand("tp @a " + tp);
        context.waitTicks(10);
        context.waitFor(client -> client.player != null && client.player.onGround(), 200);
        context.runOnClient(client -> {
            place.originSet = true;
            place.originX = origin.getX();
            place.originY = origin.getY();
            place.originZ = origin.getZ();
            miner.settings().pickaxe = pickaxe;
            require(SchematicBuilder.getInstance().schematic() != null, "The test schematic did not load: " + SchematicBuilder.getInstance().loadProblem());
            miner.setEnabled(true);
        });
        context.waitTicks(2);
        require(context.computeOnClient(client -> miner.isActive() && miner.room() != null), "The miner did not start: "
                + context.computeOnClient(client -> miner.status()));
        Room room = context.computeOnClient(client -> miner.room());
        // The way in as last planned: an area pickaxe digs into the ground first and plans it again from there.
        List<BlockPos> first = context.computeOnClient(client -> List.copyOf(miner.excavator().approach()));
        List<List<BlockPos>> approach = new ArrayList<>();
        approach.add(first);
        float[] last = context.computeOnClient(client -> new float[] {client.player.getYRot(), client.player.getXRot()});
        double[] fastest = {0};
        int[] ticks = {0};
        try {
            context.waitFor(client -> {
                float yaw = client.player.getYRot(), pitch = client.player.getXRot();
                fastest[0] = Math.max(fastest[0], Math.hypot(Mth.wrapDegrees(yaw - last[0]), pitch - last[1]));
                last[0] = yaw;
                last[1] = pitch;
                if (miner.excavator() != null && miner.excavator().approach() != approach.getFirst()) approach.set(0, miner.excavator().approach());
                if (++ticks[0] % 600 == 0) System.out.println("[miner test] " + name + ": " + miner.status() + ", " + miner.left() + " left");
                return !miner.isActive();
            }, 20 * 60 * 10);
        } catch (AssertionError e) {
            screenshot(context, "donut-miner-" + name + "-timeout");
            throw new AssertionError("Timed out digging with the " + name + " pickaxe; the miner says: "
                    + context.computeOnClient(client -> miner.status()), e);
        }
        screenshot(context, "donut-miner-" + name + "-done");
        String status = context.computeOnClient(client -> miner.status());
        require(status.startsWith("Finished"), "The " + name + " dig did not finish: " + status);
        System.out.println("[miner test] " + name + " took " + ticks[0] + " ticks; fastest turn " + Math.round(fastest[0] * 10) / 10.0 + " degrees a tick");
        // The fastest a turn may go, its wobble, and a mouse step of rounding.
        require(fastest[0] <= TURN_SPEED * 1.15 + 1.5, "The camera turned " + fastest[0] + " degrees in a tick");
        check(server, room, List.copyOf(approach.getFirst()), name);
    }

    /** The room is empty, its floor whole, and every step of the way in still has a floor and room to stand. */
    private static void check(TestServerContext server, Room room, List<BlockPos> approach, String name) {
        List<String> problems = server.computeOnServer(s -> {
            ServerLevel level = s.overworld();
            List<String> found = new ArrayList<>();
            Box b = room.bounds;
            for (int x = b.minX(); x <= b.maxX(); x++) {
                for (int z = b.minZ(); z <= b.maxZ(); z++) {
                    if (!room.column(x, z)) continue;
                    for (int y = b.minY(); y <= b.maxY(); y++) {
                        BlockPos pos = new BlockPos(x, y, z);
                        if (room.contains(pos) && !level.getBlockState(pos).isAir()) found.add("left " + level.getBlockState(pos) + " at " + pos.toShortString());
                    }
                    BlockPos floor = new BlockPos(x, room.floorY - 1, z);
                    if (level.getBlockState(floor).isAir()) found.add("no floor at " + floor.toShortString());
                }
            }
            for (BlockPos step : approach) {
                if (room.contains(step)) continue;
                if (!level.getBlockState(step).isAir() || !level.getBlockState(step.above()).isAir()) found.add("the staircase is blocked at " + step.toShortString());
                if (level.getBlockState(step.below()).isAir()) found.add("the staircase has no floor at " + step.toShortString());
            }
            return found;
        });
        require(problems.isEmpty(), "After the " + name + " dig:\n" + String.join("\n", problems.subList(0, Math.min(20, problems.size()))));
    }

    /** A stand-in for DonutSMP's Amethyst Pickaxe: on the server, a block broken with it takes the 3x3 around it along. */
    private static void registerAreaPickaxe() {
        if (registered) return;
        registered = true;
        // The miner turns the camera itself: the test window never needs the mouse, and must not trap the user's.
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.mouseHandler.isMouseGrabbed()) client.mouseHandler.releaseMouse();
        });
        AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) -> {
            if (!level.isClientSide()) FACES.put(player.getUUID(), direction);
            return InteractionResult.PASS;
        });
        PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, blockEntity) -> {
            if (!areaPickaxe || !player.getMainHandItem().getHoverName().getString().contains("Amethyst")) return;
            Direction face = FACES.get(player.getUUID());
            if (face == null) return;
            for (BlockPos cell : ToolShape.area(1).cells(pos, face)) {
                if (cell.equals(pos)) continue;
                BlockState around = level.getBlockState(cell);
                if (around.isAir() || !around.getFluidState().isEmpty() || around.hasBlockEntity() || around.getDestroySpeed(level, cell) < 0) continue;
                level.destroyBlock(cell, true, player);
            }
        });
    }

    /** One glass block in the corner of a 4x6x4 box: the room is dug for the box, air and all. */
    static void writeSchematic(Path file) throws IOException {
        int[] indexes = new int[SX * SY * SZ];
        indexes[0] = 1;
        CompoundTag region = new CompoundTag();
        region.put("Position", xyz(0, 0, 0));
        region.put("Size", xyz(SX, SY, SZ));
        ListTag palette = new ListTag();
        palette.add(NbtUtils.writeBlockState(Blocks.AIR.defaultBlockState()));
        palette.add(NbtUtils.writeBlockState(Blocks.GLASS.defaultBlockState()));
        region.put("BlockStatePalette", palette);
        region.put("BlockStates", new LongArrayTag(pack(indexes)));
        region.put("TileEntities", new ListTag());
        region.put("Entities", new ListTag());
        CompoundTag regions = new CompoundTag();
        regions.put("Miner test", region);
        CompoundTag meta = new CompoundTag();
        meta.putString("Name", "Miner test");
        meta.putString("Author", "Donut game test");
        meta.put("EnclosingSize", xyz(SX, SY, SZ));
        meta.putInt("RegionCount", 1);
        meta.putInt("TotalBlocks", 1);
        CompoundTag root = new CompoundTag();
        root.putInt("Version", 7);
        root.putInt("SubVersion", 1);
        root.putInt("MinecraftDataVersion", SharedConstants.getCurrentVersion().dataVersion().version());
        root.put("Metadata", meta);
        root.put("Regions", regions);
        NbtIo.writeCompressed(root, file);
    }

    private static CompoundTag xyz(int x, int y, int z) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("x", x);
        tag.putInt("y", y);
        tag.putInt("z", z);
        return tag;
    }

    /** Litematica's bit array with two bits an entry; 64 is a multiple of two, so no entry runs into the next long. */
    private static long[] pack(int[] values) {
        long[] words = new long[(values.length * 2 + 63) / 64];
        for (int i = 0; i < values.length; i++) words[i * 2 / 64] |= (long) (values[i] & 3) << (i * 2 % 64);
        return words;
    }

    /** Copies every saved setting, so the test leaves them as it found them. */
    static <T> T copy(T from, T into) {
        try {
            for (Field f : from.getClass().getFields()) {
                if (Modifier.isStatic(f.getModifiers()) || Modifier.isFinal(f.getModifiers())) continue;
                f.set(into, f.get(from));
            }
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
        return into;
    }

    private static void screenshot(ClientGameTestContext context, String name) {
        context.takeScreenshot(TestScreenshotOptions.of(name).withSize(1280, 800));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
