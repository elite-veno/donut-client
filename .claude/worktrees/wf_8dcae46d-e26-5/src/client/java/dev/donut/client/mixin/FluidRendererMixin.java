package dev.donut.client.mixin;

import dev.donut.client.xray.XrayController;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** X-ray: a fluid's face against a hidden block is drawn, so lava lakes keep their sides and bottom. */
@Mixin(FluidRenderer.class)
abstract class FluidRendererMixin {
    @Inject(method = "isFaceOccludedByNeighbor", at = @At("HEAD"), cancellable = true)
    private static void donut$xrayFluidFaces(Direction direction, float height, BlockState neighbor, CallbackInfoReturnable<Boolean> cir) {
        if (XrayController.enabled() && XrayController.mayHide(neighbor)) cir.setReturnValue(false);
    }
}
