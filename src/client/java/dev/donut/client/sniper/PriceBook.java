package dev.donut.client.sniper;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Sale prices per market, kept across sessions so the history grows the longer the sniper runs. */
public final class PriceBook {
    private static final int MAX_SALES_PER_MARKET = 300;
    private static final int MAX_SEEN = 20_000;
    private static final Gson GSON = new GsonBuilder().create();

    private final Map<String, Market> markets = new HashMap<>();
    /** Insertion-ordered so the oldest ids are forgotten first. */
    private final LinkedHashSet<String> seen = new LinkedHashSet<>();

    public static final class Market {
        String name = "";
        final ArrayDeque<long[]> sales = new ArrayDeque<>();
    }

    /** @return true when the sale was new */
    public boolean add(MarketSale sale) {
        if (!sale.item().tradable() || !(sale.price() > 0)) return false;
        if (!seen.add(sale.id())) return false;
        if (seen.size() > MAX_SEEN) seen.remove(seen.iterator().next());
        Market market = markets.computeIfAbsent(sale.item().key(), key -> new Market());
        market.name = sale.item().label();
        // Unit prices are stored as raw double bits next to the time.
        market.sales.addLast(new long[] {sale.soldAt(), Double.doubleToLongBits(sale.unitPrice())});
        while (market.sales.size() > MAX_SALES_PER_MARKET) market.sales.removeFirst();
        return true;
    }

    public PriceStats stats(String key, long now, long windowMillis) {
        Market market = markets.get(key);
        if (market == null) return PriceStats.NONE;
        List<long[]> recent = market.sales.stream().filter(sale -> now - sale[0] <= windowMillis).toList();
        double[] units = new double[recent.size()];
        long[] times = new long[recent.size()];
        for (int i = 0; i < recent.size(); i++) {
            times[i] = recent.get(i)[0];
            units[i] = Double.longBitsToDouble(recent.get(i)[1]);
        }
        return PriceStats.of(units, times, now);
    }

    public void prune(long now, long windowMillis) {
        markets.values().forEach(market -> market.sales.removeIf(sale -> now - sale[0] > windowMillis));
        markets.values().removeIf(market -> market.sales.isEmpty());
    }

    public int markets() { return markets.size(); }

    public int sales() { return markets.values().stream().mapToInt(market -> market.sales.size()).sum(); }

    public String name(String key) {
        Market market = markets.get(key);
        return market == null ? key : market.name;
    }

    /** The busiest markets first. */
    public List<String> busiest(int limit) {
        return markets.entrySet().stream()
                .sorted(Comparator.comparingInt((Map.Entry<String, Market> entry) -> entry.getValue().sales.size()).reversed())
                .limit(limit).map(Map.Entry::getKey).toList();
    }

    public void save(Path path) throws IOException {
        Map<String, Object> markets = new LinkedHashMap<>();
        this.markets.forEach((key, market) -> {
            List<double[]> sales = new ArrayList<>();
            for (long[] sale : market.sales) sales.add(new double[] {sale[0], Double.longBitsToDouble(sale[1])});
            markets.put(key, Map.of("name", market.name, "sales", sales));
        });
        List<String> seen = new ArrayList<>(this.seen);
        Map<String, Object> root = Map.of("version", 1, "markets", markets,
                "seen", seen.subList(Math.max(0, seen.size() - 5_000), seen.size()));
        Files.createDirectories(path.getParent());
        Path temp = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(temp, GSON.toJson(root));
        Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** A missing or unreadable file starts an empty book; the history is only a cache of API data. */
    public static PriceBook load(Path path) {
        PriceBook book = new PriceBook();
        if (!Files.exists(path)) return book;
        try {
            Saved saved = GSON.fromJson(Files.readString(path), Saved.class);
            if (saved == null || saved.markets == null) return book;
            saved.markets.forEach((key, market) -> {
                if (market == null || market.sales == null) return;
                Market loaded = new Market();
                loaded.name = market.name == null ? key : market.name;
                for (double[] sale : market.sales) {
                    if (sale != null && sale.length == 2 && Double.isFinite(sale[1]) && sale[1] > 0) {
                        loaded.sales.addLast(new long[] {(long) sale[0], Double.doubleToLongBits(sale[1])});
                    }
                }
                if (!loaded.sales.isEmpty()) book.markets.put(key, loaded);
            });
            if (saved.seen != null) book.seen.addAll(saved.seen);
        } catch (IOException | RuntimeException ignored) {
            return new PriceBook();
        }
        return book;
    }

    private static final class Saved {
        Map<String, SavedMarket> markets;
        List<String> seen;
    }

    private static final class SavedMarket {
        String name;
        List<double[]> sales;
    }
}
