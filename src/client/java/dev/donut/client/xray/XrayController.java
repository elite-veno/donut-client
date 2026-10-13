package dev.donut.client.xray;

import dev.donut.client.config.ClientConfig;
import dev.donut.client.config.XraySettings;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Hides common terrain while chunk meshes are built, so ores, cave contents, chests, spawners and
 * anything players built stand out. Gameplay is unaffected: only the rendered copy of each block changes.
 * Sideways deepslate stays visible, since worldgen only places it upright.
 */
public final class XrayController {
    private static final Direction[] DIRECTIONS = Direction.values();
    /** The current filter, read by chunk builder threads; null while X-ray is off. */
    private static volatile Filter active;

    private final ClientConfig config;
    private Snapshot applied;

    public XrayController(ClientConfig config) { this.config = config; }

    public static boolean enabled() { return active != null; }

    /** Whether a neighbor may be missing from the mesh, so the face against it must be drawn. */
    public static boolean mayHide(BlockState state) {
        Filter filter = active;
        return filter != null && (filter.hides(state) || filter.exposedOresOnly && isOre(state));
    }

    /** The state to render at this position: air when X-ray hides it. */
    public static BlockState filter(BlockGetter region, BlockPos pos, BlockState state) {
        Filter filter = active;
        if (filter == null || state.isAir()) return state;
        if (filter.hides(state)) return Blocks.AIR.defaultBlockState();
        if (filter.exposedOresOnly && isOre(state) && !exposed(region, pos)) return Blocks.AIR.defaultBlockState();
        return state;
    }

    /** Whether X-ray currently hides this block everywhere. */
    public static boolean hides(BlockState state) {
        Filter filter = active;
        return filter != null && filter.hides(state);
    }

    /** Rebuilds the world's meshes whenever X-ray turns on or off or its block list changes. */
    public void tick(Minecraft client) {
        XraySettings s = config.xray;
        Snapshot wanted = s.enabled ? new Snapshot(s.hideStone, s.hideSoil, s.hideNether, s.hideEndStone,
                s.hideWater, s.hideLava, s.exposedOresOnly, s.extraHidden.toLowerCase(Locale.ROOT)) : null;
        if (Objects.equals(wanted, applied)) return;
        applied = wanted;
        active = wanted == null ? null : new Filter(blocks(wanted), wanted.exposedOresOnly());
        if (client.levelRenderer != null && client.level != null) {
            client.levelRenderer.invalidateCompiledGeometry(client.level, client.options, client.gameRenderer.mainCamera(), client.getBlockColors());
        }
    }

    /** Turns X-ray off immediately, such as when the game closes. */
    public void close() {
        active = null;
        applied = null;
    }

    static boolean isOre(BlockState state) {
        // 26.2 keeps tags only for the ores that share them with raw blocks; the rest are listed by block.
        return state.is(BlockTags.IRON_ORES) || state.is(BlockTags.GOLD_ORES) || state.is(BlockTags.COPPER_ORES)
                || state.is(Blocks.COAL_ORE) || state.is(Blocks.DEEPSLATE_COAL_ORE)
                || state.is(Blocks.REDSTONE_ORE) || state.is(Blocks.DEEPSLATE_REDSTONE_ORE)
                || state.is(Blocks.LAPIS_ORE) || state.is(Blocks.DEEPSLATE_LAPIS_ORE)
                || state.is(Blocks.DIAMOND_ORE) || state.is(Blocks.DEEPSLATE_DIAMOND_ORE)
                || state.is(Blocks.EMERALD_ORE) || state.is(Blocks.DEEPSLATE_EMERALD_ORE)
                || state.is(Blocks.NETHER_QUARTZ_ORE) || state.is(Blocks.ANCIENT_DEBRIS);
    }

    /** Anti-xray plugins only replace blocks whose six neighbors all conduct redstone, like stone. */
    private static boolean exposed(BlockGetter region, BlockPos pos) {
        BlockPos.MutableBlockPos neighbor = new BlockPos.MutableBlockPos();
        for (Direction direction : DIRECTIONS) {
            neighbor.setWithOffset(pos, direction);
            if (!region.getBlockState(neighbor).isRedstoneConductor(region, neighbor)) return true;
        }
        return false;
    }

    static Set<Block> blocks(Snapshot s) {
        Set<Block> hidden = Collections.newSetFromMap(new IdentityHashMap<>());
        if (s.stone()) Collections.addAll(hidden, Blocks.STONE, Blocks.GRANITE, Blocks.DIORITE, Blocks.ANDESITE,
                Blocks.DEEPSLATE, Blocks.TUFF, Blocks.CALCITE, Blocks.SMOOTH_BASALT, Blocks.BEDROCK, Blocks.DRIPSTONE_BLOCK,
                Blocks.POINTED_DRIPSTONE, Blocks.INFESTED_STONE, Blocks.INFESTED_DEEPSLATE);
        if (s.soil()) Collections.addAll(hidden, Blocks.DIRT, Blocks.COARSE_DIRT, Blocks.ROOTED_DIRT, Blocks.GRASS_BLOCK,
                Blocks.PODZOL, Blocks.MYCELIUM, Blocks.MUD, Blocks.GRAVEL, Blocks.SAND, Blocks.RED_SAND, Blocks.SANDSTONE,
                Blocks.RED_SANDSTONE, Blocks.CLAY, Blocks.SNOW_BLOCK);
        if (s.nether()) Collections.addAll(hidden, Blocks.NETHERRACK, Blocks.BASALT, Blocks.BLACKSTONE, Blocks.SOUL_SAND,
                Blocks.SOUL_SOIL, Blocks.CRIMSON_NYLIUM, Blocks.WARPED_NYLIUM, Blocks.MAGMA_BLOCK);
        if (s.endStone()) hidden.add(Blocks.END_STONE);
        if (s.water()) Collections.addAll(hidden, Blocks.WATER, Blocks.SEAGRASS, Blocks.TALL_SEAGRASS, Blocks.KELP,
                Blocks.KELP_PLANT, Blocks.BUBBLE_COLUMN);
        if (s.lava()) hidden.add(Blocks.LAVA);
        for (String id : s.extra().split("[,;\\s]+")) {
            if (id.isBlank()) continue;
            Identifier key = Identifier.tryParse(id.contains(":") ? id : "minecraft:" + id);
            if (key == null) continue;
            BuiltInRegistries.BLOCK.getOptional(key).ifPresent(block -> {
                if (block != Blocks.AIR) hidden.add(block);
            });
        }
        return hidden;
    }

    record Snapshot(boolean stone, boolean soil, boolean nether, boolean endStone, boolean water, boolean lava,
                    boolean exposedOresOnly, String extra) { }

    private record Filter(Set<Block> blocks, boolean exposedOresOnly) {
        boolean hides(BlockState state) {
            if (!blocks.contains(state.getBlock()) || state.hasBlockEntity()) return false;
            // Players can place deepslate on its side; worldgen never does.
            return !state.is(Blocks.DEEPSLATE) || state.getValue(RotatedPillarBlock.AXIS) == Direction.Axis.Y;
        }
    }
}
