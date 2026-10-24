package dev.donut.client.config;

import java.util.LinkedHashMap;
import java.util.Map;

/** Where each click GUI panel was left, and whether it is collapsed. */
public final class GuiSettings {
    public Map<String, Panel> panels = new LinkedHashMap<>();
    /** Light snowfall behind the menu. */
    public boolean snow = false;
    /** The menu design these colors were chosen for; null in configs from before the mint design. */
    public String theme;

    public static final class Panel {
        public int x;
        public int y;
        public boolean open = true;
    }

    public void sanitize() {
        if (panels == null) panels = new LinkedHashMap<>();
        panels.entrySet().removeIf(entry -> entry.getKey() == null || entry.getValue() == null);
        for (Panel panel : panels.values()) {
            // The screen keeps panels reachable; this only rejects absurd values from a hand-edited file.
            panel.x = Math.clamp(panel.x, -4096, 4096);
            panel.y = Math.clamp(panel.y, -4096, 4096);
        }
    }
}
