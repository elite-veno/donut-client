package dev.donut.client.input;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** The click GUI edits the same mappings exposed in Minecraft's Controls screen. */
public final class ModuleKeybinds {
    public static final int COUNT = 12;
    private static final KeyMapping[] MODULES = new KeyMapping[COUNT];
    private static final boolean[] pressed = new boolean[COUNT];
    private static final Set<InputConstants.Key> heldInputs = new HashSet<>();
    private static boolean discardNextTick;

    private ModuleKeybinds() { }

    /**
     * In module order: storage, spawner, freecam, debris, activity, Auto RTP, X-ray, Fullbright, entities, tunnels, bases,
     * schematic builder.
     * Modules without a mapping stay unbound.
     */
    public static void initialize(KeyMapping... modules) {
        Arrays.fill(MODULES, null);
        System.arraycopy(modules, 0, MODULES, 0, Math.min(modules.length, COUNT));
        Arrays.fill(pressed, false);
        heldInputs.clear();
        discardNextTick = false;
    }

    public static KeyMapping mapping(int index) { return index >= 0 && index < COUNT ? MODULES[index] : null; }

    public static void setKey(Minecraft client, int index, InputConstants.Key key) {
        KeyMapping mapping = mapping(index);
        if (mapping == null) return;
        mapping.setKey(key);
        pressed[index] = false;
        mapping.setDown(false);
        while (mapping.consumeClick()) { }
        KeyMapping.resetMapping();
        client.options.save();
        if (!key.equals(InputConstants.UNKNOWN)) heldInputs.add(key);
        discardNextTick = true;
    }

    /** Drain every tick, even in menus; native key repeat must not toggle a module repeatedly. */
    public static boolean consumePress(Minecraft client, int index) {
        KeyMapping mapping = mapping(index);
        if (mapping == null) return false;
        boolean clicked = false;
        while (mapping.consumeClick()) clicked = true;
        boolean firstPress = clicked && !pressed[index];
        // A short press released between ticks still has a queued click and fires once.
        pressed[index] = isHeld(client, mapping) && (pressed[index] || clicked);
        return firstPress;
    }

    /**
     * Whether the mapping's key is physically held. A toggle mapping's isDown is its toggle state instead, but GLFW can
     * only poll by key symbol, so a key bound by scancode alone relies on the mapping, which key events keep current.
     */
    public static boolean isHeld(Minecraft client, KeyMapping mapping) {
        InputConstants.Key key = InputConstants.getKey(mapping.saveString());
        if (key.getType() == InputConstants.Type.SCANCODE) return mapping.isDown();
        return isHeld(client, key);
    }

    /** Keep the input that assigned a binding or closed the menu from toggling a module. */
    public static void suppressUntilReleased(Minecraft client) {
        for (KeyMapping mapping : MODULES) {
            if (mapping == null) continue;
            rememberHeld(client, mapping);
            mapping.setDown(false);
            while (mapping.consumeClick()) { }
        }
        KeyMapping open = KeyMapping.get("key.donut.open_gui");
        if (open != null) rememberHeld(client, open);
        discardNextTick = true;
    }

    private static void rememberHeld(Minecraft client, KeyMapping mapping) {
        InputConstants.Key key = InputConstants.getKey(mapping.saveString());
        if (isHeld(client, key)) heldInputs.add(key);
    }

    /** Call once each client tick, and consume queued mapping clicks even when blocked. */
    public static boolean inputBlocked(Minecraft client) {
        boolean blocked = client.gui.screen() != null || discardNextTick || !heldInputs.isEmpty();
        discardNextTick = false;
        heldInputs.removeIf(key -> !isHeld(client, key));
        return blocked;
    }

    private static boolean isHeld(Minecraft client, InputConstants.Key key) {
        if (key.equals(InputConstants.UNKNOWN)) return false;
        if (key.getType() == InputConstants.Type.MOUSE) {
            return GLFW.glfwGetMouseButton(client.getWindow().handle(), key.getValue()) == GLFW.GLFW_PRESS;
        }
        if (key.getType() == InputConstants.Type.KEYSYM) {
            return InputConstants.isKeyDown(client.getWindow(), key.getValue());
        }
        // GLFW exposes polling by key symbol, so resolve unusual scancode-only keys first.
        for (int code = GLFW.GLFW_KEY_SPACE; code <= GLFW.GLFW_KEY_LAST; code++) {
            if (validKeySymbol(code) && GLFW.glfwGetKeyScancode(code) == key.getValue()
                    && InputConstants.isKeyDown(client.getWindow(), code)) return true;
        }
        return false;
    }

    private static boolean validKeySymbol(int code) {
        return code == 32 || code == 39 || code >= 44 && code <= 57 || code == 59 || code == 61
                || code >= 65 && code <= 93 || code == 96 || code >= 161 && code <= 162
                || code >= 256 && code <= 269 || code >= 280 && code <= 284
                || code >= 290 && code <= 314 || code >= 320 && code <= 336 || code >= 340 && code <= 348;
    }
}
