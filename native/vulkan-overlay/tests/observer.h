#ifndef OBS_OVERLAY_TEST_OBSERVER_H
#define OBS_OVERLAY_TEST_OBSERVER_H
#include <stdint.h>
#include <vulkan/vulkan.h>
#define OBSERVER_LAYER_NAME "VK_LAYER_HALFDROP_test_observer"
#if defined(__GNUC__)
#define OBSERVER_API __attribute__((visibility("default")))
#else
#define OBSERVER_API
#endif
#ifdef __cplusplus
extern "C" {
#endif
/* Single-threaded test observer. It never blends or creates HUD pixels.
 * Configure with count=0 to release the previous configuration.
 * Every supplied swapchain image must permit TRANSFER_SRC usage.
 */
OBSERVER_API int observerConfigure(VkPhysicalDevice physical, VkDevice device,
    VkQueue queue, uint32_t family, VkSwapchainKHR swapchain,
    const VkImage *images, uint32_t count, VkFormat format,
    uint32_t width, uint32_t height, const char *outdir);
/* Marks a frame pending. Status becomes 0 only after its actual present was
 * intercepted and the real swapchain bytes were successfully read and saved.
 */
OBSERVER_API void observerFrame(uint64_t frame_id);
OBSERVER_API int observerStatus(void);
OBSERVER_API const char *observerError(void);
OBSERVER_API uint64_t observerCapturedFrames(void);
#ifdef __cplusplus
}
#endif
#endif
