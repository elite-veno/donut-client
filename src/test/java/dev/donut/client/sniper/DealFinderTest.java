package dev.donut.client.sniper;

import static org.junit.jupiter.api.Assertions.*;

import dev.donut.client.config.SniperSettings;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DealFinderTest {
    private static final long HOUR = 3_600_000L;
    private final SniperSettings settings = new SniperSettings();

    /** Forty sales around 1,000 a piece over two days, spread 990 to 1,010. */
    private static PriceStats market() {
        double[] units = new double[40];
        long[] times = new long[40];
        for (int i = 0; i < 40; i++) {
            units[i] = 990 + (i % 5) * 5;
            times[i] = i * (48 * HOUR / 40);
        }
        return PriceStats.of(units, times, 48 * HOUR);
    }

    private static MarketListing listing(double total, int count) {
        return new MarketListing(MarketDataTest.item("diamond", "Diamond", count), total, "Seller", 3_600);
    }

    private DealFinder.Decision judge(MarketListing listing, double competitor) {
        return DealFinder.evaluate(listing, market(), competitor, settings, 0, "Me");
    }

    @Test
    void aDeepDiscountWithRoomToResellIsADeal() {
        DealFinder.Decision decision = judge(listing(16 * 600, 16), Double.NaN);
        assertTrue(decision.buy(), decision.reason());
        assertEquals(1_000, decision.resaleUnit(), 1e-9);
        assertEquals(16 * 1_000 * 0.95 - 16 * 600, decision.profit(), 1e-6);
        assertTrue(decision.z() > settings.minDeviation);
    }

    @Test
    void neverBuysAtOrAboveTheUsualPriceHoweverUnusual() {
        DealFinder.Decision decision = judge(listing(5_000, 1), Double.NaN);
        assertFalse(decision.buy());
        assertEquals("at or above the usual price", decision.reason());
        assertTrue(decision.z() < 0, "expensive listings deviate upward");
    }

    @Test
    void anOrdinaryDiscountIsWithinTheSpread() {
        DealFinder.Decision decision = judge(listing(985, 1), Double.NaN);
        assertFalse(decision.buy());
        assertEquals("within the usual spread", decision.reason());
    }

    @Test
    void aCheapCompetitorCapsTheResaleAndCanKillTheDeal() {
        settings.minProfit = 0;
        DealFinder.Decision capped = judge(listing(600, 1), 800);
        assertTrue(capped.buy(), capped.reason());
        assertEquals(792, capped.resaleUnit(), 1e-9, "one percent under the competitor");
        DealFinder.Decision killed = judge(listing(600, 1), 640);
        assertFalse(killed.buy());
        assertEquals("margin too small", killed.reason());
    }

    @Test
    void taxProfitAndMarginThresholdsApply() {
        settings.minProfit = 5_000;
        assertEquals("profit too small", judge(listing(600, 1), Double.NaN).reason(), "a 400 gain on one diamond");
        settings.minProfit = 0;
        settings.taxPercent = 40;
        assertEquals("margin too small", judge(listing(600, 1), Double.NaN).reason(), "tax eats the gain");
    }

    @Test
    void limitsAndThinMarketsBlockBuying() {
        settings.minProfit = 0;
        settings.maxPrice = 500;
        assertEquals("over the price limit", judge(listing(600, 1), Double.NaN).reason());
        settings.maxPrice = 1e9;
        settings.maxExposure = 1_000;
        assertEquals("would exceed the exposure limit",
                DealFinder.evaluate(listing(600, 1), market(), Double.NaN, settings, 500, "Me").reason());
        settings.maxExposure = 1e9;
        settings.minSales = 50;
        assertTrue(judge(listing(600, 1), Double.NaN).reason().startsWith("only 40 sales"));
        settings.minSales = 8;
        settings.minSalesPerDay = 100;
        assertEquals("sells too rarely to resell", judge(listing(600, 1), Double.NaN).reason());
    }

    @Test
    void ownListingsAndContainersAreIgnored() {
        assertEquals("own listing", DealFinder.evaluate(new MarketListing(MarketDataTest.item("diamond", "Diamond", 1), 100,
                "me", 1), market(), Double.NaN, settings, 0, "Me").reason());
        MarketListing shulker = new MarketListing(new MarketItem("shulker_box", "", 1, Map.of(), "", List.of(), true), 1, "x", 1);
        assertFalse(DealFinder.evaluate(shulker, market(), Double.NaN, settings, 0, "Me").buy());
    }

    @Test
    void theLedgerSellsPaperTradesToLaterBuyersAndLiveOnesOnlyFromOurListing() {
        Ledger ledger = new Ledger();
        MarketListing cheap = listing(600, 1);
        Ledger.Position paper = ledger.open(false, cheap, 1_000, 5, 1_000);
        Ledger.Position live = ledger.open(true, cheap, 1_000, 5, 1_000);
        live.state = Ledger.State.LISTED;
        live.listedPrice = 1_000;
        assertEquals(1_200, ledger.exposure(false) + ledger.exposure(true));

        assertNull(ledger.onSale(new MarketSale(cheap.item(), 900, "Other", 2_000), "Me"), "too cheap for either");
        assertNull(ledger.onSale(new MarketSale(cheap.item(), 1_000, "Other", 500), "Me"), "sold before the trade");
        assertSame(paper, ledger.onSale(new MarketSale(cheap.item(), 1_000, "Other", 3_000), "Me"));
        assertEquals(1_000 * 0.95 - 600, paper.realized, 1e-9);
        assertNull(ledger.onSale(new MarketSale(cheap.item(), 1_000, "Other", 4_000), "Me"), "someone else's sale");
        assertSame(live, ledger.onSale(new MarketSale(cheap.item(), 1_000, "me", 5_000), "Me"));
        assertEquals(2 * (1_000 * 0.95 - 600), ledger.realized(false) + ledger.realized(true), 1e-9);
        assertEquals(0, ledger.exposure(false));

        Ledger.Position stale = ledger.open(false, cheap, 1_000, 5, 10_000);
        ledger.expire(10_000 + Ledger.PAPER_EXPIRY + 1);
        assertEquals(Ledger.State.UNSOLD, stale.state);
    }
}
