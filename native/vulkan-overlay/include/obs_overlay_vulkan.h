/* SPDX-License-Identifier: MIT
 * Copyright (c) 2026 half-drop contributors
 */
#ifndef OBS_OVERLAY_VULKAN_H
#define OBS_OVERLAY_VULKAN_H

#include <stdint.h>

#if defined(_WIN32)
# if defined(OBSVK_BUILD)
#  define OBSVK_API __declspec(dllexport)
# else
#  define OBSVK_API __declspec(dllimport)
# endif
#else
# define OBSVK_API __attribute__((visibility("default")))
#endif

#ifdef __cplusplus
extern "C" {
#endif

#define OBSVK_ABI_VERSION 1u
#define OBSVK_LAYER_NAME "VK_LAYER_HALFDROP_obs_overlay"
#define OBSVK_HUD_PREMULTIPLIED_ALPHA 1u

typedef enum ObsVkResult {
    OBSVK_SUCCESS = 0,
    OBSVK_ERROR_ARGUMENT = -1,
    OBSVK_ERROR_NOT_READY = -2,
    OBSVK_ERROR_UNSUPPORTED = -3,
    OBSVK_ERROR_VULKAN = -4,
    OBSVK_ERROR_OUT_OF_MEMORY = -5
} ObsVkResult;

/* Fixed 72-byte, eight-byte-aligned ABI. All handles are unsigned 64-bit
 * values, including dispatchable handles. The library does not own image.
 * Publish on the render thread before the corresponding QueuePresentKHR.
 * The HUD must be produced on queue before that present, and its production
 * must be covered by that present's incoming wait semaphores.
 * layout is VK_IMAGE_LAYOUT_GENERAL or SHADER_READ_ONLY_OPTIMAL and remains
 * unchanged. image must have SAMPLED usage and be a single-sampled 2D image.
 * Call obsvkWaitHud before writing/reusing a slot; ClearHud before destroying
 * any HUD image. flags=1 means RGB is already multiplied by alpha. A frame
 * with image=0 revokes the publication without waiting for previous reads.
 * HUD coordinates match RenderPearl: presentation flips the texture's Y axis.
 */
typedef struct ObsVkHudFrame {
    uint32_t struct_size;
    uint32_t abi_version;
    uint64_t device;
    uint64_t queue;
    uint64_t swapchain;
    uint64_t image;
    uint32_t format;
    uint32_t layout;
    uint32_t width;
    uint32_t height;
    uint32_t flags;
    uint32_t reserved;
    uint64_t frame_id;
} ObsVkHudFrame;

OBSVK_API uint32_t obsvkGetAbiVersion(void);
/* 1 if the explicit layer tracks a usable graphics device, 0 otherwise. */
OBSVK_API int32_t obsvkIsDeviceReady(uint64_t device);
OBSVK_API int32_t obsvkPublishHud(const ObsVkHudFrame *frame);
/* Waits only for native submissions reading this image. */
OBSVK_API int32_t obsvkWaitHud(uint64_t device, uint64_t image);
/* A zero swapchain clears all HUDs on device. Waits for native HUD reads. */
OBSVK_API int32_t obsvkClearHud(uint64_t device, uint64_t swapchain);
/* Copies the last error as UTF-8, always NUL-terminating if capacity > 0.
 * Returns the required buffer size including the NUL terminator.
 */
OBSVK_API uint32_t obsvkGetLastError(char *destination, uint32_t capacity);

#ifdef __cplusplus
}
static_assert(sizeof(ObsVkHudFrame) == 72, "ObsVkHudFrame ABI size");
#endif

#endif
