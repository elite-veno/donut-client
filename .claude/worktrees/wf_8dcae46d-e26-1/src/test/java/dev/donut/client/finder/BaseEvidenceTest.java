package dev.donut.client.finder;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class BaseEvidenceTest {
    private static int[] counts(Object... pairs) {
        int[] counts = new int[BaseEvidence.values().length];
        for (int i = 0; i < pairs.length; i += 2) counts[((BaseEvidence) pairs[i]).ordinal()] = (Integer) pairs[i + 1];
        return counts;
    }

    @Test
    void oneStrongFindReachesTheDefaultScoreAlone() {
        assertEquals(6, BaseEvidence.score(counts(BaseEvidence.SHULKER_BOX, 1), 0, 0));
        assertTrue(BaseEvidence.score(counts(BaseEvidence.BEACON, 1), 0, 0) >= BaseEvidence.DEFAULT_MIN_SCORE);
    }

    @Test
    void whatStructuresPlaceStaysBelowTheDefaultScore() {
        // An igloo basement's sign, a trial chamber's concrete relief and disposal hopper, an outpost's banners.
        assertTrue(BaseEvidence.score(counts(BaseEvidence.SIGN, 1), 0, 0) < BaseEvidence.DEFAULT_MIN_SCORE);
        assertTrue(BaseEvidence.score(counts(BaseEvidence.CONCRETE, 40, BaseEvidence.HOPPER, 1), 0, 0) < BaseEvidence.DEFAULT_MIN_SCORE);
        assertTrue(BaseEvidence.score(counts(BaseEvidence.BANNER, 4), 0, 0) < BaseEvidence.DEFAULT_MIN_SCORE);
        assertTrue(BaseEvidence.score(counts(BaseEvidence.DIAMOND_BLOCK, 1), 0, 0) < BaseEvidence.DEFAULT_MIN_SCORE);
        assertTrue(BaseEvidence.score(counts(), 9, 0) < BaseEvidence.DEFAULT_MIN_SCORE, "a few chests are any structure's");
    }

    @Test
    void eachKindCountsOnlyUpToItsCap() {
        assertEquals(6, BaseEvidence.score(counts(BaseEvidence.SIGN, 50), 0, 0));
        assertEquals(BaseEvidence.STORAGE_WEIGHT, BaseEvidence.score(counts(), 40, 0));
    }

    @Test
    void structureEvidenceIsIgnoredWhereStructuresPlaceIt() {
        assertTrue(BaseEvidence.ENDER_CHEST.natural(false, true, false), "end cities place ender chests");
        assertFalse(BaseEvidence.ENDER_CHEST.natural(false, false, false));
        assertTrue(BaseEvidence.COMPARATOR.natural(false, false, true), "ancient cities place comparators");
        assertTrue(BaseEvidence.NETHER_BED.natural(false, false, false), "villages and igloos place beds");
        assertFalse(BaseEvidence.NETHER_BED.natural(true, false, false));
    }

    @Test
    void reasonsNameTheStrongestFirst() {
        assertEquals("shulker box, beacon, 3 signs, ...",
                BaseEvidence.reasons(counts(BaseEvidence.SIGN, 3, BaseEvidence.SHULKER_BOX, 1, BaseEvidence.BEACON, 1), 0, List.of("item frame")));
        assertEquals("2 hoppers", BaseEvidence.reasons(counts(BaseEvidence.HOPPER, 2), 0, List.of()));
    }
}
