package dev.donut.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.donut.client.fullbright.FullbrightController;
import net.minecraft.client.renderer.LightmapRenderStateExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Slice;

/** Fullbright raises only the brightness the lightmap is built with; the saved option is untouched. */
@Mixin(LightmapRenderStateExtractor.class)
abstract class LightTextureMixin {
    // The first option read after gamma() is gamma's own; the darkness effect scale is read right after it.
    @ModifyExpressionValue(method = "extract", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/OptionInstance;get()Ljava/lang/Object;", ordinal = 0),
            slice = @Slice(from = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/Options;gamma()Lnet/minecraft/client/OptionInstance;")))
    private Object donut$fullbrightGamma(Object gamma) {
        return FullbrightController.effectiveGamma((Double) gamma);
    }
}
