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
import me.zziger.obsoverlay.OBSOverlayConfig;

@Mixin(EntityRenderer.class)
public class NameTagMixin {

    // This method will be injected into the render method at the point where the name tag is being drawn.
    @Inject(method = "render(Lnet/minecraft/entity/Entity;FJJ)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/entity/EntityRenderer;renderLabelIfPresent(Lnet/minecraft/entity/Entity;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider$Immediate;I)V", shift = At.Shift.AFTER))
    private void drawNameTagStart(Entity entity, float yaw, double x, double y, double z, CallbackInfo ci) {
        // Begin drawing the overlay for name tag rendering
        OverlayRenderer.beginDraw(AllDefaultOverlayComponents.nameTag);
        
        // Clear OpenGL to prepare for rendering
        GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
    }

    // This method will be injected after the name tag rendering is done.
    @Inject(method = "render(Lnet/minecraft/entity/Entity;FJJ)V", at = @At("RETURN"))
    private void drawNameTagEnd(Entity entity, float yaw, double x, double y, double z, CallbackInfo ci) {
        // End drawing the overlay for name tag rendering
        OverlayRenderer.endDraw(AllDefaultOverlayComponents.nameTag);
    }
}
