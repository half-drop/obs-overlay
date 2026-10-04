package me.zziger.obsoverlay;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import me.zziger.obsoverlay.vulkan.VulkanOverlayBridge;
import net.minecraft.client.Minecraft;
import org.joml.Vector4f;

import java.util.ArrayDeque;
import java.util.Deque;

/** Renders private HUD strata into transparent textures consumed by the Vulkan layer. */
final class VulkanOverlayRenderer extends OverlayRenderer {
    private static final int HUD_SLOTS = 3;
    private final OverlayFramebuffer[] hud = new OverlayFramebuffer[HUD_SLOTS];
    private final Deque<OverlayFramebufferType> drawScopes = new ArrayDeque<>();
    private final VulkanOverlayBridge bridge;
    private int slot = -1;
    private long frameId;
    private boolean closed;

    VulkanOverlayRenderer() {
        bridge = VulkanOverlayBridge.attach(RenderSystem.getDevice());
        try {
            Minecraft client = Minecraft.getInstance();
            int width = Math.max(1, client.getWindow().getWidth());
            int height = Math.max(1, client.getWindow().getHeight());
            for (int i = 0; i < hud.length; i++) {
                hud[i] = new OverlayFramebuffer(new TextureTarget("OBS Overlay Vulkan HUD " + i,
                        width, height, GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT));
            }
            OBSOverlay.LOGGER.info("OBS Overlay Vulkan HUD backend initialized ({} texture slots)", HUD_SLOTS);
        } catch (RuntimeException | Error e) {
            try {
                close();
            } catch (Throwable cleanupFailure) {
                e.addSuppressed(cleanupFailure);
            }
            throw e;
        }
    }

    @Override
    public RenderTarget getFramebuffer(OverlayFramebufferType type) {
        return type == OverlayFramebufferType.NORMAL && slot >= 0 ? hud[slot].object : null;
    }

    @Override
    public void beginFrame() {
        if (closed) return;
        slot = (slot + 1) % hud.length;
        OverlayFramebuffer target = hud[slot];
        // Minecraft's fences do not cover the layer's additional sampled-image read.
        // Wait only when reusing this slot, rather than serializing every present.
        bridge.waitHud(target.object.getColorTexture());
        clear(target);
        frameId++;
    }

    private static void clear(OverlayFramebuffer target) {
        RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(
                target.object.getColorTexture(), new Vector4f(0.0F),
                target.object.getDepthTexture(), 0.0);
        target.dirty = false;
    }

    @Override
    public void markDirty(OverlayFramebufferType type) {
        if (!closed && slot >= 0 && type == OverlayFramebufferType.NORMAL) hud[slot].dirty = true;
    }

    @Override
    public void finishFrame() {
        if (closed || slot < 0) return;
        OverlayFramebuffer target = hud[slot];
        // The GUI blend produces premultiplied RGB over transparent black. Publishing
        // is a CPU notification; all texture writes are in Minecraft's current submit.
        bridge.publishHud(target.dirty ? target.object.getColorTexture() : null, frameId, true);
    }

    @Override
    public void onResolutionChanged(Minecraft client) {
        if (closed) return;
        bridge.clearHud();
        int width = Math.max(1, client.getWindow().getWidth());
        int height = Math.max(1, client.getWindow().getHeight());
        for (OverlayFramebuffer target : hud) {
            target.object.resize(width, height);
            target.dirty = false;
        }
        // A resize can arrive after this frame's clear. Initialize its new attachment.
        if (slot >= 0) clear(hud[slot]);
    }

    @Override
    protected boolean supportsType(OverlayFramebufferType type) {
        return type == OverlayFramebufferType.NORMAL;
    }

    @Override
    public void beginDraw(OverlayFramebufferType type) {
        if (closed) return;
        drawScopes.addLast(type);
        if (type == OverlayFramebufferType.NORMAL) GuiOverlayManager.begin(false);
    }

    @Override
    public void beginEmptyDraw() {
        if (closed) return;
        drawScopes.addLast(OverlayFramebufferType.NORMAL);
        GuiOverlayManager.begin(true);
    }

    @Override
    public void endDraw() {
        if (drawScopes.isEmpty()) return;
        if (drawScopes.removeLast() == OverlayFramebufferType.NORMAL) GuiOverlayManager.end();
    }

    @Override
    public void close() {
        if (closed) return;
        // Do not release a sampled image if the native fence wait fails. Device teardown
        // will reclaim it, and OBSOverlay disables further rendering after this error.
        bridge.close();
        closed = true;
        drawScopes.clear();
        for (OverlayFramebuffer target : hud) {
            if (target != null) target.object.destroyBuffers();
        }
    }
}
