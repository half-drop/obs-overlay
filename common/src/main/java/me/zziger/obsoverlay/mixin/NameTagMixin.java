package me.zziger.obsoverlay.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.PlayerEntityRenderer;
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
    @Inject(method = "renderLabelIfPresent(Lnet/minecraft/entity/Entity;Lnet/minecraft/text/Text;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;IF)V", 
            at = @At("HEAD"))
    private void drawStart(CallbackInfo ci) {
        // 开始绘制覆盖层
        OverlayRenderer.beginDraw(AllDefaultOverlayComponents.nameTag);
    }

    // 在 renderLabelIfPresent 方法结束时插入代码
    @Inject(method = "renderLabelIfPresent(Lnet/minecraft/entity/Entity;Lnet/minecraft/text/Text;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;IF)V", 
            at = @At("RETURN"))
    private void drawEnd(CallbackInfo ci) {
        // 结束绘制覆盖层
        OverlayRenderer.endDraw(AllDefaultOverlayComponents.nameTag);
    }
    
}
