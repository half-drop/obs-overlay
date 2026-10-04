// SPDX-License-Identifier: MIT
// Copyright (c) 2026 half-drop contributors
// An original explicit Vulkan layer; no OBS implementation is linked or copied.
#include "obs_overlay_vulkan.h"
#include "hud_shaders.hpp"
#include <vulkan/vk_layer.h>

#include <algorithm>
#include <array>
#include <cstddef>
#include <cstdio>
#include <cstring>
#include <limits>
#include <memory>
#include <mutex>
#include <new>
#include <type_traits>
#include <unordered_map>
#include <utility>
#include <vector>

namespace {
static_assert(sizeof(void *) == 8, "OBS Overlay Vulkan supports 64-bit processes");
static_assert(offsetof(ObsVkHudFrame, frame_id) == 64, "HUD ABI frame_id offset");

std::mutex errorMutex;
std::array<char, 1024> lastError{};

void error(const char *message, VkResult result = VK_SUCCESS) noexcept {
    std::lock_guard<std::mutex> lock(errorMutex);
    if (result == VK_SUCCESS) std::snprintf(lastError.data(), lastError.size(), "%s", message);
    else std::snprintf(lastError.data(), lastError.size(), "%s (VkResult %d)", message, static_cast<int>(result));
    std::fprintf(stderr, "[obs-overlay-vulkan] %s\n", lastError.data());
}

template <typename T> uint64_t raw(T handle) noexcept {
    if constexpr (std::is_pointer_v<T>) return static_cast<uint64_t>(reinterpret_cast<uintptr_t>(handle));
    else return static_cast<uint64_t>(handle);
}
template <typename T> T handle(uint64_t value) noexcept {
    if constexpr (std::is_pointer_v<T>) return reinterpret_cast<T>(static_cast<uintptr_t>(value));
    else return static_cast<T>(value);
}
template <typename T> void *dispatchKey(T object) noexcept {
    return object ? *reinterpret_cast<void *const *>(object) : nullptr;
}

struct VulkanFailure { VkResult result; };
void check(VkResult result, const char *operation) {
    if (result != VK_SUCCESS) { error(operation, result); throw VulkanFailure{result}; }
}

#define DEVICE_FUNCTIONS(X) \
    X(DestroyDevice) X(GetDeviceQueue) X(CreateSwapchainKHR) X(DestroySwapchainKHR) \
    X(GetSwapchainImagesKHR) X(AcquireNextImageKHR) X(QueuePresentKHR) X(QueueSubmit) X(QueueWaitIdle) \
    X(CreateCommandPool) X(DestroyCommandPool) X(AllocateCommandBuffers) X(ResetCommandPool) \
    X(BeginCommandBuffer) X(EndCommandBuffer) X(CmdPipelineBarrier) X(CmdBeginRenderPass) \
    X(CmdEndRenderPass) X(CmdBindPipeline) X(CmdSetViewport) X(CmdSetScissor) \
    X(CmdBindDescriptorSets) X(CmdDraw) X(CreateRenderPass) X(DestroyRenderPass) \
    X(CreateFramebuffer) X(DestroyFramebuffer) X(CreateShaderModule) X(DestroyShaderModule) \
    X(CreateDescriptorSetLayout) X(DestroyDescriptorSetLayout) X(CreatePipelineLayout) \
    X(DestroyPipelineLayout) X(CreateGraphicsPipelines) X(DestroyPipeline) \
    X(CreateDescriptorPool) X(DestroyDescriptorPool) X(AllocateDescriptorSets) X(UpdateDescriptorSets) \
    X(CreateSampler) X(DestroySampler) X(CreateImageView) X(DestroyImageView) \
    X(CreateSemaphore) X(DestroySemaphore) X(CreateFence) X(DestroyFence) X(WaitForFences) X(ResetFences)

struct DeviceFunctions {
    PFN_vkGetDeviceProcAddr GetDeviceProcAddr = nullptr;
#define DECLARE_FUNCTION(name) PFN_vk##name name = nullptr;
    DEVICE_FUNCTIONS(DECLARE_FUNCTION)
#undef DECLARE_FUNCTION
    PFN_vkGetDeviceQueue2 GetDeviceQueue2 = nullptr;
    PFN_vkAcquireNextImage2KHR AcquireNextImage2KHR = nullptr;
};

struct InstanceState {
    VkInstance instance = VK_NULL_HANDLE;
    PFN_vkGetInstanceProcAddr GetInstanceProcAddr = nullptr;
    PFN_GetPhysicalDeviceProcAddr GetPhysicalDeviceProcAddr = nullptr;
    PFN_vkDestroyInstance DestroyInstance = nullptr;
    PFN_vkGetPhysicalDeviceQueueFamilyProperties GetPhysicalDeviceQueueFamilyProperties = nullptr;
    PFN_vkGetPhysicalDeviceSurfaceCapabilitiesKHR GetPhysicalDeviceSurfaceCapabilitiesKHR = nullptr;
    PFN_vkGetPhysicalDeviceFormatProperties GetPhysicalDeviceFormatProperties = nullptr;
};

struct Submission {
    VkFence fence = VK_NULL_HANDLE;
    bool pending = false;
};

struct FrameSlot {
    VkImage image = VK_NULL_HANDLE;
    VkImageView view = VK_NULL_HANDLE;
    VkFramebuffer framebuffer = VK_NULL_HANDLE;
    VkCommandPool commandPool = VK_NULL_HANDLE;
    VkCommandBuffer commands = VK_NULL_HANDLE;
    VkDescriptorPool descriptorPool = VK_NULL_HANDLE;
    VkDescriptorSet descriptor = VK_NULL_HANDLE;
    VkSemaphore presentReady = VK_NULL_HANDLE;
    std::shared_ptr<Submission> submission;
    uint64_t hudImage = 0;
    uint64_t acquireSerial = 0;
    uint64_t presentedSerial = 0;
    bool semaphoreAbandoned = false;
};

struct HudImage {
    VkImageView view = VK_NULL_HANDLE;
    VkFormat format = VK_FORMAT_UNDEFINED;
};

struct SwapchainState {
    VkSwapchainKHR swapchain = VK_NULL_HANDLE;
    VkFormat format = VK_FORMAT_UNDEFINED;
    VkExtent2D extent{};
    bool supported = false;
    bool initialized = false;
    uint32_t family = UINT32_MAX;
    VkQueue usedQueue = VK_NULL_HANDLE;
    VkRenderPass renderPass = VK_NULL_HANDLE;
    std::array<VkPipeline, 2> pipelines{};
    std::vector<FrameSlot> frames;
    std::unordered_map<uint64_t, HudImage> hudImages;
    ObsVkHudFrame published{};
};

struct QueueState { uint32_t family; VkQueueFlags flags; bool protectedQueue; };

struct DeviceState {
    VkDevice device = VK_NULL_HANDLE;
    VkPhysicalDevice physicalDevice = VK_NULL_HANDLE;
    std::shared_ptr<InstanceState> instance;
    DeviceFunctions f;
    PFN_vkSetDeviceLoaderData SetDeviceLoaderData = nullptr;
    bool ready = false;
    bool announced = false;
    std::mutex mutex;
    std::vector<VkQueueFamilyProperties> families;
    std::unordered_map<uint64_t, QueueState> queues;
    std::unordered_map<uint64_t, std::unique_ptr<SwapchainState>> swaps;
    std::vector<std::shared_ptr<Submission>> submissions;
    VkDescriptorSetLayout descriptorLayout = VK_NULL_HANDLE;
    VkPipelineLayout pipelineLayout = VK_NULL_HANDLE;
    VkSampler sampler = VK_NULL_HANDLE;
    VkShaderModule vertexShader = VK_NULL_HANDLE;
    VkShaderModule fragmentShader = VK_NULL_HANDLE;
};

std::mutex registryMutex;
std::unordered_map<void *, std::shared_ptr<InstanceState>> instances;
std::unordered_map<uint64_t, std::shared_ptr<DeviceState>> devices;
std::unordered_map<void *, std::weak_ptr<DeviceState>> deviceDispatch;

template <typename T> std::shared_ptr<InstanceState> findInstance(T object) {
    std::lock_guard<std::mutex> lock(registryMutex);
    auto it = instances.find(dispatchKey(object));
    return it == instances.end() ? nullptr : it->second;
}
std::shared_ptr<DeviceState> findDevice(uint64_t key) {
    std::lock_guard<std::mutex> lock(registryMutex);
    auto it = devices.find(key);
    return it == devices.end() ? nullptr : it->second;
}
std::shared_ptr<DeviceState> findQueueDevice(VkQueue queue) {
    std::lock_guard<std::mutex> lock(registryMutex);
    auto it = deviceDispatch.find(dispatchKey(queue));
    return it == deviceDispatch.end() ? nullptr : it->second.lock();
}

bool sdrFormat(VkFormat format) noexcept {
    return format == VK_FORMAT_R8G8B8A8_UNORM || format == VK_FORMAT_B8G8R8A8_UNORM ||
           format == VK_FORMAT_R8G8B8A8_SRGB || format == VK_FORMAT_B8G8R8A8_SRGB;
}

VkImageSubresourceRange colorRange() noexcept {
    VkImageSubresourceRange range{};
    range.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    range.levelCount = 1;
    range.layerCount = 1;
    return range;
}

void waitSubmission(DeviceState &d, const std::shared_ptr<Submission> &submission) {
    if (submission && submission->pending) {
        check(d.f.WaitForFences(d.device, 1, &submission->fence, VK_TRUE, UINT64_MAX), "Waiting for HUD sampling failed");
        submission->pending = false;
    }
}

void waitSlot(DeviceState &d, FrameSlot &frame) {
    waitSubmission(d, frame.submission);
    frame.submission.reset();
    frame.hudImage = 0;
}

std::shared_ptr<Submission> reserveSubmission(DeviceState &d) {
    for (const auto &submission : d.submissions) {
        if (submission.use_count() == 1) {
            waitSubmission(d, submission);
            check(d.f.ResetFences(d.device, 1, &submission->fence), "Resetting a HUD fence failed");
            return submission;
        }
    }
    auto submission = std::make_shared<Submission>();
    d.submissions.push_back(submission);
    VkFenceCreateInfo info{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
    check(d.f.CreateFence(d.device, &info, nullptr, &submission->fence), "Creating a HUD fence failed");
    return submission;
}

void clearHud(DeviceState &d, SwapchainState &swap) {
    swap.published = {};
    for (auto &frame : swap.frames) waitSlot(d, frame);
    for (const auto &entry : swap.hudImages) d.f.DestroyImageView(d.device, entry.second.view, nullptr);
    swap.hudImages.clear();
}

void destroySwapResources(DeviceState &d, SwapchainState &swap) noexcept {
    // DestroySwapchainKHR is a retirement path, never a per-frame wait. Finish
    // native commands and the queue's pending present waits before releasing
    // the per-image presentation semaphores.
    if (swap.usedQueue) {
        VkResult result = d.f.QueueWaitIdle(swap.usedQueue);
        if (result != VK_SUCCESS) error("Waiting for the retiring swapchain failed", result);
    }
    for (auto &frame : swap.frames) {
        if (frame.submission) frame.submission->pending = false;
        if (frame.framebuffer) d.f.DestroyFramebuffer(d.device, frame.framebuffer, nullptr);
        if (frame.view) d.f.DestroyImageView(d.device, frame.view, nullptr);
        if (frame.descriptorPool) d.f.DestroyDescriptorPool(d.device, frame.descriptorPool, nullptr);
        if (frame.commandPool) d.f.DestroyCommandPool(d.device, frame.commandPool, nullptr);
        if (frame.presentReady) d.f.DestroySemaphore(d.device, frame.presentReady, nullptr);
        frame = {};
    }
    for (const auto &entry : swap.hudImages) d.f.DestroyImageView(d.device, entry.second.view, nullptr);
    swap.hudImages.clear();
    for (auto &pipeline : swap.pipelines) {
        if (pipeline) d.f.DestroyPipeline(d.device, pipeline, nullptr);
        pipeline = VK_NULL_HANDLE;
    }
    if (swap.renderPass) d.f.DestroyRenderPass(d.device, swap.renderPass, nullptr);
    swap.renderPass = VK_NULL_HANDLE;
    swap.initialized = false;
    swap.published = {};
}

void destroyDeviceResources(DeviceState &d) noexcept {
    for (auto &entry : d.swaps) destroySwapResources(d, *entry.second);
    d.swaps.clear();
    for (const auto &submission : d.submissions) {
        if (submission->fence) d.f.DestroyFence(d.device, submission->fence, nullptr);
    }
    d.submissions.clear();
    if (d.vertexShader) d.f.DestroyShaderModule(d.device, d.vertexShader, nullptr);
    if (d.fragmentShader) d.f.DestroyShaderModule(d.device, d.fragmentShader, nullptr);
    if (d.sampler) d.f.DestroySampler(d.device, d.sampler, nullptr);
    if (d.pipelineLayout) d.f.DestroyPipelineLayout(d.device, d.pipelineLayout, nullptr);
    if (d.descriptorLayout) d.f.DestroyDescriptorSetLayout(d.device, d.descriptorLayout, nullptr);
}

void ensureDeviceResources(DeviceState &d) {
    if (!d.descriptorLayout) {
        VkDescriptorSetLayoutBinding binding{};
        binding.binding = 0;
        binding.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        binding.descriptorCount = 1;
        binding.stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT;
        VkDescriptorSetLayoutCreateInfo info{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
        info.bindingCount = 1;
        info.pBindings = &binding;
        check(d.f.CreateDescriptorSetLayout(d.device, &info, nullptr, &d.descriptorLayout), "Creating the HUD descriptor layout failed");
    }
    if (!d.pipelineLayout) {
        VkPipelineLayoutCreateInfo info{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
        info.setLayoutCount = 1;
        info.pSetLayouts = &d.descriptorLayout;
        check(d.f.CreatePipelineLayout(d.device, &info, nullptr, &d.pipelineLayout), "Creating the HUD pipeline layout failed");
    }
    if (!d.sampler) {
        VkSamplerCreateInfo info{VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO};
        info.magFilter = VK_FILTER_NEAREST;
        info.minFilter = VK_FILTER_NEAREST;
        info.mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST;
        info.addressModeU = info.addressModeV = info.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
        info.maxLod = 0.0f;
        check(d.f.CreateSampler(d.device, &info, nullptr, &d.sampler), "Creating the HUD sampler failed");
    }
    if (!d.vertexShader) {
        VkShaderModuleCreateInfo info{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
        info.codeSize = sizeof(obsvk_shaders::vert);
        info.pCode = obsvk_shaders::vert;
        check(d.f.CreateShaderModule(d.device, &info, nullptr, &d.vertexShader), "Creating the HUD vertex shader failed");
    }
    if (!d.fragmentShader) {
        VkShaderModuleCreateInfo info{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
        info.codeSize = sizeof(obsvk_shaders::frag);
        info.pCode = obsvk_shaders::frag;
        check(d.f.CreateShaderModule(d.device, &info, nullptr, &d.fragmentShader), "Creating the HUD fragment shader failed");
    }
}

void createPipeline(DeviceState &d, SwapchainState &swap, unsigned premultiplied) {
    if (swap.pipelines[premultiplied]) return;
    std::array<VkPipelineShaderStageCreateInfo, 2> stages{};
    for (auto &stage : stages) stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    stages[0].stage = VK_SHADER_STAGE_VERTEX_BIT;
    stages[0].module = d.vertexShader;
    stages[0].pName = "main";
    stages[1].stage = VK_SHADER_STAGE_FRAGMENT_BIT;
    stages[1].module = d.fragmentShader;
    stages[1].pName = "main";
    VkPipelineVertexInputStateCreateInfo vertex{VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO};
    VkPipelineInputAssemblyStateCreateInfo assembly{VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO};
    assembly.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
    VkPipelineViewportStateCreateInfo viewport{VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO};
    viewport.viewportCount = viewport.scissorCount = 1;
    VkPipelineRasterizationStateCreateInfo raster{VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO};
    raster.polygonMode = VK_POLYGON_MODE_FILL;
    raster.cullMode = VK_CULL_MODE_NONE;
    raster.frontFace = VK_FRONT_FACE_COUNTER_CLOCKWISE;
    raster.lineWidth = 1.0f;
    VkPipelineMultisampleStateCreateInfo samples{VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO};
    samples.rasterizationSamples = VK_SAMPLE_COUNT_1_BIT;
    VkPipelineColorBlendAttachmentState attachment{};
    attachment.blendEnable = VK_TRUE;
    attachment.srcColorBlendFactor = premultiplied ? VK_BLEND_FACTOR_ONE : VK_BLEND_FACTOR_SRC_ALPHA;
    attachment.dstColorBlendFactor = VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
    attachment.colorBlendOp = VK_BLEND_OP_ADD;
    attachment.srcAlphaBlendFactor = VK_BLEND_FACTOR_ONE;
    attachment.dstAlphaBlendFactor = VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
    attachment.alphaBlendOp = VK_BLEND_OP_ADD;
    attachment.colorWriteMask = VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT |
                                VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT;
    VkPipelineColorBlendStateCreateInfo blend{VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO};
    blend.attachmentCount = 1;
    blend.pAttachments = &attachment;
    const VkDynamicState dynamicStates[] = {VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR};
    VkPipelineDynamicStateCreateInfo dynamic{VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO};
    dynamic.dynamicStateCount = 2;
    dynamic.pDynamicStates = dynamicStates;
    VkGraphicsPipelineCreateInfo info{VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO};
    info.stageCount = static_cast<uint32_t>(stages.size());
    info.pStages = stages.data();
    info.pVertexInputState = &vertex;
    info.pInputAssemblyState = &assembly;
    info.pViewportState = &viewport;
    info.pRasterizationState = &raster;
    info.pMultisampleState = &samples;
    info.pColorBlendState = &blend;
    info.pDynamicState = &dynamic;
    info.layout = d.pipelineLayout;
    info.renderPass = swap.renderPass;
    check(d.f.CreateGraphicsPipelines(d.device, VK_NULL_HANDLE, 1, &info, nullptr,
                                     &swap.pipelines[premultiplied]), "Creating the HUD blend pipeline failed");
}

void ensureSwapResources(DeviceState &d, SwapchainState &swap, uint32_t family) {
    if (swap.initialized) return;
    ensureDeviceResources(d);
    swap.family = family;
    if (!swap.renderPass) {
        VkAttachmentDescription attachment{};
        attachment.format = swap.format;
        attachment.samples = VK_SAMPLE_COUNT_1_BIT;
        attachment.loadOp = VK_ATTACHMENT_LOAD_OP_LOAD;
        attachment.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
        attachment.stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
        attachment.stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
        attachment.initialLayout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
        attachment.finalLayout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
        VkAttachmentReference color{0, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL};
        VkSubpassDescription subpass{};
        subpass.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
        subpass.colorAttachmentCount = 1;
        subpass.pColorAttachments = &color;
        VkRenderPassCreateInfo info{VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO};
        info.attachmentCount = 1;
        info.pAttachments = &attachment;
        info.subpassCount = 1;
        info.pSubpasses = &subpass;
        check(d.f.CreateRenderPass(d.device, &info, nullptr, &swap.renderPass), "Creating the HUD LOAD render pass failed");
    }
    createPipeline(d, swap, 0);
    createPipeline(d, swap, 1);
    for (auto &frame : swap.frames) {
        if (!frame.view) {
            VkImageViewCreateInfo info{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
            info.image = frame.image;
            info.viewType = VK_IMAGE_VIEW_TYPE_2D;
            info.format = swap.format;
            info.subresourceRange = colorRange();
            check(d.f.CreateImageView(d.device, &info, nullptr, &frame.view), "Creating a swapchain HUD view failed");
        }
        if (!frame.framebuffer) {
            VkFramebufferCreateInfo info{VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO};
            info.renderPass = swap.renderPass;
            info.attachmentCount = 1;
            info.pAttachments = &frame.view;
            info.width = swap.extent.width;
            info.height = swap.extent.height;
            info.layers = 1;
            check(d.f.CreateFramebuffer(d.device, &info, nullptr, &frame.framebuffer), "Creating a HUD framebuffer failed");
        }
        if (!frame.commandPool) {
            VkCommandPoolCreateInfo info{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
            info.flags = VK_COMMAND_POOL_CREATE_TRANSIENT_BIT;
            info.queueFamilyIndex = family;
            check(d.f.CreateCommandPool(d.device, &info, nullptr, &frame.commandPool), "Creating a HUD command pool failed");
        }
        if (!frame.commands) {
            VkCommandBufferAllocateInfo info{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
            info.commandPool = frame.commandPool;
            info.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
            info.commandBufferCount = 1;
            check(d.f.AllocateCommandBuffers(d.device, &info, &frame.commands), "Allocating a HUD command buffer failed");
            if (d.SetDeviceLoaderData)
                check(d.SetDeviceLoaderData(d.device, frame.commands), "Initializing HUD command dispatch failed");
        }
        if (!frame.descriptorPool) {
            VkDescriptorPoolSize size{VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1};
            VkDescriptorPoolCreateInfo info{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
            info.maxSets = 1;
            info.poolSizeCount = 1;
            info.pPoolSizes = &size;
            check(d.f.CreateDescriptorPool(d.device, &info, nullptr, &frame.descriptorPool), "Creating a HUD descriptor pool failed");
        }
        if (!frame.descriptor) {
            VkDescriptorSetAllocateInfo info{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
            info.descriptorPool = frame.descriptorPool;
            info.descriptorSetCount = 1;
            info.pSetLayouts = &d.descriptorLayout;
            check(d.f.AllocateDescriptorSets(d.device, &info, &frame.descriptor), "Allocating a HUD descriptor failed");
        }
        if (!frame.presentReady) {
            VkSemaphoreCreateInfo info{VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
            check(d.f.CreateSemaphore(d.device, &info, nullptr, &frame.presentReady), "Creating a HUD presentation semaphore failed");
        }
    }
    swap.initialized = true;
}

VkImageView ensureHudView(DeviceState &d, SwapchainState &swap, const ObsVkHudFrame &hud) {
    auto found = swap.hudImages.find(hud.image);
    if (found != swap.hudImages.end()) {
        if (found->second.format != static_cast<VkFormat>(hud.format)) {
            error("A HUD image was republished with a different format; clear it before destroying or replacing it");
            throw VulkanFailure{VK_ERROR_FORMAT_NOT_SUPPORTED};
        }
        return found->second.view;
    }
    auto inserted = swap.hudImages.emplace(hud.image, HudImage{}).first;
    auto &entry = inserted->second;
    entry.format = static_cast<VkFormat>(hud.format);
    VkImageViewCreateInfo info{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
    info.image = handle<VkImage>(hud.image);
    info.viewType = VK_IMAGE_VIEW_TYPE_2D;
    info.format = entry.format;
    info.subresourceRange = colorRange();
    VkResult result = d.f.CreateImageView(d.device, &info, nullptr, &entry.view);
    if (result != VK_SUCCESS) swap.hudImages.erase(inserted);
    check(result, "Creating a sampled HUD image view failed");
    return entry.view;
}

void recordHud(DeviceState &d, SwapchainState &swap, FrameSlot &frame, const ObsVkHudFrame &hud) {
    waitSlot(d, frame);
    if (frame.semaphoreAbandoned) {
        // A present OOM is specified not to enqueue its waits. The old signal
        // is now complete, so replace that unconsumed binary semaphore.
        d.f.DestroySemaphore(d.device, frame.presentReady, nullptr);
        frame.presentReady = VK_NULL_HANDLE;
        VkSemaphoreCreateInfo info{VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
        check(d.f.CreateSemaphore(d.device, &info, nullptr, &frame.presentReady), "Replacing an unused presentation semaphore failed");
        frame.semaphoreAbandoned = false;
    }
    VkDescriptorImageInfo imageInfo{d.sampler, ensureHudView(d, swap, hud), static_cast<VkImageLayout>(hud.layout)};
    VkWriteDescriptorSet descriptor{VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};
    descriptor.dstSet = frame.descriptor;
    descriptor.dstBinding = 0;
    descriptor.descriptorCount = 1;
    descriptor.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    descriptor.pImageInfo = &imageInfo;
    d.f.UpdateDescriptorSets(d.device, 1, &descriptor, 0, nullptr);
    check(d.f.ResetCommandPool(d.device, frame.commandPool, 0), "Resetting a HUD command pool failed");
    VkCommandBufferBeginInfo begin{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    check(d.f.BeginCommandBuffer(frame.commands, &begin), "Beginning a HUD command buffer failed");

    std::array<VkImageMemoryBarrier, 2> barriers{};
    for (auto &barrier : barriers) {
        barrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        barrier.srcQueueFamilyIndex = barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        barrier.subresourceRange = colorRange();
    }
    auto &target = barriers[0];
    target.image = frame.image;
    // ALL_COMMANDS also covers graphics work from other upstream layers.
    // In particular the prior OBS TRANSFER_READ must finish before LOAD/blend.
    target.srcAccessMask = VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT;
    target.dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_READ_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
    target.oldLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
    target.newLayout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
    auto &source = barriers[1];
    source.image = handle<VkImage>(hud.image);
    source.srcAccessMask = VK_ACCESS_MEMORY_WRITE_BIT;
    source.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    source.oldLayout = source.newLayout = static_cast<VkImageLayout>(hud.layout);
    d.f.CmdPipelineBarrier(frame.commands, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
        VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
        0, 0, nullptr, 0, nullptr, static_cast<uint32_t>(barriers.size()), barriers.data());

    VkRenderPassBeginInfo render{VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO};
    render.renderPass = swap.renderPass;
    render.framebuffer = frame.framebuffer;
    render.renderArea.extent = swap.extent;
    d.f.CmdBeginRenderPass(frame.commands, &render, VK_SUBPASS_CONTENTS_INLINE);
    const unsigned premultiplied = (hud.flags & OBSVK_HUD_PREMULTIPLIED_ALPHA) ? 1u : 0u;
    d.f.CmdBindPipeline(frame.commands, VK_PIPELINE_BIND_POINT_GRAPHICS, swap.pipelines[premultiplied]);
    VkViewport viewport{0.0f, 0.0f, static_cast<float>(swap.extent.width), static_cast<float>(swap.extent.height), 0.0f, 1.0f};
    VkRect2D scissor{{0, 0}, swap.extent};
    d.f.CmdSetViewport(frame.commands, 0, 1, &viewport);
    d.f.CmdSetScissor(frame.commands, 0, 1, &scissor);
    d.f.CmdBindDescriptorSets(frame.commands, VK_PIPELINE_BIND_POINT_GRAPHICS, d.pipelineLayout,
                             0, 1, &frame.descriptor, 0, nullptr);
    d.f.CmdDraw(frame.commands, 3, 1, 0, 0);
    d.f.CmdEndRenderPass(frame.commands);

    target.srcAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
    target.dstAccessMask = 0;
    target.oldLayout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
    target.newLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
    source.srcAccessMask = VK_ACCESS_SHADER_READ_BIT;
    source.dstAccessMask = VK_ACCESS_MEMORY_WRITE_BIT;
    d.f.CmdPipelineBarrier(frame.commands,
        VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
        VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, 0, nullptr, 0, nullptr,
        static_cast<uint32_t>(barriers.size()), barriers.data());
    check(d.f.EndCommandBuffer(frame.commands), "Ending a HUD command buffer failed");
}

template <typename T> T *layerInfo(const void *head, VkStructureType type, VkLayerFunction function) {
    auto *item = reinterpret_cast<const VkBaseInStructure *>(head);
    while (item) {
        if (item->sType == type) {
            auto *info = reinterpret_cast<const T *>(item);
            if (info->function == function) return const_cast<T *>(info);
        }
        item = item->pNext;
    }
    return nullptr;
}

VkResult VKAPI_CALL createInstance(const VkInstanceCreateInfo *info, const VkAllocationCallbacks *allocator, VkInstance *out) {
    try {
        auto *link = layerInfo<VkLayerInstanceCreateInfo>(info->pNext, VK_STRUCTURE_TYPE_LOADER_INSTANCE_CREATE_INFO, VK_LAYER_LINK_INFO);
        if (!link || !link->u.pLayerInfo) return VK_ERROR_INITIALIZATION_FAILED;
        const auto next = link->u.pLayerInfo->pfnNextGetInstanceProcAddr;
        const auto nextPhysical = link->u.pLayerInfo->pfnNextGetPhysicalDeviceProcAddr;
        const auto create = reinterpret_cast<PFN_vkCreateInstance>(next(VK_NULL_HANDLE, "vkCreateInstance"));
        if (!create) return VK_ERROR_INITIALIZATION_FAILED;
        auto instance = std::make_shared<InstanceState>();
        link->u.pLayerInfo = link->u.pLayerInfo->pNext;
        const VkResult result = create(info, allocator, out);
        if (result != VK_SUCCESS) return result;
        instance->instance = *out;
        instance->GetInstanceProcAddr = next;
        instance->GetPhysicalDeviceProcAddr = nextPhysical;
#define INSTANCE_FUNCTION(name) instance->name = reinterpret_cast<PFN_vk##name>(next(*out, "vk" #name));
        INSTANCE_FUNCTION(DestroyInstance)
        INSTANCE_FUNCTION(GetPhysicalDeviceQueueFamilyProperties)
        INSTANCE_FUNCTION(GetPhysicalDeviceSurfaceCapabilitiesKHR)
        INSTANCE_FUNCTION(GetPhysicalDeviceFormatProperties)
#undef INSTANCE_FUNCTION
        try {
            std::lock_guard<std::mutex> lock(registryMutex);
            instances.emplace(dispatchKey(*out), instance);
        } catch (...) {
            instance->DestroyInstance(*out, allocator);
            *out = VK_NULL_HANDLE;
            throw;
        }
        return VK_SUCCESS;
    } catch (const std::bad_alloc &) { error("Not enough memory to track the Vulkan instance"); return VK_ERROR_OUT_OF_HOST_MEMORY; }
    catch (...) { error("Creating the Vulkan layer instance failed"); return VK_ERROR_INITIALIZATION_FAILED; }
}

void VKAPI_CALL destroyInstance(VkInstance instance, const VkAllocationCallbacks *allocator) {
    auto state = findInstance(instance);
    if (!state) return;
    {
        std::lock_guard<std::mutex> lock(registryMutex);
        instances.erase(dispatchKey(instance));
    }
    state->DestroyInstance(instance, allocator);
}

VkResult VKAPI_CALL createDevice(VkPhysicalDevice physical, const VkDeviceCreateInfo *info,
                                 const VkAllocationCallbacks *allocator, VkDevice *out) {
    std::shared_ptr<DeviceState> d;
    try {
        auto instance = findInstance(physical);
        auto *link = layerInfo<VkLayerDeviceCreateInfo>(info->pNext, VK_STRUCTURE_TYPE_LOADER_DEVICE_CREATE_INFO, VK_LAYER_LINK_INFO);
        auto *callback = layerInfo<VkLayerDeviceCreateInfo>(info->pNext, VK_STRUCTURE_TYPE_LOADER_DEVICE_CREATE_INFO, VK_LOADER_DATA_CALLBACK);
        if (!instance || !link || !link->u.pLayerInfo) return VK_ERROR_INITIALIZATION_FAILED;
        auto nextGipa = link->u.pLayerInfo->pfnNextGetInstanceProcAddr;
        auto nextGdpa = link->u.pLayerInfo->pfnNextGetDeviceProcAddr;
        auto create = reinterpret_cast<PFN_vkCreateDevice>(nextGipa(instance->instance, "vkCreateDevice"));
        if (!create) return VK_ERROR_INITIALIZATION_FAILED;
        d = std::make_shared<DeviceState>();
        d->instance = instance;
        d->physicalDevice = physical;
        d->SetDeviceLoaderData = callback ? callback->u.pfnSetDeviceLoaderData : nullptr;
        link->u.pLayerInfo = link->u.pLayerInfo->pNext;
        const VkResult result = create(physical, info, allocator, out);
        if (result != VK_SUCCESS) return result;
        d->device = *out;
        d->f.GetDeviceProcAddr = nextGdpa;
        bool functionsReady = true;
#define LOAD_FUNCTION(name) \
        d->f.name = reinterpret_cast<PFN_vk##name>(nextGdpa(*out, "vk" #name)); \
        functionsReady = functionsReady && d->f.name != nullptr;
        DEVICE_FUNCTIONS(LOAD_FUNCTION)
#undef LOAD_FUNCTION
        d->f.GetDeviceQueue2 = reinterpret_cast<PFN_vkGetDeviceQueue2>(nextGdpa(*out, "vkGetDeviceQueue2"));
        d->f.AcquireNextImage2KHR = reinterpret_cast<PFN_vkAcquireNextImage2KHR>(nextGdpa(*out, "vkAcquireNextImage2KHR"));
        uint32_t count = 0;
        instance->GetPhysicalDeviceQueueFamilyProperties(physical, &count, nullptr);
        d->families.resize(count);
        instance->GetPhysicalDeviceQueueFamilyProperties(physical, &count, d->families.data());
        bool graphicsQueue = false;
        for (uint32_t i = 0; i < info->queueCreateInfoCount; ++i) {
            const auto &requested = info->pQueueCreateInfos[i];
            if (requested.queueFamilyIndex >= d->families.size()) continue;
            for (uint32_t j = 0; j < requested.queueCount; ++j) {
                VkQueue queue = VK_NULL_HANDLE;
                if (requested.flags == 0) d->f.GetDeviceQueue(*out, requested.queueFamilyIndex, j, &queue);
                else if (d->f.GetDeviceQueue2) {
                    VkDeviceQueueInfo2 queueInfo{VK_STRUCTURE_TYPE_DEVICE_QUEUE_INFO_2};
                    queueInfo.flags = requested.flags;
                    queueInfo.queueFamilyIndex = requested.queueFamilyIndex;
                    queueInfo.queueIndex = j;
                    d->f.GetDeviceQueue2(*out, &queueInfo, &queue);
                }
                if (!queue) continue;
                if (d->SetDeviceLoaderData) check(d->SetDeviceLoaderData(*out, queue), "Initializing HUD queue dispatch failed");
                const bool protectedQueue = (requested.flags & VK_DEVICE_QUEUE_CREATE_PROTECTED_BIT) != 0;
                const VkQueueFlags flags = d->families[requested.queueFamilyIndex].queueFlags;
                d->queues.emplace(raw(queue), QueueState{requested.queueFamilyIndex, flags, protectedQueue});
                graphicsQueue = graphicsQueue || (!protectedQueue && (flags & VK_QUEUE_GRAPHICS_BIT));
            }
        }
        d->ready = functionsReady && graphicsQueue && instance->GetPhysicalDeviceSurfaceCapabilitiesKHR &&
                   instance->GetPhysicalDeviceFormatProperties;
        {
            std::lock_guard<std::mutex> lock(registryMutex);
            devices.emplace(raw(*out), d);
            deviceDispatch.emplace(dispatchKey(*out), d);
        }
        return VK_SUCCESS;
    } catch (const VulkanFailure &failure) {
        if (d && d->device && d->f.DestroyDevice) d->f.DestroyDevice(d->device, allocator);
        *out = VK_NULL_HANDLE;
        return failure.result;
    } catch (...) {
        if (d && d->device && d->f.DestroyDevice) d->f.DestroyDevice(d->device, allocator);
        *out = VK_NULL_HANDLE;
        error("Not enough memory to track the Vulkan device");
        return VK_ERROR_OUT_OF_HOST_MEMORY;
    }
}

void VKAPI_CALL destroyDevice(VkDevice device, const VkAllocationCallbacks *allocator) {
    auto d = findDevice(raw(device));
    if (!d) return;
    {
        std::lock_guard<std::mutex> lock(d->mutex);
        d->ready = false;
        destroyDeviceResources(*d);
    }
    {
        std::lock_guard<std::mutex> lock(registryMutex);
        devices.erase(raw(device));
        deviceDispatch.erase(dispatchKey(device));
    }
    d->f.DestroyDevice(device, allocator);
}

void VKAPI_CALL getDeviceQueue(VkDevice device, uint32_t family, uint32_t index, VkQueue *out) {
    auto d = findDevice(raw(device));
    if (!d) { *out = VK_NULL_HANDLE; return; }
    d->f.GetDeviceQueue(device, family, index, out);
    try {
        std::lock_guard<std::mutex> lock(d->mutex);
        if (*out && family < d->families.size())
            d->queues[raw(*out)] = QueueState{family, d->families[family].queueFlags, false};
    } catch (...) { error("Not enough memory to track a graphics queue"); }
}

void VKAPI_CALL getDeviceQueue2(VkDevice device, const VkDeviceQueueInfo2 *info, VkQueue *out) {
    auto d = findDevice(raw(device));
    if (!d || !d->f.GetDeviceQueue2) { *out = VK_NULL_HANDLE; return; }
    d->f.GetDeviceQueue2(device, info, out);
    try {
        std::lock_guard<std::mutex> lock(d->mutex);
        if (*out && info->queueFamilyIndex < d->families.size())
            d->queues[raw(*out)] = QueueState{info->queueFamilyIndex, d->families[info->queueFamilyIndex].queueFlags,
                                           (info->flags & VK_DEVICE_QUEUE_CREATE_PROTECTED_BIT) != 0};
    } catch (...) { error("Not enough memory to track a graphics queue"); }
}

VkResult VKAPI_CALL createSwapchain(VkDevice device, const VkSwapchainCreateInfoKHR *info,
                                    const VkAllocationCallbacks *allocator, VkSwapchainKHR *out) {
    auto d = findDevice(raw(device));
    if (!d || !d->f.CreateSwapchainKHR) return VK_ERROR_INITIALIZATION_FAILED;
    std::lock_guard<std::mutex> lock(d->mutex);
    VkSwapchainCreateInfoKHR modified = *info;
    bool supported = d->ready && sdrFormat(info->imageFormat) && info->imageArrayLayers == 1 &&
                     info->imageColorSpace == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR &&
                     (info->flags & VK_SWAPCHAIN_CREATE_PROTECTED_BIT_KHR) == 0;
    if (supported && !(modified.imageUsage & VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT)) {
        VkSurfaceCapabilitiesKHR capabilities{};
        supported = d->instance->GetPhysicalDeviceSurfaceCapabilitiesKHR(d->physicalDevice, info->surface, &capabilities) == VK_SUCCESS &&
                    (capabilities.supportedUsageFlags & VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT) != 0;
        if (supported) modified.imageUsage |= VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
    }
    VkResult result = d->f.CreateSwapchainKHR(device, &modified, allocator, out);
    if (result != VK_SUCCESS && modified.imageUsage != info->imageUsage) {
        supported = false;
        result = d->f.CreateSwapchainKHR(device, info, allocator, out);
    }
    if (result != VK_SUCCESS) return result;
    try {
        auto swap = std::make_unique<SwapchainState>();
        swap->swapchain = *out;
        swap->format = info->imageFormat;
        swap->extent = info->imageExtent;
        swap->supported = supported;
        uint32_t count = 0;
        check(d->f.GetSwapchainImagesKHR(device, *out, &count, nullptr), "Querying swapchain HUD images failed");
        std::vector<VkImage> images(count);
        check(d->f.GetSwapchainImagesKHR(device, *out, &count, images.data()), "Reading swapchain HUD images failed");
        swap->frames.resize(count);
        for (uint32_t i = 0; i < count; ++i) swap->frames[i].image = images[i];
        d->swaps.emplace(raw(*out), std::move(swap));
        return VK_SUCCESS;
    } catch (const VulkanFailure &failure) {
        d->f.DestroySwapchainKHR(device, *out, allocator);
        *out = VK_NULL_HANDLE;
        return failure.result;
    } catch (...) {
        d->f.DestroySwapchainKHR(device, *out, allocator);
        *out = VK_NULL_HANDLE;
        error("Not enough memory to track a swapchain");
        return VK_ERROR_OUT_OF_HOST_MEMORY;
    }
}

void VKAPI_CALL destroySwapchain(VkDevice device, VkSwapchainKHR swapchain, const VkAllocationCallbacks *allocator) {
    auto d = findDevice(raw(device));
    if (!d || !d->f.DestroySwapchainKHR) return;
    std::lock_guard<std::mutex> lock(d->mutex);
    auto found = d->swaps.find(raw(swapchain));
    if (found != d->swaps.end()) {
        destroySwapResources(*d, *found->second);
        d->swaps.erase(found);
    }
    d->f.DestroySwapchainKHR(device, swapchain, allocator);
}

void imageAcquired(DeviceState &d, VkSwapchainKHR swapchain, uint32_t index) {
    std::lock_guard<std::mutex> lock(d.mutex);
    auto found = d.swaps.find(raw(swapchain));
    if (found != d.swaps.end() && index < found->second->frames.size())
        ++found->second->frames[index].acquireSerial;
}

VkResult VKAPI_CALL acquireNextImage(VkDevice device, VkSwapchainKHR swapchain, uint64_t timeout,
                                     VkSemaphore semaphore, VkFence fence, uint32_t *index) {
    auto d = findDevice(raw(device));
    if (!d || !d->f.AcquireNextImageKHR) return VK_ERROR_INITIALIZATION_FAILED;
    VkResult result = d->f.AcquireNextImageKHR(device, swapchain, timeout, semaphore, fence, index);
    if (result == VK_SUCCESS || result == VK_SUBOPTIMAL_KHR) imageAcquired(*d, swapchain, *index);
    return result;
}

VkResult VKAPI_CALL acquireNextImage2(VkDevice device, const VkAcquireNextImageInfoKHR *info, uint32_t *index) {
    auto d = findDevice(raw(device));
    if (!d || !d->f.AcquireNextImage2KHR) return VK_ERROR_INITIALIZATION_FAILED;
    VkResult result = d->f.AcquireNextImage2KHR(device, info, index);
    if (result == VK_SUCCESS || result == VK_SUBOPTIMAL_KHR) imageAcquired(*d, info->swapchain, *index);
    return result;
}

VkResult VKAPI_CALL queuePresent(VkQueue queue, const VkPresentInfoKHR *info) {
    auto d = findQueueDevice(queue);
    if (!d || !d->f.QueuePresentKHR) return VK_ERROR_INITIALIZATION_FAILED;
    std::lock_guard<std::mutex> lock(d->mutex);
    if (!d->ready) return d->f.QueuePresentKHR(queue, info);
    struct Pending { SwapchainState *swap; FrameSlot *frame; ObsVkHudFrame hud; };
    std::vector<Pending> pending;
    std::vector<VkCommandBuffer> commands;
    std::vector<VkSemaphore> ready;
    std::shared_ptr<Submission> submission;
    try {
        pending.reserve(info->swapchainCount);
        commands.reserve(info->swapchainCount);
        ready.reserve(info->swapchainCount);
        for (uint32_t i = 0; i < info->swapchainCount; ++i) {
            auto found = d->swaps.find(raw(info->pSwapchains[i]));
            if (found == d->swaps.end()) continue;
            auto &swap = *found->second;
            const auto hud = swap.published;
            swap.published = {}; // Each publication is consumed at most once.
            if (!hud.image) continue;
            if (hud.queue != raw(queue) || info->pImageIndices[i] >= swap.frames.size()) {
                error("The HUD and presentation must use the same graphics VkQueue and a valid swapchain image");
                throw VulkanFailure{VK_ERROR_INITIALIZATION_FAILED};
            }
            auto &frame = swap.frames[info->pImageIndices[i]];
            if (frame.acquireSerial == frame.presentedSerial && !frame.semaphoreAbandoned) {
                error("A HUD presentation image was reused without reacquiring it");
                throw VulkanFailure{VK_ERROR_INITIALIZATION_FAILED};
            }
            // Incremental-present rectangles from another layer would also
            // need expansion to cover the private HUD. Minecraft uses none.
            for (auto *extension = reinterpret_cast<const VkBaseInStructure *>(info->pNext);
                 extension; extension = extension->pNext) {
                if (extension->sType == VK_STRUCTURE_TYPE_PRESENT_REGIONS_KHR) {
                    error("Incremental presentation regions are not supported by the private HUD compositor");
                    throw VulkanFailure{VK_ERROR_FEATURE_NOT_PRESENT};
                }
            }
            recordHud(*d, swap, frame, hud);
            pending.push_back(Pending{&swap, &frame, hud});
            commands.push_back(frame.commands);
            ready.push_back(frame.presentReady);
        }
        if (pending.empty()) return d->f.QueuePresentKHR(queue, info);
        submission = reserveSubmission(*d);
        std::vector<VkPipelineStageFlags> waitStages(info->waitSemaphoreCount, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT);
        VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO};
        // Consume exactly the incoming waits once. OBS currently forwards
        // them untouched; other layers may have substituted their own.
        submit.waitSemaphoreCount = info->waitSemaphoreCount;
        submit.pWaitSemaphores = info->pWaitSemaphores;
        submit.pWaitDstStageMask = waitStages.data();
        submit.commandBufferCount = static_cast<uint32_t>(commands.size());
        submit.pCommandBuffers = commands.data();
        submit.signalSemaphoreCount = static_cast<uint32_t>(ready.size());
        submit.pSignalSemaphores = ready.data();
        VkResult result = d->f.QueueSubmit(queue, 1, &submit, submission->fence);
        if (result != VK_SUCCESS) {
            d->ready = false;
            error("Submitting the private HUD failed", result);
            // Do not retry a partially failed queue operation or risk waiting
            // twice on an incoming binary semaphore.
            return result;
        }
        submission->pending = true;
        for (auto &item : pending) {
            item.frame->submission = submission;
            item.frame->hudImage = item.hud.image;
            item.frame->presentedSerial = item.frame->acquireSerial;
            item.swap->usedQueue = queue;
        }
        VkPresentInfoKHR present = *info;
        present.waitSemaphoreCount = static_cast<uint32_t>(ready.size());
        present.pWaitSemaphores = ready.data();
        result = d->f.QueuePresentKHR(queue, &present);
        if (result == VK_ERROR_OUT_OF_HOST_MEMORY || result == VK_ERROR_OUT_OF_DEVICE_MEMORY) {
            for (auto &item : pending) item.frame->semaphoreAbandoned = true;
            d->ready = false;
            error("The private HUD present could not be enqueued", result);
        } else if (result == VK_ERROR_DEVICE_LOST) {
            d->ready = false;
            error("The Vulkan device was lost during HUD presentation", result);
        }
        if (!d->announced && (result == VK_SUCCESS || result == VK_SUBOPTIMAL_KHR)) {
            d->announced = true;
            std::fprintf(stderr, "[obs-overlay-vulkan] Transparent HUD compositing active; same-queue LOAD, GENERAL sampling, per-image presentation semaphores\n");
        }
        return result;
    } catch (const VulkanFailure &) {
        d->ready = false;
    } catch (const std::bad_alloc &) {
        d->ready = false;
        error("Not enough memory to prepare HUD presentation");
    } catch (...) {
        d->ready = false;
        error("Preparing HUD presentation failed");
    }
    // No native submission has occurred on the exception paths above. Keep
    // ordinary presentation working; the next Java publish observes ready=0
    // and restores the normal Minecraft GUI rendering path.
    return d->f.QueuePresentKHR(queue, info);
}

VkResult VKAPI_CALL enumerateLayers(uint32_t *count, VkLayerProperties *properties) {
    if (!properties) { *count = 1; return VK_SUCCESS; }
    if (*count == 0) return VK_INCOMPLETE;
    VkLayerProperties layer{};
    std::snprintf(layer.layerName, sizeof(layer.layerName), "%s", OBSVK_LAYER_NAME);
    std::snprintf(layer.description, sizeof(layer.description), "OBS Overlay private HUD compositor");
    layer.specVersion = VK_API_VERSION_1_3;
    layer.implementationVersion = OBSVK_ABI_VERSION;
    properties[0] = layer;
    *count = 1;
    return VK_SUCCESS;
}

VkResult VKAPI_CALL enumerateExtensions(const char *name, uint32_t *count, VkExtensionProperties *) {
    if (!name || std::strcmp(name, OBSVK_LAYER_NAME) != 0) return VK_ERROR_LAYER_NOT_PRESENT;
    *count = 0;
    return VK_SUCCESS;
}
} // namespace

extern "C" OBSVK_API PFN_vkVoidFunction VKAPI_CALL obsvkGetInstanceProcAddr(VkInstance instance, const char *name);
extern "C" OBSVK_API PFN_vkVoidFunction VKAPI_CALL obsvkGetDeviceProcAddr(VkDevice device, const char *name);

namespace {
PFN_vkVoidFunction deviceHook(const char *name) noexcept {
#define HOOK(api, function) if (std::strcmp(name, "vk" #api) == 0) return reinterpret_cast<PFN_vkVoidFunction>(function);
    HOOK(GetDeviceProcAddr, obsvkGetDeviceProcAddr)
    HOOK(DestroyDevice, destroyDevice)
    HOOK(GetDeviceQueue, getDeviceQueue)
    HOOK(GetDeviceQueue2, getDeviceQueue2)
    HOOK(CreateSwapchainKHR, createSwapchain)
    HOOK(DestroySwapchainKHR, destroySwapchain)
    HOOK(AcquireNextImageKHR, acquireNextImage)
    HOOK(AcquireNextImage2KHR, acquireNextImage2)
    HOOK(QueuePresentKHR, queuePresent)
#undef HOOK
    return nullptr;
}

PFN_vkVoidFunction VKAPI_CALL getPhysicalDeviceProcAddr(VkInstance instance, const char *name) {
    auto state = findInstance(instance);
    if (!state || !name) return nullptr;
    return state->GetPhysicalDeviceProcAddr ? state->GetPhysicalDeviceProcAddr(instance, name) :
           state->GetInstanceProcAddr(instance, name);
}
}

extern "C" OBSVK_API PFN_vkVoidFunction VKAPI_CALL obsvkGetInstanceProcAddr(VkInstance instance, const char *name) {
    if (!name) return nullptr;
#define HOOK(api, function) if (std::strcmp(name, "vk" #api) == 0) return reinterpret_cast<PFN_vkVoidFunction>(function);
    HOOK(GetInstanceProcAddr, obsvkGetInstanceProcAddr)
    HOOK(GetDeviceProcAddr, obsvkGetDeviceProcAddr)
    HOOK(CreateInstance, createInstance)
    HOOK(DestroyInstance, destroyInstance)
    HOOK(CreateDevice, createDevice)
    HOOK(EnumerateInstanceLayerProperties, enumerateLayers)
    HOOK(EnumerateInstanceExtensionProperties, enumerateExtensions)
#undef HOOK
    if (!instance) return nullptr;
    if (auto hook = deviceHook(name)) return hook;
    auto state = findInstance(instance);
    return state ? state->GetInstanceProcAddr(instance, name) : nullptr;
}

extern "C" OBSVK_API PFN_vkVoidFunction VKAPI_CALL obsvkGetDeviceProcAddr(VkDevice device, const char *name) {
    if (!device || !name) return nullptr;
    auto d = findDevice(raw(device));
    if (!d) return nullptr;
    auto next = d->f.GetDeviceProcAddr(device, name);
    if (!next) return nullptr;
    if (auto hook = deviceHook(name)) return hook;
    return next;
}

extern "C" OBSVK_API VkResult VKAPI_CALL obsvkNegotiateLoaderLayerInterfaceVersion(VkNegotiateLayerInterface *info) {
    if (!info || info->sType != LAYER_NEGOTIATE_INTERFACE_STRUCT || info->loaderLayerInterfaceVersion < 2)
        return VK_ERROR_INITIALIZATION_FAILED;
    info->loaderLayerInterfaceVersion = 2;
    info->pfnGetInstanceProcAddr = obsvkGetInstanceProcAddr;
    info->pfnGetDeviceProcAddr = obsvkGetDeviceProcAddr;
    info->pfnGetPhysicalDeviceProcAddr = getPhysicalDeviceProcAddr;
    return VK_SUCCESS;
}

extern "C" OBSVK_API uint32_t obsvkGetAbiVersion(void) { return OBSVK_ABI_VERSION; }

extern "C" OBSVK_API int32_t obsvkIsDeviceReady(uint64_t device) {
    try {
        auto d = findDevice(device);
        if (!d) return 0;
        std::lock_guard<std::mutex> lock(d->mutex);
        return d->ready ? 1 : 0;
    } catch (...) { return 0; }
}

extern "C" OBSVK_API int32_t obsvkPublishHud(const ObsVkHudFrame *hud) {
    if (!hud || hud->struct_size < sizeof(ObsVkHudFrame) || hud->abi_version != OBSVK_ABI_VERSION || hud->reserved != 0) {
        error("The HUD frame ABI is invalid");
        return OBSVK_ERROR_ARGUMENT;
    }
    try {
        auto d = findDevice(hud->device);
        if (!d) { error("The explicit Vulkan layer does not track this device"); return OBSVK_ERROR_NOT_READY; }
        std::lock_guard<std::mutex> lock(d->mutex);
        if (!d->ready) return OBSVK_ERROR_NOT_READY; // Keep the original failure message.
        if (!hud->image) {
            if (!hud->swapchain) for (auto &entry : d->swaps) entry.second->published = {};
            else {
                auto found = d->swaps.find(hud->swapchain);
                if (found != d->swaps.end()) found->second->published = {};
            }
            return OBSVK_SUCCESS;
        }
        auto found = d->swaps.find(hud->swapchain);
        if (found == d->swaps.end()) { error("The HUD swapchain is not tracked by the Vulkan layer"); return OBSVK_ERROR_NOT_READY; }
        auto &swap = *found->second;
        swap.published = {};
        if (!swap.supported) { error("The HUD requires an SDR swapchain with COLOR_ATTACHMENT support"); return OBSVK_ERROR_UNSUPPORTED; }
        auto queue = d->queues.find(hud->queue);
        if (queue == d->queues.end() || !(queue->second.flags & VK_QUEUE_GRAPHICS_BIT) || queue->second.protectedQueue ||
            (swap.initialized && swap.family != queue->second.family) ||
            (swap.usedQueue && raw(swap.usedQueue) != hud->queue)) {
            error("The HUD requires one unprotected graphics/present queue per swapchain");
            return OBSVK_ERROR_UNSUPPORTED;
        }
        if (!hud->width || !hud->height || !sdrFormat(static_cast<VkFormat>(hud->format)) ||
            (hud->layout != VK_IMAGE_LAYOUT_GENERAL && hud->layout != VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL) ||
            (hud->flags & ~OBSVK_HUD_PREMULTIPLIED_ALPHA)) {
            error("Unsupported HUD dimensions, format, layout, or flags");
            return OBSVK_ERROR_ARGUMENT;
        }
        VkFormatProperties properties{};
        d->instance->GetPhysicalDeviceFormatProperties(d->physicalDevice, static_cast<VkFormat>(hud->format), &properties);
        if (!(properties.optimalTilingFeatures & VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT)) {
            error("The HUD format cannot be sampled on this Vulkan device");
            return OBSVK_ERROR_UNSUPPORTED;
        }
        ensureSwapResources(*d, swap, queue->second.family);
        ensureHudView(*d, swap, *hud);
        swap.published = *hud;
        return OBSVK_SUCCESS;
    } catch (const VulkanFailure &) { return OBSVK_ERROR_VULKAN; }
    catch (...) { error("Not enough memory to publish the HUD"); return OBSVK_ERROR_OUT_OF_MEMORY; }
}

extern "C" OBSVK_API int32_t obsvkWaitHud(uint64_t device, uint64_t image) {
    try {
        auto d = findDevice(device);
        if (!d) { error("Waiting for HUD sampling on an unknown Vulkan device"); return OBSVK_ERROR_NOT_READY; }
        std::lock_guard<std::mutex> lock(d->mutex);
        for (auto &entry : d->swaps) for (auto &frame : entry.second->frames)
            if (frame.hudImage == image) waitSlot(*d, frame);
        return OBSVK_SUCCESS;
    } catch (const VulkanFailure &) { return OBSVK_ERROR_VULKAN; }
    catch (...) { error("Waiting for HUD sampling failed"); return OBSVK_ERROR_VULKAN; }
}

extern "C" OBSVK_API int32_t obsvkClearHud(uint64_t device, uint64_t swapchain) {
    try {
        auto d = findDevice(device);
        if (!d) { error("Clearing a HUD on an unknown Vulkan device"); return OBSVK_ERROR_NOT_READY; }
        std::lock_guard<std::mutex> lock(d->mutex);
        if (!swapchain) for (auto &entry : d->swaps) clearHud(*d, *entry.second);
        else {
            auto found = d->swaps.find(swapchain);
            if (found != d->swaps.end()) clearHud(*d, *found->second);
        }
        return OBSVK_SUCCESS;
    } catch (const VulkanFailure &) { return OBSVK_ERROR_VULKAN; }
    catch (...) { error("Clearing the HUD failed"); return OBSVK_ERROR_VULKAN; }
}

extern "C" OBSVK_API uint32_t obsvkGetLastError(char *destination, uint32_t capacity) {
    std::lock_guard<std::mutex> lock(errorMutex);
    const auto needed = static_cast<uint32_t>(std::strlen(lastError.data()) + 1);
    if (destination && capacity) {
        const size_t length = std::min<size_t>(needed - 1, capacity - 1);
        std::memcpy(destination, lastError.data(), length);
        destination[length] = '\0';
    }
    return needed;
}
