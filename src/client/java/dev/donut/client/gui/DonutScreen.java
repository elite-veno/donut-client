package dev.donut.client.gui;

import com.mojang.blaze3d.platform.InputConstants;
import dev.donut.client.config.ClientConfig;
import dev.donut.client.config.FinderSettings;
import dev.donut.client.config.RenderMode;
import dev.donut.client.finder.ActivityArea;
import dev.donut.client.finder.ActivityStats;
import dev.donut.client.finder.DebrisStats;
import dev.donut.client.finder.FinderScanner;
import dev.donut.client.input.ModuleKeybinds;
import dev.donut.client.sniper.Ledger;
import dev.donut.client.sniper.LiveTrader;
import dev.donut.client.sniper.Money;
import dev.donut.client.sniper.PriceStats;
import dev.donut.client.sniper.SniperEngine;
import dev.doughbay.fabric.GoNutsApi;
import dev.doughbay.fabric.Payroll;
import dev.doughbay.fabric.Tuning;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

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
    private static final int TEXT = 0xFFF0F6F5, LABEL = 0xFFCAD7D5, MUTED = 0xFF8EA3A1, DIM = 0xFF5E7472;
    private static final int WINDOW = 0xF6071112, SIDEBAR = 0xFF0A1718, CARD = 0xFF0C1A1B, CARD_HOVER = 0xFF102324;
    private static final int EDGE = 0xFF1B3031, EDGE_HOVER = 0xFF2A4646, FIELD = 0xFF061011, TRACK = 0xFF1D2D2E;
    private static final int WARN = 0xFFF2B35E, DANGER = 0xFFFF6F6F, GOOD = 0xFF6FE3A6;
    private static final int[] PALETTE = {MINT, 0x3FC1D6, 0x68B4FF, 0x8C9BFF, 0xBA8CFF, 0xEC80B5,
            0xFF707B, 0xFF975F, 0xF2B35E, 0xC5E774, 0x7EDD8E, 0xF0F6F5};
    /** The page and the module whose settings are open; kept while the game runs, so the menu reopens as it was left. */
    private static String pageId = "finders", detailName;
    /** Settings showing their "More options", kept the same way. */
    private static final Set<String> MORE = new HashSet<>();

    private final ClientConfig config;
    private final Runnable save;
    private final Supplier<ActivityStats> activityStats;
    private final Supplier<String> rtpStatus;
    private final SniperEngine sniper;
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
    /** The focused text field, if any; its edits are saved when focus leaves it. */
    private String textField;
    /** The focused field's text; it is only applied, and checked, when focus leaves the field. */
    private String textBuffer = "";
    /** The focused field's character limit; never below what it already held. */
    private int textLimit = 128;
    private Consumer<String> textSetter;
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
        this(config, save, activityStats, rtpStatus, null);
    }

    public DonutScreen(ClientConfig config, Runnable save, Supplier<ActivityStats> activityStats, Supplier<String> rtpStatus,
                       SniperEngine sniper) {
        super(Component.literal("Donut Client"));
        this.config = config;
        this.save = save;
        this.activityStats = activityStats;
        this.rtpStatus = rtpStatus;
        this.sniper = sniper;
        version = FabricLoader.getInstance().getModContainer("donut")
                .map(mod -> "VERSION " + mod.getMetadata().getVersion().getFriendlyString().toUpperCase(Locale.ROOT)).orElse("");
        group("Modules",
                page("finders", "Finders", "search", List.of(storage(), spawners(), debris(), tunnels(), entities(), activity())),
                page("render", "Render", "eye", List.of(xray(), fullbright(), freecam())),
                page("utility", "Utility", "rtp", List.of(autoRtp())),
                page("hud", "HUD", "monitor", List.of(
                        module("ArrayList", "Active module names at the top right", "hud array list modules",
                                () -> config.showHud, () -> change(() -> config.showHud = !config.showHud), null),
                        module("FinderInfo", "Counts, distance and direction of the nearest finds", "hud finder info counts distance",
                                () -> config.hud.info, () -> change(() -> config.hud.info = !config.hud.info), null),
                        module("Labels", "Names and distances on the nearest highlights", "hud labels distance names",
                                () -> config.hud.labels, () -> change(() -> config.hud.labels = !config.hud.labels), c ->
                                        c.slider("count", "Labels shown", "How many of the nearest highlights get a label",
                                                () -> config.hud.maxLabels, v -> config.hud.maxLabels = (int) Math.round(v), 1, 32, 1, "")))));
        if (sniper != null) {
            group("Trading",
                    page("sniper", "Sniper", "chart", List.of(ahSniper())),
                    page("dashboard", "Dashboard", "grid", "Profit, open trades and the sniper's activity", this::drawDashboard),
                    page("markets", "Markets", "list", "The busiest items in the saved sale history", this::drawMarkets));
        }
        // GoNuts ships inside Donut Client; these pages drive it through GoNutsApi, its one stable entry point.
        group("GoNuts",
                page("gonuts", "Overview", "donut", "Trading session, safety and updates", this::drawGoNuts).heading("GoNuts"),
                new Page("gonuts-settings", "Settings", "sliders", "Every GoNuts setting, by group; changes save at once",
                        goNutsGroups(), null, "GoNuts settings"));
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
            c.color("color", "Base color", () -> s.color, v -> s.color = v);
            c.toggle("perType", "Colors by type", "Give each storage family its own color", () -> s.perTypeColors, () -> s.perTypeColors = !s.perTypeColors);
            if (s.perTypeColors) {
                c.color("chestColor", "Chests & barrels", () -> s.chestColor, v -> s.chestColor = v);
                c.color("shulkerColor", "Shulkers", () -> s.shulkerColor, v -> s.shulkerColor = v);
                c.color("dropperColor", "Droppers & hoppers", () -> s.dropperColor, v -> s.dropperColor = v);
                c.color("furnaceColor", "Furnaces", () -> s.furnaceColor, v -> s.furnaceColor = v);
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
            c.chip("minecarts", "Minecarts", "Chest and hopper minecarts, as in mineshafts", () -> s.minecarts, () -> s.minecarts = !s.minecarts);
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
        return finder(3, "DebrisFinder", "Ancient debris, with anti-xray detection", "debris ancient netherite nether ores seed", s,
                c -> {
            c.color("color", "Debris", () -> s.color, v -> s.color = v);
            c.color("secondaryColor", "Seed estimates", () -> s.secondaryColor, v -> s.secondaryColor = v);
            c.color("tertiaryColor", "Search areas", () -> s.tertiaryColor, v -> s.tertiaryColor = v);
        }, c -> {
            c.section("Detection");
            c.toggle("filterFakes", "Filter fakes", "Hide fake debris that anti-xray scatters through netherrack",
                    () -> s.filterFakes, () -> s.filterFakes = !s.filterFakes);
            c.toggle("notify", "Reveal alert", "Sound and message when mining uncovers debris", () -> s.notify, () -> s.notify = !s.notify);
            c.toggle("searchAreas", "Search areas", "Outline 16-block sections the server's block data says still hold hidden debris",
                    () -> s.searchAreas, () -> s.searchAreas = !s.searchAreas);
            c.toggle("seedEstimates", "Seed estimates", "Mark the netherrack where the world seed placed debris the server hides",
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
        if (stats.fakedChunks() > 0) c.info("Fakes hidden", stats.hiddenFakes() + " in " + stats.fakedChunks() + " ch.", TEXT);
        if (stats.searchAreas() > 0) c.info("Search areas", Integer.toString(stats.searchAreas()), TEXT);
        if (stats.seeded()) {
            c.info("Seed estimates", Integer.toString(stats.estimates()), TEXT);
            if (stats.bedrockReady()) c.info("Bedrock check", Math.round(100.0 * stats.bedrockMatched() / stats.bedrockChecked()) + "% match",
                    stats.bedrockMismatch() ? WARN : GOOD);
            if (stats.seedChecked() > 0) c.info("Seed check", stats.seedMatched() + " of " + stats.seedChecked() + " real debris",
                    stats.debrisMismatch() ? WARN : GOOD);
            int verified = stats.seedChunks() + stats.offSeedChunks();
            if (verified > 0) c.info("Seed applies", stats.seedChunks() + " of " + verified + " chunks", stats.seedChunks() > 0 ? GOOD : WARN);
            if (stats.dugOut() > 0) c.info("Already dug out", Integer.toString(stats.dugOut()), TEXT);
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
        } else if (stats.seeded() && stats.seedChunks() > 0 && stats.estimates() == 0 && stats.dugOut() > 0) {
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
            c.slider("minLength", "Min length", "Caves are rarely this narrow and straight for long; raise it if natural caves show up",
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
            c.chip("players", "Players", "Everyone except you, labeled with their name", () -> s.players, () -> s.players = !s.players);
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
            c.slider("minActivity", "Min growth", "Worldgen buds score near 0%; 50% is roughly an hour of nearby activity",
                    () -> s.minActivity, v -> s.minActivity = v, 0, 1, .01, "%");
            c.toggle("surface", "Surface marker", "Squares on the surface with a guide down to the geode",
                    () -> s.surfaceMarker, () -> s.surfaceMarker = !s.surfaceMarker);
            c.toggle("allLoaded", "All loaded chunks", "Every chunk your client has, at any distance and depth",
                    () -> s.allLoadedChunks, () -> s.allLoadedChunks = !s.allLoadedChunks);
            activityScan(c, s);
        });
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
            c.note("Nothing reaches Min growth; lower it to see weaker chunks.", MUTED);
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
            // ActivityFinder can cover every loaded chunk, which makes range and distance fade irrelevant.
            boolean everyChunk = s == config.activity && s.allLoadedChunks;
            c.section("Render");
            c.choice("mode", "Mode", RenderMode.values().length, () -> s.mode.ordinal(),
                    v -> s.mode = RenderMode.values()[v], v -> modeName(RenderMode.values()[v]));
            c.toggle("walls", "Through walls", "Keep highlights visible behind blocks", () -> s.throughWalls, () -> s.throughWalls = !s.throughWalls);
            c.toggle("tracers", "Tracers", "Lines from your crosshair to every highlight", () -> s.tracers, () -> s.tracers = !s.tracers);
            if (!everyChunk) c.slider("range", "Range", "Detection range; only loaded chunks can be highlighted",
                    () -> s.range, v -> s.range = v, 16, 256, 1, " blocks");
            detection.accept(c);
            c.more(m -> {
                m.section("Appearance");
                m.slider("outlineOpacity", "Outline opacity", "How opaque the outlines are",
                        () -> s.outlineOpacity, v -> s.outlineOpacity = v, 0, 1, .01, "%");
                m.slider("fillOpacity", "Fill opacity", "How opaque the filled faces are", () -> s.fillOpacity, v -> s.fillOpacity = v, 0, 1, .01, "%");
                m.slider("lineWidth", "Line width", "Outline width", () -> s.lineWidth, v -> s.lineWidth = v, .5, 5, .1, " px");
                if (!everyChunk) m.toggle("fade", "Distance fade", "Soften highlights near the range limit",
                        () -> s.distanceFade, () -> s.distanceFade = !s.distanceFade);
                m.section("Colors");
                colors.accept(m);
            });
        }).key(index);
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
            c.more(m -> m.field("extra", "Also hide", "Block ids, separated by commas", () -> s.extraHidden, v -> s.extraHidden = v, false));
            c.note("Uses Minecraft's own renderer; Sodium replaces it and is not supported.", DIM);
        }).key(6);
    }

    private Module fullbright() {
        var s = config.fullbright;
        return module("Fullbright", "Full brightness in caves and at night; your brightness setting is left alone",
                "fullbright brightness gamma night vision fog dark caves", () -> s.enabled, () -> change(() -> s.enabled = !s.enabled), c -> {
            c.bind(7);
            c.toggle("noFog", "Remove fog", "Distance, water, lava, blindness and darkness fog", () -> s.noFog, () -> s.noFog = !s.noFog);
        }).key(7);
    }

    private Module freecam() {
        var s = config.freecam;
        return worldModule("Freecam", "Explore with a detached camera; close this menu to fly", "freecam free camera fly perspective spectator",
                () -> s.enabled, () -> s.enabled = !s.enabled, c -> {
            c.bind(2);
            c.section("Movement");
            c.slider("speed", "Speed", "Camera speed", () -> s.speed, v -> s.speed = v, .1, 5, .1, " blocks/tick");
            c.slider("boost", "Sprint boost", "Speed multiplier while sprinting", () -> s.boostMultiplier, v -> s.boostMultiplier = v, 1, 10, .1, "x");
            c.section("Controls");
            c.info("Move", keyName(minecraft.options.keyUp) + keyName(minecraft.options.keyLeft)
                    + keyName(minecraft.options.keyDown) + keyName(minecraft.options.keyRight), LABEL);
            c.info("Up / down", keyName(minecraft.options.keyJump) + " / " + keyName(minecraft.options.keyShift), LABEL);
            c.info("Boost", keyName(minecraft.options.keySprint), LABEL);
        }).key(2);
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
            c.slider("stopAt", "Stop at growth", "Stops once a chunk reaches this growth; ActivityFinder's squares keep their own threshold",
                    () -> s.stopAtGrowth, v -> s.stopAtGrowth = v, 0, 1, .01, "%");
            c.toggle("hurt", "Stop when hurt", "Stop as soon as you lose health", () -> s.stopWhenHurt, () -> s.stopWhenHurt = !s.stopWhenHurt);
            c.more(m -> {
                m.section("Menu");
                m.field("menu", "Menu item", "Clicked when the command opens a menu", () -> s.menuItem, v -> s.menuItem = v, false);
                m.section("Timing");
                m.slider("interval", "Interval", "Time between attempts; waits longer whenever the server reports a cooldown",
                        () -> s.intervalSeconds, v -> s.intervalSeconds = v, 5, 600, 5, " s");
                m.slider("scan", "Scan time", "Time ActivityFinder scans after each teleport", () -> s.scanSeconds, v -> s.scanSeconds = v, 2, 30, 1, " s");
                m.slider("attempts", "Max attempts", "Stops after this many teleports", () -> s.maxAttempts,
                        v -> s.maxAttempts = (int) Math.round(v), 1, 200, 1, "");
            });
            c.note("Check the server's rules before automating commands.", DIM);
        }).key(5);
    }

    private Module ahSniper() {
        var s = config.sniper;
        return new Module("AHSniper", "Watches the Auction House for listings far under their usual price",
                "sniper ah auction house trading deals flip", () -> s.enabled, () -> {
            if (s.enabled || canUseWorld()) s.enabled = !s.enabled;
        }, () -> s.enabled || canUseWorld(), "Join a server to start the sniper", c -> {
            c.note(sniper.status(), s.enabled ? TEXT : MUTED);
            c.info("Mode", s.live ? "Live" : "Paper", s.live ? DANGER : LABEL);
            c.info("Checked", sniper.listingsChecked() + " listings", LABEL);
            c.info("Deals", Integer.toString(sniper.deals()), LABEL);
            if (sniper.traderPhase() != LiveTrader.Phase.IDLE) {
                c.info("Trader", sniper.traderPhase().name().toLowerCase(Locale.ROOT).replace('_', ' '), TEXT);
            }
            c.section("Account");
            c.field("key", "API key", "Run /api in game to get one", () -> s.apiKey, v -> s.apiKey = v, true);
            c.toggle("live", "Live trading", "Real purchases with your money; resets every session", () -> s.live, () -> s.live = !s.live);
            if (s.live) c.note("Live: buys with your money.", DANGER);
            c.section("Limits");
            c.money("max", "Max price", "Never pay more per trade", () -> s.maxPrice, v -> s.maxPrice = v);
            c.money("exposure", "Max exposure", "Total in items not yet sold back", () -> s.maxExposure, v -> s.maxExposure = v);
            c.more(m -> {
                m.section("Deal rules");
                m.slider("deviation", "Min deviation", "Robust standard deviations under the item's value", () -> s.minDeviation,
                        v -> s.minDeviation = v, 1, 6, .1, "σ");
                m.slider("margin", "Min margin", "Margin after tax when resold", () -> s.minMargin, v -> s.minMargin = v, .02, 1, .01, "%");
                m.money("profit", "Min profit", "Per trade, after tax; type 250k, 1.5M or 12000", () -> s.minProfit, v -> s.minProfit = v);
                m.slider("tax", "AH tax", "The Auction House's cut of each sale", () -> s.taxPercent, v -> s.taxPercent = v, 0, 20, .5, " %");
                m.slider("undercut", "Undercut", "Relist this far under the cheapest competitor", () -> s.undercutPercent,
                        v -> s.undercutPercent = v, 0, 10, .5, " %");
                m.slider("sales", "Min sales", "Sales on record before an item is traded", () -> s.minSales,
                        v -> s.minSales = (int) Math.round(v), 3, 50, 1, "");
                m.slider("perDay", "Min sales / day", "How often the item must sell", () -> s.minSalesPerDay, v -> s.minSalesPerDay = v, 0, 50, .5, "");
                m.section("Pace");
                m.slider("rate", "Trades / hour", "Maximum live trades per hour", () -> s.maxTradesPerHour,
                        v -> s.maxTradesPerHour = (int) Math.round(v), 1, 100, 1, "");
                m.slider("poll", "Poll every", "Check new listings this often", () -> s.pollSeconds, v -> s.pollSeconds = v, 2, 30, 1, " s");
                m.slider("history", "History", "Sale history to keep", () -> s.historyDays, v -> s.historyDays = v, 1, 30, 1, " days");
                m.section("Commands");
                m.field("search", "Search command", "{item} becomes the item name", () -> s.searchCommand, v -> s.searchCommand = v, false);
                m.field("sell", "Sell command", "{price} becomes the stack price", () -> s.sellCommand, v -> s.sellCommand = v, false);
                m.field("confirm", "Confirm button", "Text on the confirm item", () -> s.confirmText, v -> s.confirmText = v, false);
            });
            c.note("Try paper mode first. Check the server's rules before automating trades.", DIM);
        }, -1);
    }

    private Module module(String name, String description, String keywords, BooleanSupplier enabled, Runnable toggle,
                          Consumer<Column> settings) {
        return new Module(name, description, keywords, enabled, toggle, () -> true, "", settings, -1);
    }

    /** Freecam and Auto RTP act in a world, so they can only start inside one. */
    private Module worldModule(String name, String description, String keywords, BooleanSupplier enabled, Runnable flip,
                               Consumer<Column> settings) {
        return new Module(name, description, keywords, enabled, () -> {
            if (enabled.getAsBoolean() || canUseWorld()) flip.run();
        }, () -> enabled.getAsBoolean() || canUseWorld(), "Join a world to use " + name, settings, -1);
    }

    private boolean canUseWorld() { return minecraft.level != null && minecraft.player != null; }

    private String modeName(RenderMode mode) {
        return switch (mode) { case OUTLINE -> "Outline"; case FILL -> "Filled"; case BOTH -> "Both"; };
    }

    // Pages -------------------------------------------------------------------------------------

    private void drawDashboard(Column c) {
        Ledger ledger = sniper.ledger();
        c.label(sniper.status(), TEXT);
        String trader = sniper.traderPhase() == LiveTrader.Phase.IDLE ? ""
                : "  ·  trading: " + sniper.traderPhase().name().toLowerCase(Locale.ROOT).replace('_', ' ');
        c.note(sniper.listingsChecked() + " listings checked  ·  " + sniper.deals() + " deals" + trader, MUTED);
        c.y += 4;
        boolean wide = c.w >= 440;
        int columns = wide ? 4 : 2, gap = 8, w = (c.w - gap * (columns - 1)) / columns;
        String[][] stats = {
                {"Paper profit", Money.format(ledger.realized(false)), ledger.count(false, Ledger.State.SOLD) + " sold"},
                {"Paper open", Money.format(ledger.exposure(false)), ledger.count(false, Ledger.State.OPEN) + " trades"},
                {"Live profit", Money.format(ledger.realized(true)), ledger.count(true, Ledger.State.SOLD) + " sold"},
                {"Live listed", Money.format(ledger.exposure(true)), ledger.count(true, Ledger.State.LISTED) + " listed"}};
        for (int i = 0; i < stats.length; i++) {
            stat(c.x + (i % columns) * (w + gap), c.y + (i / columns) * 48, w, stats[i][0], stats[i][1], stats[i][2]);
        }
        c.y += (stats.length / columns) * 48;
        c.section("Trades");
        var trades = ledger.recent(6);
        if (trades.isEmpty()) c.note("No trades yet.", MUTED);
        for (Ledger.Position trade : trades) {
            String result = trade.state == Ledger.State.SOLD ? "+" + Money.format(trade.realized)
                    : trade.state == Ledger.State.UNSOLD ? "unsold" : trade.state == Ledger.State.LISTED ? "listed" : "open";
            String price = Money.format(trade.cost) + "  →  " + result;
            int priceWidth = width(price, Size.BASE);
            draw(fit((trade.live ? "" : "[paper] ") + trade.label + " x" + trade.count, Size.BASE, c.w - priceWidth - 10),
                    Size.BASE, c.x, c.y + 3, LABEL);
            draw(price, Size.BASE, c.x + c.w - priceWidth, c.y + 3, trade.state == Ledger.State.SOLD ? GOOD : MUTED);
            c.y += 16;
        }
        c.section("Activity");
        var lines = sniper.log();
        if (lines.isEmpty()) c.note("Start the sniper to watch the Auction House.", MUTED);
        for (String line : lines.subList(0, Math.min(lines.size(), 25))) {
            draw(fit(line, Size.SMALL, c.w), Size.SMALL, c.x, c.y + 2, MUTED);
            c.y += 12;
        }
    }

    private void stat(int x, int y, int w, String label, String value, String detail) {
        round(graphics, x, y, w, 40, 6, CARD_HOVER);
        outline(graphics, x, y, w, 40, 6, EDGE);
        draw(fit(label.toUpperCase(Locale.ROOT), Size.SMALL, w - 20), Size.SMALL, x + 10, y + 5, DIM);
        bold(fit(value, Size.BASE, w - 20), x + 10, y + 16, TEXT);
        draw(fit(detail, Size.SMALL, w - 20), Size.SMALL, x + 10, y + 28, MUTED);
    }

    private void drawMarkets(Column c) {
        c.note("Busiest items in the last " + Math.round(config.sniper.historyDays) + " days of saved sales.", MUTED);
        int count = c.x + c.w, perDay = count - 48, spread = perDay - 52, median = spread - 62;
        draw("ITEM", Size.SMALL, c.x, c.y + 2, DIM);
        String[] heads = {"MEDIAN", "SPREAD", "/ DAY", "SALES"};
        int[] rights = {median, spread, perDay, count};
        for (int i = 0; i < heads.length; i++) draw(heads[i], Size.SMALL, rights[i] - width(heads[i], Size.SMALL), c.y + 2, DIM);
        round(graphics, c.x, c.y + 13, c.w, 1, 0, EDGE);
        c.y += 18;
        var rows = sniper.busiest(60);
        if (rows.isEmpty()) c.note("No sales yet. Start the sniper with an API key.", MUTED);
        for (SniperEngine.MarketRow row : rows) {
            PriceStats stats = row.stats();
            String[] cells = {Money.format(stats.median()),
                    stats.sales() == 0 || stats.median() <= 0 ? "-" : Math.round(stats.sigma() / stats.median() * 100) + "%",
                    String.format(Locale.ROOT, "%.1f", stats.salesPerDay()), Integer.toString(stats.sales())};
            draw(fit(row.name(), Size.BASE, median - 70 - c.x), Size.BASE, c.x, c.y + 3, LABEL);
            for (int i = 0; i < cells.length; i++) {
                draw(cells[i], Size.BASE, rights[i] - width(cells[i], Size.BASE), c.y + 3, i == 0 ? TEXT : MUTED);
            }
            c.y += 16;
        }
    }

    private void drawKeybinds(Column c) {
        for (Page p : pages.values()) {
            if (p.modules == null) continue;
            for (Module m : p.modules) if (m.key >= 0) c.bindRow("keys." + m.name, m.key, m.name, m.description);
        }
        c.note("Click a key, then press the new one. Delete or Backspace clears it, Esc cancels.", MUTED);
    }

    private void drawTheme(Column c) {
        c.section("Colors");
        c.color("accent", "Accent color", "Switches, sliders and the selected page", () -> config.accentColor, v -> config.accentColor = v);
        c.section("Effects");
        c.toggle("snow", "Snowfall", "Faint snow behind the menu", () -> config.gui.snow, () -> config.gui.snow = !config.gui.snow);
    }

    // GoNuts ------------------------------------------------------------------------------------

    /** A number, optionally after an x, then an optional unit: "720", "12h", "250k", "x1.5", "3/h", "25 %". */
    private static final java.util.regex.Pattern NUMBER =
            java.util.regex.Pattern.compile("^\\s*x?\\s*(-?(?:\\d+(?:\\.\\d+)?|\\.\\d+))\\s*([a-z%/]*)\\s*$");
    /** GoNuts settings that pick one of a few modes, with the names their descriptions give the numbers. */
    private static final Map<String, String[]> GONUTS_OPTIONS = Map.of(
            "hud.corner", new String[]{"Top left", "Top right", "Bottom left", "Bottom right"},
            "feed.keyless", new String[]{"API only", "Keyless", "API, else keyless"},
            "evasion.escape", new String[]{"Nothing", "/rtp away", "Leave server"},
            "restart.hop_way", new String[]{"/leave, then /rtp", "Rejoin elsewhere"});
    private static final String WEBHOOK = "https://discord.com/api/webhooks/", OLD_WEBHOOK = "https://discordapp.com/api/webhooks/";
    private static final long ARM_MILLIS = 8_000, REPLY_MILLIS = 15_000;

    /** What the last click on a GoNuts page answered, and when; it fades after a while. */
    private String goNutsReply = "";
    private long goNutsReplyAt;
    private boolean goNutsReplyBad;
    /** Guarded settings armed by a first click, until when; a second click before then changes them. */
    private final Map<String, Long> armed = new HashMap<>();

    private void drawGoNuts(Column c) {
        var session = GoNutsApi.session();
        c.section("Session");
        if (session == null) {
            c.note("GoNuts is still starting.", MUTED);
        } else {
            String state = session.state().name();
            boolean stopped = state.equals("STOPPED"), paused = state.equals("PAUSED");
            c.info("State", words(state), stopped ? MUTED : paused ? WARN : GOOD);
            String detail = session.detail() == null ? "" : session.detail();
            // "Automation session is stopped" only says the state again.
            if (!detail.isBlank() && !(stopped && detail.toLowerCase(Locale.ROOT).contains("stopped"))) c.note(detail, MUTED);
            c.info("Trades started", Integer.toString(session.tradesStarted()), TEXT);
            c.info("Committed", Money.format(session.committedSpend()), TEXT);
            c.info("Open listings", Integer.toString(session.openListings()), TEXT);
            long balance = GoNutsApi.balance();
            c.info("Balance", balance < 0 ? "Not read yet" : Money.format(balance), balance < 0 ? MUTED : TEXT);
            boolean resumable = session.resumable() || session.recoveredSession();
            if (stopped) {
                c.button("session", "Trading", "Starts trading on this client, within the limits in Settings", "Start", this::goNutsStart);
            } else if (paused && resumable) {
                c.button("session", "Trading", "Goes on with the paused session", "Resume", this::goNutsStart);
                c.button("sessionStop", "Or stop", "Stops trading on this client", "Stop", this::goNutsStop);
            } else {
                if (paused) c.note("Paused. What it waits for, and how to go on, is on the full GoNuts screen.", WARN);
                c.button("session", "Trading", "Stops trading on this client", "Stop", this::goNutsStop);
                // Steps that need no screen open wait for this menu to close, unlike for GoNuts's own screen.
                if (!paused) c.note("Trading steps wait while this menu is open; close it to let GoNuts work.", MUTED);
            }
        }
        replyNote(c);

        c.section("Safety");
        // No payroll payment goes out until the rule is approved, so the request comes first and stands out.
        var pending = GoNutsApi.pendingPayment();
        if (pending != null) {
            c.note("A payroll rule waits for your approval before its first payment: " + Money.format(pending.amount()) + " to "
                    + pending.name() + ", from " + Money.format(pending.profit()) + " profit. Approving lets the rule pay on its own "
                    + "from now on, so check it on the full GoNuts screen if unsure.", WARN);
            c.warnNext();
            c.button("confirmPay", "Payroll rule for " + pending.name(), "Nothing is paid until you approve", "Approve",
                    () -> goNutsApprove(pending));
        }
        long paid = GoNutsApi.paidToday(), cap = Math.round(GoNutsApi.get("pay.daily_cap"));
        // A cap of 0 means no limit at all.
        c.info("Paid out, last 24 h", Money.format(paid) + (cap > 0 ? " of " + Money.format(cap) : "  ·  no limit"),
                cap <= 0 || paid >= cap ? WARN : TEXT);
        List<String> risky = GoNutsApi.riskyFeaturesOn();
        if (risky.isEmpty()) c.note("Nothing that spends money or acts for this account is on.", MUTED);
        else c.note("On, and able to spend money or act for this account: " + String.join(", ", risky) + ".", WARN);

        c.section("GoNuts");
        var update = GoNutsApi.updateStatus();
        String updateState = update.state().name();
        String updates = switch (updateState) {
            case "OFF" -> "Not checked (turned off)";
            case "UNCHECKED" -> "Not checked yet";
            case "CURRENT" -> "Up to date";
            case "NEWER" -> "A newer GoNuts is out";
            case "READY" -> "Update ready; it installs when the game closes";
            case "AHEAD" -> "This build is newer than the release";
            case "FAILED" -> "Could not check";
            default -> words(updateState);
        };
        c.info("Updates", updates, updateState.equals("FAILED") ? WARN
                : updateState.equals("NEWER") || updateState.equals("READY") ? accent() : LABEL);
        if (updateState.equals("FAILED") && update.detail() != null && !update.detail().isBlank()) c.note(update.detail(), MUTED);
        c.button("full", "Full GoNuts", "Markets, charts, payroll rules, presets and the web panel", "Open", () -> {
            if (GoNutsApi.session() == null) reply("GoNuts has not started yet.", true);
            else GoNutsApi.openFullScreen();
        });
    }

    /** Starts or resumes, only if the session is still stopped or resumable when the click lands. */
    private void goNutsStart() {
        var now = GoNutsApi.session();
        if (now == null) { reply("GoNuts has not started yet.", true); return; }
        String state = now.state().name();
        boolean startable = state.equals("STOPPED") || state.equals("PAUSED") && (now.resumable() || now.recoveredSession());
        if (!startable) { reply("The session changed before the click; look again.", true); return; }
        String answer = GoNutsApi.start();
        reply(answer, refused(answer));
    }

    private void goNutsStop() {
        String answer = GoNutsApi.stop();
        reply(answer, refused(answer));
    }

    /** Approves the payroll rule only if the request on screen is still the one waiting. */
    private void goNutsApprove(Payroll.PendingConfirmation shown) {
        var now = GoNutsApi.pendingPayment();
        if (now == null || now.ruleId() != shown.ruleId() || !now.name().equals(shown.name()) || now.amount() != shown.amount()) {
            reply("The payment waiting for approval changed; look again before approving.", true);
            return;
        }
        GoNutsApi.confirmPayment(shown.ruleId());
        reply("Approved the payroll rule for " + shown.name() + ".", false);
    }

    private static boolean refused(String answer) {
        return answer.toLowerCase(Locale.ROOT).matches(".*(refus|cannot|can't|not started|not running|fail|error|blocked).*");
    }

    private void reply(String text, boolean bad) {
        goNutsReply = text == null ? "" : text;
        goNutsReplyBad = bad;
        goNutsReplyAt = System.currentTimeMillis();
    }

    private void replyNote(Column c) {
        if (!goNutsReply.isBlank() && System.currentTimeMillis() - goNutsReplyAt < REPLY_MILLIS) c.note(goNutsReply, goNutsReplyBad ? WARN : LABEL);
    }

    /** Runs a change to a guarded setting on a second click within eight seconds; the first click only arms it. */
    private void guard(String id, String label, Runnable change) {
        long now = System.currentTimeMillis();
        Long until = armed.remove(id);
        if (until != null && now < until) {
            change.run();
            reply(label + " changed.", false);
        } else {
            armed.put(id, now + ARM_MILLIS);
            reply("Click " + label + " again within 8 seconds to change it; it moves money, data or the server joined.", true);
        }
    }

    private boolean isArmed(String id) {
        Long until = armed.get(id);
        return until != null && System.currentTimeMillis() < until;
    }

    /** One card per GoNuts settings group, plus its free-text lists; each opens like a module's settings. */
    private List<Module> goNutsGroups() {
        List<Module> cards = new ArrayList<>();
        for (String group : GoNutsApi.groups()) {
            List<Tuning.Setting> settings = GoNutsApi.settings().stream().filter(s -> s.group().equals(group)).toList();
            StringBuilder keywords = new StringBuilder("gonuts");
            for (Tuning.Setting s : settings) keywords.append(' ').append(s.label());
            // The count comes first, so a cut-off list of names never hides it.
            String description = settings.size() + (settings.size() == 1 ? " setting" : " settings") + "  ·  "
                    + String.join(", ", settings.stream().map(Tuning.Setting::label).toList());
            cards.add(new Module(group, description, keywords.toString(), () -> false, null, () -> true, "",
                    c -> goNutsSettings(c, settings), -1));
        }
        StringBuilder keywords = new StringBuilder("gonuts lists");
        for (Tuning.ListSetting l : GoNutsApi.lists()) keywords.append(' ').append(l.label());
        cards.add(new Module("Lists", GoNutsApi.lists().size() + " lists  ·  item lists, prices, addresses and the status webhook",
                keywords.toString(), () -> false, null, () -> true, "", c -> {
                    for (Tuning.ListSetting l : GoNutsApi.lists()) goNutsList(c, l);
                    replyNote(c);
                    c.note("Lists save when you press Enter or click elsewhere.", DIM);
                }, -1));
        return cards;
    }

    private void goNutsList(Column c, Tuning.ListSetting l) {
        String key = l.key();
        boolean webhook = key.equals("webhook.url");
        if (GoNutsApi.guarded(key)) c.warnNext();
        // GoNuts's own editor takes up to 2000 characters, and 240 for the webhook; a shorter limit would drop entries.
        c.field(key, l.label(), clean(l.description()), () -> GoNutsApi.text(key), text -> {
            String value = text.strip();
            if (webhook && !value.isEmpty() && !value.startsWith(WEBHOOK) && !value.startsWith(OLD_WEBHOOK)) {
                reply("That is not a Discord webhook URL (channel settings, Integrations, New Webhook, Copy); nothing was saved.", true);
                return;
            }
            GoNutsApi.setText(key, value);
        }, webhook, webhook ? 240 : 2000);
    }

    /** A group's first settings, and every guarded one, up front; the rest behind "More options". */
    private void goNutsSettings(Column c, List<Tuning.Setting> settings) {
        List<Tuning.Setting> front = new ArrayList<>(), folded = new ArrayList<>();
        for (int i = 0; i < settings.size(); i++) {
            Tuning.Setting s = settings.get(i);
            (i < 6 || GoNutsApi.guarded(s.key()) ? front : folded).add(s);
        }
        for (Tuning.Setting s : front) goNutsSetting(c, s);
        if (!folded.isEmpty()) c.more(m -> folded.forEach(s -> goNutsSetting(m, s)));
        replyNote(c);
        c.note("Changes save at once. Resets and presets are on the full GoNuts screen.", DIM);
    }

    private void goNutsSetting(Column c, Tuning.Setting s) {
        String key = s.key(), id = c.scope.isEmpty() ? key : c.scope + "." + key;
        boolean guarded = GoNutsApi.guarded(key);
        String description = clean(s.description());
        if (guarded) {
            description = isArmed(id) ? "Click again within 8 seconds to change this" : "Guarded: " + description;
            c.warnNext();
        }
        String[] options = GONUTS_OPTIONS.get(key);
        if (options != null && options.length == (int) Math.round(s.max() - s.min()) + 1) {
            int base = (int) Math.round(s.min());
            c.choice(key, s.label(), options.length, () -> Math.clamp((int) Math.round(GoNutsApi.get(key)) - base, 0, options.length - 1), v -> {
                Runnable change = () -> GoNutsApi.set(key, v + base);
                if (guarded) guard(id + "." + v, s.label(), change);
                else change.run();
            }, v -> options[v]);
            return;
        }
        if (s.kind() == Tuning.Kind.TOGGLE) {
            Runnable flip = () -> GoNutsApi.set(key, GoNutsApi.get(key) >= .5 ? 0 : 1);
            c.toggle(key, s.label(), description, () -> GoNutsApi.get(key) >= .5, guarded ? () -> guard(id, s.label(), flip) : flip);
            return;
        }
        double range = s.max() - s.min(), step = stepFor(s);
        // Guarded numbers are typed, as on the GoNuts screen; so are ranges a slider could not land on precisely.
        if (guarded || !Double.isFinite(range) || range / step > 200) {
            c.field(key, s.label(), description, () -> numberText(s, GoNutsApi.get(key)), text -> {
                double value = parseSetting(s, text);
                if (Double.isFinite(value)) {
                    double set = GoNutsApi.set(key, value);
                    if (Math.abs(set - value) > 1e-9) reply(s.label() + " is held to " + s.format(s.min()) + " to " + s.format(s.max())
                            + "; it was set to " + s.format(set) + ".", true);
                } else if (!text.strip().equals(numberText(s, GoNutsApi.get(key)))) {
                    reply("\"" + text.strip() + "\" is not a value for " + s.label() + "; it was left at " + s.format(GoNutsApi.get(key)) + ".", true);
                }
            }, false);
            return;
        }
        // Writing a GoNuts setting rewrites its settings files, so a dragged slider sets its value once, on release.
        c.slider(key, s.label(), description, () -> GoNutsApi.get(key), v -> GoNutsApi.set(key, v), s.min(), s.max(), step, s::format, true);
    }

    /** The finest step a setting's default and minimum need, and at least tenths for small non-count ranges. */
    private static double stepFor(Tuning.Setting s) {
        double range = s.max() - s.min();
        int decimals = Math.max(decimals(s.defaultValue()), decimals(s.min()));
        if (s.kind() != Tuning.Kind.COUNT) {
            if (range <= 1) decimals = Math.max(decimals, 2);
            else if (range <= 10) decimals = Math.max(decimals, 1);
        }
        return decimals == 0 ? 1 : Math.pow(10, -decimals);
    }

    private static int decimals(double value) {
        for (int d = 0; d < 3; d++) {
            double scaled = value * Math.pow(10, d);
            if (Math.abs(scaled - Math.round(scaled)) < 1e-9) return d;
        }
        return 3;
    }

    /** A typed setting's text: large counts, such as money, with thousands separators. */
    private static String numberText(Tuning.Setting s, double value) {
        return s.kind() == Tuning.Kind.COUNT && Math.abs(value) >= 10_000 ? String.format(Locale.ROOT, "%,.0f", value) : s.format(value);
    }

    /**
     * A typed value in the setting's own unit: minutes take s, min, h or d; seconds take s, min or h; counts take k, m
     * or b. Any other letters make it unreadable, so nothing is set rather than a guess.
     */
    private static double parseSetting(Tuning.Setting s, String text) {
        var matcher = NUMBER.matcher(text.toLowerCase(Locale.ROOT).replace(",", "").strip());
        if (!matcher.matches()) return Double.NaN;
        double value = Double.parseDouble(matcher.group(1));
        String unit = matcher.group(2);
        double scale = switch (s.kind()) {
            case MINUTES -> switch (unit) {
                case "", "m", "min", "mins", "minute", "minutes" -> 1;
                case "s", "sec", "secs", "second", "seconds" -> 1 / 60.0;
                case "h", "hr", "hrs", "hour", "hours" -> 60;
                case "d", "day", "days" -> 1440;
                default -> Double.NaN;
            };
            case SECONDS -> switch (unit) {
                case "", "s", "sec", "secs", "second", "seconds" -> 1;
                case "m", "min", "mins", "minute", "minutes" -> 60;
                case "h", "hr", "hrs", "hour", "hours" -> 3600;
                default -> Double.NaN;
            };
            case COUNT -> switch (unit) {
                case "" -> 1;
                case "k" -> 1e3;
                case "m" -> 1e6;
                case "b" -> 1e9;
                default -> Double.NaN;
            };
            case PERCENT -> unit.isEmpty() || unit.equals("%") ? 1 : Double.NaN;
            case RATE -> unit.isEmpty() || unit.equals("/h") ? 1 : Double.NaN;
            case BLOCKS -> unit.isEmpty() || unit.startsWith("block") ? 1 : Double.NaN;
            case FACTOR -> unit.isEmpty() || unit.equals("x") ? 1 : Double.NaN;
            case TOGGLE -> Double.NaN;
        };
        return value * scale;
    }

    /** GoNuts descriptions end on-off settings with "(1 on, 0 off)", which a switch already says. */
    private static String clean(String description) {
        return description == null ? "" : description.replaceAll("\\s*\\(1 on, 0 off\\)", "");
    }

    /** "WAITING_FOR_ORDER" as "Waiting for order". */
    private static String words(String constant) {
        String spaced = constant.toLowerCase(Locale.ROOT).replace('_', ' ');
        return spaced.isEmpty() ? spaced : Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
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
        pageId = id;
        detailName = null;
        query = "";
        searchFocused = false;
        restart();
    }

    private void openDetail(Module m) {
        pageId = pageOf(m).id;
        detailName = m.name;
        query = "";
        searchFocused = false;
        restart();
    }

    private void closeDetail() {
        detailName = null;
        restart();
    }

    /** Scrolls a new page to its top and slides it in. */
    private void restart() {
        content.scroll = content.scrollTarget = 0;
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
        pixels = Math.min((int) (gui * fit), Math.max(1, minecraft.getWindow().getHeight() / 384));
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
        dragging = null;
        hits.clear();
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
        donut(graphics, x + 12, y + 12, accent());
        bold("Donut", x + 34, y + 11, TEXT);
        draw("Client", Size.BASE, x + 34 + width("Donut ", Size.BASE) + 1, y + 11, accent(), false, true);
        if (!version.isEmpty()) draw(fit(version, Size.SMALL, w - 44), Size.SMALL, x + 34, y + 24, DIM);
        int top = y + 44;
        nav.set(x, top, w, y + h - 6 - top);
        drawing = nav;
        nav.scroll = ease(nav.scroll, nav.scrollTarget, 18);
        pushClip(nav.x, nav.y, nav.x + nav.w, nav.y + nav.h);
        int shift = snapped(nav.scroll);
        graphics.pose().pushMatrix();
        graphics.pose().translate(0, (float) (shift - nav.scroll));
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
            draw(count, Size.SMALL, x + w - 9 - width(count, Size.SMALL), y + 8, DIM);
        }
        hit("nav." + p.id, x, y, w, NAV_H, () -> openPage(p.id), null);
    }

    /** The page icons are drawn on a 17-unit grid and shown at ten units. */
    private void icon(String name, int x, int y, int color) {
        if (name.equals("search")) { search(graphics, x, y, color); return; }
        graphics.pose().pushMatrix();
        graphics.pose().translate(x, y);
        graphics.pose().scale(.6f, .6f);
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
            default -> sliders(graphics, 0, 0, color);
        }
        graphics.pose().popMatrix();
    }

    private void drawHeader() {
        int x = mainX(), w = mainW();
        Page page = currentPage();
        String title = query.isEmpty() ? page.header() : "Search";
        int searchW = Math.min(176, w / 3), titleW = w - searchW - 16;
        draw("Donut", Size.TITLE, x, wy + 21, accent(), false, true);
        int after = x + width("Donut ", Size.TITLE) + 2;
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
        while (width(shown, Size.BASE) > w - 34 && !shown.isEmpty()) shown = removeFirstCharacter(shown);
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
            scrolled(x + 1, top + 1, w - 2, frame - 2, origin -> {
                Column c = new Column(x + 16, w - 32, origin + 10, "");
                page.content.accept(c);
                c.flushChips();
                return c.y + 10;
            });
        }
    }

    /** Draws content into the scrolling region, sliding it in after a page change. */
    private void scrolled(int x, int y, int w, int h, java.util.function.IntUnaryOperator draw) {
        content.set(x, y, w, h);
        Region previous = drawing;
        drawing = content;
        content.scroll = ease(content.scroll, content.scrollTarget, 18);
        double slide = 1 - animate("page", 1);
        pushClip(x, y, x + w, y + h);
        int shift = snapped(content.scroll);
        graphics.pose().pushMatrix();
        graphics.pose().translate(0, (float) (shift - content.scroll + slide * 8));
        contentOrigin = y - shift;
        int end = draw.applyAsInt(contentOrigin);
        graphics.pose().popMatrix();
        popClip();
        content.contentHeight = end - contentOrigin;
        clampScroll(content);
        int max = Math.max(0, content.contentHeight - h);
        if (max > 0) {
            int thumb = Math.max(16, h * h / content.contentHeight);
            int thumbY = y + (int) (content.scroll / max * (h - thumb));
            round(graphics, x + w + 5, thumbY, 3, thumb, 1.5f, alpha(0xFFFFFF, .16));
        }
        drawing = previous;
    }

    /** Scroll offsets snap to whole screen pixels, so text does not shimmer while it moves. */
    private int snapped(double scroll) { return (int) Math.floor(Math.round(scroll * pixels) / (double) pixels); }

    private void clampScroll(Region r) {
        int max = Math.max(0, r.contentHeight - r.h);
        r.scroll = Math.clamp(r.scroll, 0, max);
        r.scrollTarget = Math.clamp(r.scrollTarget, 0, max);
    }

    private void drawModules(List<Module> modules, int x, int top, int w, int bottom) {
        scrolled(x, top, w, bottom - top, origin -> {
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
        scrolled(x + 1, y + 45, w - 2, h - 49, origin -> {
            Column c = new Column(x + 16, w - 32, origin + 8, m.name);
            m.settings.accept(c);
            c.flushChips();
            return c.y + 10;
        });
    }

    /** A switch; the knob slides across a track filled with the accent when on. */
    private void toggleSwitch(int x, int y, int w, int h, boolean on, String animation, boolean usable) {
        double position = animate(animation, on ? 1 : 0);
        int track = blend(TRACK, accent(), position);
        round(graphics, (float) x, (float) y, w, h, h / 2f, usable ? track : alpha(track, .45));
        if (position < .99) outline(graphics, (float) x, (float) y, w, h, h / 2f, alpha(0xFFFFFF, .08 * (1 - position)));
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

    private String conflicts(int index) {
        KeyMapping mapping = ModuleKeybinds.mapping(index);
        if (mapping == null || mapping.isUnbound()) return "";
        List<String> names = new ArrayList<>();
        for (KeyMapping other : minecraft.options.keyMappings) {
            if (other != mapping && mapping.same(other)) names.add(Component.translatable(other.getName()).getString());
        }
        return String.join(", ", names);
    }

    // Text fields -------------------------------------------------------------------------------

    private void commitText() {
        if (textField == null) return;
        textField = null;
        textSetter.accept(textBuffer);
        config.sanitize();
        dirty = true;
        flush();
    }

    // Helpers -----------------------------------------------------------------------------------

    private int accent() { return alpha(config.accentColor, 1); }

    private void draw(String text, Size size, int x, int y, int color) { draw(text, size, x, y, color, false, false); }

    private void draw(String text, Size size, int x, int y, int color, boolean bold, boolean italic) {
        UiPaint.text(graphics, font, text, size, x, y, color, bold, italic);
    }

    private void bold(String text, int x, int y, int color) { draw(text, Size.BASE, x, y, color, true, false); }

    private int width(String text, Size size) { return UiPaint.width(font, text, size); }

    /** The text cut to fit, ending in an ellipsis; a cut never leaves a stray period or comma before it. */
    private String fit(String value, Size size, int maxWidth) {
        if (width(value, size) <= maxWidth) return value;
        while (!value.isEmpty() && width(value + "…", size) > maxWidth) value = removeLastCharacter(value);
        return value.replaceAll("[\\s.,;:]+$", "") + "…";
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
                ModuleKeybinds.setKey(minecraft, binding, InputConstants.Type.MOUSE.getOrCreate(button));
            }
            binding = -1;
            return true;
        }
        boolean secondary = event.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT;
        if (event.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT && !secondary) return false;
        double x = event.x() / scale, y = event.y() / scale;
        keyboardFocus = null;
        searchFocused = false;
        commitText();
        if (colorEditor != null) colorEditor.hexFocused = false;
        Region under = regionAt(x, y);
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit h = hits.get(i);
            if (!h.contains(x, y) || h.region != under) continue;
            if (secondary) { if (h.secondary != null) h.secondary.run(); }
            else if (h.drag != null) { dragging = h; h.move(x, y); }
            else h.action.run();
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
            if (setter != null) setter.accept(value);
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
        r.scrollTarget = Math.clamp(r.scrollTarget + amount, 0, Math.max(0, r.contentHeight - r.h));
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
                ModuleKeybinds.setKey(minecraft, binding, key);
            }
            binding = -1;
            return true;
        }
        if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
            if (colorEditor != null) closeColor();
            else if (searchFocused || !query.isEmpty()) { searchFocused = false; query = ""; restart(); }
            else if (textField != null) commitText();
            else onClose();
            return true;
        }
        if (colorEditor != null && colorEditor.hexFocused) {
            if (event.key() == GLFW.GLFW_KEY_V && event.hasControlDown()) {
                String pasted = minecraft.keyboardHandler.getClipboard().trim().replaceFirst("^#", "");
                if (pasted.matches("(?i)[0-9a-f]{6}")) { colorEditor.set(Integer.parseInt(pasted, 16)); flush(); }
                return true;
            }
            if (event.key() == GLFW.GLFW_KEY_C && event.hasControlDown()) {
                minecraft.keyboardHandler.setClipboard("#" + colorEditor.hex); return true;
            }
            if (event.key() == GLFW.GLFW_KEY_BACKSPACE) {
                colorEditor.hex = colorEditor.replaceHex ? "" : colorEditor.hex.substring(0, Math.max(0, colorEditor.hex.length() - 1));
                colorEditor.replaceHex = false;
                return true;
            }
            if (event.key() == GLFW.GLFW_KEY_ENTER) { closeColor(); return true; }
            if (event.key() == GLFW.GLFW_KEY_A && (event.modifiers() & GLFW.GLFW_MOD_CONTROL) != 0) {
                colorEditor.replaceHex = true; return true;
            }
        }
        if (searchFocused) {
            if (event.key() == GLFW.GLFW_KEY_V && event.hasControlDown()) {
                String pasted = minecraft.keyboardHandler.getClipboard().replaceAll("[\\p{Cntrl}]", "");
                query = (query + pasted).substring(0, Math.min(64, query.length() + pasted.length()));
                content.scroll = content.scrollTarget = 0;
                return true;
            }
            if (event.key() == GLFW.GLFW_KEY_BACKSPACE) { query = removeLastCharacter(query); return true; }
            if (event.key() == GLFW.GLFW_KEY_ENTER) { searchFocused = false; return true; }
            if (event.key() == GLFW.GLFW_KEY_A && (event.modifiers() & GLFW.GLFW_MOD_CONTROL) != 0) { query = ""; return true; }
        }
        if (textField != null) {
            if (event.key() == GLFW.GLFW_KEY_V && event.hasControlDown()) {
                String pasted = minecraft.keyboardHandler.getClipboard().replaceAll("[\\p{Cntrl}]", "").strip();
                // Only the pasted text is cut to fit; what is already there is never shortened.
                textBuffer += pasted.substring(0, Math.clamp(textLimit - textBuffer.length(), 0, pasted.length())); return true;
            }
            if (event.key() == GLFW.GLFW_KEY_BACKSPACE) { textBuffer = removeLastCharacter(textBuffer); return true; }
            if (event.key() == GLFW.GLFW_KEY_ENTER) { commitText(); return true; }
            if (event.key() == GLFW.GLFW_KEY_A && (event.modifiers() & GLFW.GLFW_MOD_CONTROL) != 0) { textBuffer = ""; return true; }
            if (event.key() == GLFW.GLFW_KEY_TAB) commitText();
        }
        if (event.key() == GLFW.GLFW_KEY_TAB && !hits.isEmpty()) {
            int index = -1;
            for (int i = 0; i < hits.size(); i++) if (hits.get(i).id.equals(keyboardFocus)) index = i;
            int direction = (event.modifiers() & GLFW.GLFW_MOD_SHIFT) != 0 ? -1 : 1;
            keyboardFocus = hits.get(Math.floorMod(index + direction, hits.size())).id;
            searchFocused = false;
            scrollTo(keyboardFocus);
            return true;
        }
        if (keyboardFocus != null && (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_SPACE)) {
            hits.stream().filter(h -> h.id.equals(keyboardFocus)).findFirst().ifPresent(h -> {
                if (h.action != null) h.action.run();
            });
            // Enter/Space that opens capture is not itself the new binding on repeat.
            if (binding >= 0) capturedKey = pressed;
            return true;
        }
        if (keyboardFocus != null && (event.key() == GLFW.GLFW_KEY_LEFT || event.key() == GLFW.GLFW_KEY_RIGHT)) {
            hits.stream().filter(h -> h.id.equals(keyboardFocus)).findFirst().ifPresent(h -> {
                if (h.nudge != null) h.nudge.accept(event.key() == GLFW.GLFW_KEY_RIGHT ? 1 : -1);
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
        }
        return false;
    }

    @Override
    public boolean keyReleased(KeyEvent event) {
        if (InputConstants.getKey(event).equals(capturedKey)) capturedKey = null;
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
            if (event.isAllowedChatCharacter() && textBuffer.length() < textLimit) textBuffer += event.codepointAsString();
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
    private void flush() { if (dirty) { save.run(); dirty = false; } }
    private String removeFirstCharacter(String value) { return value.isEmpty() ? value : value.substring(value.offsetByCodePoints(0, 1)); }
    private String removeLastCharacter(String value) { return value.isEmpty() ? value : value.substring(0, value.offsetByCodePoints(value.length(), -1)); }

    @Override
    public void onClose() { flush(); minecraft.gui.setScreen(null); }

    @Override
    public void removed() {
        commitText();
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
            bold(fit(label, Size.BASE, room), x, y + (described ? 7 : 8), TEXT);
            if (!described) return;
            String shown = fit(description, Size.SMALL, room);
            draw(shown, Size.SMALL, x, y + 20, descriptionColor);
            if (over && !shown.equals(description)) tooltip = description;
        }

        void section(String title) {
            flushChips();
            y += 10;
            String label = title.toUpperCase(Locale.ROOT);
            mark(id("section." + title), y, 12);
            draw(label, Size.SMALL, x, y + 2, DIM);
            int rule = x + width(label, Size.SMALL) + 8;
            if (rule < x + w) round(graphics, rule, y + 6, x + w - rule, 1, 0, EDGE);
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
            y += h + 2;
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
            outline(graphics, chipX, y, chipW, 20, 10, over ? EDGE_HOVER : blend(EDGE, alpha(config.accentColor, .75), on));
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

        /** A segmented control; each option is its own target, named by the option. */
        void choice(String key, String label, int count, IntSupplier getter, IntConsumer setter, IntFunction<String> name) {
            flushChips();
            String id = id(key);
            int index = getter.getAsInt(), h = 26, total = 4;
            int[] widths = new int[count];
            for (int i = 0; i < count; i++) total += widths[i] = width(name.apply(i), Size.BASE) + 22;
            int left = x + w - total, top = y + (h - 20) / 2;
            boolean over = inside(x - 10, y, w + 20, h);
            rowLabel(label, null, MUTED, over, h, total);
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
            y += h + 2;
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
            int box = Math.max(46, width(display, Size.BASE) + 16), trackW = Math.min(140, (int) (w * .3));
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
                setter.accept(snap(getter.getAsDouble() + direction * step, min, max, step));
                dirty = true;
                flush();
            });
            y += h + 2;
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
            y += h + 2;
        }

        void field(String key, String label, String description, Supplier<String> getter, Consumer<String> setter, boolean secret) {
            field(key, label, description, getter, setter, secret, 128);
        }

        /** A text field holding at most {@code limit} characters; long lists such as item ids need more than names do. */
        void field(String key, String label, String description, Supplier<String> getter, Consumer<String> setter, boolean secret,
                   int limit) {
            flushChips();
            String id = id(key);
            boolean focused = id.equals(textField);
            int h = rowHeight(description), boxW = Math.min(220, (int) (w * .45)), bx = x + w - boxW, by = y + h / 2 - 10;
            boolean over = inside(x - 10, y, w + 20, h);
            rowLabel(label, description, MUTED, over, h, boxW);
            round(graphics, bx, by, boxW, 20, 6, FIELD);
            outline(graphics, bx, by, boxW, 20, 6, focused ? accent() : over ? EDGE_HOVER : EDGE);
            String value = focused ? textBuffer : getter.get();
            // Secrets show only their last four characters unless being edited.
            if (secret && !focused) value = value.isEmpty() ? "" : "*".repeat(Math.min(12, Math.max(0, value.length() - 4)))
                    + value.substring(Math.max(0, value.length() - 4));
            String shown;
            if (focused) {
                // Keep the end in view while typing.
                shown = value + (System.currentTimeMillis() % 1000 < 500 ? "|" : "");
                while (!shown.isEmpty() && width(shown, Size.BASE) > boxW - 16) shown = removeFirstCharacter(shown);
            } else {
                shown = fit(value, Size.BASE, boxW - 16);
            }
            draw(shown, Size.BASE, bx + 8, by + 7, focused ? TEXT : LABEL);
            hit(id, x - 10, y, w + 20, h, () -> {
                textField = id;
                textBuffer = getter.get();
                textSetter = setter;
                textLimit = Math.max(limit, textBuffer.length());
                keyboardFocus = null;
            }, null);
            y += h + 2;
        }

        /** Amounts are typed as 250k, 1.5M or 12000. */
        void money(String key, String label, String description, DoubleSupplier getter, DoubleConsumer setter) {
            field(key, label, description, () -> Money.format(getter.getAsDouble()), text -> {
                double value = Money.parse(text);
                if (Double.isFinite(value)) setter.accept(value);
            }, false);
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
            KeyMapping mapping = ModuleKeybinds.mapping(index);
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
            hit(id, x - 10, y, w + 20, h, () -> {
                binding = index;
                dragging = null;
                searchFocused = false;
                keyboardFocus = null;
            }, null);
            y += h + 2;
        }

        void button(String key, String label, String description, Runnable action) { button(key, label, description, label, action); }

        /** A row with a button on the right; the button reads {@code text}. */
        void button(String key, String label, String description, String text, Runnable action) {
            flushChips();
            String id = id(key);
            int h = rowHeight(description), bw = width(text, Size.BASE) + 28, bx = x + w - bw, by = y + h / 2 - 10;
            boolean over = inside(x - 10, y, w + 20, h);
            rowLabel(label, description, MUTED, over, h, bw);
            round(graphics, bx, by, bw, 20, 6, over ? CARD_HOVER : FIELD);
            outline(graphics, bx, by, bw, 20, 6, over ? EDGE_HOVER : EDGE);
            draw(text, Size.BASE, bx + 14, by + 7, over ? TEXT : LABEL);
            hit(id, x - 10, y, w + 20, h, () -> change(action), null);
            y += h + 2;
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

    /** A module: its switch, its settings (or null), and the index of its keybind (or -1). */
    private record Module(String name, String description, String keywords, BooleanSupplier enabled, Runnable toggle,
                          BooleanSupplier available, String unavailable, Consumer<Column> settings, int key) {
        Module key(int index) {
            return new Module(name, description, keywords, enabled, toggle, available, unavailable, settings, index);
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
