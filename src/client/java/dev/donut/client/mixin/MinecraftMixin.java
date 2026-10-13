package dev.donut.client.mixin;

import dev.donut.client.freecam.FreecamController;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
abstract class MinecraftMixin {
    @Shadow public HitResult hitResult;
    @Shadow public Entity crosshairPickEntity;

    @Inject(method = "handleKeybinds", at = @At("HEAD"))
    private void donut$clearActions(CallbackInfo ci) {
        if (FreecamController.enabled()) FreecamController.getInstance().suppressActions((Minecraft) (Object) this);
    }

    @Inject(method = {"startUseItem", "pickBlockOrEntity", "continueAttack"}, at = @At("HEAD"), cancellable = true)
    private void donut$blockInteractions(CallbackInfo ci) {
        if (FreecamController.enabled()) ci.cancel();
    }

    @Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
    private void donut$blockAttack(CallbackInfoReturnable<Boolean> cir) {
        if (FreecamController.enabled()) cir.setReturnValue(false);
    }

    /** The free camera aims at nothing, so nothing can be targeted or highlighted through it. */
    @Inject(method = "pick", at = @At("HEAD"), cancellable = true)
    private void donut$clearTarget(float partialTicks, CallbackInfo ci) {
        if (!FreecamController.enabled()) return;
        var pos = FreecamController.getInstance().position();
        hitResult = BlockHitResult.miss(pos, Direction.DOWN, BlockPos.containing(pos));
        crosshairPickEntity = null;
        ci.cancel();
    }
}
