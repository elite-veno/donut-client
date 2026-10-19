package dev.donut.client.freecam;

import dev.donut.client.GoNutsGate;
import dev.donut.client.config.ClientConfig;
import dev.donut.client.config.FreecamSettings;
import dev.donut.client.input.ModuleKeybinds;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

/** Camera-only flight. Never writes a player transform, abilities, gamemode or movement packet. */
public final class FreecamController {
    private static FreecamController instance;
    private final ClientConfig config;
    /** The body keeps the sneak it had: standing up would show its nametag through walls and drop it off ledges. */
    private boolean sneaking;
    private final ClientInput neutralInput = new ClientInput() {
        @Override public void tick() {
            keyPresses = sneaking ? new Input(false, false, false, false, false, true, false) : Input.EMPTY;
            moveVector = Vec2.ZERO;
        }
        // Vanilla auto-jump can call this after input.tick(). It must stay neutral too.
        @Override public void makeJump() { }
    };
    /** Toggle Sneak and Toggle Sprint states when Freecam started; keys pressed while flying must not change them. */
    private boolean savedSneakToggle, savedSprintToggle;
    private LocalPlayer player;
    private ClientLevel level;
    private ClientInput savedInput;
    private boolean active;
    private Vec3 position = Vec3.ZERO;
    private Vec3 previousPosition = Vec3.ZERO;
    private float yaw;
    private float pitch;
    private float lastHealth;

    public FreecamController(ClientConfig config) {
        this.config = config;
        instance = this;
    }

    public static FreecamController getInstance() { return instance; }
    public static boolean enabled() { return instance != null && instance.isActive(); }
    public FreecamSettings settings() { return config.freecam; }
    public Vec3 position() { return position; }
    public float yaw() { return yaw; }
    public float pitch() { return pitch; }

    public boolean isActive() {
        Minecraft client = Minecraft.getInstance();
        return active && client.player == player && client.level == level
                && player != null && player.isAlive() && !player.isRemoved()
                && client.getCameraEntity() == player;
    }

    public void setEnabled(boolean enabled) {
        if (!enabled) { disable(); return; }
        if (isActive()) { settings().enabled = true; return; }
        disable();
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null || !client.player.isAlive()
                || client.getCameraEntity() != client.player) return;
        // Releasing a charged bow/trident would be a gameplay action. Let the user finish first.
        if (client.player.isUsingItem()) {
            client.player.sendOverlayMessage(Component.literal("Release the item before enabling Freecam."));
            return;
        }
        // The body would keep gliding with nobody steering it.
        if (client.player.isFallFlying()) {
            client.player.sendOverlayMessage(Component.literal("Land before enabling Freecam."));
            return;
        }
        // GoNuts clicks through the crosshair, which Freecam takes away.
        if (GoNutsGate.busy()) {
            client.player.sendOverlayMessage(Component.literal("GoNuts is trading or buying; stop it before enabling Freecam."));
            return;
        }
        settings().sanitize();
        player = client.player;
        level = client.level;
        savedInput = player.input;
        sneaking = savedInput.keyPresses.shift();
        lastHealth = player.getHealth();
        savedSneakToggle = client.options.keyShift.isDown();
        savedSprintToggle = client.options.keySprint.isDown();
        neutralInput.tick();
        player.input = neutralInput;
        position = player.getEyePosition();
        previousPosition = position;
        yaw = player.getYRot();
        pitch = player.getXRot();
        if (client.gameMode != null) client.gameMode.stopDestroyBlock();
        suppressActions(client);
        active = true;
        settings().enabled = true;
    }

    public void disable() {
        if (player != null && player.input == neutralInput && savedInput != null) player.input = savedInput;
        if (active) {
            Minecraft client = Minecraft.getInstance();
            suppressActions(client);
            releaseMovement(client);
        }
        active = false;
        settings().enabled = false;
        player = null;
        level = null;
        savedInput = null;
    }

    public void tick(Minecraft client) {
        if (active && !isActive()) {
            disable();
            // Death, a dimension change or another camera ended it; say so rather than vanish.
            if (client.player != null) client.player.sendOverlayMessage(Component.literal("Freecam off"));
            return;
        }
        if (settings().enabled != active) setEnabled(settings().enabled);
        if (!isActive()) return;
        if (GoNutsGate.busy()) {
            disable();
            client.player.sendOverlayMessage(Component.literal("Freecam off: GoNuts started trading or buying"));
            return;
        }
        float health = player.getHealth();
        if (settings().exitWhenHurt && health < lastHealth) {
            disable();
            client.player.sendOverlayMessage(Component.literal("Freecam off: you took damage"));
            return;
        }
        lastHealth = health;
        suppressActions(client);
        previousPosition = position;
        if (client.gui.screen() != null || client.isPaused()) return;
        var options = client.options;
        double forward = axis(options.keyUp, options.keyDown);
        double strafe = axis(options.keyRight, options.keyLeft);
        // Sneak and sprint report their toggle state when set to Toggle; flying needs the key itself.
        double vertical = (options.keyJump.isDown() ? 1 : 0) - (ModuleKeybinds.isHeld(client, options.keyShift) ? 1 : 0);
        Vec3 look = Vec3.directionFromRotation(pitch, yaw);
        double radians = Math.toRadians(yaw);
        Vec3 motion = look.scale(forward).add(-Math.cos(radians) * strafe, vertical, -Math.sin(radians) * strafe);
        if (motion.lengthSqr() > 1.0e-8) {
            double speed = settings().speed * (ModuleKeybinds.isHeld(client, options.keySprint) ? settings().boostMultiplier : 1);
            Vec3 next = position.add(motion.normalize().scale(speed));
            // Keep all render matrices finite and within Minecraft's supported horizontal world bounds.
            position = new Vec3(Math.clamp(next.x, -29_999_984, 29_999_984),
                    Math.clamp(next.y, -2048, 4096), Math.clamp(next.z, -29_999_984, 29_999_984));
        }
    }

    public Vec3 interpolatedPosition(float partialTick) {
        return previousPosition.lerp(position, Math.clamp(partialTick, 0, 1));
    }

    /** One wheel notch changes the speed by a tenth, within the setting's limits. */
    public void scroll(double notches) {
        if (!isActive() || notches == 0) return;
        settings().speed = settings().speed * Math.pow(1.1, Math.signum(notches));
        settings().sanitize();
        player.sendOverlayMessage(Component.literal("Freecam speed %.1f".formatted(settings().speed)));
    }

    /** Arguments have already been sensitivity-scaled by Minecraft's mouse handler. */
    public void turn(double dx, double dy) {
        if (!isActive() || Minecraft.getInstance().gui.screen() != null) return;
        yaw = net.minecraft.util.Mth.wrapDegrees(yaw + (float) (dx * 0.15));
        pitch = Math.clamp(pitch + (float) (dy * 0.15), -90, 90);
    }

    private static double axis(KeyMapping positive, KeyMapping negative) {
        return (positive.isDown() ? 1 : 0) - (negative.isDown() ? 1 : 0);
    }

    /** Drain action clicks as well as held states so they cannot replay after exiting Freecam. */
    public void suppressActions(Minecraft client) {
        var options = client.options;
        release(options.keyAttack);
        release(options.keyUse);
        release(options.keyPickItem);
        release(options.keyDrop);
        release(options.keySwapOffhand);
        for (KeyMapping slot : options.keyHotbarSlots) release(slot);
    }

    /**
     * Keys held when Freecam ends move the body only after a fresh press, and toggled sneak and sprint return to how
     * they were when it started.
     */
    private void releaseMovement(Minecraft client) {
        var options = client.options;
        for (KeyMapping key : new KeyMapping[] {options.keyUp, options.keyDown, options.keyLeft, options.keyRight, options.keyJump}) {
            key.setDown(false);
        }
        restore(options.keyShift, options.toggleCrouch().get(), savedSneakToggle);
        restore(options.keySprint, options.toggleSprint().get(), savedSprintToggle);
    }

    private static void restore(KeyMapping key, boolean toggle, boolean saved) {
        if (!toggle) {
            key.setDown(false);
            return;
        }
        // In toggle mode a press flips the state.
        if (key.isDown() != saved) key.setDown(true);
    }

    private static void release(KeyMapping key) {
        key.setDown(false);
        // Set to Toggle, setDown(false) does nothing and a press flips it: turn a toggled attack or use off.
        if (key.isDown()) key.setDown(true);
        while (key.consumeClick()) { }
    }
}
