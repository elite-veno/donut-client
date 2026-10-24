package dev.donut.client.finder;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Blocks that only players and generated structures place, for ChunkFinder: no natural feature makes planks, bricks,
 * glass, torches, rails, workstations, beds and the like, so one of them means a structure (a village, mineshaft,
 * dungeon, trial chamber, ruin) or someone's build. Left out because nature makes them too: cobblestone and obsidian,
 * which flowing lava makes once a chunk ticks; mossy cobblestone, from taiga boulders; smooth basalt, from geodes;
 * terracotta, from badlands; bone blocks, from fossils; infested stone; and logs, leaves and bee nests.
 */
public final class BuiltBlocks {
    private static final List<TagKey<Block>> TAGS = List.of(BlockTags.PLANKS, BlockTags.STAIRS, BlockTags.SLABS, BlockTags.WALLS,
            BlockTags.FENCES, BlockTags.FENCE_GATES, BlockTags.DOORS, BlockTags.TRAPDOORS, BlockTags.BEDS, BlockTags.BUTTONS,
            BlockTags.PRESSURE_PLATES, BlockTags.RAILS, BlockTags.ALL_SIGNS, BlockTags.BANNERS, BlockTags.WOOL,
            BlockTags.WOOL_CARPETS, BlockTags.CANDLES, BlockTags.SHULKER_BOXES, BlockTags.ANVIL, BlockTags.CAULDRONS,
            BlockTags.FLOWER_POTS, BlockTags.CAMPFIRES);
    /** Parts of block ids that name made things. */
    private static final List<String> PARTS = List.of("brick", "tiles", "glass", "glazed_terracotta", "concrete", "torch", "lantern",
            "bookshelf", "_table", "furnace", "smoker", "chest", "barrel", "ladder", "lever", "repeater", "comparator", "piston",
            "observer", "dispenser", "dropper", "hopper", "redstone_wire", "redstone_lamp", "iron_bars", "chain", "beacon",
            "conduit", "spawner", "vault", "sponge", "purpur", "prismarine", "end_rod", "potted_", "copper", "_skull", "_head",
            "suspicious_", "quartz", "tripwire");
    private static final List<String> PREFIXES = List.of("polished_", "chiseled_", "cut_", "smooth_", "cobbled_");
    private static final Set<String> NAMES = Set.of("bricks", "tnt", "bell", "hay_block", "composter", "lectern", "grindstone",
            "stonecutter", "loom", "brewing_stand", "jukebox", "note_block", "end_portal_frame", "iron_block", "gold_block",
            "diamond_block", "emerald_block", "netherite_block", "lapis_block", "redstone_block", "coal_block", "crying_obsidian",
            "dirt_path", "farmland", "scaffolding", "target", "daylight_detector", "lodestone", "respawn_anchor", "crafter",
            "decorated_pot", "packed_mud", "beehive", "lightning_rod", "nether_portal", "cake", "slime_block", "honey_block",
            "heavy_core");
    /** Natural blocks the name rules above would catch. */
    private static final Set<String> NATURAL = Set.of("smooth_basalt", "infested_stone", "infested_deepslate", "infested_cobblestone");
    /**
     * What gives a structure away: dungeons' and mineshafts' spawners, mineshafts' rails and cobwebs, villages' bells
     * and paths, trial chambers' tuff, copper and vaults, ancient cities' deepslate, worn stone bricks of strongholds,
     * temples and ruins, desert sandstone, monuments' prismarine, ruined portals' crying obsidian and the suspicious
     * blocks of ruins and wells. Players rarely build with these, and if they do, the chunk is still no base spot.
     */
    private static final Set<String> STRUCTURE_NAMES = Set.of("spawner", "trial_spawner", "vault", "rail", "cobweb", "bell",
            "dirt_path", "end_portal_frame", "crying_obsidian", "sea_lantern", "reinforced_deepslate", "suspicious_sand",
            "suspicious_gravel", "cobbled_deepslate");
    private static final List<String> STRUCTURE_PARTS = List.of("mossy_stone_brick", "cracked_stone_brick", "chiseled_stone_brick",
            "infested_", "tuff_brick", "polished_tuff", "chiseled_tuff", "copper", "deepslate_brick", "deepslate_tile",
            "cut_sandstone", "chiseled_sandstone", "smooth_sandstone", "cut_red_sandstone", "chiseled_red_sandstone",
            "smooth_red_sandstone", "prismarine", "mud_brick");

    private BuiltBlocks() { }

    public static boolean built(BlockState state) { return Holder.BUILT.contains(state.getBlock()); }

    /**
     * What mineshafts are made of: their supports, the chains their lanterns hang from in 26.2, rails, cobwebs and
     * torches. A buried chunk of nothing else with supports or chains in it is a piece of a mineshaft; torches alone
     * are a miner's.
     */
    static boolean mineshaft(Block block) { return Holder.MINESHAFT.contains(block); }

    static boolean mineshaftFrame(Block block) { return Holder.MINESHAFT_FRAME.contains(block); }

    /** Whether the block gives a structure away; some, such as cobwebs and reinforced deepslate, are not built blocks. */
    public static boolean structure(BlockState state) { return Holder.STRUCTURE.contains(state.getBlock()); }

    /** The block's id as words, such as "stone bricks", for labels. */
    static String name(Block block) { return BuiltInRegistries.BLOCK.getKey(block).getPath().replace('_', ' '); }

    static boolean structureName(String path) {
        if (path.endsWith("_ore") || path.startsWith("raw_") || path.equals("infested_stone") || path.equals("infested_deepslate")) return false;
        if (STRUCTURE_NAMES.contains(path)) return true;
        for (String part : STRUCTURE_PARTS) if (path.contains(part)) return true;
        return false;
    }

    static boolean builtName(String path) {
        if (NATURAL.contains(path) || path.endsWith("_ore") || path.startsWith("raw_")) return false;
        if (NAMES.contains(path)) return true;
        for (String prefix : PREFIXES) if (path.startsWith(prefix)) return true;
        for (String part : PARTS) if (path.contains(part)) return true;
        return false;
    }

    /** Resolved once the registries are ready. */
    private static final class Holder {
        static final Set<Block> BUILT = build();
        static final Set<Block> STRUCTURE = structures();
        static final Set<Block> MINESHAFT_FRAME = Set.of(Blocks.OAK_PLANKS, Blocks.DARK_OAK_PLANKS, Blocks.OAK_FENCE, Blocks.DARK_OAK_FENCE,
                Blocks.IRON_CHAIN);
        static final Set<Block> MINESHAFT = Set.of(Blocks.OAK_PLANKS, Blocks.DARK_OAK_PLANKS, Blocks.OAK_FENCE, Blocks.DARK_OAK_FENCE,
                Blocks.IRON_CHAIN, Blocks.RAIL, Blocks.COBWEB, Blocks.TORCH, Blocks.WALL_TORCH);

        private static Set<Block> structures() {
            Set<Block> blocks = Collections.newSetFromMap(new IdentityHashMap<>());
            BuiltInRegistries.BLOCK.forEach(block -> {
                if (structureName(BuiltInRegistries.BLOCK.getKey(block).getPath())) blocks.add(block);
            });
            return blocks;
        }

        private static Set<Block> build() {
            Set<Block> blocks = Collections.newSetFromMap(new IdentityHashMap<>());
            BuiltInRegistries.BLOCK.forEach(block -> {
                String path = BuiltInRegistries.BLOCK.getKey(block).getPath();
                if (builtName(path)) blocks.add(block);
                else for (TagKey<Block> tag : TAGS) if (block.defaultBlockState().is(tag)) { blocks.add(block); break; }
            });
            return blocks;
        }
    }
}
