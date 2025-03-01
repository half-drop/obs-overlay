package me.zziger.obsoverlay.mixin;

import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.entity.Entity;
import net.minecraft.text.Text;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import me.zziger.obsoverlay.OverlayRenderer;
import me.zziger.obsoverlay.registry.AllDefaultOverlayComponents;

@Mixin(EntityRenderer.class)
public class NameTagMixin {

    // 嵌套计数器，确保只在最外层保存和恢复 GL 状态
    private static int drawCounter = 0;

    @Inject(method = "renderLabelIfPresent(Lnet/minecraft/entity/Entity;Lnet/minecraft/text/Text;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;IF)V",
            at = @At("HEAD"))
    private void drawStart(Entity entity, Text text, MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light, float tickDelta, CallbackInfo ci) {
        // 只对有文字的情况做处理
        if (text == null || text.getString().trim().isEmpty()) {
            return;
        }
        // 第一次调用时保存当前 GL 状态，并启动覆盖层
        if (drawCounter == 0) {
            GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
            OverlayRenderer.beginDraw(AllDefaultOverlayComponents.nameTag);
        }
        drawCounter++;
    }

    @Inject(method = "renderLabelIfPresent(Lnet/minecraft/entity/Entity;Lnet/minecraft/text/Text;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;IF)V",
            at = @At("RETURN"))
    private void drawEnd(Entity entity, Text text, MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light, float tickDelta, CallbackInfo ci) {
        if (text == null || text.getString().trim().isEmpty()) {
            return;
        }
        drawCounter--;
        // 当所有嵌套调用结束后，结束覆盖层并恢复 GL 状态
        if (drawCounter == 0) {
            OverlayRenderer.endDraw(AllDefaultOverlayComponents.nameTag);
            GL11.glPopAttrib();
        }
    }
}
