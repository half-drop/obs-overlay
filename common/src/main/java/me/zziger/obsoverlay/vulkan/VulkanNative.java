package me.zziger.obsoverlay.vulkan;

import com.sun.jna.Library;
import com.sun.jna.Structure;

import java.nio.charset.StandardCharsets;

/** Matches native/vulkan-overlay/include/obs_overlay_vulkan.h. */
public interface VulkanNative extends Library {
    int ABI_VERSION = 1;
    int SUCCESS = 0;
    int HUD_PREMULTIPLIED_ALPHA = 1;

    int obsvkGetAbiVersion();

    int obsvkIsDeviceReady(long device);

    int obsvkPublishHud(HudFrame frame);

    int obsvkWaitHud(long device, long image);

    int obsvkClearHud(long device, long swapchain);

    int obsvkGetLastError(byte[] destination, int capacity);

    default String lastError() {
        byte[] buffer = new byte[4096];
        obsvkGetLastError(buffer, buffer.length);
        int length = 0;
        while (length < buffer.length && buffer[length] != 0) length++;
        return new String(buffer, 0, length, StandardCharsets.UTF_8);
    }

    @Structure.FieldOrder({"structSize", "abiVersion", "device", "queue", "swapchain", "image",
            "format", "layout", "width", "height", "flags", "reserved", "frameId"})
    final class HudFrame extends Structure {
        public int structSize;
        public int abiVersion;
        public long device;
        public long queue;
        public long swapchain;
        public long image;
        public int format;
        public int layout;
        public int width;
        public int height;
        public int flags;
        public int reserved;
        public long frameId;

        public HudFrame() {
            structSize = size();
            abiVersion = ABI_VERSION;
            if (structSize != 72 || fieldOffset("frameId") != 64) {
                throw new IllegalStateException("Unsupported OBS Overlay Vulkan native structure layout");
            }
        }
    }
}
