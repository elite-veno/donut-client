package dev.donut.client.mixin;

import dev.donut.client.xray.XrayController;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** X-ray: hidden blocks cast no ambient-occlusion shadow on the ores and builds next to them. */
@Mixin(BlockBehaviour.BlockStateBase.class)
abstract class BlockStateBaseMixin {
    @Inject(method = "getShadeBrightness", at = @At("HEAD"), cancellable = true)
    private void donut$xrayShade(BlockGetter level, BlockPos pos, CallbackInfoReturnable<Float> cir) {
        if (XrayController.enabled() && XrayController.hides((BlockState) (Object) this)) cir.setReturnValue(1.0f);
    }
}
