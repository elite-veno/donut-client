package dev.donut.client.finder;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Random;
import net.minecraft.world.level.ChunkPos;
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
    void theTableHoldsTheExactWorldgenOdds() {
        for (int budding : new int[] {1, 4, 17, 64, 100, 256}) {
            for (int stages : new int[] {1, budding, 2 * budding, 3 * budding, 4 * budding}) {
                double exact = AmethystGrowth.naturalTail(budding, stages);
                assertEquals(exact, AmethystGrowth.chance(budding, stages), 1e-12 + 1e-9 * exact,
                        budding + " budding, " + stages + " stages");
            }
        }
    }

    @Test
    void pastTheTableTheApproximationKeepsTheFalseAlarmRate() {
        for (int budding : new int[] {257, 400, 800}) {
            int cutoff = 0;
            while (!AmethystGrowth.beyondWorldgen(budding, cutoff)) cutoff++;
            // Fresh worldgen reaches the accepted count about as rarely as promised, and not much more often.
            double atCutoff = AmethystGrowth.naturalTail(budding, cutoff);
            assertTrue(atCutoff <= 1.5 * AmethystGrowth.FALSE_ALARM, budding + " budding: " + atCutoff + " at " + cutoff);
            assertTrue(AmethystGrowth.naturalTail(budding, cutoff - 1) > 0.5 * AmethystGrowth.FALSE_ALARM,
                    budding + " budding: needlessly strict at " + cutoff);
        }
    }

    @Test
    void aLuckyPieceIsOutweighedByTheRestOfItsGeode() {
        // Four budding blocks with a cluster each: rare, but within worldgen's reach on its own.
        ActivityArea lucky = area(0, 0, 4, 4, 16);
        assertEquals(1, ActivityIndex.judge(lucky, List.of(lucky)).score());
        // Next to a fresh piece of the same geode the pooled growth is ordinary worldgen.
        ActivityArea fresh = area(1, 0, 30, 34, 26);
        assertEquals(0, ActivityIndex.judge(lucky, List.of(lucky, fresh)).score());
        assertEquals(0, ActivityIndex.judge(fresh, List.of(lucky, fresh)).score());
    }

    @Test
    void aGrownGeodeSplitThinlyOverChunksIsStillFound() {
        ActivityArea sliver = area(0, 0, 2, 2, 8);
        // Two budding blocks alone are too few to tell growth from luck.
        assertEquals(0, ActivityIndex.judge(sliver, List.of(sliver)).score());
        ActivityArea rest = area(0, 1, 20, 24, 90);
        ActivityArea judged = ActivityIndex.judge(sliver, List.of(sliver, rest));
        assertEquals(1, judged.score());
        assertTrue(judged.chance() < 1e-12, "chance " + judged.chance());
    }

    private static ActivityArea area(int x, int z, int budding, int faces, int stages) {
        return new ActivityArea(new ChunkPos(x, z), 0, 0, 64, 0, budding, faces, stages, Double.NaN);
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
