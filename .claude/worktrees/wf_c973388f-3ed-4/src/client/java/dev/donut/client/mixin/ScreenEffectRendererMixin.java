package dev.donut.client.mixin;

import dev.donut.client.freecam.FreecamController;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(ScreenEffectRenderer.class)
abstract class ScreenEffectRendererMixin {
    /**
     * The free camera is not where the player's body is, so the body's in-wall, water and fire overlays are left out.
     * The totem animation, drawn by the same method, still plays. Without this hook those overlays simply show.
     */
    @ModifyVariable(method = "submit", at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 0)
    private boolean donut$notFirstPerson(boolean isFirstPerson) {
        return isFirstPerson && !FreecamController.enabled();
    }
}
