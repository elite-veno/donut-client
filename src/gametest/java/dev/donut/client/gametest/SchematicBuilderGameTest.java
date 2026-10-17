package dev.donut.client.gametest;

import dev.donut.client.config.ClientConfig;
import dev.donut.client.config.SchematicSettings;
import dev.donut.client.finder.ChunkEvents;
import dev.donut.client.gui.DonutScreen;
import dev.donut.client.schematic.BlockRules;
import dev.donut.client.schematic.BuildPlan;
import dev.donut.client.schematic.Materials;
import dev.donut.client.schematic.SchematicBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ComparatorMode;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.RedstoneSide;
import net.minecraft.world.level.block.state.properties.SlabType;

/**
 * Builds a small redstone schematic in survival with the production builder, as a player would: the .litematic is
 * dropped on the menu, the module switched on, and the builder walks up to the build on its own, with normal reach and
 * without flying, looking at every spot it clicks. Some blocks only come from a shulker box in the inventory. It checks every block's state on the server, that layers went in from the bottom up
 * with a lantern hanging from the layer above waiting for its ceiling, that redstone followed the plain blocks of its
 * layer, and that blocks waited the configured delay.
 */
public final class SchematicBuilderGameTest implements FabricClientGameTest {
    private static final BlockPos ORIGIN = new BlockPos(0, 65, 0);
    private static final String FILE = "donut-test.litematic";
    private static final double MIN_DELAY = 0.5, MAX_DELAY = 0.8;

    @Override
    public void runTest(ClientGameTestContext context) {
        SchematicBuilder builder = SchematicBuilder.getInstance();
        require(builder != null, "The production schematic builder was not created");
        // Each placement goes in the log, for a failure to be traced.
        System.setProperty("donut.schematic.debug", "true");

        SchematicSettings settings = builder.settings();
        SchematicSettings original = copy(settings);
        Map<BlockPos, BlockState> blocks = blocks();
        Path dropped = FabricLoader.getInstance().getGameDir().resolve("schematic-drop").resolve(FILE);
        Path imported = context.computeOnClient(client -> SchematicBuilder.folder().resolve(FILE));
        Map<BlockPos, Long> placedAt = new ConcurrentHashMap<>();
        // Where the player stood as each block went in: the delay is only measured between blocks placed from one spot.
        Map<BlockPos, net.minecraft.world.phys.Vec3> placedFrom = new ConcurrentHashMap<>();
        ChunkEvents.Listener listener = new ChunkEvents.Listener() {
            @Override
            public void blockChanged(ClientLevel level, BlockPos pos, BlockState previous, BlockState current) {
                BlockPos relative = pos.subtract(ORIGIN);
                BlockState target = blocks.get(relative);
                if (target != null && BlockRules.kind(target) == BlockRules.Kind.PLACE && previous.canBeReplaced() && !current.canBeReplaced()) {
                    if (placedAt.putIfAbsent(relative.immutable(), System.nanoTime()) == null) {
                        var player = net.minecraft.client.Minecraft.getInstance().player;
                        if (player != null) placedFrom.put(relative.immutable(), player.position());
                    }
                }
            }
        };
        try {
            Files.createDirectories(dropped.getParent());
            writeSchematic(dropped, blocks);
            Files.deleteIfExists(imported);
        } catch (IOException e) {
            throw new AssertionError("Could not write the test schematic", e);
        }
        context.getInput().resizeWindow(1280, 800);
        ChunkEvents.register(listener);
        try (var world = context.worldBuilder().adjustSettings(creator -> creator.setAllowCommands(true)).create()) {
            TestServerContext server = world.getServer();
            server.runCommand("difficulty peaceful");
            server.runCommand("time set noon");
            // Ground five deep, for pits to stand in.
            server.runCommand("fill -12 60 -12 16 64 20 minecraft:smooth_stone");
            server.runCommand("fill -12 65 -12 16 72 20 minecraft:air");
            server.runCommand("gamemode survival @a");
            server.runCommand("clear @a");
            // Loose in the inventory; more than the hotbar holds, so some must be swapped in from the inventory.
            for (String give : List.of("stone 12", "repeater", "observer", "piston", "redstone 4", "lever", "redstone_torch",
                    "oak_planks", "stone_slab", "hopper", "oak_door", "red_bed", "iron_pickaxe", "dirt 16")) {
                server.runCommand("give @a minecraft:" + give);
            }
            // These only come out of a shulker box.
            server.runCommand("give @a minecraft:purple_shulker_box[minecraft:container=["
                    + "{slot:0,item:{id:\"minecraft:lantern\",count:1}},{slot:1,item:{id:\"minecraft:candle\",count:3}},"
                    + "{slot:2,item:{id:\"minecraft:oak_stairs\",count:1}},{slot:3,item:{id:\"minecraft:comparator\",count:1}},"
                    + "{slot:4,item:{id:\"minecraft:diamond\",count:5}}]]");
            // Far to the south at first: out of reach, so it has to walk.
            server.runCommand("tp @a 5.5 65 16.5 180 25");
            context.waitTicks(10);
            world.getConnection().waitForChunksRender();
            context.waitFor(client -> client.player != null && client.player.onGround() && client.player.getInventory().contains(
                    stack -> stack.is(Items.DYED_SHULKER_BOX.purple())));

            // Import by dropping the file on the menu, then switch the module on there.
            ClientConfig screenConfig = new ClientConfig();
            screenConfig.schematic = settings;
            context.setScreen(() -> new DonutScreen(screenConfig, () -> { }));
            context.waitTicks(2);
            context.runOnClient(client -> ((DonutScreen) client.gui.screen()).onFilesDrop(List.of(dropped)));
            require(FILE.equals(settings.file), "Dropping the file did not choose it: " + settings.file);
            require(Files.isRegularFile(imported), "Dropping the file did not copy it into the schematics folder");
            context.runOnClient(client -> {
                settings.originSet = true;
                settings.originX = ORIGIN.getX();
                settings.originY = ORIGIN.getY();
                settings.originZ = ORIGIN.getZ();
                settings.rotation = 0;
                settings.mirror = 0;
                settings.setMaxDelay(MAX_DELAY);
                settings.setMinDelay(MIN_DELAY);
                settings.useShulkers = true;
                settings.walk = true;
                settings.helperBlocks = "dirt";
                settings.redstoneLast = true;
                settings.preview = true;
                require(builder.schematic() != null, "The dropped schematic did not load: " + builder.loadProblem());
            });
            GuiClicks.settle(context);
            screenshot(context, "donut-schematic-01-settings");
            GuiClicks.reveal(context, "SchematicBuilder.section.Materials");
            screenshot(context, "donut-schematic-02-materials");
            GuiClicks.click(context, "module.SchematicBuilder");
            require(settings.enabled, "The module switch did not turn the builder on");
            context.waitTicks(3);
            context.runOnClient(client -> require(!builder.isActive() || builder.status().startsWith("Paused"),
                    "The builder placed blocks with its menu open: " + builder.status()));
            context.runOnClient(client -> client.gui.screen().onClose());
            context.waitForScreen(null);

            AtomicBoolean flew = new AtomicBoolean();
            ClientTickEvents.END_CLIENT_TICK.register(client -> {
                if (client.player != null && client.player.getAbilities().flying) flew.set(true);
            });
            context.waitFor(client -> builder.isActive(), 200);
            context.runOnClient(client -> require(builder.summary() != null, "The info panel has no builder line"));
            context.runOnClient(client -> require(client.player.blockInteractionRange() <= 4.5 + 1e-6,
                    "The test runs with more than the normal reach: " + client.player.blockInteractionRange()));
            await(context, builder, placedAt, client -> placedAt.size() >= 12, 20 * 90);
            screenshot(context, "donut-schematic-04-building");
            await(context, builder, placedAt, client -> !builder.isActive(), 20 * 180);
            context.runOnClient(client -> require(builder.status().startsWith("Finished"), "The build did not finish: " + builder.status()));
            require(!flew.get(), "The player flew");
            context.waitTicks(10);
            screenshot(context, "donut-schematic-05-finished");

            checkBlocks(server, blocks);
            int holes = server.computeOnServer(s -> {
                int count = 0;
                for (BlockPos pos : BlockPos.betweenClosed(-12, 60, -12, 16, 64, 20)) {
                    if (!s.overworld().getBlockState(pos).is(Blocks.SMOOTH_STONE)) count++;
                }
                return count;
            });
            require(holes == 0, holes + " blocks of the ground were left dug out or filled with something else");
            int dirt = server.computeOnServer(s -> {
                int count = 0;
                for (BlockPos pos : BlockPos.betweenClosed(-12, 65, -12, 16, 72, 20)) if (s.overworld().getBlockState(pos).is(Blocks.DIRT)) count++;
                return count;
            });
            require(dirt == 0, dirt + " helper blocks were left standing");
            checkOrder(blocks, placedAt, placedFrom);
            checkShulker(context, server);
        } finally {
            ChunkEvents.unregister(listener);
            context.runOnClient(client -> {
                builder.setEnabled(false);
                restore(settings, original);
            });
        }
    }

    /** Waits, and on a timeout says where the builder stood, with a screenshot. */
    private static void await(ClientGameTestContext context, SchematicBuilder builder, Map<BlockPos, Long> placedAt,
                              java.util.function.Predicate<net.minecraft.client.Minecraft> done, int ticks) {
        try {
            context.waitFor(done, ticks);
        } catch (AssertionError e) {
            screenshot(context, "donut-schematic-timeout");
            String status = context.computeOnClient(client -> builder.status());
            throw new AssertionError("Timed out with " + placedAt.size() + " blocks placed; the builder says: " + status, e);
        }
    }

    /**
     * Four layers. A diagonal line of wire climbs three steps; a lantern hangs from a ceiling held only from the side in
     * the layer above; a wall torch and a top slab need a block beside them first; repeater, comparator, lever, door and
     * a lone wire need clicks after placing; three candles go in one spot; a bed and a door bring their other half.
     */
    private static Map<BlockPos, BlockState> blocks() {
        Map<BlockPos, BlockState> b = new LinkedHashMap<>();
        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockState wire = Blocks.REDSTONE_WIRE.defaultBlockState();
        // Layer 0.
        for (int x : new int[] {0, 1, 2, 5, 7}) b.put(new BlockPos(x, 0, 0), stone);
        b.put(new BlockPos(4, 0, 0), Blocks.LEVER.defaultBlockState().setValue(BlockStateProperties.ATTACH_FACE, AttachFace.WALL)
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST).setValue(BlockStateProperties.POWERED, true));
        b.put(new BlockPos(6, 0, 0), Blocks.LANTERN.defaultBlockState().setValue(BlockStateProperties.HANGING, true));
        BlockState bed = Blocks.BED.red().defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST);
        b.put(new BlockPos(9, 0, 0), bed.setValue(BlockStateProperties.BED_PART, BedPart.FOOT));
        b.put(new BlockPos(10, 0, 0), bed.setValue(BlockStateProperties.BED_PART, BedPart.HEAD));
        b.put(new BlockPos(0, 0, 2), Blocks.REPEATER.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST)
                .setValue(BlockStateProperties.DELAY, 3));
        b.put(new BlockPos(1, 0, 2), Blocks.COMPARATOR.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST)
                .setValue(BlockStateProperties.MODE_COMPARATOR, ComparatorMode.SUBTRACT));
        b.put(new BlockPos(2, 0, 2), Blocks.OBSERVER.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.NORTH));
        b.put(new BlockPos(3, 0, 2), Blocks.PISTON.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.UP));
        b.put(new BlockPos(4, 0, 2), wire);
        BlockState door = Blocks.OAK_DOOR.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH)
                .setValue(BlockStateProperties.DOOR_HINGE, DoorHingeSide.LEFT).setValue(BlockStateProperties.OPEN, true);
        b.put(new BlockPos(8, 0, 2), door.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER));
        b.put(new BlockPos(8, 1, 2), door.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER));
        b.put(new BlockPos(0, 0, 4), stone);
        b.put(new BlockPos(1, 0, 4), Blocks.REDSTONE_WALL_TORCH.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST));
        b.put(new BlockPos(2, 0, 4), Blocks.OAK_PLANKS.defaultBlockState());
        b.put(new BlockPos(3, 0, 4), Blocks.STONE_SLAB.defaultBlockState().setValue(BlockStateProperties.SLAB_TYPE, SlabType.TOP));
        b.put(new BlockPos(4, 0, 4), Blocks.OAK_STAIRS.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH)
                .setValue(BlockStateProperties.HALF, Half.BOTTOM));
        b.put(new BlockPos(5, 0, 4), Blocks.CANDLE.defaultBlockState().setValue(BlockStateProperties.CANDLES, 3));
        b.put(new BlockPos(6, 0, 4), Blocks.HOPPER.defaultBlockState().setValue(BlockStateProperties.FACING_HOPPER, Direction.EAST));
        b.put(new BlockPos(7, 0, 4), stone);
        // Layer 1: the first step of wire, the second step's block, and the lantern's ceiling, held from the side.
        b.put(new BlockPos(0, 1, 0), wire.setValue(BlockStateProperties.EAST_REDSTONE, RedstoneSide.UP));
        b.put(new BlockPos(1, 1, 0), stone);
        b.put(new BlockPos(2, 1, 0), stone);
        b.put(new BlockPos(6, 1, 0), stone);
        b.put(new BlockPos(7, 1, 0), stone);
        // Layers 2 and 3: the wire climbs on.
        b.put(new BlockPos(1, 2, 0), wire.setValue(BlockStateProperties.WEST_REDSTONE, RedstoneSide.SIDE)
                .setValue(BlockStateProperties.EAST_REDSTONE, RedstoneSide.UP));
        b.put(new BlockPos(2, 2, 0), stone);
        b.put(new BlockPos(2, 3, 0), wire.setValue(BlockStateProperties.WEST_REDSTONE, RedstoneSide.SIDE));
        return b;
    }

    private static void checkBlocks(TestServerContext server, Map<BlockPos, BlockState> blocks) {
        List<String> wrong = new ArrayList<>();
        Map<BlockPos, BlockState> world = server.computeOnServer(s -> {
            Map<BlockPos, BlockState> read = new LinkedHashMap<>();
            for (BlockPos pos : blocks.keySet()) read.put(pos, s.overworld().getBlockState(pos.offset(ORIGIN)));
            return read;
        });
        for (Map.Entry<BlockPos, BlockState> entry : blocks.entrySet()) {
            BlockState target = entry.getValue(), actual = world.get(entry.getKey());
            boolean ok = BlockRules.kind(target) == BlockRules.Kind.AUTO ? BlockRules.placementMatches(actual, target)
                    : BlockRules.satisfied(actual, target);
            if (!ok) wrong.add(entry.getKey().toShortString() + ": " + actual + " instead of " + target);
        }
        require(wrong.isEmpty(), "Blocks differ from the schematic:\n" + String.join("\n", wrong));
        // The wire's diagonal steps join up across the layers.
        require(world.get(new BlockPos(0, 1, 0)).getValue(BlockStateProperties.EAST_REDSTONE) == RedstoneSide.UP,
                "The bottom step of wire does not climb to the next");
        require(world.get(new BlockPos(1, 2, 0)).getValue(BlockStateProperties.EAST_REDSTONE) == RedstoneSide.UP
                && world.get(new BlockPos(1, 2, 0)).getValue(BlockStateProperties.WEST_REDSTONE) != RedstoneSide.NONE,
                "The middle step of wire is not joined to both neighbors");
        require(world.get(new BlockPos(2, 3, 0)).getValue(BlockStateProperties.WEST_REDSTONE) != RedstoneSide.NONE,
                "The top step of wire is not joined to the one below");
    }

    private static void checkOrder(Map<BlockPos, BlockState> blocks, Map<BlockPos, Long> placedAt,
                                   Map<BlockPos, net.minecraft.world.phys.Vec3> placedFrom) {
        BlockPos lantern = new BlockPos(6, 0, 0), ceiling = new BlockPos(6, 1, 0);
        for (Map.Entry<BlockPos, BlockState> entry : blocks.entrySet()) {
            if (BlockRules.kind(entry.getValue()) != BlockRules.Kind.PLACE) continue;
            require(placedAt.containsKey(entry.getKey()), "Saw no placement at " + entry.getKey().toShortString());
        }
        // Bottom up: every block goes in before every block of a higher layer, except the lantern, which waits for its ceiling.
        for (BlockPos low : placedAt.keySet()) {
            if (low.equals(lantern)) continue;
            for (BlockPos high : placedAt.keySet()) {
                if (high.getY() > low.getY()) {
                    require(placedAt.get(low) < placedAt.get(high), low.toShortString() + " went in after " + high.toShortString()
                            + " in a higher layer");
                }
            }
        }
        require(placedAt.get(lantern) > placedAt.get(ceiling), "The hanging lantern went in before its ceiling");
        require(placedAt.get(lantern) < placedAt.get(new BlockPos(1, 2, 0)), "The lantern waited past the layer its ceiling is in");
        // Within the bottom layer, near each other: plain blocks, then redstone, then the piston and observer. Blocks
        // further apart across the layer may go in in any order.
        for (Map.Entry<BlockPos, Long> later : placedAt.entrySet()) {
            BlockPos at = later.getKey();
            if (at.getY() != 0 || at.equals(lantern)) continue;
            int group = BlockRules.group(blocks.get(at));
            for (Map.Entry<BlockPos, Long> earlier : placedAt.entrySet()) {
                BlockPos near = earlier.getKey();
                if (near.getY() != 0 || near.equals(lantern) || BlockRules.group(blocks.get(near)) >= group) continue;
                if (Math.abs(near.getX() - at.getX()) > BuildPlan.NEAR || Math.abs(near.getZ() - at.getZ()) > BuildPlan.NEAR) continue;
                require(earlier.getValue() < later.getValue(), blocks.get(at).getBlock().getName().getString() + " at " + at.toShortString()
                        + " went in before " + blocks.get(near).getBlock().getName().getString() + " beside it at " + near.toShortString());
            }
        }
        // Each block waited at least the shortest delay after the one before. Between blocks placed from one spot, the
        // camera turns during the wait, so most go in within the longest delay; walking in between takes what it takes.
        List<BlockPos> order = new ArrayList<>(placedAt.keySet());
        order.sort(java.util.Comparator.comparingLong(placedAt::get));
        List<Double> gaps = new ArrayList<>(), standing = new ArrayList<>();
        for (int i = 1; i < order.size(); i++) {
            double gap = (placedAt.get(order.get(i)) - placedAt.get(order.get(i - 1))) / 1e9;
            gaps.add(gap);
            var a = placedFrom.get(order.get(i - 1));
            var b = placedFrom.get(order.get(i));
            if (a != null && b != null && a.distanceTo(b) < 0.05) standing.add(gap);
        }
        double shortest = gaps.stream().mapToDouble(Double::doubleValue).min().orElse(0);
        require(shortest >= MIN_DELAY - 0.06, "Two blocks went in " + shortest + " s apart, below the " + MIN_DELAY + " s shortest delay");
        require(standing.size() >= 5, "Only " + standing.size() + " blocks went in one after another from the same spot");
        standing.sort(null);
        double median = standing.get(standing.size() / 2);
        require(median <= MAX_DELAY + 0.25, "Blocks placed from one spot went in " + median + " s apart in the middle, beyond the "
                + MAX_DELAY + " s longest delay");
    }

    private static void checkShulker(ClientGameTestContext context, TestServerContext server) {
        context.runOnClient(client -> {
            var inventory = client.player.getInventory();
            int boxes = 0;
            for (int slot = 0; slot < Materials.SLOTS; slot++) {
                ItemStack stack = inventory.getItem(slot);
                if (!Materials.isShulkerBox(stack)) continue;
                boxes++;
                require(Materials.countInside(stack, Items.LANTERN) == 0 && Materials.countInside(stack, Items.CANDLE) == 0
                        && Materials.countInside(stack, Items.OAK_STAIRS) == 0 && Materials.countInside(stack, Items.COMPARATOR) == 0,
                        "The shulker box still holds blocks the build needed");
                require(Materials.countInside(stack, Items.DIAMOND) == 5, "The builder took items the build doesn't need");
            }
            require(boxes == 1, "The shulker box is not back in the inventory");
        });
        boolean left = server.computeOnServer(s -> {
            for (BlockPos pos : BlockPos.betweenClosed(-12, 65, -12, 16, 66, 20)) {
                if (s.overworld().getBlockState(pos).getBlock() instanceof ShulkerBoxBlock) return true;
            }
            return false;
        });
        require(!left, "A shulker box was left standing in the world");
    }

    /** A single-region schematic as Litematica saves it. */
    private static void writeSchematic(Path file, Map<BlockPos, BlockState> blocks) throws IOException {
        int sx = 0, sy = 0, sz = 0;
        for (BlockPos pos : blocks.keySet()) {
            sx = Math.max(sx, pos.getX() + 1);
            sy = Math.max(sy, pos.getY() + 1);
            sz = Math.max(sz, pos.getZ() + 1);
        }
        List<BlockState> palette = new ArrayList<>(List.of(Blocks.AIR.defaultBlockState()));
        int[] indexes = new int[sx * sy * sz];
        for (Map.Entry<BlockPos, BlockState> entry : blocks.entrySet()) {
            int index = palette.indexOf(entry.getValue());
            if (index < 0) {
                palette.add(entry.getValue());
                index = palette.size() - 1;
            }
            BlockPos pos = entry.getKey();
            indexes[(pos.getY() * sz + pos.getZ()) * sx + pos.getX()] = index;
        }
        CompoundTag region = new CompoundTag();
        region.put("Position", xyz(0, 0, 0));
        region.put("Size", xyz(sx, sy, sz));
        ListTag paletteTag = new ListTag();
        for (BlockState state : palette) paletteTag.add(NbtUtils.writeBlockState(state));
        region.put("BlockStatePalette", paletteTag);
        region.put("BlockStates", new LongArrayTag(pack(indexes, palette.size())));
        region.put("TileEntities", new ListTag());
        region.put("Entities", new ListTag());
        CompoundTag regions = new CompoundTag();
        regions.put("Redstone test", region);
        CompoundTag meta = new CompoundTag();
        meta.putString("Name", "Redstone test");
        meta.putString("Author", "Donut game test");
        meta.put("EnclosingSize", xyz(sx, sy, sz));
        meta.putInt("RegionCount", 1);
        meta.putInt("TotalBlocks", blocks.size());
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

    /** Litematica's bit array: each index takes the palette's bits, running on into the next long when one fills. */
    private static long[] pack(int[] values, int paletteSize) {
        int bits = Math.max(2, Integer.SIZE - Integer.numberOfLeadingZeros(paletteSize - 1));
        long mask = (1L << bits) - 1;
        long[] words = new long[(int) ((values.length * (long) bits + 63) / 64)];
        for (int index = 0; index < values.length; index++) {
            long start = index * (long) bits;
            int first = (int) (start >> 6), last = (int) (((index + 1L) * bits - 1) >> 6), offset = (int) (start & 63);
            long value = values[index] & mask;
            words[first] = words[first] & ~(mask << offset) | value << offset;
            if (first != last) {
                int carried = 64 - offset, rest = bits - carried;
                words[last] = words[last] >>> rest << rest | value >> carried;
            }
        }
        return words;
    }

    private static SchematicSettings copy(SchematicSettings s) {
        SchematicSettings c = new SchematicSettings();
        restore(c, s);
        return c;
    }

    private static void restore(SchematicSettings into, SchematicSettings from) {
        into.file = from.file;
        into.originSet = from.originSet;
        into.originX = from.originX;
        into.originY = from.originY;
        into.originZ = from.originZ;
        into.rotation = from.rotation;
        into.mirror = from.mirror;
        into.minDelay = from.minDelay;
        into.maxDelay = from.maxDelay;
        into.useShulkers = from.useShulkers;
        into.walk = from.walk;
        into.helperBlocks = from.helperBlocks;
        into.redstoneLast = from.redstoneLast;
        into.preview = from.preview;
    }

    private static void screenshot(ClientGameTestContext context, String name) {
        context.takeScreenshot(TestScreenshotOptions.of(name).withSize(1280, 800));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
