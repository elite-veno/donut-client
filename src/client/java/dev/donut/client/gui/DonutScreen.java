package dev.donut.client.gui;

import com.mojang.blaze3d.platform.InputConstants;
import dev.donut.client.config.ClientConfig;
import dev.donut.client.config.FinderSettings;
import dev.donut.client.config.RenderMode;
import dev.donut.client.finder.ActivityArea;
import dev.donut.client.finder.ActivityStats;
import dev.donut.client.finder.ChunkStats;
import dev.donut.client.finder.DebrisStats;
import dev.donut.client.finder.FinderScanner;
import dev.donut.client.finder.GoodChunk;
import dev.donut.client.input.ModuleKeybinds;
import dev.donut.client.miner.ChunkMiner;
import dev.donut.client.miner.Excavator;
import dev.donut.client.miner.Kit;
import dev.donut.client.schematic.Materials;
import dev.donut.client.schematic.Schematic;
import dev.donut.client.schematic.SchematicBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Field;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;
import java.util.function.DoubleSupplier;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import static dev.donut.client.gui.UiPaint.*;

/**
 * The Donut Client menu: a sidebar of module categories and pages, a header with the page's title and a search box,
 * and the page itself. A category lists its modules as cards; a card's switch turns the module on or off, and clicking
 * the card opens its settings, one row per setting with a short description. Settings take effect immediately.
 */
public final class DonutScreen extends Screen {
    private static final int SIDEBAR_W = 148, NAV_H = 22, CARD_H = 38, CARD_GAP = 6, HEADER_H = 50, MAX_W = 720, MAX_H = 420;
    /** The menu needs this much room; smaller screens scale the whole GUI down to fit it. */
    private static final float MIN_W = 560, MIN_H = 340;
    /** The default accent color. */
    public static final int MINT = 0x5BE3C8;
    private static final int TEXT = 0xFFF0F6F5, LABEL = 0xFFCAD7D5, MUTED = 0xFF8EA3A1, DIM = 0xFF748C8A;
    private static final int WINDOW = 0xF6071112, SIDEBAR = 0xFF0A1718, CARD = 0xFF0C1A1B, CARD_HOVER = 0xFF102324;
    private static final int EDGE = 0xFF1B3031, EDGE_HOVER = 0xFF2A4646, FIELD = 0xFF061011, TRACK = 0xFF2A3D3E;
    private static final int WARN = 0xFFF2B35E, DANGER = 0xFFFF6F6F, GOOD = 0xFF6FE3A6;
    private static final int[] PALETTE = {MINT, 0x3FC1D6, 0x68B4FF, 0x8C9BFF, 0xBA8CFF, 0xEC80B5,
            0xFF707B, 0xFF975F, 0xF2B35E, 0xC5E774, 0x7EDD8E, 0xF0F6F5};
    /** The page and the module whose settings are open; kept while the game runs, so the menu reopens as it was left. */
    private static String pageId = "finders", detailName;
    /** Settings showing their "More options", kept the same way. */
    private static final Set<String> MORE = new HashSet<>();
    /** Where each page and module's settings were scrolled to, kept the same way. */
    private static final Map<String, Double> SCROLLS = new HashMap<>();

    private final ClientConfig config;
    private final Runnable save;
    private final Supplier<ActivityStats> activityStats;
    private final Supplier<String> rtpStatus;
    private final String version;
    private final List<NavGroup> groups = new ArrayList<>();
    private final Map<String, Page> pages = new LinkedHashMap<>();
    /** The scrolling sidebar list, the scrolling page content, and the fixed parts of the window around them. */
    private final Region nav = new Region(), content = new Region(), chrome = new Region();
    private final List<Hit> hits = new ArrayList<>();
    /** Where each control sits in its region's content, clipped or not, so it can be scrolled into view. */
    private final Map<String, Place> places = new HashMap<>();
    private final Map<String, Double> animations = new HashMap<>();
    private final Deque<int[]> clips = new ArrayDeque<>();
    /** Values deferred sliders hold while dragged, and where each goes on release. */
    private final Map<String, Double> drafts = new HashMap<>();
    private final Map<String, DoubleConsumer> commits = new HashMap<>();
    /** Text already cut to fit, by size, width and text. */
    private final Map<String, String> fitted = new HashMap<>();
    /** Each module's slider value box width, the widest of its sliders' values, so their tracks line up. */
    private final Map<String, Integer> sliderBoxes = new HashMap<>();
    /** Line breaks and tabs in pasted text, which separate entries. */
    private static final java.util.regex.Pattern SEPARATORS = java.util.regex.Pattern.compile("\\s*[\\r\\n\\t]+\\s*");
    /** What a cut must not end on before its ellipsis. */
    private static final java.util.regex.Pattern TRAILING = java.util.regex.Pattern.compile("[\\s.,;:]+$");
    private GuiGraphicsExtractor graphics;
    private float scale = 1;
    /** Screen pixels per layout unit; whole, so the UI font draws sharp. */
    private int pixels = 1;
    private int layoutW, layoutH;
    /** The window's place and size. */
    private int wx, wy, ww, wh;
    private double mouseX, mouseY;
    private long lastFrame;
    private double frameSeconds;
    private boolean moving, settled;
    /** The region being drawn, and the region under the mouse; only that one shows hover states. */
    private Region drawing, hovered;
    private int contentOrigin;
    /** A short explanation shown beside the mouse, for controls too small to carry their own. */
    private String tooltip;
    private String query = "";
    private boolean searchFocused;
    /** The focused text field, if any; its edits are saved on Enter or when focus leaves it, and Esc drops them. */
    private String textField;
    /** The focused field's text; it is only applied, and checked, when the edit is saved. */
    private String textBuffer = "", textOriginal = "";
    /** The focused field's character limit; never below what it already held. */
    private int textLimit = 128;
    private Consumer<String> textSetter;
    /** Whether the field's whole text is selected, as it is on focus, so typing or pasting replaces it. */
    private boolean textSelected;
    private boolean textSecret;
    /** For a guarded field, what saving would do; it saves on a second Enter of the same text, and never on focus loss. */
    private Function<String, Confirm> textConfirm;
    private String textArmed;
    private long textArmedUntil;
    /** A short message in the focused field's description, such as why a paste was refused, and until when. */
    private String textNotice;
    private long textNoticeUntil;
    /** The Enter or Space key that just activated a control, so its key repeats do not activate it again. */
    private InputConstants.Key activationKey;
    /** The last control clicked, so the second click of a double click on the same control counts once. */
    private String lastClicked;
    private boolean dirty;
    private Hit dragging;
    private String keyboardFocus;
    private ColorEditor colorEditor;
    private int binding = -1;
    private InputConstants.Key capturedKey;

    public DonutScreen(ClientConfig config, Runnable save) { this(config, save, () -> ActivityStats.EMPTY); }

    public DonutScreen(ClientConfig config, Runnable save, Supplier<ActivityStats> activityStats) {
        this(config, save, activityStats, () -> "Idle");
    }

    public DonutScreen(ClientConfig config, Runnable save, Supplier<ActivityStats> activityStats, Supplier<String> rtpStatus) {
        super(Component.literal("Ven Client"));
        this.config = config;
        this.save = save;
        this.activityStats = activityStats;
        this.rtpStatus = rtpStatus;
        version = FabricLoader.getInstance().getModContainer("donut")
                .map(mod -> "v" + mod.getMetadata().getVersion().getFriendlyString()).orElse("");
        group("Modules",
                page("finders", "Finders", "search", List.of(storage(), spawners(), debris(), tunnels(), entities(), activity(), bases(),
                        chunks())),
                page("render", "Render", "eye", List.of(xray(), fullbright(), freecam())),
                page("utility", "Utility", "rtp", List.of(autoRtp())),
                page("build", "Build", "cube", List.of(schematicBuilder(), chunkMiner())),
                page("hud", "HUD", "monitor", List.of(
                        module("ArrayList", "Active module names at the top right", "hud array list modules",
                                () -> config.showHud, () -> change(() -> config.showHud = !config.showHud), null),
                        module("FinderInfo", "Counts, distance and direction of the nearest finds", "hud finder info counts distance",
                                () -> config.hud.info, () -> change(() -> config.hud.info = !config.hud.info), null),
                        module("Labels", "Names and distances on the nearest highlights", "hud labels distance names",
                                () -> config.hud.labels, () -> change(() -> config.hud.labels = !config.hud.labels), c ->
                                        c.slider("count", "Labels shown", "How many of the nearest highlights get a label",
                                                () -> config.hud.maxLabels, v -> config.hud.maxLabels = (int) Math.round(v), 1, 32, 1, "")))));
        group("General",
                page("keybinds", "Keybinds", "keyboard", "Keys that turn modules on or off", this::drawKeybinds),
                page("theme", "Theme", "palette", "Colors and effects", this::drawTheme));
        if (!pages.containsKey(pageId)) pageId = "finders";
        // Configs from before this design carry the winter theme's colors; they move to its mint once.
        if (config.gui.theme == null) {
            config.gui.theme = "mint";
            config.accentColor = MINT;
            config.gui.snow = false;
            save.run();
        }
        content.scroll = content.scrollTarget = SCROLLS.getOrDefault(view(), 0.0);
    }

    private void group(String title, Page... list) {
        groups.add(new NavGroup(title, List.of(list)));
        for (Page p : list) pages.put(p.id, p);
    }

    private static Page page(String id, String title, String icon, List<Module> modules) {
        return new Page(id, title, icon, null, modules, null, null);
    }

    private static Page page(String id, String title, String icon, String subtitle, Consumer<Column> content) {
        return new Page(id, title, icon, subtitle, null, content, null);
    }

    // Modules -----------------------------------------------------------------------------------

    private Module storage() {
        FinderSettings s = config.storage;
        return finder(0, "StorageFinder", "Chests, shulkers, barrels, hoppers and more",
                "storage chests trapped ender shulkers barrels hoppers furnaces droppers dispensers minecarts", s, c -> {
            c.toggle("perType", "Colors by type", "Give each storage family its own color", () -> s.perTypeColors, () -> s.perTypeColors = !s.perTypeColors);
            // With colors by type, every kind has its own color and the base color is not used.
            if (s.perTypeColors) {
                c.color("chestColor", "Chests, barrels & carts", () -> s.chestColor, v -> s.chestColor = v);
                c.color("shulkerColor", "Shulkers", () -> s.shulkerColor, v -> s.shulkerColor = v);
                c.color("dropperColor", "Droppers, dispensers & hoppers", () -> s.dropperColor, v -> s.dropperColor = v);
                c.color("furnaceColor", "Furnaces", () -> s.furnaceColor, v -> s.furnaceColor = v);
            } else {
                c.color("color", "Color", () -> s.color, v -> s.color = v);
            }
        }, c -> {
            c.section("Filters");
            c.chip("chests", "Chests", "Standard and double chests", () -> s.chests, () -> s.chests = !s.chests);
            c.chip("trapped", "Trapped", "Trapped chests, which trigger redstone", () -> s.trappedChests, () -> s.trappedChests = !s.trappedChests);
            c.chip("ender", "Ender", "Personal ender storage", () -> s.enderChests, () -> s.enderChests = !s.enderChests);
            c.chip("shulkers", "Shulkers", "Every shulker box color", () -> s.shulkers, () -> s.shulkers = !s.shulkers);
            c.chip("barrels", "Barrels", "Barrel storage", () -> s.barrels, () -> s.barrels = !s.barrels);
            c.chip("hoppers", "Hoppers", "Hoppers, common in farms and sorters", () -> s.hoppers, () -> s.hoppers = !s.hoppers);
            c.chip("droppers", "Droppers", "Dropper blocks", () -> s.droppers, () -> s.droppers = !s.droppers);
            c.chip("dispensers", "Dispensers", "Dispenser blocks", () -> s.dispensers, () -> s.dispensers = !s.dispensers);
            c.chip("furnaces", "Furnaces", "Furnaces, smokers and blast furnaces", () -> s.furnaces, () -> s.furnaces = !s.furnaces);
            c.chip("minecarts", "Minecarts & boats", "Chest and hopper minecarts, as in mineshafts, and chest boats and rafts",
                    () -> s.minecarts, () -> s.minecarts = !s.minecarts);
        });
    }

    private Module spawners() {
        FinderSettings s = config.spawner;
        return finder(1, "SpawnerFinder", "Spawners, and which ones players visited", "spawners trial monster activated", s, c -> {
            c.color("color", "Spawners", () -> s.color, v -> s.color = v);
            c.color("secondaryColor", "Activated", () -> s.secondaryColor, v -> s.secondaryColor = v);
        }, c -> {
            c.section("Filters");
            c.chip("trial", "Trial spawners", "Include trial chamber spawners; monster spawners are always shown",
                    () -> s.trialSpawners, () -> s.trialSpawners = !s.trialSpawners);
            c.chip("activated", "Mark activated", "A changed timer means a player stood within 16 blocks of it, or placed it",
                    () -> s.activatedSpawners, () -> s.activatedSpawners = !s.activatedSpawners);
        });
    }

    private Module debris() {
        FinderSettings s = config.debris;
        return finder(3, "DebrisFinder", "Exposed ancient debris, and what mining reveals", "debris ancient netherite nether ores seed", s,
                c -> {
            c.color("color", "Debris", () -> s.color, v -> s.color = v);
            c.color("secondaryColor", "Seed estimates", () -> s.secondaryColor, v -> s.secondaryColor = v);
            c.color("tertiaryColor", "Search areas", () -> s.tertiaryColor, v -> s.tertiaryColor = v);
        }, c -> {
            c.note("Can't find debris DonutSMP hides; shows exposed debris and lights up what mining reveals.", DIM);
            c.section("Detection");
            c.toggle("filterFakes", "Filter fakes", "Hide fake debris that anti-xray scatters through netherrack",
                    () -> s.filterFakes, () -> s.filterFakes = !s.filterFakes);
            c.toggle("notify", "Reveal alert", "Sound and message when mining uncovers debris", () -> s.notify, () -> s.notify = !s.notify);
            c.toggle("searchAreas", "Search areas", "Outline 16-block sections the server's block data says still hold hidden debris",
                    () -> s.searchAreas, () -> s.searchAreas = !s.searchAreas);
            c.toggle("seedEstimates", "Seed estimates", "Off by default: DonutSMP seeds its debris separately. Turns itself off when revealed spots keep coming up empty",
                    () -> s.seedEstimates, () -> s.seedEstimates = !s.seedEstimates);
            if (s.seedEstimates) {
                c.field("seed", "World seed", "The server's seed; singleplayer worlds use their own", () -> s.seed, v -> s.seed = v.trim(), false);
            }
            debrisScan(c, s);
        });
    }

    /** Why DebrisFinder shows what it shows, so a server's anti-xray isn't mistaken for a broken finder. */
    private void debrisScan(Column c, FinderSettings s) {
        c.section("Live scan");
        if (!s.enabled) { c.note("Enable DebrisFinder to scan loaded chunks.", MUTED); return; }
        DebrisStats stats = FinderScanner.instance() == null ? DebrisStats.EMPTY : FinderScanner.instance().debrisStats();
        c.info("Chunks", Integer.toString(stats.scannedChunks()), TEXT);
        c.info("Buried", Integer.toString(stats.buried()), TEXT);
        c.info("Exposed", Integer.toString(stats.exposed()), TEXT);
        if (stats.hiddenFakes() > 0) c.info("Fakes hidden", stats.hiddenFakes() + " in " + stats.fakedChunks() + " chunks", TEXT);
        if (s.searchAreas && stats.searchAreas() > 0) c.info("Search areas", Integer.toString(stats.searchAreas()), TEXT);
        if (stats.seeded()) {
            c.info("Seed estimates", Integer.toString(stats.estimates()), TEXT);
            if (stats.bedrockReady()) c.info("Bedrock check", Math.round(100.0 * stats.bedrockMatched() / stats.bedrockChecked()) + "% match",
                    stats.bedrockMismatch() ? WARN : GOOD);
            if (stats.seedChecked() > 0) c.info("Seed check", stats.seedMatched() + " of " + stats.seedChecked() + " real debris",
                    stats.debrisMismatch() ? WARN : GOOD);
            int verified = stats.seedChunks() + stats.offSeedChunks();
            if (verified > 0) c.info("Seed applies", stats.seedChunks() + " of " + verified + " chunks", stats.seedChunks() > 0 ? GOOD : WARN);
            if (stats.dugOut() > 0) c.info("Seed spots open (mined or cave)", Integer.toString(stats.dugOut()), TEXT);
        }
        if (stats.scannedChunks() > 0 && !stats.nether()) {
            c.note("Ancient debris only generates in the Nether.", WARN);
        } else if (stats.bedrockMismatch()) {
            c.note("This Nether's bedrock doesn't match the seed: it is another world's seed, so its estimates are wrong.", WARN);
        } else if (stats.oresRandomized()) {
            c.note("The seed is right, but real debris is elsewhere: this server places ores from its own random seeds, so estimates can't work here.", WARN);
        } else if (stats.seedMismatch()) {
            c.note("Real debris here is not where this seed puts it: the seed is not this world's, so its estimates are wrong.", WARN);
        } else if (stats.seeded() && stats.offSeedChunks() > 0 && stats.seedChunks() == 0) {
            c.note("This area's gravel and blackstone aren't where the seed puts them: the server generated it another way, so it gets no estimates.", WARN);
        } else if (stats.allDugOut()) {
            c.note("Every seed spot around here is already dug out: other players mined this debris. Try another area.", WARN);
        } else if (stats.seeded() && (stats.fakedChunks() > 0 || stats.likelyHidden())) {
            c.note("This server hides debris (anti-xray). Seed estimates mark the netherrack where the world seed placed it.", WARN);
        } else if (stats.fakedChunks() > 0) {
            c.note("This server fills buried netherrack with fake debris (anti-xray). Shown: exposed debris, and debris it reveals as you mine.", WARN);
        } else if (stats.likelyHidden()) {
            c.note("No buried debris in any loaded chunk: this server hides it (anti-xray). Mined-open debris lights up with a sound.", WARN);
        }
    }

    private Module tunnels() {
        FinderSettings s = config.tunnels;
        return finder(9, "TunnelFinder", "Dug tunnels, holes and staircases; anti-xray never hides dug-out air",
                "tunnels holes shafts staircases stairs dug base underground", s, c -> {
            c.color("color", "Tunnels", () -> s.color, v -> s.color = v);
            c.color("secondaryColor", "Holes", () -> s.secondaryColor, v -> s.secondaryColor = v);
            c.color("tertiaryColor", "Staircases", () -> s.tertiaryColor, v -> s.tertiaryColor = v);
        }, c -> {
            c.section("Detection");
            c.chip("tunnelRuns", "Tunnels", "Straight 1x2 corridors", () -> s.tunnels, () -> s.tunnels = !s.tunnels);
            c.chip("holes", "Holes", "1x1 vertical shafts", () -> s.holes, () -> s.holes = !s.holes);
            c.chip("stairs", "Staircases", "1-wide stairs dug up or down", () -> s.staircases, () -> s.staircases = !s.staircases);
            c.slider("minLength", "Min length", "Raise it if natural caves show up; staircases need half as many steps",
                    () -> s.minLength, v -> s.minLength = v, 4, 32, 1, " blocks");
        });
    }

    private Module entities() {
        FinderSettings s = config.entities;
        return finder(8, "EntityFinder", "Players and mobs through walls, with tracers; only entities the server sends",
                "entities players esp mobs hostile animals items tracers", s, c -> {
            c.color("color", "Players", () -> s.color, v -> s.color = v);
            c.color("secondaryColor", "Hostile mobs", () -> s.secondaryColor, v -> s.secondaryColor = v);
            c.color("tertiaryColor", "Other mobs", () -> s.tertiaryColor, v -> s.tertiaryColor = v);
            c.color("itemColor", "Items", () -> s.itemColor, v -> s.itemColor = v);
        }, c -> {
            c.section("Detection");
            c.chip("players", "Players", "Everyone except you, labeled with their name; server NPCs, which have no tab list entry, are skipped", () -> s.players, () -> s.players = !s.players);
            c.chip("hostiles", "Hostile mobs", "Zombies, creepers, skeletons and other monsters", () -> s.hostiles, () -> s.hostiles = !s.hostiles);
            c.chip("passives", "Other mobs", "Animals, villagers and golems", () -> s.passives, () -> s.passives = !s.passives);
            c.chip("items", "Items", "Items lying on the ground", () -> s.items, () -> s.items = !s.items);
        });
    }

    private Module activity() {
        FinderSettings s = config.activity;
        return finder(4, "ActivityFinder", "Long-loaded chunks from amethyst growth",
                "activity base basefinder players amethyst geode crystals clusters", s,
                c -> c.color("color", "Squares", () -> s.color, v -> s.color = v), c -> {
            c.section("Detection");
            c.slider("minActivity", "Min growth", "Worldgen is near 0%; 50% is about an hour of activity",
                    () -> s.minActivity, v -> s.minActivity = v, 0, 1, .01, "%");
            c.toggle("surface", "Surface marker", "Squares on the surface with a guide down to the geode",
                    () -> s.surfaceMarker, () -> s.surfaceMarker = !s.surfaceMarker);
            c.toggle("allLoaded", "All loaded chunks", "Every chunk your client has, at any distance and depth",
                    () -> s.allLoadedChunks, () -> s.allLoadedChunks = !s.allLoadedChunks);
            activityScan(c, s);
        });
    }

    private Module bases() {
        FinderSettings s = config.bases;
        return finder(10, "BaseFinder", "Chunks with what only players build: shulker boxes, beacons, signs, player-only blocks",
                "bases basefinder base stash player built suspicious chunks shulker beacon signs", s,
                c -> c.color("color", "Bases", () -> s.color, v -> s.color = v), c -> {
            c.section("Detection");
            c.slider("minScore", "Min score", "One shulker box, beacon or enchanting table is 6; raise it if structures show up",
                    () -> s.minBaseScore, v -> s.minBaseScore = v, 2, 30, 1, "");
            c.toggle("allLoaded", "All loaded chunks", "Every chunk your client has, at any distance and depth",
                    () -> s.allLoadedChunks, () -> s.allLoadedChunks = !s.allLoadedChunks);
            c.note("Only blocks open to air count, so anti-xray's buried fake chests don't. Each marked chunk is labeled with its "
                    + "score and what it found.", DIM);
        });
    }

    private Module chunks() {
        FinderSettings s = config.chunks;
        return finder(12, "ChunkFinder", "Base spots no player or structure is near, with solid ground to dig into",
                "chunks chunkfinder good chunk base spot hide hidden fresh untouched rtp kelp vines sugar cane cactus amethyst structure caves", s, c -> {
            c.color("color", "Good chunks", () -> s.color, v -> s.color = v);
            c.color("secondaryColor", "Signs of players", () -> s.secondaryColor, v -> s.secondaryColor = v);
            c.color("tertiaryColor", "Structures", () -> s.tertiaryColor, v -> s.tertiaryColor = v);
        }, c -> {
            c.section("Detection");
            c.slider("clearance", "Clearance", "Rings of chunks around a spot that must be loaded and show no sign of players",
                    () -> s.minClearance, v -> s.minClearance = v, 1, 12, 1, " chunks");
            c.slider("unseen", "Max unseen time", "Spots need enough cane, cactus, kelp or vines around them that a stay this long would show",
                    () -> s.maxUnseen, v -> s.maxUnseen = v, 1, 60, 1, " min");
            c.slider("ignore", "Ignore visits under", "Growth from shorter stays, such as someone flying past, is no sign",
                    () -> s.ignoreVisitsUnder, v -> s.ignoreVisitsUnder = v, 0, 30, 1, " min");
            c.slider("caves", "Max caves", "How much of a spot's underground may be open cave, water or lava; lower is safer to dig",
                    () -> s.maxCaves, v -> s.maxCaves = v, 0, 0.3, .01, "%");
            c.slider("rtp", "RTP distance", "How far a spot must be from every place /rtp landed you; 0 turns it off",
                    () -> s.rtpDistance, v -> s.rtpDistance = v, 0, 10000, 100, " blocks");
            c.toggle("signs", "Show signs", "Mark the chunks spots keep away from, labelled with why",
                    () -> s.showSigns, () -> s.showSigns = !s.showSigns);
            c.toggle("notify", "Announce spots", "A chat line with the coordinates when a new area has a good chunk",
                    () -> s.notify, () -> s.notify = !s.notify);
            chunkScan(c, s);
        });
    }

    /** What ChunkFinder's last pass saw, so an empty result can be explained. */
    private void chunkScan(Column c, FinderSettings s) {
        c.section("Live scan");
        FinderScanner scanner = FinderScanner.instance();
        if (!s.enabled || scanner == null) { c.note("Enable ChunkFinder to read loaded chunks.", MUTED); return; }
        ChunkStats stats = scanner.chunkStats();
        c.info("Chunks", Integer.toString(stats.scanned()), TEXT);
        c.info("With plants", Integer.toString(stats.withClocks()), TEXT);
        c.info("With signs", Integer.toString(stats.signs()), TEXT);
        c.info("Structures", Integer.toString(stats.structures()), TEXT);
        GoodChunk best = scanner.bestGoodChunk();
        c.info("Best", best == null ? "None" : "x " + best.surfaceCenter().getX() + ", z " + best.surfaceCenter().getZ(), TEXT);
        var zones = scanner.rtpZones(minecraft);
        c.info("RTP landings", Integer.toString(zones.size()), TEXT);
        var travel = scanner.travelTarget();
        if (travel != null) c.info("Travel to", "x " + Math.round(travel.x) + ", z " + Math.round(travel.z), TEXT);
        if (!zones.isEmpty()) {
            c.button("forgetRtp", "Forget RTP landings", "Every landing ChunkFinder noted on this server; spots near them are allowed again",
                    "Forget", () -> scanner.forgetRtpZones(minecraft));
        }
        if (!stats.overworld()) {
            c.note("Cane, cactus, kelp and vines only grow in the Overworld.", WARN);
        } else if (best == null && stats.scanned() >= 9) {
            c.note(stats.roomy() == 0 && stats.signs() == 0 ? "No chunk has a whole clearance loaded around it; lower Clearance."
                    : stats.roomy() == 0 ? "Every loaded area is near signs of players or in structures; move on or lower Clearance."
                    : stats.solid() == 0 ? "The clear chunks have too much cave under them; move on or raise Max caves."
                    : "Clear areas have too few plants to tell whether anyone was there; raise Max unseen time.", MUTED);
        }
        c.note("Each place /rtp lands you is noted; spots keep the RTP distance from all of them, and until you are that far out "
                + "the info panel points the way.", DIM);
        c.note("Any built block open to air, a placed entity or a dug passage keeps spots a clearance away; buried blocks don't "
                + "count, since anti-xray fakes them. Structures keep spots out of them and the chunks touching them. Plants are "
                + "judged as the server first sent them, so your own time here doesn't count.", DIM);
    }

    /** Live counts, including chunks below the threshold, so an empty world view can be explained. */
    private void activityScan(Column c, FinderSettings s) {
        c.section("Live scan");
        if (!s.enabled) { c.note("Enable ActivityFinder to scan loaded chunks.", MUTED); return; }
        ActivityStats stats = activityStats.get();
        c.info("Chunks", Integer.toString(stats.scannedChunks()), TEXT);
        c.info("With amethyst", Integer.toString(stats.amethystChunks()), TEXT);
        c.info("Budding blocks", Integer.toString(stats.budding()), TEXT);
        ActivityArea best = stats.best();
        c.info("Highest", best == null ? "None" : Math.round(best.score() * 100) + "%", TEXT);
        if (best != null) c.info("At", "x " + (best.chunk().getMinBlockX() + 8) + ", z " + (best.chunk().getMinBlockZ() + 8), MUTED);
        if (!stats.overworld()) {
            c.note("Amethyst geodes only generate in the Overworld.", WARN);
        } else if (stats.scannedChunks() >= 100 && stats.amethystChunks() == 0) {
            c.note("No budding amethyst in any loaded chunk. Geodes are common, so this server may hide amethyst (anti-xray).", WARN);
        } else if (best != null && best.score() < s.minActivity) {
            c.note(best.score() <= 0 ? "No chunk has grown beyond worldgen yet."
                    : "Nothing reaches Min growth; lower it to see weaker chunks.", MUTED);
        }
    }

    /**
     * A finder's settings: the controls people use up front, then its own detection settings, with the look of the
     * highlights behind "More options".
     */
    private Module finder(int index, String name, String description, String keywords, FinderSettings s,
                          Consumer<Column> colors, Consumer<Column> detection) {
        return module(name, description, keywords, () -> s.enabled, () -> change(() -> s.enabled = !s.enabled), c -> {
            c.bind(index);
            // ActivityFinder and BaseFinder can cover every loaded chunk, which makes range and distance fade irrelevant.
            boolean everyChunk = (s == config.activity || s == config.bases || s == config.chunks) && s.allLoadedChunks;
            c.section("Render");
            c.choice("mode", "Mode", RenderMode.values().length, () -> s.mode.ordinal(),
                    v -> s.mode = RenderMode.values()[v], v -> modeName(RenderMode.values()[v]));
            c.toggle("walls", "Through walls", "Keep highlights visible behind blocks, drawn faintly where something hides them", () -> s.throughWalls, () -> s.throughWalls = !s.throughWalls);
            c.toggle("tracers", "Tracers", "Lines from your crosshair to every highlight", () -> s.tracers, () -> s.tracers = !s.tracers);
            if (!everyChunk) c.slider("range", "Range", "Only loaded chunks can be highlighted",
                    () -> s.range, v -> s.range = v, 16, 256, 1, " blocks");
            detection.accept(c);
            c.more(m -> {
                m.section("Appearance");
                m.slider("outlineOpacity", "Outline opacity", "How opaque the outlines are",
                        () -> s.outlineOpacity, v -> s.outlineOpacity = v, 0, 1, .01, "%");
                m.slider("fillOpacity", "Fill opacity", "How opaque the filled faces are", () -> s.fillOpacity, v -> s.fillOpacity = v, 0, 1, .01, "%");
                m.slider("lineWidth", "Line width", "Outline width; thicker on screens above 1080p, like Minecraft's own lines", () -> s.lineWidth, v -> s.lineWidth = v, .5, 5, .1, " px");
                if (!everyChunk) m.toggle("fade", "Distance fade", "Soften highlights near the range limit",
                        () -> s.distanceFade, () -> s.distanceFade = !s.distanceFade);
                m.section("Colors");
                colors.accept(m);
            });
        }).key(index).values(s);
    }

    private Module xray() {
        var s = config.xray;
        return module("Xray", "See through terrain to the ores, caves and builds it hides",
                "xray x-ray see through stone ores caves underground hidden base", () -> s.enabled, () -> change(() -> s.enabled = !s.enabled), c -> {
            c.bind(6);
            c.section("Hide");
            c.chip("stone", "Stone", "Stone, deepslate, tuff, granite, diorite, andesite and bedrock; sideways deepslate stays, since only players place it",
                    () -> s.hideStone, () -> s.hideStone = !s.hideStone);
            c.chip("soil", "Dirt & sand", "Dirt, sand and gravel; also grass, clay and sandstone", () -> s.hideSoil, () -> s.hideSoil = !s.hideSoil);
            c.chip("nether", "Nether", "Netherrack, basalt, blackstone, soul sand and magma", () -> s.hideNether, () -> s.hideNether = !s.hideNether);
            c.chip("end", "End stone", "The End's islands", () -> s.hideEndStone, () -> s.hideEndStone = !s.hideEndStone);
            c.chip("water", "Water", "Also seagrass and kelp", () -> s.hideWater, () -> s.hideWater = !s.hideWater);
            c.chip("lava", "Lava", "See debris and bases behind lava lakes", () -> s.hideLava, () -> s.hideLava = !s.hideLava);
            c.section("Anti-xray");
            c.toggle("exposed", "Exposed ores only", "Hide ores touching no air; anti-xray fakes never do",
                    () -> s.exposedOresOnly, () -> s.exposedOresOnly = !s.exposedOresOnly);
            c.more(m -> m.field("extra", "Also hide", "Block ids, separated by commas", () -> s.extraHidden, v -> s.extraHidden = v, false, 512));
            if (FabricLoader.getInstance().isModLoaded("sodium")) {
                c.note("X-ray works with Minecraft's own renderer, which Sodium replaces: with Sodium it does nothing.", WARN);
            }
        }).key(6).values(s);
    }

    private Module fullbright() {
        var s = config.fullbright;
        return module("Fullbright", "Full brightness in caves and at night; your brightness setting is left alone",
                "fullbright brightness gamma night vision fog dark caves", () -> s.enabled, () -> change(() -> s.enabled = !s.enabled), c -> {
            c.bind(7);
            c.toggle("noFog", "Remove fog", "Distance, water, lava, blindness and darkness fog", () -> s.noFog, () -> s.noFog = !s.noFog);
        }).key(7).values(s);
    }

    private Module freecam() {
        var s = config.freecam;
        return worldModule("Freecam", "Explore with a detached camera; close this menu to fly", "freecam free camera fly perspective spectator",
                () -> s.enabled, () -> s.enabled = !s.enabled, c -> {
            c.bind(2);
            c.section("Movement");
            c.slider("speed", "Speed", "Camera speed", () -> s.speed, v -> s.speed = v, .1, 5, .1, " blocks/tick");
            c.slider("boost", "Sprint boost", "Speed multiplier while sprinting", () -> s.boostMultiplier, v -> s.boostMultiplier = v, 1, 10, .1, "x");
            c.section("Safety");
            c.toggle("exitWhenHurt", "Exit when hurt", "Freecam turns off when your body takes damage",
                    () -> s.exitWhenHurt, () -> s.exitWhenHurt = !s.exitWhenHurt);
            c.section("Controls");
            c.info("Move", String.join(" / ", keyName(minecraft.options.keyUp), keyName(minecraft.options.keyLeft),
                    keyName(minecraft.options.keyDown), keyName(minecraft.options.keyRight)), LABEL);
            c.info("Up / down", keyName(minecraft.options.keyJump) + " / " + keyName(minecraft.options.keyShift), LABEL);
            c.info("Boost", keyName(minecraft.options.keySprint), LABEL);
        }).key(2).values(s);
    }

    private Module autoRtp() {
        var s = config.rtp;
        return worldModule("AutoRTP", "/rtp until ActivityFinder sees activity", "autortp auto rtp random teleport wild activity base",
                () -> s.enabled, () -> s.enabled = !s.enabled, c -> {
            c.bind(5);
            c.note(rtpStatus.get(), s.enabled ? TEXT : MUTED);
            c.section("Command");
            c.field("command", "Command", "Sent without the slash", () -> s.command, v -> s.command = v, false);
            c.section("Stop");
            c.slider("stopAt", "Stop at growth", "Stops once a chunk reaches this growth",
                    () -> s.stopAtGrowth, v -> s.stopAtGrowth = v, 0, 1, .01, "%");
            c.toggle("hurt", "Stop when hurt", "Stop as soon as you lose health", () -> s.stopWhenHurt, () -> s.stopWhenHurt = !s.stopWhenHurt);
            c.more(m -> {
                m.section("Menu");
                m.field("menu", "Menu item", "Clicked when the command opens a menu", () -> s.menuItem, v -> s.menuItem = v, false);
                m.section("Timing");
                m.slider("interval", "Interval", "Waits longer when the server reports a cooldown",
                        () -> s.intervalSeconds, v -> s.intervalSeconds = v, 5, 600, 5, " s");
                m.slider("scan", "Scan time", "Time ActivityFinder scans after each teleport", () -> s.scanSeconds, v -> s.scanSeconds = v, 2, 30, 1, " s");
                m.slider("attempts", "Max attempts", "Stops after this many teleports", () -> s.maxAttempts,
                        v -> s.maxAttempts = (int) Math.round(v), 1, 200, 1, "");
            });
            c.note("Check the server's rules before automating commands.", DIM);
        }).key(5).values(s);
    }

    private Module schematicBuilder() {
        var s = config.schematic;
        return worldModule("SchematicBuilder", "Builds a .litematic layer by layer from your inventory and its shulker boxes",
                "schematic builder litematica litematic printer build place blocks layers redstone shulker",
                () -> s.enabled, () -> s.enabled = !s.enabled, c -> {
            SchematicBuilder builder = SchematicBuilder.getInstance();
            c.bind(11);
            if (builder != null) c.note(builder.status(), builder.statusWarn() ? WARN : s.enabled ? TEXT : MUTED);
            replyNote(c);
            if (builder != null && builder.isActive()) buildProgress(c, builder.progress());
            c.section("Schematic");
            c.field("file", "File", "A .litematic in the schematics folder; drop one on this menu to add it",
                    () -> s.file, v -> s.file = v, false, 128);
            for (String file : schematicFiles()) {
                String shown = file.substring(0, file.length() - ".litematic".length());
                c.chip("pick." + file, shown.length() > 28 ? shown.substring(0, 27) + "..." : shown, file,
                        () -> file.equals(s.file), () -> s.file = file);
            }
            c.button("folder", "Schematics folder", "Where Litematica saves them; open it to add or remove files", "Open",
                    this::openSchematicsFolder);
            Schematic schematic = builder == null ? null : builder.schematic();
            if (schematic == null) {
                if (builder != null) c.note(builder.loadProblem() + ".", s.file.isEmpty() ? MUTED : WARN);
            } else {
                c.info("Name", schematic.name, TEXT);
                if (!schematic.author.isEmpty()) c.info("Author", schematic.author, LABEL);
                BlockPos size = schematic.max.subtract(schematic.min).offset(1, 1, 1);
                c.info("Size", size.getX() + " x " + size.getY() + " x " + size.getZ() + (schematic.regions > 1
                        ? ", " + schematic.regions + " regions" : ""), LABEL);
                c.info("Blocks", Integer.toString(schematic.size()), LABEL);
                if (!schematic.unknown.isEmpty()) c.note("Left out, unknown to this game: " + String.join(", ", schematic.unknown.keySet()), WARN);
            }
            c.section("Placement");
            c.info("Origin", s.originSet ? s.originX + " " + s.originY + " " + s.originZ : "Where you stand when it starts", s.originSet ? TEXT : MUTED);
            c.button("here", "Set origin here", "The block at your feet becomes the schematic's origin", "Here", this::originHere);
            c.field("origin", "Origin", "Three whole numbers: x y z", () -> s.originSet ? s.originX + " " + s.originY + " " + s.originZ : "",
                    this::setOrigin, false, 48);
            c.choice("rotation", "Rotation", "Turned clockwise about the origin", 4, () -> s.rotation, v -> s.rotation = v,
                    v -> v * 90 + "°");
            c.choice("mirror", "Mirror", "Flipped before it is turned", 3, () -> s.mirror, v -> s.mirror = v,
                    v -> new String[] {"None", "Left-right", "Front-back"}[v]);
            c.section("Timing");
            c.slider("minDelay", "Shortest delay", "Each block waits a random time between the shortest and longest delay",
                    () -> s.minDelay, s::setMinDelay, 0.05, 10, 0.05, v -> String.format(Locale.ROOT, "%.2f s", v));
            c.slider("maxDelay", "Longest delay", "Set both the same for a fixed delay", () -> s.maxDelay, s::setMaxDelay,
                    0.05, 10, 0.05, v -> String.format(Locale.ROOT, "%.2f s", v));
            c.section("Moving");
            c.toggle("walk", "Walk", "Walks, jumps and climbs to each block by itself, as a player on foot; off, you walk and it places what comes in reach",
                    () -> s.walk, () -> s.walk = !s.walk);
            c.field("helpers", "Helper blocks", "Put down to climb on or place a block against, then taken away; the build's own blocks are never used",
                    () -> s.helperBlocks, v -> s.helperBlocks = v, false, 512);
            c.section("Materials");
            c.toggle("shulkers", "Use shulker boxes", "Take blocks from shulker boxes in your inventory: placed beside you, emptied, broken and picked up",
                    () -> s.useShulkers, () -> s.useShulkers = !s.useShulkers);
            buildMaterials(c, builder);
            c.more(m -> {
                m.section("Placing");
                m.toggle("redstoneLast", "Redstone last", "In each layer: plain blocks, then redstone, then pistons, observers and dispensers",
                        () -> s.redstoneLast, () -> s.redstoneLast = !s.redstoneLast);
                m.toggle("preview", "Preview", "Outline the schematic and the blocks left in the layer being built",
                        () -> s.preview, () -> s.preview = !s.preview);
            });
            c.note(s.walk ? "It walks the build layer by layer within normal reach, looking at each spot it clicks, and never flies. Check the server's rules before automating building."
                    : "Walk along each layer: blocks go in as they come in reach and view. Check the server's rules before automating building.", DIM);
        }).key(11).values(s);
    }

    private Module chunkMiner() {
        var s = config.miner;
        return worldModule("ChunkMiner", "Digs the room a schematic needs under a good chunk, with space around it",
                "chunk miner dig room base mine excavate tunnel staircase amethyst pickaxe 3x3 good chunk storage",
                () -> s.enabled, () -> s.enabled = !s.enabled, c -> {
            ChunkMiner miner = ChunkMiner.getInstance();
            c.bind(13);
            if (miner != null) c.note(miner.status(), miner.statusWarn() ? WARN : s.enabled ? TEXT : MUTED);
            replyNote(c);
            if (miner != null && miner.isActive() && miner.excavator() != null) digProgress(c, miner);
            c.section("Where");
            SchematicBuilder builder = SchematicBuilder.getInstance();
            Schematic schematic = builder == null ? null : builder.schematic();
            if (schematic == null) {
                c.note("Pick a schematic in SchematicBuilder first: the room is dug for it.", WARN);
            } else {
                BlockPos size = schematic.max.subtract(schematic.min).offset(1, 1, 1);
                c.info("Schematic", schematic.name + ", " + size.getX() + " x " + size.getY() + " x " + size.getZ(), TEXT);
            }
            c.toggle("goodChunks", "Dig good chunks", "Wait for ChunkFinder's best good chunk and dig under its middle; off digs at the schematic's origin",
                    () -> s.goodChunks, () -> s.goodChunks = !s.goodChunks);
            if (s.goodChunks) {
                c.slider("floorY", "Floor height", "The schematic's lowest layer goes at this height; the block under it stays as the floor",
                        () -> s.floorY, v -> s.floorY = (int) Math.round(v), -60, 120, 1, v -> "y " + Math.round(v));
            } else {
                var place = config.schematic;
                c.info("Origin", place.originSet ? place.originX + " " + place.originY + " " + place.originZ : "Where you stand when it starts",
                        place.originSet ? TEXT : MUTED);
                c.button("here", "Dig here", "The block at your feet becomes the schematic's origin, as SchematicBuilder's Set origin here does",
                        "Here", this::originHere);
            }
            c.section("Room");
            c.slider("sideRoom", "Room around it", "Blocks dug on every side of the schematic, to walk around it",
                    () -> s.sideRoom, v -> s.sideRoom = (int) Math.round(v), 0, 12, 1, " blocks");
            c.slider("headroom", "Headroom", "Blocks dug above it", () -> s.headroom, v -> s.headroom = (int) Math.round(v), 0, 8, 1, " blocks");
            c.choice("storage", "Storage room", "A lower room beside it for chests; Entrance puts it where the staircase comes in", 6,
                    () -> s.storageSide, v -> s.storageSide = v, v -> new String[] {"None", "North", "East", "South", "West", "Entrance"}[v]);
            if (s.storageSide != 0) {
                c.slider("storageDepth", "Storage depth", "How far the storage room reaches out from the room",
                        () -> s.storageDepth, v -> s.storageDepth = (int) Math.round(v), 2, 24, 1, " blocks");
                c.slider("storageHeight", "Storage height", "Its height above the floor", () -> s.storageHeight,
                        v -> s.storageHeight = (int) Math.round(v), 2, 8, 1, " blocks");
            }
            c.section("Pickaxe");
            c.choice("pickaxe", "Use", "An Amethyst Pickaxe breaks 3x3 a swing; the miner checks on its first swings what yours breaks", 3,
                    () -> s.pickaxe, v -> s.pickaxe = v, v -> new String[] {"Amethyst first", "Amethyst only", "Normal only"}[v]);
            minerTools(c, miner);
            c.slider("keep", "Keep durability", "A tool with this many uses left is put away, so it never breaks",
                    () -> s.keepDurability, v -> s.keepDurability = (int) Math.round(v), 1, 200, 1, " uses");
            c.section("Looking human");
            c.slider("minDelay", "Shortest delay", "Each block waits a random time between the shortest and longest delay",
                    () -> s.minDelay, s::setMinDelay, 0, 5, 0.05, v -> String.format(Locale.ROOT, "%.2f s", v));
            c.slider("maxDelay", "Longest delay", "Set both the same for a fixed delay", () -> s.maxDelay, s::setMaxDelay,
                    0, 5, 0.05, v -> String.format(Locale.ROOT, "%.2f s", v));
            c.slider("turnSpeed", "Turn speed", "The fastest the camera turns; it eases in and out, by whole mouse steps, and never snaps",
                    () -> s.turnSpeed, v -> s.turnSpeed = v, 4, 90, 1, v -> Math.round(v) + "°/tick");
            c.toggle("vary", "Vary order", "Not always the very best next block, so no two rooms are dug the same way",
                    () -> s.varyOrder, () -> s.varyOrder = !s.varyOrder);
            c.more(m -> {
                m.section("Looking human");
                m.slider("aimSpread", "Aim spread", "How far from a face's middle the aim may land", () -> s.aimSpread, v -> s.aimSpread = v,
                        0, 0.9, 0.05, v -> Math.round(v * 100) + "% of a face");
                m.slider("handShake", "Hand shake", "The camera's small drift while a block breaks, at most", () -> s.handShake,
                        v -> s.handShake = v, 0, 2, 0.05, v -> String.format(Locale.ROOT, "%.2f°", v));
                m.slider("pauseChance", "Pause chance", "After each block, the chance of a longer pause, glancing around",
                        () -> s.pauseChance, v -> s.pauseChance = v, 0, 0.5, 0.01, "%");
                m.slider("pauseMin", "Shortest pause", "How long a longer pause lasts, at least", () -> s.pauseMin, s::setPauseMin,
                        0.2, 30, 0.1, v -> String.format(Locale.ROOT, "%.1f s", v));
                m.slider("pauseMax", "Longest pause", "And at most", () -> s.pauseMax, s::setPauseMax,
                        0.2, 30, 0.1, v -> String.format(Locale.ROOT, "%.1f s", v));
                m.section("Safety");
                m.toggle("hurt", "Stop when hurt", "Stop as soon as you lose health", () -> s.stopWhenHurt, () -> s.stopWhenHurt = !s.stopWhenHurt);
                m.slider("players", "Pause for players", "Waits while another player is this close; 0 never waits",
                        () -> s.pauseForPlayers, v -> s.pauseForPlayers = (int) Math.round(v), 0, 128, 1,
                        v -> v < 0.5 ? "Off" : Math.round(v) + " blocks");
                m.toggle("fillGaps", "Fill gaps", "Put cobblestone, dirt or the like under a step that has nothing under it",
                        () -> s.fillGaps, () -> s.fillGaps = !s.fillGaps);
                m.section("After");
                m.toggle("buildAfter", "Build when done", "Start SchematicBuilder on the room once it is dug",
                        () -> s.buildAfter, () -> s.buildAfter = !s.buildAfter);
                m.toggle("preview", "Preview", "Outline the room, where the schematic goes in it, and the block being dug",
                        () -> s.preview, () -> s.preview = !s.preview);
            });
            c.note("Carry a pickaxe, food, and some cobblestone or dirt. It pauses when you are hungry. Check the server's rules before "
                    + "automating mining.", DIM);
        }).key(13).values(s);
    }

    private void digProgress(Column c, ChunkMiner miner) {
        c.section("Progress");
        Excavator dig = miner.excavator();
        if (miner.left() >= 0) c.info("Blocks left", Integer.toString(miner.left()), TEXT);
        if (dig.phase() == Excavator.Phase.APPROACH || dig.phase() == Excavator.Phase.CLIMB) {
            c.info("Staircase", dig.stepsLeft() + " steps to go", TEXT);
        } else if (dig.phase() == Excavator.Phase.TOWER) {
            c.info("Recovering", "climbing back up to the band it fell from", WARN);
        } else {
            c.info("Band", dig.tierNumber() + " of " + dig.tiers().size() + ", from the top", TEXT);
        }
        if (miner.room() != null) c.info("Room", miner.room().main.toString(), LABEL);
    }

    /** The pickaxes carried: which the miner would use, what each breaks, and how worn it is. */
    private void minerTools(Column c, ChunkMiner miner) {
        if (minecraft.player == null || miner == null) return;
        var inventory = minecraft.player.getInventory();
        int shown = 0;
        for (int slot = 0; slot < Kit.SLOTS && shown < 4; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!Kit.isPickaxe(stack)) continue;
            int radius = miner.radius(stack), left = Kit.usesLeft(stack);
            boolean worn = left <= config.miner.keepDurability;
            c.info(Kit.name(stack), (radius > 0 ? (2 * radius + 1) + "x" + (2 * radius + 1) + ", " : "")
                    + (left == Integer.MAX_VALUE ? "unbreakable" : left + " uses left") + (worn ? ", put away" : ""),
                    worn ? WARN : radius > 0 ? GOOD : LABEL);
            shown++;
        }
        if (shown == 0) c.note("You carry no pickaxe.", WARN);
    }

    private void buildProgress(Column c, SchematicBuilder.Progress p) {
        c.section("Progress");
        int percent = p.placeable() == 0 ? 100 : (int) Math.floor(100.0 * p.done() / p.placeable());
        c.info("Placed", p.done() + " of " + p.placeable() + " (" + percent + "%)", TEXT);
        if (p.layer() > 0) c.info("Layer", p.layer() + " of " + p.layers() + ", y " + p.layerY(), TEXT);
        if (p.wrong() > 0) c.info("Other blocks in the way", Integer.toString(p.wrong()), WARN);
        if (p.failed() > 0) c.info("Not kept by the server", Integer.toString(p.failed()), WARN);
        if (p.misplaced() > 0) c.info("Facing another way", Integer.toString(p.misplaced()), WARN);
    }

    /** What the build takes against what the inventory and its shulker boxes hold. */
    private void buildMaterials(Column c, SchematicBuilder builder) {
        if (builder == null || minecraft.player == null) return;
        Map<Item, Integer> needs = builder.isActive() ? builder.needs() : builder.bill();
        if (needs.isEmpty()) return;
        if (minecraft.player.hasInfiniteMaterials()) {
            c.note("Creative: every block is made as it is placed.", MUTED);
            return;
        }
        Materials carried = Materials.of(minecraft.player.getInventory());
        int shown = 0, short_ = 0;
        for (Map.Entry<Item, Integer> need : needs.entrySet()) {
            int have = carried.total(need.getKey());
            if (have < need.getValue()) short_++;
            if (shown++ < 8) {
                c.info(SchematicBuilder.name(need.getKey()), Math.min(have, need.getValue()) + " of " + need.getValue(),
                        have >= need.getValue() ? GOOD : WARN);
            }
        }
        if (needs.size() > 8) c.note("And " + (needs.size() - 8) + " more kinds.", MUTED);
        c.note(short_ == 0 ? "You carry everything " + (builder.isActive() ? "the rest of the build" : "it") + " needs."
                : short_ + (short_ == 1 ? " kind is" : " kinds are") + " short; it builds what it can and waits for the rest.",
                short_ == 0 ? GOOD : WARN);
    }

    private List<String> schematicFiles;
    private long filesListedAt;

    /** The schematics folder's files, listed again at most once a second. */
    private List<String> schematicFiles() {
        long now = System.currentTimeMillis();
        if (schematicFiles == null || now - filesListedAt > 1000) {
            schematicFiles = SchematicBuilder.files();
            filesListedAt = now;
        }
        return schematicFiles.subList(0, Math.min(12, schematicFiles.size()));
    }

    private void openSchematicsFolder() {
        Path folder = SchematicBuilder.folder();
        try {
            Files.createDirectories(folder);
        } catch (IOException e) {
            reply("Couldn't make the schematics folder: " + e.getMessage(), true);
            return;
        }
        Util.getPlatform().openPath(folder);
    }

    private void originHere() {
        if (minecraft.player == null) {
            reply("Join a world to set the origin.", true);
            return;
        }
        BlockPos at = minecraft.player.blockPosition();
        var s = config.schematic;
        s.originSet = true;
        s.originX = at.getX();
        s.originY = at.getY();
        s.originZ = at.getZ();
        reply("Origin set to " + at.getX() + " " + at.getY() + " " + at.getZ() + ".", false);
    }

    /** Takes "x y z", also with commas; an empty field unsets the origin, so the next start uses where the player stands. */
    private void setOrigin(String text) {
        if (text.isBlank()) {
            config.schematic.originSet = false;
            return;
        }
        String[] parts = text.trim().split("[\\s,]+");
        try {
            if (parts.length != 3) throw new NumberFormatException();
            int x = Integer.parseInt(parts[0]), y = Integer.parseInt(parts[1]), z = Integer.parseInt(parts[2]);
            var s = config.schematic;
            s.originSet = true;
            s.originX = x;
            s.originY = y;
            s.originZ = z;
        } catch (NumberFormatException e) {
            reply("The origin takes three whole numbers, such as 120 64 -35.", true);
        }
    }

    /** A .litematic dropped on the menu goes into the schematics folder and becomes the builder's schematic. */
    @Override
    public void onFilesDrop(List<Path> files) {
        try {
            String name = SchematicBuilder.importFiles(files);
            if (name == null) {
                reply("Only .litematic files can be added.", true);
                return;
            }
            config.schematic.file = name;
            dirty = true;
            flush();
            schematicFiles = null;
            for (Page p : pages.values()) {
                if (p.modules == null) continue;
                for (Module m : p.modules) if (m.name.equals("SchematicBuilder")) openDetail(m);
            }
            reply("Added " + name + " to the schematics folder.", false);
        } catch (IOException e) {
            reply("Couldn't add it: " + e.getMessage(), true);
        }
    }

    private Module module(String name, String description, String keywords, BooleanSupplier enabled, Runnable toggle,
                          Consumer<Column> settings) {
        return new Module(name, description, keywords, enabled, toggle, () -> true, "", settings, -1, null);
    }

    /** Freecam and Auto RTP act in a world, so they can only start inside one. */
    private Module worldModule(String name, String description, String keywords, BooleanSupplier enabled, Runnable flip,
                               Consumer<Column> settings) {
        return new Module(name, description, keywords, enabled, () -> {
            if (enabled.getAsBoolean() || canUseWorld()) flip.run();
        }, () -> enabled.getAsBoolean() || canUseWorld(), "Join a world to use " + name, settings, -1, null);
    }

    private boolean canUseWorld() { return minecraft.level != null && minecraft.player != null; }

    private String modeName(RenderMode mode) {
        return switch (mode) { case OUTLINE -> "Outline"; case FILL -> "Filled"; case BOTH -> "Both"; };
    }

    // Pages -------------------------------------------------------------------------------------

    private void drawKeybinds(Column c) {
        c.bindRow("keys.open", OPEN_KEY, "Open this menu", "Also closes it; Right Shift by default");
        for (Page p : pages.values()) {
            if (p.modules == null) continue;
            for (Module m : p.modules) if (m.key >= 0) c.bindRow("keys." + m.name, m.key, m.name, m.description);
        }
        c.note("Click a key, then press the new one. Delete or Backspace clears it, Esc cancels, and a right click puts back its "
                + "default.", MUTED);
    }

    private void drawTheme(Column c) {
        c.section("Colors");
        c.color("accent", "Accent color", "Switches, sliders and the selected page", () -> config.accentColor, v -> config.accentColor = v);
        c.section("Effects");
        c.toggle("snow", "Snowfall", "Faint snow behind the menu", () -> config.gui.snow, () -> config.gui.snow = !config.gui.snow);
    }

    // Messages --------------------------------------------------------------------------------

    private static final long ARM_MILLIS = 8_000, REPLY_MILLIS = 15_000;
    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();
    /** What the last click answered, and until when it shows. */
    private String reply = "";
    private long replyUntil;
    private boolean replyBad;
    /** Resets armed by a first click, until when; a second click before then goes ahead. */
    private final Map<String, Long> armed = new HashMap<>();

    private void reply(String text, boolean bad) {
        reply = text == null ? "" : text;
        replyBad = bad;
        replyUntil = System.currentTimeMillis() + REPLY_MILLIS;
    }

    private void replyNote(Column c) {
        if (!reply.isBlank() && System.currentTimeMillis() < replyUntil) c.note(reply, replyBad ? WARN : LABEL);
    }

    private boolean isArmed(String id) {
        Long until = armed.get(id);
        return until != null && System.currentTimeMillis() < until;
    }

    // Navigation --------------------------------------------------------------------------------

    private Page currentPage() { return pages.getOrDefault(pageId, pages.values().iterator().next()); }

    /** The module whose settings are open, if any. */
    private Module detail() {
        if (detailName == null || !query.isEmpty()) return null;
        for (Page p : pages.values()) {
            if (p.modules == null) continue;
            for (Module m : p.modules) if (m.name.equals(detailName)) return m;
        }
        return null;
    }

    private Page pageOf(Module module) {
        for (Page p : pages.values()) if (p.modules != null && p.modules.contains(module)) return p;
        return currentPage();
    }

    private void openPage(String id) {
        remember();
        reply = "";
        pageId = id;
        detailName = null;
        query = "";
        searchFocused = false;
        restart();
    }

    private void openDetail(Module m) {
        remember();
        reply = "";
        pageId = pageOf(m).id;
        detailName = m.name;
        query = "";
        searchFocused = false;
        restart();
    }

    private void closeDetail() {
        remember();
        reply = "";
        detailName = null;
        restart();
    }

    /** The page, or module settings, on screen. */
    private static String view() { return pageId + "/" + (detailName == null ? "" : detailName); }

    /** Keeps where the page on screen is scrolled to, for when it is shown again. */
    private void remember() { if (query.isEmpty()) SCROLLS.put(view(), content.scrollTarget); }

    /** Slides a new page in, scrolled to where it was last left, or to its top. */
    private void restart() {
        content.scroll = content.scrollTarget = query.isEmpty() ? SCROLLS.getOrDefault(view(), 0.0) : 0;
        animations.put("page", 0.0);
        keyboardFocus = null;
    }

    private List<Module> searchResults() {
        String needle = query.toLowerCase(Locale.ROOT).trim();
        List<Module> found = new ArrayList<>();
        for (Page p : pages.values()) {
            if (p.modules == null) continue;
            for (Module m : p.modules) {
                if ((m.name + " " + m.keywords + " " + m.description).toLowerCase(Locale.ROOT).contains(needle)) found.add(m);
            }
        }
        return found;
    }

    /** Shows the page that holds a control, for game tests that click controls by id. */
    private void navigateTo(String id) {
        boolean row = id.startsWith("module.") || id.startsWith("expand.");
        String name = row ? id.substring(7) : id.indexOf('.') > 0 && !id.startsWith("keys.") && !id.startsWith("nav.")
                ? id.substring(0, id.indexOf('.')) : null;
        if (name == null) return;
        for (Page p : pages.values()) {
            if (p.modules == null) continue;
            for (Module m : p.modules) {
                if (!m.name.equals(name)) continue;
                query = "";
                pageId = p.id;
                if (!row) detailName = name;
                else if (!name.equals(detailName)) detailName = null;
                restart();
                return;
            }
        }
    }

    // Layout ------------------------------------------------------------------------------------

    @Override
    protected void init() {
        int gui = minecraft.getWindow().getGuiScale();
        float fit = Math.clamp(Math.min(width / MIN_W, height / MIN_H), 0.1f, 1f);
        // A whole number of screen pixels per unit keeps text sharp. Large GUI scales are capped so the window stays
        // inside the screen with room around it instead of filling it.
        // The fonts come in up to six screen pixels per unit for small and title text, so the menu goes no larger.
        pixels = Math.min(Math.min((int) (gui * fit), Math.max(1, minecraft.getWindow().getHeight() / 384)), UiPaint.MAX_RATIO);
        if (pixels >= 1) {
            scale = (float) pixels / gui;
        } else {
            // A window too small for even one pixel per unit gets a fractional scale, and slightly soft text.
            scale = fit;
            pixels = 1;
        }
        layoutW = Math.round(width / scale);
        layoutH = Math.round(height / scale);
        ww = Math.min(layoutW - 32, MAX_W);
        wh = Math.min(layoutH - 24, MAX_H);
        wx = (layoutW - ww) / 2;
        wy = (layoutH - wh) / 2;
        // A resize in the middle of a drag sets what the slider shows, rather than leaving it to a later release.
        // The key that opened the menu is still down; its repeats must not close the menu again.
        if (activationKey == null && openKeyHeld()) activationKey = InputConstants.getKey(keyMapping(OPEN_KEY).saveString());
        commitDrafts();
        dragging = null;
        hits.clear();
        fitted.clear();
        sliderBoxes.clear();
    }

    private int mainX() { return wx + 8 + SIDEBAR_W + 18; }

    private int mainW() { return wx + ww - 18 - mainX(); }

    private Region regionAt(double x, double y) {
        if (colorEditor != null) return null;
        if (content.contains(x, y)) return content;
        if (nav.contains(x, y)) return nav;
        return x >= wx && x < wx + ww && y >= wy && y < wy + wh ? chrome : null;
    }

    // Drawing -----------------------------------------------------------------------------------

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        graphics = g;
        long now = System.nanoTime();
        frameSeconds = lastFrame == 0 ? 1.0 / 60 : Math.min(.1, (now - lastFrame) / 1_000_000_000.0);
        lastFrame = now;
        // The exact cursor position: the integer one moves in steps of several screen pixels on a large GUI scale.
        mouseX = minecraft.mouseHandler.getScaledXPos(minecraft.getWindow()) / scale;
        mouseY = minecraft.mouseHandler.getScaledYPos(minecraft.getWindow()) / scale;
        hits.clear();
        places.clear();
        clips.clear();
        moving = false;
        tooltip = null;
        hovered = regionAt(mouseX, mouseY);
        g.fill(0, 0, width, height, 0x9A030808);
        g.pose().pushMatrix();
        g.pose().scale(scale, scale);
        UiPaint.pixelScale(pixels);
        try {
            if (config.gui.snow) drawSnow();
            drawWindow();
            if (colorEditor != null) drawColorEditor();
            if (keyboardFocus != null) {
                hits.stream().filter(h -> h.id.equals(keyboardFocus)).findFirst().ifPresent(h ->
                        outline(g, h.x - 2, h.y - 2, h.w + 4, h.h + 4, 5, alpha(accent(), .85)));
            }
            if (tooltip != null) drawTooltip();
        } finally {
            // The HUD draws at the GUI scale again.
            UiPaint.pixelScale(0);
        }
        g.pose().popMatrix();
        settled = !moving;
    }

    /** Slow, faint snowfall behind the window, placed by formula so it needs no state. */
    private void drawSnow() {
        double time = System.nanoTime() / 1e9 % 100_000;
        int span = layoutH + 8;
        for (int i = 0; i < 70; i++) {
            double across = fraction(i * .618034), start = fraction(i * .754878 + .31), depth = fraction(i * .569840 + .67);
            double y = (start * span + time * (6 + 12 * depth)) % span - 4;
            double x = across * layoutW + Math.sin(time * (.3 + .5 * depth) + i * 1.7) * (3 + 5 * depth);
            float size = 1f + (float) depth * 1.6f;
            round(graphics, (float) x, (float) y, size, size, size / 2, alpha(0xFFFFFF, .18 + .32 * depth));
        }
    }

    private static double fraction(double value) { return value - Math.floor(value); }

    private void drawWindow() {
        drawing = chrome;
        chrome.set(wx, wy, ww, wh);
        shadow(graphics, wx, wy + 4, ww, wh, 12, 0xC8000000);
        round(graphics, wx, wy, ww, wh, 10, WINDOW);
        // A soft glow of the accent behind the page title.
        graphics.enableScissor(wx, wy, wx + ww, wy + wh);
        shadow(graphics, mainX() + mainW() / 5, wy - 46, mainW() * 3 / 5, 44, 40, alpha(config.accentColor, .13));
        graphics.disableScissor();
        outline(graphics, wx, wy, ww, wh, 10, EDGE);
        drawSidebar();
        drawing = chrome;
        drawHeader();
        drawPage();
        drawing = null;
    }

    private void drawSidebar() {
        int x = wx + 8, y = wy + 8, w = SIDEBAR_W, h = wh - 16;
        round(graphics, x, y, w, h, 8, SIDEBAR);
        outline(graphics, x, y, w, h, 8, EDGE);
        ven(graphics, x + 12, y + 12, accent());
        bold("Ven", x + 34, y + 11, TEXT);
        draw("Client", Size.BASE, x + 34 + width("Ven ", Size.BASE) + 1, y + 11, accent(), false, true);
        if (!version.isEmpty()) draw(fit(version, Size.SMALL, w - 44), Size.SMALL, x + 34, y + 24, MUTED);
        int top = y + 44;
        nav.set(x, top, w, y + h - 6 - top);
        drawing = nav;
        nav.scroll = ease(nav.scroll, nav.scrollTarget, 18);
        pushClip(nav.x, nav.y, nav.x + nav.w, nav.y + nav.h);
        int shift = snapped(nav.scroll);
        graphics.pose().pushMatrix();
        graphics.pose().translate(0, (float) (shift - onPixel(nav.scroll)));
        contentOrigin = top - shift;
        int cy = contentOrigin;
        for (int i = 0; i < groups.size(); i++) {
            NavGroup group = groups.get(i);
            if (i > 0) {
                round(graphics, x + 12, cy + 3, w - 24, 1, 0, EDGE);
                cy += 10;
            }
            draw(group.title.toUpperCase(Locale.ROOT), Size.SMALL, x + 13, cy + 2, DIM);
            cy += 16;
            for (Page p : group.pages) {
                navItem(p, x + 7, cy, w - 14);
                cy += NAV_H + 3;
            }
            cy += 5;
        }
        graphics.pose().popMatrix();
        popClip();
        nav.contentHeight = cy - contentOrigin;
        clampScroll(nav);
        drawing = chrome;
    }

    private void navItem(Page p, int x, int y, int w) {
        boolean selected = p.id.equals(pageId) && query.isEmpty(), over = inside(x, y, w, NAV_H);
        double on = animate("nav." + p.id, selected ? 1 : 0);
        if (on > .01) {
            round(graphics, x, y, w, NAV_H, 6, blend(SIDEBAR, accent(), .14 * on));
            outline(graphics, x, y, w, NAV_H, 6, alpha(config.accentColor, .55 * on));
        } else if (over) {
            round(graphics, x, y, w, NAV_H, 6, alpha(0xFFFFFF, .04));
        }
        icon(p.icon, x + 8, y + 6, selected ? accent() : over ? TEXT : LABEL);
        draw(p.title, Size.BASE, x + 26, y + 8, selected || over ? TEXT : LABEL);
        if (p.modules != null) {
            String count = Integer.toString(p.modules.size());
            draw(count, Size.SMALL, x + w - 9 - width(count, Size.SMALL), y + 8, selected ? MUTED : DIM);
        }
        hit("nav." + p.id, x, y, w, NAV_H, () -> openPage(p.id), null);
    }

    /** The page icons are drawn on a 17-unit grid and shown at ten units, with their lines as thick as the search icon's. */
    private void icon(String name, int x, int y, int color) {
        if (name.equals("search")) { search(graphics, x, y, color); return; }
        graphics.pose().pushMatrix();
        graphics.pose().translate(x, y);
        graphics.pose().scale(.6f, .6f);
        UiPaint.strokeScale(1 / .6f);
        try {
            switch (name) {
                case "eye" -> eye(graphics, 0, 0, color);
                case "rtp" -> rtp(graphics, 0, 0, color);
                case "chart" -> chart(graphics, 0, 0, color);
                case "monitor" -> monitor(graphics, 0, 0, color);
                case "grid" -> grid(graphics, 0, 0, color);
                case "list" -> list(graphics, 0, 0, color);
                case "keyboard" -> keyboard(graphics, 0, 0, color);
                case "palette" -> palette(graphics, 0, 0, color);
                case "donut" -> donut(graphics, 1, 1, color);
                case "cube" -> cube(graphics, 0, 0, color);
                default -> sliders(graphics, 0, 0, color);
            }
        } finally {
            UiPaint.strokeScale(1);
        }
        graphics.pose().popMatrix();
    }

    private void drawHeader() {
        int x = mainX(), w = mainW();
        Page page = currentPage();
        String title = query.isEmpty() ? page.header() : "Search";
        int searchW = Math.min(176, w / 3), titleW = w - searchW - 16;
        draw("Ven", Size.TITLE, x, wy + 21, accent(), false, true);
        int after = x + width("Ven ", Size.TITLE) + 2;
        draw(fit(title, Size.TITLE, x + titleW - after), Size.TITLE, after, wy + 21, TEXT, true, false);
        String subtitle;
        if (!query.isEmpty()) {
            int found = searchResults().size();
            subtitle = found + (found == 1 ? " module" : " modules");
        } else if (page.modules != null && page.subtitle == null) {
            long enabled = page.modules.stream().filter(m -> m.enabled.getAsBoolean()).count();
            subtitle = page.modules.size() + (page.modules.size() == 1 ? " module" : " modules") + "  ·  " + enabled + " enabled";
        } else {
            subtitle = page.subtitle;
        }
        draw(fit(subtitle, Size.SMALL, titleW), Size.SMALL, x, wy + 35, MUTED);
        drawSearch(x + w - searchW, wy + 14, searchW, 22);
    }

    private void drawSearch(int x, int y, int w, int h) {
        boolean over = inside(x, y, w, h);
        round(graphics, x, y, w, h, 7, alpha(FIELD, .9));
        outline(graphics, x, y, w, h, 7, searchFocused ? accent() : over ? EDGE_HOVER : EDGE);
        search(graphics, x + 9, y + 6, searchFocused || !query.isEmpty() ? LABEL : MUTED);
        String shown = query.isEmpty() && !searchFocused ? "Search modules"
                : query + (searchFocused && System.currentTimeMillis() % 1000 < 500 ? "|" : "");
        shown = tail(shown, Size.BASE, w - 34);
        draw(shown, Size.BASE, x + 25, y + h / 2 - 3, query.isEmpty() ? MUTED : TEXT);
        hit("search", x, y, w, h, () -> searchFocused = true, null);
    }

    private void drawPage() {
        int x = mainX(), w = mainW(), top = wy + HEADER_H + 6, bottom = wy + wh - 14;
        Page page = currentPage();
        Module open = detail();
        if (!query.isEmpty()) {
            drawModules(searchResults(), x, top, w, bottom);
        } else if (open != null) {
            drawDetail(open, x, top, w, bottom);
        } else if (page.modules != null) {
            drawModules(page.modules, x, top, w, bottom);
        } else {
            int frame = bottom - top;
            round(graphics, x, top, w, frame, 9, CARD);
            outline(graphics, x, top, w, frame, 9, EDGE);
            scrolled(x + 1, top + 1, w - 2, frame - 2, CARD, origin -> {
                Column c = new Column(x + 16, w - 32, origin + 10, "");
                page.content.accept(c);
                c.flushChips();
                return c.y + 10;
            });
        }
    }

    /** Draws content into the scrolling region, sliding it in after a page change. */
    private void scrolled(int x, int y, int w, int h, int background, java.util.function.IntUnaryOperator draw) {
        content.set(x, y, w, h);
        Region previous = drawing;
        drawing = content;
        content.scroll = ease(content.scroll, content.scrollTarget, 18);
        double slide = 1 - animate("page", 1);
        pushClip(x, y, x + w, y + h);
        int shift = snapped(content.scroll);
        graphics.pose().pushMatrix();
        graphics.pose().translate(0, (float) (shift - onPixel(content.scroll - slide * 8)));
        contentOrigin = y - shift;
        int end = draw.applyAsInt(contentOrigin);
        graphics.pose().popMatrix();
        popClip();
        content.contentHeight = end - contentOrigin;
        clampScroll(content);
        int max = Math.max(0, content.contentHeight - h);
        // Rows cut by the edges fade out into the background instead of ending in a hard line.
        // Inside a rounded card the fade stays clear of its corners.
        int clear = background & 0x00FFFFFF, inset = background == CARD ? 6 : 0;
        if (content.scroll > .5) graphics.fillGradient(x + inset, y, x + w - inset, y + 10, background, clear);
        if (content.scroll < max - .5) graphics.fillGradient(x + inset, y + h - 10, x + w - inset, y + h, clear, background);
        if (max > 0) {
            int thumb = Math.max(16, h * h / content.contentHeight);
            int thumbY = y + (int) (content.scroll / max * (h - thumb));
            round(graphics, x + w + 5, thumbY, 3, thumb, 1.5f, alpha(0xFFFFFF, .16));
        }
        drawing = previous;
    }

    /** Scroll offsets snap to whole screen pixels, so text does not shimmer while it moves. */
    private int snapped(double scroll) { return (int) Math.floor(Math.round(scroll * pixels) / (double) pixels); }

    /** A position on the nearest whole screen pixel, so content drawn at it stays sharp at rest. */
    private double onPixel(double value) { return Math.round(value * pixels) / (double) pixels; }

    private void clampScroll(Region r) {
        int max = Math.max(0, r.contentHeight - r.h);
        r.scroll = Math.clamp(r.scroll, 0, max);
        r.scrollTarget = Math.clamp(r.scrollTarget, 0, max);
    }

    private void drawModules(List<Module> modules, int x, int top, int w, int bottom) {
        scrolled(x, top, w, bottom - top, WINDOW, origin -> {
            int y = origin;
            if (modules.isEmpty()) {
                String message = fit("No modules match \"" + query.trim() + "\"", Size.BASE, w);
                draw(message, Size.BASE, x + (w - width(message, Size.BASE)) / 2, y + 30, MUTED);
                return y + 60;
            }
            for (Module m : modules) {
                moduleCard(m, x, y, w);
                y += CARD_H + CARD_GAP;
            }
            return y;
        });
    }

    private void moduleCard(Module m, int x, int y, int w) {
        boolean usable = m.available.getAsBoolean(), on = m.enabled.getAsBoolean(), expandable = m.settings != null;
        boolean over = inside(x, y, w, CARD_H);
        double hover = animate("card." + m.name, over ? 1 : 0);
        round(graphics, x, y, w, CARD_H, 8, blend(CARD, CARD_HOVER, hover));
        outline(graphics, x, y, w, CARD_H, 8, blend(EDGE, EDGE_HOVER, hover));
        if (hover > .01) round(graphics, x, y + 10, 2f, CARD_H - 20f, 1f, alpha(config.accentColor, hover));
        bold(fit(m.name, Size.BASE, w - 120), x + 15, y + 10, usable ? TEXT : DIM);
        String description = usable ? m.description : m.unavailable;
        draw(fit(description, Size.SMALL, w - 120), Size.SMALL, x + 15, y + 23, usable ? MUTED : WARN);
        // Every switch sits at the same place, whether or not its card has settings.
        int switchX = x + w - 64;
        if (m.toggle != null) toggleSwitch(switchX, y + 13, 26, 13, on, "card.switch." + m.name, usable);
        if (expandable) chevron(x + w - 18, y + CARD_H / 2f, false, over ? TEXT : MUTED);
        Runnable open = () -> openDetail(m);
        if (expandable) {
            hit("expand." + m.name, x, y, w, CARD_H, open, m.toggle);
            if (m.toggle != null) hit("module." + m.name, switchX - 8, y, 42, CARD_H, m.toggle, null);
        } else {
            hit("module." + m.name, x, y, w, CARD_H, m.toggle, null);
        }
    }

    private void drawDetail(Module m, int x, int top, int w, int bottom) {
        boolean overBack = inside(x, top, 62, 20);
        round(graphics, x, top, 62, 20, 10, overBack ? CARD_HOVER : CARD);
        outline(graphics, x, top, 62, 20, 10, overBack ? EDGE_HOVER : EDGE);
        chevronLeft(x + 15, top + 10, overBack ? TEXT : LABEL);
        draw("Back", Size.BASE, x + 24, top + 7, overBack ? TEXT : LABEL);
        hit("expand." + m.name, x, top, 62, 20, this::closeDetail, null);
        int y = top + 28, h = bottom - y;
        round(graphics, x, y, w, h, 9, CARD);
        outline(graphics, x, y, w, h, 9, EDGE);
        boolean usable = m.available.getAsBoolean();
        bold(fit(m.name, Size.BASE, w - 90), x + 16, y + 13, TEXT);
        draw(fit(usable ? m.description : m.unavailable, Size.SMALL, w - 90), Size.SMALL, x + 16, y + 27, usable ? MUTED : WARN);
        if (m.toggle != null) {
            int switchX = x + w - 46;
            toggleSwitch(switchX, y + 15, 30, 15, m.enabled.getAsBoolean(), "detail.switch." + m.name, usable);
            hit("module." + m.name, switchX - 8, y, 46, 44, m.toggle, null);
        }
        round(graphics, x + 1, y + 44, w - 2, 1, 0, EDGE);
        scrolled(x + 1, y + 45, w - 2, h - 49, CARD, origin -> {
            Column c = new Column(x + 16, w - 32, origin + 8, m.name);
            m.settings.accept(c);
            if (m.values != null) resetRow(c, m);
            c.flushChips();
            return c.y + 10;
        });
    }

    /** The last row of a module's settings: puts them all back to their defaults, on a second click. */
    private void resetRow(Column c, Module m) {
        String id = "reset." + m.name;
        boolean confirming = isArmed(id);
        c.section("Defaults");
        if (confirming) c.warnNext();
        c.button("reset", "Reset settings", confirming ? "Click Confirm within 8 seconds to reset every setting above"
                : "Every setting back to its default; the switch and keybind stay", confirming ? "Confirm" : "Reset", () -> {
            if (isArmed(id)) {
                armed.remove(id);
                resetToDefaults(m.values);
                armed.put("done." + m.name, System.currentTimeMillis() + 3_000);
            } else {
                armed.put(id, System.currentTimeMillis() + ARM_MILLIS);
            }
        });
        if (isArmed("done." + m.name)) c.note("Every setting is back to its default.", GOOD);
    }

    /** Kept by Reset: whether a module is on, whether it is a favorite, and the builder's schematic and where it goes. */
    private static final Set<String> KEPT_ON_RESET = Set.of("enabled", "favorite", "file", "originSet", "originX", "originY", "originZ");

    /**
     * Copies every saved setting from a fresh config into {@code values}, one of this config's module settings, leaving
     * those in {@link #KEPT_ON_RESET} as they are.
     */
    private void resetToDefaults(Object values) {
        ClientConfig fresh = new ClientConfig();
        try {
            for (Field holder : ClientConfig.class.getFields()) {
                if (holder.get(config) != values) continue;
                Object defaults = holder.get(fresh);
                for (Field f : values.getClass().getFields()) {
                    int modifiers = f.getModifiers();
                    if (Modifier.isStatic(modifiers) || Modifier.isFinal(modifiers) || Modifier.isTransient(modifiers)
                            || KEPT_ON_RESET.contains(f.getName())) continue;
                    f.set(values, f.get(defaults));
                }
                return;
            }
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Could not reset " + values.getClass().getSimpleName(), e);
        }
    }

    /** A switch; the knob slides across a track filled with the accent when on. */
    private void toggleSwitch(int x, int y, int w, int h, boolean on, String animation, boolean usable) {
        double position = animate(animation, on ? 1 : 0);
        int track = blend(TRACK, accent(), position);
        round(graphics, (float) x, (float) y, w, h, h / 2f, usable ? track : alpha(track, .45));
        if (position < .99) outline(graphics, (float) x, (float) y, w, h, h / 2f, alpha(0xFFFFFF, .12 * (1 - position)));
        float knob = h - 4f, knobX = x + 2f + (w - h) * (float) position;
        round(graphics, knobX, y + 2.6f, knob, knob, knob / 2, 0x55000000);
        round(graphics, knobX, y + 2f, knob, knob, knob / 2, usable ? 0xFFFFFFFF : 0xFF9AAAA8);
    }

    private void chevron(float cx, float cy, boolean down, int color) {
        if (down) {
            line(graphics, cx - 3, cy - 1.5f, cx, cy + 1.5f, color);
            line(graphics, cx, cy + 1.5f, cx + 3, cy - 1.5f, color);
        } else {
            line(graphics, cx - 1.5f, cy - 3, cx + 1.5f, cy, color);
            line(graphics, cx + 1.5f, cy, cx - 1.5f, cy + 3, color);
        }
    }

    private void chevronLeft(float cx, float cy, int color) {
        line(graphics, cx + 1.5f, cy - 3, cx - 1.5f, cy, color);
        line(graphics, cx - 1.5f, cy, cx + 1.5f, cy + 3, color);
    }

    private void drawTooltip() {
        List<String> lines = wrap(tooltip, 180, Size.SMALL);
        int w = 0;
        for (String line : lines) w = Math.max(w, width(line, Size.SMALL));
        w += 16;
        int h = lines.size() * 11 + 9;
        int x = (int) Math.min(mouseX + 10, layoutW - w - 4), y = (int) Math.min(mouseY + 14, layoutH - h - 4);
        shadow(graphics, x, y + 2, w, h, 6, 0x90000000);
        round(graphics, x, y, w, h, 6, 0xF5112122);
        outline(graphics, x, y, w, h, 6, EDGE_HOVER);
        for (int i = 0; i < lines.size(); i++) draw(lines.get(i), Size.SMALL, x + 8, y + 5 + i * 11, LABEL);
    }

    // Color editor ------------------------------------------------------------------------------

    private void drawColorEditor() {
        // Modal hit testing replaces the window's controls.
        hits.clear();
        clips.clear();
        graphics.fill(0, 0, layoutW, layoutH, 0x99030808);
        int w = 206, h = 168, x = (layoutW - w) / 2, y = (layoutH - h) / 2;
        int color = colorEditor.getter.getAsInt();
        shadow(graphics, x, y + 3, w, h, 10, 0xC0000000);
        round(graphics, x, y, w, h, 10, WINDOW);
        outline(graphics, x, y, w, h, 10, EDGE);
        bold(fit(colorEditor.title, Size.BASE, w - 70), x + 14, y + 12, TEXT);
        round(graphics, x + w - 58, y + 10, 22, 12, 4, alpha(color, 1));
        outline(graphics, x + w - 58, y + 10, 22, 12, 4, alpha(0xFFFFFF, .16));
        boolean overClose = inside(x + w - 28, y + 4, 22, 24);
        int closeColor = overClose ? TEXT : MUTED;
        line(graphics, x + w - 20, y + 12, x + w - 14, y + 18, closeColor);
        line(graphics, x + w - 14, y + 12, x + w - 20, y + 18, closeColor);
        hit("colorClose", x + w - 28, y + 4, 22, 24, this::closeColor, null);
        round(graphics, x + 1, y + 31, w - 2, 1, 0, EDGE);
        for (int i = 0; i < PALETTE.length; i++) {
            int px = x + 14 + i * 15, swatch = PALETTE[i];
            round(graphics, px, y + 42, 12, 12, 6, alpha(swatch, 1));
            if (color == swatch) outline(graphics, px - 2, y + 40, 16, 16, 8, TEXT);
            hit("swatch" + i, px - 2, y + 40, 16, 16, () -> { colorEditor.set(swatch); flush(); }, null);
        }
        colorChannel("Red", 16, 0xFFF17886, x, y + 66, w);
        colorChannel("Green", 8, 0xFF84DCB3, x, y + 86, w);
        colorChannel("Blue", 0, 0xFF81AEFF, x, y + 106, w);
        round(graphics, x + 14, y + 134, 90, 20, 6, FIELD);
        outline(graphics, x + 14, y + 134, 90, 20, 6, colorEditor.hexFocused ? accent() : EDGE);
        String value = "#" + colorEditor.hex + (colorEditor.hexFocused && System.currentTimeMillis() % 1000 < 500 ? "|" : "");
        draw(value, Size.BASE, x + 22, y + 141, colorEditor.hex.length() == 6 ? TEXT : WARN);
        hit("hex", x + 14, y + 134, 90, 20, () -> { colorEditor.hexFocused = true; colorEditor.replaceHex = true; }, null);
        boolean overDone = inside(x + w - 84, y + 134, 70, 20);
        round(graphics, x + w - 84, y + 134, 70, 20, 6, overDone ? accent() : alpha(accent(), .85));
        bold("Done", x + w - 49 - width("Done", Size.BASE) / 2, y + 141, 0xFF041011);
        hit("colorDone", x + w - 84, y + 134, 70, 20, this::closeColor, null);
    }

    private void colorChannel(String label, int shift, int tint, int x, int y, int w) {
        int value = (colorEditor.getter.getAsInt() >> shift) & 255;
        draw(label, Size.SMALL, x + 14, y + 1, tint);
        String number = Integer.toString(value);
        draw(number, Size.SMALL, x + w - 14 - width(number, Size.SMALL), y + 1, MUTED);
        int sx = x + 56, sw = w - 96;
        float filled = sw * value / 255f;
        round(graphics, (float) sx, y + 3.5f, (float) sw, 3f, 1.5f, TRACK);
        round(graphics, (float) sx, y + 3.5f, Math.max(3f, filled), 3f, 1.5f, tint);
        round(graphics, sx + filled - 4f, y + 1f, 8f, 8f, 4f, tint);
        dragHit("rgb" + shift, sx, y - 4, sw, 16, t -> {
            int channel = (int) Math.round(Math.clamp(t, 0, 1) * 255);
            colorEditor.set((colorEditor.getter.getAsInt() & ~(255 << shift)) | channel << shift);
        }, direction -> {
            int channel = Math.clamp(((colorEditor.getter.getAsInt() >> shift) & 255) + direction, 0, 255);
            colorEditor.set((colorEditor.getter.getAsInt() & ~(255 << shift)) | channel << shift);
            flush();
        });
    }

    private void closeColor() { colorEditor = null; dragging = null; keyboardFocus = null; flush(); }
    private String hex(int color) { return String.format(Locale.ROOT, "#%06X", color & 0xFFFFFF); }

    // Keybinds ----------------------------------------------------------------------------------

    private String keyName(KeyMapping key) { return key.getTranslatedKeyMessage().getString(); }

    /** The key binding index of the key that opens this menu, after the modules' own. */
    private static final int OPEN_KEY = ModuleKeybinds.COUNT;

    private static KeyMapping keyMapping(int index) {
        return index == OPEN_KEY ? KeyMapping.get("key.donut.open_gui") : ModuleKeybinds.mapping(index);
    }

    /** Binds a key; the key that opens this menu is never left unbound, since nothing else opens it in game. */
    private void setKey(int index, InputConstants.Key key) {
        if (index != OPEN_KEY) {
            ModuleKeybinds.setKey(minecraft, index, key);
            return;
        }
        KeyMapping open = keyMapping(OPEN_KEY);
        if (open == null) return;
        open.setKey(key.equals(InputConstants.UNKNOWN) ? open.getDefaultKey() : key);
        KeyMapping.resetMapping();
        minecraft.options.save();
    }

    /** Whether the key that opens this menu is held down, as it is the moment it opens the menu. */
    private boolean openKeyHeld() {
        KeyMapping open = keyMapping(OPEN_KEY);
        if (open == null || open.isUnbound()) return false;
        InputConstants.Key key = InputConstants.getKey(open.saveString());
        long window = minecraft.getWindow().handle();
        return key.getType() == InputConstants.Type.MOUSE ? GLFW.glfwGetMouseButton(window, key.getValue()) == GLFW.GLFW_PRESS
                : key.getType() == InputConstants.Type.KEYSYM && GLFW.glfwGetKey(window, key.getValue()) == GLFW.GLFW_PRESS;
    }

    private String conflicts(int index) {
        KeyMapping mapping = keyMapping(index);
        if (mapping == null || mapping.isUnbound()) return "";
        List<String> names = new ArrayList<>();
        for (KeyMapping other : minecraft.options.keyMappings) {
            if (other != mapping && mapping.same(other)) names.add(Component.translatable(other.getName()).getString());
        }
        return String.join(", ", names);
    }

    // Text fields -------------------------------------------------------------------------------

    /** Saves the focused field as focus leaves it; a guarded field's edit is dropped instead, since it saves only on Enter. */
    private void commitText() { commitText(false); }

    /**
     * Saves the focused field. A guarded field saves only on Enter, and only on the second Enter of the same text within
     * eight seconds; the first one shows what saving will do, and text it cannot save stays in the field with the reason.
     */
    private void commitText(boolean entered) {
        if (textField == null) return;
        if (textConfirm != null && !textBuffer.equals(textOriginal)) {
            if (!entered) {
                reply("Not saved: amber fields save on Enter, twice.", true);
                textField = null;
                return;
            }
            Confirm check = textConfirm.apply(textBuffer);
            long now = System.currentTimeMillis();
            if (!check.ok()) {
                notice(check.text());
                return;
            }
            if (!textBuffer.equals(textArmed) || now > textArmedUntil) {
                textArmed = textBuffer;
                textArmedUntil = now + ARM_MILLIS;
                return;
            }
        }
        textField = null;
        String value = textBuffer;
        Consumer<String> setter = textSetter;
        safely(() -> setter.accept(value));
        config.sanitize();
        dirty = true;
        flush();
    }

    /** Leaves the focused field without saving it. */
    private void cancelText() { textField = null; }

    private void notice(String text) {
        textNotice = text;
        textNoticeUntil = System.currentTimeMillis() + 4_000;
    }

    // Helpers -----------------------------------------------------------------------------------

    private int accent() { return alpha(config.accentColor, 1); }

    private void draw(String text, Size size, int x, int y, int color) { draw(text, size, x, y, color, false, false); }

    private void draw(String text, Size size, int x, int y, int color, boolean bold, boolean italic) {
        UiPaint.text(graphics, font, text, size, x, y, color, bold, italic);
    }

    private void bold(String text, int x, int y, int color) { draw(text, Size.BASE, x, y, color, true, false); }

    private int width(String text, Size size) { return UiPaint.width(font, text, size); }

    /**
     * The text cut to fit, ending in an ellipsis; a cut never leaves a stray period or comma before it. Long
     * descriptions can run to a thousand characters, so the cut is found by halving and remembered until the next resize.
     */
    private String fit(String value, Size size, int maxWidth) {
        String key = size.ordinal() + ":" + maxWidth + ":" + value;
        String cached = fitted.get(key);
        if (cached != null) return cached;
        String result = value;
        if (width(value, size) > maxWidth) {
            int low = 0, high = value.length();
            while (low < high) {
                int mid = (low + high + 1) >>> 1;
                if (width(value.substring(0, mid) + "…", size) <= maxWidth) low = mid;
                else high = mid - 1;
            }
            if (low > 0 && Character.isHighSurrogate(value.charAt(low - 1))) low--;
            result = TRAILING.matcher(value.substring(0, low)).replaceAll("") + "…";
        }
        if (fitted.size() > 4_000) fitted.clear();
        fitted.put(key, result);
        return result;
    }

    /** The end of the text that fits, for fields and the search box, which keep what is being typed in view. */
    private String tail(String value, Size size, int maxWidth) {
        if (width(value, size) <= maxWidth) return value;
        int low = 0, high = value.length();
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (width(value.substring(mid), size) <= maxWidth) high = mid;
            else low = mid + 1;
        }
        if (low < value.length() && Character.isLowSurrogate(value.charAt(low))) low++;
        return value.substring(low);
    }

    private List<String> wrap(String value, int maxWidth, Size size) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : value.split(" ")) {
            String next = line.isEmpty() ? word : line + " " + word;
            if (!line.isEmpty() && width(next, size) > maxWidth) {
                lines.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(next);
            }
        }
        if (!line.isEmpty()) lines.add(fit(line.toString(), size, maxWidth));
        return lines;
    }

    private double ease(double current, double target, double speed) {
        double result = current + (target - current) * (1 - Math.exp(-speed * frameSeconds));
        if (Math.abs(result - target) < .001) return target;
        moving = true;
        return result;
    }

    private double animate(String id, double target) {
        double result = ease(animations.getOrDefault(id, target), target, 16);
        animations.put(id, result);
        return result;
    }

    private void pushClip(int x0, int y0, int x1, int y1) {
        int[] top = clips.peek();
        int[] clip = top == null ? new int[]{x0, y0, x1, y1}
                : new int[]{Math.max(x0, top[0]), Math.max(y0, top[1]), Math.min(x1, top[2]), Math.min(y1, top[3])};
        clips.push(clip);
        graphics.enableScissor(x0, y0, x1, Math.max(y0, y1));
    }

    private void popClip() {
        clips.pop();
        graphics.disableScissor();
    }

    private boolean inside(int x, int y, int w, int h) {
        if (drawing != hovered) return false;
        int[] clip = clips.peek();
        if (clip != null && (mouseX < clip[0] || mouseX >= clip[2] || mouseY < clip[1] || mouseY >= clip[3])) return false;
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }

    /** Records where content sits in its region, so it can be scrolled into view later. */
    private void mark(String id, int y, int h) {
        if (drawing != null) places.put(id, new Place(drawing, y - contentOrigin, h));
    }

    private void hit(String id, int x, int y, int w, int h, Runnable action, Runnable secondary) {
        addHit(id, x, y, w, h, action, secondary, null, null);
    }

    private void dragHit(String id, int x, int y, int w, int h, DoubleConsumer action, IntConsumer nudge) {
        addHit(id, x, y, w, h, null, null, action, nudge);
    }

    private void addHit(String id, int x, int y, int w, int h, Runnable action, Runnable secondary, DoubleConsumer drag, IntConsumer nudge) {
        if (!clips.isEmpty()) mark(id, y, h);
        int[] clip = clips.peek();
        int left = x, top = y, right = x + w, bottom = y + h;
        if (clip != null) {
            left = Math.max(left, clip[0]); top = Math.max(top, clip[1]);
            right = Math.min(right, clip[2]); bottom = Math.min(bottom, clip[3]);
            if (right <= left || bottom <= top) return;
        }
        // Drag targets keep their full width, so a slider's value still maps across the whole track.
        hits.add(new Hit(id, drawing, drag == null ? left : x, top, drag == null ? right - left : w, bottom - top,
                action, secondary, drag, nudge));
    }

    /**
     * A control's x, y, width and height in screen coordinates as drawn in the last frame, or null unless all of it
     * was on screen. For game tests, which click controls by id.
     */
    public double[] controlBounds(String id) {
        Place place = places.get(id);
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit h = hits.get(i);
            if (!h.id.equals(id)) continue;
            // Partly scrolled out of view, or covered.
            if (place != null && h.h < place.h || regionAt(h.x + h.w / 2.0, h.y + h.h / 2.0) != h.region) return null;
            return new double[]{h.x * scale, h.y * scale, h.w * scale, h.h * scale};
        }
        return null;
    }

    /** Whether a module's settings page is open. */
    public boolean isExpanded(String module) { return module.equals(detailName) && query.isEmpty(); }

    /** Whether a module's settings show their "More options". */
    public boolean showsMore(String module) { return MORE.contains(module + ".more"); }

    /** True once the last frame had no animation or scrolling still in progress. */
    public boolean isSettled() { return settled; }

    /**
     * Scrolls a control into view from the next frame, first showing the page that holds it if needed; false until the
     * control has been laid out.
     */
    public boolean scrollTo(String id) {
        Place place = places.get(id);
        if (place == null) {
            // Headers, the sidebar and the search box never scroll.
            if (hits.stream().anyMatch(h -> h.id.equals(id))) return true;
            navigateTo(id);
            return false;
        }
        Region r = place.region;
        if (place.y < r.scroll || place.y + place.h > r.scroll + r.h) {
            r.scroll = r.scrollTarget = Math.clamp(place.y - 12, 0, Math.max(0, r.contentHeight - r.h));
        }
        return true;
    }

    // Input -------------------------------------------------------------------------------------

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        if (binding >= 0) {
            // The two main buttons cancel; binding them would toggle the module on every attack or use.
            int button = event.button();
            if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT && button != GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
                setKey(binding, InputConstants.Type.MOUSE.getOrCreate(button));
            }
            binding = -1;
            return true;
        }
        // The mouse's back button goes back from a module's settings, as in a browser.
        if (event.button() == GLFW.GLFW_MOUSE_BUTTON_4 && colorEditor == null && detail() != null) {
            commitText();
            closeDetail();
            return true;
        }
        boolean secondary = event.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT;
        if (event.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT && !secondary) return false;
        double x = event.x() / scale, y = event.y() / scale;
        keyboardFocus = null;
        searchFocused = false;
        if (colorEditor != null) colorEditor.hexFocused = false;
        Region under = regionAt(x, y);
        Hit target = null;
        for (int i = hits.size() - 1; i >= 0 && target == null; i--) {
            Hit h = hits.get(i);
            if (h.contains(x, y) && h.region == under) target = h;
        }
        // A click inside the field being edited only moves the cursor to its end; anywhere else saves the edit first.
        if (target != null && target.id.equals(textField)) {
            textSelected = false;
            return true;
        }
        commitText();
        if (target != null) {
            // The second click of a double click on the same control counts once, so it cannot confirm what the first armed.
            if (doubled && target.id.equals(lastClicked)) return true;
            lastClicked = target.id;
            if (secondary) { if (target.secondary != null) safely(target.secondary); }
            else if (target.drag != null) { dragging = target; target.move(x, y); }
            else safely(target.action);
            return true;
        }
        // Clicking beside the color editor closes it.
        if (colorEditor != null && (Math.abs(x - layoutW / 2.0) > 103 || Math.abs(y - layoutH / 2.0) > 84)) closeColor();
        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (dragging == null) return false;
        dragging.move(event.x() / scale, event.y() / scale);
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (dragging == null) return false;
        dragging = null;
        commitDrafts();
        flush();
        return true;
    }

    /** Sets the values deferred sliders held while they were dragged. */
    private void commitDrafts() {
        drafts.forEach((id, value) -> {
            DoubleConsumer setter = commits.get(id);
            if (setter != null) safely(() -> setter.accept(value));
        });
        drafts.clear();
        commits.clear();
    }

    /** A value on the slider's grid: whole steps from {@code min}, without floating-point dust such as 0.30000000000000004. */
    private static double snap(double value, double min, double max, double step) {
        if (!(max > min) || !(step > 0)) return min;
        double snapped = min + Math.round((value - min) / step) * step;
        return Math.clamp(Math.round(snapped * 1e9) / 1e9, min, max);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (colorEditor != null || binding >= 0) return true;
        Region r = regionAt(x / scale, y / scale);
        if (r == null || r == chrome) return false;
        scroll(r, -vertical * 24);
        keyboardFocus = null;
        return true;
    }

    private void scroll(Region r, double amount) {
        // Whole units, so a touchpad's fractional steps never leave the content resting between pixels.
        r.scrollTarget = Math.clamp(Math.round(r.scrollTarget + amount), 0, Math.max(0, r.contentHeight - r.h));
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        // A held capture/cancel key must not repeat into ordinary GUI navigation.
        InputConstants.Key pressed = InputConstants.getKey(event);
        if (pressed.equals(capturedKey)) return true;
        if (binding >= 0) {
            capturedKey = pressed;
            if (event.key() != GLFW.GLFW_KEY_ESCAPE) {
                InputConstants.Key key = event.key() == GLFW.GLFW_KEY_DELETE || event.key() == GLFW.GLFW_KEY_BACKSPACE
                        ? InputConstants.UNKNOWN : pressed;
                setKey(binding, key);
            }
            binding = -1;
            return true;
        }
        // Enter and Space act once per press; a held key's repeats must not arm and then confirm a guarded change.
        if (pressed.equals(activationKey)) return true;
        if (event.isEscape()) {
            if (colorEditor != null) closeColor();
            else if (textField != null) cancelText();
            else if (!query.isEmpty()) { searchFocused = false; query = ""; restart(); }
            else if (searchFocused) searchFocused = false;
            else onClose();
            return true;
        }
        KeyMapping open = keyMapping(OPEN_KEY);
        if (open != null && open.matches(event) && textField == null && !searchFocused && colorEditor == null) {
            onClose();
            return true;
        }
        if (colorEditor != null && colorEditor.hexFocused) {
            if (event.isPaste()) {
                String pasted = minecraft.keyboardHandler.getClipboard().trim().replaceFirst("^#", "");
                if (pasted.matches("(?i)[0-9a-f]{6}")) { colorEditor.set(Integer.parseInt(pasted, 16)); flush(); }
                return true;
            }
            if (event.isCopy()) { minecraft.keyboardHandler.setClipboard("#" + colorEditor.hex); return true; }
            if (event.key() == GLFW.GLFW_KEY_BACKSPACE) {
                colorEditor.hex = colorEditor.replaceHex ? "" : colorEditor.hex.substring(0, Math.max(0, colorEditor.hex.length() - 1));
                colorEditor.replaceHex = false;
                return true;
            }
            if (event.isConfirmation()) { closeColor(); return true; }
            if (event.isSelectAll()) { colorEditor.replaceHex = true; return true; }
        }
        if (searchFocused) {
            if (event.isPaste()) {
                String pasted = minecraft.keyboardHandler.getClipboard().replaceAll("[\\p{Cntrl}]", "");
                query = (query + pasted).substring(0, Math.min(64, query.length() + pasted.length()));
                content.scroll = content.scrollTarget = 0;
                return true;
            }
            if (event.key() == GLFW.GLFW_KEY_BACKSPACE) { query = removeLastCharacter(query); return true; }
            if (event.isConfirmation()) { searchFocused = false; return true; }
            if (event.isSelectAll()) { query = ""; return true; }
        }
        if (textField != null) {
            if (event.isPaste()) {
                // Line breaks and tabs separate entries, so a pasted column of items stays a list of items.
                String pasted = SEPARATORS.matcher(minecraft.keyboardHandler.getClipboard().strip()).replaceAll(", ")
                        .replaceAll("[\\p{Cntrl}]", "");
                String base = textSelected ? "" : textBuffer;
                // A paste that does not fit is refused whole: a cut could leave a valid but wrong last entry.
                if (base.length() + pasted.length() > textLimit) {
                    notice("Too long to paste: " + (base.length() + pasted.length()) + " characters, and this takes " + textLimit);
                } else {
                    textBuffer = base + pasted;
                    textSelected = false;
                }
                return true;
            }
            if (event.isCopy() && !textSecret) { minecraft.keyboardHandler.setClipboard(textBuffer); return true; }
            if (event.isSelectAll()) { textSelected = true; return true; }
            if (event.key() == GLFW.GLFW_KEY_BACKSPACE) {
                textBuffer = textSelected ? "" : removeLastCharacter(textBuffer);
                textSelected = false;
                return true;
            }
            if (event.isConfirmation()) { activationKey = pressed; commitText(true); return true; }
            if (event.isLeft() || event.isRight() || event.key() == GLFW.GLFW_KEY_END) { textSelected = false; return true; }
            if (event.isCycleFocus()) commitText();
        }
        if (event.isCycleFocus() && !hits.isEmpty()) {
            int index = -1;
            for (int i = 0; i < hits.size(); i++) if (hits.get(i).id.equals(keyboardFocus)) index = i;
            int direction = event.hasShiftDown() ? -1 : 1;
            keyboardFocus = hits.get(Math.floorMod(index + direction, hits.size())).id;
            searchFocused = false;
            scrollTo(keyboardFocus);
            return true;
        }
        if (keyboardFocus != null && event.isSelection()) {
            activationKey = pressed;
            hits.stream().filter(h -> h.id.equals(keyboardFocus)).findFirst().ifPresent(h -> {
                if (h.action != null) safely(h.action);
            });
            // Enter/Space that opens capture is not itself the new binding on repeat.
            if (binding >= 0) capturedKey = pressed;
            return true;
        }
        if (keyboardFocus != null && (event.isLeft() || event.isRight())) {
            hits.stream().filter(h -> h.id.equals(keyboardFocus)).findFirst().ifPresent(h -> {
                if (h.nudge != null) h.nudge.accept(event.isRight() ? 1 : -1);
            });
            return true;
        }
        if (colorEditor == null) {
            if (event.key() == GLFW.GLFW_KEY_PAGE_DOWN || event.key() == GLFW.GLFW_KEY_PAGE_UP) {
                scroll(content, event.key() == GLFW.GLFW_KEY_PAGE_DOWN ? 150 : -150);
                keyboardFocus = null;
                return true;
            }
            if (event.key() == GLFW.GLFW_KEY_HOME) { content.scrollTarget = 0; return true; }
            if (event.key() == GLFW.GLFW_KEY_END) { scroll(content, content.contentHeight); return true; }
            // With nothing being typed, Backspace goes back from a module's settings.
            if (event.key() == GLFW.GLFW_KEY_BACKSPACE && !searchFocused && detail() != null) { closeDetail(); return true; }
        }
        return false;
    }

    @Override
    public boolean keyReleased(KeyEvent event) {
        InputConstants.Key released = InputConstants.getKey(event);
        if (released.equals(capturedKey)) capturedKey = null;
        if (released.equals(activationKey)) activationKey = null;
        // A deferred slider stepped with the arrow keys is set once the key is let go.
        if ((event.isLeft() || event.isRight()) && !drafts.isEmpty() && dragging == null) {
            commitDrafts();
            flush();
        }
        return super.keyReleased(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (binding >= 0 || capturedKey != null) return true;
        if (colorEditor != null && colorEditor.hexFocused) {
            int c = event.codepoint();
            if (Character.digit(c, 16) >= 0 && c < 128) {
                if (colorEditor.replaceHex) { colorEditor.hex = ""; colorEditor.replaceHex = false; }
                if (colorEditor.hex.length() < 6) colorEditor.hex += Character.toString(c).toUpperCase(Locale.ROOT);
                if (colorEditor.hex.length() == 6) { colorEditor.setter.accept(Integer.parseInt(colorEditor.hex, 16)); dirty = true; flush(); }
            }
            return true;
        }
        if (textField != null) {
            if (!event.isAllowedChatCharacter()) return true;
            // Typing over selected text replaces it.
            if (textSelected) {
                textBuffer = "";
                textSelected = false;
            }
            if (textBuffer.length() < textLimit) textBuffer += event.codepointAsString();
            return true;
        }
        // Typing anywhere else searches, as long as no other field has focus.
        if (colorEditor == null && !searchFocused && event.isAllowedChatCharacter() && !Character.isWhitespace(event.codepoint())) {
            searchFocused = true;
            keyboardFocus = null;
        }
        if (searchFocused && event.isAllowedChatCharacter() && query.length() < 64) {
            if (query.isEmpty()) content.scroll = content.scrollTarget = 0;
            query += event.codepointAsString();
            return true;
        }
        return false;
    }

    private void change(Runnable action) { action.run(); dirty = true; flush(); }

    /** Runs a control's action; a failure in it is logged and shown instead of crashing the game. */
    private void safely(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException | LinkageError e) {
            LOG.error("A Donut menu action failed", e);
            reply("That did not work: " + e, true);
        }
    }
    private void flush() { if (dirty) { save.run(); dirty = false; } }
    private String removeLastCharacter(String value) { return value.isEmpty() ? value : value.substring(0, value.offsetByCodePoints(value.length(), -1)); }

    @Override
    public void onClose() { flush(); minecraft.gui.setScreen(null); }

    @Override
    public void removed() {
        remember();
        commitText();
        commitDrafts();
        binding = -1;
        capturedKey = null;
        ModuleKeybinds.suppressUntilReleased(minecraft);
        flush();
    }

    @Override
    public boolean isPauseScreen() { return false; }

    // Settings rows -----------------------------------------------------------------------------

    /**
     * Lays out a module's settings or a page's content from top to bottom: each setting is a row with its label and a
     * short description on the left and its control on the right.
     */
    private final class Column {
        final int x, w;
        final String scope;
        int y;
        /** Where the next chip goes on the current line of chips, or -1 when no line is open. */
        private int chipX = -1;

        Column(int x, int w, int y, String scope) {
            this.x = x;
            this.w = w;
            this.y = y;
            this.scope = scope;
        }

        private String id(String key) { return scope.isEmpty() ? key : scope + "." + key; }

        private int rowHeight(String description) { return description == null || description.isEmpty() ? 22 : 32; }

        /** Set by {@link #warnNext()}: the next row's description is a warning. */
        private boolean warning;

        /** Shows the next row's description in the warning color. */
        void warnNext() { warning = true; }

        /** Set by {@link #resetNext}: the next row's Reset link, its id, its default and where it was drawn. */
        private Runnable reset;
        private String resetId, resetDefault;
        private int[] resetAt;

        /**
         * Marks the next row as changed from its default: a dot follows its label, and while the row is hovered a
         * Reset link beside the dot puts it back.
         */
        void resetNext(String key, String defaultValue, Runnable action) {
            reset = action;
            resetId = id(key) + ".reset";
            resetDefault = defaultValue;
        }

        /** Moves below a row; its Reset link, added last, takes clicks before the row under it. */
        private void endRow(int h) {
            if (resetAt != null) {
                Runnable action = reset;
                hit(resetId, resetAt[0], resetAt[1], resetAt[2], resetAt[3], () -> change(action), null);
            }
            reset = null;
            resetAt = null;
            y += h + 2;
        }

        /**
         * A row's label and description; a hovered row gets an accent tick at its left edge, and a description too
         * long for the row shows in full beside the mouse.
         */
        private void rowLabel(String label, String description, int descriptionColor, boolean over, int h, int controlWidth) {
            int room = w - controlWidth - 14;
            boolean described = description != null && !description.isEmpty();
            if (warning) {
                descriptionColor = WARN;
                warning = false;
            }
            if (over) round(graphics, x - 9, y + 6, 2f, h - 12f, 1f, accent());
            // A label without a description sits in the middle of its row, whatever the row's height.
            int labelY = y + (described ? 7 : (h - 6) / 2), linkW = width("Reset", Size.SMALL) + 12;
            String shownLabel = fit(label, Size.BASE, reset == null ? room : room - linkW - 6);
            bold(shownLabel, x, labelY, TEXT);
            boolean overLink = false;
            if (reset != null) {
                int lx = x + width(shownLabel, Size.BASE) + 6;
                overLink = inside(lx - 3, labelY - 4, linkW + 3, 15);
                round(graphics, lx, labelY + 2, 4, 4, 2, accent());
                if (over || resetId.equals(keyboardFocus)) {
                    draw("Reset", Size.SMALL, lx + 9, labelY + 1, overLink ? accent() : DIM, overLink, false);
                }
                if (overLink) tooltip = "Default: " + resetDefault;
                resetAt = new int[]{lx - 3, labelY - 4, linkW + 3, 15};
            }
            if (!described) return;
            String shown = fit(description, Size.SMALL, room);
            draw(shown, Size.SMALL, x, y + 20, descriptionColor);
            if (over && !overLink && !shown.equals(description)) tooltip = description;
        }

        void section(String title) {
            flushChips();
            y += 10;
            String label = title.toUpperCase(Locale.ROOT);
            mark(id("section." + title), y, 12);
            draw(label, Size.SMALL, x, y + 2, DIM);
            int rule = x + width(label, Size.SMALL) + 8;
            if (rule < x + w) round(graphics, rule, y + 5, x + w - rule, 1, 0, EDGE);
            y += 16;
        }

        /** A line of text the width of the column. */
        void label(String value, int color) {
            flushChips();
            draw(fit(value, Size.BASE, w), Size.BASE, x, y + 3, color);
            y += 16;
        }

        void toggle(String key, String label, String description, BooleanSupplier value, Runnable action) {
            flushChips();
            String id = id(key);
            int h = rowHeight(description);
            boolean over = inside(x - 10, y, w + 20, h);
            rowLabel(label, description, MUTED, over, h, 40);
            toggleSwitch(x + w - 26, y + (h - 13) / 2, 26, 13, value.getAsBoolean(), "toggle." + id, true);
            hit(id, x - 10, y, w + 20, h, () -> change(action), null);
            endRow(h);
        }

        /** A small on/off pill; consecutive chips share lines, so a list of filters stays compact. */
        void chip(String key, String label, String description, BooleanSupplier value, Runnable action) {
            String id = id(key);
            int chipW = width(label, Size.BASE) + 20;
            if (chipX < 0) {
                chipX = x;
                y += 2;
            } else if (chipX + chipW > x + w) {
                chipX = x;
                y += 24;
            }
            boolean over = inside(chipX, y, chipW, 20);
            if (over) tooltip = description;
            double on = animate("chip." + id, value.getAsBoolean() ? 1 : 0);
            round(graphics, chipX, y, chipW, 20, 10, blend(FIELD, blend(CARD, accent(), .18), on));
            outline(graphics, chipX, y, chipW, 20, 10, blend(over ? EDGE_HOVER : EDGE, accent(), (over ? .7 : .5) * on));
            draw(label, Size.BASE, chipX + 10, y + 7, over ? TEXT : blend(MUTED, TEXT, on));
            hit(id, chipX, y, chipW, 20, () -> change(action), null);
            chipX += chipW + 6;
        }

        /** Ends the current line of chips. */
        void flushChips() {
            if (chipX < 0) return;
            chipX = -1;
            y += 26;
        }

        void choice(String key, String label, int count, IntSupplier getter, IntConsumer setter, IntFunction<String> name) {
            choice(key, label, null, count, getter, setter, name);
        }

        /** A segmented control; each option is its own target, named by the option. */
        void choice(String key, String label, String description, int count, IntSupplier getter, IntConsumer setter,
                    IntFunction<String> name) {
            flushChips();
            String id = id(key);
            int index = getter.getAsInt(), h = description == null || description.isEmpty() ? 26 : 32, total = 4;
            int[] widths = new int[count];
            for (int i = 0; i < count; i++) total += widths[i] = width(name.apply(i), Size.BASE) + 22;
            int left = x + w - total, top = y + (h - 20) / 2;
            boolean over = inside(x - 10, y, w + 20, h);
            rowLabel(label, description, MUTED, over, h, total);
            round(graphics, left, top, total, 20, 7, FIELD);
            outline(graphics, left, top, total, 20, 7, EDGE);
            int offset = 2;
            for (int i = 0; i < index; i++) offset += widths[i];
            // The highlight slides by its offset inside the control, so moving the control (a resize) does not animate it.
            float slideX = left + (float) animate("choice.x." + id, offset), slideW = (float) animate("choice.w." + id, widths[index]);
            round(graphics, slideX, top + 2f, slideW, 16f, 5f, blend(CARD, accent(), .22));
            outline(graphics, slideX, top + 2f, slideW, 16f, 5f, alpha(config.accentColor, .8));
            int sx = left + 2;
            for (int i = 0; i < count; i++) {
                String option = name.apply(i);
                boolean overOption = inside(sx, top, widths[i], 20);
                draw(option, Size.BASE, sx + (widths[i] - width(option, Size.BASE)) / 2, top + 7,
                        i == index ? TEXT : overOption ? LABEL : MUTED, i == index, false);
                int value = i;
                hit(id + "." + option, sx, top, widths[i], 20, () -> change(() -> setter.accept(value)), null);
                sx += widths[i];
            }
            endRow(h);
        }

        void slider(String key, String label, String description, DoubleSupplier getter, DoubleConsumer setter,
                    double min, double max, double step, String unit) {
            slider(key, label, description, getter, setter, min, max, step, value -> unit.equals("%") ? Math.round(value * 100) + "%"
                    : step >= 1 ? Math.round(value) + unit : String.format(Locale.ROOT, "%.1f%s", value, unit));
        }

        /** A slider whose value box shows the value as {@code format} writes it. */
        void slider(String key, String label, String description, DoubleSupplier getter, DoubleConsumer setter,
                    double min, double max, double step, DoubleFunction<String> format) {
            slider(key, label, description, getter, setter, min, max, step, format, false);
        }

        /**
         * A slider; values snap to {@code step} counted from {@code min}. A deferred slider holds its value while it is
         * dragged and sets it once, on release, for settings that are expensive to write.
         */
        void slider(String key, String label, String description, DoubleSupplier getter, DoubleConsumer setter,
                    double min, double max, double step, DoubleFunction<String> format, boolean deferred) {
            flushChips();
            String id = id(key);
            Double draft = drafts.get(id);
            double value = draft != null ? draft : getter.getAsDouble();
            String display = format.apply(value);
            int h = rowHeight(description);
            // The value box is as wide as the widest value any slider of this module shows, so tracks line up and a track
            // never moves under the mouse while its value grows a digit.
            int widest = Math.max(width(display, Size.BASE),
                    Math.max(width(format.apply(min), Size.BASE), width(format.apply(max), Size.BASE)));
            int box = sliderBoxes.merge(scope, Math.max(46, widest + 16), Math::max), trackW = Math.min(110, (int) (w * .24));
            int trackX = x + w - box - 14 - trackW, mid = y + h / 2;
            boolean over = inside(x - 10, y, w + 20, h);
            rowLabel(label, description, MUTED, over, h, box + 14 + trackW + 6);
            float filled = max > min ? (float) (trackW * Math.clamp((value - min) / (max - min), 0, 1)) : 0;
            round(graphics, (float) trackX, mid - 2f, (float) trackW, 4f, 2f, TRACK);
            round(graphics, (float) trackX, mid - 2f, Math.max(4f, filled), 4f, 2f, accent());
            round(graphics, trackX + filled - 5f, mid - 4.4f, 10f, 10f, 5f, 0x66000000);
            round(graphics, trackX + filled - 5f, mid - 5f, 10f, 10f, 5f, over ? blend(accent(), 0xFFFFFFFF, .25) : accent());
            round(graphics, x + w - box, mid - 9, box, 18, 5, FIELD);
            outline(graphics, x + w - box, mid - 9, box, 18, 5, EDGE);
            draw(display, Size.BASE, x + w - box + (box - width(display, Size.BASE)) / 2, mid - 3, TEXT);
            dragHit(id, trackX, y, trackW, h, position -> {
                double next = snap(min + Math.clamp(position, 0, 1) * (max - min), min, max, step);
                if (deferred) {
                    drafts.put(id, next);
                    commits.put(id, setter);
                } else {
                    setter.accept(next);
                    dirty = true;
                }
            }, direction -> {
                // A held arrow key steps a deferred slider's draft, which is set once the key is let go.
                Double held = drafts.get(id);
                double next = snap((held != null ? held : getter.getAsDouble()) + direction * step, min, max, step);
                if (deferred) {
                    drafts.put(id, next);
                    commits.put(id, setter);
                } else {
                    setter.accept(next);
                    dirty = true;
                    flush();
                }
            });
            endRow(h);
        }

        void color(String key, String label, IntSupplier getter, IntConsumer setter) { color(key, label, null, getter, setter); }

        void color(String key, String label, String description, IntSupplier getter, IntConsumer setter) {
            flushChips();
            String id = id(key);
            int h = rowHeight(description), mid = y + h / 2;
            boolean over = inside(x - 10, y, w + 20, h);
            String code = hex(getter.getAsInt());
            rowLabel(label, description, MUTED, over, h, 30 + 10 + width(code, Size.SMALL));
            draw(code, Size.SMALL, x + w - 40 - width(code, Size.SMALL), mid - 3, over ? LABEL : DIM);
            round(graphics, x + w - 30, mid - 7, 30, 14, 5, alpha(getter.getAsInt(), 1));
            outline(graphics, x + w - 30, mid - 7, 30, 14, 5, alpha(0xFFFFFF, over ? .3 : .14));
            hit(id, x - 10, y, w + 20, h, () -> { colorEditor = new ColorEditor(label, getter, setter); keyboardFocus = null; }, null);
            endRow(h);
        }

        void field(String key, String label, String description, Supplier<String> getter, Consumer<String> setter, boolean secret) {
            field(key, label, description, getter, setter, secret, 128);
        }

        void field(String key, String label, String description, Supplier<String> getter, Consumer<String> setter, boolean secret,
                   int limit) {
            field(key, label, description, getter, setter, secret, limit, 220);
        }

        /** Set by {@link #confirmNext}: the next field is guarded, and saves on a second Enter after saying what it sets. */
        private Function<String, Confirm> confirm;
        /** Set by {@link #hintNext}: what the next field takes, shown while it is being edited. */
        private String hint;

        void confirmNext(Function<String, Confirm> check) { confirm = check; }

        void hintNext(String text) { hint = text; }

        /**
         * A text field holding at most {@code limit} characters, in a box up to {@code boxWidth} wide; long lists such as
         * item ids need more than names do. Clicking it selects its text, so typing or pasting replaces it; Enter saves,
         * and Esc leaves it as it was.
         */
        void field(String key, String label, String description, Supplier<String> getter, Consumer<String> setter, boolean secret,
                   int limit, int boxWidth) {
            flushChips();
            String id = id(key);
            boolean focused = id.equals(textField);
            Function<String, Confirm> check = confirm;
            String takes = hint;
            confirm = null;
            hint = null;
            if (focused) {
                // While it is edited, the row says what the field takes, what a second Enter does, or why it cannot save.
                long now = System.currentTimeMillis();
                if (textNotice != null && now < textNoticeUntil) {
                    description = textNotice;
                    warning = true;
                } else if (check != null && textBuffer.equals(textArmed) && now < textArmedUntil) {
                    description = "Press Enter again within 8 seconds to " + check.apply(textBuffer).text();
                    warning = true;
                } else {
                    String keys = check != null ? "Enter twice saves, Esc cancels" : "Enter saves, Esc cancels";
                    description = takes == null ? keys : takes + ". " + keys;
                }
            }
            int h = rowHeight(description), boxW = Math.min(boxWidth, (int) (w * .45)), bx = x + w - boxW, by = y + h / 2 - 10;
            boolean over = inside(x - 10, y, w + 20, h);
            rowLabel(label, description, MUTED, over, h, boxW);
            round(graphics, bx, by, boxW, 20, 6, FIELD);
            outline(graphics, bx, by, boxW, 20, 6, focused ? accent() : over ? EDGE_HOVER : EDGE);
            String value = focused ? textBuffer : getter.get();
            // Secrets show only their last four characters, also while they are edited; a short one, such as a PIN, none.
            if (secret) value = value.length() <= 8 ? "*".repeat(value.length())
                    : "*".repeat(Math.min(12, value.length() - 4)) + value.substring(value.length() - 4);
            if (focused) {
                // The end stays in view while typing; selected text is highlighted, and a cursor blinks after it otherwise.
                String shown = tail(value, Size.BASE, boxW - 20);
                if (textSelected && !shown.isEmpty()) round(graphics, bx + 6, by + 4, width(shown, Size.BASE) + 4, 12, 2, alpha(accent(), .32));
                else if (System.currentTimeMillis() % 1000 < 500) round(graphics, bx + 9 + width(shown, Size.BASE), by + 5, 1, 10, 0, TEXT);
                draw(shown, Size.BASE, bx + 8, by + 7, TEXT);
            } else {
                draw(fit(value, Size.BASE, boxW - 16), Size.BASE, bx + 8, by + 7, LABEL);
            }
            hit(id, x - 10, y, w + 20, h, () -> {
                if (id.equals(textField)) {
                    textSelected = false;
                    return;
                }
                textField = id;
                textBuffer = textOriginal = getter.get();
                textSelected = true;
                textSetter = setter;
                textLimit = Math.max(limit, textBuffer.length());
                textSecret = secret;
                textConfirm = check;
                textArmed = null;
                textNotice = null;
                keyboardFocus = null;
            }, null);
            endRow(h);
        }

        void info(String label, String value, int color) {
            flushChips();
            draw(label, Size.BASE, x, y + 4, MUTED);
            int labelWidth = width(label, Size.BASE) + 10;
            String shown = fit(value, Size.BASE, w - labelWidth);
            draw(shown, Size.BASE, x + w - width(shown, Size.BASE), y + 4, color);
            y += 17;
        }

        void note(String value, int color) {
            flushChips();
            y += 2;
            for (String line : wrap(value, w, Size.SMALL)) {
                draw(line, Size.SMALL, x, y + 2, color);
                y += 12;
            }
            y += 4;
        }

        void bind(int index) {
            bindRow("bind", index, "Keybind", "Turns " + scope + " on or off");
        }

        /** A key binding row; click it, then press a key or mouse button. */
        void bindRow(String key, int index, String label, String description) {
            flushChips();
            KeyMapping mapping = keyMapping(index);
            if (mapping == null) return;
            String id = id(key);
            boolean capturing = binding == index;
            int h = 32;
            boolean over = inside(x - 10, y, w + 20, h);
            String conflict = conflicts(index);
            String name = capturing ? "Press a key..." : mapping.isUnbound() ? "None" : keyName(mapping);
            int boxW = Math.max(72, width(name, Size.BASE) + 24), bx = x + w - boxW, by = y + h / 2 - 10;
            rowLabel(label, conflict.isEmpty() ? description : "Also used by " + conflict, conflict.isEmpty() ? MUTED : WARN,
                    over, h, boxW);
            double hover = animate("bind." + id, over || capturing ? 1 : 0);
            round(graphics, bx, by, boxW, 20, 6, capturing ? blend(FIELD, accent(), .15) : FIELD);
            outline(graphics, bx, by, boxW, 20, 6, capturing ? accent() : blend(EDGE, EDGE_HOVER, hover));
            draw(name, Size.BASE, bx + (boxW - width(name, Size.BASE)) / 2, by + 7,
                    capturing ? TEXT : !conflict.isEmpty() ? WARN : mapping.isUnbound() ? MUTED : TEXT, !capturing, false);
            // A right click puts the key back to its default.
            hit(id, x - 10, y, w + 20, h, () -> {
                binding = index;
                dragging = null;
                searchFocused = false;
                keyboardFocus = null;
            }, () -> setKey(index, mapping.getDefaultKey()));
            endRow(h);
        }

        void button(String key, String label, String description, Runnable action) { button(key, label, description, label, action); }

        /** A row with a button on the right; the button reads {@code text}. */
        void button(String key, String label, String description, String text, Runnable action) {
            flushChips();
            String id = id(key);
            int h = rowHeight(description), bw = width(text, Size.BASE) + 28, bx = x + w - bw, by = y + h / 2 - 10;
            boolean over = inside(x - 10, y, w + 20, h), warned = warning;
            rowLabel(label, description, MUTED, over, h, bw);
            // A button whose row is a warning, such as Approve or a Reset waiting for its Confirm, is amber as well.
            round(graphics, bx, by, bw, 20, 6, warned ? blend(FIELD, WARN, over ? .22 : .12) : over ? CARD_HOVER : FIELD);
            outline(graphics, bx, by, bw, 20, 6, warned ? alpha(WARN, over ? .9 : .6) : over ? EDGE_HOVER : EDGE);
            draw(text, Size.BASE, bx + 14, by + 7, warned ? WARN : over ? TEXT : LABEL);
            hit(id, x - 10, y, w + 20, h, () -> change(action), null);
            endRow(h);
        }

        /** Settings most people leave alone, folded away until asked for. */
        void more(Consumer<Column> body) {
            flushChips();
            String id = id("more");
            boolean open = MORE.contains(id);
            y += 6;
            boolean over = inside(x - 10, y, w + 20, 20);
            String label = open ? "Fewer options" : "More options";
            int labelWidth = width(label, Size.BASE);
            draw(label, Size.BASE, x, y + 6, over ? TEXT : MUTED, over, false);
            chevron(x + labelWidth + 9, y + 10, open, over ? TEXT : MUTED);
            int rule = x + labelWidth + 18;
            if (rule < x + w) round(graphics, rule, y + 10, x + w - rule, 1, 0, EDGE);
            hit(id, x - 10, y, w + 20, 20, () -> { if (!MORE.remove(id)) MORE.add(id); }, null);
            y += 22;
            if (open) {
                body.accept(this);
                flushChips();
            }
        }
    }

    private record NavGroup(String title, List<Page> pages) { }

    /** A sidebar entry: a category of modules, or a page that draws its own content. */
    private record Page(String id, String title, String icon, String subtitle, List<Module> modules, Consumer<Column> content,
                        String heading) {
        /** The same page under a different title in the header than in the sidebar. */
        Page heading(String text) { return new Page(id, title, icon, subtitle, modules, content, text); }

        String header() { return heading == null ? title : heading; }
    }

    /**
     * A module: its switch, its settings (or null), the index of its keybind (or -1), and the config object its settings
     * live in (or null), which Reset puts back to its defaults.
     */
    private record Module(String name, String description, String keywords, BooleanSupplier enabled, Runnable toggle,
                          BooleanSupplier available, String unavailable, Consumer<Column> settings, int key, Object values) {
        Module key(int index) {
            return new Module(name, description, keywords, enabled, toggle, available, unavailable, settings, index, values);
        }

        Module values(Object object) {
            return new Module(name, description, keywords, enabled, toggle, available, unavailable, settings, key, object);
        }
    }

    /** A part of the window that takes the mouse; the sidebar and the page content also scroll. */
    private static final class Region {
        int x, y, w, h;
        double scroll, scrollTarget;
        int contentHeight;

        void set(int x, int y, int w, int h) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }

        boolean contains(double mx, double my) { return mx >= x && mx < x + w && my >= y && my < y + h; }
    }

    private record Place(Region region, int y, int h) { }

    /** Whether a guarded field's text can be saved, and what saving it does or why it cannot, in a few words. */
    private record Confirm(boolean ok, String text) { }

    /** A clickable area; only the region under the mouse receives clicks. */
    private record Hit(String id, Region region, int x, int y, int w, int h, Runnable action, Runnable secondary,
                       DoubleConsumer drag, IntConsumer nudge) {
        boolean contains(double mx, double my) { return mx >= x && my >= y && mx < x + w && my < y + h; }
        void move(double mx, double my) { drag.accept((mx - x) / w); }
    }

    private final class ColorEditor {
        final String title;
        final IntSupplier getter;
        final IntConsumer setter;
        String hex;
        boolean hexFocused;
        boolean replaceHex;
        ColorEditor(String title, IntSupplier getter, IntConsumer setter) {
            this.title = title;
            this.getter = getter;
            this.setter = setter;
            this.hex = DonutScreen.this.hex(getter.getAsInt()).substring(1);
        }
        void set(int color) {
            setter.accept(color & 0xFFFFFF);
            hex = DonutScreen.this.hex(color).substring(1);
            dirty = true;
        }
    }
}
