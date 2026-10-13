package dev.donut.client.finder;

/**
 * Amethyst buds only grow from random ticks, which only run in chunks simulated near a player.
 * Worldgen gives about 35% of budding amethyst a single bud of a uniformly random size and never more,
 * so growth beyond that baseline means the chunk spent a long time near someone.
 */
public final class AmethystGrowth {
    /** Worldgen bud size per budding block: none 65% of the time, otherwise 1 to 4 with equal odds. */
    private static final double[] NATURAL = {0.65, 0.0875, 0.0875, 0.0875, 0.0875};
    /** Expected bud stages per budding block straight from worldgen: a 35% chance times an average stage of 2.5. */
    static final double NATURAL_STAGES = 0.35 * 2.5;
    private static final double NATURAL_VARIANCE = 0.35 * 7.5 - NATURAL_STAGES * NATURAL_STAGES;
    /** A few budding blocks are averaged so one lucky worldgen cluster cannot dominate the score. */
    static final int MIN_BUDDING = 4;
    /** Accept growth that fewer than one in ten thousand freshly generated chunks would show by chance. */
    static final double FALSE_ALARM = 1e-4;
    /** Beyond this the exact tail is replaced by a normal approximation with the same false-alarm rate. */
    private static final int EXACT_LIMIT = 64;
    private static final double NORMAL_CUTOFF = 3.72;

    private AmethystGrowth() { }

    /**
     * Growth from 0, as generated, to 1, every open face carrying a full cluster. At the default random tick
     * speed a geode gains roughly half of its possible growth per hour spent within simulation distance.
     * Chunks whose buds could plausibly be worldgen luck score 0.
     *
     * @param budding budding amethyst blocks with at least one face that can hold a bud
     * @param faces those blocks' faces that are open or already hold a bud
     * @param stages bud sizes on those faces, 1 for a small bud up to 4 for a cluster
     */
    public static double score(int budding, int faces, int stages) {
        if (budding < MIN_BUDDING || faces <= 0 || !beyondWorldgen(budding, stages)) return 0;
        double natural = NATURAL_STAGES * budding;
        double full = 4.0 * faces;
        if (full <= natural) return 0;
        return Math.clamp((stages - natural) / (full - natural), 0.0, 1.0);
    }

    static boolean beyondWorldgen(int budding, int stages) {
        // Worldgen never gives a budding block more than one bud.
        if (stages > 4 * budding) return true;
        if (budding > EXACT_LIMIT) {
            return (stages - NATURAL_STAGES * budding) / Math.sqrt(NATURAL_VARIANCE * budding) >= NORMAL_CUTOFF;
        }
        return naturalTail(budding, stages) <= FALSE_ALARM;
    }

    /** The chance that freshly generated budding blocks carry at least this many stages in total. */
    static double naturalTail(int budding, int stages) {
        double[] sums = {1};
        for (int block = 0; block < budding; block++) {
            double[] next = new double[sums.length + 4];
            for (int sum = 0; sum < sums.length; sum++) {
                for (int stage = 0; stage < NATURAL.length; stage++) next[sum + stage] += sums[sum] * NATURAL[stage];
            }
            sums = next;
        }
        double tail = 0;
        for (int sum = Math.max(stages, 0); sum < sums.length; sum++) tail += sums[sum];
        return tail;
    }
}
