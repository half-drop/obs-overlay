# API Documentation

## Installation

The Minecraft 26.3 Vulkan beta is available in the artifacts of successful [GitHub Actions builds](https://github.com/half-drop/obs-overlay/actions?query=branch%3Afeat%2Fvulkan-26.3). Download the JAR for your loader and add it as a compile dependency. For example, with Fabric Loom 1.17:

```gradle
dependencies {
    compileOnly files('libs/obs_overlay-fabric-2.3.0-beta.1-26.3.jar')
}
```

Install that JAR and its runtime dependencies in the client as well. The upstream Modrinth project has a separate release history from this fork.

## Usage

Use `me.zziger.obsoverlay.OBSOverlay.getAPI()` and `me.zziger.obsoverlay.component.OverlayComponentRegistry`. Retrieve the API for each draw scope; it returns a no-op implementation if overlay initialization has failed.
### Drawing to an overlay

In order to draw your own HUD components to the overlay, you need to wrap your rendering code with following methods:

```java
var api = OBSOverlay.getAPI();
api.beginDraw(component);
try {
    // Submit this component's elements with the current GuiGraphicsExtractor.
} finally {
    api.endDraw(component);
}
```

Here `component` is a registered `HUDOverlayComponent` or another `IOverlayComponent` using the `NORMAL` framebuffer type. Minecraft 26.3 extracts GUI render states before drawing them. The component scope marks those states for the overlay pass, including strata created inside the scope and nested components. The active graphics backend composites the resulting transparent HUD locally: at the Windows OpenGL buffer swap, or in the Vulkan layer before presentation. Submit your GUI elements during extraction; wrapping an arbitrary later GPU command does not provide this isolation.

In-world element hiding is unavailable in this version. The low-level framebuffer overloads are retained for existing integrations, but the component-based extraction API is the supported path for GUI elements.

On Vulkan, the low-level `NORMAL` scope also marks extracted GUI strata. `DEPTH` scopes and `backupDepth` have no effect. Do not cast the renderer's textures to OpenGL classes. Do not retain a framebuffer or native image handle across frames or resizes: the Vulkan backend rotates HUD textures and owns their lifetime. Retrieve `OBSOverlay.getAPI()` for each scope so a backend failure can safely disable the integration.

### Adding your own HUD components to settings

You can extend list of components that are displayed in mod settings.\
To achieve that you can use `OverlayComponentRegistry.registerComponents` method.

Creating an instance of `HUDOverlayComponent` stores the component state in OBS Overlay config. To use your own config, implement `IOverlayComponent` or extend `DefaultOverlayComponent`.\
Pass your component to `beginDraw`/`endDraw` to apply its overlay and automatic hiding settings.\

You will also need to define `obs_overlay.component.your_component_id` and `obs_overlay.component.your_component_id.tooltip` localization keys.

For example check [ChatHudMixin](common/src/main/java/me/zziger/obsoverlay/mixin/components/ChatHudMixin.java) and [DefaultOverlayComponent](common/src/main/java/me/zziger/obsoverlay/component/type/DefaultOverlayComponent.java).

### Adding your own overlayable screens to settings

You can extend list of screens that are displayed in mod settings.\
To achieve that you can call `OverlayComponentRegistry.addHideableScreen` method.

Screen's state will be stored in OBS Overlay config.

### Adding your own non-fullscreen screens

By default, this mod automatically hides most components when any screen except for Chat is opened.\
You can add your own Screen that will also be excluded from this check by calling `OverlayComponentRegistry.addIgnoredScreen` method.
