package dev.donut.client.order;

import dev.donut.client.miner.ChunkMiner;
import dev.donut.client.rtp.AutoRtpController;
import dev.donut.client.schematic.Materials;
import dev.donut.client.schematic.SchematicBuilder;
import dev.doughbay.fabric.GoNutsApi;
import dev.doughbay.fabric.MaterialBuyer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * Orders what a schematic still needs on the auction house through the bundled GoNuts: what the build takes, less what
 * the player carries (shulker boxes counted), goes to GoNuts for a quote; once the player says yes, GoNuts buys it, and
 * with Build when bought on, SchematicBuilder starts after. Nothing here spends on its own: buying needs the player's
 * yes to the quote, and GoNuts' authorized execution on.
 */
public final class MaterialOrder {
    /** Empty buckets enough for the builder to fill and pour water with, however many water blocks there are. */
    private static final int BUCKETS = 4;

    private static boolean buildAfter;
    private static boolean wasBuying;

    private MaterialOrder() { }

    /** Whether the bundled GoNuts is there to order with. */
    public static boolean available() {
        try {
            GoNutsApi.materials();
            return true;
        } catch (LinkageError | RuntimeException e) {
            return false;
        }
    }

    /** What to order: the build's needs less what is carried, as GoNuts item ids. */
    public static List<MaterialBuyer.Need> needs(SchematicBuilder builder, LocalPlayer player) {
        Map<Item, Integer> wanted = builder.isActive() ? builder.needs() : builder.bill();
        Materials carried = Materials.of(player.getInventory());
        Map<Item, Integer> order = new LinkedHashMap<>();
        for (Map.Entry<Item, Integer> need : wanted.entrySet()) {
            Item item = need.getKey();
            int missing = need.getValue() - carried.total(item);
            if (item == Items.WATER_BUCKET) {
                // The builder fills its own buckets at water that comes back: a few empty ones are enough.
                int buckets = Math.min(BUCKETS, need.getValue()) - carried.total(Items.BUCKET) - carried.total(Items.WATER_BUCKET);
                if (buckets > 0) order.merge(Items.BUCKET, buckets, Integer::sum);
                continue;
            }
            if (missing > 0) order.merge(item, missing, Integer::sum);
        }
        List<MaterialBuyer.Need> needs = new ArrayList<>();
        for (Map.Entry<Item, Integer> e : order.entrySet()) {
            needs.add(new MaterialBuyer.Need(BuiltInRegistries.ITEM.getKey(e.getKey()).toString(), e.getValue()));
        }
        return needs;
    }

    /** Why nothing may be ordered now, or null. */
    public static String blocker() {
        if (!available()) return "GoNuts isn't in this build";
        if (SchematicBuilder.busy()) return "SchematicBuilder is building; stop it first";
        if (ChunkMiner.busy()) return "ChunkMiner is digging; stop it first";
        AutoRtpController rtp = AutoRtpController.getInstance();
        if (rtp != null && rtp.isActive()) return "Auto RTP is running; stop it first";
        return null;
    }

    /**
     * Starts a quote for what the build still needs, the cheapest combination of listings for each item; with
     * {@code highPrice}, listings over GoNuts' $10M cap count too. Null, or why not.
     */
    public static String quote(SchematicBuilder builder, boolean highPrice) {
        String blocked = blocker();
        if (blocked != null) return blocked;
        LocalPlayer player = Minecraft.getInstance().player;
        if (builder == null || builder.schematic() == null || player == null) return "Pick a schematic first";
        List<MaterialBuyer.Need> needs = needs(builder, player);
        if (needs.isEmpty()) return "You carry everything it needs";
        return GoNutsApi.quoteMaterials(needs, highPrice);
    }

    /** Buys what the quote found, up to its total and GoNuts' margin over it; null, or why not. */
    public static String buy(boolean thenBuild) { return buy(thenBuild, Long.MAX_VALUE); }

    /** As {@link #buy(boolean)}, never spending more than {@code ceiling} dollars in all. */
    public static String buy(boolean thenBuild, long ceiling) {
        String blocked = blocker();
        if (blocked != null) return blocked;
        MaterialBuyer.Snapshot quote = GoNutsApi.materials();
        long budget = Math.min(Math.round(quote.quoteTotal() * MaterialBuyer.BUDGET_MARGIN), ceiling);
        if (budget <= 0) return "No money left to spend";
        String why = GoNutsApi.buyMaterials(budget);
        if (why == null) buildAfter = thenBuild;
        return why;
    }

    public static void stop() {
        buildAfter = false;
        if (available()) GoNutsApi.stopMaterials("Stopped from the menu");
    }

    public static void clear() {
        if (available()) GoNutsApi.clearMaterials();
    }

    public static MaterialBuyer.Snapshot snapshot() {
        return available() ? GoNutsApi.materials() : null;
    }

    /** Each tick: once buying is over, starts the build if asked to. */
    public static void tick(Minecraft client) {
        if (!buildAfter || !available()) return;
        MaterialBuyer.State state = GoNutsApi.materials().state();
        if (state == MaterialBuyer.State.BUYING) {
            wasBuying = true;
            return;
        }
        if (!wasBuying) return;
        if (state != MaterialBuyer.State.DONE) {
            // Stopped, or an emergency stop: no build after that.
            wasBuying = buildAfter = false;
            return;
        }
        // A page GoNuts left up closes first; the builder would only pause behind it.
        if (client.gui.screen() != null || GoNutsApi.busy()) return;
        wasBuying = buildAfter = false;
        if (SchematicBuilder.getInstance() != null) SchematicBuilder.getInstance().setEnabled(true);
    }
}
