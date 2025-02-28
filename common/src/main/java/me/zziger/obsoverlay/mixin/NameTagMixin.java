package me.zziger.obsoverlay.mixin;

import net.minecraft.client.MinecraftClient;
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

    // 注入 renderLabelIfPresent 方法以便在 name tag 渲染时插入自定义渲染
    @Inject(method = "renderLabelIfPresent(Lnet/minecraft/entity/Entity;Lnet/minecraft/text/Text;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;IF)V", 
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/entity/EntityRenderer;renderLabelIfPresent(Lnet/minecraft/entity/Entity;Lnet/minecraft/text/Text;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;IF)V", 
                    shift = At.Shift.AFTER))
    private void drawNameTagStart(Entity entity, Text text, MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light, float tickDelta, CallbackInfo ci) {
        // 开始绘制 name tag 的覆盖层
        OverlayRenderer.beginDraw(AllDefaultOverlayComponents.nameTag);
        
        // 清除 OpenGL 深度缓冲区，准备绘制
        GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
    }

    // 在 name tag 渲染结束后结束绘制
    @Inject(method = "renderLabelIfPresent(Lnet/minecraft/entity/Entity;Lnet/minecraft/text/Text;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;IF)V", 
            at = @At("RETURN"))
    private void drawNameTagEnd(Entity entity, Text text, MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light, float tickDelta, CallbackInfo ci) {
        // 结束绘制 name tag 的覆盖层
        OverlayRenderer.endDraw(AllDefaultOverlayComponents.nameTag);
    }
}
