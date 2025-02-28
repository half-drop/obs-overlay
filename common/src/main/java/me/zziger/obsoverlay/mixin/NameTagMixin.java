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

    // 确保在 renderLabelIfPresent 被调用之前开始绘制
    @Inject(method = "renderLabelIfPresent", at = @At(value = "INVOKE", 
            target = "Lnet/minecraft/client/render/entity/EntityRenderer;renderLabelIfPresent(Lnet/minecraft/entity/Entity;Lnet/minecraft/text/Text;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;IF)V", 
            shift = At.Shift.AFTER))
    private void drawStart(Entity entity, Text text, MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light, float tickDelta, CallbackInfo ci) {
        // 开始绘制覆盖层
        OverlayRenderer.beginDraw(AllDefaultOverlayComponents.nameTag);
    }

    // 确保在 renderLabelIfPresent 完成后结束绘制
    @Inject(method = "renderLabelIfPresent", at = @At("RETURN"))
    private void drawEnd(Entity entity, Text text, MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light, float tickDelta, CallbackInfo ci) {
        // 结束绘制覆盖层
        OverlayRenderer.endDraw(AllDefaultOverlayComponents.nameTag);
    }
}
