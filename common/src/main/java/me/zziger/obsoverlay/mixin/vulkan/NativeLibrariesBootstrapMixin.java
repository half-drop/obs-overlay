package me.zziger.obsoverlay.mixin.vulkan;

import com.mojang.blaze3d.platform.NativeLibrariesBootstrap;
import me.zziger.obsoverlay.vulkan.VulkanLayerBootstrap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(NativeLibrariesBootstrap.class)
public class NativeLibrariesBootstrapMixin {
    @Inject(method = "tryLoadingVulkan()Z", at = @At("HEAD"))
    private static void obsOverlay$prepareVulkanLayer(CallbackInfoReturnable<Boolean> cir) {
        VulkanLayerBootstrap.prepare();
    }
}
