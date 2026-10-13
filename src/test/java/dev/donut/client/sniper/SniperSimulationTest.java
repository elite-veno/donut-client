package dev.donut.client.sniper;

import static org.junit.jupiter.api.Assertions.*;

import dev.donut.client.config.SniperSettings;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Simulated markets: the value drifts, buyers pay around it, sellers mostly ask a little above it, and now
 * and then someone dumps far below it. The sniper only sees past sales and the live listings, as in game.
 * A trade sells when a later buyer pays at least its resale price within two days; otherwise it is dumped
 * at 85% of the value then.
 */
class SniperSimulationTest {
    private static final long MINUTE = 60_000, HOUR = 60 * MINUTE, DAY = 24 * HOUR;

    record Result(int buys, int sold, double spent, double profit) { }

    private static Result simulate(long seed, double crashAtDay) {
        Random random = new Random(seed);
        SniperSettings settings = new SniperSettings();
        settings.minProfit = 0;
        settings.maxExposure = Double.MAX_VALUE;
        settings.maxPrice = Double.MAX_VALUE;
        MarketItem item = MarketDataTest.item("diamond", "Diamond", 1);
        long end = 7 * DAY;
        // Value path, one point a minute.
        double[] value = new double[(int) (end / MINUTE) + 1];
        value[0] = 1_000;
        for (int i = 1; i < value.length; i++) {
            double day = i * MINUTE / (double) DAY;
            double drift = day >= crashAtDay && day < crashAtDay + 0.5 ? Math.log(0.6) / (0.5 * 24 * 60) : 0;
            value[i] = value[i - 1] * Math.exp(drift + random.nextGaussian() * 0.05 / Math.sqrt(24 * 60));
        }
        List<long[]> sales = new ArrayList<>();
        List<Double> salePrices = new ArrayList<>();
        int buys = 0, sold = 0;
        double spent = 0, profit = 0;
        for (long t = 0; t < end - 2 * DAY; t += MINUTE) {
            double v = value[(int) (t / MINUTE)];
            if (random.nextDouble() < 3.0 / 60) {
                sales.add(new long[] {t});
                salePrices.add(v * Math.exp(random.nextGaussian() * 0.04));
            }
            if (random.nextDouble() >= 6.0 / 60 || t < DAY) continue;
            // A new listing: usually a little over the value, sometimes a fire sale.
            double ask = random.nextDouble() < 0.05 ? v * (0.45 + random.nextDouble() * 0.35)
                    : v * Math.exp(0.06 + random.nextGaussian() * 0.06);
            double competitor = Double.POSITIVE_INFINITY;
            for (int k = 0; k < 8; k++) competitor = Math.min(competitor, v * Math.exp(0.06 + random.nextGaussian() * 0.06));
            PriceStats stats = stats(sales, salePrices, t, settings);
            DealFinder.Decision decision = DealFinder.evaluate(new MarketListing(item, ask, "Seller", 1), stats,
                    competitor, settings, 0, "Me");
            if (!decision.buy()) continue;
            buys++;
            spent += ask;
            // Future buyers come from the same process; the first one paying our price takes it.
            double revenue = Double.NaN;
            Random future = new Random(seed * 31 + t);
            for (long u = t + MINUTE; u < t + 2 * DAY; u += MINUTE) {
                if (future.nextDouble() < 3.0 / 60
                        && value[(int) (u / MINUTE)] * Math.exp(future.nextGaussian() * 0.04) >= decision.resaleUnit()) {
                    revenue = decision.resaleUnit();
                    sold++;
                    break;
                }
            }
            if (Double.isNaN(revenue)) revenue = 0.85 * value[(int) ((t + 2 * DAY) / MINUTE)];
            profit += revenue * (1 - settings.taxPercent / 100) - ask;
        }
        return new Result(buys, sold, spent, profit);
    }

    private static PriceStats stats(List<long[]> sales, List<Double> prices, long now, SniperSettings settings) {
        long window = (long) (settings.historyDays * DAY);
        int from = sales.size();
        while (from > 0 && now - sales.get(from - 1)[0] <= window) from--;
        double[] units = new double[sales.size() - from];
        long[] times = new long[units.length];
        for (int i = from; i < sales.size(); i++) {
            units[i - from] = prices.get(i);
            times[i - from] = sales.get(i)[0];
        }
        return PriceStats.of(units, times, now);
    }

    @Test
    void steadyMarketsTurnFireSalesIntoProfit() {
        int buys = 0, sold = 0;
        double spent = 0, profit = 0;
        for (long seed = 1; seed <= 30; seed++) {
            Result result = simulate(seed, Double.POSITIVE_INFINITY);
            buys += result.buys();
            sold += result.sold();
            spent += result.spent();
            profit += result.profit();
        }
        assertTrue(buys > 100, "fire sales should be found: " + buys);
        assertTrue(sold >= 0.9 * buys, sold + " of " + buys + " resold at their planned price");
        assertTrue(profit / spent > 0.15, "return on money spent: " + profit / spent);
    }

    @Test
    void aCrashDoesNotTurnTheSniperIntoABuyerOfFallingItems() {
        double spent = 0, profit = 0;
        int buys = 0, sold = 0;
        for (long seed = 1; seed <= 30; seed++) {
            Result result = simulate(seed, 3);
            buys += result.buys();
            sold += result.sold();
            spent += result.spent();
            profit += result.profit();
        }
        assertTrue(profit > 0, "a 40% crash must not wipe out the gains: " + profit + " on " + spent);
        assertTrue(sold >= 0.8 * buys, sold + " of " + buys + " resold");
    }
}
