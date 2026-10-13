package dev.donut.client.finder;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Random;
import org.junit.jupiter.api.Test;

class AmethystGrowthTest {
    /** Vanilla: randomTickSpeed 3 picks per 16x16x16 section each tick, one in five ticks grows a bud. */
    private static final double RANDOM_TICKS_PER_HOUR = 72_000 * 3 / 4096.0;

    @Test
    void tooFewBuddingBlocksOrFacesScoreZero() {
        assertEquals(0, AmethystGrowth.score(AmethystGrowth.MIN_BUDDING - 1, 3, 12));
        assertEquals(0, AmethystGrowth.score(8, 0, 0));
    }

    @Test
    void worldgenBaselineScoresZeroAndFullClustersScoreOne() {
        assertEquals(0, AmethystGrowth.score(8, 8, 7));
        assertEquals(0, AmethystGrowth.score(8, 8, 0));
        assertEquals(1, AmethystGrowth.score(8, 8, 32));
        // Baseline 32 * 0.875 = 28 of a possible 128 stages, so 78 stages is half of the way.
        assertEquals(0.5, AmethystGrowth.score(32, 32, 78), 1e-9);
    }

    @Test
    void naturalTailIsTheExactWorldgenDistribution() {
        assertEquals(1, AmethystGrowth.naturalTail(5, 0), 1e-12);
        assertEquals(0.0875, AmethystGrowth.naturalTail(1, 4), 1e-12);
        assertEquals(Math.pow(0.0875, 4), AmethystGrowth.naturalTail(4, 16), 1e-15);
        assertEquals(0, AmethystGrowth.naturalTail(4, 17));
    }

    @Test
    void luckyWorldgenInASmallChunkIsNotReported() {
        // Three clusters on four budding blocks happens to a few in a thousand fresh chunks.
        assertEquals(0, AmethystGrowth.score(4, 4, 12));
        assertEquals(1, AmethystGrowth.score(4, 4, 16));
        // Two buds on one budding block cannot come from worldgen at all.
        assertTrue(AmethystGrowth.beyondWorldgen(4, 17));
    }

    @Test
    void largeChunksUseTheNormalApproximation() {
        assertEquals(0, AmethystGrowth.score(100, 150, 120));
        assertEquals((400 - 87.5) / (600 - 87.5), AmethystGrowth.score(100, 150, 400), 1e-9);
    }

    @Test
    void freshGeodesAlmostNeverReachTheDefaultThreshold() {
        Random random = new Random(1);
        double total = 0;
        int flagged = 0;
        for (int i = 0; i < 20_000; i++) {
            double score = new Geode(random).score();
            total += score;
            if (score >= 0.5) flagged++;
        }
        assertTrue(total / 20_000 < 0.01, "mean " + total / 20_000);
        assertTrue(flagged <= 4, flagged + " of 20000 fresh geodes were flagged");
    }

    @Test
    void anHourNearbyReachesAboutHalfAndAFewHoursNearlyAll() {
        Random random = new Random(2);
        double oneHour = 0, threeHours = 0;
        for (int i = 0; i < 2000; i++) {
            Geode geode = new Geode(random);
            geode.simulate(random, 1);
            oneHour += geode.score();
            geode.simulate(random, 2);
            threeHours += geode.score();
        }
        assertEquals(0.5, oneHour / 2000, 0.15);
        assertTrue(threeHours / 2000 > 0.85, "three hours " + threeHours / 2000);
    }

    /** Budding blocks with 1 to 3 open faces; stages[block][face] is 0 for empty up to 4 for a cluster. */
    private static final class Geode {
        final int[][] stages;

        Geode(Random random) {
            stages = new int[4 + random.nextInt(37)][];
            for (int block = 0; block < stages.length; block++) {
                stages[block] = new int[1 + random.nextInt(3)];
                // Worldgen: 35% of budding blocks get one bud of a uniformly random size.
                if (random.nextDouble() < 0.35) stages[block][0] = 1 + random.nextInt(4);
            }
        }

        void simulate(Random random, double hours) {
            for (int[] faces : stages) {
                int ticks = poisson(random, RANDOM_TICKS_PER_HOUR * hours);
                for (int tick = 0; tick < ticks; tick++) {
                    if (random.nextInt(5) != 0) continue;
                    // Six directions; faces past this block's open ones are solid neighbors.
                    int direction = random.nextInt(6);
                    if (direction < faces.length && faces[direction] < 4) faces[direction]++;
                }
            }
        }

        double score() {
            int faces = 0, total = 0;
            for (int[] block : stages) {
                faces += block.length;
                for (int stage : block) total += stage;
            }
            return AmethystGrowth.score(stages.length, faces, total);
        }

        private static int poisson(Random random, double mean) {
            double limit = Math.exp(-mean), product = random.nextDouble();
            int count = 0;
            while (product > limit) {
                product *= random.nextDouble();
                count++;
            }
            return count;
        }
    }
}
