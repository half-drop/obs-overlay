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

    // 嵌套计数器，确保每帧只调用一对 begin/end 操作
    private static int drawCounter = 0;

    @Inject(method = "renderLabelIfPresent(Lnet/minecraft/entity/Entity;Lnet/minecraft/text/Text;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;IF)V",
            at = @At("HEAD"))
    private void drawStart(Entity entity, Text text, MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light, float tickDelta, CallbackInfo ci) {
        // 只有当文本符合 Minecraft 用户名格式时，才进行自定义绘制操作
        if (!isMinecraftUsername(text)) {
            return;
        }
        if (drawCounter == 0) {
            OverlayRenderer.beginDraw(AllDefaultOverlayComponents.nameTag);
        }
        drawCounter++;
    }

    @Inject(method = "renderLabelIfPresent(Lnet/minecraft/entity/Entity;Lnet/minecraft/text/Text;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;IF)V",
            at = @At("RETURN"))
    private void drawEnd(Entity entity, Text text, MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light, float tickDelta, CallbackInfo ci) {
        if (!isMinecraftUsername(text)) {
            return;
        }
        drawCounter--;
        if (drawCounter == 0) {
            OverlayRenderer.endDraw(AllDefaultOverlayComponents.nameTag);
        }
    }

    /**
     * 判断给定的 Text 是否为有效的 Minecraft 用户名。
     * Minecraft 用户名要求长度在 3 到 16 之间，并且只包含字母、数字和下划线。
     */
    private boolean isMinecraftUsername(Text text) {
        if (text == null) return false;
        String name = text.getString().trim();
        return name.matches("^[A-Za-z0-9_]{3,16}$");
    }
}
