package dev.donut.client.finder;

/**
 * What DebrisFinder's last scan saw, so its settings page can explain an empty or crowded world view.
 *
 * @param buried debris with no open neighbor, real or faked
 * @param exposed debris touching air, fluid or another see-through block; always real
 * @param fakedChunks chunks holding far more buried debris than worldgen places
 * @param hiddenFakes buried debris in faked chunks that is not drawn
 * @param seeded whether a world seed placed estimates
 * @param estimates seed positions drawn because the server may be hiding debris there
 * @param seedChecked debris the server proved real, by exposing or revealing it
 * @param seedMatched how much of that debris lies on a seed position
 * @param bedrockChecked sampled blocks in the Nether's bedrock layers, y 1-4 and 123-126
 * @param bedrockMatched those that are bedrock exactly where the seed puts it
 * @param bedrockSeen those that are bedrock; none means the server generates flat bedrock
 * @param seedChunks chunks whose gravel and blackstone, with their neighbors', lie where the seed puts them
 * @param offSeedChunks chunks whose ores clearly don't; the server generated them some other way, so they get no estimates
 * @param dugOut seed positions in seed chunks that are open air now: mined, or open from the start
 * @param searchAreas sections in range whose palette lists debris that none of their blocks show
 */
public record DebrisStats(boolean nether, int scannedChunks, int buried, int exposed, int fakedChunks, int hiddenFakes,
                          boolean seeded, int estimates, int seedChecked, int seedMatched,
                          int bedrockChecked, int bedrockMatched, int bedrockSeen, int seedChunks, int offSeedChunks, int dugOut,
                          int searchAreas) {
    public static final DebrisStats EMPTY = new DebrisStats(false, 0, 0, 0, 0, 0, false, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

    /** Enough of the Nether was scanned that finding no buried debris at all means the server hides it. */
    public boolean likelyHidden() { return nether && scannedChunks >= 40 && buried == 0 && fakedChunks == 0; }

    /** Estimates are wrong here: the seed is not this Nether's, or the server places debris its own way. */
    public boolean seedMismatch() { return bedrockMismatch() || debrisMismatch(); }

    /** Real debris keeps turning up off the seed's positions. */
    public boolean debrisMismatch() { return seeded && seedChecked >= 4 && seedMatched * 2 < seedChecked; }

    /** Players can't break bedrock, so this Nether's seed matches nearly all of it; another seed matches about 60%. */
    public boolean bedrockReady() { return seeded && bedrockSeen > 0 && bedrockChecked >= 1024; }

    public boolean bedrockMismatch() { return bedrockReady() && bedrockMatched < bedrockChecked * 0.97; }

    /**
     * The server hides debris and none of the seed's spots here still hides any: players dug them out. Spots that were
     * open from the start count as dug too, so this needs a server that hides debris to mean anything.
     */
    public boolean allDugOut() { return seedChunks > 0 && estimates == 0 && dugOut > 0 && searchAreas == 0 && likelyHidden(); }

    /** The seed is this Nether's, yet debris lies elsewhere: the server seeds its ore placement separately. */
    public boolean oresRandomized() { return bedrockReady() && !bedrockMismatch() && debrisMismatch(); }

    /**
     * Estimate spots the server revealed came up empty too often to keep drawing them: at least three misses, and
     * fewer than half as many hits. Worldgen fills a spot it tried unless air was next to it, so a seed that places this
     * server's debris rarely misses.
     */
    public static boolean seedSpotsEmpty(int hits, int misses) { return misses >= 3 && hits * 2 < misses; }
}
