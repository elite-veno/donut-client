package dev.donut.client.gametest;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

/**
 * The user works beside the test window while game tests run, so it lets go of their mouse every tick, except while a
 * test that needs it holds it. The builder edition's WatchBuild does the same; when the branches meet, one of the two goes.
 */
public final class ReleaseMouse implements ClientModInitializer {
    /** Set by a test that turns the camera with the mouse, which Minecraft only does while it holds the mouse. */
    static volatile boolean held;

    @Override
    public void onInitializeClient() {
        if (System.getProperty("fabric.client.gametest") == null) return;
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!held && client.mouseHandler.isMouseGrabbed()) client.mouseHandler.releaseMouse();
        });
    }
}
