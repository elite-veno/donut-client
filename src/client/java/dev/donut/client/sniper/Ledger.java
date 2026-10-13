package dev.donut.client.sniper;

import java.util.ArrayList;
import java.util.List;

/**
 * Open and closed trades, for paper and live mode alike. A paper trade counts as sold when someone
 * later pays at least its resale price for the same item: that buyer would have taken ours.
 */
public final class Ledger {
    /** Paper trades nobody would have bought by then are written off as unsold. */
    static final long PAPER_EXPIRY = 48 * 3_600_000L;

    public enum State { OPEN, LISTED, SOLD, UNSOLD }

    public static final class Position {
        public final boolean live;
        public final String key, label;
        public final int count;
        public final double cost, resaleUnit, expectedRevenue;
        public final long openedAt;
        public State state = State.OPEN;
        public double listedPrice = Double.NaN, realized = Double.NaN;
        public long closedAt;

        Position(boolean live, String key, String label, int count, double cost, double resaleUnit,
                 double expectedRevenue, long openedAt) {
            this.live = live;
            this.key = key;
            this.label = label;
            this.count = count;
            this.cost = cost;
            this.resaleUnit = resaleUnit;
            this.expectedRevenue = expectedRevenue;
            this.openedAt = openedAt;
        }

        public boolean open() { return state == State.OPEN || state == State.LISTED; }
    }

    private final List<Position> positions = new ArrayList<>();

    public Position open(boolean live, MarketListing listing, double resaleUnit, double taxPercent, long now) {
        double revenue = resaleUnit * listing.item().count() * (1 - taxPercent / 100);
        Position position = new Position(live, listing.item().key(), listing.item().label(), listing.item().count(),
                listing.price(), resaleUnit, revenue, now);
        positions.add(position);
        return position;
    }

    /** A paper trade sells to a later buyer at its price; a live one when our own listing is bought. */
    public Position onSale(MarketSale sale, String me) {
        String key = sale.item().key();
        for (Position position : positions) {
            if (!position.open() || !position.key.equals(key) || sale.soldAt() <= position.openedAt) continue;
            if (position.live) {
                if (position.state != State.LISTED || !sale.seller().equalsIgnoreCase(me)) continue;
                if (Math.abs(sale.price() - position.listedPrice) > 1) continue;
            } else if (sale.unitPrice() < position.resaleUnit * (1 - 1e-9)) {
                continue;
            }
            position.state = State.SOLD;
            position.realized = position.expectedRevenue - position.cost;
            position.closedAt = sale.soldAt();
            return position;
        }
        return null;
    }

    public void expire(long now) {
        for (Position position : positions) {
            if (!position.live && position.open() && now - position.openedAt > PAPER_EXPIRY) {
                position.state = State.UNSOLD;
                position.closedAt = now;
            }
        }
    }

    public double exposure(boolean live) {
        return positions.stream().filter(p -> p.live == live && p.open()).mapToDouble(p -> p.cost).sum();
    }

    public double realized(boolean live) {
        return positions.stream().filter(p -> p.live == live && p.state == State.SOLD).mapToDouble(p -> p.realized).sum();
    }

    public long count(boolean live, State state) {
        return positions.stream().filter(p -> p.live == live && p.state == state).count();
    }

    public List<Position> recent(int limit) {
        int from = Math.max(0, positions.size() - limit);
        List<Position> recent = new ArrayList<>(positions.subList(from, positions.size()));
        java.util.Collections.reverse(recent);
        return recent;
    }

    public long tradesSince(boolean live, long since) {
        return positions.stream().filter(p -> p.live == live && p.openedAt >= since).count();
    }
}
