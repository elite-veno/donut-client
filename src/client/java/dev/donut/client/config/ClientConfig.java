package dev.donut.client.config;

public final class ClientConfig {
    public int schemaVersion = 1;
    public FinderSettings storage = new FinderSettings();
    public FinderSettings spawner = spawnerDefaults();
    public FinderSettings debris = debrisDefaults();
    public FinderSettings activity = activityDefaults();
    public FinderSettings tunnels = tunnelDefaults();
    public FinderSettings entities = entityDefaults();
    public XraySettings xray = new XraySettings();
    public FullbrightSettings fullbright = new FullbrightSettings();
    public FreecamSettings freecam = new FreecamSettings();
    public RtpSettings rtp = new RtpSettings();
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

    public void sanitize() {
        if (storage == null) storage = new FinderSettings();
        if (spawner == null) spawner = spawnerDefaults();
        if (debris == null) debris = debrisDefaults();
        if (activity == null) activity = activityDefaults();
        if (tunnels == null) tunnels = tunnelDefaults();
        if (entities == null) entities = entityDefaults();
        if (xray == null) xray = new XraySettings();
        if (fullbright == null) fullbright = new FullbrightSettings();
        if (freecam == null) freecam = new FreecamSettings();
        if (rtp == null) rtp = new RtpSettings();
        if (hud == null) hud = new HudSettings();
        if (gui == null) gui = new GuiSettings();
        storage.sanitize(new FinderSettings());
        spawner.sanitize(spawnerDefaults());
        debris.sanitize(debrisDefaults());
        activity.sanitize(activityDefaults());
        tunnels.sanitize(tunnelDefaults());
        entities.sanitize(entityDefaults());
        xray.sanitize();
        fullbright.sanitize();
        freecam.sanitize();
        rtp.sanitize();
        hud.sanitize();
        gui.sanitize();
        accentColor &= 0xFFFFFF;
    }
}
