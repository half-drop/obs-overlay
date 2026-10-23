package me.zziger.obsoverlay.mixin.vulkan;

import me.zziger.obsoverlay.OBSOverlay;
import me.zziger.obsoverlay.OverlayRenderer;
import me.zziger.obsoverlay.error.OverlayHookException;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class MinecraftVulkanMixin {
    @Inject(method = "renderFrame(Z)V", at = @At(value = "INVOKE", target =
            "Lcom/mojang/renderpearl/api/device/GpuSurface;blitFromTexture(Lcom/mojang/renderpearl/api/commands/CommandEncoder;Lcom/mojang/renderpearl/api/textures/GpuTextureView;)V",
            shift = At.Shift.BEFORE))
    private void obsOverlay$finishFrame(boolean renderLevel, CallbackInfo ci) {
        OverlayRenderer renderer = OBSOverlay.getRenderer();
        if (renderer != null) {
            try {
                renderer.finishFrame();
            } catch (OverlayHookException failure) {
                OBSOverlay.handleRenderFailure(failure);
            }
        }
    }
}
