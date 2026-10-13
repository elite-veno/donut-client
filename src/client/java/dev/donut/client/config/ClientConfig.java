package dev.donut.client.config;

public final class ClientConfig {
    public int schemaVersion = 1;
    public FinderSettings storage = new FinderSettings();
    public FinderSettings spawner = spawnerDefaults();
    public FinderSettings debris = debrisDefaults();
    public FinderSettings activity = activityDefaults();
    public FreecamSettings freecam = new FreecamSettings();
    public RtpSettings rtp = new RtpSettings();
    public int accentColor = 0x4C86FF;
    public boolean showHud = true;

    private static FinderSettings spawnerDefaults() {
        FinderSettings settings = new FinderSettings();
        settings.color = 0xFF779C;
        settings.perTypeColors = false;
        return settings;
    }

    private static FinderSettings debrisDefaults() {
        FinderSettings settings = new FinderSettings();
        settings.color = 0xA855F7;
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

    public void sanitize() {
        if (storage == null) storage = new FinderSettings();
        if (spawner == null) spawner = spawnerDefaults();
        if (debris == null) debris = debrisDefaults();
        if (activity == null) activity = activityDefaults();
        if (freecam == null) freecam = new FreecamSettings();
        if (rtp == null) rtp = new RtpSettings();
        storage.sanitize();
        spawner.sanitize();
        debris.sanitize();
        activity.sanitize();
        freecam.sanitize();
        rtp.sanitize();
        accentColor &= 0xFFFFFF;
    }
}
