package dev.donut.client.finder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * What BaseFinder counts as signs of players, each with a weight and a cap on how many count. The list was checked
 * against every vanilla 26.2 structure template: most of these never generate, and the few that do are weighted or
 * ruled out where they do (ender chests in end cities, comparators and redstone blocks in ancient cities, concrete
 * reliefs in trial chambers, a diamond block in woodland mansions).
 */
public enum BaseEvidence {
    SHULKER_BOX("shulker box", "shulker boxes", 6, 3, state -> state.is(BlockTags.SHULKER_BOXES)),
    BEACON("beacon", "beacons", 6, 2, state -> state.is(Blocks.BEACON)),
    CONDUIT("conduit", "conduits", 6, 1, state -> state.is(Blocks.CONDUIT)),
    ENCHANTING_TABLE("enchanting table", "enchanting tables", 6, 1, state -> state.is(Blocks.ENCHANTING_TABLE)),
    NETHERITE_BLOCK("netherite block", "netherite blocks", 6, 2, state -> state.is(Blocks.NETHERITE_BLOCK)),
    NETHER_PORTAL("lit portal", "lit portals", 4, 1, state -> state.is(Blocks.NETHER_PORTAL)),
    /** End cities place one; anywhere else it is a player's. */
    ENDER_CHEST("ender chest", "ender chests", 4, 2, state -> state.is(Blocks.ENDER_CHEST)),
    RESPAWN_ANCHOR("respawn anchor", "respawn anchors", 4, 1, state -> state.is(Blocks.RESPAWN_ANCHOR)),
    LODESTONE("lodestone", "lodestones", 4, 1, state -> state.is(Blocks.LODESTONE)),
    EMERALD_BLOCK("emerald block", "emerald blocks", 4, 2, state -> state.is(Blocks.EMERALD_BLOCK)),
    /** Two woodland mansion rooms hold one, so it can't settle a chunk alone. */
    DIAMOND_BLOCK("diamond block", "diamond blocks", 3, 2, state -> state.is(Blocks.DIAMOND_BLOCK)),
    JUKEBOX("jukebox", "jukeboxes", 3, 1, state -> state.is(Blocks.JUKEBOX)),
    DAYLIGHT_DETECTOR("daylight detector", "daylight detectors", 3, 1, state -> state.is(Blocks.DAYLIGHT_DETECTOR)),
    OBSERVER("observer", "observers", 3, 2, state -> state.is(Blocks.OBSERVER)),
    CRAFTER("crafter", "crafters", 3, 1, state -> state.is(Blocks.CRAFTER)),
    HANGING_SIGN("hanging sign", "hanging signs", 3, 2, state -> state.is(BlockTags.ALL_HANGING_SIGNS)),
    /** An igloo basement and one taiga house have a sign. */
    SIGN("sign", "signs", 2, 3, state -> state.is(BlockTags.SIGNS) || state.is(BlockTags.WALL_SIGNS)),
    POWERED_RAIL("powered rail", "powered rails", 2, 3,
            state -> state.is(Blocks.POWERED_RAIL) || state.is(Blocks.DETECTOR_RAIL) || state.is(Blocks.ACTIVATOR_RAIL)),
    COMPARATOR("comparator", "comparators", 2, 2, state -> state.is(Blocks.COMPARATOR)),
    REDSTONE_BLOCK("redstone block", "redstone blocks", 2, 2, state -> state.is(Blocks.REDSTONE_BLOCK)),
    TARGET("target", "targets", 2, 1, state -> state.is(Blocks.TARGET)),
    CONCRETE_POWDER("concrete powder", "concrete powder", 2, 3, state -> Names.CONCRETE_POWDER.contains(state.getBlock())),
    /** Trial chambers carve reliefs from it. */
    CONCRETE("concrete", "concrete", 1, 3, state -> Names.CONCRETE.contains(state.getBlock())),
    /** A trial chamber's disposal room has one. */
    HOPPER("hopper", "hoppers", 1, 3, state -> state.is(Blocks.HOPPER)),
    NOTE_BLOCK("note block", "note blocks", 1, 2, state -> state.is(Blocks.NOTE_BLOCK)),
    ANVIL("anvil", "anvils", 1, 1, state -> state.is(BlockTags.ANVIL)),
    /** Outposts, end cities and savanna villages hang a few. */
    BANNER("banner", "banners", 1, 2, state -> state.is(BlockTags.BANNERS)),
    /** Beds explode in the Nether, so one placed there is a player's. Villages, igloos and trial chambers have them elsewhere. */
    NETHER_BED("bed", "beds", 3, 2, state -> state.is(BlockTags.BEDS));

    /** A storage room: many more chests and barrels in one chunk than any structure places together. */
    static final int STORAGE_ROOM = 10, STORAGE_WEIGHT = 3;
    /** A chunk this score or above is suspicious at the default setting: one strong find, or a few weaker ones. */
    public static final double DEFAULT_MIN_SCORE = 6;

    private static final BaseEvidence[] VALUES = values();
    /** Any block worth reading a section for. */
    static final Predicate<BlockState> ANY = state -> of(state) != null || storage(state);

    final String one, many;
    final int weight, cap;
    private final Predicate<BlockState> matches;

    BaseEvidence(String one, String many, int weight, int cap, Predicate<BlockState> matches) {
        this.one = one;
        this.many = many;
        this.weight = weight;
        this.cap = cap;
        this.matches = matches;
    }

    /** The evidence this block is, or null. */
    static BaseEvidence of(BlockState state) {
        for (BaseEvidence evidence : VALUES) if (evidence.matches.test(state)) return evidence;
        return null;
    }

    static boolean storage(BlockState state) {
        return state.is(Blocks.CHEST) || state.is(Blocks.TRAPPED_CHEST) || state.is(Blocks.BARREL);
    }

    /** Where worldgen places this evidence itself, so it says nothing about players there. */
    boolean natural(boolean nether, boolean end, boolean ancientCity) {
        return switch (this) {
            case ENDER_CHEST -> end;
            case NETHER_BED -> !nether;
            case COMPARATOR, REDSTONE_BLOCK, TARGET, NOTE_BLOCK -> ancientCity;
            default -> false;
        };
    }

    /**
     * A chunk's score from its counts, by evidence ordinal, plus its chests and barrels; each kind counts up to its cap.
     * Item frames and armor stands, which are entities, come in as {@code extra}.
     */
    static int score(int[] counts, int storage, int extra) {
        int score = extra;
        for (BaseEvidence evidence : VALUES) score += Math.min(counts[evidence.ordinal()], evidence.cap) * evidence.weight;
        if (storage >= STORAGE_ROOM) score += STORAGE_WEIGHT;
        return score;
    }

    /** The strongest reasons first, such as "shulker box, 3 signs, beacon". */
    static String reasons(int[] counts, int storage, List<String> extra) {
        List<BaseEvidence> found = new ArrayList<>();
        for (BaseEvidence evidence : VALUES) if (counts[evidence.ordinal()] > 0) found.add(evidence);
        found.sort((a, b) -> Integer.compare(Math.min(counts[b.ordinal()], b.cap) * b.weight, Math.min(counts[a.ordinal()], a.cap) * a.weight));
        List<String> parts = new ArrayList<>();
        for (BaseEvidence evidence : found) {
            int count = counts[evidence.ordinal()];
            parts.add(count == 1 ? evidence.one : count + " " + evidence.many);
        }
        if (storage >= STORAGE_ROOM) parts.add(storage + " chests");
        parts.addAll(extra);
        return String.join(", ", parts.subList(0, Math.min(parts.size(), 3))) + (parts.size() > 3 ? ", ..." : "");
    }

    /** Block families without a tag, by name; resolved once the registry is ready. */
    private static final class Names {
        static final Set<Block> CONCRETE = byName("_concrete");
        static final Set<Block> CONCRETE_POWDER = byName("_concrete_powder");

        private static Set<Block> byName(String suffix) {
            Set<Block> blocks = Collections.newSetFromMap(new IdentityHashMap<>());
            BuiltInRegistries.BLOCK.forEach(block -> {
                if (BuiltInRegistries.BLOCK.getKey(block).getPath().endsWith(suffix)) blocks.add(block);
            });
            return blocks;
        }
    }
}
