package me.zziger.obsoverlay.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import me.zziger.obsoverlay.GuiOverlayManager;
import me.zziger.obsoverlay.OBSOverlay;
import me.zziger.obsoverlay.OverlayFramebufferType;
import me.zziger.obsoverlay.OverlayRenderer;
import me.zziger.obsoverlay.mixin.accessor.GuiRenderStateAccessor;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Mixin(GuiRenderer.class)
public abstract class GuiRendererMixin {
    @Shadow @Final private GuiRenderState renderState;

    private boolean obsOverlay$renderingPartition;
    private boolean obsOverlay$renderingOverlay;

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void obsOverlay$renderPartitions(CallbackInfo ci) {
        if (obsOverlay$renderingPartition) return;
        OverlayRenderer renderer = OBSOverlay.getRenderer();
        if (renderer == null) {
            GuiOverlayManager.clear();
            return;
        }

        GuiRenderStateAccessor accessor = (GuiRenderStateAccessor) renderState;
        List<Object> all = new ArrayList<>(accessor.obsOverlay$getRootLayers());
        Set<Object> itemModelIdentities = Set.copyOf(accessor.obsOverlay$getItemModelIdentities());
        int firstStratumAfterBlur = accessor.obsOverlay$getFirstStratumAfterBlur();
        int normalStrataBeforeBlur = 0;
        List<Object> normal = new ArrayList<>();
        List<Object> overlay = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            Object layer = all.get(i);
            if (GuiOverlayManager.isHiddenLayer(layer)) continue;
            if (GuiOverlayManager.isOverlayLayer(layer)) overlay.add(layer);
            else {
                normal.add(layer);
                if (i < firstStratumAfterBlur) normalStrataBeforeBlur++;
            }
        }
        if (overlay.isEmpty() && all.size() == normal.size()) return;

        obsOverlay$renderingPartition = true;
        try {
            accessor.obsOverlay$setRootLayers(normal);
            // The blur boundary indexes the original strata. Recompute it after
            // removing overlays so a visible menu still blurs only its background.
            accessor.obsOverlay$setFirstStratumAfterBlur(firstStratumAfterBlur == Integer.MAX_VALUE
                    ? Integer.MAX_VALUE : normalStrataBeforeBlur);
            ((GuiRenderer) (Object) this).render();

            if (!overlay.isEmpty()) {
                accessor.obsOverlay$setRootLayers(overlay);
                accessor.obsOverlay$getItemModelIdentities().addAll(itemModelIdentities);
                obsOverlay$renderingOverlay = true;
                ((GuiRenderer) (Object) this).render();
                renderer.markDirty(OverlayFramebufferType.NORMAL);
                obsOverlay$renderingOverlay = false;
            }
        } finally {
            obsOverlay$renderingOverlay = false;
            obsOverlay$renderingPartition = false;
            GuiOverlayManager.clear();
            // Reset also installs a current node in the new strata list for the
            // next extraction, rather than leaving it pointing at a discarded list.
            renderState.reset();
        }
        ci.cancel();
    }

    @Redirect(method = "draw", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;mainRenderTarget()Lcom/mojang/blaze3d/pipeline/RenderTarget;"))
    private RenderTarget obsOverlay$selectFramebuffer(GameRenderer gameRenderer) {
        OverlayRenderer renderer = OBSOverlay.getRenderer();
        if (obsOverlay$renderingOverlay && renderer != null) {
            return renderer.getFramebuffer(OverlayFramebufferType.NORMAL);
        }
        return gameRenderer.mainRenderTarget();
    }
}
