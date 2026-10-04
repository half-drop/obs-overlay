# Vulkan HUD backend

The `feat/vulkan-26.3` branch adds an experimental Vulkan backend in **2.3.0-beta.1** for **Minecraft 26.3 / Java 25**. The intended capture platform is **Windows x86_64 with OBS Game Capture**. The existing OpenGL implementation remains a separate backend. HUD and screen selection use the same settings and extraction API on both backends.

## Capture and display order

Minecraft draws the world and public GUI into its normal render target. Private GUI strata are drawn into a transparent HUD texture. The original game frame reaches the Vulkan swapchain without those private strata.

The mod enables `VK_LAYER_HALFDROP_obs_overlay` as an application explicit layer before Vulkan instance creation. With the normal Vulkan loader ordering, OBS's implicit capture layer runs before this explicit layer. OBS can copy the clean swapchain image; the mod then loads the existing image, blends the private HUD, and forwards presentation. The compositor uses the actual queue, swapchain, image index, and wait semaphores passed to `vkQueuePresentKHR`.

This arrangement depends on the active layer chain. A changed layer order or a different capture method can capture the final image. The mod does not claim to control Display Capture, Window Capture, or third-party recording hooks. Use OBS Game Capture and verify the test icon in an actual recording after changing the graphics or capture configuration.

Implementation references:

- [OBS 32.2.2 Vulkan capture source](https://github.com/obsproject/obs-studio/blob/ba2f32bdf791005443988a4955e963663e16b1ed/plugins/win-capture/graphics-hook/vulkan-capture.c): `OBS_QueuePresentKHR`, capture copy submission, and swapchain image transitions.
- [Vulkan loader application interface](https://github.com/KhronosGroup/Vulkan-Loader/blob/main/docs/LoaderApplicationInterface.md): explicit and implicit layer discovery and ordering.
- [Vulkan layer interface](https://github.com/KhronosGroup/Vulkan-Loader/blob/main/docs/LoaderLayerInterface.md): instance/device dispatch and loader negotiation.
- [Swapchain semaphore reuse](https://docs.vulkan.org/guide/latest/swapchain_semaphore_reuse.html): presentation semaphore lifetime.
- [vkQueuePresentKHR](https://docs.vulkan.org/refpages/latest/refpages/source/vkQueuePresentKHR.html) and [vkCmdPipelineBarrier](https://docs.vulkan.org/refpages/latest/refpages/source/vkCmdPipelineBarrier.html): presentation waits and image dependencies.

The native layer is original MIT-licensed code. OBS source was used to investigate capture behavior; its implementation is not copied into this project.

## Resource and frame lifecycle

1. Before Vulkan initialization, the Java bootstrap extracts the packaged native library into a content-addressed directory, checks its digest and ABI, writes a process-local layer manifest, and extends the loader search path. Existing layer paths and requested validation layers are retained.
2. Once Minecraft's device exists, the bridge resolves its exact Vulkan backend and graphics queue. A device not tracked by the layer causes overlay initialization to fail with a visible error.
3. At the start of each rendered frame, the backend selects one of three RGBA8 / D32 HUD targets. It waits for native submissions still reading that image, then clears it to transparent black.
4. The GUI partition draws private strata into the selected target. Before Minecraft schedules its final surface blit and submits GPU work, Java publishes the borrowed image handle and current swapchain through the fixed 72-byte C ABI. Publishing does not submit GPU commands.
5. The native layer consumes the original present waits once, records a `LOAD` render pass, blends the HUD with premultiplied alpha, restores the image to present layout, and passes its own signal semaphore to the next present function. Each swapchain image owns its presentation semaphore. Sampling matches Minecraft's vertical flip; it adds no gamma conversion.
6. An empty HUD frame withdraws the publication without waiting on every outstanding submission. Resize and shutdown withdraw the publication and wait for native reads before Minecraft releases HUD textures. The library remains loaded while Vulkan may dispatch through it.

Minecraft's own GPU completion tracking does not cover the layer's additional image reads. The explicit native waits are required even though all production and composition work uses the same graphics queue.

## Supported scope

| Area | Current behavior |
| --- | --- |
| Minecraft / loaders | Minecraft 26.3; Fabric and NeoForge |
| Windows x86_64 | Packaged Vulkan DLL and existing OpenGL hook |
| Linux x86_64 | Packaged Vulkan library for opt-in developer validation |
| macOS / ARM | Vulkan layer unavailable; reports the unsupported platform |
| HUD, menus, tooltips | Shared GUI extraction and component policy |
| In-world elements / name tags | Unavailable in the 26.3 port |
| Private HUD composition | Transparent alpha pass preserving existing backbuffer pixels |
| `DEPTH` API on Vulkan | No effect |
| Nonstandard swapchain formats or queues | Native capability checks; no assumption that every Vulkan application is supported |

The three HUD targets use approximately `width × height × 24` bytes before driver overhead: about 47.5 MiB at 1920×1080 and 189.8 MiB at 3840×2160. Native framebuffers, synchronization objects, and shaders add a smaller amount. Hardware performance and external overlay compatibility require measurements on the target configuration.

## Validation

The development checks cover real Vulkan rendering on Linux with Mesa lavapipe and the Khronos validation layer, plus an actual Minecraft 26.3 Vulkan launch. A native probe compares an upstream clean GPU readback with a downstream readback of the presented HUD image. This checks the compositor, synchronization, orientation, and resource lifetime independently of Java extraction.

These checks do not establish Windows OBS interoperability or hardware performance. Before declaring the backend stable, verify actual recordings with OBS Game Capture on Windows across NVIDIA, AMD, and Intel drivers, including window resize, minimize/restore, fullscreen changes, capture reconnect, and any enabled third-party overlays. The implementation remains a beta while that platform validation is outstanding.

## Building

GitHub Actions builds the Windows x64 DLL with MSVC and the Linux x64 library with GCC, then packages both into the Fabric and NeoForge JARs. The tool download script pins Khronos headers and the shader compiler by version and SHA-256. The Windows build uses the static MSVC runtime.

For a local Linux native build, use Python 3.12+, CMake 3.21+, Ninja, and a C++17 compiler:

```sh
python3 .github/scripts/prepare_native_tools.py --platform linux-x86_64
cmake -S native/vulkan-overlay -B build/native-vulkan -G Ninja \
  -DCMAKE_BUILD_TYPE=Release \
  -DVULKAN_HEADERS_DIR="$PWD/build/native-tools/Vulkan-Headers/include" \
  -DGLSLANG_VALIDATOR="$PWD/build/native-tools/glslang/bin/glslang"
cmake --build build/native-vulkan
```

On Windows, fetch `--platform windows-x86_64`, point `GLSLANG_VALIDATOR` at `glslang.exe`, and configure with `-G "Visual Studio 17 2022" -A x64 -DCMAKE_MSVC_RUNTIME_LIBRARY=MultiThreaded`. Build with `cmake --build build/native-vulkan --config Release`.

Place the resulting files in this resource tree, or download and merge the two native CI artifacts:

| Native build | Required destination |
| --- | --- |
| Windows x64 DLL | `build/native-resources/assets/obs_overlay/native/windows-x86_64/obs_overlay_vulkan.dll` |
| Linux x64 library | `build/native-resources/assets/obs_overlay/native/linux-x86_64/libobs_overlay_vulkan.so` |

Then run:

```sh
./gradlew :fabric:build :neoforge:build
```

Use `-PvulkanNativeResourcesDir=/absolute/path/to/resource-tree` to select another resource directory. Packaging fails when either library is missing. CI additionally compares the packaged native bytes with the corresponding build artifact.

For Linux development launches, add `-Dobs_overlay.vulkan.linuxTest=true`. To use a locally rebuilt library, also set `-Dobs_overlay.vulkan.library=/absolute/path/to/libobs_overlay_vulkan.so`. These are Java system properties, supplied before the client starts. Normal Windows installations use the packaged DLL automatically.
