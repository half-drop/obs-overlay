package me.zziger.obsoverlay.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import me.zziger.obsoverlay.GuiDeferredPolicyAccess;
import me.zziger.obsoverlay.GuiOverlayManager;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.network.chat.Style;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GuiGraphicsExtractor.class)
public abstract class GuiGraphicsExtractorMixin implements GuiDeferredPolicyAccess {
    @Unique private Boolean obsOverlay$tooltipHiddenPolicy;
    @Unique private Boolean obsOverlay$preeditHiddenPolicy;
    @Unique private Boolean obsOverlay$hoveredTextHiddenPolicy;

    @Inject(method = "setTooltipForNextFrameInternal(Lnet/minecraft/client/gui/Font;Ljava/util/List;IILnet/minecraft/client/gui/screens/inventory/tooltip/ClientTooltipPositioner;Lnet/minecraft/resources/Identifier;ZZ)V",
            at = @At(value = "FIELD", target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;deferredTooltip:Ljava/lang/Runnable;", opcode = Opcodes.PUTFIELD, shift = At.Shift.AFTER))
    private void obsOverlay$captureTooltipPolicy(CallbackInfo ci) {
        // Record only an accepted tooltip; a rejected replacement must retain the previous policy.
        obsOverlay$tooltipHiddenPolicy = GuiOverlayManager.currentHiddenPolicy();
    }

    @Inject(method = "setPreeditOverlay(Lnet/minecraft/client/gui/components/Renderable;)V", at = @At("RETURN"))
    private void obsOverlay$capturePreeditPolicy(CallbackInfo ci) {
        obsOverlay$preeditHiddenPolicy = GuiOverlayManager.currentHiddenPolicy();
    }

    @Override
    public void obsOverlay$setHoveredTextHiddenPolicy(Boolean hidden) {
        obsOverlay$hoveredTextHiddenPolicy = hidden;
    }

    @WrapOperation(method = "extractDeferredElements(IIF)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;componentHoverEffect(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Style;II)V"))
    private void obsOverlay$extractHoverWithPolicy(GuiGraphicsExtractor graphics, Font font, Style style,
                                                  int mouseX, int mouseY, Operation<Void> original) {
        // Hover text creates its tooltip here, after the originating component's scope has ended.
        GuiOverlayManager.runWithPolicy(obsOverlay$hoveredTextHiddenPolicy,
                () -> original.call(graphics, font, style, mouseX, mouseY));
    }

    @WrapOperation(method = "extractDeferredElements(IIF)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/Renderable;extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V"))
    private void obsOverlay$extractPreeditWithPolicy(Renderable preedit, GuiGraphicsExtractor graphics,
                                                    int mouseX, int mouseY, float delta, Operation<Void> original) {
        GuiOverlayManager.runWithPolicy(obsOverlay$preeditHiddenPolicy,
                () -> original.call(preedit, graphics, mouseX, mouseY, delta));
    }

    @WrapOperation(method = "extractDeferredElements(IIF)V",
            at = @At(value = "INVOKE", target = "Ljava/lang/Runnable;run()V"))
    private void obsOverlay$extractTooltipWithPolicy(Runnable tooltip, Operation<Void> original) {
        GuiOverlayManager.runWithPolicy(obsOverlay$tooltipHiddenPolicy, () -> original.call(tooltip));
    }

    @Mixin(targets = "net.minecraft.client.gui.GuiGraphicsExtractor$RenderingTextCollector")
    public static class RenderingTextCollectorMixin {
        @Shadow @Final private GuiGraphicsExtractor this$0;

        @Inject(method = "accept(Lnet/minecraft/network/chat/Style;)V",
                at = @At(value = "FIELD", target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;hoveredTextStyle:Lnet/minecraft/network/chat/Style;", opcode = Opcodes.PUTFIELD, shift = At.Shift.AFTER))
        private void obsOverlay$captureHoverPolicy(CallbackInfo ci) {
            ((GuiDeferredPolicyAccess) this$0).obsOverlay$setHoveredTextHiddenPolicy(GuiOverlayManager.currentHiddenPolicy());
        }
    }
}
