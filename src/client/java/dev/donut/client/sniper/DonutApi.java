package dev.donut.client.sniper;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/** The DonutSMP public API (https://api.donutsmp.net), limited to 250 requests a minute per key. */
public final class DonutApi {
    public static final String BASE = "https://api.donutsmp.net/v1";
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final String base;

    public DonutApi() { this(BASE); }

    DonutApi(String base) { this.base = base; }

    public static final class ApiException extends RuntimeException {
        public final int status;
        ApiException(int status, String message) { super(message); this.status = status; }
    }

    /** @param sort lowest_price, highest_price, recently_listed or last_listed */
    public CompletableFuture<List<MarketListing>> listings(String key, int page, String search, String sort) {
        JsonObject body = new JsonObject();
        if (search != null && !search.isBlank()) body.addProperty("search", search);
        if (sort != null) body.addProperty("sort", sort);
        return get(key, "/auction/list/" + page, body.toString(), DonutApi::parseListings);
    }

    /** Pages 1 to 10, 100 sales each, newest first. */
    public CompletableFuture<List<MarketSale>> transactions(String key, int page) {
        return get(key, "/auction/transactions/" + page, null, DonutApi::parseSales);
    }

    private <T> CompletableFuture<T> get(String key, String path, String body, Function<String, T> parser) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + key)
                .header("Accept", "application/json");
        if (body != null) {
            // The API takes its search and sort options as a GET body.
            request.header("Content-Type", "application/json").method("GET", HttpRequest.BodyPublishers.ofString(body));
        } else {
            request.GET();
        }
        return http.sendAsync(request.build(), HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
            if (response.statusCode() == 401) throw new ApiException(401, "API key rejected; get one in game with /api");
            if (response.statusCode() == 429) throw new ApiException(429, "rate limited by the API");
            if (response.statusCode() != 200) throw new ApiException(response.statusCode(), "API error " + response.statusCode());
            return parser.apply(response.body());
        });
    }

    static List<MarketListing> parseListings(String json) {
        List<MarketListing> listings = new ArrayList<>();
        for (JsonElement element : results(json)) {
            if (!element.isJsonObject()) continue;
            JsonObject entry = element.getAsJsonObject();
            MarketItem item = item(entry.get("item"));
            if (item == null) continue;
            listings.add(new MarketListing(item, number(entry, "price"), seller(entry), (long) number(entry, "time_left")));
        }
        return listings;
    }

    static List<MarketSale> parseSales(String json) {
        List<MarketSale> sales = new ArrayList<>();
        for (JsonElement element : results(json)) {
            if (!element.isJsonObject()) continue;
            JsonObject entry = element.getAsJsonObject();
            MarketItem item = item(entry.get("item"));
            if (item == null) continue;
            sales.add(new MarketSale(item, number(entry, "price"), seller(entry), (long) number(entry, "unixMillisDateSold")));
        }
        return sales;
    }

    private static JsonArray results(String json) {
        JsonElement root = JsonParser.parseString(json);
        if (!root.isJsonObject()) return new JsonArray();
        JsonElement result = root.getAsJsonObject().get("result");
        return result != null && result.isJsonArray() ? result.getAsJsonArray() : new JsonArray();
    }

    private static MarketItem item(JsonElement element) {
        if (element == null || !element.isJsonObject()) return null;
        JsonObject item = element.getAsJsonObject();
        Map<String, Integer> enchants = new HashMap<>();
        String trim = "";
        JsonObject data = object(item, "enchants");
        if (data != null) {
            JsonObject levels = object(object(data, "enchantments"), "levels");
            if (levels != null) {
                levels.entrySet().forEach(entry -> {
                    if (entry.getValue().isJsonPrimitive()) enchants.put(entry.getKey(), entry.getValue().getAsInt());
                });
            }
            JsonObject trimData = object(data, "trim");
            if (trimData != null) trim = text(trimData, "material") + ":" + text(trimData, "pattern");
        }
        List<String> lore = new ArrayList<>();
        JsonElement loreElement = item.get("lore");
        if (loreElement != null && loreElement.isJsonArray()) {
            loreElement.getAsJsonArray().forEach(line -> { if (line.isJsonPrimitive()) lore.add(line.getAsString()); });
        }
        JsonElement contents = item.get("contents");
        boolean hasContents = contents != null && contents.isJsonArray() && !contents.getAsJsonArray().isEmpty();
        return new MarketItem(text(item, "id"), text(item, "display_name"), (int) number(item, "count"),
                enchants, trim.equals(":") ? "" : trim, lore, hasContents);
    }

    private static String seller(JsonObject entry) {
        JsonObject seller = object(entry, "seller");
        return seller == null ? "" : text(seller, "name");
    }

    private static JsonObject object(JsonObject parent, String name) {
        if (parent == null) return null;
        JsonElement element = parent.get(name);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    private static String text(JsonObject parent, String name) {
        JsonElement element = parent.get(name);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : "";
    }

    private static double number(JsonObject parent, String name) {
        JsonElement element = parent.get(name);
        try {
            return element != null && element.isJsonPrimitive() ? element.getAsDouble() : 0;
        } catch (NumberFormatException error) {
            return 0;
        }
    }
}
