package dev.donut.client.sniper;

import java.util.Arrays;

/**
 * Robust statistics over unit sale prices. The median and the median absolute deviation ignore
 * the occasional sale at a silly price, which would wreck a mean and a standard deviation.
 */
public record PriceStats(int sales, double median, double sigma, double salesPerDay, long newest, double recentMedian) {
    public static final PriceStats NONE = new PriceStats(0, Double.NaN, Double.NaN, 0, 0, Double.NaN);
    /** The latest sales show where a moving market is now; the full window lags behind a fall. */
    static final int RECENT = 10;
    /** Flat markets would otherwise make any tiny discount look like a huge deviation. */
    static final double MIN_RELATIVE_SIGMA = 0.02;
    private static final double MAD_TO_SIGMA = 1.4826;
    private static final long HOUR = 3_600_000L;

    /** @param times sale times in milliseconds, matching {@code units} */
    public static PriceStats of(double[] units, long[] times, long now) {
        if (units.length == 0) return NONE;
        double median = median(units.clone());
        double[] deviations = new double[units.length];
        for (int i = 0; i < units.length; i++) deviations[i] = Math.abs(units[i] - median);
        double sigma = Math.max(MAD_TO_SIGMA * median(deviations), MIN_RELATIVE_SIGMA * median);
        long oldest = Long.MAX_VALUE, newest = Long.MIN_VALUE;
        for (long time : times) {
            oldest = Math.min(oldest, time);
            newest = Math.max(newest, time);
        }
        // A short history must not turn three quick sales into hundreds a day.
        double spanHours = Math.max(1, (now - oldest) / (double) HOUR);
        Integer[] order = new Integer[units.length];
        for (int i = 0; i < order.length; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> Long.compare(times[b], times[a]));
        double[] recent = new double[Math.min(RECENT, units.length)];
        for (int i = 0; i < recent.length; i++) recent[i] = units[order[i]];
        return new PriceStats(units.length, median, sigma, units.length * 24 / spanHours, newest, median(recent));
    }

    /**
     * What the item is worth now: the lower of the long-run and the recent median, so a falling
     * market is valued at its new level while a brief spike up is ignored.
     */
    public double value() { return Math.min(median, recentMedian); }

    /** How many sigmas below the current value a unit price is; positive means cheaper than usual. */
    public double discountZ(double unit) {
        return sales == 0 || !(sigma > 0) ? 0 : (value() - unit) / sigma;
    }

    static double median(double[] values) {
        Arrays.sort(values);
        int middle = values.length / 2;
        return values.length % 2 == 1 ? values[middle] : (values[middle - 1] + values[middle]) / 2;
    }
}
