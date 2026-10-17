package dev.donut.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.resources.Identifier;

/** Small, antialiased vector drawing helpers for the click GUI. */
final class UiPaint {
    /**
     * The UI font in three sizes, each rasterized at 1 to 10 (menu sizes: 1 to 6) screen pixels per unit. Minecraft
     * samples glyphs without filtering, so text is only sharp when its raster matches the pixels it covers; any other
     * size is stretched into uneven, blocky edges. Every size sits on the same baseline, 7 units below y.
     */
    enum Size {
        SMALL("ui_small_", MAX_RATIO), BASE("ui_", 10), TITLE("ui_title_", MAX_RATIO);

        private final FontDescription[] fonts;

        Size(String prefix, int rasters) {
            fonts = new FontDescription[rasters + 1];
            for (int i = 1; i <= rasters; i++) fonts[i] = new FontDescription.Resource(Identifier.fromNamespaceAndPath("donut", prefix + i));
        }

        FontDescription font() { return fonts[Math.clamp(ratio(), 1, fonts.length - 1)]; }
    }

    /** The most screen pixels per unit every size has a raster for; the menu draws at no more than this. */
    static final int MAX_RATIO = 6;

    /** Screen pixels per unit of what is being drawn; 0 follows the GUI scale. The menu sets its own while it draws. */
    private static int pixels;
    /** How thick lines and outlines are, relative to one unit; icons drawn shrunk thicken theirs back. */
    private static float stroke = 1;

    private UiPaint() { }

    static void pixelScale(int value) { pixels = value; }

    static void strokeScale(float value) { stroke = value; }

    private static int ratio() {
        return Math.max(1, pixels > 0 ? pixels : Minecraft.getInstance().getWindow().getGuiScale());
    }

    static Component label(String value) { return label(value, Size.BASE, false); }

    static Component label(String value, Size size, boolean italic) {
        FontDescription font = size.font();
        return Component.literal(value).withStyle(style -> style.withFont(font).withItalic(italic));
    }

    static void text(GuiGraphicsExtractor g, Font font, String text, int x, int y, int color) {
        g.text(font, label(text), x, y, color, false);
    }

    /** Text of any size, optionally semibold or italic. */
    static void text(GuiGraphicsExtractor g, Font font, String text, Size size, int x, int y, int color, boolean bold, boolean italic) {
        Component value = label(text, size, italic);
        g.text(font, value, x, y, color, false);
        if (bold) nudged(g, 1, 0, () -> g.text(font, value, x, y, color, false));
    }

    static int width(Font font, String text, Size size) {
        return font.width(label(text, size, false));
    }

    /** Draws shifted by whole screen pixels. */
    private static void nudged(GuiGraphicsExtractor g, int dx, int dy, Runnable draw) {
        float pixel = 1f / ratio();
        g.pose().pushMatrix();
        g.pose().translate(dx * pixel, dy * pixel);
        draw.run();
        g.pose().popMatrix();
    }

    static int width(Font font, String text) {
        return font.width(label(text));
    }

    static int alpha(int rgb, double alpha) {
        return ((int) (Math.clamp(alpha, 0.0, 1.0) * 255) << 24) | (rgb & 0xFFFFFF);
    }

    static int blend(int a, int b, double t) {
        t = Math.clamp(t, 0.0, 1.0);
        return 0xFF000000 | ((int) (((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t) << 16)
                | ((int) (((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t) << 8)
                | (int) ((a & 255) * (1 - t) + (b & 255) * t);
    }

    static void round(GuiGraphicsExtractor g, int x, int y, int w, int h, int radius, int color) {
        round(g, (float) x, y, w, h, radius, color);
    }

    static void round(GuiGraphicsExtractor g, float x, float y, float w, float h, float radius, int color) {
        SmoothGeometry.rounded(g, x, y, w, h, radius, 0, color);
    }

    static void outline(GuiGraphicsExtractor g, int x, int y, int w, int h, int radius, int color) {
        outline(g, (float) x, y, w, h, radius, color);
    }

    static void outline(GuiGraphicsExtractor g, float x, float y, float w, float h, float radius, int color) {
        SmoothGeometry.rounded(g, x, y, w, h, radius, stroke, color);
    }

    static void shadow(GuiGraphicsExtractor g, int x, int y, int w, int h, int radius, int color) {
        SmoothGeometry.shadow(g, x, y, w, h, radius, color);
    }

    static void line(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2, int color) {
        line(g, x1 + .5f, y1 + .5f, x2 + .5f, y2 + .5f, color);
    }

    static void line(GuiGraphicsExtractor g, float x1, float y1, float x2, float y2, int color) {
        SmoothGeometry.line(g, x1, y1, x2, y2, 1.2f * stroke, color);
    }

    static void search(GuiGraphicsExtractor g, int x, int y, int color) {
        outline(g, (float) x, y, 8, 8, 4, color);
        line(g, x + 7f, y + 7f, x + 10f, y + 10f, color);
    }

    /** A compass in a ring, for random teleports: its needle's north half solid and its south half faint. */
    static void rtp(GuiGraphicsExtractor g, int x, int y, int color) {
        outline(g, x + 1, y + 1, 15, 15, 7, color);
        SmoothGeometry.line(g, x + 8.5f, y + 8.5f, x + 12.2f, y + 4.8f, 2.4f * stroke, color);
        SmoothGeometry.line(g, x + 8.5f, y + 8.5f, x + 4.8f, y + 12.2f, 2.4f * stroke, alpha(color, .45));
    }

    /** An eye, for X-ray. */
    static void eye(GuiGraphicsExtractor g, int x, int y, int color) {
        float[] path = new float[24];
        for (int i = 0; i < 12; i++) {
            double angle = i * Math.PI / 6;
            path[i * 2] = x + 8.5f + (float) Math.cos(angle) * 8f;
            path[i * 2 + 1] = y + 8.5f + (float) Math.sin(angle) * 5f;
        }
        SmoothGeometry.closedStroke(g, path, 1.15f * stroke, color);
        outline(g, x + 5, y + 5, 7, 7, 3, color);
        round(g, x + 7, y + 7, 3, 3, 1, color);
    }

    /** A small arrow centered on x/y, pointing up when {@code degrees} is 0 and turning clockwise. */
    static void arrow(GuiGraphicsExtractor g, float x, float y, float degrees, int color) {
        double angle = Math.toRadians(degrees);
        float dx = (float) Math.sin(angle), dy = (float) -Math.cos(angle);
        float tipX = x + dx * 4.5f, tipY = y + dy * 4.5f;
        float tailX = x - dx * 4f, tailY = y - dy * 4f;
        line(g, tailX, tailY, tipX, tipY, color);
        // The head's two strokes sit 35 degrees either side of the shaft.
        for (int side = -1; side <= 1; side += 2) {
            double head = angle + Math.PI + side * Math.toRadians(35);
            line(g, tipX, tipY, tipX + (float) Math.sin(head) * 3.5f, tipY - (float) Math.cos(head) * 3.5f, color);
        }
    }

    /** A donut seen from above, 15 pixels across, for the menu's brand. */
    static void donut(GuiGraphicsExtractor g, int x, int y, int color) {
        SmoothGeometry.rounded(g, x, y, 15, 15, 7.5f, 3.2f, color);
        // Two sprinkles on the ring itself.
        round(g, x + 2.6f, y + 3.4f, 2f, 1.2f, .6f, alpha(0xFFFFFF, .9));
        round(g, x + 10.4f, y + 10.4f, 2f, 1.2f, .6f, alpha(0xFFFFFF, .9));
    }

    /** A screen on a stand, for the HUD. */
    static void monitor(GuiGraphicsExtractor g, int x, int y, int color) {
        outline(g, x + 1, y + 2, 15, 10, 2, color);
        line(g, x + 8.5f, y + 12f, x + 8.5f, y + 15f, color);
        line(g, x + 5f, y + 15.5f, x + 12f, y + 15.5f, color);
    }

    /** A keyboard, for key bindings. */
    static void keyboard(GuiGraphicsExtractor g, int x, int y, int color) {
        outline(g, x, y + 3, 17, 11, 2, color);
        for (int row = 0; row < 2; row++) {
            for (int key = 0; key < 4; key++) round(g, x + 3 + key * 3, y + 6 + row * 3, 1.6f, 1.6f, .5f, color);
        }
        line(g, x + 5f, y + 11.5f, x + 12f, y + 11.5f, color);
    }

    /** A painter's palette, for the theme. */
    static void palette(GuiGraphicsExtractor g, int x, int y, int color) {
        outline(g, x + 1, y + 1, 15, 15, 7, color);
        round(g, x + 5, y + 4, 2.4f, 2.4f, 1.2f, color);
        round(g, x + 9.5f, y + 4, 2.4f, 2.4f, 1.2f, color);
        round(g, x + 4, y + 8.5f, 2.4f, 2.4f, 1.2f, color);
        round(g, x + 9, y + 10, 3, 3, 1.5f, color);
    }

    /** Four tiles, for the dashboard. */
    /** A block seen from above one corner, for building. */
    static void cube(GuiGraphicsExtractor g, int x, int y, int color) {
        line(g, x + 8.5f, y + 1f, x + 16f, y + 5f, color);
        line(g, x + 16f, y + 5f, x + 8.5f, y + 9f, color);
        line(g, x + 8.5f, y + 9f, x + 1f, y + 5f, color);
        line(g, x + 1f, y + 5f, x + 8.5f, y + 1f, color);
        line(g, x + 1f, y + 5f, x + 1f, y + 12.5f, color);
        line(g, x + 16f, y + 5f, x + 16f, y + 12.5f, color);
        line(g, x + 8.5f, y + 9f, x + 8.5f, y + 16.5f, color);
        line(g, x + 1f, y + 12.5f, x + 8.5f, y + 16.5f, color);
        line(g, x + 8.5f, y + 16.5f, x + 16f, y + 12.5f, color);
    }

    static void grid(GuiGraphicsExtractor g, int x, int y, int color) {
        outline(g, x + 1, y + 1, 6, 6, 1.5f, color);
        outline(g, x + 10, y + 1, 6, 6, 1.5f, color);
        outline(g, x + 1, y + 10, 6, 6, 1.5f, color);
        outline(g, x + 10, y + 10, 6, 6, 1.5f, color);
    }

    /** Three lines with bullets, for the market table. */
    static void list(GuiGraphicsExtractor g, int x, int y, int color) {
        for (int row = 0; row < 3; row++) {
            round(g, x + 1, y + 3 + row * 5, 2, 2, 1, color);
            line(g, x + 5f, y + 4f + row * 5, x + 16f, y + 4f + row * 5, color);
        }
    }

    /** Three rising bars, for trading. */
    static void chart(GuiGraphicsExtractor g, int x, int y, int color) {
        round(g, x + 2, y + 10, 3, 6, 1, color);
        round(g, x + 7, y + 6, 3, 10, 1, color);
        round(g, x + 12, y + 2, 3, 14, 1, color);
    }

    /** Two slider knobs on their tracks, for client settings. */
    static void sliders(GuiGraphicsExtractor g, int x, int y, int color) {
        line(g, x + 1f, y + 5f, x + 16f, y + 5f, color);
        line(g, x + 1f, y + 12f, x + 16f, y + 12f, color);
        round(g, x + 9, y + 2, 6, 6, 3, color);
        round(g, x + 3, y + 9, 6, 6, 3, color);
    }
}
