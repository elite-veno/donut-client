package dev.donut.client.gametest;

import dev.donut.client.gui.DonutScreen;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import org.lwjgl.glfw.GLFW;

/** Clicks click-GUI controls by id, through the production screen's own mouse handlers. */
final class GuiClicks {
    /** Module names by keybind index. */
    static final String[] MODULES = {"StorageFinder", "SpawnerFinder", "Freecam", "DebrisFinder", "ActivityFinder",
            "AutoRTP", "Xray", "Fullbright", "EntityFinder", "TunnelFinder", "BaseFinder"};

    private GuiClicks() { }

    static void click(ClientGameTestContext context, String id) { press(context, id, .5, GLFW.GLFW_MOUSE_BUTTON_LEFT); }

    static void rightClick(ClientGameTestContext context, String id) { press(context, id, .5, GLFW.GLFW_MOUSE_BUTTON_RIGHT); }

    /** Clicks a slider at a fraction of its width. */
    static void slide(ClientGameTestContext context, String id, double fraction) {
        press(context, id, fraction, GLFW.GLFW_MOUSE_BUTTON_LEFT);
    }

    /** Opens a module's settings unless they already are; the GUI remembers open settings while the game runs. */
    static void expand(ClientGameTestContext context, String module) {
        if (!context.computeOnClient(client -> screen(client).isExpanded(module))) click(context, "expand." + module);
        settle(context);
    }

    static void collapse(ClientGameTestContext context, String module) {
        if (context.computeOnClient(client -> screen(client).isExpanded(module))) click(context, "expand." + module);
        settle(context);
    }

    /** Unfolds a module's "More options" unless they already are; like open settings, they stay open while the game runs. */
    static void showMore(ClientGameTestContext context, String module) {
        if (!context.computeOnClient(client -> screen(client).showsMore(module))) click(context, module + ".more");
        settle(context);
    }

    /** Scrolls a control or section into view and waits for the GUI to come to rest, for screenshots. */
    static void reveal(ClientGameTestContext context, String id) {
        context.waitFor(client -> screen(client).scrollTo(id), 40);
        settle(context);
    }

    static void settle(ClientGameTestContext context) {
        context.waitTick();
        context.waitFor(client -> screen(client).isSettled(), 100);
    }

    private static void press(ClientGameTestContext context, String id, double fraction, int button) {
        // A frame must draw the control whole before it can be clicked; scrolling its panel gets it there.
        context.waitFor(client -> screen(client).scrollTo(id) && screen(client).controlBounds(id) != null, 40);
        context.runOnClient(client -> {
            DonutScreen screen = screen(client);
            double[] bounds = screen.controlBounds(id);
            var event = new MouseButtonEvent(bounds[0] + bounds[2] * fraction, bounds[1] + bounds[3] / 2,
                    new MouseButtonInfo(button, 0));
            require(screen.mouseClicked(event, false), "Donut screen did not consume a click on " + id);
            screen.mouseReleased(event);
        });
        context.waitTick();
    }

    private static DonutScreen screen(Minecraft client) {
        require(client.gui.screen() instanceof DonutScreen, "Donut screen is not open");
        return (DonutScreen) client.gui.screen();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
