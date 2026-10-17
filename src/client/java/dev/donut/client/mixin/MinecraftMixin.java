package dev.donut.client.mixin;

import dev.donut.client.freecam.FreecamController;
import dev.donut.client.schematic.SchematicBuilder;
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

    // All three must be found: one renamed in a later version would otherwise let that input through unnoticed.
    // While the schematic builder breaks a shulker box, the player's own clicks wait too: continueAttack would stop
    // the breaking every tick the attack key is up.
    @Inject(method = {"startUseItem", "pickBlockOrEntity", "continueAttack"}, at = @At("HEAD"), cancellable = true, require = 3)
    private void donut$blockInteractions(CallbackInfo ci) {
        if (FreecamController.enabled() || SchematicBuilder.holdsHands()) ci.cancel();
    }

    @Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
    private void donut$blockAttack(CallbackInfoReturnable<Boolean> cir) {
        if (FreecamController.enabled() || SchematicBuilder.holdsHands()) cir.setReturnValue(false);
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
