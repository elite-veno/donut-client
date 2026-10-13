package dev.donut.client.sniper;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * An item as the DonutSMP API describes it. Items trade as one market only when everything that sets
 * their value matches: type, name (spawners are named by mob), enchantments, armor trim and lore.
 */
public record MarketItem(String id, String name, int count, Map<String, Integer> enchants, String trim,
                         List<String> lore, boolean hasContents) {
    public MarketItem {
        id = normalizeId(id);
        name = clean(name);
        enchants = enchants == null ? Map.of() : Map.copyOf(enchants);
        trim = trim == null ? "" : clean(trim);
        lore = lore == null ? List.of() : lore.stream().map(MarketItem::clean).filter(line -> !line.isEmpty()).toList();
    }

    /** Containers carry arbitrary contents, so they never form a reliable market. */
    public boolean tradable() { return !hasContents && count > 0 && !id.isEmpty(); }

    public String key() {
        // Normalized before sorting, so "minecraft:unbreaking" and "unbreaking" sort alike.
        TreeMap<String, Integer> sorted = new TreeMap<>();
        enchants.forEach((name, level) -> sorted.put(normalizeId(name), level));
        String enchantKey = sorted.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining(","));
        return id + "|" + name.toLowerCase(Locale.ROOT) + "|" + enchantKey + "|" + trim.toLowerCase(Locale.ROOT)
                + "|" + String.join("/", lore).toLowerCase(Locale.ROOT);
    }

    /** A readable label for the GUI. */
    public String label() {
        String base = name.isEmpty() ? id.replace('_', ' ') : name;
        return enchants.isEmpty() ? base : base + " (" + enchants.size() + " ench)";
    }

    /** "minecraft:diamond_sword", "DIAMOND_SWORD" and "diamond_sword" are the same item. */
    public static String normalizeId(String id) {
        if (id == null) return "";
        String value = id.strip().toLowerCase(Locale.ROOT);
        int colon = value.indexOf(':');
        return colon >= 0 ? value.substring(colon + 1) : value;
    }

    /** Drops section-sign formatting codes and surrounding space. */
    public static String clean(String text) {
        if (text == null) return "";
        return text.replaceAll("§.", "").strip();
    }
}
