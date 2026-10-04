# Changelog

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
