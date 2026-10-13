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
     * The UI font in three sizes, each rasterized at 1 to 8 (menu sizes: 1 to 6) screen pixels per unit. Minecraft
     * samples glyphs without filtering, so text is only sharp when its raster matches the pixels it covers; any other
     * size is stretched into uneven, blocky edges. Every size sits on the same baseline, 7 units below y.
     */
    enum Size {
        SMALL("ui_small_", 6), BASE("ui_", 8), TITLE("ui_title_", 6);

        private final FontDescription[] fonts;

        Size(String prefix, int rasters) {
            fonts = new FontDescription[rasters + 1];
            for (int i = 1; i <= rasters; i++) fonts[i] = new FontDescription.Resource(Identifier.fromNamespaceAndPath("donut", prefix + i));
        }

        FontDescription font() { return fonts[Math.clamp(ratio(), 1, fonts.length - 1)]; }
    }

    /** Screen pixels per unit of what is being drawn; 0 follows the GUI scale. The menu sets its own while it draws. */
    private static int pixels;

    private UiPaint() { }

    static void pixelScale(int value) { pixels = value; }

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

    /**
     * Semibold text: a second pass one screen pixel to the right. Minecraft's own bold and shadow offset a whole unit,
     * several pixels on a large GUI scale, which smears the glyphs.
     */
    static void strong(GuiGraphicsExtractor g, Font font, String text, int x, int y, int color) {
        text(g, font, text, Size.BASE, x, y, color, true, false);
    }

    /** Text over a crisp one-pixel drop shadow, for names and titles that should stand out. */
    static void shadowed(GuiGraphicsExtractor g, Font font, String text, int x, int y, int color) {
        Component value = label(text);
        nudged(g, 1, 1, () -> g.text(font, value, x, y, alpha(0, .6), false));
        g.text(font, value, x, y, color, false);
    }

    static void strongShadowed(GuiGraphicsExtractor g, Font font, String text, int x, int y, int color) {
        Component value = label(text);
        nudged(g, 1, 1, () -> g.text(font, value, x, y, alpha(0, .6), false));
        nudged(g, 2, 1, () -> g.text(font, value, x, y, alpha(0, .6), false));
        strong(g, font, text, x, y, color);
    }

    /** Draws shifted by whole screen pixels. */
    private static void nudged(GuiGraphicsExtractor g, int dx, int dy, Runnable draw) {
        float pixel = 1f / ratio();
        g.pose().pushMatrix();
        g.pose().translate(dx * pixel, dy * pixel);
        draw.run();
        g.pose().popMatrix();
    }

    /** A filled rounded box sitting on a soft drop shadow. */
    static void raised(GuiGraphicsExtractor g, float x, float y, float w, float h, float radius, int color) {
        round(g, x, y + 1.5f, w, h, radius, 0x70000000);
        round(g, x, y, w, h, radius, color);
    }

    static int width(Font font, String text) {
        return font.width(label(text));
    }

    static void right(GuiGraphicsExtractor g, Font font, String text, int right, int y, int color) {
        text(g, font, text, right - width(font, text), y, color);
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
        SmoothGeometry.rounded(g, x, y, w, h, radius, 1, color);
    }

    static void shadow(GuiGraphicsExtractor g, int x, int y, int w, int h, int radius, int color) {
        SmoothGeometry.shadow(g, x, y, w, h, radius, color);
    }

    static void line(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2, int color) {
        line(g, x1 + .5f, y1 + .5f, x2 + .5f, y2 + .5f, color);
    }

    static void line(GuiGraphicsExtractor g, float x1, float y1, float x2, float y2, int color) {
        SmoothGeometry.line(g, x1, y1, x2, y2, 1.2f, color);
    }

    static void search(GuiGraphicsExtractor g, int x, int y, int color) {
        outline(g, (float) x, y, 8, 8, 4, color);
        line(g, x + 7f, y + 7f, x + 10f, y + 10f, color);
    }

    static void chest(GuiGraphicsExtractor g, int x, int y, int color) {
        outline(g, x + 1, y + 3, 15, 12, 2, color);
        line(g, x + 2f, y + 8f, x + 7f, y + 8f, color);
        line(g, x + 10f, y + 8f, x + 15f, y + 8f, color);
        round(g, x + 7, y + 7, 3, 4, 1, color);
    }

    static void spawner(GuiGraphicsExtractor g, int x, int y, int color) {
        outline(g, x + 1, y + 1, 15, 15, 2, color);
        line(g, x + 6f, y + 2f, x + 6f, y + 15f, color);
        line(g, x + 11f, y + 2f, x + 11f, y + 15f, color);
        line(g, x + 2f, y + 6f, x + 15f, y + 6f, color);
        line(g, x + 2f, y + 11f, x + 15f, y + 11f, color);
    }

    /** Ancient debris' ringed top face. */
    static void debris(GuiGraphicsExtractor g, int x, int y, int color) {
        outline(g, x + 1, y + 1, 15, 15, 2, color);
        outline(g, x + 5, y + 5, 7, 7, 3, color);
        round(g, x + 7, y + 7, 3, 3, 1, color);
    }

    /** A cluster of three amethyst crystals. */
    static void amethyst(GuiGraphicsExtractor g, int x, int y, int color) {
        line(g, x + 8.5f, y + 1f, x + 8.5f, y + 15f, color);
        line(g, x + 3f, y + 6f, x + 7f, y + 15f, color);
        line(g, x + 14f, y + 6f, x + 10f, y + 15f, color);
        line(g, x + 2f, y + 15.5f, x + 15f, y + 15.5f, color);
    }

    /** A compass needle in a ring, for random teleports. */
    static void rtp(GuiGraphicsExtractor g, int x, int y, int color) {
        outline(g, x + 1, y + 1, 15, 15, 7, color);
        line(g, x + 5f, y + 12f, x + 12f, y + 5f, color);
        round(g, x + 11, y + 4, 3, 3, 1, color);
    }

    static void camera(GuiGraphicsExtractor g, int x, int y, int color) {
        outline(g, x, y + 4, 17, 11, 2, color);
        outline(g, x + 5, y + 6, 7, 7, 3, color);
        round(g, x + 3, y + 2, 6, 3, 1, color);
        round(g, x + 13, y + 6, 2, 2, 1, color);
    }

    static void star(GuiGraphicsExtractor g, int x, int y, int color) {
        float[] path = new float[20];
        for (int i = 0; i < 10; i++) {
            double angle = -Math.PI / 2 + i * Math.PI / 5;
            float radius = i % 2 == 0 ? 7.5f : 3.6f;
            path[i * 2] = x + 7.5f + (float) Math.cos(angle) * radius;
            path[i * 2 + 1] = y + 7.5f + (float) Math.sin(angle) * radius;
        }
        SmoothGeometry.closedStroke(g, path, 1.15f, color);
    }

    static void cube(GuiGraphicsExtractor g, int x, int y, int size, int color, double outlineAlpha, double fillAlpha) {
        int dx = size / 3, dy = size / 4;
        round(g, x, y + dy, size, size, 1, alpha(color, fillAlpha));
        int c = alpha(color, outlineAlpha);
        outline(g, x, y + dy, size, size, 1, c);
        outline(g, x + dx, y, size, size, 1, alpha(color, outlineAlpha * 0.45));
        line(g, x, y + dy, x + dx, y, c);
        line(g, x + size - 1, y + dy, x + size + dx - 1, y, c);
        line(g, x, y + size + dy - 1, x + dx, y + size - 1, c);
        line(g, x + size - 1, y + size + dy - 1, x + size + dx - 1, y + size - 1, c);
    }

    /** An eye, for X-ray. */
    static void eye(GuiGraphicsExtractor g, int x, int y, int color) {
        float[] path = new float[24];
        for (int i = 0; i < 12; i++) {
            double angle = i * Math.PI / 6;
            path[i * 2] = x + 8.5f + (float) Math.cos(angle) * 8f;
            path[i * 2 + 1] = y + 8.5f + (float) Math.sin(angle) * 5f;
        }
        SmoothGeometry.closedStroke(g, path, 1.15f, color);
        outline(g, x + 5, y + 5, 7, 7, 3, color);
        round(g, x + 7, y + 7, 3, 3, 1, color);
    }

    /** A sun, for Fullbright. */
    static void sun(GuiGraphicsExtractor g, int x, int y, int color) {
        outline(g, x + 4, y + 4, 9, 9, 4, color);
        for (int i = 0; i < 8; i++) {
            double angle = i * Math.PI / 4;
            float cos = (float) Math.cos(angle), sin = (float) Math.sin(angle);
            line(g, x + 8.5f + cos * 6.5f, y + 8.5f + sin * 6.5f, x + 8.5f + cos * 8.5f, y + 8.5f + sin * 8.5f, color);
        }
    }

    /** A tunnel mouth in rock, for TunnelFinder. */
    static void tunnel(GuiGraphicsExtractor g, int x, int y, int color) {
        outline(g, x + 1, y + 1, 15, 15, 2, color);
        outline(g, x + 5, y + 5, 7, 11, 3, color);
        line(g, x + 2f, y + 15.5f, x + 15f, y + 15.5f, color);
    }

    /** A person, for EntityFinder. */
    static void person(GuiGraphicsExtractor g, int x, int y, int color) {
        outline(g, x + 5, y + 1, 7, 7, 3, color);
        outline(g, x + 2, y + 10, 13, 7, 3, color);
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
        round(g, x + 4.5f, y + 3.5f, 2f, 1.2f, .6f, alpha(color, .55));
        round(g, x + 9f, y + 10.5f, 2f, 1.2f, .6f, alpha(color, .55));
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

    /** A six-armed snowflake, 13 pixels across, for the menu's header. */
    static void snowflake(GuiGraphicsExtractor g, int x, int y, int color) {
        float cx = x + 6.5f, cy = y + 6.5f;
        for (int i = 0; i < 6; i++) {
            double angle = i * Math.PI / 3;
            float dx = (float) Math.sin(angle), dy = (float) -Math.cos(angle);
            line(g, cx, cy, cx + dx * 6, cy + dy * 6, color);
            // A small V on each arm, two thirds of the way out.
            float bx = cx + dx * 4, by = cy + dy * 4;
            for (int side = -1; side <= 1; side += 2) {
                double branch = angle + side * Math.toRadians(45);
                line(g, bx, by, bx + (float) Math.sin(branch) * 2.2f, by - (float) Math.cos(branch) * 2.2f, color);
            }
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
