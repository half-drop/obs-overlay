package me.zziger.obsoverlay.mixin;

import me.zziger.obsoverlay.OBSOverlayConfig;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderDispatcher;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EntityRenderer.class)
public abstract class NameTagMixin {

    @Shadow
    @Final
    protected EntityRenderDispatcher dispatcher;

    @Inject(at = @At("HEAD"), method = "renderLabelIfPresent(Lnet/minecraft/client/render/entity/state/EntityRenderState;Lnet/minecraft/text/Text;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;I)V", cancellable = true)
    private void onRenderLabelIfPresent(EntityRenderState state, Text text, MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light, CallbackInfo ci) {
        if (OBSOverlayConfig.get().hidePlayerNameTags) {
            ci.cancel();
        }
    }

    @Shadow
    public abstract TextRenderer getTextRenderer();
}
