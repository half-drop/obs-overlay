package me.zziger.obsoverlay.mixin;

import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import me.zziger.obsoverlay.OverlayRenderer;
import me.zziger.obsoverlay.OBSOverlayConfig;
import java.lang.reflect.Method; // Import the Method class from java.lang.reflect

@Mixin(EntityRenderer.class)
public class NameTagMixin<T extends Entity> {

    private Method renderLabelIfPresentMethod;

    @Inject(at = @At("HEAD"), method = "render", cancellable = true)
    private void renderNameTags(T entity, float yaw, float tickDelta, MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light, CallbackInfo ci) {
        // If player name tags should be hidden according to the config, cancel the render
        if (OBSOverlayConfig.get().hidePlayerNameTags) {
            ci.cancel(); // Cancel the default name tag rendering
            return; // Exit early
        }

        // Wrap the rendering of name tags inside the OverlayRenderer for custom overlay rendering
        OverlayRenderer.beginDraw();

        // Call the method to render the name tag, passing the necessary parameters
        this.renderNameTag(entity, matrices, vertexConsumers, light, tickDelta);

        // End the drawing process for the overlay
        OverlayRenderer.endDraw();
    }

    // Implement the method for rendering name tags
    private void renderNameTag(T entity, MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light, float tickDelta) {
        if (entity.hasCustomName()) {
            Text name = entity.getCustomName(); // Fetch the custom name of the entity

            // Use reflection to call the protected renderLabelIfPresent method
            try {
                if (renderLabelIfPresentMethod == null) {
                    renderLabelIfPresentMethod = EntityRenderer.class.getDeclaredMethod("renderLabelIfPresent", Entity.class, Text.class, MatrixStack.class, VertexConsumerProvider.class, int.class, float.class);
                    renderLabelIfPresentMethod.setAccessible(true); // Make the method accessible
                }

                // Invoke the method on the current EntityRenderer instance
                renderLabelIfPresentMethod.invoke(this, entity, name, matrices, vertexConsumers, light, tickDelta);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}
