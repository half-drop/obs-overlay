<img src="fabric/src/main/resources/assets/obs_overlay/icon.png" alt="Logo" height="128" />

# OBS Overlay
#### Hide things from OBS stream by making them an overlay.
**[26.3 builds &nearr;](https://github.com/half-drop/obs-overlay/actions?query=branch%3A26.3)** • **[Releases &nearr;](https://github.com/half-drop/obs-overlay/releases)** • **[Upstream Modrinth &nearr;](https://modrinth.com/mod/obs-overlay)** • **[Upstream CurseForge &nearr;](https://www.curseforge.com/minecraft/mc-mods/obs-overlay)**
<br><br>
<br/>

![Preview](.github/preview.png)

## Installation

This mod is **client-only**.\
Version **2.2.0** targets **Minecraft 26.3**, using **Java 25** on **Windows with OpenGL**.

Download the `obs-overlay-26.3-2.2.0` artifact from a successful [26.3 GitHub Actions build](https://github.com/half-drop/obs-overlay/actions?query=branch%3A26.3), extract the JAR for your loader, and place it in your client's `mods` folder together with the matching dependencies below. Tagged versions are also published through [GitHub releases](https://github.com/half-drop/obs-overlay/releases).

If you have selected Vulkan, change **Video Settings → Graphics API → Prefer OpenGL** and restart Minecraft. The overlay uses a Windows OpenGL buffer-swap hook; Vulkan rendering is currently unsupported.

### Dependencies

| Loader | Required dependencies | Optional |
| --- | --- | --- |
| Fabric Loader 0.19.5+ | Fabric API 0.161.0+26.3; Cloth Config 26.3.159+ | Mod Menu 21.0.0 |
| NeoForge 26.3.0.48-beta+ | Cloth Config 26.3.159+ for NeoForge | — |

Use dependency releases that explicitly support Minecraft 26.3.

## Features

> [!NOTE]
> Modern Minecraft uses deferred world and GUI rendering. Version 2.2.0 supports the HUD elements and screens listed below on Minecraft 26.3. In-world element and name-tag hiding remain unavailable and are excluded from the settings.

This mod lets you hide any combination of the following components:

### In-world elements (available up to Minecraft 1.21.4)

- Name tags
- Text on signs
- Maps
- Chests
- Banner patterns
- Beacon

### HUD elements

- Debug menu (F3)
- Chat (excluding input bar)
- Chat input bar
- Player list (TAB)
- Subtitles (accessibility)
- Scoreboard
- Title and Subtitle from /title
- Actionbar from /title
- Effect display
- Main HUD (health, hunger, armor, experience, hotbar)

Additionally this mod lets you hide any combination of the following *screens*:

- Survival inventory
- Creative inventory
- Pause menu
- Command block screen
- Any HandledScreen by it's ID (e.g. `minecraft:anvil`, `minecraft:furnace`)
- All ingame screens

By default only Debug menu (F3) is hidden.

> [!WARNING]
> There is a possibility OBS might capture the HUD if started before game. If you hide any confidential information, make sure to double-check that it's not visible in the stream.<br><br>
> For additional safety you can enable test icon, which is displayed all the time and should not be visible on stream. It should let you check if the mod is working correctly.<br><br>
> **I am not responsible for any confidential information accidentally shown on stream.**

## Usage

Mod settings can be accessed by:
- Clicking `Settings` -> `Video Settings` -> `OBS Overlay settings`
- Opening mod settings through ModMenu (Fabric only)
- Opening mod settings through the Mods menu (NeoForge only)

![Settings](.github/settings.png)

## API

If you want to add support for your own HUD components you can use API provided by this mod.\
Check [API documentation](API.md) for more information.

## Building

Use Java 25 and the included Gradle wrapper:

```sh
./gradlew :fabric:build :neoforge:build
```

The installable JARs are `fabric/build/libs/obs_overlay-fabric-2.2.0.jar` and `neoforge/build/libs/obs_overlay-neoforge-2.2.0.jar`. Files ending in `-sources.jar` contain source code for development.

## License

[MIT](LICENSE)
