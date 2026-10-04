package me.zziger.obsoverlay.mixin;

import me.zziger.obsoverlay.GuiOverlayManager;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GuiRenderState.class)
public class GuiRenderStateMixin {
    @Inject(method = "nextStratum()V", at = @At("RETURN"))
    private void obsOverlay$inheritOverlayPolicy(CallbackInfo ci) {
        // Vanilla creates additional strata inside HUD components and screens,
        // including tooltips. Each one must retain the enclosing overlay policy.
        GuiOverlayManager.onNewStratum((GuiRenderState) (Object) this);
    }
}
