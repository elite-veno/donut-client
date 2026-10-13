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
 * a guarded setting ignores a single click and a guarded typed value a single Enter, Esc drops an edit, typed values read
 * their units, and a long list is never cut. Start is never clicked, so nothing trades. GoNuts keeps its settings in the
 * test run's own game folder.
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
            if (group(group).isEmpty()) continue;
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

        // A setting changed from its default shows a Reset link that puts the default back.
        if (GoNutsApi.isDefault(toggle.key())) clickSetting(context, toggle);
        GuiClicks.settle(context);
        screenshot.accept("donut-02-clickgui-gonuts-changed");
        GuiClicks.click(context, toggle.group() + "." + toggle.key() + ".reset");
        require(GoNutsApi.isDefault(toggle.key()), "Reset did not put " + toggle.label() + " back to its default");

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

        // Minutes are typed with their unit: 2h is 120 minutes, not 2 and not 2 million. Clicking the field selects its
        // text, so what is typed replaces it.
        Tuning.Setting minutes = first(s -> s.kind() == Tuning.Kind.MINUTES && !GoNutsApi.guarded(s.key())
                && s.max() - s.min() > 200 && s.min() <= 120 && s.max() >= 120 && s.defaultValue() == Math.rint(s.defaultValue()));
        open(context, minutes);
        GuiClicks.click(context, minutes.group() + "." + minutes.key());
        context.runOnClient(client -> {
            DonutScreen screen = screen(client);
            type(screen, "2h");
            press(screen, GLFW.GLFW_KEY_ENTER, 0);
        });
        require(GoNutsApi.get(minutes.key()) == 120, minutes.label() + " read 2h as " + GoNutsApi.get(minutes.key()));

        // Esc leaves a field as it was.
        GuiClicks.click(context, minutes.group() + "." + minutes.key());
        context.runOnClient(client -> {
            DonutScreen screen = screen(client);
            type(screen, "3h");
            press(screen, GLFW.GLFW_KEY_ESCAPE, 0);
        });
        require(GoNutsApi.get(minutes.key()) == 120, "Esc saved " + minutes.label() + " as " + GoNutsApi.get(minutes.key()));
        GoNutsApi.reset(minutes.key());

        // A guarded typed value saves only on a second Enter: the first one says what it would set.
        Tuning.Setting money = first(s -> s.kind() == Tuning.Kind.COUNT && GoNutsApi.guarded(s.key()) && s.max() >= 2_000_000
                && s.min() <= 1_500_000 && GoNutsApi.get(s.key()) != 1_500_000);
        double moneyBefore = GoNutsApi.get(money.key());
        open(context, money);
        GuiClicks.click(context, money.group() + "." + money.key());
        context.runOnClient(client -> {
            DonutScreen screen = screen(client);
            // A decimal comma, as many keyboards type it: 1,5m is a million and a half, not fifteen million.
            type(screen, "1,5m");
            press(screen, GLFW.GLFW_KEY_ENTER, 0);
        });
        require(GoNutsApi.get(money.key()) == moneyBefore, "One Enter changed guarded setting " + money.label());
        GuiClicks.settle(context);
        screenshot.accept("donut-02-clickgui-gonuts-confirm");
        context.runOnClient(client -> press(screen(client), GLFW.GLFW_KEY_ENTER, 0));
        require(GoNutsApi.get(money.key()) == 1_500_000, money.label() + " read 1,5m as " + GoNutsApi.get(money.key()));
        GoNutsApi.set(money.key(), moneyBefore);

        // A pasted item is added to a long list; nothing already in it is cut. End leaves the selection, so the paste
        // is added rather than replacing the list.
        String original = GoNutsApi.text("items.deny");
        String longList = "stone,".repeat(50);
        GoNutsApi.setText("items.deny", longList);
        GuiClicks.expand(context, "Lists");
        GuiClicks.click(context, "Lists.items.deny");
        context.runOnClient(client -> {
            client.keyboardHandler.setClipboard("dirt");
            DonutScreen screen = screen(client);
            press(screen, GLFW.GLFW_KEY_END, 0);
            press(screen, GLFW.GLFW_KEY_V, GLFW.GLFW_MOD_CONTROL);
            press(screen, GLFW.GLFW_KEY_ENTER, 0);
        });
        require(GoNutsApi.text("items.deny").equals(longList.strip() + "dirt"),
                "The deny list was cut: " + GoNutsApi.text("items.deny").length() + " characters");
        GoNutsApi.setText("items.deny", original);
        GuiClicks.collapse(context, "Lists");
    }

    /** A key pressed and let go, as a person types it; a held Enter counts once. */
    private static void press(DonutScreen screen, int key, int modifiers) {
        screen.keyPressed(new KeyEvent(key, 0, modifiers));
        screen.keyReleased(new KeyEvent(key, 0, modifiers));
    }

    private static void type(DonutScreen screen, String text) {
        text.codePoints().forEach(c -> screen.charTyped(new CharacterEvent(c)));
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

    /** A group's settings as the menu shows them, without the ones GoNuts keeps for itself. */
    private static List<Tuning.Setting> group(String name) {
        return GoNutsApi.settings().stream().filter(s -> s.group().equals(name) && !DonutScreen.GONUTS_HIDDEN.contains(s.key())).toList();
    }

    /** The first setting matching; GoNuts ships settings of every kind these checks use. */
    private static Tuning.Setting first(Predicate<Tuning.Setting> filter) {
        return GoNutsApi.settings().stream().filter(s -> !DonutScreen.GONUTS_HIDDEN.contains(s.key())).filter(filter).findFirst()
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
