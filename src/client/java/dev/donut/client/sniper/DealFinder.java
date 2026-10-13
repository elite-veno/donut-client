package dev.donut.client.sniper;

import dev.donut.client.config.SniperSettings;

/**
 * Decides whether a listing is a deal. A deal is unusually cheap compared with what the item really
 * sells for, never merely cheaper than other asking prices, and it must still pay after tax when resold
 * just under the cheapest competing listing.
 */
public final class DealFinder {
    private DealFinder() { }

    public record Decision(boolean buy, String reason, double z, double resaleUnit, double profit, double margin) {
        static Decision skip(String reason, double z) { return new Decision(false, reason, z, Double.NaN, Double.NaN, Double.NaN); }
    }

    /**
     * @param competitorUnit the cheapest other listing's unit price for the same item, or NaN when there is none
     * @param exposure the total paid for items bought and not yet sold back
     */
    public static Decision evaluate(MarketListing listing, PriceStats stats, double competitorUnit,
                                    SniperSettings settings, double exposure, String me) {
        if (!listing.item().tradable()) return Decision.skip("container or empty item", 0);
        if (listing.seller() != null && listing.seller().equalsIgnoreCase(me)) return Decision.skip("own listing", 0);
        if (!(listing.price() > 0)) return Decision.skip("no price", 0);
        if (stats.sales() < settings.minSales) return Decision.skip("only " + stats.sales() + " sales on record", 0);
        if (stats.salesPerDay() < settings.minSalesPerDay) return Decision.skip("sells too rarely to resell", 0);

        double unit = listing.unitPrice();
        double z = stats.discountZ(unit);
        // Expensive listings are never bought, however unusual their price.
        if (unit >= stats.value()) return Decision.skip("at or above the usual price", z);
        if (z < settings.minDeviation) return Decision.skip("within the usual spread", z);

        // Resell at the current value, or just under a cheaper competitor so ours sells first.
        double resaleUnit = stats.value();
        if (Double.isFinite(competitorUnit)) resaleUnit = Math.min(resaleUnit, competitorUnit * (1 - settings.undercutPercent / 100));
        double revenue = resaleUnit * listing.item().count() * (1 - settings.taxPercent / 100);
        double profit = revenue - listing.price();
        double margin = profit / listing.price();
        if (listing.price() > settings.maxPrice) return new Decision(false, "over the price limit", z, resaleUnit, profit, margin);
        if (exposure + listing.price() > settings.maxExposure) {
            return new Decision(false, "would exceed the exposure limit", z, resaleUnit, profit, margin);
        }
        if (profit < settings.minProfit) return new Decision(false, "profit too small", z, resaleUnit, profit, margin);
        if (margin < settings.minMargin) return new Decision(false, "margin too small", z, resaleUnit, profit, margin);
        return new Decision(true, "deal", z, resaleUnit, profit, margin);
    }
}
