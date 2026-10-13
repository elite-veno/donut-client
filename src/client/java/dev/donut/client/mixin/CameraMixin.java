package dev.donut.client.mixin;

import dev.donut.client.freecam.FreecamController;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Camera.class)
abstract class CameraMixin {
    @Shadow private boolean detached;
    @Shadow private Level level;
    @Shadow protected abstract void setPosition(Vec3 position);
    @Shadow protected abstract void setRotation(float yaw, float pitch);
    @Shadow public abstract BlockPos blockPosition();

    /** Places the camera before the culling frustum is built from its position. */
    @Inject(method = "alignWithEntity", at = @At("RETURN"))
    private void donut$freeCamera(float partialTick, CallbackInfo ci) {
        if (!FreecamController.enabled()) return;
        FreecamController freecam = FreecamController.getInstance();
        setPosition(freecam.interpolatedPosition(partialTick));
        setRotation(freecam.yaw(), freecam.pitch());
        detached = true;
    }

    /** Renders terrain as a spectator's camera would when the free camera is inside an opaque block. */
    @Inject(method = "extractRenderState", at = @At("RETURN"))
    private void donut$freecamTerrain(CameraRenderState state, float partialTick, CallbackInfo ci) {
        if (FreecamController.enabled() && level != null && level.getBlockState(blockPosition()).isSolidRender()) {
            state.smartCull = false;
        }
    }
}
