package dev.donut.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.donut.client.fullbright.FullbrightController;
import net.minecraft.client.renderer.LightTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Slice;

/** Fullbright raises only the brightness the lightmap is built with; the saved option is untouched. */
@Mixin(LightTexture.class)
abstract class LightTextureMixin {
    @ModifyExpressionValue(method = "updateLightTexture", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/OptionInstance;get()Ljava/lang/Object;"),
            slice = @Slice(from = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/Options;gamma()Lnet/minecraft/client/OptionInstance;")))
    private Object donut$fullbrightGamma(Object gamma) {
        return FullbrightController.effectiveGamma((Double) gamma);
    }
}
