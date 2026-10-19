package dev.donut.client.gametest;

import dev.donut.client.config.SchematicSettings;
import dev.donut.client.schematic.BlockRules;
import dev.donut.client.schematic.LitematicFile;
import dev.donut.client.schematic.Materials;
import dev.donut.client.schematic.Schematic;
import dev.donut.client.schematic.SchematicBuilder;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
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
                settings.helperBlocks = "dirt";
            });
            Map<Item, Integer> bill = new LinkedHashMap<>(context.computeOnClient(client -> builder.bill()));
            // DONUT_SCHEMATIC_PREPLACE names blocks (deepslate, say) set in place before the build: a plain fill that
            // teaches the builder nothing and takes long. DONUT_SCHEMATIC_FROM_Y sets every block below that height in
            // place, so a run starts at the layer where the last one went wrong. Either way they count as built, and
            // what they take isn't handed out.
            Set<Block> preplace = preplaced();
            String fromText = System.getenv("DONUT_SCHEMATIC_FROM_Y");
            int fromY = fromText == null || fromText.isBlank() ? Integer.MIN_VALUE : Integer.parseInt(fromText.strip());
            if (!preplace.isEmpty() || fromY != Integer.MIN_VALUE) {
                Map<Item, Integer> taken = new HashMap<>();
                int placed = server.computeOnServer(s -> {
                    int n = 0;
                    BlockState air = Blocks.AIR.defaultBlockState();
                    for (int i = 0; i < schematic.size(); i++) {
                        BlockState target = Schematic.place(schematic.state(i), Mirror.NONE, Rotation.NONE);
                        BlockPos pos = Schematic.place(schematic.pos(i), ORIGIN, Mirror.NONE, Rotation.NONE);
                        if (!preplace.contains(target.getBlock()) && pos.getY() >= fromY) continue;
                        BlockRules.Kind kind = BlockRules.kind(target);
                        if (kind == BlockRules.Kind.UNPLACEABLE) continue;
                        s.overworld().setBlock(pos, target, Block.UPDATE_CLIENTS);
                        Item item = BlockRules.item(target);
                        if (item != null) taken.merge(item, kind == BlockRules.Kind.FLUID ? 1 : BlockRules.itemsNeeded(air, target), Integer::sum);
                        if (kind == BlockRules.Kind.PLACE && BlockRules.soaked(target)) taken.merge(Items.WATER_BUCKET, 1, Integer::sum);
                        n++;
                    }
                    return n;
                });
                for (Map.Entry<Item, Integer> e : taken.entrySet()) {
                    bill.computeIfPresent(e.getKey(), (item, count) -> count > e.getValue() ? count - e.getValue() : null);
                }
                LOGGER.info("Set {} blocks in place before the build ({}{})", placed, preplace.isEmpty() ? "" : preplace + " ",
                        fromY == Integer.MIN_VALUE ? "" : "everything below y " + fromY);
                context.waitTicks(20);
                // A machine set in place can move itself once its blocks update, a piston pushed or a torch knocked
                // off: what came out otherwise is set again, so the build starts on the layers it was asked to.
                int reset = server.computeOnServer(s -> {
                    int n = 0;
                    for (int i = 0; i < schematic.size(); i++) {
                        BlockState target = Schematic.place(schematic.state(i), Mirror.NONE, Rotation.NONE);
                        BlockPos pos = Schematic.place(schematic.pos(i), ORIGIN, Mirror.NONE, Rotation.NONE);
                        if (!preplace.contains(target.getBlock()) && pos.getY() >= fromY) continue;
                        if (BlockRules.kind(target) == BlockRules.Kind.UNPLACEABLE || s.overworld().getBlockState(pos) == target) continue;
                        s.overworld().setBlock(pos, target, Block.UPDATE_CLIENTS);
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
            giveInShulkers(server, bill);
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
                if (!topUp.isEmpty()) {
                    giveInShulkers(server, topUp);
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

    /** Packs everything the build takes into shulker boxes, a stack per slot, and gives them to the player. */
    private static void giveInShulkers(TestServerContext server, Map<Item, Integer> bill) {
        List<String> gives = BuildCheck.shulkerGives(bill);
        for (String give : gives) server.runCommand(give);
        LOGGER.info("Gave the materials in {} shulker boxes", gives.size());
    }

    /** Every block the builder places that the world doesn't hold as the schematic has it, with a count per kind. */
    private static List<String> compare(TestServerContext server, Schematic schematic) {
        return server.computeOnServer(s -> BuildCheck.differences(s.overworld(), schematic, ORIGIN));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
