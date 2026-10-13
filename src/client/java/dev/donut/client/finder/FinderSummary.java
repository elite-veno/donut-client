package dev.donut.client.finder;

import net.minecraft.world.phys.Vec3;

/**
 * One line of the info HUD.
 *
 * @param nearest the closest find, or null when there is none
 * @param note a short remark such as "anti-xray", or empty
 */
public record FinderSummary(String name, int count, Vec3 nearest, int color, String note) { }
