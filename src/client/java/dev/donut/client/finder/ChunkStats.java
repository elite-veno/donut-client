package dev.donut.client.finder;

/**
 * What ChunkFinder's last pass saw, so an empty result can be explained.
 *
 * @param withClocks read chunks with plants that show growth, grown or not
 * @param signs chunks with signs of players
 * @param structures chunks that are part of a structure and show no players
 * @param roomy chunks with the whole clearance read and free of signs, before their caves and plants are weighed
 * @param solid those of them with few enough caves to dig in
 */
public record ChunkStats(boolean overworld, int scanned, int withClocks, int signs, int structures, int roomy, int solid) {
    public static final ChunkStats EMPTY = new ChunkStats(true, 0, 0, 0, 0, 0, 0);
}
