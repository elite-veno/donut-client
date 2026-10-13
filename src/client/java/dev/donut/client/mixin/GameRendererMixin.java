package dev.donut.client.mixin;

import dev.donut.client.freecam.FreecamController;
import dev.donut.client.render.WorldProjection;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
abstract class GameRendererMixin {
    @Inject(method = "renderItemInHand", at = @At("HEAD"), cancellable = true)
    private void donut$hideHand(CallbackInfo ci) {
        if (FreecamController.enabled()) ci.cancel();
    }

    @Inject(method = "pick", at = @At("HEAD"), cancellable = true)
    private void donut$clearTarget(CallbackInfo ci) {
        if (!FreecamController.enabled()) return;
        var client = Minecraft.getInstance();
        var pos = FreecamController.getInstance().position();
        client.hitResult = BlockHitResult.miss(pos, Direction.DOWN, BlockPos.containing(pos));
        client.crosshairPickEntity = null;
        ci.cancel();
    }

    /** Distance labels are projected with the matrix the world is drawn with, including bobbing and FOV effects. */
    @ModifyArg(method = "renderLevel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;renderLevel(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/Camera;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lorg/joml/Vector4f;Z)V"),
            index = 5)
    private Matrix4f donut$captureProjection(Matrix4f projection) {
        WorldProjection.capture(projection);
        return projection;
    }
}
