package dev.donut.client.gui;

import dev.donut.client.config.ClientConfig;
import dev.donut.client.finder.FinderScanner;
import dev.donut.client.finder.FinderSummary;
import dev.donut.client.render.HighlightRenderer;
import dev.donut.client.render.ScreenLabel;
import dev.doughbay.fabric.GoNutsApi;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/** What the finders see, at a glance: counts, the nearest find's distance and direction, and in-world labels. */
public final class FinderHud {
    private static final int TEXT = 0xFFE9EBF0, LABEL = 0xFFB4BAC6, MUTED = 0xFF878D99, WARN = 0xFFFFC66B;
    private static final int STRIP = 0xC00A0B0F;

    private FinderHud() { }

    public static void render(GuiGraphicsExtractor g, Minecraft client, ClientConfig config, FinderScanner scanner) {
        Font font = client.font;
        if (config.hud.labels) labels(g, font, client);
        if (config.hud.info) info(g, font, client, scanner.summaries());
    }

    /** Keeps GoNuts's HUD panel, alerts and nearby-player list off the info panel; call once at startup. */
    public static void reserveSpace(ClientConfig config, FinderScanner scanner) {
        GoNutsApi.reserveHud("donut:finder_info", () -> {
            Minecraft client = Minecraft.getInstance();
            return config.hud.info && shown(client) ? infoArea(client, client.font, scanner.summaries()) : null;
        });
    }

    /** Whether Donut's HUD draws at all: in a world, with no screen open, and not hidden with F1. */
    static boolean shown(Minecraft client) {
        return client.player != null && client.gui.screen() == null && !client.gui.hud.isHidden();
    }

    /** The info panel's {x, y, width, height}, as {@link #info} lays it out, or null while it draws nothing. */
    private static int[] infoArea(Minecraft client, Font font, List<FinderSummary> lines) {
        if (lines.isEmpty() || client.getDebugOverlay().showDebugScreen()) return null;
        Vec3 origin = client.player.position();
        int width = 0;
        for (FinderSummary line : lines) width = Math.max(width, rowWidth(font, line, distance(line, origin)));
        return new int[] {2, 2, width, lines.size() * 12};
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
        if (lines.isEmpty() || client.getDebugOverlay().showDebugScreen()) return;
        Vec3 origin = client.player.position();
        float yaw = client.player.getYRot();
        int x = 2, y = 2;
        for (FinderSummary line : lines) {
            String count = Integer.toString(line.count());
            String distance = distance(line, origin);
            int nameWidth = UiPaint.width(font, line.name());
            int width = rowWidth(font, line, distance);
            int color = UiPaint.alpha(line.color(), 1);
            g.fill(x, y, x + width, y + 12, STRIP);
            g.fill(x, y, x + 1, y + 12, color);
            UiPaint.text(g, font, line.name(), x + 4, y + 2, LABEL);
            int cursor = x + 4 + nameWidth + 5;
            UiPaint.text(g, font, count, cursor, y + 2, TEXT);
            cursor += UiPaint.width(font, count);
            if (!distance.isEmpty()) {
                // The arrow points toward the nearest find relative to where you are looking.
                Vec3 to = line.nearest().subtract(origin);
                float bearing = (float) Math.toDegrees(Math.atan2(-to.x, to.z));
                UiPaint.arrow(g, cursor + 9, y + 6, Mth.wrapDegrees(bearing - yaw), color);
                UiPaint.text(g, font, distance, cursor + 16, y + 2, MUTED);
                cursor += 16 + UiPaint.width(font, distance);
            }
            if (!line.note().isEmpty()) UiPaint.text(g, font, line.note(), cursor + 6, y + 2, WARN);
            y += 12;
        }
    }

    private static void labels(GuiGraphicsExtractor g, Font font, Minecraft client) {
        int width = client.getWindow().getGuiScaledWidth(), height = client.getWindow().getGuiScaledHeight();
        List<int[]> placed = new ArrayList<>();
        // Labels arrive nearest first; a farther label that would cover a nearer one moves up, or is left out.
        for (ScreenLabel label : HighlightRenderer.labels()) {
            int textWidth = UiPaint.width(font, label.text());
            int x = (int) Math.round(label.x() * width) - textWidth / 2;
            int y = (int) Math.round(label.y() * height) - 12;
            x = Math.clamp(x, 2, Math.max(2, width - textWidth - 2));
            y = Math.clamp(y, 2, Math.max(2, height - 12));
            int free = freeRow(placed, x - 3, y - 2, textWidth + 6, 13);
            if (free == Integer.MIN_VALUE) continue;
            y = free + 2;
            placed.add(new int[] {x - 3, y - 2, textWidth + 6, 13});
            // Far labels fade so the nearest finds read first.
            double strength = Math.clamp(1.2 - label.distance() / 160.0, 0.55, 1.0);
            g.fill(x - 3, y - 2, x + textWidth + 3, y + 10, UiPaint.alpha(0x0A0B0F, 0.75 * strength));
            g.fill(x - 3, y + 10, x + textWidth + 3, y + 11, UiPaint.alpha(label.color(), strength));
            UiPaint.text(g, font, label.text(), x, y, UiPaint.alpha(0xE9EBF0, strength));
        }
    }

    /** The top of the nearest free spot at or above y, at most two rows up, or MIN_VALUE when there is none. */
    private static int freeRow(List<int[]> placed, int x, int y, int w, int h) {
        for (int attempt = 0; attempt < 3; attempt++) {
            int top = y - attempt * h;
            boolean clear = true;
            for (int[] box : placed) {
                if (x < box[0] + box[2] && box[0] < x + w && top < box[1] + box[3] && box[1] < top + h) { clear = false; break; }
            }
            if (clear && top >= 0) return top;
        }
        return Integer.MIN_VALUE;
    }
}
