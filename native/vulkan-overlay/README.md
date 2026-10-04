# Native Vulkan HUD layer

This is an original MIT-licensed explicit Vulkan layer. It draws a transparent
HUD over the current swapchain contents using a `LOAD` render pass. Its loader
interface and compositor are implemented within this project.

## Build and ABI

Configure with CMake 3.21 or later, a C++17 compiler, a pinned Vulkan-Headers
include directory, and the pinned `glslang` executable:

```sh
cmake -S native/vulkan-overlay -B build/native-vulkan \
  -DVULKAN_HEADERS_DIR=/path/to/Vulkan-Headers/include \
  -DGLSLANG_VALIDATOR=/path/to/glslang \
  -DCMAKE_BUILD_TYPE=Release
cmake --build build/native-vulkan --config Release
```

Outputs are `obs_overlay_vulkan.dll` on Windows x64 and
`libobs_overlay_vulkan.so` on Linux x64. The generated manifest declares
`VK_LAYER_HALFDROP_obs_overlay`, with the negotiation entry point
`obsvkNegotiateLoaderLayerInterfaceVersion`. The Java bridge must load the same
physical library file that the Vulkan loader uses, so both share device state.

`include/obs_overlay_vulkan.h` is the ABI source of truth. `ObsVkHudFrame` is
72 bytes, with its `frame_id` at offset 64. The bridge publishes one transparent
HUD image per presentation and retains ownership of that image. A zero image
revokes a publication without waiting. `obsvkWaitHud` waits only for native
submissions reading a particular HUD image, and succeeds for an unused slot.
Call it before reusing each of the three Java HUD slots. `obsvkClearHud` waits
for HUD reads and releases source image views before resize or texture
destruction; clearing an already retired swapchain succeeds.

The layer samples `GENERAL` or `SHADER_READ_ONLY_OPTIMAL` source images without
changing their layout. The source must have sampled-image usage. The shader
reverses Y to match Minecraft 26.3 RenderPearl's ordinary presentation blit.
Bit 0 in `flags` selects premultiplied alpha; zero selects straight alpha.

## Ordering and synchronization

The layer is enabled explicitly by the application. In the normal Vulkan
loader order this places it below OBS's implicit layer. The supported path
requires the game graphics work, capture, and presentation to use the exact
same `VkQueue` handle.

When a HUD is published, this layer records its own command buffer after the
upstream capture submission. Image barriers order prior accesses, including
the capture's transfer read, before the color attachment `LOAD` and blend.
The incoming present semaphores are waited exactly once by the new submission.
The next layer's present instead waits for private binary semaphores indexed
by swapchain image. It preserves the incoming present structure's other data.
Command buffers, descriptors, and source images have submission-fence lifetime
tracking independent of the present semaphores. A new acquisition is required
before a swapchain image's presentation semaphore can be used again.

The compositor preserves pixels drawn by upstream Steam/NVIDIA or other
layers. Further downstream layers can still alter the result. No behavior of
other layers or future OBS implementations is inferred from function names.

## Current compatibility limits

- Windows x64 is the release target; the Linux build supports software-Vulkan
  integration testing with the same compositor code.
- The initial path supports single-layer, unprotected SDR RGBA8/BGRA8
  swapchains in `SRGB_NONLINEAR` color space and a graphics-capable present
  queue. It negotiates `COLOR_ATTACHMENT` usage. Unsupported surfaces fall
  back to their original swapchain creation parameters.
- Incremental-present dirty rectangles from other layers are rejected when
  drawing a HUD, since they would need expansion to include the new pixels.
  Minecraft 26.3 itself passes no present `pNext` chain.
- A native presentation failure disables the compositor for that device. The
  next Java publish/readiness check reports the failure so the mod can restore
  ordinary GUI rendering. Messages are also written to standard error.
- **Swapchain retirement uses the legacy `vkQueueWaitIdle` teardown fallback.**
  This completes native GPU reads but does not, by itself, provide a strict
  Vulkan guarantee for destroying resources still referenced by presentation.
  Normal per-image semaphore reuse is synchronized by reacquisition; the
  remaining limitation concerns retirement/teardown. A future path using
  `VK_KHR_swapchain_maintenance1` or `VK_EXT_swapchain_maintenance1` presentation
  fences is needed for that stronger guarantee. Minecraft 26.3 does not enable
  these extensions, and this implementation does not silently enable them.

Relevant primary specifications:

- [Vulkan loader ordering](https://github.com/KhronosGroup/Vulkan-Loader/blob/main/docs/LoaderApplicationInterface.md#overall-layer-ordering)
- [Pipeline barrier scopes](https://docs.vulkan.org/refpages/latest/refpages/source/vkCmdPipelineBarrier.html)
- [Presentation semaphore reuse and teardown](https://docs.vulkan.org/guide/latest/swapchain_semaphore_reuse.html)
- [Presentation fences](https://docs.vulkan.org/refpages/latest/refpages/source/VkSwapchainPresentFenceInfoKHR.html)
