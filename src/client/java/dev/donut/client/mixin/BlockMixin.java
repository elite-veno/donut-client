package dev.donut.client.mixin;

import dev.donut.client.xray.XrayController;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** X-ray: a visible block's face against a hidden neighbor is no longer covered, so it is drawn. */
@Mixin(Block.class)
abstract class BlockMixin {
    @Inject(method = "shouldRenderFace(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/Direction;)Z",
            at = @At("HEAD"), cancellable = true)
    private static void donut$xrayFaces(BlockState state, BlockState neighbor, Direction direction, CallbackInfoReturnable<Boolean> cir) {
        if (XrayController.enabled() && XrayController.mayHide(neighbor)) cir.setReturnValue(true);
    }
}
