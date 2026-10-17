package dev.donut.client.finder;

/**
 * What ChunkFinder's last pass saw, so an empty result can be explained.
 *
 * @param withClocks read chunks with plants that show growth, grown or not
 * @param roomy chunks with the whole clearance read and free of signs, before their plants are weighed
 */
public record ChunkStats(boolean overworld, int scanned, int withClocks, int signs, int roomy) {
    public static final ChunkStats EMPTY = new ChunkStats(true, 0, 0, 0, 0);
}
