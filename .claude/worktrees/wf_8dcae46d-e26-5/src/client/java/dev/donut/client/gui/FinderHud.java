package dev.donut.client.gui;

import dev.donut.client.config.ClientConfig;
import dev.donut.client.finder.FinderScanner;
import dev.donut.client.finder.FinderSummary;
import dev.donut.client.miner.ChunkMiner;
import dev.donut.client.render.HighlightRenderer;
import dev.donut.client.render.ScreenLabel;
import dev.donut.client.schematic.SchematicBuilder;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/** What the finders see, at a glance: counts, the nearest find's distance and direction, and in-world labels. */
public final class FinderHud {
    /** The menu's colors, so the HUD reads as part of the same client. */
    static final int TEXT = 0xFFF5F0E6, LABEL = 0xFFD8D0BF, MUTED = 0xFF9E9483, WARN = 0xFFF28C52, STRIP = 0xC40A0908;
    /** Rows are as tall as the module list's, with the text a little below their top. */
    static final int ROW = 13;

    private FinderHud() { }

    public static void render(GuiGraphicsExtractor g, Minecraft client, ClientConfig config, FinderScanner scanner) {
        Font font = client.font;
        if (config.hud.labels) labels(g, font, client, config.hud.maxLabels);
        if (config.hud.info) info(g, font, client, lines(scanner));
    }

    /** The finders' lines, and the schematic builder's while it builds: blocks left and the way to the next one. */
    private static List<FinderSummary> lines(FinderScanner scanner) {
        SchematicBuilder builder = SchematicBuilder.getInstance();
        FinderSummary building = builder == null ? null : builder.summary();
        ChunkMiner miner = ChunkMiner.getInstance();
        FinderSummary digging = miner == null ? null : miner.summary();
        if (building == null && digging == null) return scanner.summaries();
        List<FinderSummary> lines = new ArrayList<>(scanner.summaries());
        if (building != null) lines.add(building);
        if (digging != null) lines.add(digging);
        return lines;
    }

    /** Keeps the distance labels off the info panel; call once at startup. */
    public static void reserveSpace(ClientConfig config, FinderScanner scanner) {
        HudSpace.reserve("donut:finder_info", () -> {
            Minecraft client = Minecraft.getInstance();
            return config.hud.info && reserved(client) ? infoArea(client.font, client, lines(scanner)) : null;
        });
    }

    /**
     * Whether Donut's HUD keeps its place on screen: in a world and not hidden with F1. A screen or F3 hides it for a
     * moment, but its place stays reserved, so labels do not jump over and back whenever one opens.
     */
    static boolean reserved(Minecraft client) {
        return client.player != null && !client.gui.hud.isHidden();
    }

    /** Whether F3's overlay is open; the HUD steps aside for it. */
    static boolean debugOpen(Minecraft client) {
        return client.debugEntries.isOverlayVisible();
    }

    /** The info panel's {x, y, width, height}, as {@link #info} lays it out, or null while it has nothing to show. */
    private static int[] infoArea(Font font, Minecraft client, List<FinderSummary> lines) {
        if (lines.isEmpty()) return null;
        Vec3 origin = eye(client).position();
        int width = 0;
        for (FinderSummary line : lines) width = Math.max(width, rowWidth(font, line, distance(line, origin)));
        return new int[] {2, 2, width, lines.size() * ROW};
    }

    /** Where distances and directions are measured from: the camera, which Freecam moves away from the player. */
    private static Camera eye(Minecraft client) {
        return client.gameRenderer.mainCamera();
    }

    private static String distance(FinderSummary line, Vec3 origin) {
        return line.nearest() == null ? "" : Math.round(line.nearest().distanceTo(origin)) + "m";
    }

    private static int rowWidth(Font font, FinderSummary line, String distance) {
        return 4 + UiPaint.width(font, line.name()) + 5 + UiPaint.width(font, Integer.toString(line.count()))
                + (distance.isEmpty() ? 0 : 16 + UiPaint.width(font, distance))
                + (line.note().isEmpty() ? 0 : 6 + UiPaint.width(font, line.note())) + 4;
    }

    /** Rows mirror the array list: a dark strip per finder, edged in that finder's color. */
    private static void info(GuiGraphicsExtractor g, Font font, Minecraft client, List<FinderSummary> lines) {
        if (lines.isEmpty() || debugOpen(client)) return;
        Camera eye = eye(client);
        Vec3 origin = eye.position();
        int x = 2, y = 2;
        for (FinderSummary line : lines) {
            String count = Integer.toString(line.count());
            String distance = distance(line, origin);
            int width = rowWidth(font, line, distance);
            int color = UiPaint.alpha(line.color(), 1);
            g.fill(x, y, x + width, y + ROW, STRIP);
            g.fill(x, y, x + 1, y + ROW, color);
            UiPaint.text(g, font, line.name(), x + 4, y + 3, LABEL);
            int cursor = x + 4 + UiPaint.width(font, line.name()) + 5;
            UiPaint.text(g, font, count, cursor, y + 3, TEXT);
            cursor += UiPaint.width(font, count);
            if (!distance.isEmpty()) {
                // The arrow points toward the nearest find relative to where the camera looks.
                Vec3 to = line.nearest().subtract(origin);
                float bearing = (float) Math.toDegrees(Math.atan2(-to.x, to.z));
                UiPaint.arrow(g, cursor + 9, y + 6.5f, Mth.wrapDegrees(bearing - eye.yRot()), color);
                UiPaint.text(g, font, distance, cursor + 16, y + 3, LABEL);
                cursor += 16 + UiPaint.width(font, distance);
            }
            // Problems such as a seed that does not match are amber; plain information is not.
            if (!line.note().isEmpty()) UiPaint.text(g, font, line.note(), cursor + 6, y + 3, line.warn() ? WARN : MUTED);
            y += ROW;
        }
    }

    /**
     * Distance labels at the nearest highlights, at most {@code max}. Labels arrive nearest first; a farther label that
     * would cover a nearer one, the info panel or the module list moves up or down a row, or is left out.
     */
    private static void labels(GuiGraphicsExtractor g, Font font, Minecraft client, int max) {
        int width = client.getWindow().getGuiScaledWidth(), height = client.getWindow().getGuiScaledHeight();
        List<int[]> placed = new ArrayList<>(HudSpace.reserved());
        int shown = 0;
        for (ScreenLabel label : HighlightRenderer.labels()) {
            if (shown >= max) break;
            int textWidth = UiPaint.width(font, label.text());
            int x = (int) Math.round(label.x() * width) - textWidth / 2;
            int y = (int) Math.round(label.y() * height) - 12;
            x = Math.clamp(x, 3, Math.max(3, width - textWidth - 3));
            y = Math.clamp(y, 3, Math.max(3, height - 12));
            int free = freeRow(placed, x - 3, y - 3, textWidth + 6, 14, height);
            if (free == Integer.MIN_VALUE) continue;
            y = free + 3;
            placed.add(new int[] {x - 3, y - 3, textWidth + 6, 14});
            shown++;
            // Far labels fade so the nearest finds read first.
            double strength = Math.clamp(1.2 - label.distance() / 160.0, 0.55, 1.0);
            g.fill(x - 3, y - 3, x + textWidth + 3, y + 10, UiPaint.alpha(STRIP, 0.8 * strength));
            g.fill(x - 3, y + 10, x + textWidth + 3, y + 11, UiPaint.alpha(label.color(), strength));
            UiPaint.text(g, font, label.text(), x, y, UiPaint.alpha(TEXT, strength));
        }
    }

    /** The top of the nearest free row at y, or one or two rows above or below it, or MIN_VALUE when there is none. */
    private static int freeRow(List<int[]> placed, int x, int y, int w, int h, int screenHeight) {
        for (int offset : new int[] {0, -1, 1, -2, 2}) {
            int top = y + offset * h;
            if (top < 0 || top + h > screenHeight) continue;
            boolean clear = true;
            for (int[] box : placed) {
                if (x < box[0] + box[2] && box[0] < x + w && top < box[1] + box[3] && box[1] < top + h) { clear = false; break; }
            }
            if (clear) return top;
        }
        return Integer.MIN_VALUE;
    }
}
