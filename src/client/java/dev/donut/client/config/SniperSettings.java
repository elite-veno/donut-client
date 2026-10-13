package dev.donut.client.config;

public final class SniperSettings {
    /** The sniper never starts on its own after joining. */
    public transient boolean enabled = false;
    /** Real purchases; every session starts in paper mode. */
    public transient boolean live = false;
    /** From the in-game /api command. */
    public String apiKey = "";
    /** How many robust standard deviations below the median a listing must be. */
    public double minDeviation = 2.5;
    /** Profit after tax as a share of the purchase price. */
    public double minMargin = 0.15;
    public double minProfit = 5_000;
    public double maxPrice = 1_000_000;
    /** The total paid for items not yet sold back. */
    public double maxExposure = 5_000_000;
    public double taxPercent = 5;
    /** How far under the cheapest competing listing the resale goes. */
    public double undercutPercent = 1;
    public int minSales = 8;
    public double minSalesPerDay = 2;
    public double historyDays = 7;
    public double pollSeconds = 3;
    public int maxTradesPerHour = 20;
    /** {item} becomes the item's name. */
    public String searchCommand = "ah {item}";
    /** {price} becomes the whole stack's price. */
    public String sellCommand = "ah sell {price}";
    public String confirmText = "confirm";

    public void sanitize() {
        apiKey = apiKey == null ? "" : apiKey.strip();
        minDeviation = clamp(minDeviation, 1, 6, 2.5);
        minMargin = clamp(minMargin, 0.02, 2, 0.15);
        minProfit = clamp(minProfit, 0, 1e12, 5_000);
        maxPrice = clamp(maxPrice, 1, 1e13, 1_000_000);
        maxExposure = clamp(maxExposure, 1, 1e14, 5_000_000);
        taxPercent = clamp(taxPercent, 0, 50, 5);
        undercutPercent = clamp(undercutPercent, 0, 20, 1);
        minSales = Math.clamp(minSales, 3, 200);
        minSalesPerDay = clamp(minSalesPerDay, 0, 1_000, 2);
        historyDays = clamp(historyDays, 1, 30, 7);
        pollSeconds = clamp(pollSeconds, 2, 60, 3);
        maxTradesPerHour = Math.clamp(maxTradesPerHour, 1, 200);
        searchCommand = command(searchCommand, "ah {item}");
        sellCommand = command(sellCommand, "ah sell {price}");
        confirmText = confirmText == null || confirmText.isBlank() ? "confirm" : confirmText.strip();
    }

    private static String command(String value, String fallback) {
        if (value == null) return fallback;
        String stripped = value.strip();
        while (stripped.startsWith("/")) stripped = stripped.substring(1).strip();
        return stripped.isEmpty() ? fallback : stripped;
    }

    private static double clamp(double value, double min, double max, double fallback) {
        return Double.isFinite(value) ? Math.clamp(value, min, max) : fallback;
    }
}
