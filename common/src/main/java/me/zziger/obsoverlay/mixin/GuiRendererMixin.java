package me.zziger.obsoverlay.mixin;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import me.zziger.obsoverlay.GuiOverlayManager;
import me.zziger.obsoverlay.OBSOverlay;
import me.zziger.obsoverlay.OverlayFramebufferType;
import me.zziger.obsoverlay.OverlayRenderer;
import me.zziger.obsoverlay.mixin.accessor.GuiRenderStateAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.gui.render.state.GuiRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

@Mixin(GuiRenderer.class)
public abstract class GuiRendererMixin {
    @Shadow @Final private GuiRenderState state;

    private boolean obsOverlay$renderingPartition;
    private boolean obsOverlay$renderingOverlay;

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void obsOverlay$renderPartitions(GpuBufferSlice fogBuffer, CallbackInfo ci) {
        if (obsOverlay$renderingPartition) return;

        GuiRenderStateAccessor accessor = (GuiRenderStateAccessor) state;
        List<Object> all = new ArrayList<>(accessor.obsOverlay$getRootLayers());
        List<Object> normal = new ArrayList<>();
        List<Object> overlay = new ArrayList<>();
        for (Object layer : all) {
            if (GuiOverlayManager.isHiddenLayer(layer)) continue;
            if (GuiOverlayManager.isOverlayLayer(layer)) overlay.add(layer);
            else normal.add(layer);
        }
        if (overlay.isEmpty() && all.size() == normal.size()) return;

        obsOverlay$renderingPartition = true;
        try {
            accessor.obsOverlay$setRootLayers(normal);
            ((GuiRenderer) (Object) this).render(fogBuffer);

            if (!overlay.isEmpty()) {
                accessor.obsOverlay$setRootLayers(overlay);
                obsOverlay$renderingOverlay = true;
                OverlayRenderer renderer = OBSOverlay.getRenderer();
                if (renderer != null) renderer.markDirty(OverlayFramebufferType.NORMAL);
                ((GuiRenderer) (Object) this).render(fogBuffer);
                obsOverlay$renderingOverlay = false;
            }
        } finally {
            obsOverlay$renderingOverlay = false;
            obsOverlay$renderingPartition = false;
            accessor.obsOverlay$setRootLayers(new ArrayList<>());
            GuiOverlayManager.clear();
        }
        ci.cancel();
    }

    @Redirect(method = "renderPreparedDraws", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/MinecraftClient;getFramebuffer()Lnet/minecraft/client/gl/Framebuffer;"))
    private Framebuffer obsOverlay$selectFramebuffer(MinecraftClient client) {
        OverlayRenderer renderer = OBSOverlay.getRenderer();
        if (obsOverlay$renderingOverlay && renderer != null) {
            return renderer.getFramebuffer(OverlayFramebufferType.NORMAL);
        }
        return client.getFramebuffer();
    }
}
