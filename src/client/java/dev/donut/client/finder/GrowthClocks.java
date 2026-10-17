package dev.donut.client.finder;

/**
 * Plants that only grow on random ticks, read as clocks for how long players have been near a chunk: random ticks only
 * run in chunks within the server's simulation distance of a player. Checked against 26.2's worldgen and growth code:
 * <ul>
 *     <li>Sugar cane generates in columns of 2 to 4 at age 0. The top of a column shorter than 3 ages by one on every
 *     random tick and grows a block after 16; the age changes without a block update, so the client only learns it from a
 *     newly sent chunk.</li>
 *     <li>Cactus generates 1 to 3 tall at age 0, and its top ages on every random tick up to 15, also without an update.</li>
 *     <li>Kelp's top generates at age 20 to 23, never in the top water block, in columns of at most 11. On a random tick
 *     it grows one block with a 14% chance, one age further, up to 25.</li>
 *     <li>Tree vines hang at most 4 blocks below the one on the leaf; the lowest grows down about once in 16 random
 *     ticks.</li>
 * </ul>
 * Counts are kept per chunk, or summed over an area.
 */
public final class GrowthClocks {
    public enum Kind {
        CANE("Sugar cane aged"), CACTUS("Cactus aged"), KELP("Kelp grew"), VINE("Vines grew");

        public final String grew;

        Kind(String grew) { this.grew = grew; }
    }

    private static final Kind[] KINDS = Kind.values();
    /** Random ticks each block gets per game tick at the default random tick speed of 3. */
    static final double RANDOM_TICKS_PER_TICK = 3.0 / 4096;
    /** Growth is someone else's when our own time nearby gives it less than one chance in ten thousand. */
    static final double FALSE_ALARM = 1e-4;
    private static final double KELP_GROWTH = 0.14;
    /** A hanging vine's lowest block grows down on one random tick in 4, in half the directions, copying half its faces. */
    private static final double VINE_GROWTH = 1.0 / 16;
    /** Worldgen makes columns 2, 3 or 4 tall 11, 5 and 2 times in 18; growth turns the 2s into 3s and stops. */
    private static final double CANE_STAYS_SHORT = 11.0 / 16;
    /** Random ticks a cane needs to grow from 2 to 3 blocks. */
    private static final int CANE_GROWTH_TICKS = 16;
    /** The unseen time is how long a player could stay with one chance in twenty of leaving no growth. */
    private static final double MISS = 0.05;

    private final int[] clocks = new int[KINDS.length], grown = new int[KINDS.length], ages = new int[KINDS.length];
    /** Sugar cane columns of 2 and of 3 open to the sky, whatever their age. */
    private int shortCane, tallCane;

    /** One plant that can still show growth or already does; {@code age} counts for sugar cane and cactus. */
    public void add(Kind kind, boolean grew, int age) {
        clocks[kind.ordinal()]++;
        if (grew) grown[kind.ordinal()]++;
        ages[kind.ordinal()] += age;
    }

    /** A sugar cane column of 2 or 3 with air above it. */
    public void caneColumn(boolean shortColumn) {
        if (shortColumn) shortCane++;
        else tallCane++;
    }

    /** Plants that have not grown, so they can still show a visit, added to an area's count. */
    void addUngrown(Kind kind, int count) { clocks[kind.ordinal()] += count; }

    public int clocks() {
        int total = 0;
        for (int count : clocks) total += count;
        return total;
    }

    public int clocks(Kind kind) { return clocks[kind.ordinal()]; }

    public int grown(Kind kind) { return grown[kind.ordinal()]; }

    /**
     * Whether the growth is more than our own time nearby explains.
     *
     * @param ownTicks game ticks we spent within simulation distance of these plants before they were read
     */
    public boolean foreign(double ownTicks) {
        double own = ownTicks * RANDOM_TICKS_PER_TICK, expected = 0;
        int seen = 0;
        for (Kind kind : KINDS) {
            // The likeliest our own random ticks make each plant grow, so time we spent nearby is never mistaken.
            expected += clocks[kind.ordinal()] * upperChance(kind, own);
            seen += grown[kind.ordinal()];
        }
        return seen > 0 && poissonTail(expected, seen) <= FALSE_ALARM || caneGrew(own);
    }

    /**
     * Every sugar cane column open to the sky is 3 tall, which worldgen does for fewer than one chunk in ten thousand:
     * growth stops at 3, so a chunk ticked for long loses its short columns, and its cane ages, entirely.
     */
    private boolean caneGrew(double ownRandomTicks) {
        return shortCane == 0 && tallCane > 0 && ownRandomTicks < CANE_GROWTH_TICKS / 4.0
                && Math.pow(1 - CANE_STAYS_SHORT, tallCane) <= FALSE_ALARM;
    }

    /** How long others were near, in minutes, from the plant that shows it best; 0 without growth. */
    public double minutes(double ownTicks) {
        return estimate(ownTicks * RANDOM_TICKS_PER_TICK)[0] / RANDOM_TICKS_PER_TICK / 1200;
    }

    /** What grew, for labels, such as "Kelp grew"; null without growth. */
    public String reason(double ownTicks) {
        double[] best = estimate(ownTicks * RANDOM_TICKS_PER_TICK);
        return best[1] < 0 ? null : best[1] == KINDS.length ? "Sugar cane grew" : KINDS[(int) best[1]].grew;
    }

    /** Random ticks of others' presence and the kind index that shows it, KINDS.length for cane height, -1 for none. */
    private double[] estimate(double own) {
        double best = 0, kind = -1;
        for (Kind k : KINDS) {
            int n = clocks[k.ordinal()], g = grown[k.ordinal()];
            if (g == 0) continue;
            double ticks;
            if (k == Kind.CANE || k == Kind.CACTUS) {
                // Each random tick ages a top by one, so the average age is the number of ticks.
                ticks = (double) ages[k.ordinal()] / n;
            } else {
                // All grown: the time at which that is an even bet, as a modest estimate.
                double share = g < n ? (double) g / n : Math.pow(0.5, 1.0 / n);
                ticks = inverse(k, share);
            }
            ticks -= own;
            if (ticks > best) { best = ticks; kind = k.ordinal(); }
        }
        if (caneGrew(own) && CANE_GROWTH_TICKS > best) { best = CANE_GROWTH_TICKS; kind = KINDS.length; }
        return new double[] {best, kind};
    }

    /**
     * How long a player could have stayed nearby, in minutes, with one chance in twenty of leaving none of these clocks
     * grown; infinite without clocks.
     */
    public double unseenMinutes() {
        if (clocks() == 0) return Double.POSITIVE_INFINITY;
        double low = 0, high = 1;
        while (missChance(high) > MISS) {
            high *= 2;
            if (high > 1e9) return Double.POSITIVE_INFINITY;
        }
        for (int i = 0; i < 60; i++) {
            double middle = (low + high) / 2;
            if (missChance(middle) > MISS) low = middle;
            else high = middle;
        }
        return high / RANDOM_TICKS_PER_TICK / 1200;
    }

    /** The chance that this many minutes of a player nearby would have grown at least one of these clocks. */
    public double detectChance(double minutes) { return 1 - missChance(minutes * 1200 * RANDOM_TICKS_PER_TICK); }

    private double missChance(double randomTicks) {
        double logMiss = 0;
        for (Kind kind : KINDS) {
            int n = clocks[kind.ordinal()];
            if (n > 0) logMiss += n * Math.log1p(-Math.min(chance(kind, randomTicks), 1 - 1e-15));
        }
        return Math.exp(logMiss);
    }

    /** The chance that a plant as worldgen left it shows growth after this many random ticks. */
    static double chance(Kind kind, double randomTicks) {
        if (randomTicks <= 0) return 0;
        return switch (kind) {
            case CANE, CACTUS -> -Math.expm1(-randomTicks);
            // Ages 20 to 23 are equally likely and age 24 is the first worldgen never gives: 4 to 1 growths away.
            case KELP -> (poissonTail(KELP_GROWTH * randomTicks, 1) + poissonTail(KELP_GROWTH * randomTicks, 2)
                    + poissonTail(KELP_GROWTH * randomTicks, 3) + poissonTail(KELP_GROWTH * randomTicks, 4)) / 4;
            case VINE -> -Math.expm1(-randomTicks * VINE_GROWTH);
        };
    }

    /**
     * At least {@link #chance}: kelp just below the surface shows growth after one growth whatever its age, and a vine
     * may have more faces to copy.
     */
    static double upperChance(Kind kind, double randomTicks) {
        if (randomTicks <= 0) return 0;
        return switch (kind) {
            case CANE, CACTUS -> -Math.expm1(-randomTicks);
            case KELP -> -Math.expm1(-randomTicks * KELP_GROWTH);
            case VINE -> -Math.expm1(-randomTicks * VINE_GROWTH * 2);
        };
    }

    /** Random ticks at which this share of plants shows growth. */
    private static double inverse(Kind kind, double share) {
        double low = 0, high = 1;
        while (chance(kind, high) < share && high < 1e9) high *= 2;
        for (int i = 0; i < 60; i++) {
            double middle = (low + high) / 2;
            if (chance(kind, middle) < share) low = middle;
            else high = middle;
        }
        return high;
    }

    /** The chance that a Poisson count with this mean is at least {@code k}. */
    static double poissonTail(double mean, int k) {
        if (k <= 0) return 1;
        if (mean <= 0) return 0;
        if (k > mean) {
            // Summed from k up, which keeps tiny tails exact instead of losing them to 1 minus nearly 1.
            double logTerm = -mean + k * Math.log(mean);
            for (int i = 2; i <= k; i++) logTerm -= Math.log(i);
            double term = Math.exp(logTerm), sum = 0;
            for (int i = k; term > sum * 1e-16 && i < k + 10_000; i++) {
                sum += term;
                term *= mean / (i + 1);
            }
            return Math.min(1, sum);
        }
        double term = Math.exp(-mean), sum = 0;
        for (int i = 0; i < k; i++) {
            sum += term;
            term *= mean / (i + 1);
        }
        return Math.clamp(1 - sum, 0.0, 1.0);
    }
}
