package dev.donut.client.license;

import dev.donut.client.config.ClientConfig;

/**
 * Turns every saved module off while Donut Client is locked, and back to how it was once a key passes. The mixins,
 * renderers and scanner read these switches directly, so clearing them is what actually stops the modules; the config
 * is not saved while locked, so the player's own choices stay in the file. A new module with a saved switch goes here.
 */
public final class ModuleSuspension {
    private boolean[] saved;

    public void suspend(ClientConfig config) {
        if (saved != null) return;
        saved = new boolean[] {config.storage.enabled, config.spawner.enabled, config.debris.enabled, config.activity.enabled,
                config.tunnels.enabled, config.entities.enabled, config.bases.enabled, config.xray.enabled, config.fullbright.enabled};
        config.storage.enabled = config.spawner.enabled = config.debris.enabled = config.activity.enabled = false;
        config.tunnels.enabled = config.entities.enabled = config.bases.enabled = false;
        config.xray.enabled = config.fullbright.enabled = false;
    }

    public void resume(ClientConfig config) {
        if (saved == null) return;
        config.storage.enabled = saved[0];
        config.spawner.enabled = saved[1];
        config.debris.enabled = saved[2];
        config.activity.enabled = saved[3];
        config.tunnels.enabled = saved[4];
        config.entities.enabled = saved[5];
        config.bases.enabled = saved[6];
        config.xray.enabled = saved[7];
        config.fullbright.enabled = saved[8];
        saved = null;
    }

    public boolean suspended() { return saved != null; }
}
