# Changelog

## 2.0.0 — Minecraft 1.21.11

- Added Minecraft 1.21.11 support for Fabric and NeoForge.
- Migrated HUD and screen isolation to Minecraft's deferred GUI render-state system.
- Migrated the overlay compositor and framebuffer management to the 1.21.11 GPU API.
- Updated Fabric Loader, Fabric API, NeoForge, Architectury API, Cloth Config, Mod Menu, Yarn, and Loom.
- Removed the obsolete compile-time dependency on ImmediatelyFast while retaining runtime compatibility.
- Fixed red-tinted GUI rendering and frame-to-frame flicker by isolating and restoring OpenGL state around overlay compositing.
- Temporarily disabled in-world element hiding because Minecraft 1.21.11 now defers those elements through a separate render-command queue. This release intentionally does not expose non-functional privacy toggles.
