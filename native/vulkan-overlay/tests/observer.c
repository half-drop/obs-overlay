/* Test-only Vulkan layer: read back the real post-overlay swapchain pixels.
 * No rendering pipelines, shaders, alpha blending, or production algorithms.
 * Deliberately synchronous readback makes the pixel oracle deterministic.
 */
#include "observer.h"
#include <vulkan/vk_layer.h>
#include <inttypes.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <errno.h>

typedef struct InstanceState {
    VkInstance instance;
    void *key;
    PFN_vkGetInstanceProcAddr gipa;
    PFN_GetPhysicalDeviceProcAddr gpdpa;
    struct InstanceState *next;
} InstanceState;

#define DEVICE_FUNCTIONS(X) \
    X(DestroyDevice) X(DeviceWaitIdle) X(CreateCommandPool) X(DestroyCommandPool) \
    X(AllocateCommandBuffers) X(ResetCommandBuffer) X(BeginCommandBuffer) \
    X(EndCommandBuffer) X(CreateBuffer) X(DestroyBuffer) \
    X(GetBufferMemoryRequirements) X(AllocateMemory) X(FreeMemory) \
    X(BindBufferMemory) X(MapMemory) X(UnmapMemory) X(InvalidateMappedMemoryRanges) \
    X(CreateFence) X(DestroyFence) X(ResetFences) X(WaitForFences) \
    X(CmdPipelineBarrier) X(CmdCopyImageToBuffer) X(QueueSubmit) X(QueuePresentKHR)

typedef struct DeviceState {
    VkDevice device;
    VkPhysicalDevice physical;
    void *key;
    InstanceState *instance;
    PFN_vkGetDeviceProcAddr gdpa;
    PFN_vkSetDeviceLoaderData set_loader_data;
#define DECLARE_FN(name) PFN_vk##name name;
    DEVICE_FUNCTIONS(DECLARE_FN)
#undef DECLARE_FN
    struct DeviceState *next;
} DeviceState;

typedef struct ObserverState {
    DeviceState *device;
    VkQueue queue;
    VkSwapchainKHR swapchain;
    VkImage *images;
    uint32_t image_count;
    uint32_t width, height;
    VkFormat format;
    VkCommandPool pool;
    VkCommandBuffer command;
    VkBuffer buffer;
    VkDeviceMemory memory;
    VkFence fence;
    void *mapped;
    VkBool32 coherent;
    uint64_t frame_id;
    uint64_t captured_frames;
    int awaiting_frame;
    int status;
    char error[512];
    char outdir[2048];
} ObserverState;

static InstanceState *instances;
static DeviceState *devices;
static ObserverState observer;

static void *dispatch_key(const void *handle) {
    return handle ? *(void *const *)handle : NULL;
}
static InstanceState *find_instance(const void *handle) {
    void *key = dispatch_key(handle);
    for (InstanceState *s = instances; s; s = s->next)
        if (s->key == key) return s;
    return NULL;
}
static DeviceState *find_device(const void *handle) {
    void *key = dispatch_key(handle);
    for (DeviceState *s = devices; s; s = s->next)
        if (s->key == key) return s;
    return NULL;
}
static int fail(const char *format, ...) {
    va_list args;
    va_start(args, format);
    vsnprintf(observer.error, sizeof(observer.error), format, args);
    va_end(args);
    observer.status = -1;
    fprintf(stderr, "[observer] ERROR %s\n", observer.error);
    return -1;
}

static void release_observer(void) {
    DeviceState *s = observer.device;
    if (s) {
        s->DeviceWaitIdle(s->device);
        if (observer.mapped) s->UnmapMemory(s->device, observer.memory);
        if (observer.fence) s->DestroyFence(s->device, observer.fence, NULL);
        if (observer.pool) s->DestroyCommandPool(s->device, observer.pool, NULL);
        if (observer.buffer) s->DestroyBuffer(s->device, observer.buffer, NULL);
        if (observer.memory) s->FreeMemory(s->device, observer.memory, NULL);
    }
    free(observer.images);
    uint64_t captured_frames = observer.captured_frames;
    memset(&observer, 0, sizeof(observer));
    observer.captured_frames = captured_frames;
}

OBSERVER_API int observerConfigure(VkPhysicalDevice physical, VkDevice device,
    VkQueue queue, uint32_t family, VkSwapchainKHR swapchain,
    const VkImage *images, uint32_t count, VkFormat format,
    uint32_t width, uint32_t height, const char *outdir) {
    release_observer();
    if (!count) return 0;
    DeviceState *s = find_device(device);
    if (!s || !physical || !queue || !swapchain || !images || !width || !height || !outdir)
        return fail("invalid observer configuration or observer layer not active");
    if (s->device != device)
        return fail("observer device mismatch");
    if (format != VK_FORMAT_R8G8B8A8_UNORM && format != VK_FORMAT_B8G8R8A8_UNORM &&
        format != VK_FORMAT_R8G8B8A8_SRGB && format != VK_FORMAT_B8G8R8A8_SRGB)
        return fail("unsupported test readback format %d", (int)format);
    if (strlen(outdir) >= sizeof(observer.outdir)) return fail("observer output path too long");
    if (mkdir(outdir, 0700) && errno != EEXIST) return fail("cannot create output dir: %s", strerror(errno));
    observer.device = s;
    observer.queue = queue;
    observer.swapchain = swapchain;
    observer.width = width;
    observer.height = height;
    observer.format = format;
    strcpy(observer.outdir, outdir);
    observer.images = malloc(sizeof(*images) * count);
    if (!observer.images) return fail("cannot allocate swapchain image list");
    memcpy(observer.images, images, sizeof(*images) * count);
    observer.image_count = count;

    VkResult result;
#define CHECK_CREATE(call) do { result = (call); if (result != VK_SUCCESS) return fail("%s returned %d", #call, (int)result); } while (0)
    VkCommandPoolCreateInfo pool = {
        .sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO,
        .flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT,
        .queueFamilyIndex = family
    };
    CHECK_CREATE(s->CreateCommandPool(device, &pool, NULL, &observer.pool));
    VkCommandBufferAllocateInfo command = {
        .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO,
        .commandPool = observer.pool, .level = VK_COMMAND_BUFFER_LEVEL_PRIMARY,
        .commandBufferCount = 1
    };
    CHECK_CREATE(s->AllocateCommandBuffers(device, &command, &observer.command));
    if (s->set_loader_data) CHECK_CREATE(s->set_loader_data(device, observer.command));
    VkBufferCreateInfo buffer = {
        .sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO,
        .size = (VkDeviceSize)width * height * 4,
        .usage = VK_BUFFER_USAGE_TRANSFER_DST_BIT,
        .sharingMode = VK_SHARING_MODE_EXCLUSIVE
    };
    CHECK_CREATE(s->CreateBuffer(device, &buffer, NULL, &observer.buffer));
    VkMemoryRequirements requirements;
    s->GetBufferMemoryRequirements(device, observer.buffer, &requirements);
    VkPhysicalDeviceMemoryProperties properties;
    PFN_vkGetPhysicalDeviceMemoryProperties get_properties =
        (PFN_vkGetPhysicalDeviceMemoryProperties)s->instance->gipa(s->instance->instance, "vkGetPhysicalDeviceMemoryProperties");
    // The loader may expose a wrapped physical-device handle to the application.
    // Our next-layer function must receive the physical handle from our own chain.
    get_properties(s->physical, &properties);
    uint32_t memory_type = UINT32_MAX;
    for (int pass = 0; pass < 2 && memory_type == UINT32_MAX; pass++) {
        VkMemoryPropertyFlags required = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT |
            (pass == 0 ? VK_MEMORY_PROPERTY_HOST_COHERENT_BIT : 0);
        for (uint32_t i = 0; i < properties.memoryTypeCount; i++) {
            if ((requirements.memoryTypeBits & (1u << i)) &&
                (properties.memoryTypes[i].propertyFlags & required) == required) {
                memory_type = i;
                observer.coherent = (properties.memoryTypes[i].propertyFlags & VK_MEMORY_PROPERTY_HOST_COHERENT_BIT) != 0;
                break;
            }
        }
    }
    if (memory_type == UINT32_MAX) return fail("no host-visible readback memory type");
    VkMemoryAllocateInfo allocation = {
        .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
        .allocationSize = requirements.size, .memoryTypeIndex = memory_type
    };
    CHECK_CREATE(s->AllocateMemory(device, &allocation, NULL, &observer.memory));
    CHECK_CREATE(s->BindBufferMemory(device, observer.buffer, observer.memory, 0));
    CHECK_CREATE(s->MapMemory(device, observer.memory, 0, VK_WHOLE_SIZE, 0, &observer.mapped));
    VkFenceCreateInfo fence = {.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
    CHECK_CREATE(s->CreateFence(device, &fence, NULL, &observer.fence));
#undef CHECK_CREATE
    fprintf(stderr, "[observer] configured %ux%u images=%u format=%d\n", width, height, count, (int)format);
    return 0;
}

OBSERVER_API void observerFrame(uint64_t frame_id) {
    observer.frame_id = frame_id;
    observer.awaiting_frame = 1;
    observer.status = -2;
    snprintf(observer.error, sizeof(observer.error), "frame %" PRIu64 " has not reached downstream observer", frame_id);
}
OBSERVER_API int observerStatus(void) { return observer.status; }
OBSERVER_API const char *observerError(void) { return observer.error; }
OBSERVER_API uint64_t observerCapturedFrames(void) { return observer.captured_frames; }

static int save_ppm(void) {
    char filename[2200];
    snprintf(filename, sizeof(filename), "%s/player-%03" PRIu64 ".ppm", observer.outdir, observer.frame_id);
    FILE *file = fopen(filename, "wb");
    if (!file) return fail("cannot open %s: %s", filename, strerror(errno));
    if (fprintf(file, "P6\n%u %u\n255\n", observer.width, observer.height) < 0) {
        fclose(file);
        return fail("cannot write PPM header");
    }
    int bgra = observer.format == VK_FORMAT_B8G8R8A8_UNORM || observer.format == VK_FORMAT_B8G8R8A8_SRGB;
    const unsigned char *pixels = observer.mapped;
    unsigned char *row = malloc((size_t)observer.width * 3);
    if (!row) { fclose(file); return fail("cannot allocate PPM row"); }
    for (uint32_t y = 0; y < observer.height; y++) {
        for (uint32_t x = 0; x < observer.width; x++) {
            const unsigned char *p = pixels + ((size_t)y * observer.width + x) * 4;
            row[x * 3] = p[bgra ? 2 : 0];
            row[x * 3 + 1] = p[1];
            row[x * 3 + 2] = p[bgra ? 0 : 2];
        }
        if (fwrite(row, 3, observer.width, file) != observer.width) {
            free(row); fclose(file); return fail("cannot write PPM pixels");
        }
    }
    free(row);
    if (fclose(file)) return fail("cannot finish PPM output");
    observer.captured_frames++;
    observer.status = 0;
    observer.awaiting_frame = 0;
    observer.error[0] = '\0';
    return 0;
}

static VkResult VKAPI_CALL observe_present(VkQueue queue, const VkPresentInfoKHR *present) {
    DeviceState *s = find_device(queue);
    if (!s) { fail("present queue not tracked"); return VK_ERROR_INITIALIZATION_FAILED; }
    if (observer.device != s || observer.queue != queue || !observer.awaiting_frame)
        return s->QueuePresentKHR(queue, present);
    uint32_t selected = UINT32_MAX;
    for (uint32_t i = 0; i < present->swapchainCount; i++)
        if (present->pSwapchains[i] == observer.swapchain) { selected = i; break; }
    if (selected == UINT32_MAX) return s->QueuePresentKHR(queue, present);
    uint32_t image_index = present->pImageIndices[selected];
    if (image_index >= observer.image_count) {
        fail("swapchain image index %u outside image count %u", image_index, observer.image_count);
        return VK_ERROR_INITIALIZATION_FAILED;
    }
    VkResult result;
#define CHECK_FRAME(call) do { result = (call); if (result != VK_SUCCESS) { fail("%s returned %d", #call, (int)result); return VK_ERROR_INITIALIZATION_FAILED; } } while (0)
    CHECK_FRAME(s->ResetCommandBuffer(observer.command, 0));
    VkCommandBufferBeginInfo begin = {
        .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO,
        .flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT
    };
    CHECK_FRAME(s->BeginCommandBuffer(observer.command, &begin));
    VkImageMemoryBarrier barrier = {
        .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
        .srcAccessMask = VK_ACCESS_MEMORY_WRITE_BIT,
        .dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT,
        .oldLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
        .newLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
        .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
        .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
        .image = observer.images[image_index],
        .subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1}
    };
    s->CmdPipelineBarrier(observer.command, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
        VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, NULL, 0, NULL, 1, &barrier);
    VkBufferImageCopy region = {
        .imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1},
        .imageExtent = {observer.width, observer.height, 1}
    };
    s->CmdCopyImageToBuffer(observer.command, barrier.image,
        VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, observer.buffer, 1, &region);
    barrier.srcAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
    barrier.dstAccessMask = 0;
    barrier.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
    barrier.newLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
    s->CmdPipelineBarrier(observer.command, VK_PIPELINE_STAGE_TRANSFER_BIT,
        VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, 0, NULL, 0, NULL, 1, &barrier);
    CHECK_FRAME(s->EndCommandBuffer(observer.command));
    CHECK_FRAME(s->ResetFences(s->device, 1, &observer.fence));
    VkPipelineStageFlags *wait_stages = NULL;
    if (present->waitSemaphoreCount) {
        wait_stages = malloc(sizeof(*wait_stages) * present->waitSemaphoreCount);
        if (!wait_stages) { fail("cannot allocate wait stage array"); return VK_ERROR_OUT_OF_HOST_MEMORY; }
        for (uint32_t i = 0; i < present->waitSemaphoreCount; i++)
            wait_stages[i] = VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
    }
    VkSubmitInfo submit = {
        .sType = VK_STRUCTURE_TYPE_SUBMIT_INFO,
        .waitSemaphoreCount = present->waitSemaphoreCount,
        .pWaitSemaphores = present->pWaitSemaphores,
        .pWaitDstStageMask = wait_stages,
        .commandBufferCount = 1,
        .pCommandBuffers = &observer.command
    };
    result = s->QueueSubmit(queue, 1, &submit, observer.fence);
    free(wait_stages);
    if (result != VK_SUCCESS) { fail("observer QueueSubmit returned %d", (int)result); return result; }
    CHECK_FRAME(s->WaitForFences(s->device, 1, &observer.fence, VK_TRUE, UINT64_C(10000000000)));
    if (!observer.coherent) {
        VkMappedMemoryRange range = {
            .sType = VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE,
            .memory = observer.memory, .offset = 0, .size = VK_WHOLE_SIZE
        };
        CHECK_FRAME(s->InvalidateMappedMemoryRanges(s->device, 1, &range));
    }
    if (save_ppm()) return VK_ERROR_INITIALIZATION_FAILED;
    VkPresentInfoKHR forwarded = *present;
    forwarded.waitSemaphoreCount = 0;
    forwarded.pWaitSemaphores = NULL;
    return s->QueuePresentKHR(queue, &forwarded);
#undef CHECK_FRAME
}

static VkLayerInstanceCreateInfo *instance_link(const VkInstanceCreateInfo *info) {
    for (VkLayerInstanceCreateInfo *p = (VkLayerInstanceCreateInfo *)info->pNext; p; p = (VkLayerInstanceCreateInfo *)p->pNext)
        if (p->sType == VK_STRUCTURE_TYPE_LOADER_INSTANCE_CREATE_INFO && p->function == VK_LAYER_LINK_INFO) return p;
    return NULL;
}
static VkLayerDeviceCreateInfo *device_link(const VkDeviceCreateInfo *info, VkLayerFunction function) {
    for (VkLayerDeviceCreateInfo *p = (VkLayerDeviceCreateInfo *)info->pNext; p; p = (VkLayerDeviceCreateInfo *)p->pNext)
        if (p->sType == VK_STRUCTURE_TYPE_LOADER_DEVICE_CREATE_INFO && p->function == function) return p;
    return NULL;
}
static VkResult VKAPI_CALL observer_create_instance(const VkInstanceCreateInfo *info, const VkAllocationCallbacks *allocator, VkInstance *instance) {
    VkLayerInstanceCreateInfo *chain = instance_link(info);
    if (!chain || !chain->u.pLayerInfo) return VK_ERROR_INITIALIZATION_FAILED;
    VkLayerInstanceLink *link = chain->u.pLayerInfo;
    PFN_vkGetInstanceProcAddr gipa = link->pfnNextGetInstanceProcAddr;
    PFN_GetPhysicalDeviceProcAddr gpdpa = link->pfnNextGetPhysicalDeviceProcAddr;
    PFN_vkCreateInstance create = (PFN_vkCreateInstance)gipa(VK_NULL_HANDLE, "vkCreateInstance");
    chain->u.pLayerInfo = link->pNext;
    VkResult result = create(info, allocator, instance);
    if (result != VK_SUCCESS) return result;
    InstanceState *s = calloc(1, sizeof(*s));
    if (!s) {
        ((PFN_vkDestroyInstance)gipa(*instance, "vkDestroyInstance"))(*instance, allocator);
        return VK_ERROR_OUT_OF_HOST_MEMORY;
    }
    s->instance = *instance;
    s->key = dispatch_key(*instance);
    s->gipa = gipa;
    s->gpdpa = gpdpa;
    s->next = instances;
    instances = s;
    return VK_SUCCESS;
}
static void VKAPI_CALL observer_destroy_instance(VkInstance instance, const VkAllocationCallbacks *allocator) {
    InstanceState *s = find_instance(instance);
    if (!s) return;
    ((PFN_vkDestroyInstance)s->gipa(instance, "vkDestroyInstance"))(instance, allocator);
    InstanceState **cursor = &instances;
    while (*cursor && *cursor != s) cursor = &(*cursor)->next;
    if (*cursor) *cursor = s->next;
    free(s);
}
static VkResult VKAPI_CALL observer_create_device(VkPhysicalDevice physical, const VkDeviceCreateInfo *info, const VkAllocationCallbacks *allocator, VkDevice *device) {
    InstanceState *instance = find_instance(physical);
    VkLayerDeviceCreateInfo *chain = device_link(info, VK_LAYER_LINK_INFO);
    VkLayerDeviceCreateInfo *loader = device_link(info, VK_LOADER_DATA_CALLBACK);
    if (!instance || !chain || !chain->u.pLayerInfo) return VK_ERROR_INITIALIZATION_FAILED;
    VkLayerDeviceLink *link = chain->u.pLayerInfo;
    PFN_vkGetDeviceProcAddr gdpa = link->pfnNextGetDeviceProcAddr;
    PFN_vkCreateDevice create = (PFN_vkCreateDevice)link->pfnNextGetInstanceProcAddr(instance->instance, "vkCreateDevice");
    PFN_vkSetDeviceLoaderData set_loader_data = loader ? loader->u.pfnSetDeviceLoaderData : NULL;
    chain->u.pLayerInfo = link->pNext;
    VkResult result = create(physical, info, allocator, device);
    if (result != VK_SUCCESS) return result;
    DeviceState *s = calloc(1, sizeof(*s));
    if (!s) {
        ((PFN_vkDestroyDevice)gdpa(*device, "vkDestroyDevice"))(*device, allocator);
        return VK_ERROR_OUT_OF_HOST_MEMORY;
    }
    s->device = *device;
    s->physical = physical;
    s->key = dispatch_key(*device);
    s->instance = instance;
    s->gdpa = gdpa;
    s->set_loader_data = set_loader_data;
#define LOAD_FN(name) s->name = (PFN_vk##name)gdpa(*device, "vk" #name);
    DEVICE_FUNCTIONS(LOAD_FN)
#undef LOAD_FN
    s->next = devices;
    devices = s;
    return VK_SUCCESS;
}
static void VKAPI_CALL observer_destroy_device(VkDevice device, const VkAllocationCallbacks *allocator) {
    DeviceState *s = find_device(device);
    if (!s) return;
    if (observer.device == s) release_observer();
    s->DestroyDevice(device, allocator);
    DeviceState **cursor = &devices;
    while (*cursor && *cursor != s) cursor = &(*cursor)->next;
    if (*cursor) *cursor = s->next;
    free(s);
}

OBSERVER_API PFN_vkVoidFunction VKAPI_CALL observerGetInstanceProcAddr(VkInstance instance, const char *name);
OBSERVER_API PFN_vkVoidFunction VKAPI_CALL observerGetDeviceProcAddr(VkDevice device, const char *name);
static PFN_vkVoidFunction VKAPI_CALL observer_get_physical_device_proc_addr(VkInstance instance, const char *name) {
    InstanceState *s = find_instance(instance);
    if (!s) return NULL;
    return s->gpdpa ? s->gpdpa(instance, name) : s->gipa(instance, name);
}
OBSERVER_API PFN_vkVoidFunction VKAPI_CALL observerGetDeviceProcAddr(VkDevice device, const char *name) {
    if (!strcmp(name, "vkGetDeviceProcAddr")) return (PFN_vkVoidFunction)observerGetDeviceProcAddr;
    if (!strcmp(name, "vkDestroyDevice")) return (PFN_vkVoidFunction)observer_destroy_device;
    if (!strcmp(name, "vkQueuePresentKHR")) return (PFN_vkVoidFunction)observe_present;
    DeviceState *s = find_device(device);
    return s ? s->gdpa(device, name) : NULL;
}
OBSERVER_API PFN_vkVoidFunction VKAPI_CALL observerGetInstanceProcAddr(VkInstance instance, const char *name) {
    if (!strcmp(name, "vkGetInstanceProcAddr")) return (PFN_vkVoidFunction)observerGetInstanceProcAddr;
    if (!strcmp(name, "vkGetDeviceProcAddr")) return (PFN_vkVoidFunction)observerGetDeviceProcAddr;
    if (!strcmp(name, "vkCreateInstance")) return (PFN_vkVoidFunction)observer_create_instance;
    if (!strcmp(name, "vkDestroyInstance")) return (PFN_vkVoidFunction)observer_destroy_instance;
    if (!strcmp(name, "vkCreateDevice")) return (PFN_vkVoidFunction)observer_create_device;
    if (!strcmp(name, "vkDestroyDevice")) return (PFN_vkVoidFunction)observer_destroy_device;
    if (!strcmp(name, "vkQueuePresentKHR")) return (PFN_vkVoidFunction)observe_present;
    InstanceState *s = find_instance(instance);
    return s ? s->gipa(instance, name) : NULL;
}
OBSERVER_API VkResult VKAPI_CALL observerNegotiateLoaderLayerInterfaceVersion(VkNegotiateLayerInterface *version) {
    if (!version || version->sType != LAYER_NEGOTIATE_INTERFACE_STRUCT) return VK_ERROR_INITIALIZATION_FAILED;
    if (version->loaderLayerInterfaceVersion < 2) return VK_ERROR_INITIALIZATION_FAILED;
    version->loaderLayerInterfaceVersion = 2;
    version->pfnGetInstanceProcAddr = observerGetInstanceProcAddr;
    version->pfnGetDeviceProcAddr = observerGetDeviceProcAddr;
    version->pfnGetPhysicalDeviceProcAddr = observer_get_physical_device_proc_addr;
    return VK_SUCCESS;
}
