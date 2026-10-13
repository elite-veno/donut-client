package dev.donut.client.sniper;

/** A completed Auction House purchase from the transaction history. */
public record MarketSale(MarketItem item, double price, String seller, long soldAt) {
    public double unitPrice() { return price / Math.max(1, item.count()); }

    /** Transaction pages overlap from poll to poll, so each sale is recognised by its contents. */
    public String id() { return soldAt + "|" + seller + "|" + price + "|" + item.count() + "|" + item.key(); }
}
