package me.zziger.obsoverlay.mixin.accessor;

import net.minecraft.client.renderer.state.gui.GuiRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;
import java.util.Set;

@Mixin(GuiRenderState.class)
public interface GuiRenderStateAccessor {
    @Accessor("strata")
    List<Object> obsOverlay$getRootLayers();

    @Mutable
    @Accessor("strata")
    void obsOverlay$setRootLayers(List<Object> layers);

    @Accessor("itemModelIdentities")
    Set<Object> obsOverlay$getItemModelIdentities();

}
