package dev.donut.client.sniper;

import dev.donut.client.config.ClientConfig;
import dev.donut.client.config.SniperSettings;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;

/**
 * The Auction House sniper. Real sale prices come from the API's transaction history; new listings are
 * polled and judged by {@link DealFinder}, then a deal is checked against the cheapest competing listing
 * before it is bought, on paper or for real.
 */
public final class SniperEngine implements LiveTrader.Listener {
    /** The API keeps the last 1,000 sales in ten pages. */
    private static final int SALE_PAGES = 10;
    private static final long SALES_INTERVAL = 30_000, SAVE_INTERVAL = 5 * 60_000, ERROR_BACKOFF = 15_000;
    private static final int LOG_LINES = 60, MAX_SEEN_LISTINGS = 5_000;
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final ClientConfig config;
    private final DonutApi api;
    private final Path bookPath;
    private final PriceBook book;
    private final Ledger ledger = new Ledger();
    private final LiveTrader trader;
    private final Queue<Runnable> inbox = new ConcurrentLinkedQueue<>();
    private final LinkedHashSet<String> seenListings = new LinkedHashSet<>();
    private final ArrayDeque<MarketListing> candidates = new ArrayDeque<>();
    private final ArrayDeque<String> log = new ArrayDeque<>();
    private boolean running, salesInFlight, listingsInFlight, competitorInFlight;
    private int backfillPage;
    private long nextSales, nextListings, nextSave, nextExpire;
    private String status = "Stopped";
    private int listingsChecked, deals;
    private String me = "";
    private Minecraft client;
    /** The trade in progress in live mode. */
    private double pendingResale;
    private Ledger.Position livePosition;

    public SniperEngine(ClientConfig config, DonutApi api, Path bookPath) {
        this.config = config;
        this.api = api;
        this.bookPath = bookPath;
        this.book = PriceBook.load(bookPath);
        this.trader = new LiveTrader(config.sniper, this);
    }

    private SniperSettings settings() { return config.sniper; }

    public void tick(Minecraft client) {
        this.client = client;
        for (Runnable task; (task = inbox.poll()) != null; ) task.run();
        trader.tick(client);
        if (!settings().enabled) {
            if (running) stop("Stopped");
            return;
        }
        if (client.player == null) { settings().enabled = false; stop("Join a server to start"); return; }
        if (settings().apiKey.isBlank()) { status = "Enter an API key from /api in Settings"; return; }
        if (!running) start(client);

        long now = System.currentTimeMillis();
        if (!salesInFlight && (backfillPage > 0 || now >= nextSales)) requestSales(now);
        // Listings are judged against the sales history, so they wait for the backfill.
        if (backfillPage == 0 && !listingsInFlight && now >= nextListings) requestListings(now);
        if (!competitorInFlight && trader.idle() && !candidates.isEmpty()) checkCompetitors(candidates.poll());
        if (now >= nextExpire) { ledger.expire(now); nextExpire = now + 60_000; }
        if (now >= nextSave) { save(); nextSave = now + SAVE_INTERVAL; }
    }

    private void start(Minecraft client) {
        running = true;
        me = client.player.getScoreboardName();
        backfillPage = 1;
        nextSave = System.currentTimeMillis() + SAVE_INTERVAL;
        candidates.clear();
        status = "Loading sales history";
        log("Started in " + (settings().live ? "LIVE" : "paper") + " mode");
    }

    private void stop(String why) {
        running = false;
        candidates.clear();
        if (client != null) trader.cancel(client);
        status = why;
        save();
    }

    public void save() {
        try {
            book.prune(System.currentTimeMillis(), windowMillis());
            book.save(bookPath);
        } catch (Exception error) {
            log("Could not save the price history: " + error.getMessage());
        }
    }

    private long windowMillis() { return (long) (settings().historyDays * 86_400_000L); }

    private void requestSales(long now) {
        salesInFlight = true;
        int page = backfillPage > 0 ? backfillPage : 1;
        api.transactions(settings().apiKey, page).whenComplete((sales, error) -> inbox.add(() -> onSales(page, sales, error)));
    }

    private void onSales(int page, List<MarketSale> sales, Throwable error) {
        salesInFlight = false;
        long now = System.currentTimeMillis();
        if (error != null) { apiError(error); nextSales = now + ERROR_BACKOFF; return; }
        int fresh = 0;
        for (MarketSale sale : sales) {
            if (book.add(sale)) fresh++;
            Ledger.Position sold = ledger.onSale(sale, me);
            if (sold != null) {
                log((sold.live ? "SOLD " : "Paper sold ") + sold.label + " for " + Money.format(sold.realized) + " profit");
                if (sold.live) notify("sold " + sold.label + ": " + Money.format(sold.realized) + " profit", ChatFormatting.GREEN);
            }
        }
        if (backfillPage > 0) {
            // A page with nothing new means the saved history already covers the rest.
            backfillPage = page >= SALE_PAGES || fresh == 0 ? 0 : page + 1;
            status = backfillPage == 0 ? watching() : "Loading sales history (page " + backfillPage + " of " + SALE_PAGES + ")";
        }
        nextSales = now + SALES_INTERVAL;
    }

    private void requestListings(long now) {
        listingsInFlight = true;
        api.listings(settings().apiKey, 1, null, "recently_listed")
                .whenComplete((listings, error) -> inbox.add(() -> onListings(listings, error)));
    }

    private void onListings(List<MarketListing> listings, Throwable error) {
        listingsInFlight = false;
        long now = System.currentTimeMillis();
        if (error != null) { apiError(error); nextListings = now + ERROR_BACKOFF; return; }
        status = watching();
        for (MarketListing listing : listings) {
            if (!seenListings.add(listing.id())) continue;
            if (seenListings.size() > MAX_SEEN_LISTINGS) seenListings.remove(seenListings.iterator().next());
            listingsChecked++;
            PriceStats stats = book.stats(listing.item().key(), now, windowMillis());
            DealFinder.Decision decision = DealFinder.evaluate(listing, stats, Double.NaN, settings(), exposure(), me);
            // The cheap test first; only promising listings cost another request.
            if (decision.buy()) candidates.add(listing);
        }
        nextListings = now + (long) (settings().pollSeconds * 1000);
    }

    private void checkCompetitors(MarketListing listing) {
        competitorInFlight = true;
        api.listings(settings().apiKey, 1, LiveTrader.searchTerm(listing.item()), "lowest_price")
                .whenComplete((listings, error) -> inbox.add(() -> onCompetitors(listing, listings, error)));
    }

    private void onCompetitors(MarketListing listing, List<MarketListing> listings, Throwable error) {
        competitorInFlight = false;
        if (error != null) { apiError(error); return; }
        if (!running) return;
        double competitor = Double.NaN;
        boolean stillListed = false;
        for (MarketListing other : listings) {
            if (!other.item().key().equals(listing.item().key())) continue;
            if (other.id().equals(listing.id())) { stillListed = true; continue; }
            if (Double.isNaN(competitor) || other.unitPrice() < competitor) competitor = other.unitPrice();
        }
        String label = listing.item().label() + " x" + listing.item().count() + " for " + Money.format(listing.price());
        if (!stillListed) { log("Gone before buying: " + label); return; }
        long now = System.currentTimeMillis();
        PriceStats stats = book.stats(listing.item().key(), now, windowMillis());
        DealFinder.Decision decision = DealFinder.evaluate(listing, stats, competitor, settings(), exposure(), me);
        if (!decision.buy()) { log("Passed on " + label + ": " + decision.reason()); return; }
        deals++;
        String detail = label + String.format(java.util.Locale.ROOT, " (%.1f sigma under %s, resell %s, +%s)",
                decision.z(), Money.format(stats.value()), Money.format(decision.resaleUnit() * listing.item().count()),
                Money.format(decision.profit()));
        if (!settings().live) {
            ledger.open(false, listing, decision.resaleUnit(), settings().taxPercent, now);
            log("Paper buy: " + detail);
            return;
        }
        if (ledger.tradesSince(true, now - 3_600_000L) >= settings().maxTradesPerHour) { log("Hourly trade limit reached; skipped " + label); return; }
        if (client == null || client.player == null || client.screen != null) { log("Skipped " + label + ": a menu is open"); return; }
        pendingResale = decision.resaleUnit();
        log("Buying: " + detail);
        trader.buy(client, listing, (long) Math.floor(decision.resaleUnit() * listing.item().count()));
    }

    @Override
    public void bought(MarketListing listing, double paid) {
        MarketListing actual = new MarketListing(listing.item(), paid, listing.seller(), listing.timeLeft());
        livePosition = ledger.open(true, actual, pendingResale, settings().taxPercent, System.currentTimeMillis());
        log("Bought " + listing.item().label() + " for " + Money.format(paid));
    }

    @Override
    public void listed(MarketListing listing, double price) {
        if (livePosition != null) {
            livePosition.state = Ledger.State.LISTED;
            livePosition.listedPrice = price;
        }
        log("Listed " + listing.item().label() + " for " + Money.format(price));
        notify("bought " + listing.item().label() + " for " + Money.format(listing.price()) + ", listed for "
                + Money.format(price), ChatFormatting.GREEN);
    }

    @Override
    public void failed(MarketListing listing, String reason, boolean holding) {
        String label = listing == null ? "trade" : listing.item().label();
        log("Trade failed (" + label + "): " + reason);
        if (holding) {
            // Money is tied up in an item that was not relisted; stop until the player has looked.
            settings().live = false;
            notify("bought " + label + " but could not relist it (" + reason + "). Live trading is off; sell it by hand.",
                    ChatFormatting.RED);
        }
    }

    public void onServerMessage(String text) {
        if (client != null && !trader.idle()) trader.onMessage(client, text);
    }

    private void apiError(Throwable error) {
        Throwable cause = error.getCause() != null ? error.getCause() : error;
        status = cause instanceof DonutApi.ApiException api ? api.getMessage() : "API unreachable: " + cause.getClass().getSimpleName();
        if (cause instanceof DonutApi.ApiException api && api.status == 401) settings().enabled = false;
    }

    private String watching() {
        return "Watching the Auction House (" + book.markets() + " items, " + book.sales() + " sales)";
    }

    private double exposure() { return ledger.exposure(settings().live); }

    private void log(String line) {
        log.addFirst(LocalTime.now().format(CLOCK) + "  " + line);
        while (log.size() > LOG_LINES) log.removeLast();
    }

    private void notify(String text, ChatFormatting color) {
        if (client == null || client.player == null) return;
        client.player.displayClientMessage(Component.literal("[Donut] AH Sniper " + text).withStyle(color), false);
        client.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0f));
    }

    // Read by the GUI.
    public String status() { return status; }
    public boolean running() { return running; }
    public LiveTrader.Phase traderPhase() { return trader.phase(); }
    public List<String> log() { return new ArrayList<>(log); }
    public int listingsChecked() { return listingsChecked; }
    public int deals() { return deals; }
    public Ledger ledger() { return ledger; }
    public int markets() { return book.markets(); }
    public int sales() { return book.sales(); }

    public record MarketRow(String name, PriceStats stats) { }

    public List<MarketRow> busiest(int limit) {
        long now = System.currentTimeMillis();
        List<MarketRow> rows = new ArrayList<>();
        for (String key : book.busiest(limit)) rows.add(new MarketRow(book.name(key), book.stats(key, now, windowMillis())));
        return rows;
    }
}
