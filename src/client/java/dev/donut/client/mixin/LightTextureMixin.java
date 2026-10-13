package dev.donut.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.donut.client.fullbright.FullbrightController;
import net.minecraft.client.renderer.LightmapRenderStateExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Slice;

/**
 * Fullbright raises only the brightness the lightmap is built with; the saved options are untouched. Both hooks are
 * optional: if another mod rewrites this method, Fullbright stops working rather than the game failing to start.
 */
@Mixin(LightmapRenderStateExtractor.class)
abstract class LightTextureMixin {
    // The first option read after gamma() is gamma's own; the darkness effect scale is read right after it.
    @ModifyExpressionValue(method = "extract", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/OptionInstance;get()Ljava/lang/Object;", ordinal = 0),
            slice = @Slice(from = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/Options;gamma()Lnet/minecraft/client/OptionInstance;")), require = 0)
    private Object donut$fullbrightGamma(Object gamma) {
        return FullbrightController.effectiveGamma((Double) gamma);
    }

    /**
     * The Darkness effect darkens the lightmap by a pulsing amount after its brightness is applied, which a raised
     * brightness turns into black and white flashes; while the world is brightened, the effect's darkening is left out.
     */
    @ModifyExpressionValue(method = "extract", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/OptionInstance;get()Ljava/lang/Object;", ordinal = 1),
            slice = @Slice(from = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/Options;gamma()Lnet/minecraft/client/OptionInstance;")), require = 0)
    private Object donut$fullbrightDarkness(Object scale) {
        // Fullbright (or X-ray) brightens the world exactly when it overrides gamma.
        return FullbrightController.effectiveGamma(0) > 0 ? (Object) 0.0 : scale;
    }
}
