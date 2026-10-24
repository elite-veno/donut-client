package dev.donut.client.finder;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class GrowthClocksTest {
    private static GrowthClocks plants(GrowthClocks.Kind kind, int fresh, int grown, int age) {
        GrowthClocks clocks = new GrowthClocks();
        for (int i = 0; i < fresh; i++) clocks.add(kind, false, 0);
        for (int i = 0; i < grown; i++) clocks.add(kind, true, age);
        return clocks;
    }

    @Test
    void plantsAsWorldgenLeavesThemAreNoSign() {
        GrowthClocks clocks = plants(GrowthClocks.Kind.CANE, 20, 0, 0);
        for (int i = 0; i < 60; i++) clocks.add(GrowthClocks.Kind.KELP, false, 0);
        for (int i = 0; i < 10; i++) clocks.add(GrowthClocks.Kind.VINE, false, 0);
        assertFalse(clocks.foreign(0));
        assertEquals(0, clocks.minutes(0));
        assertNull(clocks.reason(0));
    }

    @Test
    void anyAgeIsASignWhenWeWereNeverNear() {
        GrowthClocks clocks = plants(GrowthClocks.Kind.CANE, 4, 1, 3);
        assertTrue(clocks.foreign(0));
        assertEquals("Sugar cane aged", clocks.reason(0));
        // Three random ticks over five canes: about 0.6 of a tick each, some 41 seconds at the default speed.
        assertEquals(0.6 / GrowthClocks.RANDOM_TICKS_PER_TICK / 1200, clocks.minutes(0), 1e-9);
    }

    @Test
    void ourOwnTimeNearbyExplainsSomeGrowth() {
        // Fifteen seconds within simulation distance ages about one cane in five once.
        assertFalse(plants(GrowthClocks.Kind.CANE, 17, 3, 1).foreign(300));
        assertTrue(plants(GrowthClocks.Kind.CANE, 17, 3, 1).foreign(0));
        assertTrue(plants(GrowthClocks.Kind.CANE, 4, 16, 8).foreign(300), "far more growth than fifteen seconds give");
        // What our own time explains is taken off the estimate.
        assertEquals(0, plants(GrowthClocks.Kind.CANE, 0, 20, 1).minutes(20 * 60 * 20));
    }

    @Test
    void kelpGrowthNeedsOneToFourGrowthsFromWorldgen() {
        assertEquals(0, GrowthClocks.chance(GrowthClocks.Kind.KELP, 0));
        // For a short time only the quarter generated at age 23 can show it, after one growth.
        assertEquals(0.25 * 0.14 * 0.01, GrowthClocks.chance(GrowthClocks.Kind.KELP, 0.01), 1e-6);
        assertEquals(1, GrowthClocks.chance(GrowthClocks.Kind.KELP, 1000), 1e-9);
        assertTrue(GrowthClocks.upperChance(GrowthClocks.Kind.KELP, 1) > GrowthClocks.chance(GrowthClocks.Kind.KELP, 1));
        GrowthClocks forest = plants(GrowthClocks.Kind.KELP, 40, 10, 0);
        assertTrue(forest.foreign(0));
        assertEquals("Kelp grew", forest.reason(0));
        // A fifth grown takes about 0.8 growths each, 5.7 random ticks at 14% a growth: six and a half minutes.
        assertEquals(6.5, forest.minutes(0), 0.1);
    }

    @Test
    void oneCaneWouldShowAVisitOfAFewMinutes() {
        GrowthClocks one = plants(GrowthClocks.Kind.CANE, 1, 0, 0);
        double expected = Math.log(20) / GrowthClocks.RANDOM_TICKS_PER_TICK / 1200;
        assertEquals(expected, one.unseenMinutes(), 1e-3);
        assertEquals(3.41, one.unseenMinutes(), 0.01);
        assertEquals(expected / 10, plants(GrowthClocks.Kind.CANE, 10, 0, 0).unseenMinutes(), 1e-3);
        assertEquals(Double.POSITIVE_INFINITY, new GrowthClocks().unseenMinutes());
        assertEquals(1 - Math.exp(-10 * 1200 * GrowthClocks.RANDOM_TICKS_PER_TICK), one.detectChance(10), 1e-9);
    }

    @Test
    void slowPlantsNeedManyToVouchForAnArea() {
        // Kelp grows about one random tick in seven, and a quarter of it shows the first growth.
        double kelp = plants(GrowthClocks.Kind.KELP, 50, 0, 0).unseenMinutes();
        assertTrue(kelp > 1 && kelp < 4, "fifty kelp: " + kelp);
        assertTrue(plants(GrowthClocks.Kind.VINE, 1, 0, 0).unseenMinutes() > 30);
    }

    @Test
    void sugarCaneThatAllGrewToThreeIsASign() {
        GrowthClocks grown = new GrowthClocks();
        for (int i = 0; i < 8; i++) grown.caneColumn(false);
        assertTrue(grown.foreign(0));
        assertEquals("Sugar cane grew", grown.reason(0));
        GrowthClocks few = new GrowthClocks();
        for (int i = 0; i < 7; i++) few.caneColumn(false);
        assertFalse(few.foreign(0), "seven 3-tall columns happen in worldgen");
        GrowthClocks mixed = new GrowthClocks();
        for (int i = 0; i < 20; i++) mixed.caneColumn(false);
        mixed.caneColumn(true);
        assertFalse(mixed.foreign(0));
    }

    @Test
    void poissonTailMatchesItsSum() {
        for (double mean : new double[] {0.001, 0.3, 2, 9.5, 40}) {
            for (int k = 0; k <= 60; k += 3) {
                double below = 0, term = Math.exp(-mean);
                for (int i = 0; i < k; i++) {
                    below += term;
                    term *= mean / (i + 1);
                }
                double tail = GrowthClocks.poissonTail(mean, k);
                assertEquals(1 - below, tail, 1e-9, "mean " + mean + ", k " + k);
                assertTrue(tail >= 0 && tail <= 1);
            }
        }
        // Tiny tails stay exact instead of rounding to zero.
        assertEquals(Math.pow(0.001, 5) / 120, GrowthClocks.poissonTail(0.001, 5), 1e-20);
        assertEquals(0, GrowthClocks.poissonTail(0, 1));
        assertEquals(1, GrowthClocks.poissonTail(0, 0));
    }
}
