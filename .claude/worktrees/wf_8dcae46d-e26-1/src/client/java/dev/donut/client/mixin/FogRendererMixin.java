package dev.donut.client.mixin;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import dev.donut.client.fullbright.FullbrightController;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Uses the fog-free buffer, as the vanilla debug fog toggle does, while Fullbright's Remove fog is on. */
@Mixin(FogRenderer.class)
abstract class FogRendererMixin {
    @Shadow @Final private GpuBuffer emptyBuffer;

    @Inject(method = "getBuffer", at = @At("HEAD"), cancellable = true)
    private void donut$noFog(FogRenderer.FogMode mode, CallbackInfoReturnable<GpuBufferSlice> cir) {
        if (FullbrightController.noFog()) cir.setReturnValue(emptyBuffer.slice(0L, FogRenderer.FOG_UBO_SIZE));
    }
}
