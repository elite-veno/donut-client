package dev.donut.client.finder;

/**
 * What ActivityFinder's last scan saw, below the threshold too, so an empty screen can be explained.
 *
 * @param best the highest-scoring chunk with budding amethyst, or null when there is none
 */
public record ActivityStats(boolean overworld, int scannedChunks, int amethystChunks, int budding, ActivityArea best) {
    public static final ActivityStats EMPTY = new ActivityStats(true, 0, 0, 0, null);
}
