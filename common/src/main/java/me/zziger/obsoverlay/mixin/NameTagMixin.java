package me.zziger.obsoverlay.mixin;

import me.zziger.obsoverlay.OBSOverlayConfig;
import me.zziger.obsoverlay.OverlayRenderer;
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
public class NameTagMixin<T extends Entity> {
    @Inject(at = @At("HEAD"), method = "render", cancellable = true)
    private void renderNameTags(T entity, float yaw, float tickDelta, MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light, CallbackInfo ci) {
        // Check if name tags are enabled in the config
        if (OBSOverlayConfig.get().hidePlayerNameTags) {

        // Wrap rendering in OverlayRenderer for name tag overlay
        OverlayRenderer.beginDraw();

        renderNameTags(entity, yaw, tickDelta, matrices, vertexConsumers, light, ci);
        
        // End the drawing process
        OverlayRenderer.endDraw();

        }
    }

}
