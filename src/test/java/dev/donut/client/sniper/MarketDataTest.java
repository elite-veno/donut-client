package dev.donut.client.sniper;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MarketDataTest {
    private static final long HOUR = 3_600_000L;
    @TempDir Path directory;

    static MarketItem item(String id, String name, int count) {
        return new MarketItem(id, name, count, Map.of(), "", List.of(), false);
    }

    @Test
    void itemsFromDifferentSpellingsShareOneMarket() {
        MarketItem a = new MarketItem("minecraft:diamond_sword", "§bDiamond Sword", 1,
                Map.of("sharpness", 5, "minecraft:unbreaking", 3), "", List.of(), false);
        MarketItem b = new MarketItem("DIAMOND_SWORD", "Diamond Sword", 1,
                Map.of("minecraft:unbreaking", 3, "minecraft:sharpness", 5), "", List.of(), false);
        assertEquals(a.key(), b.key());
        assertNotEquals(a.key(), item("diamond_sword", "Diamond Sword", 1).key(), "enchantments set the value");
        assertNotEquals(item("spawner", "Skeleton Spawner", 1).key(), item("spawner", "Zombie Spawner", 1).key());
        assertEquals(item("diamond", "", 1).key(), item("diamond", "", 64).key(), "stack size is priced per item");
        assertFalse(new MarketItem("shulker_box", "", 1, Map.of(), "", List.of(), true).tradable());
    }

    @Test
    void apiResponsesAreParsedIntoListingsAndSales() {
        String listings = """
                {"status":200,"result":[
                  {"item":{"id":"minecraft:diamond","count":16,"display_name":"Diamond","lore":[],
                    "enchants":{"enchantments":{"levels":{}}}},"price":32000,"seller":{"name":"Notch","uuid":"x"},"time_left":3600},
                  {"item":{"id":"minecraft:netherite_sword","count":1,"display_name":"Netherite Sword",
                    "enchants":{"enchantments":{"levels":{"minecraft:sharpness":5}},"trim":{"material":"","pattern":""}}},
                    "price":"1.5E6","seller":{"name":"jeb_"},"time_left":10},
                  {"broken":true}, 5
                ]}""";
        List<MarketListing> parsed = DonutApi.parseListings(listings);
        assertEquals(2, parsed.size());
        assertEquals("diamond", parsed.get(0).item().id());
        assertEquals(2_000, parsed.get(0).unitPrice());
        assertEquals("Notch", parsed.get(0).seller());
        assertEquals(Map.of("minecraft:sharpness", 5), parsed.get(1).item().enchants());
        assertEquals(1_500_000, parsed.get(1).price());

        String sales = """
                {"status":200,"result":[{"item":{"id":"diamond","count":2,"display_name":"Diamond"},
                  "price":4100,"seller":{"name":"Notch"},"unixMillisDateSold":1700000000000}]}""";
        MarketSale sale = DonutApi.parseSales(sales).getFirst();
        assertEquals(2_050, sale.unitPrice());
        assertEquals(1_700_000_000_000L, sale.soldAt());
        assertTrue(DonutApi.parseSales("{\"status\":500}").isEmpty());
        assertTrue(DonutApi.parseListings("[]").isEmpty());
    }

    @Test
    void robustStatisticsIgnoreSillyPrices() {
        double[] units = {100, 102, 98, 101, 99, 100, 1_000_000};
        long[] times = new long[units.length];
        for (int i = 0; i < times.length; i++) times[i] = i * HOUR;
        PriceStats stats = PriceStats.of(units, times, 7 * HOUR);
        assertEquals(100, stats.median());
        assertTrue(stats.sigma() < 5, "one absurd sale must not blow up the spread: " + stats.sigma());
        assertEquals(24, stats.salesPerDay(), 1e-9, "seven sales over seven hours");
        assertEquals(4, stats.discountZ(100 - 4 * stats.sigma()), 1e-9);

        PriceStats flat = PriceStats.of(new double[] {50, 50, 50, 50}, new long[] {0, 1, 2, 3}, HOUR);
        assertEquals(1, flat.sigma(), 1e-9, "a flat market still gets a 2% spread");
    }

    @Test
    void theRecentMedianValuesAFallingMarketAtItsNewLevel() {
        double[] units = new double[30];
        long[] times = new long[30];
        for (int i = 0; i < 30; i++) {
            units[i] = i < 20 ? 100 : 60;
            times[i] = i * HOUR;
        }
        PriceStats stats = PriceStats.of(units, times, 30 * HOUR);
        assertEquals(100, stats.median());
        assertEquals(60, stats.recentMedian());
        assertEquals(60, stats.value());
    }

    @Test
    void theBookSkipsRepeatedSalesAndSurvivesARestart() throws IOException {
        PriceBook book = new PriceBook();
        MarketSale sale = new MarketSale(item("diamond", "Diamond", 4), 800, "Notch", 1_000);
        assertTrue(book.add(sale));
        assertFalse(book.add(sale), "transaction pages overlap from poll to poll");
        assertTrue(book.add(new MarketSale(item("diamond", "Diamond", 1), 210, "jeb_", 2_000)));
        assertFalse(book.add(new MarketSale(new MarketItem("shulker_box", "", 1, Map.of(), "", List.of(), true), 5, "x", 3)));
        assertEquals(1, book.markets());
        assertEquals(2, book.sales());
        assertEquals(205, book.stats(sale.item().key(), 3_000, HOUR).median(), 1e-9, "unit prices of both stack sizes");

        Path path = directory.resolve("market.json");
        book.save(path);
        PriceBook loaded = PriceBook.load(path);
        assertEquals(2, loaded.sales());
        assertFalse(loaded.add(sale), "seen sales are remembered across restarts");
        assertEquals(205, loaded.stats(sale.item().key(), 3_000, HOUR).median(), 1e-9);

        Files.writeString(path, "{not json");
        assertEquals(0, PriceBook.load(path).sales());
        book.prune(2 * HOUR + 1_000, HOUR);
        assertEquals(0, book.markets());
    }
}
