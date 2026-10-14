package dev.donut.client.finder;

/**
 * What ActivityFinder's last scan saw, below the threshold too, so an empty screen can be explained.
 *
 * @param best the highest-scoring chunk with budding amethyst, or null when there is none
 * @param certain the highest-scoring chunk whose growth worldgen shows less than once in a million, or null; a lucky
 *                piece of a geode can score higher than a real one
 */
public record ActivityStats(boolean overworld, int scannedChunks, int amethystChunks, int budding, ActivityArea best,
                            ActivityArea certain) {
    public static final ActivityStats EMPTY = new ActivityStats(true, 0, 0, 0, null, null);
    /** Auto RTP stops only on growth this unlikely from worldgen, so a whole run practically never stops falsely. */
    public static final double CERTAIN_CHANCE = 1e-6;
}
