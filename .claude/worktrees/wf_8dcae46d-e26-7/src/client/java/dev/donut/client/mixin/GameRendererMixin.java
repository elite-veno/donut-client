package dev.donut.client.mixin;

import dev.donut.client.freecam.FreecamController;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
abstract class GameRendererMixin {
    /** The free camera shows no hand; a mod that rewrites this method costs that detail, not the game's start. */
    @Inject(method = "renderItemInHand", at = @At("HEAD"), cancellable = true, require = 0)
    private void donut$hideHand(CallbackInfo ci) {
        if (FreecamController.enabled()) ci.cancel();
    }
}
