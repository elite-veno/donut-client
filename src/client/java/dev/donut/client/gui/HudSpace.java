package dev.donut.client.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Parts of the screen Donut's HUD panels take, so the distance labels keep clear of them. Each owner registers a
 * supplier answering {x, y, width, height} in GUI pixels, or null while it draws nothing, so the area follows its panel
 * from frame to frame.
 */
public final class HudSpace {
    private static final Map<String, Supplier<int[]>> AREAS = new ConcurrentHashMap<>();

    private HudSpace() { }

    /** Claims an area; a null area gives it back. */
    public static void reserve(String owner, Supplier<int[]> area) {
        if (owner == null) return;
        if (area == null) AREAS.remove(owner);
        else AREAS.put(owner, area);
    }

    /** The claimed areas as they are this frame. */
    public static List<int[]> reserved() {
        List<int[]> out = new ArrayList<>();
        for (Supplier<int[]> area : AREAS.values()) {
            int[] r = area.get();
            if (r != null && r.length >= 4 && r[2] > 0 && r[3] > 0) out.add(r);
        }
        return out;
    }
}
