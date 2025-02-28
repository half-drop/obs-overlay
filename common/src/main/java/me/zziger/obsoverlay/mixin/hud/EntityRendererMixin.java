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
public abstract class EntityRendererMixin<T extends Entity> {

    @Shadow
    @Final
    protected EntityRenderDispatcher dispatcher;

    // Intercepting the method responsible for rendering name tags
    @Inject(at = @At("HEAD"), method = "renderLabelIfPresent", cancellable = true)
    private void onRenderLabelIfPresent(Text text, MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light, CallbackInfo ci) {
        // Check if the name tag hiding option is enabled
        if (OBSOverlayConfig.get().hidePlayerNameTags) {
            // Skip rendering the name tag for the current entity
            ci.cancel();
        }
    }

    @Shadow
    public abstract TextRenderer getTextRenderer();
}
