package me.zziger.obsoverlay.mixin.components;

import me.zziger.obsoverlay.OBSOverlay;
import me.zziger.obsoverlay.component.AllDefaultOverlayComponents;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Hud;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.scores.Objective;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Hud.class)
public class InGameHudMixin {
    @Inject(method = "displayScoreboardSidebar(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/world/scores/Objective;)V", at = @At("HEAD"))
    private void drawStartScoreboard(GuiGraphicsExtractor drawContext, Objective objective, CallbackInfo ci) {
        OBSOverlay.getAPI().beginDraw(AllDefaultOverlayComponents.scoreboards);
    }

    @Inject(method = "displayScoreboardSidebar(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/world/scores/Objective;)V", at = @At("RETURN"))
    private void drawEndScoreboard(GuiGraphicsExtractor drawContext, Objective objective, CallbackInfo ci) {
        OBSOverlay.getAPI().endDraw(AllDefaultOverlayComponents.scoreboards);
    }

    @Inject(method = "extractOverlayMessage", at = @At("HEAD"))
    private void drawStartActionbar(GuiGraphicsExtractor context, DeltaTracker tickCounter, CallbackInfo ci) {
        OBSOverlay.getAPI().beginDraw(AllDefaultOverlayComponents.actionbar);
    }

    @Inject(method = "extractOverlayMessage", at = @At("RETURN"))
    private void drawEndActionbar(GuiGraphicsExtractor context, DeltaTracker tickCounter, CallbackInfo ci) {
        OBSOverlay.getAPI().endDraw(AllDefaultOverlayComponents.actionbar);
    }

    @Inject(method = "extractTitle", at = @At("HEAD"))
    private void drawStartTitleSubtitle(GuiGraphicsExtractor context, DeltaTracker tickCounter, CallbackInfo ci) {
        OBSOverlay.getAPI().beginDraw(AllDefaultOverlayComponents.titleSubtitle);
    }

    @Inject(method = "extractTitle", at = @At("RETURN"))
    private void drawEndTitleSubtitle(GuiGraphicsExtractor context, DeltaTracker tickCounter, CallbackInfo ci) {
        OBSOverlay.getAPI().endDraw(AllDefaultOverlayComponents.titleSubtitle);
    }

    @Inject(method = "extractEffects", at = @At("HEAD"))
    private void drawStartEffects(GuiGraphicsExtractor context, DeltaTracker tickCounter, CallbackInfo ci) {
        OBSOverlay.getAPI().beginDraw(AllDefaultOverlayComponents.effects);
    }

    @Inject(method = "extractEffects", at = @At("RETURN"))
    private void drawEndEffects(GuiGraphicsExtractor context, DeltaTracker tickCounter, CallbackInfo ci) {
        OBSOverlay.getAPI().endDraw(AllDefaultOverlayComponents.effects);
    }

}
