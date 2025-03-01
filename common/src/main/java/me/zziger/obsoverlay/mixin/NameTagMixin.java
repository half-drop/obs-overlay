package me.zziger.obsoverlay.mixin;

import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.entity.Entity;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import me.zziger.obsoverlay.OverlayRenderer;
import me.zziger.obsoverlay.registry.AllDefaultOverlayComponents;

@Mixin(EntityRenderer.class)
public class NameTagMixin {

    @Inject(method = "renderLabelIfPresent(Lnet/minecraft/entity/Entity;Lnet/minecraft/text/Text;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;IF)V",at = @At("HEAD"))
    private void drawStart(CallbackInfo ci) {
         if (!isMinecraftUsername(text)) return;
         OverlayRenderer.beginDraw(AllDefaultOverlayComponents.nameTag);
    }

    @Inject(method = "renderLabelIfPresent(Lnet/minecraft/entity/Entity;Lnet/minecraft/text/Text;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;IF)V",at = @At("RETURN"))
    private void drawEnd(CallbackInfo ci) {
         if (!isMinecraftUsername(text)) return;
         OverlayRenderer.endDraw(AllDefaultOverlayComponents.nameTag);
    }

    private boolean isMinecraftUsername(Text text) {
        if (text == null) return false;
        String name = text.getString();
        return name.matches("^[A-Za-z0-9_]{3,16}$");
    }
}
