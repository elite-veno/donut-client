package dev.donut.client.gui;

import dev.donut.client.config.ClientConfig;
import dev.doughbay.fabric.GoNutsApi;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.BooleanSupplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.effect.MobEffectInstance;

/** Active module names at the top right, longest first, each on a dark strip with an accent edge. */
public final class ModuleArrayList {
    private ModuleArrayList() { }

    public static void render(GuiGraphicsExtractor graphics, Font font, int screenWidth,
                              ClientConfig config, boolean freecamActive) {
        Minecraft client = Minecraft.getInstance();
        // F3's overlay has a column of its own on this side.
        if (!config.showHud || FinderHud.debugOpen(client)) return;
        int right = screenWidth - 2, y = top(client), accent = UiPaint.alpha(config.accentColor, 1);
        for (String name : names(font, config, freecamActive)) {
            int left = right - UiPaint.width(font, name) - 8;
            graphics.fill(left, y, right, y + FinderHud.ROW, FinderHud.STRIP);
            graphics.fill(left - 1, y, left, y + FinderHud.ROW, accent);
            UiPaint.text(graphics, font, name, left + 4, y + 3, FinderHud.TEXT);
            y += FinderHud.ROW;
        }
    }

    /** Keeps GoNuts's HUD panel, alerts and nearby-player list off the list; call once at startup. */
    public static void reserveSpace(ClientConfig config, BooleanSupplier freecamActive) {
        GoNutsApi.reserveHud("donut:arraylist", () -> {
            Minecraft client = Minecraft.getInstance();
            if (!config.showHud || !FinderHud.reserved(client)) return null;
            List<String> names = names(client.font, config, freecamActive.getAsBoolean());
            if (names.isEmpty()) return null;
            // The widest strip comes first, with its accent edge one pixel to its left.
            int width = UiPaint.width(client.font, names.getFirst()) + 9;
            return new int[] {client.getWindow().getGuiScaledWidth() - 2 - width, top(client), width, names.size() * FinderHud.ROW};
        });
    }

    /** The list's top: below the effect icons Minecraft draws in this corner, a row for helpful and one for harmful ones. */
    private static int top(Minecraft client) {
        if (client.player == null) return 2;
        boolean helpful = false, harmful = false;
        for (MobEffectInstance effect : client.player.getActiveEffects()) {
            if (!effect.showIcon()) continue;
            if (effect.getEffect().value().isBeneficial()) helpful = true;
            else harmful = true;
        }
        return harmful ? 54 : helpful ? 28 : 2;
    }

    private static List<String> names(Font font, ClientConfig config, boolean freecamActive) {
        List<String> names = new ArrayList<>(12);
        if (config.storage.enabled) names.add("StorageFinder");
        if (config.spawner.enabled) names.add("SpawnerFinder");
        if (config.debris.enabled) names.add("DebrisFinder");
        if (config.activity.enabled) names.add("ActivityFinder");
        if (config.tunnels.enabled) names.add("TunnelFinder");
        if (config.entities.enabled) names.add("EntityFinder");
        if (config.bases.enabled) names.add("BaseFinder");
        if (config.xray.enabled) names.add("Xray");
        if (config.fullbright.enabled) names.add("Fullbright");
        if (config.rtp.enabled) names.add("AutoRTP");
        if (freecamActive) names.add("Freecam");
        names.sort(Comparator.comparingInt((String name) -> UiPaint.width(font, name)).reversed());
        return names;
    }
}
