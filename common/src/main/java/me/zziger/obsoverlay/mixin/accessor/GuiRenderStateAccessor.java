package me.zziger.obsoverlay.mixin.accessor;

import net.minecraft.client.gui.render.state.GuiRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

@Mixin(GuiRenderState.class)
public interface GuiRenderStateAccessor {
    @Accessor("rootLayers")
    List<Object> obsOverlay$getRootLayers();

    @Mutable
    @Accessor("rootLayers")
    void obsOverlay$setRootLayers(List<Object> layers);

}
