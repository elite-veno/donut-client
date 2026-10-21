package dev.donut.client.gametest;

import dev.donut.client.config.SchematicSettings;
import dev.donut.client.schematic.BlockRules;
import dev.donut.client.schematic.LitematicFile;
import dev.donut.client.schematic.Materials;
import dev.donut.client.schematic.Schematic;
import dev.donut.client.schematic.SchematicBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds a schematic from a file in survival as a player on DonutSMP would: normal reach, no flying, no teleports,
 * every block carried in shulker boxes, a few stacks of dirt to climb with. Afterwards every block is checked on the
 * server, and no dirt may be left standing. It only runs when DONUT_SCHEMATIC_TEST names a .litematic, so other
 * people's schematics stay out of the repository.
 */
public final class SchematicFileGameTest implements FabricClientGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("Donut/SchematicFileTest");
    private static final BlockPos ORIGIN = new BlockPos(0, 65, 0);

    @Override
    public void runTest(ClientGameTestContext context) {
        String file = System.getenv("DONUT_SCHEMATIC_TEST");
        if (file == null || file.isBlank()) return;
        Path source = Path.of(file);
        SchematicBuilder builder = SchematicBuilder.getInstance();
        SchematicSettings settings = builder.settings();
        System.setProperty("donut.schematic.debug", "true");
        // DONUT_SCHEMATIC_SWIM=1: logs how many blocks placed with a look up a place to swim in still water would let go in.
        if ("1".equals(System.getenv("DONUT_SCHEMATIC_SWIM"))) System.setProperty("donut.schematic.swimStands", "true");
        LitematicFile parsed;
        String name;
        try {
            parsed = LitematicFile.read(source);
            name = context.computeOnClient(client -> {
                try {
                    return SchematicBuilder.importFiles(List.of(source));
                } catch (IOException e) {
                    throw new AssertionError(e);
                }
            });
        } catch (IOException e) {
            throw new AssertionError("Could not read " + source, e);
        }
        Schematic schematic = context.computeOnClient(client -> Schematic.of(parsed, client.getFixerUpper()));
        BlockPos size = schematic.max.subtract(schematic.min).offset(1, 1, 1);
        LOGGER.info("Building {} ({} blocks, {}x{}x{}) in survival", name, schematic.size(), size.getX(), size.getY(), size.getZ());
        AtomicBoolean flew = new AtomicBoolean();
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player != null && client.player.getAbilities().flying) flew.set(true);
        });
        context.getInput().resizeWindow(1280, 800);
        // DONUT_SCHEMATIC_ORDERS=1: as on the server, no shulker boxes handed out: everything the build takes waits on the
        // player's own buy orders, delivered in full, behind a copy of DonutSMP's order pages, and the builder collects it
        // from there through GoNuts' order driver whenever it runs out, as much as fits each time.
        boolean fromOrders = "1".equals(System.getenv("DONUT_SCHEMATIC_ORDERS"));
        if (fromOrders) {
            DonutOrders.register();
            DonutOrders.clear();
        }
        try (var world = context.worldBuilder().adjustSettings(creator -> creator.setAllowCommands(true)).create()) {
            TestServerContext server = world.getServer();
            server.runCommand("difficulty peaceful");
            server.runCommand("time set noon");
            server.runCommand("gamerule advance_time false");
            int x0 = ORIGIN.getX() + schematic.min.getX() - 12, z0 = ORIGIN.getZ() + schematic.min.getZ() - 12;
            int x1 = ORIGIN.getX() + schematic.max.getX() + 12, z1 = ORIGIN.getZ() + schematic.max.getZ() + 12;
            // Ground four deep, for pits to stand in.
            server.runCommand("fill " + x0 + " 61 " + z0 + " " + x1 + " 64 " + z1 + " minecraft:smooth_stone");
            server.runCommand("fill " + x0 + " 65 " + z0 + " " + x1 + " " + (ORIGIN.getY() + size.getY() + 12) + " " + z1 + " minecraft:air");
            server.runCommand("gamemode survival @a");
            server.runCommand("clear @a");
            server.runCommand("tp @a " + (ORIGIN.getX() + schematic.min.getX() - 3) + ".5 65 " + (ORIGIN.getZ() + schematic.min.getZ() - 3) + ".5 0 20");
            context.waitTicks(10);
            world.getConnection().waitForChunksRender();
            context.runOnClient(client -> {
                settings.file = name;
                settings.originSet = true;
                settings.originX = ORIGIN.getX();
                settings.originY = ORIGIN.getY();
                settings.originZ = ORIGIN.getZ();
                settings.rotation = 0;
                settings.mirror = 0;
                settings.setMinDelay(0.05);
                // Breaks are for building in front of others; the test times the building itself, unless
                // DONUT_SCHEMATIC_BREAKS=1 asks to watch them too.
                settings.breaks = "1".equals(System.getenv("DONUT_SCHEMATIC_BREAKS"));
                settings.setMaxDelay(0.05);
                settings.redstoneLast = true;
                settings.walk = true;
                settings.useShulkers = true;
                settings.fromOrders = fromOrders;
                settings.helperBlocks = "dirt";
                if (fromOrders) {
                    // GoNuts' driver only acts where its live execution is allowed: here, on this single-player world.
                    var driver = dev.doughbay.fabric.DoughBayClient.executionDriver();
                    driver.setAuthorizedServers(List.of("singleplayer"));
                    driver.setAuthorizedExecutionEnabled(true);
                }
            });
            Map<Item, Integer> bill = new LinkedHashMap<>(context.computeOnClient(client -> builder.bill()));
            // DONUT_SCHEMATIC_PREPLACE names blocks (deepslate, say) set in place before the build: a plain fill that
            // teaches the builder nothing and takes long. DONUT_SCHEMATIC_FROM_Y sets every block below that height in
            // place, so a run starts at the layer where the last one went wrong. DONUT_SCHEMATIC_RESUME=<log>[@hh:mm:ss] also
            // sets every block that log's run placed (before that time), so a run goes on at the very block where the
            // last one went wrong, not at the start of its layer. Either way they count as built, and what they take
            // isn't handed out.
            Set<Block> preplace = preplaced();
            String fromText = System.getenv("DONUT_SCHEMATIC_FROM_Y");
            int givenY = fromText == null || fromText.isBlank() ? Integer.MIN_VALUE : Integer.parseInt(fromText.strip());
            Set<Long> resumed = resumed(schematic);
            int fromY = resumed.isEmpty() ? givenY : Math.max(givenY, Integer.MIN_VALUE + 1);
            if (!preplace.isEmpty() || fromY != Integer.MIN_VALUE) {
                Map<Item, Integer> taken = new HashMap<>();
                int placed = server.computeOnServer(s -> {
                    int n = 0;
                    BlockState air = Blocks.AIR.defaultBlockState();
                    for (int i = 0; i < schematic.size(); i++) {
                        BlockState target = Schematic.place(schematic.state(i), Mirror.NONE, Rotation.NONE);
                        BlockPos pos = Schematic.place(schematic.pos(i), ORIGIN, Mirror.NONE, Rotation.NONE);
                        if (!preplace.contains(target.getBlock()) && pos.getY() >= fromY && !resumed.contains(pos.asLong())) continue;
                        BlockRules.Kind kind = BlockRules.kind(target);
                        if (kind == BlockRules.Kind.UNPLACEABLE) continue;
                        s.overworld().setBlock(pos, asSet(schematic, target, pos, preplace, fromY, resumed), Block.UPDATE_CLIENTS);
                        Item item = BlockRules.item(target);
                        if (item != null) taken.merge(item, kind == BlockRules.Kind.FLUID ? 1 : BlockRules.itemsNeeded(air, target), Integer::sum);
                        if (kind == BlockRules.Kind.PLACE && (BlockRules.soaked(target) || BlockRules.wet(target))) taken.merge(Items.WATER_BUCKET, 1, Integer::sum);
                        n++;
                    }
                    return n;
                });
                for (Map.Entry<Item, Integer> e : taken.entrySet()) {
                    bill.computeIfPresent(e.getKey(), (item, count) -> count > e.getValue() ? count - e.getValue() : null);
                }
                LOGGER.info("Set {} blocks in place before the build ({}{}{})", placed, preplace.isEmpty() ? "" : preplace + " ",
                        fromY <= Integer.MIN_VALUE + 1 ? "" : "everything below y " + fromY + " ",
                        resumed.isEmpty() ? "" : resumed.size() + " placed in " + System.getenv("DONUT_SCHEMATIC_RESUME"));
                context.waitTicks(20);
                // A machine set in place can move itself once its blocks update, a piston pushed or a torch knocked
                // off: what came out otherwise is set again, so the build starts on the layers it was asked to.
                int reset = server.computeOnServer(s -> {
                    int n = 0;
                    for (int i = 0; i < schematic.size(); i++) {
                        BlockState target = Schematic.place(schematic.state(i), Mirror.NONE, Rotation.NONE);
                        BlockPos pos = Schematic.place(schematic.pos(i), ORIGIN, Mirror.NONE, Rotation.NONE);
                        if (!preplace.contains(target.getBlock()) && pos.getY() >= fromY && !resumed.contains(pos.asLong())) continue;
                        BlockState set = asSet(schematic, target, pos, preplace, fromY, resumed);
                        if (BlockRules.kind(target) == BlockRules.Kind.UNPLACEABLE || s.overworld().getBlockState(pos) == set) continue;
                        s.overworld().setBlock(pos, set, Block.UPDATE_CLIENTS);
                        n++;
                    }
                    return n;
                });
                if (reset > 0) LOGGER.info("Set {} of them again, which had changed", reset);
                context.waitTicks(10);
            }
            // DONUT_SCHEMATIC_DIRT_BOXED: helper dirt carried in shulker boxes on top of the loose stacks, as a player brings
            // for a big build; the builder takes it out when the loose ones run low.
            String boxedText = System.getenv("DONUT_SCHEMATIC_DIRT_BOXED");
            int boxedDirt = boxedText == null || boxedText.isBlank() ? 0 : Integer.parseInt(boxedText.trim());
            if (boxedDirt > 0) bill.merge(Items.DIRT, boxedDirt, Integer::sum);
            // DONUT_SCHEMATIC_SPARE=n: n percent more of each block, at least one, as a player brings a few spare: a farm
            // that runs while it is built can push a block out, and it is placed again.
            String spareText = System.getenv("DONUT_SCHEMATIC_SPARE");
            int spare = spareText == null || spareText.isBlank() ? 0 : Integer.parseInt(spareText.trim());
            if (spare > 0) bill.replaceAll((item, count) -> item == Items.DIRT ? count : count + Math.max(1, count * spare / 100));
            int wettestLayer = wettestLayer(schematic, preplace, fromY, resumed);
            if (!fromOrders) capWater(bill, wettestLayer, 0);
            java.util.UUID player = server.computeOnServer(s -> s.getPlayerList().getPlayers().getFirst().getUUID());
            if (fromOrders) {
                // The helper dirt stays in hand, as a player brings their own.
                Map<Item, Integer> ordered = new LinkedHashMap<>(bill);
                ordered.remove(Items.DIRT);
                DonutOrders.fill(player, ordered);
                LOGGER.info("Orders waiting to be collected: {}", DonutOrders.waiting(player));
            } else {
                giveInShulkers(server, bill);
            }
            // DONUT_SCHEMATIC_DIRT: fewer helper blocks than the default 448, to see the builder take back those of earlier walks.
            String dirtText = System.getenv("DONUT_SCHEMATIC_DIRT");
            server.runCommand("give @a minecraft:dirt " + (dirtText == null || dirtText.isBlank() ? 448 : Integer.parseInt(dirtText.trim())));
            server.runCommand("give @a minecraft:diamond_pickaxe");
            server.runCommand("give @a minecraft:diamond_shovel");
            context.waitTicks(10);
            context.runOnClient(client -> builder.setEnabled(true));
            // It starts on its next tick.
            context.waitFor(client -> builder.isActive() || !settings.enabled, 100);
            if (!preplace.isEmpty() || fromY != Integer.MIN_VALUE) {
                // With blocks set in place, what the rest still takes can differ from the bill less them (water set in
                // place may run off, a machine move itself): what the build's own plan finds short is handed out too.
                context.waitTicks(5);
                Map<Item, Integer> topUp = context.computeOnClient(client -> builder.shortage(Materials.of(client.player.getInventory())));
                // What waits on the orders counts as had: it is collected when the build runs out.
                if (fromOrders) {
                    Map<Item, Integer> waiting = DonutOrders.waiting(player);
                    topUp.replaceAll((item, count) -> count - waiting.getOrDefault(item, 0));
                    topUp.values().removeIf(count -> count <= 0);
                }
                if (!topUp.isEmpty()) {
                    if (fromOrders) DonutOrders.fill(player, topUp);
                    else {
                        // Water capped in the bill stays capped: the plan counts every source short, but the empty
                        // buckets carried are filled where water comes back.
                        if (bill.containsKey(Items.BUCKET)) {
                            topUp.remove(Items.WATER_BUCKET);
                            topUp.remove(Items.BUCKET);
                        }
                        capWater(topUp, wettestLayer, carriedBoxes(server));
                        giveInShulkers(server, topUp);
                    }
                    LOGGER.info("Topped up what the plan still needs: {}", topUp);
                }
            }
            long started = System.currentTimeMillis();
            // DONUT_SCHEMATIC_MINUTES gives a big schematic longer than the 50 minutes the test waits by default.
            String limit = System.getenv("DONUT_SCHEMATIC_MINUTES");
            long minutes = limit == null || limit.isBlank() ? 50 : Long.parseLong(limit.strip());
            int shot = 0;
            while (context.computeOnClient(client -> builder.isActive())) {
                context.waitTicks(20);
                if (System.currentTimeMillis() - started > minutes * 60_000L) {
                    String status = context.computeOnClient(client -> builder.status());
                    LOGGER.warn("Gave up after {} minutes: {}", minutes, status);
                    break;
                }
                if (++shot % 45 == 0) {
                    SchematicBuilder.Progress progress = context.computeOnClient(client -> builder.progress());
                    String status = context.computeOnClient(client -> builder.status());
                    LOGGER.info("Progress: {} ({})", progress, status);
                    context.takeScreenshot(TestScreenshotOptions.of("schematic-file-progress-" + shot / 45).withSize(1280, 800));
                }
            }
            String finalStatus = context.computeOnClient(client -> builder.status());
            if (fromOrders) LOGGER.info("Left waiting on the orders: {}", DonutOrders.waiting(player));
            LOGGER.info("Builder finished in {} s: {}", (System.currentTimeMillis() - started) / 1000, finalStatus);
            context.waitTicks(20);
            context.takeScreenshot(TestScreenshotOptions.of("schematic-file-built").withSize(1280, 800));
            List<String> differences = compare(server, schematic);
            LOGGER.info("{} lines of differences", differences.size());
            for (String line : differences.subList(0, Math.min(120, differences.size()))) LOGGER.info("  {}", line);
            int dirt = server.computeOnServer(s -> {
                int count = 0;
                for (BlockPos pos : BlockPos.betweenClosed(x0, 65, z0, x1, ORIGIN.getY() + size.getY() + 8, z1)) {
                    if (s.overworld().getBlockState(pos).is(Blocks.DIRT)) count++;
                }
                return count;
            });
            int holes = server.computeOnServer(s -> {
                int count = 0;
                for (BlockPos pos : BlockPos.betweenClosed(x0, 61, z0, x1, 64, z1)) if (!s.overworld().getBlockState(pos).is(Blocks.SMOOTH_STONE)) count++;
                return count;
            });
            LOGGER.info("Helper dirt left standing: {}; ground left dug out or filled otherwise: {}; flew: {}", dirt, holes, flew.get());
            require(!flew.get(), "The player flew");
            require(finalStatus.startsWith("Finished"), "The build did not finish: " + finalStatus);
            require(differences.isEmpty(), differences.size() + " lines of differences; see the log");
            require(dirt == 0, dirt + " helper blocks were left standing");
            require(holes == 0, holes + " blocks of the ground were left dug out or filled with something else");
        } finally {
            context.runOnClient(client -> builder.setEnabled(false));
        }
    }

    /**
     * The places, as {@link BlockPos#asLong}, of the schematic's blocks that DONUT_SCHEMATIC_RESUME's log says were
     * placed there ("Placed Block{id}... at x, y, z", or "Resumed Block" for those a run resumed itself), up to the time
     * after an @ if one is given. Empty without it.
     */
    private static Set<Long> resumed(Schematic schematic) {
        String spec = System.getenv("DONUT_SCHEMATIC_RESUME");
        Set<Long> placed = new HashSet<>();
        if (spec == null || spec.isBlank()) return placed;
        int at = spec.lastIndexOf('@');
        String until = at > 1 ? spec.substring(at + 1).strip() : null;
        Path log = Path.of(at > 1 ? spec.substring(0, at).strip() : spec.strip());
        Map<Long, String> logged = new HashMap<>();
        Pattern line = Pattern.compile("^\\[(\\d\\d:\\d\\d:\\d\\d)\\].*?(?:Placed|Resumed) Block\\{\\{?([a-z0-9_:]+)\\}\\}?\\S* at (-?\\d+), (-?\\d+), (-?\\d+):");
        try {
            for (String text : Files.readAllLines(log)) {
                Matcher m = line.matcher(text);
                if (!m.find()) continue;
                if (until != null && m.group(1).compareTo(until) >= 0) break;
                logged.put(BlockPos.asLong(Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5))), m.group(2));
            }
        } catch (IOException e) {
            throw new AssertionError("Can't read DONUT_SCHEMATIC_RESUME " + log, e);
        }
        for (int i = 0; i < schematic.size(); i++) {
            BlockState target = Schematic.place(schematic.state(i), Mirror.NONE, Rotation.NONE);
            long pos = Schematic.place(schematic.pos(i), ORIGIN, Mirror.NONE, Rotation.NONE).asLong();
            String id = logged.get(pos);
            if (id != null && id.equals(BuiltInRegistries.BLOCK.getKey(target.getBlock()).toString())) {
                placed.add(pos);
                // In this run's log too, so a run after it can go on from there with this log alone.
                BlockPos cell = BlockPos.of(pos);
                // Not "{{{}}}": slf4j has no escape for a brace, and printed both.
                LOGGER.info("Resumed Block" + "{" + id + "} at {}, {}, {}:", cell.getX(), cell.getY(), cell.getZ());
            }
        }
        return placed;
    }

    /**
     * The block set in place at {@code pos} for {@code target}: the target, but for half of a double chest whose other
     * half isn't set in place as well. That stands alone, as it did in the run resumed: set as a half, nothing that is
     * placed beside it later joins it, as blocks set in place don't update their neighbours.
     */
    private static BlockState asSet(Schematic schematic, BlockState target, BlockPos pos, Set<Block> preplace, int fromY, Set<Long> resumed) {
        BlockPos partner = BlockRules.chestPartner(pos, target);
        if (partner == null) return target;
        boolean partnerSet = preplace.contains(target.getBlock()) || partner.getY() < fromY || resumed.contains(partner.asLong());
        return partnerSet ? target : target.setValue(ChestBlock.TYPE, ChestType.SINGLE);
    }

    /** The blocks DONUT_SCHEMATIC_PREPLACE names, comma separated, by id with or without "minecraft:". */
    private static Set<Block> preplaced() {
        String list = System.getenv("DONUT_SCHEMATIC_PREPLACE");
        Set<Block> blocks = new HashSet<>();
        if (list == null) return blocks;
        for (String id : list.split("[\\s,]+")) {
            if (id.isEmpty()) continue;
            Identifier name = Identifier.tryParse(id.contains(":") ? id : "minecraft:" + id);
            if (name != null) BuiltInRegistries.BLOCK.getOptional(name).ifPresent(blocks::add);
        }
        return blocks;
    }

    /**
     * Boxes the player may carry at most: the 36 slots less the pickaxe and shovel, two stacks of helper dirt and four
     * slots free, the room the builder takes a box's blocks out into. v4-fresh, its bill 10% spare, was given 34 boxes:
     * with the dirt beside them every slot was full, nothing carried could go back into a box, and the build stopped in
     * its second minute with no room to take blue ice out. A player brings no more than fits, and neither does the test.
     */
    private static final int MOST_BOXES = 36 - 2 - 2 - 4;

    /**
     * Packs everything the build takes into shulker boxes, a stack per slot, and gives them to the player; fails first when
     * they come to more boxes, with those carried already, than {@link #MOST_BOXES}.
     */
    private static void giveInShulkers(TestServerContext server, Map<Item, Integer> bill) {
        List<String> gives = BuildCheck.shulkerGives(bill);
        int carried = carriedBoxes(server);
        require(carried + gives.size() <= MOST_BOXES, "The materials take " + (carried + gives.size()) + " shulker boxes, "
                + carried + " of them carried already; at most " + MOST_BOXES + " leave room to take blocks out of one."
                + " Build fewer layers (DONUT_SCHEMATIC_FROM_Y) or bring less spare (DONUT_SCHEMATIC_SPARE).");
        for (String give : gives) server.runCommand(give);
        LOGGER.info("Gave the materials in {} shulker boxes", gives.size());
    }

    private static int carriedBoxes(TestServerContext server) {
        return server.computeOnServer(s -> {
            int n = 0;
            for (ItemStack stack : s.getPlayerList().getPlayers().getFirst().getInventory()) if (Materials.isShulkerBox(stack)) n++;
            return n;
        });
    }

    /**
     * Water buckets don't stack, and the bill counts one for each source, block full of water and kelp or seagrass spot:
     * ikea_v4 came to 34 boxes so (v4-fresh2), past {@link #MOST_BOXES}. Where they don't all fit with the rest, as many go
     * in as do, but never fewer than the layer that takes the most, and a stack of empty buckets besides: the builder fills
     * them where water comes back, as a player does who brings a few.
     */
    private static void capWater(Map<Item, Integer> bill, int wettestLayer, int carried) {
        int water = bill.getOrDefault(Items.WATER_BUCKET, 0);
        if (water == 0) return;
        int stacks = 0;
        for (Map.Entry<Item, Integer> e : bill.entrySet()) {
            int max = e.getKey().getDefaultMaxStackSize();
            if (e.getKey() != Items.WATER_BUCKET) stacks += (e.getValue() + max - 1) / max;
        }
        int slots = (MOST_BOXES - carried) * 27;
        if (stacks + water <= slots) return;
        int kept = Math.max(wettestLayer, slots - stacks - 1);
        bill.put(Items.WATER_BUCKET, kept);
        bill.merge(Items.BUCKET, Items.BUCKET.getDefaultMaxStackSize(), Integer::sum);
        LOGGER.info("Water buckets: {} of the {} the bill counts, the most a layer takes {}, and {} empty ones to fill",
                kept, water, wettestLayer, Items.BUCKET.getDefaultMaxStackSize());
    }

    /** The most water buckets one layer still to build takes, counted as the bill counts them. */
    private static int wettestLayer(Schematic schematic, Set<Block> preplace, int fromY, Set<Long> resumed) {
        Map<Integer, Integer> byLayer = new HashMap<>();
        for (int i = 0; i < schematic.size(); i++) {
            BlockState target = Schematic.place(schematic.state(i), Mirror.NONE, Rotation.NONE);
            BlockPos pos = Schematic.place(schematic.pos(i), ORIGIN, Mirror.NONE, Rotation.NONE);
            if (preplace.contains(target.getBlock()) || pos.getY() < fromY || resumed.contains(pos.asLong())) continue;
            BlockRules.Kind kind = BlockRules.kind(target);
            boolean bucket = kind == BlockRules.Kind.FLUID && BlockRules.item(target) == Items.WATER_BUCKET
                    || kind == BlockRules.Kind.PLACE && (BlockRules.soaked(target) || BlockRules.wet(target));
            if (bucket) byLayer.merge(pos.getY(), 1, Integer::sum);
        }
        int most = 0;
        for (int n : byLayer.values()) most = Math.max(most, n);
        return most;
    }

    /** Every block the builder places that the world doesn't hold as the schematic has it, with a count per kind. */
    private static List<String> compare(TestServerContext server, Schematic schematic) {
        return server.computeOnServer(s -> BuildCheck.differences(s.overworld(), schematic, ORIGIN));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
