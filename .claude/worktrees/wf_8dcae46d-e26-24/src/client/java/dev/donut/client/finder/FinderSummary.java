package dev.donut.client.finder;

import net.minecraft.world.phys.Vec3;

/**
 * One line of the info HUD.
 *
 * @param nearest the closest find, or null when there is none
 * @param note a short remark such as "+12 by seed", or empty
 * @param warn the note reports a problem, such as a seed that doesn't match, rather than plain information
 */
public record FinderSummary(String name, int count, Vec3 nearest, int color, String note, boolean warn) {
    public FinderSummary(String name, int count, Vec3 nearest, int color, String note) {
        this(name, count, nearest, color, note, false);
    }
}
