package me.zziger.obsoverlay.vulkan;

import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.vulkan.VulkanConst;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuSurface;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture;
import me.zziger.obsoverlay.error.OverlayHookException;
import me.zziger.obsoverlay.mixin.vulkan.FrontendGpuDeviceAccessor;
import me.zziger.obsoverlay.mixin.vulkan.FrontendGpuSurfaceAccessor;
import me.zziger.obsoverlay.mixin.vulkan.VulkanGpuSurfaceAccessor;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.vulkan.VK12;

/** Publishes borrowed HUD images; the native layer owns its submissions and fences. */
public final class VulkanOverlayBridge implements AutoCloseable {
    private final VulkanNative library;
    private final long device;
    private final long queue;
    private final VulkanNative.HudFrame frame = new VulkanNative.HudFrame();
    private long lastSwapchain;
    private boolean closed;

    private VulkanOverlayBridge(VulkanNative library, VulkanDevice backend) {
        this.library = library;
        device = backend.vkDevice().address();
        queue = backend.graphicsQueue().vkQueue().address();
        if (library.obsvkIsDeviceReady(device) != 1) {
            String reason = library.lastError();
            if (reason.isBlank()) reason = VulkanLayerBootstrap.failureReason();
            if (reason.isBlank()) reason = "the explicit layer was not enabled before device creation";
            throw new OverlayHookException("OBS Overlay Vulkan layer is not active for Minecraft's device: " + reason);
        }
    }

    public static VulkanOverlayBridge attach(GpuDevice gpuDevice) {
        VulkanNative library = VulkanLayerBootstrap.requireLibrary();
        if (!(gpuDevice instanceof FrontendGpuDeviceAccessor accessor)
                || !(accessor.obsOverlay$getBackend() instanceof VulkanDevice backend)) {
            throw new OverlayHookException("Could not access Minecraft's Vulkan device");
        }
        return new VulkanOverlayBridge(library, backend);
    }

    public void publishHud(@Nullable GpuTexture texture, long frameId, boolean premultiplied) {
        requireOpen();
        long swapchain = currentSwapchain();
        if (swapchain != lastSwapchain && lastSwapchain != 0) {
            check("retire the previous swapchain HUD", library.obsvkClearHud(device, lastSwapchain));
        }
        lastSwapchain = swapchain;
        if (swapchain == 0) return;

        frame.device = device;
        frame.queue = queue;
        frame.swapchain = swapchain;
        frame.frameId = frameId;
        frame.reserved = 0;
        if (texture == null) {
            // Clear just the publication; blank frames must not wait for all native fences.
            frame.image = 0;
            frame.format = 0;
            frame.layout = 0;
            frame.width = 0;
            frame.height = 0;
            frame.flags = 0;
        } else {
            VulkanGpuTexture image = requireTexture(texture);
            frame.image = image.vkImage();
            frame.format = VulkanConst.toVk(texture.getFormat());
            frame.layout = VK12.VK_IMAGE_LAYOUT_GENERAL;
            frame.width = texture.getWidth(0);
            frame.height = texture.getHeight(0);
            frame.flags = premultiplied ? VulkanNative.HUD_PREMULTIPLIED_ALPHA : 0;
        }
        check("publish the HUD", library.obsvkPublishHud(frame));
    }

    public void waitHud(GpuTexture texture) {
        requireOpen();
        check("wait for the HUD image", library.obsvkWaitHud(device, requireTexture(texture).vkImage()));
    }

    public void clearHud() {
        requireOpen();
        // Zero selects every swapchain on this Minecraft device, including a retired one.
        check("clear the HUD", library.obsvkClearHud(device, 0));
        lastSwapchain = 0;
    }

    @Override
    public void close() {
        if (closed) return;
        clearHud();
        closed = true;
    }

    private static VulkanGpuTexture requireTexture(GpuTexture texture) {
        if (!(texture instanceof VulkanGpuTexture image) || texture.isClosed()) {
            throw new OverlayHookException("OBS Overlay requires a live Vulkan HUD texture");
        }
        if ((texture.usage() & GpuTexture.USAGE_TEXTURE_BINDING) == 0) {
            throw new OverlayHookException("OBS Overlay HUD texture must support sampling");
        }
        return image;
    }

    private static long currentSwapchain() {
        Minecraft client = Minecraft.getInstance();
        if (client == null || !(client.windowSurface() instanceof FrontendGpuSurfaceAccessor accessor)
                || !(accessor.obsOverlay$getBackend() instanceof VulkanGpuSurface surface)) return 0;
        return ((VulkanGpuSurfaceAccessor) surface).obsOverlay$getSwapchain();
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("OBS Overlay Vulkan bridge is closed");
    }

    private void check(String operation, int status) {
        if (status != VulkanNative.SUCCESS) {
            throw new OverlayHookException("Failed to " + operation + " (" + status + "): " + library.lastError());
        }
    }
}
