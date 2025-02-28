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

    // 嵌套计数器：用于跟踪当前正在渲染的名字标签嵌套数量
    private static int drawCounter = 0;

    /**
     * 在每个名字标签开始渲染时调用，
     * 如果当前是第一层调用，则启动覆盖层绘制。
     */
    @Inject(method = "renderLabelIfPresent(Lnet/minecraft/entity/Entity;Lnet/minecraft/text/Text;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;IF)V", 
            at = @At("HEAD"))
    private void drawStart(Entity entity, Text text, MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light, float tickDelta, CallbackInfo ci) {
        if (drawCounter == 0) {
            OverlayRenderer.beginDraw(AllDefaultOverlayComponents.nameTag);
        }
        drawCounter++;
    }

    /**
     * 在每个名字标签渲染结束时调用，
     * 当所有嵌套调用结束后，结束覆盖层绘制。
     */
    @Inject(method = "renderLabelIfPresent(Lnet/minecraft/entity/Entity;Lnet/minecraft/text/Text;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;IF)V", 
            at = @At("RETURN"))
    private void drawEnd(Entity entity, Text text, MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light, float tickDelta, CallbackInfo ci) {
        drawCounter--;
        if (drawCounter == 0) {
            OverlayRenderer.endDraw(AllDefaultOverlayComponents.nameTag);
        }
    }
}
