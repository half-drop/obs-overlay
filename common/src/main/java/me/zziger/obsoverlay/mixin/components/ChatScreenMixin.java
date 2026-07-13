package me.zziger.obsoverlay.mixin.components;

import me.zziger.obsoverlay.OBSOverlay;
import me.zziger.obsoverlay.component.AllDefaultOverlayComponents;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ChatScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChatScreen.class)
public class ChatScreenMixin {
    @Inject(method = "extractRenderState", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/ChatComponent;extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/gui/Font;IIILnet/minecraft/client/gui/components/ChatComponent$DisplayMode;Z)V", shift = At.Shift.AFTER))
    private void drawStart(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        OBSOverlay.getAPI().beginDraw(AllDefaultOverlayComponents.chatBar);
    }

    @Inject(method = "extractRenderState", at = @At("RETURN"))
    private void drawEnd(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        OBSOverlay.getAPI().endDraw(AllDefaultOverlayComponents.chatBar);
    }
}
