package me.zziger.obsoverlay.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import me.zziger.obsoverlay.*;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.util.Identifier;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public class GameRendererMixin {
    @Inject(method = "renderHand(FZLorg/joml/Matrix4f;)V", at = @At(value = "HEAD"))
    private void renderHand(float tickDelta, boolean sleeping, Matrix4f matrix4f, CallbackInfo ci) {
        OverlayRenderer renderer = OBSOverlay.getRenderer();
        if (renderer != null) renderer.renderingHands = true;
        OBSOverlay.getAPI().backupDepth(true);
    }

    @Inject(method = "renderHand(FZLorg/joml/Matrix4f;)V", at = @At(value = "RETURN"))
    private void renderHandEnd(float tickDelta, boolean sleeping, Matrix4f matrix4f, CallbackInfo ci) {
        OverlayRenderer renderer = OBSOverlay.getRenderer();
        if (renderer != null) renderer.renderingHands = false;
        OBSOverlay.getAPI().backupDepth(true);
    }

    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/render/GuiRenderer;render(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;)V", shift = At.Shift.BEFORE))
    private void renderTestIcon(RenderTickCounter tickCounter, boolean tick, CallbackInfo ci, @Local() DrawContext instance) {
        if (OBSOverlayConfig.get().showTestIcon && OBSOverlay.getIsInitialized()) {
            GuiOverlayManager.begin(false);
            try {
                instance.drawGuiTexture(RenderPipelines.GUI_TEXTURED, Identifier.ofVanilla("icon/checkmark"), 0, 0, 16, 16);
            } catch (Exception ignored) {
            }
            GuiOverlayManager.end();
        }
    }
}
