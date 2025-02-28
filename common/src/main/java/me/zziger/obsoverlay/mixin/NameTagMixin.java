package me.zziger.obsoverlay.mixin;

import me.zziger.obsoverlay.OverlayUtils;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.state.EntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EntityRenderer.class)
public class NameTagMixin<T extends Entity, S extends EntityRenderState> {
    @Inject(at = @At("HEAD"), method = "render", cancellable = true)
    private void doNotRenderNametags(S state, MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light, CallbackInfo ci) {
        // Prevent name tags from rendering if the OverlayUtils condition is met
        if (OverlayUtils.shouldHideNametags((T) state.getEntity())) {
            ci.cancel();
        }
    }
}
