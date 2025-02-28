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

@Mixin(EntityRenderer.class) // 修改为EntityRenderer，适用于所有实体
public class NameTagMixin {

    // 在 name tag 渲染前开始绘制覆盖层
    @Inject(method = "renderLabelIfPresent", at = @At("HEAD"))
    private void drawStart(Entity entity, Text text, MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light, float tickDelta, CallbackInfo ci) {
        // 开始绘制覆盖层
        OverlayRenderer.beginDraw(AllDefaultOverlayComponents.nameTag);
    }

    // 在 name tag 渲染结束后结束覆盖层绘制
    @Inject(method = "renderLabelIfPresent", at = @At("RETURN"))
    private void drawEnd(Entity entity, Text text, MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light, float tickDelta, CallbackInfo ci) {
        // 结束绘制覆盖层
        OverlayRenderer.endDraw(AllDefaultOverlayComponents.nameTag);
    }
}
