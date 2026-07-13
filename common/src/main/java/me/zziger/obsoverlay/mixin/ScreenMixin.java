package me.zziger.obsoverlay.mixin;

import me.zziger.obsoverlay.OBSOverlayConfig;
import me.zziger.obsoverlay.ScreenOverlayRenderer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Screen.class)
public class ScreenMixin {
    @Inject(method = "extractBlurredBackground", at = @At("HEAD"), cancellable = true)
    private void renderBlur(CallbackInfo ci) {
        if (OBSOverlayConfig.isScreenOverlayed((Screen) (Object) this))
            ci.cancel();
    }

    @Inject(method = "extractRenderStateWithTooltipAndSubtitles", at = @At("HEAD"))
    private void obsOverlay$beforeExtract(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        ScreenOverlayRenderer.beforeScreenRender((Screen) (Object) this);
    }

    @Inject(method = "extractRenderStateWithTooltipAndSubtitles", at = @At("RETURN"))
    private void obsOverlay$afterExtract(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        ScreenOverlayRenderer.afterScreenRender((Screen) (Object) this);
    }
}
