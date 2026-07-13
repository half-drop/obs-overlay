package me.zziger.obsoverlay.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import me.zziger.obsoverlay.GuiOverlayManager;
import me.zziger.obsoverlay.OBSOverlay;
import me.zziger.obsoverlay.OBSOverlayConfig;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Gui.class)
public class GuiMixin {
    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void obsOverlay$extractTestIcon(DeltaTracker deltaTracker, boolean shouldRenderLevel, boolean resourcesLoaded,
                                            CallbackInfo ci, @Local GuiGraphicsExtractor graphics) {
        if (OBSOverlayConfig.get().showTestIcon && OBSOverlay.getIsInitialized()) {
            GuiOverlayManager.begin(false);
            try {
                graphics.blitSprite(RenderPipelines.GUI_TEXTURED, Identifier.withDefaultNamespace("icon/checkmark"), 0, 0, 16, 16);
            } finally {
                GuiOverlayManager.end();
            }
        }
    }
}
