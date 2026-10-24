package dev.donut.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * The hurt tilt and view bobbing the world is about to be drawn with. Finder overlays are extracted before the level
 * renders, so they rebuild this frame's projection from the same steps instead of reusing last frame's.
 */
@Mixin(GameRenderer.class)
public interface GameRendererAccess {
    @Invoker("bobHurt")
    void donut$bobHurt(CameraRenderState cameraState, PoseStack poseStack);

    @Invoker("bobView")
    void donut$bobView(CameraRenderState cameraState, PoseStack poseStack);
}
