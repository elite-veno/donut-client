package dev.donut.client.sniper;

/** A live Auction House entry. {@code price} is for the whole stack. */
public record MarketListing(MarketItem item, double price, String seller, long timeLeft) {
    public double unitPrice() { return price / Math.max(1, item.count()); }

    /** The same entry polled twice has the same seller, price and item; its time left changes. */
    public String id() { return seller + "|" + price + "|" + item.count() + "|" + item.key(); }
}
