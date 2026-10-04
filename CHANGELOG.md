# Changelog

## 2.3.0-beta.1 — Vulkan HUD backend for Minecraft 26.3

- Added an original MIT-licensed Vulkan layer for Windows x86_64 and a Linux build for development validation.
- Prepared and enabled the explicit layer before Minecraft creates its Vulkan instance, preserving existing layer paths and validation layers.
- Split graphics operations into OpenGL and Vulkan backends while sharing the GUI component and screen policies.
- Rendered private HUD strata into three transparent textures and alpha-composited them into the presented swapchain image, preserving existing backbuffer contents.
- Bound the native publication to Minecraft's exact device, graphics queue, and active swapchain. Preserved Minecraft's present orientation and UNORM color values.
- Added native fence waits before HUD image reuse, resize, or teardown, and per-swapchain-image presentation semaphores.
- Withdrew the HUD publication on empty frames, and disabled overlay rendering with an error message if initialization or a bridge operation fails.
- Added Windows and Linux native CI builds, shader compilation, dependency checksum verification, and checks that both loader JARs contain the matching libraries.
- Kept in-world element hiding unavailable. Vulkan remains a beta pending Windows OBS Game Capture validation across hardware and third-party overlays; see `docs/VULKAN.md`.

## 2.2.0 — Minecraft 26.3

- Added Minecraft 26.3 support for Fabric and NeoForge with Java 25.
- Migrated OpenGL texture and framebuffer integration to RenderPearl and the explicit color/depth format API.
- Updated the main HUD hooks to cover the new combined hotbar and decorations extraction method.
- Kept HUD, screen, and tooltip strata under their enclosing overlay policy, including nested components and the chat input background.
- Preserved the normal GUI blur boundary after separating overlay layers, and reset the GUI state correctly for the next frame.
- Retained the Windows OpenGL swap hook with SDL, with explicit feedback when a different graphics backend is selected.
- Fixed native callback lifetime and error handling, framebuffer restoration, and cleanup after failed overlay initialization.
- Preserved per-target OpenGL blend and color-mask state used by the 26.3 multi-target rendering pipeline.
- Updated the dependencies and release workflow; release artifacts contain the two installable loader JARs.

## 2.1.0 — Minecraft 26.2

- Added Minecraft 26.2 support for Fabric and NeoForge with Java 25.
- Migrated from the legacy Yarn/Architectury build to Minecraft's official mappings and platform-native Fabric Loom / NeoForge ModDevGradle builds.
- Ported HUD and screen isolation to the 26.2 GUI extraction and render-state pipeline.
- Updated the overlay framebuffer bridge for the 26.2 GPU texture API.
- Prevented the NeoForge early loading window from using overlay resources from the wrong OpenGL context.
- Kept the complete OpenGL state restoration that prevents red-tinted frames and flickering.
- Updated Fabric Loader, Fabric API, NeoForge, Cloth Config, Mod Menu, Loom, Gradle, and the release workflow.

## 2.0.0 — Minecraft 1.21.11

- Added Minecraft 1.21.11 support for Fabric and NeoForge.
- Migrated HUD and screen isolation to Minecraft's deferred GUI render-state system.
- Migrated the overlay compositor and framebuffer management to the 1.21.11 GPU API.
- Updated Fabric Loader, Fabric API, NeoForge, Architectury API, Cloth Config, Mod Menu, Yarn, and Loom.
- Removed the obsolete compile-time dependency on ImmediatelyFast while retaining runtime compatibility.
- Fixed red-tinted GUI rendering and frame-to-frame flicker by isolating and restoring OpenGL state around overlay compositing.
- Temporarily disabled in-world element hiding because Minecraft 1.21.11 now defers those elements through a separate render-command queue. This release intentionally does not expose non-functional privacy toggles.
