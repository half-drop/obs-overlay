package me.zziger.obsoverlay.mixin;

import me.zziger.obsoverlay.OBSOverlayConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EntityRenderer.class)
public class NameTagMixin {
    @Inject(at = @At("HEAD"), method = "render", cancellable = true)
    private void renderNameTags(S state, MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light, CallbackInfo ci) {
        // Check if name tags are enabled in the config
        if (OBSOverlayConfig.get().hidePlayerNameTags) {

        // Wrap rendering in OverlayRenderer for name tag overlay
        OverlayRenderer.beginDraw(AllDefaultOverlayComponents.nameTags);

        renderEntityNameTags(state, matrices, vertexConsumers, light);
        
        // End the drawing process
        OverlayRenderer.endDraw(AllDefaultOverlayComponents.nameTags);

        }
    }

}
