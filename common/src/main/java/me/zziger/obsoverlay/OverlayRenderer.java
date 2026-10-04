package me.zziger.obsoverlay;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import me.zziger.obsoverlay.component.IOverlayComponent;
import me.zziger.obsoverlay.error.OverlayHookException;
import net.minecraft.client.Minecraft;

import java.io.Closeable;

/** Shared HUD policy and lifecycle; display operations belong to a graphics backend. */
public abstract class OverlayRenderer implements Closeable {
    public boolean renderingHands;

    public static OverlayRenderer create() {
        return switch (RenderSystem.getDevice().getDeviceInfo().backendName()) {
            case "OpenGL" -> new OpenGlOverlayRenderer();
            case "Vulkan" -> new VulkanOverlayRenderer();
            default -> throw new OverlayHookException("Unsupported graphics backend for OBS Overlay");
        };
    }

    public abstract RenderTarget getFramebuffer(OverlayFramebufferType type);
    public abstract void beginFrame();
    public abstract void markDirty(OverlayFramebufferType type);
    public abstract void onResolutionChanged(Minecraft client);
    public abstract void beginDraw(OverlayFramebufferType type);
    public abstract void beginEmptyDraw();
    public abstract void endDraw();
    @Override public abstract void close();

    /** Called immediately before Minecraft submits its recorded GPU commands. */
    public void finishFrame() {}

    public boolean isFramebufferOverridden() { return false; }
    public void backupDepth(boolean overrideDepth) {}
    protected boolean supportsType(OverlayFramebufferType type) { return true; }

    public final void beginDraw(IOverlayComponent component) {
        if (!component.isOverlayEnabled() || !supportsType(component.getFramebufferType())) return;
        component.beforeBeginDraw();
        if (component.getFramebufferType() == OverlayFramebufferType.NORMAL) {
            GuiOverlayManager.begin(component.isHidden());
        } else if (component.isHidden()) {
            beginEmptyDraw();
        } else {
            beginDraw(component.getFramebufferType());
        }
    }

    public final void endDraw(IOverlayComponent component) {
        if (!component.isOverlayEnabled() || !supportsType(component.getFramebufferType())) return;
        component.beforeEndDraw();
        if (component.getFramebufferType() == OverlayFramebufferType.NORMAL) GuiOverlayManager.end();
        else endDraw();
    }
}
