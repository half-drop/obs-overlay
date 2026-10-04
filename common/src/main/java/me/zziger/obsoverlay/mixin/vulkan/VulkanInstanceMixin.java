package me.zziger.obsoverlay.mixin.vulkan;

import com.mojang.renderpearl.backend.vulkan.VulkanInstance;
import me.zziger.obsoverlay.vulkan.VulkanLayerBootstrap;
import org.lwjgl.PointerBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(VulkanInstance.class)
public class VulkanInstanceMixin {
    @ModifyArg(method = "<init>(IZZ)V", at = @At(value = "INVOKE", target =
            "Lorg/lwjgl/vulkan/VkInstanceCreateInfo;ppEnabledLayerNames(Lorg/lwjgl/PointerBuffer;)Lorg/lwjgl/vulkan/VkInstanceCreateInfo;"),
            index = 0)
    private PointerBuffer obsOverlay$enableVulkanLayer(PointerBuffer original) {
        return VulkanLayerBootstrap.enabledLayers(original);
    }
}
