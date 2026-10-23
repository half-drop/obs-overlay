package me.zziger.obsoverlay.mixin;

import me.zziger.obsoverlay.OBSOverlay;
import me.zziger.obsoverlay.OverlayRenderer;
import me.zziger.obsoverlay.error.OverlayHookException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.main.GameConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class MinecraftClientMixin {
    @Inject(method = "<init>", at = @At("RETURN"))
    private void constructor(GameConfig args, CallbackInfo ci) {
        OBSOverlay.initRender();
    }

    @Inject(method = "framebufferSizeChanged", at = @At("RETURN"))
    private void onResolutionChanged(CallbackInfo ci) {
        OverlayRenderer renderer = OBSOverlay.getRenderer();
        if (renderer != null) {
            try {
                renderer.onResolutionChanged((Minecraft)(Object)this);
            } catch (OverlayHookException error) {
                OBSOverlay.handleRenderFailure(error);
            }
        }
    }

    @Inject(method = "renderFrame(Z)V", at = @At("HEAD"))
    private void onRender(boolean tick, CallbackInfo ci) {
        OverlayRenderer renderer = OBSOverlay.getRenderer();
        if (renderer != null) {
            try {
                renderer.beginFrame();
            } catch (OverlayHookException error) {
                OBSOverlay.handleRenderFailure(error);
            }
        }
    }

    @Inject(method = "close()V", at = @At("HEAD"))
    private void obsOverlay$closeRenderer(CallbackInfo ci) {
        OBSOverlay.shutdownRender();
    }
}
