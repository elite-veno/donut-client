package dev.donut.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.donut.client.freecam.FreecamController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
abstract class MouseHandlerMixin {
    /** A wrap, not a redirect: zoom and camera mods hook the same call, and two redirects on one call crash. */
    @WrapOperation(method = "turnPlayer", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;turn(DD)V"))
    private void donut$turnCamera(LocalPlayer player, double dx, double dy, Operation<Void> turn) {
        if (FreecamController.enabled()) FreecamController.getInstance().turn(dx, dy);
        else turn.call(player, dx, dy);
    }

    /** In Freecam the wheel changes the flight speed instead of the hotbar slot. */
    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void donut$preventHotbarScroll(long window, double horizontal, double vertical, CallbackInfo ci) {
        if (!FreecamController.enabled() || Minecraft.getInstance().gui.screen() != null) return;
        FreecamController.getInstance().scroll(vertical);
        ci.cancel();
    }
}
