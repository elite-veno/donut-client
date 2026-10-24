package dev.donut.client.finder;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class DebrisStatsTest {
    @Test
    void estimatesStopOnceRevealedSpotsKeepComingUpEmpty() {
        assertFalse(DebrisStats.seedSpotsEmpty(0, 2), "two misses can be bad luck");
        assertTrue(DebrisStats.seedSpotsEmpty(0, 3));
        assertTrue(DebrisStats.seedSpotsEmpty(1, 3));
        assertFalse(DebrisStats.seedSpotsEmpty(2, 3), "a seed that places debris hits about as often as it misses at worst");
        assertFalse(DebrisStats.seedSpotsEmpty(10, 4));
    }
}
