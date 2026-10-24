package dev.donut.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.donut.client.xray.XrayController;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * X-ray: the mesh builder sees hidden blocks as air. They draw nothing, and the section stays see-through
 * for occlusion culling, so caves behind them are rendered too. Fabric's renderer hooks later in the same
 * loop and receives the replaced state.
 */
@Mixin(SectionCompiler.class)
abstract class SectionCompilerMixin {
    @WrapOperation(method = "compile", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/chunk/RenderSectionRegion;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"))
    private BlockState donut$xray(RenderSectionRegion region, BlockPos pos, Operation<BlockState> original) {
        BlockState state = original.call(region, pos);
        return XrayController.enabled() ? XrayController.filter(region, pos, state) : state;
    }
}
