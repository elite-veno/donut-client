package dev.donut.client.sniper;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** DonutSMP writes money as "$ 37.9K"; abbreviated amounts are only known to within their last digit. */
public final class Money {
    private static final Pattern AMOUNT = Pattern.compile("\\$\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*([kKmMbBtT])?(?![a-zA-Z])");
    private static final Pattern PLAIN = Pattern.compile("\\s*\\$?\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*([kKmMbBtT])?\\s*");

    private Money() { }

    /** An amount and how far the true value can be from it because of rounding in the text. */
    public record Amount(double value, double tolerance) {
        public boolean matches(double exact) { return Math.abs(exact - value) <= tolerance + 1e-6; }
    }

    /** The first dollar amount in a chat message or lore line, or null. */
    public static Amount find(String text) {
        Matcher matcher = AMOUNT.matcher(text);
        return matcher.find() ? amount(matcher.group(1), matcher.group(2)) : null;
    }

    /** A typed amount such as "1.5M", "250k" or "12,000", or NaN. */
    public static double parse(String text) {
        if (text == null) return Double.NaN;
        Matcher matcher = PLAIN.matcher(text);
        return matcher.matches() ? amount(matcher.group(1), matcher.group(2)).value : Double.NaN;
    }

    private static Amount amount(String digits, String suffix) {
        String number = digits.replace(",", "");
        double multiplier = suffix == null ? 1 : switch (Character.toLowerCase(suffix.charAt(0))) {
            case 'k' -> 1e3;
            case 'm' -> 1e6;
            case 'b' -> 1e9;
            default -> 1e12;
        };
        int dot = number.indexOf('.');
        int decimals = dot < 0 ? 0 : number.length() - dot - 1;
        double value = Double.parseDouble(number) * multiplier;
        // "37.9K" covers 37,850 up to 37,950; an exact "37,900" still allows for cents.
        double tolerance = suffix == null ? 0.5 : 0.5 * Math.pow(10, -decimals) * multiplier;
        return new Amount(value, tolerance);
    }

    /** Short form for the GUI: $950, $37.9K, $1.25M. */
    public static String format(double value) {
        if (!Double.isFinite(value)) return "-";
        double abs = Math.abs(value);
        String sign = value < 0 ? "-" : "";
        if (abs < 1_000) return sign + "$" + Math.round(abs);
        String[] units = {"K", "M", "B", "T"};
        int unit = (int) Math.min(units.length, Math.floor(Math.log10(abs) / 3));
        double scaled = abs / Math.pow(1000, unit);
        String digits = scaled >= 100 ? String.format(Locale.ROOT, "%.0f", scaled)
                : scaled >= 10 ? String.format(Locale.ROOT, "%.1f", scaled) : String.format(Locale.ROOT, "%.2f", scaled);
        return sign + "$" + digits + units[unit - 1];
    }
}
