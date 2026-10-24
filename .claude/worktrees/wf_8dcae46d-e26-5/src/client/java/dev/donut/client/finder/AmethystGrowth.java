package dev.donut.client.finder;

/**
 * Amethyst buds only grow from random ticks, which only run in chunks within the server's simulation
 * distance of a player (vanilla default 10 chunks) or kept ticking by portals, ender pearls or forceloading.
 * Worldgen gives about 35% of budding amethyst with an exposed face a single bud of a uniformly random size,
 * and never more within one geode, so growth beyond that baseline means the chunk spent a long time near
 * someone: possibly the player's own time there, and the base may be anywhere within that distance.
 */
public final class AmethystGrowth {
    /** Worldgen bud size per budding block: none 65% of the time, otherwise 1 to 4 with equal odds. */
    private static final double[] NATURAL = {0.65, 0.0875, 0.0875, 0.0875, 0.0875};
    /** Expected bud stages per budding block straight from worldgen: a 35% chance times an average stage of 2.5. */
    static final double NATURAL_STAGES = 0.35 * 2.5;
    private static final double NATURAL_VARIANCE = 0.35 * 7.5 - NATURAL_STAGES * NATURAL_STAGES;
    /** The per-block distribution leans right: big sums are likelier than a normal curve says. */
    private static final double NATURAL_SKEW = (0.35 * 25 - 3 * NATURAL_STAGES * 0.35 * 7.5
            + 2 * NATURAL_STAGES * NATURAL_STAGES * NATURAL_STAGES) / Math.pow(NATURAL_VARIANCE, 1.5);
    /** A few budding blocks are pooled so one lucky worldgen cluster cannot dominate the score. */
    static final int MIN_BUDDING = 4;
    /** Accept growth that fewer than one in ten thousand freshly generated chunks would show by chance. */
    static final double FALSE_ALARM = 1e-4;
    /** Exact worldgen odds are tabulated up to this many budding blocks; beyond it a skew-corrected normal curve. */
    static final int EXACT_LIMIT = 256;

    private AmethystGrowth() { }

    /**
     * Growth from 0, as generated, to 1, every open face carrying a full cluster, when it is more than
     * worldgen gives; 0 when it could plausibly be worldgen luck. At the default random tick speed an open
     * face is about 45% grown after an hour nearby, 75% after two and 90% after three.
     *
     * @param budding budding amethyst blocks that could have had a bud
     * @param faces those blocks' faces that are open or already hold a bud
     * @param stages bud sizes on those faces, 1 for a small bud up to 4 for a cluster
     */
    public static double score(int budding, int faces, int stages) {
        return significant(budding, stages) ? growth(budding, faces, stages) : 0;
    }

    /** The share of possible growth past worldgen's average, 0 to 1, whether or not it could be chance. */
    public static double growth(int budding, int faces, int stages) {
        if (budding <= 0 || faces <= 0) return 0;
        double natural = NATURAL_STAGES * budding;
        double full = 4.0 * faces;
        if (full <= natural) return 0;
        return Math.clamp((stages - natural) / (full - natural), 0.0, 1.0);
    }

    /** Whether these blocks carry more growth than worldgen would give them, at the false-alarm rate. */
    public static boolean significant(int budding, int stages) {
        return budding >= MIN_BUDDING && chance(budding, stages) <= FALSE_ALARM;
    }

    static boolean beyondWorldgen(int budding, int stages) {
        return chance(budding, stages) <= FALSE_ALARM;
    }

    /** The chance that freshly generated budding blocks carry at least this many bud stages in total. */
    public static double chance(int budding, int stages) {
        if (stages <= 0) return 1;
        if (budding <= 0) return 0;
        // A geode gives a budding block one bud at most.
        if (stages > 4 * budding) return 0;
        if (budding <= EXACT_LIMIT) return Tails.TABLE[budding][stages];
        double x = (stages - NATURAL_STAGES * budding) / Math.sqrt(NATURAL_VARIANCE * budding);
        // Cornish-Fisher, first order: read the skewed sum's tail off a normal curve at a corrected point.
        double z = x - NATURAL_SKEW / 6 * (x * x - 1) / Math.sqrt(budding);
        return upperNormal(z);
    }

    /** {@link #chance} computed from scratch, for checking the table and the approximation. */
    static double naturalTail(int budding, int stages) {
        double[] sums = distribution(budding);
        double tail = 0;
        for (int sum = Math.max(stages, 0); sum < sums.length; sum++) tail += sums[sum];
        return tail;
    }

    private static double[] distribution(int budding) {
        double[] sums = {1};
        for (int block = 0; block < budding; block++) sums = addBlock(sums);
        return sums;
    }

    private static double[] addBlock(double[] sums) {
        double[] next = new double[sums.length + 4];
        for (int sum = 0; sum < sums.length; sum++) {
            for (int stage = 0; stage < NATURAL.length; stage++) next[sum + stage] += sums[sum] * NATURAL[stage];
        }
        return next;
    }

    /** The upper tail of the standard normal curve. */
    static double upperNormal(double z) {
        return 0.5 * erfc(z / Math.sqrt(2));
    }

    /** Complementary error function with a relative error below 1.2e-7 (Numerical Recipes, erfcc). */
    private static double erfc(double x) {
        double z = Math.abs(x);
        double t = 1 / (1 + 0.5 * z);
        double r = t * Math.exp(-z * z - 1.26551223 + t * (1.00002368 + t * (0.37409196 + t * (0.09678418
                + t * (-0.18628806 + t * (0.27886807 + t * (-1.13520398 + t * (1.48851587
                + t * (-0.82215223 + t * 0.17087277)))))))));
        return x >= 0 ? r : 2 - r;
    }

    /** Worldgen's exact odds per block count, built once on first use (about 1 MB). */
    private static final class Tails {
        static final double[][] TABLE = build();

        private static double[][] build() {
            double[][] table = new double[EXACT_LIMIT + 1][];
            double[] sums = {1};
            for (int budding = 0; budding <= EXACT_LIMIT; budding++) {
                if (budding > 0) sums = addBlock(sums);
                double[] tail = new double[sums.length + 1];
                for (int sum = sums.length - 1; sum >= 0; sum--) tail[sum] = tail[sum + 1] + sums[sum];
                table[budding] = tail;
            }
            return table;
        }
    }
}
