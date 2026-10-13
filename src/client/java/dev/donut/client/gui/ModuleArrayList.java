package dev.donut.client.gui;

import dev.donut.client.config.ClientConfig;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Active module names at the top right, longest first, each on a dark strip with an accent edge. */
public final class ModuleArrayList {
    private ModuleArrayList() { }

    public static void render(GuiGraphicsExtractor graphics, Font font, int screenWidth,
                              ClientConfig config, boolean freecamActive) {
        if (!config.showHud) return;
        List<String> names = new ArrayList<>(11);
        if (config.storage.enabled) names.add("StorageFinder");
        if (config.spawner.enabled) names.add("SpawnerFinder");
        if (config.debris.enabled) names.add("DebrisFinder");
        if (config.activity.enabled) names.add("ActivityFinder");
        if (config.tunnels.enabled) names.add("TunnelFinder");
        if (config.entities.enabled) names.add("EntityFinder");
        if (config.xray.enabled) names.add("Xray");
        if (config.fullbright.enabled) names.add("Fullbright");
        if (config.rtp.enabled) names.add("AutoRTP");
        if (freecamActive) names.add("Freecam");
        names.sort(Comparator.comparingInt((String name) -> UiPaint.width(font, name)).reversed());

        int right = screenWidth - 2, y = 2, accent = UiPaint.alpha(config.accentColor, 1);
        for (String name : names) {
            int left = right - UiPaint.width(font, name) - 8;
            graphics.fill(left, y, right, y + 13, 0xC4081213);
            graphics.fill(left - 1, y, left, y + 13, accent);
            UiPaint.text(graphics, font, name, left + 4, y + 3, 0xFFF0F6F5);
            y += 13;
        }
    }
}
