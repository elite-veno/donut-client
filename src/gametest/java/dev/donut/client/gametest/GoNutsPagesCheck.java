package dev.donut.client.gametest;

import dev.donut.client.gui.DonutScreen;
import dev.doughbay.fabric.GoNutsApi;
import dev.doughbay.fabric.Tuning;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Clicks through the GoNuts pages of the Donut menu: every settings group opens, settings round-trip through GoNutsApi,
 * a guarded setting ignores a single click, typed values read their units, and a long list is never cut. Start is never
 * clicked, so nothing trades. GoNuts keeps its settings in the test run's own game folder.
 */
final class GoNutsPagesCheck {
    private GoNutsPagesCheck() { }

    static void run(ClientGameTestContext context, Consumer<String> screenshot) {
        GuiClicks.click(context, "nav.gonuts");
        GuiClicks.settle(context);
        GuiClicks.reveal(context, "full");
        context.runOnClient(client -> {
            DonutScreen screen = screen(client);
            require(screen.controlBounds("full") != null, "The GoNuts overview shows no Open button");
            require(screen.controlBounds("confirmPay") == null, "A payroll approval shows with no payment waiting");
        });
        screenshot.accept("donut-02-clickgui-gonuts");

        GuiClicks.click(context, "nav.gonuts-settings");
        GuiClicks.settle(context);
        screenshot.accept("donut-02-clickgui-gonuts-settings");

        // Every group opens, with its folded settings too, so a setting that cannot be drawn fails here.
        for (String group : GoNutsApi.groups()) {
            GuiClicks.expand(context, group);
            if (folded(group)) GuiClicks.showMore(context, group);
            context.runOnClient(client -> require(screen(client).isExpanded(group), "GoNuts group " + group + " did not open"));
            GuiClicks.collapse(context, group);
        }

        GuiClicks.expand(context, "Session");
        screenshot.accept("donut-02-clickgui-gonuts-session");
        GuiClicks.collapse(context, "Session");

        Tuning.Setting toggle = first(s -> s.kind() == Tuning.Kind.TOGGLE && !GoNutsApi.guarded(s.key()));
        double before = GoNutsApi.get(toggle.key());
        clickSetting(context, toggle);
        require(GoNutsApi.get(toggle.key()) != before, "Clicking " + toggle.label() + " did not change it");
        clickSetting(context, toggle);
        require(GoNutsApi.get(toggle.key()) == before, "Clicking " + toggle.label() + " again did not change it back");

        // A guarded setting changes only on a second click; one click must leave it alone.
        Tuning.Setting guarded = first(s -> s.kind() == Tuning.Kind.TOGGLE && GoNutsApi.guarded(s.key()));
        double guardedBefore = GoNutsApi.get(guarded.key());
        clickSetting(context, guarded);
        require(GoNutsApi.get(guarded.key()) == guardedBefore, "One click changed guarded setting " + guarded.label());

        // A percentage on 0 to 100 is a slider; halfway sets 50 once the mouse is let go.
        Tuning.Setting percent = first(s -> s.kind() == Tuning.Kind.PERCENT && s.min() == 0 && s.max() == 100
                && !GoNutsApi.guarded(s.key()) && s.defaultValue() == Math.rint(s.defaultValue()));
        open(context, percent);
        GuiClicks.slide(context, percent.group() + "." + percent.key(), .5);
        require(Math.abs(GoNutsApi.get(percent.key()) - 50) <= 1, percent.label() + " is " + GoNutsApi.get(percent.key()) + ", not 50");
        GoNutsApi.reset(percent.key());

        // Minutes are typed with their unit: 2h is 120 minutes, not 2 and not 2 million.
        Tuning.Setting minutes = first(s -> s.kind() == Tuning.Kind.MINUTES && !GoNutsApi.guarded(s.key())
                && s.max() - s.min() > 200 && s.min() <= 120 && s.max() >= 120 && s.defaultValue() == Math.rint(s.defaultValue()));
        open(context, minutes);
        GuiClicks.click(context, minutes.group() + "." + minutes.key());
        context.runOnClient(client -> {
            DonutScreen screen = screen(client);
            screen.keyPressed(new KeyEvent(GLFW.GLFW_KEY_A, 0, GLFW.GLFW_MOD_CONTROL));
            "2h".codePoints().forEach(c -> screen.charTyped(new CharacterEvent(c)));
            screen.keyPressed(new KeyEvent(GLFW.GLFW_KEY_ENTER, 0, 0));
        });
        require(GoNutsApi.get(minutes.key()) == 120, minutes.label() + " read 2h as " + GoNutsApi.get(minutes.key()));
        GoNutsApi.reset(minutes.key());

        // A pasted item is added to a long list; nothing already in it is cut.
        String original = GoNutsApi.text("items.deny");
        String longList = "stone,".repeat(50);
        GoNutsApi.setText("items.deny", longList);
        GuiClicks.expand(context, "Lists");
        GuiClicks.click(context, "Lists.items.deny");
        context.runOnClient(client -> {
            client.keyboardHandler.setClipboard("dirt");
            DonutScreen screen = screen(client);
            screen.keyPressed(new KeyEvent(GLFW.GLFW_KEY_V, 0, GLFW.GLFW_MOD_CONTROL));
            screen.keyPressed(new KeyEvent(GLFW.GLFW_KEY_ENTER, 0, 0));
        });
        require(GoNutsApi.text("items.deny").equals(longList.strip() + "dirt"),
                "The deny list was cut: " + GoNutsApi.text("items.deny").length() + " characters");
        GoNutsApi.setText("items.deny", original);
        GuiClicks.collapse(context, "Lists");
    }

    /** Opens a setting's group, and its "More options" when the setting is folded there. */
    private static void open(ClientGameTestContext context, Tuning.Setting setting) {
        GuiClicks.expand(context, setting.group());
        List<Tuning.Setting> group = group(setting.group());
        int index = group.indexOf(setting);
        if (index >= 6 && !GoNutsApi.guarded(setting.key())) GuiClicks.showMore(context, setting.group());
    }

    private static void clickSetting(ClientGameTestContext context, Tuning.Setting setting) {
        open(context, setting);
        GuiClicks.click(context, setting.group() + "." + setting.key());
    }

    /** Whether a group folds some settings under "More options": past the first six, guarded ones stay up front. */
    private static boolean folded(String name) {
        List<Tuning.Setting> group = group(name);
        for (int i = 6; i < group.size(); i++) if (!GoNutsApi.guarded(group.get(i).key())) return true;
        return false;
    }

    private static List<Tuning.Setting> group(String name) {
        return GoNutsApi.settings().stream().filter(s -> s.group().equals(name)).toList();
    }

    /** The first setting matching; GoNuts ships settings of every kind these checks use. */
    private static Tuning.Setting first(Predicate<Tuning.Setting> filter) {
        return GoNutsApi.settings().stream().filter(filter).findFirst()
                .orElseThrow(() -> new AssertionError("GoNuts has no setting of the kind this check needs"));
    }

    private static DonutScreen screen(Minecraft client) {
        require(client.gui.screen() instanceof DonutScreen, "Donut screen is not open");
        return (DonutScreen) client.gui.screen();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
