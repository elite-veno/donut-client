package dev.donut.client.config;

/** AutoBase: a schematic's room dug, its materials bought and the build made, one after the other, and its guard. */
public final class PipelineSettings {
    /** The run always starts off when joining a new session. */
    public transient boolean enabled = false;
    public boolean favorite = false;
    /** A quote at or under this many dollars is bought without asking; 0 always asks first. */
    public long autoSpend = 0;
    /** Listings over GoNuts' $10M cap may be quoted and bought. */
    public boolean highPrice = false;
    /** Hold ChunkMiner, the buying and SchematicBuilder while GoNuts' player guard holds; they go on once it clears. */
    public boolean guardHold = true;
    /** Stop all three on a GoNuts Staff Watch alert; nothing goes on by itself after one. */
    public boolean staffStop = true;
    /** Seconds the guard has to stay clear before what it held goes on. */
    public int clearSeconds = 10;

    public void sanitize() {
        autoSpend = Math.clamp(autoSpend, 0, 10_000_000_000L);
        clearSeconds = Math.clamp(clearSeconds, 2, 120);
    }
}
