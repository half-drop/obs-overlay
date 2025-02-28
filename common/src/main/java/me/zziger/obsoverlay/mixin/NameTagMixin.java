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
    private void renderNameTags(T entity, Text text, MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light, float tickDelta, CallbackInfo ci) {
        // Check if name tags should be hidden in the config
        if (OBSOverlayConfig.get().hidePlayerNameTags) {
            ci.cancel(); // Cancel the default rendering if name tags should be hidden
            return; // Exit early
        }

        // Wrap rendering in OverlayRenderer for name tag overlay
        OverlayRenderer.beginDraw();
        
        // Call the method to render name tags, this could be the original renderLabelIfPresent or any other custom logic
        entity.renderLabelIfPresent(entity, text, matrices, vertexConsumers, light, tickDelta);

        // End the drawing process for the overlay
        OverlayRenderer.endDraw();   
    }
}
