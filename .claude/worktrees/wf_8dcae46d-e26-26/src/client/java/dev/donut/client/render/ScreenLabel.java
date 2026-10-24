package dev.donut.client.render;

/**
 * A highlight's name and distance, placed where its top projects onto the screen.
 *
 * @param x horizontal position from 0 (left edge) to 1 (right edge)
 * @param y vertical position from 0 (top edge) to 1 (bottom edge)
 */
public record ScreenLabel(double x, double y, String text, int color, double distance) { }
