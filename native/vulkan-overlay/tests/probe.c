#define _POSIX_C_SOURCE 200809L
#define VK_USE_PLATFORM_XLIB_KHR
#include <vulkan/vulkan.h>
#include <X11/Xlib.h>
#include <X11/Xutil.h>
#include <dlfcn.h>
#include <errno.h>
#include <inttypes.h>
#include <limits.h>
#include <stdatomic.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <time.h>

#include "obs_overlay_vulkan.h"
#include "observer.h"

#define OBSERVER_NAME OBSERVER_LAYER_NAME
#define CHECK(call) do { VkResult result_ = (call); if (result_ != VK_SUCCESS) \
    die_vk(#call, result_); } while (0)

typedef struct Buffer {
    VkBuffer buffer;
    VkDeviceMemory memory;
    void *mapped;
    VkDeviceSize size;
} Buffer;

typedef struct Image {
    VkImage image;
    VkDeviceMemory memory;
    bool initialized;
} Image;

typedef struct Probe {
    Display *display;
    Window window;
    VkInstance instance;
    VkDebugUtilsMessengerEXT debug;
    VkPhysicalDevice physical;
    VkDevice device;
    VkQueue queue;
    uint32_t family;
    VkSurfaceKHR surface;
    VkSurfaceFormatKHR surface_format;
    VkSwapchainKHR swapchain;
    VkExtent2D extent;
    VkImage *images;
    uint32_t image_count;
    uint64_t seen_images;
    unsigned seen_generations;
    VkCommandPool pool;
    VkCommandBuffer command;
    VkCommandBuffer capture_command;
    VkSemaphore acquire[2];
    VkSemaphore *rendered;
    VkFence submitted;
    uint32_t current_index;
    unsigned current_acquire;
    bool prefetch;
    Buffer staging;
    Buffer clean;
    Image hud;
    bool hud_published;
    void *production_library;
    void *observer_library;
    uint32_t (*abi)(void);
    int32_t (*ready)(uint64_t);
    int32_t (*publish)(const ObsVkHudFrame *);
    int32_t (*wait_hud)(uint64_t, uint64_t);
    int32_t (*clear_hud)(uint64_t, uint64_t);
    uint32_t (*last_error)(char *, uint32_t);
    int (*observer_configure)(VkPhysicalDevice, VkDevice, VkQueue, uint32_t,
                              VkSwapchainKHR, const VkImage *, uint32_t,
                              VkFormat, uint32_t, uint32_t, const char *);
    void (*observer_frame)(uint64_t);
    int (*observer_status)(void);
    const char *(*observer_error)(void);
    char output[PATH_MAX];
    bool validation;
    unsigned validated_frames;
    unsigned dirty_off_frames;
    unsigned active_frames;
} Probe;

static atomic_uint validation_errors;

static void die(const char *message) {
    fprintf(stderr, "FAIL: %s\n", message);
    exit(1);
}

static void die_vk(const char *call, VkResult result) {
    fprintf(stderr, "FAIL: %s returned VkResult %d\n", call, result);
    exit(1);
}

static void *allocate(size_t bytes) {
    void *result = calloc(1, bytes);
    if (!result) die("out of host memory");
    return result;
}

static void make_directory(const char *path) {
    char copy[PATH_MAX];
    if (strlen(path) >= sizeof(copy)) die("output path is too long");
    strcpy(copy, path);
    for (char *p = copy + 1; *p; ++p) {
        if (*p != '/') continue;
        *p = '\0';
        if (mkdir(copy, 0700) && errno != EEXIST) die("cannot create output directory");
        *p = '/';
    }
    if (mkdir(copy, 0700) && errno != EEXIST) die("cannot create output directory");
}

static VKAPI_ATTR VkBool32 VKAPI_CALL debug_callback(
        VkDebugUtilsMessageSeverityFlagBitsEXT severity,
        VkDebugUtilsMessageTypeFlagsEXT type,
        const VkDebugUtilsMessengerCallbackDataEXT *message, void *user) {
    (void)type;
    (void)user;
    if (severity & VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT)
        atomic_fetch_add(&validation_errors, 1);
    fprintf(stderr, "VALIDATION: %s\n", message->pMessage);
    return VK_FALSE;
}

static void *symbol(void *library, const char *name) {
    dlerror();
    void *result = dlsym(library, name);
    const char *error = dlerror();
    if (!result || error) {
        fprintf(stderr, "FAIL: missing export %s: %s\n", name, error ? error : "null");
        exit(1);
    }
    return result;
}

#define LOAD(target, library, name) do { \
    void *address_ = symbol(library, name); \
    _Static_assert(sizeof(target) == sizeof(address_), "function pointer size"); \
    memcpy(&(target), &address_, sizeof(target)); \
} while (0)

static void json_string(FILE *file, const char *text) {
    fputc('"', file);
    for (; *text; ++text) {
        if (*text == '"' || *text == '\\') fputc('\\', file);
        if ((unsigned char)*text < 32) die("control character in library path");
        fputc(*text, file);
    }
    fputc('"', file);
}

static void layer_manifest(const char *directory, const char *filename,
                           const char *name, const char *library) {
    char path[PATH_MAX];
    if (snprintf(path, sizeof(path), "%s/%s.json", directory, filename) >= (int)sizeof(path))
        die("manifest path is too long");
    FILE *file = fopen(path, "w");
    if (!file) die("cannot write private test layer manifest");
    fprintf(file, "{\"file_format_version\":\"1.2.0\",\"layer\":{\"name\":");
    json_string(file, name);
    fprintf(file, ",\"type\":\"GLOBAL\",\"library_path\":");
    json_string(file, library);
    fprintf(file, ",\"api_version\":\"1.2.0\",\"implementation_version\":1,"
                  "\"description\":\"OBS Overlay actual Vulkan probe\"");
    if (!strcmp(name, OBSERVER_NAME))
        fprintf(file, ",\"functions\":{"
                      "\"vkGetInstanceProcAddr\":\"observerGetInstanceProcAddr\","
                      "\"vkGetDeviceProcAddr\":\"observerGetDeviceProcAddr\","
                      "\"vkNegotiateLoaderLayerInterfaceVersion\":\"observerNegotiateLoaderLayerInterfaceVersion\"}");
    else if (!strcmp(name, OBSVK_LAYER_NAME))
        fprintf(file, ",\"functions\":{"
                      "\"vkNegotiateLoaderLayerInterfaceVersion\":\"obsvkNegotiateLoaderLayerInterfaceVersion\"}");
    fprintf(file, "}}\n");
    if (fclose(file)) die("cannot finish layer manifest");
}

static void load_layers(Probe *p, const char *production, const char *observer) {
    char manifests[PATH_MAX];
    if (snprintf(manifests, sizeof(manifests), "%s/manifests", p->output) >= (int)sizeof(manifests))
        die("manifest directory is too long");
    make_directory(manifests);
    if (production) {
        p->production_library = dlopen(production, RTLD_NOW | RTLD_LOCAL);
        if (!p->production_library) die(dlerror());
        LOAD(p->abi, p->production_library, "obsvkGetAbiVersion");
        LOAD(p->ready, p->production_library, "obsvkIsDeviceReady");
        LOAD(p->publish, p->production_library, "obsvkPublishHud");
        LOAD(p->wait_hud, p->production_library, "obsvkWaitHud");
        LOAD(p->clear_hud, p->production_library, "obsvkClearHud");
        LOAD(p->last_error, p->production_library, "obsvkGetLastError");
        if (p->abi() != OBSVK_ABI_VERSION) die("production layer ABI version mismatch");
        layer_manifest(manifests, "production", OBSVK_LAYER_NAME, production);
    }
    if (observer) {
        p->observer_library = dlopen(observer, RTLD_NOW | RTLD_LOCAL);
        if (!p->observer_library) die(dlerror());
        LOAD(p->observer_configure, p->observer_library, "observerConfigure");
        LOAD(p->observer_frame, p->observer_library, "observerFrame");
        LOAD(p->observer_status, p->observer_library, "observerStatus");
        LOAD(p->observer_error, p->observer_library, "observerError");
        layer_manifest(manifests, "observer", OBSERVER_NAME, observer);
    }
    const char *old = getenv("VK_LAYER_PATH");
    size_t bytes = strlen(manifests) + (old ? strlen(old) + 1 : 0) + 1;
    char *search = allocate(bytes);
    snprintf(search, bytes, "%s%s%s", manifests, old ? ":" : "", old ? old : "");
    if (setenv("VK_LAYER_PATH", search, 1)) die("cannot set test layer path");
    free(search);
}

static void check_production(Probe *p, int32_t result, const char *operation) {
    if (result == OBSVK_SUCCESS) return;
    char error[2048] = {0};
    p->last_error(error, sizeof(error));
    fprintf(stderr, "FAIL: %s returned %d: %s\n", operation, result, error);
    exit(1);
}

static void create_instance(Probe *p) {
    const char *extensions[] = {
        VK_KHR_SURFACE_EXTENSION_NAME, VK_KHR_XLIB_SURFACE_EXTENSION_NAME,
        VK_EXT_DEBUG_UTILS_EXTENSION_NAME, VK_EXT_VALIDATION_FEATURES_EXTENSION_NAME
    };
    const char *layers[3];
    uint32_t layer_count = 0;
    if (p->production_library) layers[layer_count++] = OBSVK_LAYER_NAME;
    if (p->observer_library) layers[layer_count++] = OBSERVER_NAME;
    if (p->validation) layers[layer_count++] = "VK_LAYER_KHRONOS_validation";
    uint32_t available_count = 0;
    CHECK(vkEnumerateInstanceLayerProperties(&available_count, NULL));
    VkLayerProperties *available = allocate(sizeof(*available) * available_count);
    CHECK(vkEnumerateInstanceLayerProperties(&available_count, available));
    for (uint32_t i = 0; i < layer_count; ++i) {
        bool found = false;
        for (uint32_t k = 0; k < available_count; ++k)
            if (!strcmp(layers[i], available[k].layerName)) found = true;
        if (!found) {
            fprintf(stderr, "FAIL: requested layer unavailable: %s\n", layers[i]);
            exit(1);
        }
        printf("layer[%u]=%s\n", i, layers[i]);
    }
    free(available);
    VkApplicationInfo application = {
        .sType = VK_STRUCTURE_TYPE_APPLICATION_INFO,
        .pApplicationName = "OBS Overlay actual Vulkan probe",
        .applicationVersion = 1,
        .pEngineName = "independent test",
        .apiVersion = VK_API_VERSION_1_2
    };
    VkValidationFeatureEnableEXT synchronization = VK_VALIDATION_FEATURE_ENABLE_SYNCHRONIZATION_VALIDATION_EXT;
    VkValidationFeaturesEXT validation = {
        .sType = VK_STRUCTURE_TYPE_VALIDATION_FEATURES_EXT,
        .enabledValidationFeatureCount = 1, .pEnabledValidationFeatures = &synchronization
    };
    VkDebugUtilsMessengerCreateInfoEXT debug = {
        .sType = VK_STRUCTURE_TYPE_DEBUG_UTILS_MESSENGER_CREATE_INFO_EXT,
        .pNext = &validation,
        .messageSeverity = VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT |
                           VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT,
        .messageType = VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT |
                       VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT |
                       VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT,
        .pfnUserCallback = debug_callback
    };
    VkInstanceCreateInfo instance = {
        .sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO,
        .pNext = p->validation ? &debug : NULL,
        .pApplicationInfo = &application,
        .enabledLayerCount = layer_count, .ppEnabledLayerNames = layers,
        .enabledExtensionCount = p->validation ? 4u : 2u,
        .ppEnabledExtensionNames = extensions
    };
    CHECK(vkCreateInstance(&instance, NULL, &p->instance));
    if (p->validation) {
        debug.pNext = NULL;
        PFN_vkCreateDebugUtilsMessengerEXT create =
            (PFN_vkCreateDebugUtilsMessengerEXT)vkGetInstanceProcAddr(p->instance, "vkCreateDebugUtilsMessengerEXT");
        if (!create) die("debug-utils creation entry point unavailable");
        CHECK(create(p->instance, &debug, NULL, &p->debug));
    }
}

static void create_device(Probe *p) {
    VkXlibSurfaceCreateInfoKHR surface = {
        .sType = VK_STRUCTURE_TYPE_XLIB_SURFACE_CREATE_INFO_KHR,
        .dpy = p->display, .window = p->window
    };
    CHECK(vkCreateXlibSurfaceKHR(p->instance, &surface, NULL, &p->surface));
    uint32_t count = 0;
    CHECK(vkEnumeratePhysicalDevices(p->instance, &count, NULL));
    VkPhysicalDevice *devices = allocate(count * sizeof(*devices));
    CHECK(vkEnumeratePhysicalDevices(p->instance, &count, devices));
    for (uint32_t i = 0; i < count && !p->physical; ++i) {
        uint32_t families = 0;
        vkGetPhysicalDeviceQueueFamilyProperties(devices[i], &families, NULL);
        VkQueueFamilyProperties *properties = allocate(families * sizeof(*properties));
        vkGetPhysicalDeviceQueueFamilyProperties(devices[i], &families, properties);
        for (uint32_t q = 0; q < families; ++q) {
            VkBool32 present = VK_FALSE;
            CHECK(vkGetPhysicalDeviceSurfaceSupportKHR(devices[i], q, p->surface, &present));
            if ((properties[q].queueFlags & VK_QUEUE_GRAPHICS_BIT) && present) {
                p->physical = devices[i]; p->family = q; break;
            }
        }
        free(properties);
    }
    free(devices);
    if (!p->physical) die("no physical device has one graphics+present queue");
    VkPhysicalDeviceProperties properties;
    vkGetPhysicalDeviceProperties(p->physical, &properties);
    printf("device=%s api=%u.%u.%u queue_family=%u\n", properties.deviceName,
           VK_API_VERSION_MAJOR(properties.apiVersion), VK_API_VERSION_MINOR(properties.apiVersion),
           VK_API_VERSION_PATCH(properties.apiVersion), p->family);
    const float priority = 1.0f;
    VkDeviceQueueCreateInfo queue = {
        .sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO,
        .queueFamilyIndex = p->family, .queueCount = 1, .pQueuePriorities = &priority
    };
    const char *extensions[] = {VK_KHR_SWAPCHAIN_EXTENSION_NAME};
    VkDeviceCreateInfo device = {
        .sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO,
        .queueCreateInfoCount = 1, .pQueueCreateInfos = &queue,
        .enabledExtensionCount = 1, .ppEnabledExtensionNames = extensions
    };
    CHECK(vkCreateDevice(p->physical, &device, NULL, &p->device));
    vkGetDeviceQueue(p->device, p->family, 0, &p->queue);
    if (p->production_library && p->ready((uint64_t)(uintptr_t)p->device) != 1)
        die("production layer does not report this real Vulkan device ready");
    VkCommandPoolCreateInfo pool = {
        .sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO,
        .flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT,
        .queueFamilyIndex = p->family
    };
    CHECK(vkCreateCommandPool(p->device, &pool, NULL, &p->pool));
    VkCommandBufferAllocateInfo allocate_info = {
        .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO,
        .commandPool = p->pool, .level = VK_COMMAND_BUFFER_LEVEL_PRIMARY,
        .commandBufferCount = 2
    };
    VkCommandBuffer commands[2];
    CHECK(vkAllocateCommandBuffers(p->device, &allocate_info, commands));
    p->command = commands[0];
    p->capture_command = commands[1];
}

static uint32_t memory_type(Probe *p, uint32_t bits, VkMemoryPropertyFlags flags) {
    VkPhysicalDeviceMemoryProperties properties;
    vkGetPhysicalDeviceMemoryProperties(p->physical, &properties);
    for (uint32_t i = 0; i < properties.memoryTypeCount; ++i)
        if ((bits & (1u << i)) && (properties.memoryTypes[i].propertyFlags & flags) == flags)
            return i;
    die("required memory type unavailable");
    return 0;
}

static Buffer create_buffer(Probe *p, VkDeviceSize size, VkBufferUsageFlags usage) {
    Buffer result = {.size = size};
    VkBufferCreateInfo buffer = {
        .sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO, .size = size,
        .usage = usage, .sharingMode = VK_SHARING_MODE_EXCLUSIVE
    };
    CHECK(vkCreateBuffer(p->device, &buffer, NULL, &result.buffer));
    VkMemoryRequirements requirements;
    vkGetBufferMemoryRequirements(p->device, result.buffer, &requirements);
    VkMemoryAllocateInfo memory = {
        .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO, .allocationSize = requirements.size,
        .memoryTypeIndex = memory_type(p, requirements.memoryTypeBits,
            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)
    };
    CHECK(vkAllocateMemory(p->device, &memory, NULL, &result.memory));
    CHECK(vkBindBufferMemory(p->device, result.buffer, result.memory, 0));
    CHECK(vkMapMemory(p->device, result.memory, 0, size, 0, &result.mapped));
    return result;
}

static void destroy_buffer(Probe *p, Buffer *buffer) {
    if (!buffer->buffer) return;
    vkUnmapMemory(p->device, buffer->memory);
    vkDestroyBuffer(p->device, buffer->buffer, NULL);
    vkFreeMemory(p->device, buffer->memory, NULL);
    memset(buffer, 0, sizeof(*buffer));
}

static void create_hud(Probe *p) {
    VkImageCreateInfo image = {
        .sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO, .imageType = VK_IMAGE_TYPE_2D,
        .format = VK_FORMAT_R8G8B8A8_UNORM,
        .extent = {p->extent.width, p->extent.height, 1},
        .mipLevels = 1, .arrayLayers = 1, .samples = VK_SAMPLE_COUNT_1_BIT,
        .tiling = VK_IMAGE_TILING_OPTIMAL,
        .usage = VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT,
        .sharingMode = VK_SHARING_MODE_EXCLUSIVE, .initialLayout = VK_IMAGE_LAYOUT_UNDEFINED
    };
    CHECK(vkCreateImage(p->device, &image, NULL, &p->hud.image));
    VkMemoryRequirements requirements;
    vkGetImageMemoryRequirements(p->device, p->hud.image, &requirements);
    VkMemoryAllocateInfo memory = {
        .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO, .allocationSize = requirements.size,
        .memoryTypeIndex = memory_type(p, requirements.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT)
    };
    CHECK(vkAllocateMemory(p->device, &memory, NULL, &p->hud.memory));
    CHECK(vkBindImageMemory(p->device, p->hud.image, p->hud.memory, 0));
}

static void create_swapchain(Probe *p, unsigned width, unsigned height) {
    XResizeWindow(p->display, p->window, width, height);
    XSync(p->display, False);
    VkSurfaceCapabilitiesKHR capabilities;
    CHECK(vkGetPhysicalDeviceSurfaceCapabilitiesKHR(p->physical, p->surface, &capabilities));
    uint32_t format_count = 0;
    CHECK(vkGetPhysicalDeviceSurfaceFormatsKHR(p->physical, p->surface, &format_count, NULL));
    VkSurfaceFormatKHR *formats = allocate(format_count * sizeof(*formats));
    CHECK(vkGetPhysicalDeviceSurfaceFormatsKHR(p->physical, p->surface, &format_count, formats));
    bool found_format = false;
    for (uint32_t i = 0; i < format_count; ++i) {
        if ((formats[i].format == VK_FORMAT_R8G8B8A8_UNORM || formats[i].format == VK_FORMAT_B8G8R8A8_UNORM) &&
                formats[i].colorSpace == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR) {
            p->surface_format = formats[i]; found_format = true; break;
        }
    }
    free(formats);
    if (!found_format) die("probe requires SDR RGBA8/BGRA8 UNORM surface");
    p->extent = capabilities.currentExtent;
    if (p->extent.width == UINT32_MAX) p->extent = (VkExtent2D){width, height};
    VkImageUsageFlags usage = VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    if ((capabilities.supportedUsageFlags & usage) != usage)
        die("surface does not support the real capture copy test");
    uint32_t desired_count = capabilities.minImageCount + 2;
    if (capabilities.maxImageCount && desired_count > capabilities.maxImageCount)
        desired_count = capabilities.maxImageCount;
    VkCompositeAlphaFlagBitsKHR composite = VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
    if (!(capabilities.supportedCompositeAlpha & composite))
        composite = (VkCompositeAlphaFlagBitsKHR)(capabilities.supportedCompositeAlpha &
                                                (0u - capabilities.supportedCompositeAlpha));
    VkSwapchainCreateInfoKHR swapchain = {
        .sType = VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR, .surface = p->surface,
        .minImageCount = desired_count, .imageFormat = p->surface_format.format,
        .imageColorSpace = p->surface_format.colorSpace, .imageExtent = p->extent,
        .imageArrayLayers = 1, .imageUsage = usage,
        .imageSharingMode = VK_SHARING_MODE_EXCLUSIVE,
        .preTransform = capabilities.currentTransform, .compositeAlpha = composite,
        .presentMode = VK_PRESENT_MODE_FIFO_KHR, .clipped = VK_TRUE
    };
    // COLOR_ATTACHMENT is deliberately absent; the production layer must request it.
    CHECK(vkCreateSwapchainKHR(p->device, &swapchain, NULL, &p->swapchain));
    CHECK(vkGetSwapchainImagesKHR(p->device, p->swapchain, &p->image_count, NULL));
    p->images = allocate(p->image_count * sizeof(*p->images));
    CHECK(vkGetSwapchainImagesKHR(p->device, p->swapchain, &p->image_count, p->images));
    p->prefetch = p->image_count > capabilities.minImageCount;
    p->seen_images = 0;
    ++p->seen_generations;
    VkSemaphoreCreateInfo semaphore = {.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
    CHECK(vkCreateSemaphore(p->device, &semaphore, NULL, &p->acquire[0]));
    CHECK(vkCreateSemaphore(p->device, &semaphore, NULL, &p->acquire[1]));
    p->rendered = allocate(p->image_count * sizeof(*p->rendered));
    for (uint32_t i = 0; i < p->image_count; ++i)
        CHECK(vkCreateSemaphore(p->device, &semaphore, NULL, &p->rendered[i]));
    VkFenceCreateInfo fence = {.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
    CHECK(vkCreateFence(p->device, &fence, NULL, &p->submitted));
    VkDeviceSize bytes = (VkDeviceSize)p->extent.width * p->extent.height * 4;
    p->staging = create_buffer(p, 2 * bytes, VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
    p->clean = create_buffer(p, bytes, VK_BUFFER_USAGE_TRANSFER_DST_BIT);
    create_hud(p);
    if (p->observer_library && p->observer_configure(p->physical, p->device, p->queue, p->family,
            p->swapchain, p->images, p->image_count, p->surface_format.format,
            p->extent.width, p->extent.height, p->output) != 0)
        die(p->observer_error());
    p->current_acquire = 0;
    CHECK(vkAcquireNextImageKHR(p->device, p->swapchain, UINT64_MAX, p->acquire[0],
                              VK_NULL_HANDLE, &p->current_index));
    printf("swapchain generation=%u extent=%ux%u format=%d images=%u prefetch=%d requested_usage=TRANSFER_SRC|TRANSFER_DST\n",
           p->seen_generations, p->extent.width, p->extent.height, p->surface_format.format,
           p->image_count, p->prefetch);
}

static void destroy_swapchain(Probe *p) {
    if (!p->swapchain) return;
    if (p->production_library)
        check_production(p, p->clear_hud((uint64_t)(uintptr_t)p->device, (uint64_t)p->swapchain), "ClearHud");
    if (p->observer_library && p->observer_configure(p->physical, p->device, p->queue, p->family,
            VK_NULL_HANDLE, NULL, 0, p->surface_format.format, 0, 0, p->output) != 0)
        die(p->observer_error());
    CHECK(vkDeviceWaitIdle(p->device));
    unsigned seen = (unsigned)__builtin_popcountll(p->seen_images);
    printf("swapchain generation=%u distinct_images_presented=%u\n", p->seen_generations, seen);
    if (p->prefetch && seen < 2) die("multi-image test never presented two distinct swapchain images");
    destroy_buffer(p, &p->staging);
    destroy_buffer(p, &p->clean);
    vkDestroyImage(p->device, p->hud.image, NULL);
    vkFreeMemory(p->device, p->hud.memory, NULL);
    memset(&p->hud, 0, sizeof(p->hud));
    p->hud_published = false;
    vkDestroyFence(p->device, p->submitted, NULL);
    for (uint32_t i = 0; i < p->image_count; ++i)
        vkDestroySemaphore(p->device, p->rendered[i], NULL);
    free(p->rendered); p->rendered = NULL;
    vkDestroySemaphore(p->device, p->acquire[0], NULL);
    vkDestroySemaphore(p->device, p->acquire[1], NULL);
    vkDestroySwapchainKHR(p->device, p->swapchain, NULL);
    free(p->images); p->images = NULL; p->swapchain = VK_NULL_HANDLE;
}

static void image_barrier(VkCommandBuffer command, VkImage image,
                          VkImageLayout old_layout, VkImageLayout new_layout,
                          VkPipelineStageFlags source_stage, VkAccessFlags source_access,
                          VkPipelineStageFlags destination_stage, VkAccessFlags destination_access) {
    VkImageMemoryBarrier barrier = {
        .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
        .srcAccessMask = source_access, .dstAccessMask = destination_access,
        .oldLayout = old_layout, .newLayout = new_layout,
        .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED, .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
        .image = image, .subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1}
    };
    vkCmdPipelineBarrier(command, source_stage, destination_stage, 0, 0, NULL, 0, NULL, 1, &barrier);
}

static void fill_images(Probe *p, unsigned frame) {
    unsigned w = p->extent.width, h = p->extent.height;
    uint8_t *game = p->staging.mapped;
    uint8_t *hud = game + (size_t)w * h * 4;
    memset(hud, 0, (size_t)w * h * 4);
    bool bgra = p->surface_format.format == VK_FORMAT_B8G8R8A8_UNORM;
    for (unsigned y = 0; y < h; ++y) {
        for (unsigned x = 0; x < w; ++x) {
            size_t offset = ((size_t)y * w + x) * 4;
            bool checker = ((x / 24) ^ (y / 24)) & 1;
            uint8_t red = checker ? 35 : 18, green = checker ? 58 : 32;
            uint8_t blue = (uint8_t)(100 + (frame * 7) % 80);
            // An upstream marker must survive transparent HUD composition unchanged.
            if (x >= 3 * w / 4 && x < 7 * w / 8 && y >= h / 8 && y < 3 * h / 8) {
                red = 240; green = (uint8_t)(170 + frame % 50); blue = 20;
            }
            game[offset + (bgra ? 2 : 0)] = red;
            game[offset + 1] = green;
            game[offset + (bgra ? 0 : 2)] = blue;
            game[offset + 3] = 255;
            // The source mark is low; Minecraft's production layer flips it upward.
            if (x >= w / 8 && x < 3 * w / 8 && y >= h / 2 && y < 3 * h / 4) {
                hud[offset + (frame % 2 ? 1 : 0)] = 128;
                hud[offset + 3] = 128;
            }
        }
    }
}

static void record_frame(Probe *p) {
    CHECK(vkResetCommandBuffer(p->command, 0));
    VkCommandBufferBeginInfo begin = {
        .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO,
        .flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT
    };
    CHECK(vkBeginCommandBuffer(p->command, &begin));
    image_barrier(p->command, p->hud.image,
                  p->hud.initialized ? VK_IMAGE_LAYOUT_GENERAL : VK_IMAGE_LAYOUT_UNDEFINED,
                  VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                  p->hud.initialized ? VK_PIPELINE_STAGE_ALL_COMMANDS_BIT : VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                  p->hud.initialized ? VK_ACCESS_MEMORY_READ_BIT : 0,
                  VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT);
    VkBufferImageCopy copy = {
        .bufferOffset = (VkDeviceSize)p->extent.width * p->extent.height * 4,
        .imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1},
        .imageExtent = {p->extent.width, p->extent.height, 1}
    };
    vkCmdCopyBufferToImage(p->command, p->staging.buffer, p->hud.image,
                          VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &copy);
    image_barrier(p->command, p->hud.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL,
                  VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
                  VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT);
    p->hud.initialized = true;
    VkImage swap_image = p->images[p->current_index];
    image_barrier(p->command, swap_image, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                  VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, 0, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT);
    copy.bufferOffset = 0;
    vkCmdCopyBufferToImage(p->command, p->staging.buffer, swap_image,
                          VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &copy);
    image_barrier(p->command, swap_image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
                  VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
                  VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0);
    CHECK(vkEndCommandBuffer(p->command));

    CHECK(vkResetCommandBuffer(p->capture_command, 0));
    CHECK(vkBeginCommandBuffer(p->capture_command, &begin));
    image_barrier(p->capture_command, swap_image, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                  VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_ACCESS_MEMORY_WRITE_BIT,
                  VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_READ_BIT);
    // The upstream capture is a separate submission on the same queue. It
    // adds no semaphore wait and leaves the app's present semaphore untouched.
    vkCmdCopyImageToBuffer(p->capture_command, swap_image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                          p->clean.buffer, 1, &copy);
    VkBufferMemoryBarrier host_read = {
        .sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER,
        .srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT, .dstAccessMask = VK_ACCESS_HOST_READ_BIT,
        .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED, .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
        .buffer = p->clean.buffer, .size = VK_WHOLE_SIZE
    };
    vkCmdPipelineBarrier(p->capture_command, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT,
                         0, 0, NULL, 1, &host_read, 0, NULL);
    image_barrier(p->capture_command, swap_image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
                  VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_READ_BIT,
                  VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0);
    CHECK(vkEndCommandBuffer(p->capture_command));
}

static uint8_t *clean_rgb(Probe *p) {
    size_t pixels = (size_t)p->extent.width * p->extent.height;
    uint8_t *result = allocate(pixels * 3), *input = p->clean.mapped;
    bool bgra = p->surface_format.format == VK_FORMAT_B8G8R8A8_UNORM;
    for (size_t i = 0; i < pixels; ++i) {
        result[3 * i] = input[4 * i + (bgra ? 2 : 0)];
        result[3 * i + 1] = input[4 * i + 1];
        result[3 * i + 2] = input[4 * i + (bgra ? 0 : 2)];
    }
    return result;
}

static void write_ppm(const char *path, unsigned width, unsigned height, const uint8_t *rgb) {
    FILE *file = fopen(path, "wb");
    if (!file) die("cannot create image output");
    fprintf(file, "P6\n%u %u\n255\n", width, height);
    if (fwrite(rgb, 3, (size_t)width * height, file) != (size_t)width * height || fclose(file))
        die("cannot finish image output");
}

static uint8_t *read_ppm(const char *path, unsigned width, unsigned height) {
    FILE *file = fopen(path, "rb");
    if (!file) die("observer did not write player image");
    char magic[3] = {0};
    unsigned w, h, max;
    if (fscanf(file, "%2s %u %u %u", magic, &w, &h, &max) != 4 || strcmp(magic, "P6") ||
            w != width || h != height || max != 255 || fgetc(file) != '\n')
        die("observer PPM header does not match this frame");
    uint8_t *pixels = allocate((size_t)w * h * 3);
    if (fread(pixels, 3, (size_t)w * h, file) != (size_t)w * h)
        die("observer PPM pixels incomplete");
    fclose(file);
    return pixels;
}

static void assert_pixel(const uint8_t *actual, const uint8_t *expected,
                         unsigned tolerance, unsigned frame, const char *name) {
    for (unsigned channel = 0; channel < 3; ++channel) {
        int delta = (int)actual[channel] - expected[channel];
        if (abs(delta) > (int)tolerance) {
            fprintf(stderr, "FAIL frame=%u %s actual=%u,%u,%u expected=%u,%u,%u\n", frame, name,
                    actual[0], actual[1], actual[2], expected[0], expected[1], expected[2]);
            exit(1);
        }
    }
}

static void validate_player(Probe *p, unsigned frame, bool active, const uint8_t *clean) {
    char path[PATH_MAX];
    snprintf(path, sizeof(path), "%s/player-%03u.ppm", p->output, frame);
    uint8_t *player = read_ppm(path, p->extent.width, p->extent.height);
    unsigned w = p->extent.width, h = p->extent.height;
    size_t changed = 0;
    for (size_t i = 0; i < (size_t)w * h; ++i)
        if (memcmp(player + 3 * i, clean + 3 * i, 3)) ++changed;
    size_t upper_mark = ((size_t)(3 * h / 8) * w + w / 4) * 3;
    size_t lower_source = ((size_t)(5 * h / 8) * w + w / 4) * 3;
    size_t upstream_mark = ((size_t)(h / 4) * w + 13 * w / 16) * 3;
    size_t transparent = ((size_t)(7 * h / 8) * w + 7 * w / 8) * 3;
    assert_pixel(player + upstream_mark, clean + upstream_mark, 0, frame, "upstream marker preserved");
    assert_pixel(player + transparent, clean + transparent, 0, frame, "transparent background preserved");
    assert_pixel(player + lower_source, clean + lower_source, 0, frame, "source lower mark flips upward");
    if (active) {
        uint8_t expected[3];
        for (unsigned c = 0; c < 3; ++c) {
            unsigned source = c == frame % 2 ? 128 : 0;
            expected[c] = (uint8_t)((source * 255 + clean[upper_mark + c] * 127 + 127) / 255);
        }
        assert_pixel(player + upper_mark, expected, 2, frame, "production premultiplied alpha blend");
        if (changed < (size_t)w * h / 30 || changed > (size_t)w * h / 6)
            die("HUD did not affect only its expected bounded region");
        ++p->active_frames;
    } else {
        if (changed) die("withdrawn or unregistered HUD leaked into player output");
        ++p->dirty_off_frames;
    }
    ++p->validated_frames;
    printf("frame=%u image=%u hud=%s changed_pixels=%zu clean=PASS player=PASS\n",
           frame, p->current_index, active ? "active" : "absent", changed);
    free(player);
}

static unsigned component(unsigned long pixel, unsigned long mask) {
    if (!mask) return 0;
    unsigned shift = (unsigned)__builtin_ctzl(mask);
    unsigned long value = (pixel & mask) >> shift;
    unsigned long maximum = mask >> shift;
    return (unsigned)((value * 255 + maximum / 2) / maximum);
}

static void capture_window(Probe *p, unsigned frame) {
    char path[PATH_MAX], player_path[PATH_MAX];
    snprintf(path, sizeof(path), "%s/window-%03u.ppm", p->output, frame);
    snprintf(player_path, sizeof(player_path), "%s/player-%03u.ppm", p->output, frame);
    uint8_t *expected = p->observer_library ? read_ppm(player_path, p->extent.width, p->extent.height) : NULL;
    size_t bytes = (size_t)p->extent.width * p->extent.height * 3;
    uint8_t *rgb = allocate(bytes);
    bool matched = false;
    for (unsigned attempt = 0; attempt < 100; ++attempt) {
        XSync(p->display, False);
        XImage *image = XGetImage(p->display, p->window, 0, 0, p->extent.width, p->extent.height, AllPlanes, ZPixmap);
        if (!image) die("XGetImage could not capture the actual presented window");
        for (unsigned y = 0; y < p->extent.height; ++y)
            for (unsigned x = 0; x < p->extent.width; ++x) {
                unsigned long pixel = XGetPixel(image, x, y);
                size_t offset = ((size_t)y * p->extent.width + x) * 3;
                rgb[offset] = (uint8_t)component(pixel, image->red_mask);
                rgb[offset + 1] = (uint8_t)component(pixel, image->green_mask);
                rgb[offset + 2] = (uint8_t)component(pixel, image->blue_mask);
            }
        XDestroyImage(image);
        if (!expected || !memcmp(rgb, expected, bytes)) { matched = true; break; }
        struct timespec pause = {.tv_nsec = 10000000};
        nanosleep(&pause, NULL);
    }
    write_ppm(path, p->extent.width, p->extent.height, rgb);
    free(rgb); free(expected);
    if (!matched) die("actual X11 presented pixels never matched the downstream GPU readback");
    printf("window=%u actual_XGetImage=PASS\n", frame);
}

static void render_frame(Probe *p, unsigned frame, bool active, bool unregistered, bool last) {
    if (p->production_library && p->hud_published)
        check_production(p, p->wait_hud((uint64_t)(uintptr_t)p->device, (uint64_t)p->hud.image), "WaitHud before rewrite");
    fill_images(p, frame);
    record_frame(p);
    if (p->production_library && !unregistered) {
        ObsVkHudFrame published = {
            .struct_size = sizeof(published), .abi_version = OBSVK_ABI_VERSION,
            .device = (uint64_t)(uintptr_t)p->device, .queue = (uint64_t)(uintptr_t)p->queue,
            .swapchain = (uint64_t)p->swapchain, .image = active ? (uint64_t)p->hud.image : 0,
            .format = VK_FORMAT_R8G8B8A8_UNORM, .layout = VK_IMAGE_LAYOUT_GENERAL,
            .width = p->extent.width, .height = p->extent.height,
            .flags = OBSVK_HUD_PREMULTIPLIED_ALPHA, .frame_id = frame
        };
        check_production(p, p->publish(&published), active ? "PublishHud" : "withdraw image=0");
        if (active) p->hud_published = true;
    }
    uint32_t next_index = 0;
    unsigned next_acquire = 1 - p->current_acquire;
    if (p->prefetch && !last)
        CHECK(vkAcquireNextImageKHR(p->device, p->swapchain, UINT64_MAX, p->acquire[next_acquire], VK_NULL_HANDLE, &next_index));
    VkPipelineStageFlags wait_stage = VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
    VkSubmitInfo submit = {
        .sType = VK_STRUCTURE_TYPE_SUBMIT_INFO, .waitSemaphoreCount = 1,
        .pWaitSemaphores = &p->acquire[p->current_acquire], .pWaitDstStageMask = &wait_stage,
        .commandBufferCount = 1, .pCommandBuffers = &p->command,
        .signalSemaphoreCount = 1, .pSignalSemaphores = &p->rendered[p->current_index]
    };
    CHECK(vkResetFences(p->device, 1, &p->submitted));
    CHECK(vkQueueSubmit(p->queue, 1, &submit, VK_NULL_HANDLE));
    VkSubmitInfo capture_submit = {
        .sType = VK_STRUCTURE_TYPE_SUBMIT_INFO,
        .commandBufferCount = 1, .pCommandBuffers = &p->capture_command
    };
    CHECK(vkQueueSubmit(p->queue, 1, &capture_submit, p->submitted));
    if (p->observer_library) p->observer_frame(frame);
    VkPresentInfoKHR present = {
        .sType = VK_STRUCTURE_TYPE_PRESENT_INFO_KHR, .waitSemaphoreCount = 1,
        .pWaitSemaphores = &p->rendered[p->current_index], .swapchainCount = 1,
        .pSwapchains = &p->swapchain, .pImageIndices = &p->current_index
    };
    // Submit remains asynchronous here; the production layer must honor its incoming wait.
    VkResult result = vkQueuePresentKHR(p->queue, &present);
    if (result != VK_SUCCESS && result != VK_SUBOPTIMAL_KHR) die_vk("vkQueuePresentKHR", result);
    if (p->observer_library && p->observer_status() != 0) die(p->observer_error());
    CHECK(vkWaitForFences(p->device, 1, &p->submitted, VK_TRUE, UINT64_MAX));
    if (p->current_index < 64) p->seen_images |= UINT64_C(1) << p->current_index;
    // Compare actual clean readback to the uploaded game pixels; HUD must be absent.
    if (memcmp(p->clean.mapped, p->staging.mapped, (size_t)p->clean.size))
        die("pre-overlay clean GPU readback differs from the uploaded game image");
    uint8_t *rgb = clean_rgb(p);
    char path[PATH_MAX];
    snprintf(path, sizeof(path), "%s/clean-%03u.ppm", p->output, frame);
    write_ppm(path, p->extent.width, p->extent.height, rgb);
    if (p->observer_library) validate_player(p, frame, active, rgb);
    else printf("frame=%u image=%u clean=PASS presented=YES\n", frame, p->current_index);
    free(rgb);
    if (last) capture_window(p, frame);
    if (!last) {
        if (!p->prefetch)
            CHECK(vkAcquireNextImageKHR(p->device, p->swapchain, UINT64_MAX, p->acquire[next_acquire], VK_NULL_HANDLE, &next_index));
        p->current_index = next_index;
        p->current_acquire = next_acquire;
    }
}

int main(int argc, char **argv) {
    Probe probe = {.validation = true};
    const char *production = NULL, *observer = NULL;
    unsigned frames = 18, resize_frame = 9, width = 320, height = 240;
    bool baseline = false;
    strcpy(probe.output, "probe-output");
    for (int i = 1; i < argc; ++i) {
        if (!strcmp(argv[i], "--layer") && i + 1 < argc) production = argv[++i];
        else if (!strcmp(argv[i], "--observer") && i + 1 < argc) observer = argv[++i];
        else if (!strcmp(argv[i], "--output") && i + 1 < argc) {
            if (strlen(argv[++i]) >= sizeof(probe.output)) die("output path too long");
            strcpy(probe.output, argv[i]);
        } else if (!strcmp(argv[i], "--frames") && i + 1 < argc) frames = (unsigned)strtoul(argv[++i], NULL, 10);
        else if (!strcmp(argv[i], "--resize-frame") && i + 1 < argc) resize_frame = (unsigned)strtoul(argv[++i], NULL, 10);
        else if (!strcmp(argv[i], "--width") && i + 1 < argc) width = (unsigned)strtoul(argv[++i], NULL, 10);
        else if (!strcmp(argv[i], "--height") && i + 1 < argc) height = (unsigned)strtoul(argv[++i], NULL, 10);
        else if (!strcmp(argv[i], "--no-validation")) probe.validation = false;
        else if (!strcmp(argv[i], "--baseline")) baseline = true;
        else die("usage: probe --layer ABSOLUTE_SO --observer ABSOLUTE_SO --output DIR [--frames N --resize-frame N] [--baseline]");
    }
    if ((!production || !observer) && !baseline)
        die("full validation requires both the production layer and downstream observer; use --baseline for presentation setup");
    if (width < 64 || height < 64 || width > 4096 || height > 4096 || frames < 4 || frames > 1000)
        die("invalid probe dimensions or frame count");
    if (resize_frame && (resize_frame < 2 || resize_frame + 2 >= frames))
        die("resize must leave at least two frames in each swapchain generation");
    setvbuf(stdout, NULL, _IOLBF, 0);
    make_directory(probe.output);
    load_layers(&probe, production, observer);
    probe.display = XOpenDisplay(NULL);
    if (!probe.display) die("XOpenDisplay failed; use the authenticated TCP Xvfb wrapper");
    probe.window = XCreateSimpleWindow(probe.display, DefaultRootWindow(probe.display),
                                      16, 16, width, height, 0, 0, 0);
    XStoreName(probe.display, probe.window, "OBS Overlay Vulkan integration probe");
    XMapWindow(probe.display, probe.window);
    XSync(probe.display, False);
    create_instance(&probe);
    create_device(&probe);
    create_swapchain(&probe, width, height);
    for (unsigned frame = 0; frame < frames; ++frame) {
        bool resized = resize_frame && frame == resize_frame;
        if (resized) {
            destroy_swapchain(&probe);
            create_swapchain(&probe, width + 64, height + 48);
        }
        bool last_generation = frame + 1 == frames || (resize_frame && frame + 1 == resize_frame);
        bool active = probe.production_library && frame % 5 != 2 && !resized;
        render_frame(&probe, frame, active, resized, last_generation);
    }
    destroy_swapchain(&probe);
    vkDestroyCommandPool(probe.device, probe.pool, NULL);
    vkDestroyDevice(probe.device, NULL);
    vkDestroySurfaceKHR(probe.instance, probe.surface, NULL);
    if (probe.debug) {
        PFN_vkDestroyDebugUtilsMessengerEXT destroy =
            (PFN_vkDestroyDebugUtilsMessengerEXT)vkGetInstanceProcAddr(probe.instance, "vkDestroyDebugUtilsMessengerEXT");
        destroy(probe.instance, probe.debug, NULL);
    }
    vkDestroyInstance(probe.instance, NULL);
    XDestroyWindow(probe.display, probe.window);
    XCloseDisplay(probe.display);
    unsigned errors = atomic_load(&validation_errors);
    if (errors) {
        fprintf(stderr, "FAIL: %u Vulkan validation errors\n", errors);
        return 1;
    }
    printf("RESULT PASS mode=%s frames=%u verified=%u active=%u dirty_off=%u generations=%u validation_errors=%u\n",
           production && observer ? "production-layer" : "presentation-baseline", frames,
           probe.validated_frames, probe.active_frames, probe.dirty_off_frames,
           probe.seen_generations, errors);
    return 0;
}
