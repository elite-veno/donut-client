package dev.donut.client.config;

public final class XraySettings {
    public boolean enabled = false;
    public boolean favorite = false;
    /** Stone, deepslate, tuff, granite, diorite, andesite, calcite and bedrock. */
    public boolean hideStone = true;
    /** Dirt, grass, sand, sandstone, gravel and clay. */
    public boolean hideSoil = true;
    /** Netherrack, basalt, blackstone, soul sand and soil, nylium and magma. */
    public boolean hideNether = true;
    public boolean hideEndStone = true;
    public boolean hideWater = true;
    public boolean hideLava = false;
    /**
     * Hide ores that touch no air or fluid. Anti-xray plugins fill buried stone with fake ores,
     * but they always leave exposed blocks as they are.
     */
    public boolean exposedOresOnly = false;
    /** Comma-separated block ids, such as {@code minecraft:obsidian}, that are hidden too. */
    public String extraHidden = "";

    public void sanitize() {
        if (extraHidden == null) extraHidden = "";
        extraHidden = extraHidden.strip();
        if (extraHidden.length() > 512) extraHidden = extraHidden.substring(0, 512);
    }
}
