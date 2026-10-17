package dev.donut.client.config;

public final class ClientConfig {
    public static final int SCHEMA_VERSION = 2;
    public int schemaVersion = SCHEMA_VERSION;
    public FinderSettings storage = new FinderSettings();
    public FinderSettings spawner = spawnerDefaults();
    public FinderSettings debris = debrisDefaults();
    public FinderSettings activity = activityDefaults();
    public FinderSettings tunnels = tunnelDefaults();
    public FinderSettings entities = entityDefaults();
    public FinderSettings bases = baseDefaults();
    public XraySettings xray = new XraySettings();
    public FullbrightSettings fullbright = new FullbrightSettings();
    public FreecamSettings freecam = new FreecamSettings();
    public RtpSettings rtp = new RtpSettings();
    public SchematicSettings schematic = new SchematicSettings();
    public MinerSettings miner = new MinerSettings();
    public HudSettings hud = new HudSettings();
    public GuiSettings gui = new GuiSettings();
    public int accentColor = 0x5BE3C8;
    public boolean showHud = true;

    private static FinderSettings spawnerDefaults() {
        FinderSettings settings = new FinderSettings();
        settings.color = 0xFF779C;
        // Amber, so an activated spawner stands out from the pink ones even faded in a dark cave.
        settings.secondaryColor = 0xFFB347;
        settings.perTypeColors = false;
        return settings;
    }

    private static FinderSettings debrisDefaults() {
        FinderSettings settings = new FinderSettings();
        settings.color = 0xA855F7;
        settings.secondaryColor = 0xFF7AC6;
        settings.tertiaryColor = 0xFFB347;
        settings.perTypeColors = false;
        return settings;
    }

    private static FinderSettings activityDefaults() {
        FinderSettings settings = new FinderSettings();
        settings.color = 0xFFB347;
        settings.perTypeColors = false;
        settings.fillOpacity = 0.08;
        return settings;
    }

    private static FinderSettings tunnelDefaults() {
        FinderSettings settings = new FinderSettings();
        settings.color = 0x68B4FF;
        settings.secondaryColor = 0xEC80B5;
        settings.tertiaryColor = 0xC5E774;
        settings.perTypeColors = false;
        settings.fillOpacity = 0.1;
        settings.range = 128;
        return settings;
    }

    private static FinderSettings baseDefaults() {
        FinderSettings settings = new FinderSettings();
        settings.color = 0xFF7849;
        settings.perTypeColors = false;
        settings.fillOpacity = 0.06;
        settings.range = 128;
        settings.allLoadedChunks = true;
        return settings;
    }

    private static FinderSettings entityDefaults() {
        FinderSettings settings = new FinderSettings();
        settings.color = 0xFF5A5A;
        settings.secondaryColor = 0xFFC66B;
        settings.tertiaryColor = 0x7EDD8E;
        settings.perTypeColors = false;
        settings.fillOpacity = 0.08;
        settings.range = 128;
        settings.tracers = true;
        return settings;
    }

    /** Brings a file saved by an older version up to date. */
    public void migrate(int savedVersion) {
        // Version 2: seed estimates turned out wrong on DonutSMP, whose debris has its own seed, and there is no other way
        // to find debris the server hides, so DebrisFinder is turned off; it can still be switched on for exposed debris.
        if (savedVersion < 2) {
            debris.enabled = false;
            debris.seedEstimates = false;
        }
        schemaVersion = SCHEMA_VERSION;
    }

    public void sanitize() {
        if (storage == null) storage = new FinderSettings();
        if (spawner == null) spawner = spawnerDefaults();
        if (debris == null) debris = debrisDefaults();
        if (activity == null) activity = activityDefaults();
        if (tunnels == null) tunnels = tunnelDefaults();
        if (entities == null) entities = entityDefaults();
        if (bases == null) bases = baseDefaults();
        if (xray == null) xray = new XraySettings();
        if (fullbright == null) fullbright = new FullbrightSettings();
        if (freecam == null) freecam = new FreecamSettings();
        if (rtp == null) rtp = new RtpSettings();
        if (schematic == null) schematic = new SchematicSettings();
        if (miner == null) miner = new MinerSettings();
        if (hud == null) hud = new HudSettings();
        if (gui == null) gui = new GuiSettings();
        storage.sanitize(new FinderSettings());
        spawner.sanitize(spawnerDefaults());
        debris.sanitize(debrisDefaults());
        activity.sanitize(activityDefaults());
        tunnels.sanitize(tunnelDefaults());
        entities.sanitize(entityDefaults());
        bases.sanitize(baseDefaults());
        xray.sanitize();
        fullbright.sanitize();
        freecam.sanitize();
        rtp.sanitize();
        schematic.sanitize();
        miner.sanitize();
        hud.sanitize();
        gui.sanitize();
        accentColor &= 0xFFFFFF;
    }
}
