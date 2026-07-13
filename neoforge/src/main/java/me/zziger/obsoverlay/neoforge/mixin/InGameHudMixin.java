package me.zziger.obsoverlay.neoforge.mixin;

import me.zziger.obsoverlay.OBSOverlay;
import me.zziger.obsoverlay.component.AllDefaultOverlayComponents;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Hud.class)
public class InGameHudMixin {
    @Inject(method = {"extractHotbar", "extractContextualInfoBarBackground", "extractExperienceLevel",
            "extractContextualInfoBar", "extractSelectedItemName", "extractPlayerHealth"}, at = @At("HEAD"))
    private void obsOverlay$beginMainHud(CallbackInfo ci) {
        OBSOverlay.getAPI().beginDraw(AllDefaultOverlayComponents.mainHud);
    }

    @Inject(method = {"extractHotbar", "extractContextualInfoBarBackground", "extractExperienceLevel",
            "extractContextualInfoBar", "extractSelectedItemName", "extractPlayerHealth"}, at = @At("RETURN"))
    private void obsOverlay$endMainHud(CallbackInfo ci) {
        OBSOverlay.getAPI().endDraw(AllDefaultOverlayComponents.mainHud);
    }

}
